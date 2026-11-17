// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.online

import android.util.Log

/** Payload-free OCR diagnostics. Never write exception messages, causes or Python tracebacks. */
object OnlineSearchLog {

    const val TAG = "OnlineSearch"

    /** One line per bridge call: what was asked for and how it went, without the payload. */
    fun ocrAttempt(byteCount: Int, language: String?, target: String?, outcome: String) {
        Log.i(TAG, "ocr request bytes=$byteCount lang=${language ?: "auto"} " +
            "target=${target ?: "none"} -> $outcome")
    }

    /**
     * The engine the user picked. Logged when the selection changes, so a device log can be tied to
     * the setting that produced a request instead of guessing from timing.
     */
    fun ocrEngineSelection(id: String, label: String) {
        Log.i(TAG, "ocr engine selected id=$id label=\"$label\"")
    }

    /** Which engine a READ THE COVER request was sent to — the routing evidence. */
    fun ocrEngineRequest(id: String, label: String, byteCount: Int) {
        Log.i(TAG, "ocr engine request id=$id label=\"$label\" bytes=$byteCount")
    }

    /** How that request ended, and which library version answered. No OCR text is ever logged. */
    fun ocrEngineResult(id: String, outcome: String) {
        Log.i(TAG, "ocr engine result id=$id $outcome")
    }

    fun ocrFailed(byteCount: Int, throwable: Throwable) {
        Log.w(TAG, OcrDiagnostics.failure(byteCount, throwable))
    }

    fun warn(message: String) = Log.w(TAG, message)
}
