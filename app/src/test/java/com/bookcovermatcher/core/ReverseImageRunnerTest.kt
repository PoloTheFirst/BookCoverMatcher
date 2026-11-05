package com.bookcovermatcher.core

import com.bookcovermatcher.core.online.reverse.ReverseImageAutomation
import com.bookcovermatcher.core.online.reverse.ReverseImageHit
import com.bookcovermatcher.core.online.reverse.ReverseImageOutcome
import com.bookcovermatcher.core.online.reverse.ReverseImageProviders
import com.bookcovermatcher.core.online.reverse.ReverseImageRequest
import com.bookcovermatcher.core.online.reverse.ReverseImageSearchRunner
import com.bookcovermatcher.core.online.reverse.ReverseImageSource
import com.bookcovermatcher.core.online.reverse.ReverseImageState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.5.0-dev.2 — the reverse-image pipeline's ordering, isolation and honesty rules.
 *
 * Every provider here is a fake with an injected clock, so the assertions are deterministic: SauceNAO is first,
 * IQDB runs afterwards anyway, a slow answer is a timeout rather than an empty result, a cancelled run sends
 * nothing, and one provider's failure never erases the other's results.
 */
class ReverseImageRunnerTest {

    private val request = ReverseImageRequest(bytes = ByteArray(64) { 7 }, fileName = "cover.jpg")

    private class FakeSource(
        override val id: String,
        override val displayName: String,
        override val priority: Int,
        override val automation: ReverseImageAutomation = ReverseImageAutomation.AUTOMATED,
        override val timeoutMs: Long = 30_000L,
        override val searchPageUrl: String? = null,
        private val ready: ReverseImageState = ReverseImageState.READY,
        /** How long the fake pretends the request took, in clock units. */
        private val cost: Long = 0L,
        private val advance: (Long) -> Unit = {},
        private val outcome: () -> ReverseImageOutcome,
    ) : ReverseImageSource {
        var calls = 0
            private set

        override fun readiness(): ReverseImageState = ready

        override fun search(request: ReverseImageRequest): ReverseImageOutcome {
            calls++
            advance(cost)
            return outcome()
        }
    }

    private fun hit(
        providerId: String,
        name: String,
        similarity: Double?,
        productId: String = "",
        url: String? = null,
        title: String = "",
    ) = ReverseImageHit(
        providerId = providerId,
        providerName = name,
        providerResultId = url,
        sourceUrl = url,
        title = title,
        productId = productId,
        providerSimilarity = similarity,
    )

    @Test
    fun `sauce nao is tried first, and iqdb is still tried when sauce nao found something`() {
        var now = 0L
        val order = ArrayList<String>()
        val sauceNao = FakeSource(
            ReverseImageProviders.SAUCE_NAO_ID,
            "SauceNAO",
            ReverseImageProviders.SAUCE_NAO_PRIORITY,
            advance = { now += it },
            outcome = {
                order += "saucenao"
                ReverseImageOutcome.Found(listOf(hit("saucenao", "SauceNAO", 95.0, productId = "RJ01234567")))
            },
        )
        val iqdb = FakeSource(
            ReverseImageProviders.IQDB_ID,
            "IQDB",
            ReverseImageProviders.IQDB_PRIORITY,
            advance = { now += it },
            outcome = {
                order += "iqdb"
                ReverseImageOutcome.NoResults()
            },
        )

        // Declared in the wrong order on purpose: the runner sorts by provider priority, not list order.
        val result = ReverseImageSearchRunner(listOf(iqdb, sauceNao), clock = { now }).run(request)

        assertEquals(listOf("saucenao", "iqdb"), order)
        assertEquals(listOf("saucenao", "iqdb"), result.runs.map { it.providerId })
        assertEquals(1, sauceNao.calls)
        assertEquals("IQDB must not be skipped because SauceNAO answered", 1, iqdb.calls)
        assertEquals(ReverseImageState.SUCCESS, result.stateOf("saucenao"))
        assertEquals(ReverseImageState.NO_RESULTS, result.stateOf("iqdb"))
        assertTrue(result.anyAnswered)
    }

    @Test
    fun `one provider failing leaves the other provider's results intact`() {
        var now = 0L
        val sauceNao = FakeSource(
            ReverseImageProviders.SAUCE_NAO_ID,
            "SauceNAO",
            ReverseImageProviders.SAUCE_NAO_PRIORITY,
            advance = { now += it },
            outcome = {
                ReverseImageOutcome.Found(listOf(hit("saucenao", "SauceNAO", 91.0, productId = "RJ01234567")))
            },
        )
        val iqdb = FakeSource(
            ReverseImageProviders.IQDB_ID,
            "IQDB",
            ReverseImageProviders.IQDB_PRIORITY,
            advance = { now += it },
            outcome = { ReverseImageOutcome.Failed(ReverseImageState.TIMEOUT, "no answer") },
        )

        val result = ReverseImageSearchRunner(listOf(sauceNao, iqdb), clock = { now }).run(request)

        assertEquals(ReverseImageState.SUCCESS, result.stateOf("saucenao"))
        assertEquals(ReverseImageState.TIMEOUT, result.stateOf("iqdb"))
        assertEquals(1, result.candidates.size)
        assertEquals(1, result.contributingRuns.size)
        assertEquals("RJ01234567", result.candidates[0].productId)
    }

    @Test
    fun `a late answer is a timeout, never a silent empty result`() {
        var now = 0L
        val slow = FakeSource(
            ReverseImageProviders.IQDB_ID,
            "IQDB",
            ReverseImageProviders.IQDB_PRIORITY,
            timeoutMs = 1_000L,
            cost = 5_000L,
            advance = { now += it },
            outcome = { ReverseImageOutcome.Found(listOf(hit("iqdb", "IQDB", 88.0, url = "https://example.test/1"))) },
        )

        val result = ReverseImageSearchRunner(listOf(slow), clock = { now }).run(request)
        val run = result.runs.single()

        assertEquals(ReverseImageState.TIMEOUT, run.state)
        assertTrue(run.message!!.contains("timeout, not an empty result"))
        assertFalse("a timed-out answer contributes nothing", run.contributed)
        assertTrue(result.candidates.isEmpty())
        assertFalse(result.anyAnswered)
    }

    @Test
    fun `a provider that cannot run is reported as such and never asked`() {
        val unconfigured = FakeSource(
            ReverseImageProviders.SAUCE_NAO_ID,
            "SauceNAO",
            ReverseImageProviders.SAUCE_NAO_PRIORITY,
            ready = ReverseImageState.NOT_CONFIGURED,
            outcome = { ReverseImageOutcome.NoResults() },
        )
        val manual = FakeSource(
            ReverseImageProviders.IQDB_ID,
            "IQDB",
            ReverseImageProviders.IQDB_PRIORITY,
            automation = ReverseImageAutomation.MANUAL_ONLY,
            ready = ReverseImageState.MANUAL_ONLY,
            outcome = { ReverseImageOutcome.NoResults() },
        )

        val result = ReverseImageSearchRunner(listOf(unconfigured, manual)).run(request)

        assertEquals(0, unconfigured.calls)
        assertEquals(0, manual.calls)
        assertEquals(ReverseImageState.NOT_CONFIGURED, result.stateOf("saucenao"))
        assertEquals(ReverseImageState.MANUAL_ONLY, result.stateOf("iqdb"))
        assertFalse("nothing was searched, so nothing may be claimed", result.anyAnswered)
        assertTrue(result.someProvidersWereNotQueried)
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun `cancellation before the first provider sends nothing`() {
        val sauceNao = FakeSource(
            ReverseImageProviders.SAUCE_NAO_ID,
            "SauceNAO",
            ReverseImageProviders.SAUCE_NAO_PRIORITY,
            outcome = {
                ReverseImageOutcome.Found(listOf(hit("saucenao", "SauceNAO", 90.0, url = "https://example.test/a")))
            },
        )

        val result = ReverseImageSearchRunner(listOf(sauceNao)).run(
            request = request,
            token = 4L,
            shouldContinue = { false },
        )

        assertEquals(0, sauceNao.calls)
        assertEquals(ReverseImageState.CANCELLED, result.runs.single().state)
        assertEquals(4L, result.token)
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun `cancelling while the first provider runs stops the second`() {
        var allowed = true
        var now = 0L
        val sauceNao = FakeSource(
            ReverseImageProviders.SAUCE_NAO_ID,
            "SauceNAO",
            ReverseImageProviders.SAUCE_NAO_PRIORITY,
            advance = { now += it },
            outcome = {
                // The user taps Cancel while SauceNAO is in flight.
                allowed = false
                ReverseImageOutcome.Found(listOf(hit("saucenao", "SauceNAO", 90.0, url = "https://example.test/a")))
            },
        )
        val iqdb = FakeSource(
            ReverseImageProviders.IQDB_ID,
            "IQDB",
            ReverseImageProviders.IQDB_PRIORITY,
            advance = { now += it },
            outcome = {
                ReverseImageOutcome.Found(listOf(hit("iqdb", "IQDB", 80.0, url = "https://example.test/b")))
            },
        )

        val result = ReverseImageSearchRunner(listOf(sauceNao, iqdb), clock = { now }).run(
            request = request,
            shouldContinue = { allowed },
        )

        assertEquals(1, sauceNao.calls)
        assertEquals("nothing may be sent after a cancel", 0, iqdb.calls)
        assertEquals(ReverseImageState.CANCELLED, result.stateOf("iqdb"))
        assertEquals(1, result.candidates.size)
    }

    @Test
    fun `a stale token is dropped by the caller, and the result carries the token it ran with`() {
        val sauceNao = FakeSource(
            ReverseImageProviders.SAUCE_NAO_ID,
            "SauceNAO",
            ReverseImageProviders.SAUCE_NAO_PRIORITY,
            outcome = { ReverseImageOutcome.NoResults() },
        )
        val runner = ReverseImageSearchRunner(listOf(sauceNao))

        val stale = runner.run(request = request, token = 1L, shouldContinue = { it == 2L })
        assertEquals(1L, stale.token)
        assertEquals(ReverseImageState.CANCELLED, stale.runs.single().state)

        val current = runner.run(request = request, token = 2L, shouldContinue = { it == 2L })
        assertEquals(2L, current.token)
        assertEquals(ReverseImageState.NO_RESULTS, current.runs.single().state)
    }

    @Test
    fun `a throwing adapter is contained and reported`() {
        val broken = FakeSource(
            ReverseImageProviders.SAUCE_NAO_ID,
            "SauceNAO",
            ReverseImageProviders.SAUCE_NAO_PRIORITY,
            outcome = { throw IllegalStateException("boom") },
        )
        val result = ReverseImageSearchRunner(listOf(broken)).run(request)
        assertEquals(ReverseImageState.ERROR, result.runs.single().state)
        assertTrue(result.runs.single().message!!.contains("boom"))
    }

    @Test
    fun `a candidate found by both providers keeps both references and both numbers`() {
        val sauceNao = FakeSource(
            ReverseImageProviders.SAUCE_NAO_ID,
            "SauceNAO",
            ReverseImageProviders.SAUCE_NAO_PRIORITY,
            outcome = {
                ReverseImageOutcome.Found(
                    listOf(
                        hit(
                            "saucenao",
                            "SauceNAO",
                            95.1,
                            productId = "RJ01234567",
                            url = "https://shop.example.test/a",
                        ),
                    ),
                )
            },
        )
        val iqdb = FakeSource(
            ReverseImageProviders.IQDB_ID,
            "IQDB",
            ReverseImageProviders.IQDB_PRIORITY,
            outcome = {
                ReverseImageOutcome.Found(
                    listOf(hit("iqdb", "IQDB", 92.0, productId = "rj01234567", url = "https://shop.example.test/a")),
                )
            },
        )

        val result = ReverseImageSearchRunner(listOf(sauceNao, iqdb)).run(request)

        assertEquals(1, result.candidates.size)
        val candidate = result.candidates.single()
        assertEquals("id:RJ01234567", candidate.key)
        assertEquals(2, candidate.references.size)
        assertTrue(candidate.foundByMultipleProviders)
        assertEquals(95.1, candidate.similarityOf("saucenao")!!, 0.0001)
        assertEquals(92.0, candidate.similarityOf("iqdb")!!, 0.0001)
        assertEquals("SauceNAO 95.1% \u00B7 IQDB 92%", candidate.evidenceLine())
        assertEquals("SauceNAO is the primary reference", 1, candidate.primaryReference.priority)
    }

    @Test
    fun `two rows with the same title but different pages stay two candidates`() {
        val sauceNao = FakeSource(
            ReverseImageProviders.SAUCE_NAO_ID,
            "SauceNAO",
            ReverseImageProviders.SAUCE_NAO_PRIORITY,
            outcome = {
                ReverseImageOutcome.Found(
                    listOf(
                        hit("saucenao", "SauceNAO", 90.0, url = "https://example.test/edition-a", title = "Same Title"),
                        hit("saucenao", "SauceNAO", 88.0, url = "https://example.test/edition-b", title = "Same Title"),
                    ),
                )
            },
        )

        val result = ReverseImageSearchRunner(listOf(sauceNao)).run(request)
        assertEquals("similar titles are not identity", 2, result.candidates.size)
        assertNotNull(result.candidates.first().primaryReference)
    }
}
