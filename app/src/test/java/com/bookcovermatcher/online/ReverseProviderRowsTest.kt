// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.online

import com.bookcovermatcher.core.online.reverse.ReverseImageAutomation
import com.bookcovermatcher.core.online.reverse.ReverseImageHit
import com.bookcovermatcher.core.online.reverse.ReverseImageOutcome
import com.bookcovermatcher.core.online.reverse.ReverseImageProviders
import com.bookcovermatcher.core.online.reverse.ReverseImageQuota
import com.bookcovermatcher.core.online.reverse.ReverseImageRequest
import com.bookcovermatcher.core.online.reverse.ReverseImageRun
import com.bookcovermatcher.core.online.reverse.ReverseImageSource
import com.bookcovermatcher.core.online.reverse.ReverseImageState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.5.0-dev.2 crash fix — the reverse-image provider rows, built from an empty run map.
 *
 * This is the exact call the crash happened in: `OnlineSearchController` builds its UI state in a property
 * initializer and asked for these rows before any provider had run. The old builder read the controller's
 * `runs` field directly, which was still `null` at that moment, so construction threw a
 * `NullPointerException` and the app died on every launch.
 *
 * The builder now takes the map as a parameter ([ReverseProviderRows.of]), which makes the "read a field before
 * it exists" mistake impossible and puts the initialisation path under the default unit-test suite.
 */
class ReverseProviderRowsTest {

    private class FakeSource(
        override val id: String,
        override val displayName: String,
        override val priority: Int,
        override val automation: ReverseImageAutomation = ReverseImageAutomation.AUTOMATED,
        override val timeoutMs: Long = 30_000L,
        override val searchPageUrl: String? = null,
        private val ready: ReverseImageState = ReverseImageState.READY,
    ) : ReverseImageSource {
        override fun readiness(): ReverseImageState = ready

        override fun search(request: ReverseImageRequest): ReverseImageOutcome = ReverseImageOutcome.NoResults()
    }

    private fun sauceNao(ready: ReverseImageState = ReverseImageState.READY) = FakeSource(
        id = ReverseImageProviders.SAUCE_NAO_ID,
        displayName = "SauceNAO",
        priority = ReverseImageProviders.SAUCE_NAO_PRIORITY,
        searchPageUrl = ReverseImageProviders.SAUCE_NAO_SEARCH_PAGE,
        ready = ready,
    )

    private fun iqdb() = FakeSource(
        id = ReverseImageProviders.IQDB_ID,
        displayName = "IQDB",
        priority = ReverseImageProviders.IQDB_PRIORITY,
        automation = ReverseImageAutomation.MANUAL_ONLY,
        timeoutMs = 90_000L,
        searchPageUrl = ReverseImageProviders.IQDB_SEARCH_PAGE,
        ready = ReverseImageState.MANUAL_ONLY,
    )

    @Test
    fun `building the rows from an empty run map does not throw and yields both providers`() {
        val rows = ReverseProviderRows.of(listOf(sauceNao(), iqdb()), runs = emptyMap())

        assertEquals(2, rows.size)
        assertEquals(listOf("saucenao", "iqdb"), rows.map { it.id })
        assertEquals(listOf(1, 2), rows.map { it.priority })
        assertEquals(ReverseImageState.READY, rows[0].state)
        assertEquals(ReverseImageState.MANUAL_ONLY, rows[1].state)
        assertEquals("Ready. Tried first.", rows[0].detail)
        assertTrue("IQDB's row must explain why it is manual", rows[1].detail!!.contains("robots.txt"))
        assertTrue(rows[0].manualOnly.not())
        assertTrue(rows[1].manualOnly)
        assertEquals(0, rows[0].resultCount)
        assertEquals(0, rows[1].resultCount)
        assertNull(rows[0].quota)
    }

    @Test
    fun `a missing SauceNAO key is drawn as needing a key, not as ready`() {
        val rows = ReverseProviderRows.of(
            listOf(sauceNao(ready = ReverseImageState.NOT_CONFIGURED), iqdb()),
            runs = emptyMap(),
        )
        assertEquals(ReverseImageState.NOT_CONFIGURED, rows[0].state)
        assertTrue(rows[0].detail!!.contains("API key"))
    }

    @Test
    fun `the input order does not decide the display order`() {
        val rows = ReverseProviderRows.of(listOf(iqdb(), sauceNao()), runs = emptyMap())
        assertEquals(listOf("saucenao", "iqdb"), rows.map { it.id })
    }

    @Test
    fun `a finished run overrides readiness and its own numbers are shown`() {
        val hit = ReverseImageHit(
            providerId = ReverseImageProviders.SAUCE_NAO_ID,
            providerName = "SauceNAO",
            productId = "RJ01234567",
        )
        val run = ReverseImageRun(
            providerId = ReverseImageProviders.SAUCE_NAO_ID,
            displayName = "SauceNAO",
            priority = ReverseImageProviders.SAUCE_NAO_PRIORITY,
            state = ReverseImageState.SUCCESS,
            hits = listOf(hit),
            quota = ReverseImageQuota(shortLimit = 4, longLimit = 100, shortRemaining = 3, longRemaining = 99),
        )

        val rows = ReverseProviderRows.of(
            listOf(sauceNao(), iqdb()),
            runs = mapOf(ReverseImageProviders.SAUCE_NAO_ID to run),
        )

        assertEquals(ReverseImageState.SUCCESS, rows[0].state)
        assertEquals(1, rows[0].resultCount)
        assertEquals("99 of 100 left", rows[0].quota)
        assertEquals("the untouched provider keeps its resting state", ReverseImageState.MANUAL_ONLY, rows[1].state)
    }

    @Test
    fun `a failure's own message is shown verbatim instead of a resting explanation`() {
        val timedOut = ReverseImageRun(
            providerId = ReverseImageProviders.IQDB_ID,
            displayName = "IQDB",
            priority = ReverseImageProviders.IQDB_PRIORITY,
            state = ReverseImageState.TIMEOUT,
            message = "No answer within 90s \u2014 a timeout, not an empty result.",
        )
        val rows = ReverseProviderRows.of(
            listOf(sauceNao(), iqdb()),
            runs = mapOf(ReverseImageProviders.IQDB_ID to timedOut),
        )
        assertEquals(ReverseImageState.TIMEOUT, rows[1].state)
        assertEquals(timedOut.message, rows[1].detail)
        assertEquals("a timeout is not a result count", 0, rows[1].resultCount)
    }
}
