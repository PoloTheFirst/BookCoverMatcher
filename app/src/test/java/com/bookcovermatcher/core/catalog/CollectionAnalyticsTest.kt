package com.bookcovermatcher.core.catalog

import com.bookcovermatcher.core.excel.comments.CatalogueField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Collection analytics: every count is defined, and a blank value is never a value. */
class CollectionAnalyticsTest {

    private fun analytics(
        records: List<com.bookcovermatcher.core.excel.comments.CatalogueRecord>,
        profile: ColumnMappingProfile = CatalogFixtures.profile(),
        images: List<com.bookcovermatcher.core.excel.comments.ImageRow> = emptyList(),
    ): CollectionAnalytics = CollectionAnalyticsBuilder.build(
        CatalogFixtures.catalog(records = records, profile = profile, images = images),
    )

    @Test
    fun `records and covers are counted from the shared catalog`() {
        val result = analytics(
            records = listOf(CatalogFixtures.catalogueRecord(1), CatalogFixtures.catalogueRecord(2)),
            images = listOf(CatalogFixtures.linked("xl/media/image1.png", row = 1)),
        )
        assertEquals(2, result.records)
        assertEquals(1, result.sheets)
        assertEquals(1, result.withVerifiedCover)
        assertEquals(1, result.withoutVerifiedCover)
    }

    @Test
    fun `circle and artist are grouped separately`() {
        val result = analytics(
            records = listOf(
                CatalogFixtures.catalogueRecord(1, circle = "C1", artist = "A1"),
                CatalogFixtures.catalogueRecord(2, circle = "C1", artist = "A2"),
                CatalogFixtures.catalogueRecord(3, circle = "C2", artist = "A1"),
            ),
        )
        assertEquals(listOf(MetricCount("C1", 2), MetricCount("C2", 1)), result.byCircle)
        assertEquals(listOf(MetricCount("A1", 2), MetricCount("A2", 1)), result.byArtist)
    }

    @Test
    fun `a blank optional value is counted as missing, never as a value`() {
        val result = analytics(
            records = listOf(
                CatalogFixtures.catalogueRecord(1, circle = ""),
                CatalogFixtures.catalogueRecord(2, circle = "C2"),
            ),
        )
        assertEquals(1, result.missingCircle)
        assertEquals(listOf(MetricCount("C2", 1)), result.byCircle)
    }

    @Test
    fun `tags are split into separate buckets`() {
        val result = analytics(
            records = listOf(
                CatalogFixtures.catalogueRecord(1, tags = "short, long"),
                CatalogFixtures.catalogueRecord(2, tags = "short、extra"),
            ),
        )
        assertEquals(
            listOf(MetricCount("short", 2), MetricCount("extra", 1), MetricCount("long", 1)),
            result.byTags,
        )
    }

    @Test
    fun `publication years are grouped and unreadable dates stay unknown`() {
        val result = analytics(
            records = listOf(
                CatalogFixtures.catalogueRecord(1, published = "2022-04-01"),
                CatalogFixtures.catalogueRecord(2, published = "2022年"),
                CatalogFixtures.catalogueRecord(3, published = "17/05/2024"),
                CatalogFixtures.catalogueRecord(4, published = ""),
            ),
        )
        assertEquals(listOf(MetricCount("2022", 2)), result.byPublicationYear)
        assertEquals(2, result.unknownPublicationYear)
        assertEquals(0, result.byPublicationYear.count { it.label == "2040" })
    }

    @Test
    fun `duplicates are reported and not subtracted`() {
        val result = analytics(
            records = listOf(
                CatalogFixtures.catalogueRecord(1, identifier = "X"),
                CatalogFixtures.catalogueRecord(2, identifier = "X"),
                CatalogFixtures.catalogueRecord(3, identifier = "Y"),
            ),
        )
        assertEquals(3, result.records)
        assertEquals(1, result.duplicateIdentifierGroups)
        assertEquals(2, result.duplicateIdentifierRecords)
    }

    @Test
    fun `mapped and unmapped fields are listed for the dashboard`() {
        val result = analytics(
            records = listOf(CatalogFixtures.catalogueRecord(1)),
            profile = ColumnMappingProfile("minimal", mapOf(CatalogField.TITLE to "タイトル")),
        )
        assertEquals(listOf(CatalogField.TITLE), result.mappedFields)
        assertTrue(CatalogField.ARTIST in result.unmappedFields)
    }

    @Test
    fun `an empty workbook gives zeroed metrics`() {
        val result = CollectionAnalyticsBuilder.build(
            WorkbookCatalogBuilder.build(
                CatalogFixtures.SHA,
                CatalogFixtures.sheets(),
                emptyList(),
                emptyList(),
                CatalogFixtures.profile(),
            ),
        )
        assertEquals(0, result.records)
        assertEquals(0, result.byCircle.size)
        assertEquals(0, result.unknownPublicationYear)
        assertTrue(result.byPublicationYear.isEmpty())
    }

    @Test
    fun `a record per worksheet is counted in its own bucket`() {
        val a = CatalogFixtures.table("LIST", records = listOf(CatalogFixtures.catalogueRecord(1)))
        val b = CatalogFixtures.table(
            "EXTRA",
            records = listOf(CatalogFixtures.catalogueRecord(1), CatalogFixtures.catalogueRecord(2)),
        )
        val result = CollectionAnalyticsBuilder.build(
            WorkbookCatalogBuilder.build(
                CatalogFixtures.SHA,
                CatalogFixtures.sheets("LIST", "EXTRA"),
                listOf(a, b),
                emptyList(),
                CatalogFixtures.profile(),
            ),
        )
        assertEquals(3, result.records)
        assertEquals(2, result.sheets)
        assertEquals(listOf(MetricCount("EXTRA", 2), MetricCount("LIST", 1)), result.byWorksheet)
    }
}