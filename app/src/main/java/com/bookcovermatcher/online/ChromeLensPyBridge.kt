package com.bookcovermatcher.online

import com.chaquo.python.PyException
import com.chaquo.python.PyObject

/**
 * The Kotlin side of the `chrome-lens-py` bridge.
 *
 * Talks to `app/src/main/python/lens_bridge.py`, which calls the unmodified upstream
 * `chrome_lens_py.LensAPI`. This class does exactly three things: hand over the captured bytes,
 * read the returned Python **dict** into plain Kotlin values, and hand both to
 * [OcrResultParser]. It holds no parsing rules and no error wording of its own, so the whole
 * contract lives in one testable file instead of behind a native boundary.
 *
 * ### Reading a Python dict
 *
 * `PyObject.get(String)` is **not** dictionary lookup. The Chaquopy Javadoc for it says
 * "Equivalent to Python `getattr()` … when the attribute does not exist, this method returns
 * `null`". A `dict` has no attribute called `text`, so reading results that way silently yields
 * `null` for every field — the second bug this file used to contain. Item lookup goes through
 * [PyObject.asMap], exactly as Chaquopy's own examples do for `os.environ[...]`.
 *
 * ### Passing a byte array
 *
 * A Kotlin `ByteArray` arrives in Python as Chaquopy's `java.chaquopy.jarray`: a buffer-protocol
 * object, not a Python `bytes`. `lens_bridge.as_image_bytes` converts it. Nothing here needs to
 * know that, but nothing here should assume otherwise either.
 */
class ChromeLensPyBridge(
    private val runtime: PythonRuntime,
    private val moduleName: String = MODULE,
) : OcrTextSource {

    override fun availability(): OcrAvailability {
        val python = runtime.python()
            ?: return OcrAvailability.Unavailable(
                OcrFailure.RUNTIME_MISSING,
                runtime.failureReason
                    ?: "The embedded Python runtime could not be started on this device.",
            )
        return try {
            parseAvailability(python.getModule(moduleName).callAttr("capabilities"))
        } catch (e: PyException) {
            // getModule() runs lens_bridge's `import chrome_lens_py`, so an import failure of the
            // library or of Pillow / httpx / protobuf lands here. This is the one place where the
            // exception text is still the only signal, so it is reported rather than parsed.
            OcrAvailability.Unavailable(
                OcrFailure.LIBRARY_MISSING,
                "The embedded chrome-lens-py package could not be loaded: ${summarize(e.message)}",
            )
        } catch (t: Throwable) {
            OcrAvailability.Unavailable(OcrFailure.RUNTIME_MISSING, summarize(t.message))
        }
    }

    override fun recognise(
        imageBytes: ByteArray,
        ocrLanguage: String?,
        targetLanguage: String?,
        timeoutSeconds: Int,
    ): OcrText {
        val source = ocrLanguage?.trim()?.takeIf { it.isNotEmpty() }
        val destination = targetLanguage?.trim()?.takeIf { it.isNotEmpty() }

        val outcome = runCatching { call(imageBytes, source, destination, timeoutSeconds) }

        outcome.exceptionOrNull()?.let { error ->
            val ocr = error as? OcrException ?: OcrException(
                failure = OcrFailure.INTERNAL,
                message = OcrErrorMessages.detail(OcrFailure.INTERNAL),
                detail = error.message,
                cause = error,
            )
            OnlineSearchLog.ocrFailed(imageBytes.size, ocr)
            throw ocr
        }

        val text = outcome.getOrThrow()
        OnlineSearchLog.ocrAttempt(
            byteCount = imageBytes.size,
            language = source,
            target = destination,
            outcome = "ok blocks=${text.blockCount} words=${text.wordCount} " +
                "chars=${text.text.length} lens=${text.libraryVersion}",
        )
        return text
    }


    // -----------------------------------------------------------------------------------------
    // The only code in the app that touches Chaquopy types
    // -----------------------------------------------------------------------------------------

    private fun call(
        imageBytes: ByteArray,
        source: String?,
        destination: String?,
        timeoutSeconds: Int,
    ): OcrText {
        val python = runtime.python()
            ?: throw OcrException(
                failure = OcrFailure.RUNTIME_MISSING,
                message = OcrErrorMessages.detail(OcrFailure.RUNTIME_MISSING),
                detail = runtime.failureReason,
            )

        val envelope = try {
            python.getModule(moduleName)
                .callAttr(FUNCTION, imageBytes, source, destination, timeoutSeconds)
        } catch (e: PyException) {
            // The bridge reports operational failures as envelopes, so a PyException here means
            // the call never returned: an import error, or a raise inside chrome-lens-py itself.
            // Its text is the only signal available, which is why it is kept as `detail`.
            val text = e.message.orEmpty()
            val category = if (text.contains("ImportError") || text.contains("ModuleNotFoundError")) {
                OcrFailure.LIBRARY_MISSING
            } else {
                OcrFailure.INTERNAL
            }
            throw OcrException(
                failure = category,
                message = OcrErrorMessages.detail(category),
                detail = text,
                cause = e,
            )
        } ?: throw OcrException(
            failure = OcrFailure.INTERNAL,
            message = OcrErrorMessages.detail(OcrFailure.INTERNAL),
            detail = "callAttr($FUNCTION) returned null",
        )

        return OcrResultParser.parseOcr(envelope.asPlainMap())
    }

    private fun parseAvailability(result: PyObject): OcrAvailability =
        OcrResultParser.parseAvailability(result.asPlainMap())

    /**
     * Python dict -> `Map<String, String?>`, by **item** lookup.
     *
     * `asMap()` is the item view; `PyObject.get(String)` would be `getattr()` and yield `null`
     * for every key. Chaquopy represents Python `None` as Java `null`, and `toString()` is
     * documented as Python `str()`, so scalars arrive in the shape [OcrResultParser] expects.
     */
    private fun PyObject.asPlainMap(): Map<String, String?> {
        @Suppress("UNCHECKED_CAST")
        val items = asMap() as Map<Any?, PyObject?>
        val out = LinkedHashMap<String, String?>(items.size)
        for ((key, value) in items) {
            if (key != null) out[key.toString()] = value?.toString()
        }
        return out
    }

    /** First non-blank line, capped: tracebacks are long and Logcat and the UI are not for them. */
    private fun summarize(message: String?): String {
        val line = message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (line.length <= 200) line else line.substring(0, 200) + "…"
    }

    private companion object {
        /** The module in `app/src/main/python/`. */
        const val MODULE = "lens_bridge"
        const val FUNCTION = "ocr_image"
    }
}
