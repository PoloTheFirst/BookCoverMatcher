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
    ): CollectionTimeline = CollectionTimelineBuilder.publication(
        CatalogFixtures.catalog(records = records),
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
        assertTrue(result.unavailableReason!!.contains("unknown bucket"))
    }

    @Test
    fun `acquisition is unavailable and says why`() {
        val result = CollectionTimelineBuilder.acquisition(CatalogFixtures.catalog())
        assertFalse(result.available)
        assertEquals(TimelineMode.ACQUISITION, result.mode)
        assertTrue(result.unavailableReason!!.contains("acquisition date"))
        assertEquals(2, result.unknownCount)
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
        val entry = CollectionTimelineBuilder.publication(catalog).sections.single().entries
            .first { it.row == 1 }
        assertEquals("xl/worksheets/sheet1.xml#1", entry.recordId)
        assertEquals("LIST!2", entry.locationLabel)
        assertEquals("xl/media/image1.png", entry.mediaPath)
        assertEquals("Circle 1", entry.circle)
        assertEquals("Artist 1", entry.artist)
        assertEquals("ID-1", entry.identifier)
    }
}