package com.bookcovermatcher.online

import android.util.Log

/**
 * The online feature's log tag, and the only place it writes to Logcat.
 *
 * The rule this file exists to enforce: **never log image bytes, OCR text or catalogue
 * payloads.** OCR output is whatever was printed on someone's book cover, and the Python
 * traceback in [OcrException.detail] can quote the arguments a failed call was made with. So
 * [detail] is logged in full — it is the developer half the UI deliberately hides — but the image
 * is only ever described by its byte count, and the OCR text never appears at all.
 *
 * Filter on device with:
 *
 * ```
 * adb logcat -s OnlineSearch:V python.stderr:V python.stdout:V
 * ```
 */
object OnlineSearchLog {

    const val TAG = "OnlineSearch"

    /** One line per bridge call: what was asked for and how it went, without the payload. */
    fun ocrAttempt(byteCount: Int, language: String?, target: String?, outcome: String) {
        Log.i(TAG, "ocr request bytes=$byteCount lang=${language ?: "auto"} " +
            "target=${target ?: "none"} -> $outcome")
    }

    /**
     * A failed bridge call. [throwable] carries the category and, in
     * [OcrException.detail], the Python traceback — both go to the log, neither to the UI.
     */
    fun ocrFailed(byteCount: Int, throwable: Throwable) {
        val category = (throwable as? OcrException)?.failure?.name ?: throwable.javaClass.simpleName
        val detail = (throwable as? OcrException)?.detail
        Log.w(TAG, "ocr failed bytes=$byteCount category=$category " +
            "message=${throwable.message}".truncate(), throwable)
        if (!detail.isNullOrBlank()) {
            Log.w(TAG, "python traceback >>>\n$detail\n<<< end of traceback", throwable)
        }
    }

    fun info(message: String) = Log.i(TAG, message)

    fun warn(message: String, throwable: Throwable? = null) {
        if (throwable == null) Log.w(TAG, message) else Log.w(TAG, message, throwable)
    }

    /** Logcat truncates long entries anyway; keep one traceback from flooding the buffer. */
    private const val MAX_LOG_LENGTH = 3800

    private fun String.truncate(): String =
        if (length <= MAX_LOG_LENGTH) this else substring(0, MAX_LOG_LENGTH) + "…[truncated]"
}
