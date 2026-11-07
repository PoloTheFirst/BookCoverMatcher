package com.bookcovermatcher.ocr

import com.bookcovermatcher.online.OcrAvailability
import com.bookcovermatcher.online.OcrErrorMessages
import com.bookcovermatcher.online.OcrException
import com.bookcovermatcher.online.OcrFailure
import com.bookcovermatcher.online.OcrText
import com.bookcovermatcher.online.OcrTextSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dispatch layer: with two fakes, this pins *which* implementation a READ THE COVER request
 * reaches, that an error is never retried on the other engine, and that an in-flight request keeps
 * the engine it started with.
 */
class SwitchingOcrTextSourceTest {

    private class FakeSource(
        private val name: String,
        private val onRecognise: (() -> OcrText)? = null,
    ) : OcrTextSource, AutoCloseable {
        var availabilityCalls = 0
        var recogniseCalls = 0
        var closed = 0
        val byteCounts = mutableListOf<Int>()

        override fun availability(): OcrAvailability {
            availabilityCalls++
            return OcrAvailability.Ready(name, "v1")
        }

        override fun recognise(
            imageBytes: ByteArray,
            ocrLanguage: String?,
            targetLanguage: String?,
            timeoutSeconds: Int,
        ): OcrText {
            recogniseCalls++
            byteCounts += imageBytes.size
            onRecognise?.let { return it() }
            return OcrText(
                text = "text from $name",
                blockHeights = listOf(0.1f),
                detectedLanguage = null,
                translatedText = null,
                wordCount = 2,
                blockCount = 1,
                libraryVersion = "$name 1.0",
            )
        }

        override fun close() {
            closed++
        }
    }

    private var selected = OcrEngineChoice.CHROME_LENS.id
    private val chrome = FakeSource("ChromeLens")
    private val paddle = FakeSource("PaddleOCR")
    private val sources = mapOf(OcrEngineChoice.CHROME_LENS.id to chrome, OcrEngineChoice.PADDLE_OCR.id to paddle)
    private val switching = SwitchingOcrTextSource(current = { selected }, build = { sources.getValue(it) })

    private fun read(bytes: ByteArray = byteArrayOf(1, 2, 3)) =
        switching.recognise(bytes, null, null, 30)

    @Test
    fun theSelectedEngineIsTheOneThatRuns() {
        assertEquals("text from ChromeLens", read().text)
        assertEquals(1, chrome.recogniseCalls)
        assertEquals(0, paddle.recogniseCalls)

        selected = OcrEngineChoice.PADDLE_OCR.id
        assertEquals("text from PaddleOCR", read().text)
        assertEquals("Chrome Lens must not run again", 1, chrome.recogniseCalls)
        assertEquals(1, paddle.recogniseCalls)
    }

    @Test
    fun theImageReachesWhicheverEngineIsSelected() {
        selected = OcrEngineChoice.PADDLE_OCR.id
        read(ByteArray(4096))
        assertEquals(listOf(4096), paddle.byteCounts)
        // The same input type for both engines: raw encoded cover bytes.
        selected = OcrEngineChoice.CHROME_LENS.id
        read(ByteArray(4096))
        assertEquals(listOf(4096), chrome.byteCounts)
    }

    @Test
    fun anErrorIsNotRetriedOnTheOtherEngine() {
        val failing = FakeSource("PaddleOCR") {
            throw OcrException(OcrFailure.OCR_MODELS_MISSING, OcrErrorMessages.detail(OcrFailure.OCR_MODELS_MISSING))
        }
        val guarded = SwitchingOcrTextSource(
            current = { OcrEngineChoice.PADDLE_OCR.id },
            build = { if (it == OcrEngineChoice.PADDLE_OCR.id) failing else chrome },
        )

        val error = runCatching { guarded.recognise(byteArrayOf(1), null, null, 30) }.exceptionOrNull()

        assertTrue("expected the engine's own failure, got $error", error is OcrException)
        assertEquals(OcrFailure.OCR_MODELS_MISSING, (error as OcrException).failure)
        assertEquals("no fallback: Chrome Lens must not be invoked", 0, chrome.recogniseCalls)
    }

    @Test
    fun anInFlightRequestKeepsTheEngineItStartedWith() {
        // The paddle fake flips the selection while it is running: the call must still return its own
        // result, and the *next* call must go to the newly selected engine.
        val flipping = FakeSource("PaddleOCR") {
            selected = OcrEngineChoice.CHROME_LENS.id
            OcrText("in-flight paddle result", emptyList(), null, null, 3, 0, "PaddleOCR 1.0")
        }
        val guarded = SwitchingOcrTextSource(
            current = { selected },
            build = { if (it == OcrEngineChoice.PADDLE_OCR.id) flipping else chrome },
        )
        selected = OcrEngineChoice.PADDLE_OCR.id

        val first = guarded.recognise(byteArrayOf(1), null, null, 30)
        assertEquals("in-flight paddle result", first.text)
        assertEquals("PaddleOCR 1.0", first.libraryVersion)

        val second = guarded.recognise(byteArrayOf(1), null, null, 30)
        assertEquals("text from ChromeLens", second.text)
    }

    @Test
    fun switchingClosesTheEngineThatIsNoLongerUsed() {
        read()
        assertEquals(0, chrome.closed)

        selected = OcrEngineChoice.PADDLE_OCR.id
        read()

        assertEquals("the previous engine must be released", 1, chrome.closed)
        assertEquals(0, paddle.closed)
    }

    @Test
    fun availabilityIsAnsweredByTheSelectedEngine() {
        val chromeAvailability = switching.availability()
        assertTrue(chromeAvailability is OcrAvailability.Ready)
        assertEquals("ChromeLens", (chromeAvailability as OcrAvailability.Ready).libraryName)

        selected = OcrEngineChoice.PADDLE_OCR.id
        val paddleAvailability = switching.availability()
        assertEquals("PaddleOCR", (paddleAvailability as OcrAvailability.Ready).libraryName)
    }

    @Test
    fun closingTheSwitchClosesTheActiveEngine() {
        selected = OcrEngineChoice.PADDLE_OCR.id
        read()
        switching.close()
        assertEquals(1, paddle.closed)
        // And it is usable again afterwards (the controller does not close it in practice, but the
        // contract must not leave a half-closed state behind).
        read()
        assertEquals(2, paddle.recogniseCalls)
    }
}
