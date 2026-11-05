// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core

import com.bookcovermatcher.core.online.CandidateDecision
import com.bookcovermatcher.core.online.CandidateDecisionRecord
import com.bookcovermatcher.core.online.CandidateReviews
import com.bookcovermatcher.core.online.OnlineAggregator
import com.bookcovermatcher.core.online.ProductSearchResult
import com.bookcovermatcher.core.wishlist.ItemDraft
import com.bookcovermatcher.core.wishlist.ItemIdentity
import com.bookcovermatcher.core.wishlist.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.5.0-dev.2 — a suggestion is not a confirmation, and the same result is one wishlist identity.
 *
 * The rules here are the ones that keep the app honest after the user taps something: a decision replaces rather
 * than accumulates, it is keyed by the candidate's stable identity, and re-adding the same candidate cannot create
 * a second entry.
 */
class CandidateReviewTest {

    private fun candidate(title: String, id: String = "RJ01234567"): ProductSearchResult = ProductSearchResult(
        id = id,
        title = title,
        authors = emptyList(),
        publishedYear = null,
        publishers = emptyList(),
        pageCount = null,
        languages = emptyList(),
        coverUrl = null,
        detailUrl = "https://shop.example.test/work/=/product_id/$id.html",
        source = "DLsite",
        providerId = "dl",
        identifier = id,
    )

    @Test
    fun `a fresh candidate is a suggestion, not a decision`() {
        val records = emptyMap<String, CandidateDecisionRecord>()
        assertEquals(CandidateDecision.SUGGESTED, CandidateReviews.stateOf(records, "dl:rj01234567"))
        assertEquals(1, CandidateReviews.undecided(records, listOf(candidate("Blue Archive"))).size)
        assertEquals("Suggestion \u2014 not confirmed by you", CandidateReviews.SUGGESTION_LABEL)
    }

    @Test
    fun `confirming is idempotent and the decision is keyed by identity`() {
        val item = candidate("Blue Archive")
        var records = CandidateReviews.apply(emptyMap(), item, CandidateDecision.CONFIRMED, now = 10L)
        records = CandidateReviews.apply(records, item, CandidateDecision.CONFIRMED, now = 20L)
        assertEquals(1, records.size)
        assertEquals(20L, records.values.single().decidedAtMillis)
        assertEquals(CandidateDecision.CONFIRMED, CandidateReviews.stateOf(records, OnlineAggregator.itemKey(item)))
        assertTrue(CandidateReviews.confirmedKeys(records).contains(OnlineAggregator.itemKey(item)))
        assertTrue(CandidateReviews.undecided(records, listOf(item)).isEmpty())
    }

    @Test
    fun `a rejection is recorded and can be revisited`() {
        val item = candidate("Blue Archive")
        var records = CandidateReviews.apply(emptyMap(), item, CandidateDecision.REJECTED, now = 10L)
        assertTrue(CandidateReviews.rejectedKeys(records).isNotEmpty())
        records = CandidateReviews.apply(records, item, CandidateDecision.CONFIRMED, now = 30L)
        assertEquals(CandidateDecision.CONFIRMED, records.values.single().decision)
        assertTrue(CandidateReviews.rejectedKeys(records).isEmpty())
    }

    @Test
    fun `a manual candidate is reviewed by its own key`() {
        val key = "manual:blue archive"
        val records = CandidateReviews.applyKey(
            emptyMap(),
            key = key,
            decision = CandidateDecision.CONFIRMED,
            now = 5L,
            title = "Blue Archive",
            provider = "Manual listing",
            url = null,
        )
        assertEquals(CandidateDecision.CONFIRMED, CandidateReviews.stateOf(records, key))
        assertNull(records.getValue(key).url)
        // A blank key changes nothing: a candidate with no identity cannot be decided about.
        assertEquals(records, CandidateReviews.applyKey(records, "", CandidateDecision.REJECTED, now = 9L))
    }

    @Test
    fun `the same candidate added twice is one wishlist identity`() {
        val item = candidate("Blue Archive")
        fun draft() = ItemDraft(
            kind = ItemKind.ONLINE_RESULT,
            title = item.title,
            url = item.detailUrl,
            provider = item.source,
            providerKey = OnlineAggregator.itemKey(item),
            identifier = item.identifier,
        )
        assertEquals(draft().fingerprint, draft().fingerprint)
        assertEquals(
            "a repeated add resolves to the same store identity",
            draft().fingerprint,
            ItemIdentity.fingerprintFor(
                providerKey = OnlineAggregator.itemKey(item),
                url = item.detailUrl,
                title = item.title,
            ),
        )
        assertFalse(draft().fingerprint.isBlank())
    }
}
