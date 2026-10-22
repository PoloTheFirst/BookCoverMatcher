package com.bookcovermatcher.online

/**
 * The one sentence shown for each [OcrFailure].
 *
 * Split out of [ChromeLensPyBridge] so the wording is testable without an interpreter, and so a
 * failure category cannot exist without a message: the `when` below is exhaustive, so adding an
 * [OcrFailure] is a compile error until it has one.
 *
 * Two registers, because the two surfaces are different:
 *  * [brief] — the toast. Short enough to read while the phone is still pointed at a shelf.
 *  * [detail] — the in-panel explanation, allowed to say what to do next.
 *
 * Neither ever contains a stack trace, an exception class name, or anything about the image
 * itself. [OcrException.detail] holds that, and goes to the log.
 */
object OcrErrorMessages {

    fun brief(failure: OcrFailure): String = when (failure) {
        OcrFailure.RUNTIME_MISSING -> "Python runtime unavailable"
        OcrFailure.LIBRARY_MISSING -> "OCR library not installed"
        OcrFailure.BAD_IMAGE -> "Could not read that picture"
        OcrFailure.NETWORK -> "No connection to Google Lens"
        OcrFailure.RATE_LIMITED -> "Google Lens is busy"
        OcrFailure.AUTH -> "Google Lens refused the request"
        OcrFailure.TIMEOUT -> "Google Lens timed out"
        OcrFailure.API_ERROR -> "Google Lens reported an error"
        OcrFailure.INTERNAL -> "Could not read the cover"
    }

    fun detail(failure: OcrFailure): String = when (failure) {
        OcrFailure.RUNTIME_MISSING ->
            "The embedded Python runtime could not start on this device. Offline matching still works."
        OcrFailure.LIBRARY_MISSING ->
            "The embedded chrome-lens-py package is missing from this build. Reinstall the app."
        OcrFailure.BAD_IMAGE ->
            "The cover could not be encoded or read. Try again in better light, holding the cover flat."
        OcrFailure.NETWORK ->
            "Google Lens could not be reached. Check the connection — offline matching still works."
        OcrFailure.RATE_LIMITED ->
            "Google Lens is throttling requests. Try again in a minute."
        OcrFailure.AUTH ->
            "Google Lens rejected the built-in API key. This is not something you can fix."
        OcrFailure.TIMEOUT ->
            "Google Lens did not answer in time. Try again."
        OcrFailure.API_ERROR ->
            "Google Lens answered with an error. Try again, or type the title below."
        OcrFailure.INTERNAL ->
            "The Lens bridge failed unexpectedly. You can still type a title below."
    }
}
