// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ocr

import com.bookcovermatcher.data.OnlineSearchPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stored value -> engine resolution, which is what the selector, prefs and routing share. */
class OcrEngineChoiceTest {

    @Test
    fun theTwoEnginesHaveHumanNamesAndStableIds() {
        assertEquals("python", OcrEngineChoice.CHROME_LENS.id)
        assertEquals("Chrome Lens", OcrEngineChoice.CHROME_LENS.label)
        assertEquals("rust", OcrEngineChoice.PADDLE_OCR.id)
        assertEquals("PaddleOCR (PP-OCRv6 Medium)", OcrEngineChoice.PADDLE_OCR.label)
        assertEquals("PaddleOCR", OcrEngineChoice.PADDLE_OCR.shortLabel)
        // Ids must stay in step with the pref constants, or a stored value would stop round-tripping.
        assertEquals(OnlineSearchPrefs.ENGINE_PYTHON, OcrEngineChoice.CHROME_LENS.id)
        assertEquals(OnlineSearchPrefs.ENGINE_RUST, OcrEngineChoice.PADDLE_OCR.id)
    }

    @Test
    fun chromeLensIsTheDefaultAndIsListedFirst() {
        assertEquals(OcrEngineChoice.CHROME_LENS, OcrEngineChoice.DEFAULT)
        assertEquals(OcrEngineChoice.CHROME_LENS, OcrEngineChoice.ALL.first())
        assertEquals(2, OcrEngineChoice.ALL.size)
    }

    @Test
    fun theLegacyPythonValueStillMeansChromeLens() {
        assertEquals(OcrEngineChoice.CHROME_LENS, OcrEngineChoice.fromId("python"))
        assertEquals(OcrEngineChoice.CHROME_LENS, OcrEngineChoice.fromId("chrome_lens"))
        assertEquals(OcrEngineChoice.CHROME_LENS, OcrEngineChoice.fromId("lens"))
    }

    @Test
    fun paddleAliasesResolveToTheNativeEngine() {
        for (value in listOf("rust", "paddle", "paddleocr", "PaddleOCR", " paddle_ocr ", "ppocr")) {
            assertEquals("'$value' must select PaddleOCR", OcrEngineChoice.PADDLE_OCR, OcrEngineChoice.fromId(value))
        }
    }

    @Test
    fun anUnknownOrMissingValueResolvesToTheDefault() {
        assertEquals(OcrEngineChoice.CHROME_LENS, OcrEngineChoice.fromId(null))
        assertEquals(OcrEngineChoice.CHROME_LENS, OcrEngineChoice.fromId(""))
        assertEquals(OcrEngineChoice.CHROME_LENS, OcrEngineChoice.fromId("   "))
        assertEquals(OcrEngineChoice.CHROME_LENS, OcrEngineChoice.fromId("tesseract"))
    }

    @Test
    fun everyIdRoundTrips() {
        for (choice in OcrEngineChoice.ALL) {
            assertEquals(choice, OcrEngineChoice.fromId(choice.id))
        }
        assertTrue(OcrEngineChoice.PADDLE_OCR.isPaddle)
        assertTrue(!OcrEngineChoice.CHROME_LENS.isPaddle)
    }
}
