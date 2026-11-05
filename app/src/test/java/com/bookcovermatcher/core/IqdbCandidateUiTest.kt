// SPDX-License-Identifier: GPL-3.0-or-later

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
 * v2.5.0-dev.3, extended in v2.5.0-dev.4 — which cards get the rubbish-bin control, and how it is labelled.
 *
 * dev.3 gave the control to IQDB-only cards only. dev.4 requires every SauceNAO card to be dismissible too, so the
 * rule is now: any card a reverse-image provider reported is dismissible, and the label names the provider(s) whose
 * evidence the tap removes. A card both providers reported is one card holding both references, so it is labelled
 * "discovery" rather than pretending only one provider is affected.
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
        assertTrue(candidate.isDismissible)
        assertEquals("Dismiss IQDB result", candidate.dismissLabel)
        assertEquals(listOf("iqdb"), candidate.providerIds)
        assertEquals(1, candidate.providerCount)
        assertTrue("the evidence line still names the provider", candidate.evidence.contains("IQDB"))
    }

    @Test
    fun `a SauceNAO-only candidate is dismissible in dev 4`() {
        val candidate = candidateOf(
            hit(ReverseImageProviders.SAUCE_NAO_ID, "SauceNAO", "https://example.test/sn/1", productId = "RJ01234567"),
        )
        assertFalse(candidate.isIqdbOnly)
        assertTrue(candidate.isSauceNaoOnly)
        assertTrue(candidate.isDismissible)
        assertEquals("Dismiss SauceNAO result", candidate.dismissLabel)
        assertEquals(listOf("saucenao"), candidate.providerIds)
    }

    @Test
    fun `a candidate both providers found is dismissed as a whole with a combined label`() {
        val shared = "RJ01234567"
        val candidate = candidateOf(
            hit(ReverseImageProviders.SAUCE_NAO_ID, "SauceNAO", "https://example.test/item", productId = shared),
            hit(ReverseImageProviders.IQDB_ID, "IQDB", "https://example.test/item", productId = shared),
        )
        assertTrue(candidate.providerIds.containsAll(listOf("saucenao", "iqdb")))
        assertFalse("IQDB is not the only provider here", candidate.isIqdbOnly)
        assertTrue(candidate.isDismissible)
        assertEquals("Dismiss discovery result", candidate.dismissLabel)
        assertEquals("SauceNAO + IQDB", candidate.providerLabel)
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
        assertFalse(bare.isSauceNaoOnly)
        assertFalse(bare.isDismissible)
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
