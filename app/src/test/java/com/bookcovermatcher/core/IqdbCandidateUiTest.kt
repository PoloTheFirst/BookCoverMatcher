package com.bookcovermatcher.core

import com.bookcovermatcher.ReverseCandidateUi
import com.bookcovermatcher.toUi
import com.bookcovermatcher.core.online.CandidateDecision
import com.bookcovermatcher.core.online.reverse.ReverseImageAggregator
import com.bookcovermatcher.core.online.reverse.ReverseImageHit
import com.bookcovermatcher.core.online.reverse.ReverseImageProviders
import com.bookcovermatcher.core.online.reverse.ReverseImageRun
import com.bookcovermatcher.core.online.reverse.ReverseImageState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.5.0-dev.3 — which cards get the rubbish-bin control.
 *
 * The rule is deliberately narrower than "all reverse-image cards": the dismissal removes a whole card, so it may
 * only appear where the card's only evidence is IQDB. A candidate SauceNAO also reported must stay, otherwise one
 * tap would silently throw away the first-priority provider's result.
 */
class IqdbCandidateUiTest {

    private fun hit(providerId: String, name: String, url: String, productId: String = "") = ReverseImageHit(
        providerId = providerId,
        providerName = name,
        providerResultId = url,
        sourceUrl = url,
        productId = productId,
        providerSimilarity = 90.0,
    )

    private fun candidateOf(vararg hits: ReverseImageHit): ReverseCandidateUi {
        val runs = hits.groupBy { it.providerId }.map { (providerId, group) ->
            ReverseImageRun(
                providerId = providerId,
                displayName = group.first().providerName,
                priority = ReverseImageProviders.priorityOf(providerId),
                state = ReverseImageState.SUCCESS,
                hits = group,
            )
        }
        return ReverseImageAggregator.merge(runs).single().toUi(CandidateDecision.SUGGESTED)
    }

    @Test
    fun `an IQDB-only candidate is dismissible and carries its provider attribution`() {
        val candidate = candidateOf(hit(ReverseImageProviders.IQDB_ID, "IQDB", "https://example.test/iqdb/1"))
        assertTrue(candidate.isIqdbOnly)
        assertEquals(listOf("iqdb"), candidate.providerIds)
        assertEquals(1, candidate.providerCount)
        assertTrue("the evidence line still names the provider", candidate.evidence.contains("IQDB"))
    }

    @Test
    fun `a SauceNAO-only candidate is not dismissible`() {
        val candidate = candidateOf(
            hit(ReverseImageProviders.SAUCE_NAO_ID, "SauceNAO", "https://example.test/sn/1", productId = "RJ01234567"),
        )
        assertFalse(candidate.isIqdbOnly)
        assertEquals(listOf("saucenao"), candidate.providerIds)
    }

    @Test
    fun `a candidate both providers found keeps its SauceNAO evidence and is not dismissible`() {
        val shared = "RJ01234567"
        val candidate = candidateOf(
            hit(ReverseImageProviders.SAUCE_NAO_ID, "SauceNAO", "https://example.test/item", productId = shared),
            hit(ReverseImageProviders.IQDB_ID, "IQDB", "https://example.test/item", productId = shared),
        )
        assertTrue(candidate.providerIds.containsAll(listOf("saucenao", "iqdb")))
        assertFalse("dismissing this would drop SauceNAO's evidence", candidate.isIqdbOnly)
        assertEquals(2, candidate.providerCount)
    }

    @Test
    fun `a candidate with no provider information is never dismissible`() {
        val bare = ReverseCandidateUi(
            key = "url:https://example.test/x",
            title = "Something",
            circle = "",
            author = "",
            productId = "",
            evidence = "",
            sourceUrl = null,
            imageUrl = null,
            providerCount = 0,
            decision = CandidateDecision.SUGGESTED,
        )
        assertFalse(bare.isIqdbOnly)
    }

    @Test
    fun `the provider list survives the UI mapping in priority order`() {
        val candidate = candidateOf(
            hit(ReverseImageProviders.IQDB_ID, "IQDB", "https://example.test/iq", productId = "RJ01234567"),
            hit(ReverseImageProviders.SAUCE_NAO_ID, "SauceNAO", "https://example.test/sn", productId = "RJ01234567"),
        )
        assertEquals(listOf("saucenao", "iqdb"), candidate.providerIds)
        assertFalse(candidate.isIqdbOnly)
    }
}
