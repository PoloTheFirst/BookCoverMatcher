// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core

import com.bookcovermatcher.core.catalog.CatalogEntry
import com.bookcovermatcher.core.catalog.CatalogField
import com.bookcovermatcher.core.catalog.FieldState
import com.bookcovermatcher.core.catalog.FieldValue
import com.bookcovermatcher.core.online.CandidateMatcher
import com.bookcovermatcher.core.online.CataloguePresence
import com.bookcovermatcher.core.online.CollectionStatus
import com.bookcovermatcher.core.online.CollectionStatusOf
import com.bookcovermatcher.core.online.EvidenceState
import com.bookcovermatcher.core.online.LocalProduct
import com.bookcovermatcher.core.online.LocalProducts
import com.bookcovermatcher.core.online.MatchVerdict
import com.bookcovermatcher.core.online.ProductSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.5.0-dev.2 — the three collection facts stay independent.
 *
 * These tests exist because the tempting shortcuts are all wrong: owning something does not mean it is in the
 * workbook, being in the workbook does not mean it is wanted, and an empty workbook cannot be used to claim that
 * something is absent.
 */
class CollectionStatusTest {

    private fun entry(
        id: String,
        title: String?,
        circle: String? = null,
        artist: String? = null,
        identifier: String? = null,
    ): CatalogEntry {
        val values = LinkedHashMap<CatalogField, FieldValue>()
        values[CatalogField.TITLE] = if (title != null) {
            FieldValue(CatalogField.TITLE, FieldState.MAPPED, title)
        } else {
            FieldValue(CatalogField.TITLE, FieldState.MISSING)
        }
        values[CatalogField.CIRCLE] = circle?.let { FieldValue(CatalogField.CIRCLE, FieldState.MAPPED, it) }
            ?: FieldValue(CatalogField.CIRCLE, FieldState.UNMAPPED)
        values[CatalogField.ARTIST] = artist?.let { FieldValue(CatalogField.ARTIST, FieldState.MAPPED, it) }
            ?: FieldValue(CatalogField.ARTIST, FieldState.UNMAPPED)
        values[CatalogField.IDENTIFIER] = identifier?.let {
            FieldValue(CatalogField.IDENTIFIER, FieldState.MAPPED, it)
        } ?: FieldValue(CatalogField.IDENTIFIER, FieldState.UNMAPPED)
        return CatalogEntry(
            id = id,
            workbookSha256 = "sha",
            sheetName = "LIST",
            sheetPath = "xl/worksheets/sheet1.xml",
            row = 3,
            values = values,
            images = emptyList(),
        )
    }

    private fun candidate(title: String, circle: String? = null, identifier: String? = null) = ProductSearchResult(
        id = identifier ?: "id-$title",
        title = title,
        authors = emptyList(),
        circle = circle,
        publishedYear = null,
        publishers = emptyList(),
        pageCount = null,
        languages = emptyList(),
        coverUrl = null,
        detailUrl = "https://shop.example.test/item",
        source = "DLsite",
        providerId = "dl",
        identifier = identifier,
    )

    @Test
    fun `a workbook record becomes a local product using only mapped values`() {
        val local = LocalProducts.of(entry("LIST#3", "Blue Archive", circle = "Yostar", identifier = "RJ01234567"))
        assertEquals("LIST#3", local.key)
        assertEquals("Blue Archive", local.title)
        assertEquals("Yostar", local.circle)
        assertEquals("RJ01234567", local.identifier)
        assertEquals("", local.author)

        // An unmapped circle is empty, not a contradicting value: the matcher must report it as unknown.
        val bare = LocalProducts.of(entry("LIST#4", "Blue Archive"))
        assertEquals("", bare.circle)
        val match = CandidateMatcher.compare(candidate("Blue Archive", circle = "Yostar"), bare)
        assertEquals(EvidenceState.UNKNOWN, match.evidence.first { it.dimension == "circle" }.state)
    }

    @Test
    fun `catalogue presence is derived from the comparison, never from ownership`() {
        val local = LocalProduct("LIST#3", "Blue Archive", circle = "Yostar")
        val matches = CandidateMatcher.rank(candidate("Blue Archive", circle = "Yostar"), listOf(local))
        val status = CollectionStatusOf.of(matches, owned = true, wanted = false)
        assertEquals(CataloguePresence.MATCHED, status.catalogued)
        assertEquals(true, status.owned)
        assertEquals(false, status.wanted)
        assertEquals("Owned: yes \u00B7 In catalogue: matched \u00B7 Wanted: no", status.describe())
    }

    @Test
    fun `an empty workbook cannot claim absence`() {
        val status = CollectionStatusOf.of(matches = emptyList(), catalogueHasRecords = false)
        assertEquals(CataloguePresence.UNKNOWN, status.catalogued)
        assertNull(status.owned)
        assertNull(status.wanted)
        assertEquals("Owned: not stated \u00B7 In catalogue: unknown \u00B7 Wanted: not stated", status.describe())
    }

    @Test
    fun `a searched workbook with no agreeing record is an absence, not an unknown`() {
        val local = LocalProduct("LIST#9", "Crimson Echo", circle = "Nobody")
        val matches = CandidateMatcher.rank(candidate("Blue Archive", circle = "Yostar"), listOf(local))
        assertEquals(MatchVerdict.NO_MATCH, matches.first().verdict)
        assertEquals(CataloguePresence.ABSENT, CollectionStatusOf.of(matches, catalogueHasRecords = true).catalogued)
    }

    @Test
    fun `two plausible records are ambiguous rather than one being picked`() {
        val locals = listOf(
            LocalProduct("A", "Blue Archive", circle = "Yostar"),
            LocalProduct("B", "Blue Archive", circle = "Yostar"),
        )
        val matches = CandidateMatcher.rank(candidate("Blue Archive", circle = "Yostar"), locals)
        assertEquals(CataloguePresence.AMBIGUOUS, CollectionStatusOf.of(matches, catalogueHasRecords = true).catalogued)
    }

    @Test
    fun `wanted and owned are independent booleans, and unstated stays null`() {
        val wantedOnly = CollectionStatus(owned = null, catalogued = CataloguePresence.ABSENT, wanted = true)
        assertEquals(CataloguePresence.ABSENT, wantedOnly.catalogued)
        assertEquals(true, wantedOnly.wanted)
        assertNull(wantedOnly.owned)

        val ownedNotWanted = CollectionStatus(owned = true, catalogued = CataloguePresence.MATCHED, wanted = false)
        assertTrue(ownedNotWanted.describe().contains("Owned: yes"))
        assertTrue(ownedNotWanted.describe().contains("Wanted: no"))
    }
}
