// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.online

/** Deliberately omits exception messages, causes and Python detail, which may contain user data. */
internal object OcrDiagnostics {
    fun failure(byteCount: Int, throwable: Throwable): String {
        val category = (throwable as? OcrException)?.failure?.name ?: "INTERNAL"
        return "ocr failed bytes=${byteCount.coerceAtLeast(0)} category=$category"
    }
}
