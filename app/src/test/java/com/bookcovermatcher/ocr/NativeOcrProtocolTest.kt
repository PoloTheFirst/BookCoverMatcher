package com.bookcovermatcher.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire format is where a silent mistake becomes plausible-looking wrong text, so every field
 * rule is pinned here rather than being discovered on a device.
 */
class NativeOcrProtocolTest {

    private val quad = "1.00,2.00,3.00,2.00,3.00,4.00,1.00,4.00"

    private fun payload(vararg records: String): String =
        buildString {
            appendLine(NativeOcrProtocol.RESULT_HEADER)
            for (record in records) appendLine(record)
            appendLine("END")
        }

    @Test
    fun aLineBecomesTextScoreAndAnAxisAlignedBox() {
        val result = NativeOcrProtocol.parseResult(
            payload("REGIONS\t3", "LINE\t1\t0.987654\t0\t$quad\tTHE HOBBIT"),
        )
        assertEquals(3, result.regionsDetected)
        assertEquals(1, result.lines.size)
        val line = result.lines.single()
        assertEquals("THE HOBBIT", line.text)
        assertEquals(0.987654f, line.score, 1e-6f)
        assertEquals(false, line.vertical)
        assertEquals(1f, line.left, 0f)
        assertEquals(2f, line.top, 0f)
        assertEquals(3f, line.right, 0f)
        assertEquals(4f, line.bottom, 0f)
        assertEquals(2f, line.height, 1e-6f)
    }

    @Test
    fun aVerticalFlagAndRanksAreKept() {
        val result = NativeOcrProtocol.parseResult(
            payload("REGIONS\t2", "LINE\t2\t0.5\t1\t$quad\t縦書き"),
        )
        val line = result.lines.single()
        assertTrue(line.vertical)
        assertEquals(2, line.rank)
        assertEquals("縦書き", line.text)
    }

    @Test
    fun escapedSeparatorsComeBackAsCharacters() {
        // The wire carries `a\tb\nc\\d` (backslash-t, backslash-n, double backslash).
        val escaped = "a" + "\\" + "tb" + "\\" + "nc" + "\\\\" + "d"
        val result = NativeOcrProtocol.parseResult(
            payload("REGIONS\t1", "LINE\t1\t0.5\t0\t$quad\t$escaped"),
        )
        assertEquals("a" + "\t" + "b" + "\n" + "c" + "\\" + "d", result.lines.single().text)
    }

    @Test
    fun anUnknownRecordTypeIsIgnoredSoANewerNativeSideStillWorks() {
        val result = NativeOcrProtocol.parseResult(
            payload("REGIONS\t1", "NOTE\tfuture field", "LINE\t1\t0.5\t0\t$quad\tX"),
        )
        assertEquals(1, result.lines.size)
        assertEquals("X", result.lines.single().text)
    }

    @Test
    fun aCoverWithNoTextIsAnEmptyResultRatherThanAnError() {
        val result = NativeOcrProtocol.parseResult(payload("REGIONS\t0"))
        assertEquals(0, result.regionsDetected)
        assertTrue(result.lines.isEmpty())
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun perRegionErrorsAreCarriedThrough() {
        val result = NativeOcrProtocol.parseResult(
            payload("REGIONS\t1", "ERROR\tregion 2: recognizer inference failed"),
        )
        assertEquals(listOf("region 2: recognizer inference failed"), result.errors)
    }

    @Test
    fun aTruncatedPayloadIsRefused() {
        val text = "${NativeOcrProtocol.RESULT_HEADER}\nREGIONS\t1\n"
        val error = runCatching { NativeOcrProtocol.parseResult(text) }.exceptionOrNull()
        assertTrue("expected a refusal, got $error", error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("truncated"))
    }

    @Test
    fun aForeignHeaderIsRefusedRatherThanMisread() {
        val error = runCatching {
            NativeOcrProtocol.parseResult("BC-OCR-RESULT 2\nREGIONS\t0\nEND\n")
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("unexpected payload header"))
    }

    @Test
    fun aRecordWithTheWrongShapeIsRefused() {
        val error = runCatching {
            NativeOcrProtocol.parseResult(payload("REGIONS\t1", "LINE\t1\t0.5\t0\t1.00,2.00\ttext"))
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("8 coordinates"))
    }

    @Test
    fun aNonNumericRegionCountIsRefused() {
        val error = runCatching { NativeOcrProtocol.parseResult(payload("REGIONS\tmany")) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun theInfoPayloadIsReadIntoNamedFields() {
        val info = NativeOcrProtocol.parseInfo(
            buildString {
                appendLine(NativeOcrProtocol.INFO_HEADER)
                appendLine("VERSION\t0.1.0")
                appendLine("CLASSES\t18710")
                appendLine("DICT\t18708")
                appendLine("SPACE\t1")
                appendLine("DET\tx\t[?,3,?,?]")
                appendLine("END")
            },
        )
        assertEquals(listOf("0.1.0"), info["VERSION"])
        assertEquals(listOf("18710"), info["CLASSES"])
        assertEquals(listOf("x", "[?,3,?,?]"), info["DET"])
    }

    @Test
    fun theInfoPayloadNeverThrowsOnJunk() {
        assertTrue(NativeOcrProtocol.parseInfo("nonsense\n").isEmpty())
        assertTrue(NativeOcrProtocol.parseInfo("").isEmpty())
    }

    @Test
    fun unescapingLeavesAnUnknownEscapeAlone() {
        assertEquals("aqb", NativeOcrProtocol.unescape("a\\qb"))
        assertEquals("plain", NativeOcrProtocol.unescape("plain"))
    }
}
