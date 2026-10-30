package com.bookcovermatcher.core.catalog

import com.bookcovermatcher.core.excel.comments.CatalogueField
import com.bookcovermatcher.data.TimelineDateStore
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The date columns the user chooses, and what the app reads from them.
 *
 * Every case is about *not* guessing: an unconfigured column has no timeline, a renamed column is reported
 * instead of being replaced, and a serial number is only a date when the user said the column holds dates.
 */
class TimelineDatesTest {

    private fun table(vararg published: String) = CatalogFixtures.table(
        records = published.mapIndexed { index, value -> CatalogFixtures.catalogueRecord(index + 1, published = value) },
    )

    // ---- date parsing --------------------------------------------------------------------------

    @Test
    fun `an excel serial number is a date only when the column is a date column`() {
        assertNull("a bare number is not a date by default", CatalogDates.parse("45292"))
        assertEquals(CatalogDate(2024, 1, 1), CatalogDates.parse("45292", allowSerial = true))
        assertEquals(CatalogDate(2018, 12, 31), CatalogDates.parse("43465", allowSerial = true))
        // Excel's optional time fraction is ignored, not rounded into the next day.
        assertEquals(CatalogDate(2024, 1, 1), CatalogDates.parse("45292.75", allowSerial = true))
        assertEquals(CatalogDate(2018, 12, 31), CatalogDates.serial("43465"))
    }

    @Test
    fun `a four digit value stays a year rather than becoming a nineteen-hundreds date`() {
        assertEquals(CatalogDate(2018), CatalogDates.parse("2018", allowSerial = true))
        assertNull("below the serial floor nothing is a serial", CatalogDates.serial("1500"))
        assertNull(CatalogDates.serial(null))
        assertNull(CatalogDates.serial("spring"))
    }

    // ---- what a chosen column holds -------------------------------------------------------------

    @Test
    fun `a column nobody chose reports that it is not chosen`() {
        val report = TimelineDates.inspect(table("2024-05-17"), TimelineMode.PUBLICATION, null)
        assertEquals(DateColumnState.NOT_SET, report.state)
        assertFalse(report.hasChoice)
        assertTrue(report.message.contains("No column is chosen"))
    }

    @Test
    fun `a usable column reports how much of it can be read`() {
        val report = TimelineDates.inspect(
            table("2024-05-17", "", "17/05/2024"),
            TimelineMode.PUBLICATION,
            "発行日",
        )
        assertEquals(DateColumnState.READY, report.state)
        assertTrue(report.isUsable)
        assertEquals(3, report.column)
        assertEquals("D \u00B7 発行日", report.columnLabel)
        assertEquals(1, report.readable)
        assertEquals(1, report.blank)
        assertEquals(1, report.malformed)
        assertEquals(2, report.samples.size)
        assertTrue(report.message.contains("1 of 2"))
        assertTrue(report.message.contains("1 cell(s) are blank"))
    }

    @Test
    fun `a column with no values at all is not silently ready`() {
        val report = TimelineDates.inspect(table("", ""), TimelineMode.ACQUISITION, "発行日")
        assertEquals(DateColumnState.NO_READABLE_DATES, report.state)
        assertFalse(report.isUsable)
        assertEquals(0, report.malformed)
        assertTrue(report.message.contains("no values"))
    }

    @Test
    fun `a column of unreadable values says so instead of pretending`() {
        val report = TimelineDates.inspect(table("春", "夏"), TimelineMode.PUBLICATION, "発行日")
        assertEquals(DateColumnState.NO_READABLE_DATES, report.state)
        assertEquals(2, report.malformed)
        assertTrue(report.message.contains("None of the 2"))
        assertEquals(listOf("not a date", "not a date"), report.samples.map { it.label })
    }

    @Test
    fun `a renamed column is reported, never replaced by a similar one`() {
        val report = TimelineDates.inspect(table("2024-05-17"), TimelineMode.PUBLICATION, "公開日")
        assertEquals(DateColumnState.HEADER_MISSING, report.state)
        assertNull(report.column)
        assertTrue(report.message.contains("is not in this worksheet"))
    }

    @Test
    fun `a header that now appears twice is ambiguous, so nothing is read`() {
        val fields = listOf(CatalogueField("日付", 0), CatalogueField("日付", 1), CatalogueField("Title", 2))
        val records = listOf(CatalogFixtures.record(1, "2024", "2025", "Book"))
        val duplicated = CatalogFixtures.table(fields = fields, records = records)
        val report = TimelineDates.inspect(duplicated, TimelineMode.PUBLICATION, "日付")
        assertEquals(DateColumnState.HEADER_AMBIGUOUS, report.state)
        assertNull(report.column)
    }

    @Test
    fun `a second workbook with different header names and order needs no special case`() {
        // No Japanese, no 出版日付/購入日, dates in columns of another order: the same code path.
        val fields = listOf(
            CatalogueField("Purchase date", 0),
            CatalogueField("Title", 1),
            CatalogueField("Publication date", 2),
        )
        val records = listOf(CatalogFixtures.record(1, "2025-01-04", "A Book", "2019-07"))
        val english = CatalogFixtures.table(sheetName = "Inventory 2026", fields = fields, records = records)

        val published = TimelineDates.inspect(english, TimelineMode.PUBLICATION, "Publication date")
        assertEquals(2, published.column)
        assertEquals(DateColumnState.READY, published.state)
        assertEquals("C \u00B7 Publication date", published.columnLabel)

        val acquired = TimelineDates.inspect(english, TimelineMode.ACQUISITION, "Purchase date")
        assertEquals(0, acquired.column)
        assertEquals("A \u00B7 Purchase date", acquired.columnLabel)
        assertEquals(CatalogDate(2025, 1, 4), acquired.samples.single().date)
    }

    @Test
    fun `a column is only found by its own name, whatever the lettering`() {
        val report = TimelineDates.inspect(table("2024"), TimelineMode.PUBLICATION, "  発行日 ")
        assertEquals(3, report.column)
        assertNull(TimelineDates.columnOf(table("2024"), "発行"))
        assertEquals(3, TimelineDates.columnOf(table("2024"), "発行日"))
    }

    // ---- the saved choices ----------------------------------------------------------------------

    @Test
    fun `the two date meanings are stored independently`() {
        val settings = TimelineDateSettings("sha")
            .withHeader("LIST", TimelineMode.PUBLICATION, "出版日付")
            .withHeader("LIST", TimelineMode.ACQUISITION, "購入日")
        val choice = settings.choiceOf("LIST")
        assertEquals("出版日付", choice.publishedHeader)
        assertEquals("購入日", choice.acquiredHeader)
        assertTrue(settings.isConfigured)

        val cleared = settings.withHeader("LIST", TimelineMode.PUBLICATION, null)
        assertEquals(SheetDateChoice(null, "購入日"), cleared.choiceOf("LIST"))
        assertNull(cleared.choiceOf("LIST").headerFor(TimelineMode.PUBLICATION))
    }

    @Test
    fun `a worksheet with both choices cleared keeps no entry at all`() {
        val settings = TimelineDateSettings("sha")
            .withHeader("LIST", TimelineMode.PUBLICATION, "出版日付")
            .withoutSheet("LIST")
        assertFalse(settings.isConfigured)
        assertTrue(settings.bySheet.isEmpty())
        assertEquals(SheetDateChoice(), settings.choiceOf("LIST"))
    }

    @Test
    fun `the import timeline has no worksheet column to choose`() {
        val settings = TimelineDateSettings("sha").withHeader("LIST", TimelineMode.IMPORT, "発行日")
        assertFalse(settings.isConfigured)
        assertNull(settings.choiceOf("LIST").headerFor(TimelineMode.IMPORT))
    }

    @Test
    fun `blank headers are never stored as a choice`() {
        val settings = TimelineDateSettings("sha").withHeader("LIST", TimelineMode.PUBLICATION, "   ")
        assertFalse(settings.isConfigured)
    }

    // ---- codec and store ------------------------------------------------------------------------

    @Test
    fun `the codec round trips one workbook per entry`() {
        val settings = listOf(
            TimelineDateSettings("sha-a").withChoice("LIST", SheetDateChoice("出版日付", "購入日")),
            TimelineDateSettings("sha-b").withChoice("Sheet 1", SheetDateChoice("Published", null)),
        )
        val decoded = TimelineDateCodec.decode(TimelineDateCodec.encode(settings))
        assertEquals(2, decoded.size)
        assertEquals("出版日付", decoded.first { it.workbookSha256 == "sha-a" }.choiceOf("LIST").publishedHeader)
        assertEquals("購入日", decoded.first { it.workbookSha256 == "sha-a" }.choiceOf("LIST").acquiredHeader)
        assertNull(decoded.first { it.workbookSha256 == "sha-b" }.choiceOf("Sheet 1").acquiredHeader)
    }

    @Test
    fun `an unconfigured workbook is not written, and a broken file decodes as nothing`() {
        val empty = TimelineDateCodec.encode(listOf(TimelineDateSettings("sha")))
        assertFalse(empty.contains("sha"))
        assertTrue(TimelineDateCodec.decode("{ not json").isEmpty())
        assertTrue(TimelineDateCodec.decode("{\"workbooks\":[{\"sheets\":{}}]}").isEmpty())
    }

    @Test
    fun `the store keeps workbooks apart and survives a corrupt file`() {
        val dir = Files.createTempDirectory("bcm-dates").toFile()
        try {
            val store = TimelineDateStore(dir)
            store.save(TimelineDateSettings("sha-a").withChoice("LIST", SheetDateChoice("出版日付", "購入日")))
            store.save(TimelineDateSettings("sha-b").withChoice("LIST", SheetDateChoice("発行日", null)))
            assertEquals(2, store.load().size)
            assertEquals("出版日付", store.load("sha-a").choiceOf("LIST").publishedHeader)
            assertEquals("発行日", store.load("sha-b").choiceOf("LIST").publishedHeader)
            assertFalse(store.load("sha-c").isConfigured)

            // Saving one workbook must not touch another one's choice.
            store.save(TimelineDateSettings("sha-a").withChoice("LIST", SheetDateChoice(null, "購入日")))
            assertNull(store.load("sha-a").choiceOf("LIST").publishedHeader)
            assertEquals("発行日", store.load("sha-b").choiceOf("LIST").publishedHeader)

            store.delete("sha-b")
            assertTrue(store.load().none { it.workbookSha256 == "sha-b" })

            val file = File(dir, "date-columns.json")
            file.writeText("{ not json")
            assertTrue(store.load().isEmpty())
            assertEquals("{ not json", file.readText())
        } finally {
            dir.deleteRecursively()
        }
    }
}
