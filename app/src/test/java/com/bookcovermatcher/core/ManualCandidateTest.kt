package com.bookcovermatcher.core

import com.bookcovermatcher.core.online.ManualCandidates
import com.bookcovermatcher.core.online.ManualListing
import com.bookcovermatcher.core.online.ManualListingResult
import com.bookcovermatcher.core.online.OnlineAggregator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.5.0-dev.2 — a listing the user typed is normalised into the same candidate model as an automated result.
 *
 * The rules pinned here are what stop a manual entry from becoming a lie: it keeps the `manual` provider id, it
 * is validated rather than trusted, it never invents a URL, and the same listing entered twice is the same
 * candidate.
 */
class ManualCandidateTest {

    @Test
    fun `a title alone is enough, and the provenance is marked manual`() {
        val result = ManualCandidates.validate(ManualListing(title = "  Blue   Archive  "))
        assertTrue(result is ManualListingResult.Valid)
        val valid = result as ManualListingResult.Valid
        assertEquals("Blue Archive", valid.listing.title)
        assertEquals("Blue Archive", valid.candidate.title)
        assertEquals(ManualCandidates.PROVIDER_ID, valid.candidate.providerId)
        assertEquals(ManualCandidates.SOURCE_NAME, valid.candidate.source)
        assertEquals("", valid.candidate.detailUrl)
    }

    @Test
    fun `a product code alone is enough and is canonicalised`() {
        val result = ManualCandidates.validate(ManualListing(identifier = " rj 0123 4567 "))
        val candidate = (result as ManualListingResult.Valid).candidate
        assertEquals("RJ01234567", candidate.identifier)
        assertEquals("RJ01234567", candidate.title)
        assertEquals("rj01234567", candidate.id)
    }

    @Test
    fun `an identifier that is not an rj code is only trimmed, never reinterpreted`() {
        assertEquals("DL-9981", ManualCandidates.normalizeIdentifier(" dl-9981 "))
        assertEquals("", ManualCandidates.normalizeIdentifier("   "))
    }

    @Test
    fun `nothing identifying is refused, and the reason is specific`() {
        val result = ManualCandidates.validate(ManualListing(note = "I saw it somewhere"))
        assertTrue(result is ManualListingResult.Invalid)
        val errors = (result as ManualListingResult.Invalid).errors
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("title or a product ID"))
    }

    @Test
    fun `a non-http address is refused rather than stored`() {
        val result = ManualCandidates.validate(ManualListing(title = "Something", url = "ftp://example.test/x"))
        assertTrue(result is ManualListingResult.Invalid)
        assertTrue((result as ManualListingResult.Invalid).errors[0].contains("http"))
    }

    @Test
    fun `a real listing keeps its url verbatim and becomes a shop-style record`() {
        val listing = ManualListing(
            url = "https://shop.example.test/work/=/product_id/RJ01234567.html?ref=abc",
            title = "同人誌・特別編",
            circle = "Yostar",
            author = "Someone",
            identifier = "RJ01234567",
            providerLabel = "Melonbooks",
            note = "seen in the browser",
        )
        val candidate = (ManualCandidates.validate(listing) as ManualListingResult.Valid).candidate
        assertEquals(listing.url, candidate.detailUrl)
        assertEquals("同人誌・特別編", candidate.title)
        assertEquals("Yostar", candidate.circle)
        assertEquals(listOf("Someone"), candidate.authors)
        assertEquals("Melonbooks", candidate.source)
        assertEquals("seen in the browser", candidate.description)
        assertTrue("a listing with a circle is a shop-style record", candidate.isMarketplace)
    }

    @Test
    fun `the same listing entered twice is one identity`() {
        val first = ManualCandidates.validate(ManualListing(title = "Blue Archive", identifier = "rj01234567"))
        val second = ManualCandidates.validate(ManualListing(title = "Blue  Archive ", identifier = "RJ-01234567"))
        val a = (first as ManualListingResult.Valid).candidate
        val b = (second as ManualListingResult.Valid).candidate
        assertEquals(OnlineAggregator.itemKey(a), OnlineAggregator.itemKey(b))
        assertEquals(ManualCandidates.PROVIDER_ID, a.providerId)
        assertFalse(a.providerId == "dl")
    }

    @Test
    fun `a candidate with no url has no url, instead of an invented one`() {
        val candidate = (ManualCandidates.validate(ManualListing(title = "No Link")) as ManualListingResult.Valid).candidate
        assertEquals("", candidate.detailUrl)
        assertNull(com.bookcovermatcher.core.wishlist.ItemIdentity.normaliseUrl(candidate.detailUrl))
    }
}
