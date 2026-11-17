// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.online

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrDiagnosticsTest {
    @Test fun exceptionPayloadAndCauseNeverReachDiagnostics() {
        val error = OcrException(
            failure = OcrFailure.INTERNAL,
            message = "private cover text token=secret",
            detail = "traceback https://example.org/?key=secret",
            cause = IllegalStateException("private workbook path"),
        )
        assertEquals("ocr failed bytes=12 category=INTERNAL", OcrDiagnostics.failure(12, error))
    }

    @Test fun unexpectedExceptionHasOnlyFixedCategory() {
        assertEquals("ocr failed bytes=0 category=INTERNAL",
            OcrDiagnostics.failure(-1, IllegalStateException("private response")))
    }
}
