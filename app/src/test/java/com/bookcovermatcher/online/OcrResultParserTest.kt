package com.bookcovermatcher.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The Kotlin half of the `lens_bridge.py` contract, exercised without an interpreter.
 *
 * These tests exist because the online feature shipped with two defects that 84 green tests did
 * not catch: a byte array Python would not accept, and result fields read with `getattr()`
 * instead of dictionary lookup. The second is only testable on the JVM once the parsing is pure
 * Kotlin, which is what [OcrResultParser] is for.
 *
 * The maps below are exactly what `ChromeLensPyBridge.asPlainMap()` produces from a Python dict:
 * values stringified by `PyObject.toString()` (documented as Python `str()`), and `null` for
 * Python `None` (Chaquopy represents `None` as Java `null`).
 */
class OcrResultParserTest {

    private fun success(vararg extra: Pair<String, String?>) = mapOf(
        OcrResultParser.KEY_OK to "True",
        OcrResultParser.KEY_TEXT to "THE HOBBIT\nJ.R.R. TOLKIEN",
        OcrResultParser.KEY_BLOCK_HEIGHTS to "0.07500,0.02000",
        OcrResultParser.KEY_BLOCK_COUNT to "2",
        OcrResultParser.KEY_WORD_COUNT to "4",
        OcrResultParser.KEY_LANGUAGE to "en",
        OcrResultParser.KEY_TRANSLATION to null,
        OcrResultParser.KEY_VERSION to "3.8.2",
    ) + extra.toMap()

    private fun failed(code: String, vararg extra: Pair<String, String?>) = mapOf(
        OcrResultParser.KEY_OK to "False",
        OcrResultParser.KEY_ERROR_CODE to code,
    ) + extra.toMap()

    @Test fun readsEveryFieldOfASuccessfulEnvelope() {
        val text = OcrResultParser.parseOcr(success())

        assertEquals("THE HOBBIT\nJ.R.R. TOLKIEN", text.text)
        assertEquals(listOf(0.075f, 0.02f), text.blockHeights)
        assertEquals(2, text.blockCount)
        assertEquals(4, text.wordCount)
        assertEquals("en", text.detectedLanguage)
        assertNull("Python None must arrive as Kotlin null", text.translatedText)
        assertEquals("3.8.2", text.libraryVersion)
        assertFalse(text.isEmpty)
    }

    /**
     * The H2 regression, stated as a rule: a field that is genuinely absent is a broken reply,
     * never a silent empty string. Reading a dict with `getattr()` produced exactly that silent
     * empty, which is why the app said "no text found" about a cover full of text.
     */
    @Test fun aMissingTextFieldIsABrokenReplyNotAnEmptyCover() {
        val noText = success().filterKeys { it != OcrResultParser.KEY_TEXT }

        val e = expectOcr { OcrResultParser.parseOcr(noText) }

        assertEquals(OcrFailure.INTERNAL, e.failure)
        assertTrue("detail should name the missing key: ${e.detail}", e.detail?.contains("text") == true)
    }

    @Test fun blankTextOnASuccessfulEnvelopeIsNoTextNotAFailure() {
        val text = OcrResultParser.parseOcr(success(OcrResultParser.KEY_TEXT to "   "))

        assertTrue("an empty cover is a successful scan", text.isEmpty)
        assertEquals("   ", text.text)
    }

    @Test fun aMissingOkFlagIsABrokenReply() {
        val noOk = success().filterKeys { it != OcrResultParser.KEY_OK }

        assertEquals(OcrFailure.INTERNAL, expectOcr { OcrResultParser.parseOcr(noOk) }.failure)
    }

    @Test fun okIsReadCaseAndTypeInsensitively() {
        assertTrue(OcrResultParser.parseOcr(success(OcrResultParser.KEY_OK to "true")).text.isNotEmpty())
        assertEquals(
            OcrFailure.NETWORK,
            expectOcr { OcrResultParser.parseOcr(failed("network", OcrResultParser.KEY_OK to "False")) }.failure,
        )
    }

    // -----------------------------------------------------------------------------------------
    // Error codes -> categories. Mirrors ERROR_CODES in lens_bridge.py.
    // -----------------------------------------------------------------------------------------

    @Test fun everyPythonErrorCodeMapsToACategory() {
        val expected = mapOf(
            "bad_input" to OcrFailure.BAD_IMAGE,
            "image" to OcrFailure.BAD_IMAGE,
            "network" to OcrFailure.NETWORK,
            "rate_limit" to OcrFailure.RATE_LIMITED,
            "auth" to OcrFailure.AUTH,
            "timeout" to OcrFailure.TIMEOUT,
            "api" to OcrFailure.API_ERROR,
            "internal" to OcrFailure.INTERNAL,
        )
        for ((code, category) in expected) {
            val e = expectOcr { OcrResultParser.parseOcr(failed(code)) }
            assertEquals("code '$code'", category, e.failure)
        }
    }

    @Test fun anUnknownErrorCodeFallsBackToInternal() {
        assertEquals(OcrFailure.INTERNAL, OcrResultParser.failureForCode("teleportation"))
        assertEquals(OcrFailure.INTERNAL, OcrResultParser.failureForCode(null))
    }

    @Test fun thePythonMessageIsShownAndTheTracebackIsKeptApart() {
        val e = expectOcr {
            OcrResultParser.parseOcr(
                failed(
                    "network",
                    OcrResultParser.KEY_ERROR_MESSAGE to "Google Lens could not be reached. Check the connection.",
                    OcrResultParser.KEY_ERROR_DETAIL to "Traceback ... LensConnectionError: [Errno -3] failure",
                ),
            )
        }

        assertEquals(OcrFailure.NETWORK, e.failure)
        assertEquals("Google Lens could not be reached. Check the connection.", e.message)
        assertTrue(e.detail?.contains("Traceback") == true)
    }

    @Test fun aFailureWithNoMessageStillGetsTheCategorySentence() {
        val e = expectOcr { OcrResultParser.parseOcr(failed("timeout")) }

        assertEquals(OcrErrorMessages.detail(OcrFailure.TIMEOUT), e.message)
    }

    /** A traceback must never reach the user-facing message. */
    @Test fun theShownMessageNeverContainsATraceback() {
        val e = expectOcr {
            OcrResultParser.parseOcr(
                failed(
                    "api",
                    OcrResultParser.KEY_ERROR_MESSAGE to "Google Lens rejected the request.",
                    OcrResultParser.KEY_ERROR_DETAIL to "Traceback (most recent call last):\n  File ...",
                ),
            )
        }

        val shown = e.message.orEmpty()
        assertFalse(shown.contains("Traceback"))
        assertFalse(shown.contains("File "))
    }

    /** The parser reports which keys arrived, never what they held: values carry cover text. */
    @Test fun brokenReplyDetailsNameKeysButNeverValues() {
        val secret = "sensitive-title-should-not-be-logged"
        // No 'ok' flag at all, so this is a broken reply whose detail must not echo the values.
        val e = expectOcr { OcrResultParser.parseOcr(mapOf(OcrResultParser.KEY_TEXT to secret)) }

        assertEquals(OcrFailure.INTERNAL, e.failure)
        assertTrue("detail should name the key: ${e.detail}", e.detail?.contains("text") == true)
        assertFalse("value leaked into the log detail", e.detail?.contains(secret) == true)
    }

    @Test fun aNonNumericCountIsABrokenReply() {
        val e = expectOcr { OcrResultParser.parseOcr(success(OcrResultParser.KEY_WORD_COUNT to "four")) }
        assertEquals(OcrFailure.INTERNAL, e.failure)
    }

    @Test fun absentCountsAreZeroNotErrors() {
        val text = OcrResultParser.parseOcr(
            mapOf(OcrResultParser.KEY_OK to "True", OcrResultParser.KEY_TEXT to "DUNE"),
        )

        assertEquals(0, text.wordCount)
        assertEquals(0, text.blockCount)
        assertTrue(text.blockHeights.isEmpty())
        assertEquals("", text.libraryVersion)
    }

    @Test fun heightsParsingToleratesGapsAndJunk() {
        assertEquals(listOf(0.075f, 0.02f), OcrResultParser.heights("0.07500, 0.02000"))
        assertTrue(OcrResultParser.heights(null).isEmpty())
        assertTrue(OcrResultParser.heights("  ").isEmpty())
        assertEquals(listOf(0.05f), OcrResultParser.heights("0.05,not-a-number"))
    }


    // -----------------------------------------------------------------------------------------
    // availability() — the green/red status line
    // -----------------------------------------------------------------------------------------

    @Test fun availabilityReportsLibraryAndVersion() {
        val a = OcrResultParser.parseAvailability(
            mapOf(
                OcrResultParser.KEY_OK to "True",
                OcrResultParser.KEY_LIBRARY to "chrome-lens-py",
                OcrResultParser.KEY_VERSION to "3.8.2",
            ),
        )

        assertTrue(a is OcrAvailability.Ready)
        assertEquals("chrome-lens-py", (a as OcrAvailability.Ready).libraryName)
        assertEquals("3.8.2", a.libraryVersion)
    }

    /**
     * The visible symptom of H2 was this line rendering as " · OCR only, no product search" — an
     * empty name and an empty version. A Ready state must never be able to do that.
     */
    @Test fun aReadyStateAlwaysNamesALibrary() {
        val a = OcrResultParser.parseAvailability(mapOf(OcrResultParser.KEY_OK to "True"))

        assertTrue(a is OcrAvailability.Ready)
        assertTrue(
            "a Ready state must never render as a blank name",
            (a as OcrAvailability.Ready).libraryName.isNotBlank(),
        )
    }

    @Test fun availabilityCarriesTheReasonAndCategoryWhenUnavailable() {
        val a = OcrResultParser.parseAvailability(
            mapOf(
                OcrResultParser.KEY_OK to "False",
                OcrResultParser.KEY_ERROR_CODE to "auth",
                OcrResultParser.KEY_ERROR_MESSAGE to "Google Lens rejected the built-in API key.",
            ),
        )

        assertTrue(a is OcrAvailability.Unavailable)
        assertEquals(OcrFailure.AUTH, (a as OcrAvailability.Unavailable).failure)
        assertEquals("Google Lens rejected the built-in API key.", a.reason)
    }

    @Test fun anUnreadableAvailabilityReplyIsUnavailableNotACrash() {
        val a = OcrResultParser.parseAvailability(emptyMap())

        assertTrue(a is OcrAvailability.Unavailable)
        assertEquals(OcrFailure.INTERNAL, (a as OcrAvailability.Unavailable).failure)
    }

    // -----------------------------------------------------------------------------------------
    // Messages — no category may be reported with a generic shrug
    // -----------------------------------------------------------------------------------------

    @Test fun everyFailureCategoryHasItsOwnDistinctMessages() {
        val briefs = OcrFailure.entries.map { OcrErrorMessages.brief(it) }
        val details = OcrFailure.entries.map { OcrErrorMessages.detail(it) }

        assertTrue("all briefs non-blank", briefs.none { it.isBlank() })
        assertTrue("all details non-blank", details.none { it.isBlank() })
        assertEquals("each category needs its own toast text", OcrFailure.entries.size, briefs.distinct().size)
        assertEquals(OcrFailure.entries.size, details.distinct().size)
    }

    @Test fun onlyAnInternalFailureStillSaysCouldNotReadTheCover() {
        assertEquals("Could not read the cover", OcrErrorMessages.brief(OcrFailure.INTERNAL))
        assertFalse(
            "the generic message must not be reused for a known category",
            OcrFailure.entries.filter { it != OcrFailure.INTERNAL }
                .any { OcrErrorMessages.brief(it) == OcrErrorMessages.brief(OcrFailure.INTERNAL) },
        )
    }

    // -----------------------------------------------------------------------------------------

    private inline fun expectOcr(block: () -> Unit): OcrException {
        try {
            block()
        } catch (e: OcrException) {
            return e
        }
        fail("expected an OcrException")
        error("unreachable")
    }
}

