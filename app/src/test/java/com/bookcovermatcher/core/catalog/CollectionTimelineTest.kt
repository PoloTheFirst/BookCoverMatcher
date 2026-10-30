package com.bookcovermatcher.core.catalog

import com.bookcovermatcher.core.excel.comments.CatalogueRecord
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Date meanings stay apart: publication, acquisition and import are never substituted for each other. */
class CollectionTimelineTest {

    private fun timeline(
        records: List<CatalogueRecord>,
        ascending: Boolean = false,
        dates: TimelineDateSettings = CatalogFixtures.dates(),
    ): CollectionTimeline = CollectionTimelineBuilder.published(
        CatalogFixtures.catalog(records = records),
        dates,
        ascending = ascending,
    )

    @Test
    fun `a year month and day are read at their own precision`() {
        assertEquals(CatalogDate(2024), CatalogDates.parse("2024"))
        assertEquals(CatalogDate(2024), CatalogDates.parse("2024年"))
        assertEquals(CatalogDate(2024, 5), CatalogDates.parse("2024-05"))
        assertEquals(CatalogDate(2024, 5), CatalogDates.parse("2024/5"))
        assertEquals(CatalogDate(2024, 5), CatalogDates.parse("2024年5月"))
        assertEquals(CatalogDate(2024, 5, 17), CatalogDates.parse("2024-05-17"))
        assertEquals(CatalogDate(2024, 5, 17), CatalogDates.parse("2024/5/17"))
        assertEquals(CatalogDate(2024, 5, 17), CatalogDates.parse("2024.5.17"))
        assertEquals(CatalogDate(2024, 5, 17), CatalogDates.parse("2024年5月17日"))
        assertEquals("2024-05-17", CatalogDate(2024, 5, 17).label)
        assertEquals("2024-05", CatalogDate(2024, 5).label)
        assertEquals("2024", CatalogDate(2024).label)
    }

    @Test
    fun `ambiguous or impossible dates are not read`() {
        assertNull(CatalogDates.parse("17/05/2024"))
        assertNull(CatalogDates.parse("45000"))
        assertNull(CatalogDates.parse("2024-13"))
        assertNull(CatalogDates.parse("2024-02-30"))
        assertNull(CatalogDates.parse("2023-02-29"))
        assertNull(CatalogDates.parse("Spring 2024"))
        assertNull(CatalogDates.parse(""))
        assertNull(CatalogDates.parse(null))
        assertTrue(CatalogDates.isMalformed("17/05/2024"))
        assertFalse(CatalogDates.isMalformed("2024-05-17"))
        assertFalse(CatalogDates.isMalformed(""))
    }

    @Test
    fun `a leap day is only valid in a leap year`() {
        assertEquals(CatalogDate(2024, 2, 29), CatalogDates.parse("2024-02-29"))
        assertNull(CatalogDates.parse("2022-02-29"))
    }

    @Test
    fun `publication sections are newest first by default`() {
        val result = timeline(
            records = listOf(
                CatalogFixtures.catalogueRecord(1, published = "2021"),
                CatalogFixtures.catalogueRecord(2, published = "2024-05"),
                CatalogFixtures.catalogueRecord(3, published = "2022-11-02"),
            ),
        )
        assertTrue(result.available)
        assertEquals(listOf(2024, 2022, 2021), result.sections.map { it.year })
        assertEquals(3, result.datedCount)
        assertEquals(0, result.unknownCount)
        assertEquals(TimelineMode.PUBLICATION, result.mode)
    }

    @Test
    fun `ascending order reverses the sections`() {
        val result = timeline(
            records = listOf(
                CatalogFixtures.catalogueRecord(1, published = "2021"),
                CatalogFixtures.catalogueRecord(2, published = "2024"),
            ),
            ascending = true,
        )
        assertEquals(listOf(2021, 2024), result.sections.map { it.year })
        assertTrue(result.ascending)
    }

    @Test
    fun `entries inside a year are ordered by their full date`() {
        val result = timeline(
            records = listOf(
                CatalogFixtures.catalogueRecord(1, published = "2024-01-05"),
                CatalogFixtures.catalogueRecord(2, published = "2024-12-31"),
                CatalogFixtures.catalogueRecord(3, published = "2024-06"),
            ),
        )
        val entries = result.sections.single().entries
        assertEquals(listOf(2, 3, 1), entries.map { it.row })
        assertEquals("2024-12-31", entries.first().dateLabel)
    }

    @Test
    fun `records without a readable date stay in the unknown group`() {
        val result = timeline(
            records = listOf(
                CatalogFixtures.catalogueRecord(1, published = "2024"),
                CatalogFixtures.catalogueRecord(2, published = ""),
                CatalogFixtures.catalogueRecord(3, published = "17/05/2024"),
            ),
        )
        assertEquals(1, result.datedCount)
        assertEquals(2, result.unknownCount)
        assertEquals(3, result.total)
        assertTrue(result.unknown.all { it.date == null })
        assertEquals("No date", result.unknown.first().dateLabel)
    }

    @Test
    fun `a workbook with no readable dates is unavailable but keeps every record`() {
        val result = timeline(records = listOf(CatalogFixtures.catalogueRecord(1, published = "")))
        assertFalse(result.available)
        assertEquals(1, result.unknownCount)
        assertTrue(result.unavailableReason!!.contains("cannot be used"))
        assertEquals(DateColumnState.NO_READABLE_DATES, result.dateColumns.single().state)
    }

    @Test
    fun `an unconfigured workbook asks for a column instead of guessing one`() {
        // The fixture's 発行日 column holds 2024-05-17, and the alias list would happily pick it. The app
        // must not: without a choice there is no publication timeline at all.
        val result = timeline(records = listOf(CatalogFixtures.catalogueRecord(1)), dates = CatalogFixtures.dates(published = null))
        assertFalse(result.available)
        assertEquals(1, result.unknownCount)
        assertTrue(result.unavailableReason!!.contains("No column is assigned"))
        assertEquals(DateColumnState.NOT_SET, result.dateColumns.single().state)
    }

    @Test
    fun `acquisition reads its own column and never the publication one`() {
        val catalog = CatalogFixtures.catalog(
            records = listOf(
                CatalogFixtures.catalogueRecord(1, published = "2021-04-02"),
                CatalogFixtures.catalogueRecord(2, published = "2024"),
            ),
        )
        val onlyPublished = CollectionTimelineBuilder.acquired(catalog, CatalogFixtures.dates())
        assertFalse("acquisition must not borrow the publication column", onlyPublished.available)
        assertEquals(2, onlyPublished.unknownCount)

        val both = CatalogFixtures.dates(published = "発行日", acquired = "発行日")
        val acquired = CollectionTimelineBuilder.acquired(catalog, both)
        assertTrue(acquired.available)
        assertEquals(TimelineMode.ACQUISITION, acquired.mode)
        assertEquals(listOf(2024, 2021), acquired.sections.map { it.year })
    }

    @Test
    fun `an acquisition date that is a serial number is read when the user chose the column`() {
        val record = CatalogFixtures.record(
            1, "Book", "Circle", "Artist", "43465", "ID", "url", "1200", "tag", "note",
        )
        val result = CollectionTimelineBuilder.acquired(
            CatalogFixtures.catalog(records = listOf(record)),
            CatalogFixtures.dates(published = null, acquired = "発行日"),
        )
        assertTrue(result.available)
        assertEquals("2018-12-31", result.sections.single().entries.single().dateLabel)
    }

    @Test
    fun `a renamed date column leaves the timeline in its setup state`() {
        val result = timeline(
            records = listOf(CatalogFixtures.catalogueRecord(1)),
            dates = CatalogFixtures.dates(published = "公開日"),
        )
        assertFalse(result.available)
        assertEquals(DateColumnState.HEADER_MISSING, result.dateColumns.single().state)
        assertTrue(result.unavailableReason!!.contains("not in this worksheet"))
    }

    @Test
    fun `an import date from the library is a real timeline`() {
        val millis = java.time.LocalDate.of(2025, 3, 9).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val result = CollectionTimelineBuilder.imported(
            CatalogFixtures.catalog(),
            millis,
            ZoneId.of("UTC"),
        )
        assertTrue(result.available)
        assertEquals(TimelineMode.IMPORT, result.mode)
        assertEquals(listOf(2025), result.sections.map { it.year })
        assertEquals(2, result.datedCount)
        assertEquals(0, result.unknownCount)
        assertEquals("2025-03-09", result.sections.single().entries.first().dateLabel)
    }

    @Test
    fun `a staged workbook has no import date and says so`() {
        val result = CollectionTimelineBuilder.imported(CatalogFixtures.catalog(), null)
        assertFalse(result.available)
        assertTrue(result.unavailableReason!!.contains("saved-workbook library"))
        assertEquals(2, result.unknownCount)
    }

    @Test
    fun `a timeline entry carries the record it came from`() {
        val catalog = CatalogFixtures.catalog(images = listOf(CatalogFixtures.linked("xl/media/image1.png", row = 1)))
        val entry = CollectionTimelineBuilder.published(catalog, CatalogFixtures.dates()).sections.single().entries
            .first { it.row == 1 }
        assertEquals("xl/worksheets/sheet1.xml#1", entry.recordId)
        assertEquals("LIST!2", entry.locationLabel)
        assertEquals("xl/media/image1.png", entry.mediaPath)
        assertEquals("Circle 1", entry.circle)
        assertEquals("Artist 1", entry.artist)
        assertEquals("ID-1", entry.identifier)
    }
}