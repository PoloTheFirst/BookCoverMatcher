// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core

import com.bookcovermatcher.ReverseCandidateUi
import com.bookcovermatcher.toDraft
import com.bookcovermatcher.core.online.CandidateDecision
import com.bookcovermatcher.core.online.DiscoveryDrafts
import com.bookcovermatcher.core.wishlist.ItemIdentity
import com.bookcovermatcher.core.wishlist.ItemKind
import com.bookcovermatcher.core.wishlist.MatchType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.5.0-dev.4 — a discovery result becomes a wishlist record without losing (or inventing) anything.
 *
 * The identity rule is what keeps repeated clicks, and repeats across searches, from duplicating a record: the
 * candidate's own key is the item's fingerprint. The attribution rule keeps "who found it" in the record, because a
 * SauceNAO hit and an IQDB hit are not the same evidence.
 */
class DiscoveryDraftsTest {

    private fun candidate(
        key: String = "id:RJ01234567",
        title: String = "Example Work",
        circle: String = "",
        author: String = "",
        productId: String = "RJ01234567",
        sourceUrl: String? = "https://www.dlsite.com/maniax/work/=/product_id/RJ01234567.html",
        providerIds: List<String> = listOf("saucenao"),
        decision: CandidateDecision = CandidateDecision.SUGGESTED,
        imageBytes: ByteArray? = null,
    ) = ReverseCandidateUi(
        key = key,
        title = title,
        circle = circle,
        author = author,
        productId = productId,
        evidence = "SauceNAO 95.1%",
        sourceUrl = sourceUrl,
        imageUrl = null,
        providerCount = providerIds.size,
        decision = decision,
        providerIds = providerIds,
        imageBytes = imageBytes,
    )

    @Test
    fun `the candidate key is the item identity`() {
        assertEquals("id:rj01234567", DiscoveryDrafts.fingerprintOf(" id:RJ01234567 "))
        val draft = candidate().toDraft()
        assertEquals(DiscoveryDrafts.fingerprintOf("id:RJ01234567"), draft.fingerprint)
        // Two searches that return the same result produce the same fingerprint, so the store cannot duplicate it.
        assertEquals(candidate().toDraft().fingerprint, candidate().toDraft().fingerprint)
    }

    @Test
    fun `two providers on one card become one attributed record`() {
        val draft = candidate(providerIds = listOf("iqdb", "saucenao")).toDraft()
        assertEquals("SauceNAO + IQDB", draft.provider)
    }

    @Test
    fun `a single provider is named plainly`() {
        assertEquals("SauceNAO", candidate(providerIds = listOf("saucenao")).toDraft().provider)
        assertEquals("IQDB", candidate(providerIds = listOf("iqdb")).toDraft().provider)
    }

    @Test
    fun `an unknown or empty provider list is not invented`() {
        assertEquals("Discovery", DiscoveryDrafts.providerLabel(emptyList()))
        assertEquals("Discovery", DiscoveryDrafts.providerLabel(listOf("bing-visual")))
    }

    @Test
    fun `rejected candidates are not eligible suggested and confirmed are`() {
        assertFalse(DiscoveryDrafts.isEligible(CandidateDecision.REJECTED))
        assertTrue(DiscoveryDrafts.isEligible(CandidateDecision.SUGGESTED))
        assertTrue(DiscoveryDrafts.isEligible(CandidateDecision.CONFIRMED))
    }

    @Test
    fun `a result with no fact to store is not storable`() {
        assertFalse(DiscoveryDrafts.isStorable(title = "", productId = "", sourceUrl = null))
        assertFalse(DiscoveryDrafts.isStorable(title = "  ", productId = " ", sourceUrl = " "))
        assertTrue(DiscoveryDrafts.isStorable(title = "Work", productId = "", sourceUrl = null))
        assertTrue(DiscoveryDrafts.isStorable(title = "", productId = "RJ01234567", sourceUrl = null))
        assertTrue(DiscoveryDrafts.isStorable(title = "", productId = "", sourceUrl = "https://example.com/w"))
    }

    @Test
    fun `the draft keeps the metadata the provider sent`() {
        val draft = candidate(circle = "Circle A", author = "Artist B").toDraft()
        assertEquals(ItemKind.ONLINE_RESULT, draft.kind)
        assertEquals("Example Work", draft.title)
        assertEquals("Circle A", draft.circle)
        assertEquals(listOf("Artist B"), draft.authors)
        assertEquals("RJ01234567", draft.identifier)
        assertEquals("https://www.dlsite.com/maniax/work/=/product_id/RJ01234567.html", draft.url)
    }

    @Test
    fun `a reverse-image match is recorded as a verified visual match`() {
        val draft = candidate().toDraft()
        assertEquals(MatchType.VISUAL, draft.matchType)
        assertTrue(draft.matchTypeVerified)
    }

    @Test
    fun `missing metadata is left missing rather than guessed`() {
        val draft = candidate(circle = "", author = "", productId = "").toDraft()
        assertNull(draft.circle)
        assertEquals(emptyList<String>(), draft.authors)
        assertNull(draft.identifier)
        assertNull(draft.price)
        assertNull(draft.availability)
        assertNull(draft.thumbJpeg)
    }

    @Test
    fun `the title falls back to the product id then the url`() {
        assertEquals("RJ01234567", candidate(title = "").toDraft().title)
        assertEquals(
            "https://www.dlsite.com/maniax/work/=/product_id/RJ01234567.html",
            candidate(title = "", productId = "").toDraft().title,
        )
    }

    @Test
    fun `an extracted picture is stored and its absence is not an error`() {
        val bytes = byteArrayOf(7, 7, 7)
        assertEquals(bytes, candidate(imageBytes = bytes).toDraft().thumbJpeg)
        assertNull(candidate(imageBytes = null).toDraft().thumbJpeg)
    }

    @Test
    fun `the same product reported by both providers keeps one identity`() {
        val sauceNao = candidate(providerIds = listOf("saucenao")).toDraft()
        val both = candidate(providerIds = listOf("saucenao", "iqdb")).toDraft()
        // The aggregator merges the two providers into one card before this point, so both drafts share one key and
        // the store sees a single record rather than two provider-specific duplicates.
        assertEquals(sauceNao.fingerprint, both.fingerprint)
        assertEquals(ItemIdentity.fingerprintFor(providerKey = "id:RJ01234567"), both.fingerprint)
    }
}
