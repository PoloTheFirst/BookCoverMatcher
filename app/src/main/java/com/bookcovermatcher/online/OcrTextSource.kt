package com.bookcovermatcher.online

/**
 * What can go wrong between Kotlin and the embedded `chrome-lens-py` runtime.
 *
 * Every member maps to exactly one user-facing sentence in [OcrErrorMessages], so a category is
 * only added when the user could act on it differently. The Python side produces the matching
 * codes in `lens_bridge.py` (ERROR_CODES); [OcrResultParser] is the single place that translates
 * between the two, which keeps the contract in one file instead of scattered `when` blocks.
 */
enum class OcrFailure {
    /** The embedded Python runtime could not be started on this device. */
    RUNTIME_MISSING,

    /** The runtime started, but `chrome_lens_py` / `lens_bridge` could not be imported. */
    LIBRARY_MISSING,

    /** The picture was missing, empty, oversized or undecodable. */
    BAD_IMAGE,

    /** Lens could not be reached (no network, DNS, TLS, proxy). */
    NETWORK,

    /** Lens answered 429/503 after its own retries. */
    RATE_LIMITED,

    /** Lens rejected the built-in API key. */
    AUTH,

    /** Lens did not answer in time. */
    TIMEOUT,

    /** Lens answered, but reported an error. */
    API_ERROR,

    /** A bug in the bridge or an unreadable reply. Not something the user can fix. */
    INTERNAL,
}

/**
 * The recognised text of one cover, plus what the library could say about it.
 *
 * [text] being blank is a **successful** scan of a cover with no writing on it — see [isEmpty].
 * It is deliberately not an exception, so the UI can say "no text found" rather than "failed".
 */
class OcrText(
    /** Recognised text, one text block per line. Empty string when the cover has no text. */
    val text: String,
    /**
     * Normalized glyph height of each block in [text], same order. chrome-lens-py gives this
     * geometry for free, and it is the only honest way to tell a title from a series banner.
     */
    val blockHeights: List<Float>,
    val detectedLanguage: String?,
    val translatedText: String?,
    val wordCount: Int,
    val blockCount: Int,
    val libraryVersion: String,
) {
    val isEmpty: Boolean get() = text.isBlank()
}

/**
 * Thrown by an [OcrTextSource] for a reason that has a name.
 *
 * [message] is always safe to show: a short sentence in the user's terms, never a stack trace and
 * never any image data. [detail] is the developer-facing half — the Python traceback or equivalent
 * — and belongs in the log, not in the UI.
 */
class OcrException(
    val failure: OcrFailure,
    message: String,
    val detail: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Whether the Python bridge can be used right now. */
sealed interface OcrAvailability {
    data object Checking : OcrAvailability
    data class Ready(val libraryName: String, val libraryVersion: String) : OcrAvailability
    data class Unavailable(val failure: OcrFailure, val reason: String) : OcrAvailability
}

/**
 * Reads the text printed on a cover.
 *
 * This is the *only* capability of `chrome-lens-py` that the app uses, because OCR and translation
 * are the only things the library implements. Product discovery is a separate concern
 * ([com.bookcovermatcher.core.online.ProductSearchDataSource]) with a separate data source.
 *
 * Synchronous: the caller decides the dispatcher. Never call from the main thread — the Python
 * bridge runs a real event loop on the calling thread.
 */
interface OcrTextSource {
    /** Cheap, non-blocking check of whether [recognise] could possibly work. */
    fun availability(): OcrAvailability

    /** @throws OcrException with a showable [OcrException.message] and a loggable `detail`. */
    fun recognise(
        imageBytes: ByteArray,
        ocrLanguage: String?,
        targetLanguage: String?,
        timeoutSeconds: Int,
    ): OcrText
}
