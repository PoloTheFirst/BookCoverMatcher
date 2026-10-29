package com.bookcovermatcher.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gallery's filtering and ordering are pure, so the browser cannot disagree with the tests. */
class GalleryViewTest {

    private fun entries(): List<CatalogEntry> = CatalogFixtures.catalog(
        records = listOf(
            CatalogFixtures.catalogueRecord(1, title = "Beta", circle = "C2", artist = "A1", identifier = "ID-1"),
            CatalogFixtures.catalogueRecord(2, title = "Alpha", circle = "C1", artist = "", identifier = "ID-2"),
            CatalogFixtures.catalogueRecord(3, title = "", circle = "C1", artist = "A2", identifier = "ID-3", price = ""),
        ),
        images = listOf(CatalogFixtures.linked("xl/media/image1.png", row = 1)),
    ).entries

    @Test
    fun `title sort is case insensitive and puts missing titles last`() {
        val rows = GalleryView.rows(entries(), GalleryFilter(), GallerySort.TITLE)
        assertEquals(listOf("Alpha", "Beta", ""), rows.map { it.title.orEmpty() })
    }

    @Test
    fun `descending order reverses the sort`() {
        val rows = GalleryView.rows(entries(), GalleryFilter(), GallerySort.TITLE, ascending = false)
        assertEquals(listOf("", "Beta", "Alpha"), rows.map { it.title.orEmpty() })
    }

    @Test
    fun `source order follows the worksheet rows`() {
        val rows = GalleryView.rows(entries(), GalleryFilter(), GallerySort.ROW)
        assertEquals(listOf(1, 2, 3), rows.map { it.row })
    }

    @Test
    fun `cover first puts the record with a verified cover on top`() {
        val rows = GalleryView.rows(entries(), GalleryFilter(), GallerySort.COVER)
        assertEquals(1, rows.first().row)
        assertTrue(rows.first().hasVerifiedCover)
    }

    @Test
    fun `the cover filters are opposites`() {
        val withCover = GalleryView.rows(entries(), GalleryFilter(onlyWithCover = true), GallerySort.ROW)
        val without = GalleryView.rows(entries(), GalleryFilter(onlyWithoutCover = true), GallerySort.ROW)
        assertEquals(listOf(1), withCover.map { it.row })
        assertEquals(listOf(2, 3), without.map { it.row })
    }

    @Test
    fun `the query searches title circle artist and identifier`() {
        assertEquals(listOf(1), GalleryView.rows(entries(), GalleryFilter(query = "beta"), GallerySort.ROW).map { it.row })
        assertEquals(listOf(2, 3), GalleryView.rows(entries(), GalleryFilter(query = "C1"), GallerySort.ROW).map { it.row })
        assertEquals(listOf(3), GalleryView.rows(entries(), GalleryFilter(query = "A2"), GallerySort.ROW).map { it.row })
        assertEquals(listOf(2), GalleryView.rows(entries(), GalleryFilter(query = "id-2"), GallerySort.ROW).map { it.row })
    }

    @Test
    fun `missing metadata and missing title filters are honest`() {
        assertEquals(listOf(3), GalleryView.rows(entries(), GalleryFilter(onlyMissingTitle = true), GallerySort.ROW).map { it.row })
        val missing = GalleryView.rows(entries(), GalleryFilter(onlyMissingMetadata = true), GallerySort.ROW)
        assertEquals(listOf(2, 3), missing.map { it.row })
        assertEquals(1, GalleryView.missingMetadata(entries().first { it.row == 2 }))
        assertEquals(0, GalleryView.missingMetadata(entries().first { it.row == 1 }))
    }

    @Test
    fun `a worksheet filter keeps only that sheet`() {
        val rows = GalleryView.rows(entries(), GalleryFilter(sheetName = "EXTRA"), GallerySort.ROW)
        assertTrue(rows.isEmpty())
        assertEquals(3, GalleryView.rows(entries(), GalleryFilter(sheetName = "LIST"), GallerySort.ROW).size)
    }

    @Test
    fun `an inactive filter reports itself as inactive`() {
        assertFalse(GalleryFilter().isActive)
        assertTrue(GalleryFilter(query = "x").isActive)
        assertTrue(GalleryFilter(onlyWithCover = true).isActive)
    }

    @Test
    fun `ties break on the record id so the grid is stable`() {
        val catalog = CatalogFixtures.catalog(
            records = listOf(
                CatalogFixtures.catalogueRecord(1, title = "Same"),
                CatalogFixtures.catalogueRecord(2, title = "Same"),
                CatalogFixtures.catalogueRecord(3, title = "Same"),
            ),
        )
        val once = GalleryView.rows(catalog.entries, GalleryFilter(), GallerySort.TITLE).map { it.id }
        val twice = GalleryView.rows(catalog.entries.reversed(), GalleryFilter(), GallerySort.TITLE).map { it.id }
        assertEquals(once, twice)
        assertEquals(listOf(1, 2, 3), once.map { it.substringAfterLast('#').toInt() })
    }
}