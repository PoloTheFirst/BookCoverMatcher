package com.bookcovermatcher.online

/**
 * Turns the envelope `lens_bridge.py` returns into Kotlin types.
 *
 * Deliberately **pure Kotlin with no Chaquopy import**, so the whole contract is unit-testable on
 * a desktop JVM — the absence of which is how two bridge bugs reached a release. The caller
 * ([ChromeLensPyBridge]) does one job: read a Python dict into a `Map<String, String?>` and hand
 * it here.
 *
 * Values arrive as strings because `PyObject.toString()` is documented as Python `str()`, which
 * renders `str`, `int`, `float` and `bool` faithfully, and Chaquopy represents Python `None` as
 * Java `null`. Coercing here keeps every type decision in one testable place.
 *
 * Contract with Python (`ERROR_CODES` in `lens_bridge.py`): rename a code in both files together.
 */
object OcrResultParser {

    const val KEY_OK = "ok"
    const val KEY_ERROR_CODE = "error_code"
    const val KEY_ERROR_MESSAGE = "error_message"
    const val KEY_ERROR_DETAIL = "error_detail"

    const val KEY_TEXT = "text"
    const val KEY_BLOCK_HEIGHTS = "block_heights"
    const val KEY_BLOCK_COUNT = "block_count"
    const val KEY_WORD_COUNT = "word_count"
    const val KEY_LANGUAGE = "detected_language"
    const val KEY_TRANSLATION = "translated_text"
    const val KEY_LIBRARY = "library"
    const val KEY_VERSION = "library_version"

    /** Python error code -> the category the user gets a sentence for. */
    fun failureForCode(code: String?): OcrFailure = when (code) {
        "bad_input", "image" -> OcrFailure.BAD_IMAGE
        "network" -> OcrFailure.NETWORK
        "rate_limit" -> OcrFailure.RATE_LIMITED
        "auth" -> OcrFailure.AUTH
        "timeout" -> OcrFailure.TIMEOUT
        "api" -> OcrFailure.API_ERROR
        else -> OcrFailure.INTERNAL
    }

    /**
     * @throws OcrException if the envelope says it failed, or is not shaped like an envelope.
     *   A blank `text` on a successful envelope is **not** an exception: it is a cover with
     *   nothing written on it, and the caller renders it as "no text found".
     */
    fun parseOcr(fields: Map<String, String?>): OcrText {
        val succeeded = boolean(fields, KEY_OK)
            ?: throw internal("The Lens bridge reply had no '$KEY_OK' flag.", fields)

        if (!succeeded) throw failure(fields)

        // `text` is the one field the whole feature exists to obtain. Absent means the reply is
        // not the one we asked for, which is a bug, not an empty cover.
        val text = fields[KEY_TEXT]
            ?: throw internal("The Lens bridge reply had no '$KEY_TEXT' field.", fields)

        return OcrText(
            text = text,
            blockHeights = heights(fields[KEY_BLOCK_HEIGHTS]),
            detectedLanguage = fields[KEY_LANGUAGE]?.takeIf { it.isNotBlank() },
            translatedText = fields[KEY_TRANSLATION]?.takeIf { it.isNotBlank() },
            wordCount = count(fields, KEY_WORD_COUNT),
            blockCount = count(fields, KEY_BLOCK_COUNT),
            libraryVersion = fields[KEY_VERSION].orEmpty(),
        )
    }


    /** The green/red status line. Never throws: an unavailable bridge is a state, not a crash. */
    fun parseAvailability(fields: Map<String, String?>): OcrAvailability {
        val succeeded = boolean(fields, KEY_OK)
            ?: return OcrAvailability.Unavailable(
                OcrFailure.INTERNAL,
                "The Lens bridge replied with something unreadable.",
            )
        if (!succeeded) {
            val category = failureForCode(fields[KEY_ERROR_CODE])
            return OcrAvailability.Unavailable(
                category,
                fields[KEY_ERROR_MESSAGE]?.takeIf { it.isNotBlank() }
                    ?: OcrErrorMessages.detail(category),
            )
        }
        val library = fields[KEY_LIBRARY]?.takeIf { it.isNotBlank() } ?: "chrome-lens-py"
        val version = fields[KEY_VERSION]?.takeIf { it.isNotBlank() } ?: fields["version"]
        return OcrAvailability.Ready(library, version.orEmpty())
    }

    /** Builds the exception for an `ok: false` envelope. */
    fun failure(fields: Map<String, String?>): OcrException {
        val category = failureForCode(fields[KEY_ERROR_CODE])
        // Python's own message is more specific than our category sentence, and is written to be
        // shown. Fall back to the category when it is missing or blank.
        val shown = fields[KEY_ERROR_MESSAGE]?.takeIf { it.isNotBlank() }
            ?: OcrErrorMessages.detail(category)
        return OcrException(
            failure = category,
            message = shown,
            detail = fields[KEY_ERROR_DETAIL],
        )
    }

    // -----------------------------------------------------------------------------------------
    // Field coercion — every rule here is exercised by OcrResultParserTest
    // -----------------------------------------------------------------------------------------

    private fun boolean(fields: Map<String, String?>, key: String): Boolean? =
        when (fields[key]?.trim()?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }

    /** Absent counts are 0; a count that is present but not a number is a broken reply. */
    private fun count(fields: Map<String, String?>, key: String): Int {
        val raw = fields[key] ?: return 0
        return raw.trim().toIntOrNull()
            ?: throw internal("Field '$key' was not a number.", fields)
    }

    /** The bridge sends glyph heights as one comma-separated string; split it back out. */
    fun heights(csv: String?): List<Float> {
        if (csv.isNullOrBlank()) return emptyList()
        return csv.split(',').mapNotNull { it.trim().toFloatOrNull() }
    }

    private fun internal(message: String, fields: Map<String, String?>): OcrException =
        OcrException(
            failure = OcrFailure.INTERNAL,
            message = OcrErrorMessages.detail(OcrFailure.INTERNAL),
            // Keys only, never values: a value could carry text read off someone's book cover.
            detail = "$message keys=${fields.keys.sorted()}",
        )
}
