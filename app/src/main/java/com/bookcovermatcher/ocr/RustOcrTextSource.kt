package com.bookcovermatcher.ocr

import com.bookcovermatcher.core.vision.PixelImage
import com.bookcovermatcher.online.OcrAvailability
import com.bookcovermatcher.online.OcrErrorMessages
import com.bookcovermatcher.online.OcrException
import com.bookcovermatcher.online.OcrFailure
import com.bookcovermatcher.online.OcrText
import com.bookcovermatcher.online.OcrTextSource

/**
 * The native PP-OCRv6 engine, behind the existing [OcrTextSource] contract.
 *
 * Kotlin hands over pixels, Rust returns text, and this class owns the parts that are easy to get
 * wrong: when the engine is opened, when it is closed, what a failure means, and what a score is.
 *
 * ### Lifecycle
 *
 * The engine is opened lazily on the first [recognise] and reused until [close]. Opening costs
 * ~0.4–2 s (two ONNX sessions, 139 MB of weights), so doing it in [availability] would make a check
 * that is meant to be cheap slow; instead `availability` checks the library, the models and their
 * recorded verification, and says whether opening *would* work.
 *
 * All native calls are serialized on one lock: the Rust side serializes inference anyway (ONNX
 * Runtime does not allow concurrent `run` on one session), and `create`/`destroy` must not race with
 * a call. A second [recognise] waits rather than corrupting the session.
 *
 * ### Cancellation
 *
 * ONNX Runtime's C API cannot abort a `run` already in flight, so cancelling the coroutine does not
 * stop inference — it discards the result. The engine stays consistent; the wasted work is one image.
 * [timeoutSeconds] is accepted for contract compatibility and deliberately ignored, because a timeout
 * that cannot interrupt the native call would only lie about what happened.
 *
 * ### Scores and geometry
 *
 * A line score is the mean CTC probability of the emitted characters (0..1), the same meaning the
 * Python bridge's confidence has. `blockHeights` are each line's quad height divided by the image
 * height — the normalised "how big is this text" measure the query extractor already uses to tell a
 * title from a banner.
 *
 * ### What it does not do
 *
 * PP-OCRv6 is not language-conditioned, so [OcrText.detectedLanguage] is always `null` rather than a
 * guess, and there is no translation: [OcrText.translatedText] is `null` for the same reason.
 */
class RustOcrTextSource(
    private val models: PpocrModels,
    /**
     * Where to load `libonnxruntime.so` from. Empty means "resolve it by soname", which is what
     * Android requires: app libraries live in a per-app linker namespace and a native `dlopen` by
     * absolute path is refused there.
     */
    private val runtimeLibraryPath: String,
    private val native: NativeOcrApi = NativeOcr,
    /** Long edge the picture is decoded at; the detector resizes to 736 internally anyway. */
    private val maxEdge: Int = DEFAULT_MAX_EDGE,
    /** Decodes JPEG/PNG bytes to pixels. Injected so this class stays JVM-testable. */
    private val decode: (ByteArray, Int) -> PixelImage?,
) : OcrTextSource, AutoCloseable {

    private val lock = Any()
    private var handle: Long = 0L
    private var engineVersion: String? = null

    override fun availability(): OcrAvailability {
        if (!native.isLoaded) {
            return OcrAvailability.Unavailable(
                OcrFailure.OCR_ENGINE_MISSING,
                "The native OCR library is not in this build (${native.loadFailure ?: "unknown reason"}).",
            )
        }
        val version = runCatching { native.version() }.getOrElse { failure ->
            return OcrAvailability.Unavailable(
                OcrFailure.OCR_ENGINE_MISSING,
                "The native OCR library loaded but could not be used: ${failure.message}",
            )
        }
        return when (val state = models.state()) {
            is ModelState.Ready -> OcrAvailability.Ready(LIBRARY_NAME, "$version · ${state.models.fingerprint}")
            ModelState.Absent -> OcrAvailability.Unavailable(
                OcrFailure.OCR_MODELS_MISSING,
                "The PP-OCRv6 models are not installed on this device. See native/ocr/README.md.",
            )
            is ModelState.Invalid -> OcrAvailability.Unavailable(OcrFailure.OCR_MODELS_MISSING, state.reason)
        }
    }

    /**
     * @throws OcrException with the category the UI already knows how to phrase. A cover with no
     *   text is a **successful** empty [OcrText], not an exception.
     */
    override fun recognise(
        imageBytes: ByteArray,
        ocrLanguage: String?,
        targetLanguage: String?,
        timeoutSeconds: Int,
    ): OcrText {
        if (imageBytes.isEmpty()) {
            throw OcrException(OcrFailure.BAD_IMAGE, OcrErrorMessages.detail(OcrFailure.BAD_IMAGE), "empty capture")
        }
        synchronized(lock) {
            val installed = installedModels()
            val image = decode(imageBytes, maxEdge)
                ?: throw OcrException(
                    OcrFailure.BAD_IMAGE,
                    OcrErrorMessages.detail(OcrFailure.BAD_IMAGE),
                    "the capture could not be decoded to pixels",
                )
            val handle = handleLocked(installed)
            val payload = try {
                native.recognise(handle, image.argb, image.width, image.height)
            } catch (t: Throwable) {
                throw OcrException(
                    OcrFailure.INTERNAL,
                    OcrErrorMessages.detail(OcrFailure.INTERNAL),
                    "native recognise failed: ${t.message}",
                    t,
                )
            }
            val result = try {
                NativeOcrProtocol.parseResult(payload)
            } catch (e: IllegalArgumentException) {
                throw OcrException(
                    OcrFailure.INTERNAL,
                    OcrErrorMessages.detail(OcrFailure.INTERNAL),
                    "unreadable native result: ${e.message}",
                    e,
                )
            }
            // Regions that failed individually are reported only when nothing at all was read; a
            // partial read is still a useful OCR result.
            if (result.lines.isEmpty() && result.errors.isNotEmpty()) {
                throw OcrException(
                    OcrFailure.INTERNAL,
                    OcrErrorMessages.detail(OcrFailure.INTERNAL),
                    result.errors.joinToString("; "),
                )
            }
            return toOcrText(result, image.height)
        }
    }

    /** The raw `BC-OCR-INFO 1` payload. Diagnostics and tests only; opens the engine if needed. */
    fun describePayload(): String = synchronized(lock) {
        val handle = handleLocked(installedModels())
        native.describe(handle)
    }

    override fun close() {
        synchronized(lock) {
            if (handle != 0L) {
                runCatching { native.destroy(handle) }
                handle = 0L
            }
        }
    }

    private fun installedModels(): PpocrModels.Installed = when (val state = models.state()) {
        is ModelState.Ready -> state.models
        ModelState.Absent -> throw OcrException(
            OcrFailure.OCR_MODELS_MISSING,
            OcrErrorMessages.detail(OcrFailure.OCR_MODELS_MISSING),
            "no PP-OCRv6 models are installed",
        )
        is ModelState.Invalid -> throw OcrException(
            OcrFailure.OCR_MODELS_MISSING,
            OcrErrorMessages.detail(OcrFailure.OCR_MODELS_MISSING),
            state.reason,
        )
    }

    private fun handleLocked(installed: PpocrModels.Installed): Long {
        if (handle != 0L) return handle
        if (!native.isLoaded) {
            throw OcrException(
                OcrFailure.OCR_ENGINE_MISSING,
                OcrErrorMessages.detail(OcrFailure.OCR_ENGINE_MISSING),
                native.loadFailure ?: "libbc_ocr.so is missing",
            )
        }
        val created = try {
            native.create(
                installed.detector.absolutePath,
                installed.recognizer.absolutePath,
                installed.dictionary.absolutePath,
                runtimeLibraryPath,
            )
        } catch (t: Throwable) {
            throw OcrException(
                OcrFailure.RUNTIME_MISSING,
                OcrErrorMessages.detail(OcrFailure.RUNTIME_MISSING),
                "native create failed: ${t.message}",
                t,
            )
        }
        if (created <= 0L) {
            // The Rust side throws before returning 0; this is the belt-and-braces path.
            throw OcrException(
                OcrFailure.RUNTIME_MISSING,
                OcrErrorMessages.detail(OcrFailure.RUNTIME_MISSING),
                "native create returned handle $created",
            )
        }
        engineVersion = runCatching { native.version() }.getOrNull()
        handle = created
        return created
    }

    private fun toOcrText(result: NativeResult, imageHeight: Int): OcrText {
        val text = result.lines.joinToString("\n") { it.text }
        val heights = result.lines.map { line ->
            if (imageHeight <= 0) 0f else (line.height / imageHeight).coerceIn(0f, 1f)
        }
        val words = result.lines.sumOf { line ->
            line.text.split(' ', '\t', '\u3000').count { it.isNotBlank() }
        }
        return OcrText(
            text = text,
            blockHeights = heights,
            detectedLanguage = null,
            translatedText = null,
            wordCount = words,
            blockCount = result.lines.size,
            libraryVersion = "$LIBRARY_NAME ${engineVersion ?: "?"} · ${PpocrModels.FINGERPRINT}",
        )
    }

    companion object {
        /** Shown in [OcrAvailability.Ready] and on the panel's status line. */
        const val LIBRARY_NAME = "bc_ocr (PP-OCRv6 medium)"

        /** Matches the Python path's long edge so both engines see comparable input. */
        const val DEFAULT_MAX_EDGE = 1280
    }
}
