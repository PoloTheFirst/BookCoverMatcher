package com.bookcovermatcher.core.catalog

import com.bookcovermatcher.core.excel.comments.CatalogueField
import com.bookcovermatcher.core.excel.comments.CatalogueRecord
import com.bookcovermatcher.core.excel.comments.CommentDiagnostic
import com.bookcovermatcher.core.excel.comments.DiagnosticLevel
import com.bookcovermatcher.core.excel.comments.ImageRow
import com.bookcovermatcher.core.excel.comments.WorkbookCommentImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Spreadsheet Health Report: confirmed problems, possible issues, and information that is not a defect. */
class HealthReportTest {

    private fun report(
        records: List<CatalogueRecord> = listOf(CatalogFixtures.catalogueRecord(1)),
        images: List<ImageRow> = emptyList(),
        profile: ColumnMappingProfile = CatalogFixtures.profile(),
        unreadable: List<String> = emptyList(),
        model: WorkbookCommentImages? = null,
        tables: List<com.bookcovermatcher.core.excel.comments.CatalogueTable>? = null,
    ): HealthReport {
        val actual = tables ?: listOf(CatalogFixtures.table(records = records))
        val catalog = WorkbookCatalogBuilder.build(
            CatalogFixtures.SHA,
            CatalogFixtures.sheets(),
            actual,
            images,
            profile,
        )
        return HealthReportBuilder.build(catalog, CatalogFixtures.analysis(actual, images, unreadable), model)
    }

    private fun model(truncated: Boolean = false, diagnostics: List<CommentDiagnostic> = emptyList()) =
        WorkbookCommentImages(
            workbookName = "book.xlsx",
            sha256 = CatalogFixtures.SHA,
            sheets = listOf("LIST"),
            comments = emptyList(),
            media = emptyList(),
            diagnostics = diagnostics,
            threadedCommentParts = emptyList(),
            truncated = truncated,
        )

    @Test
    fun `a blank title is a confirmed error on that record`() {
        val report = report(records = listOf(CatalogFixtures.catalogueRecord(1, title = "")))
        val finding = report.findings.first { it.field == CatalogField.TITLE }
        assertEquals(HealthSeverity.ERROR, finding.severity)
        assertEquals(HealthCategory.RECORDS, finding.category)
        assertEquals("xl/worksheets/sheet1.xml#1", finding.recordId)
        assertEquals("LIST!2", finding.locationLabel)
        assertEquals(1, report.summary.errors)
    }

    @Test
    fun `an unmapped title is reported once per record and is an error`() {
        val report = report(
            records = listOf(CatalogFixtures.catalogueRecord(1), CatalogFixtures.catalogueRecord(2)),
            profile = ColumnMappingProfile("empty", emptyMap()),
        )
        val titleFindings = report.findings.filter {
            it.category == HealthCategory.RECORDS && it.field == CatalogField.TITLE && it.severity == HealthSeverity.ERROR
        }
        assertEquals(2, titleFindings.size)
        assertTrue(titleFindings.all { it.title == "Title is not mapped" })
        assertEquals(2, titleFindings.mapNotNull { it.recordId }.distinct().size)
        assertEquals(report.findings.size, report.findings.map { it.id }.distinct().size)
    }

    @Test
    fun `a blank optional field is information, not an error`() {
        val report = report(records = listOf(CatalogFixtures.catalogueRecord(1, circle = "", artist = "")))
        val circle = report.findings.first { it.field == CatalogField.CIRCLE && it.severity == HealthSeverity.INFO }
        assertEquals("Circle is empty", circle.title)
        assertEquals(HealthResolution.INFORMATIONAL, circle.resolution)
        assertEquals(0, report.summary.errors)
    }

    @Test
    fun `a present optional field produces no missing finding`() {
        val report = report()
        assertTrue(report.findings.none { it.field == CatalogField.CIRCLE && it.title.endsWith("is empty") })
        assertTrue(report.findings.none { it.field == CatalogField.URL })
    }

    @Test
    fun `an unmapped optional field is not reported once per record`() {
        val report = report(
            records = listOf(CatalogFixtures.catalogueRecord(1), CatalogFixtures.catalogueRecord(2)),
            profile = ColumnMappingProfile("minimal", mapOf(CatalogField.TITLE to "タイトル")),
        )
        assertTrue(report.findings.none { it.field == CatalogField.ARTIST })
        assertTrue(CatalogField.ARTIST in report.unmappedFields)
        assertFalse(CatalogField.TITLE in report.unmappedFields)
    }

    @Test
    fun `a duplicate product id is a warning that names both rows`() {
        val report = report(
            records = listOf(
                CatalogFixtures.catalogueRecord(1, identifier = "ABC-1"),
                CatalogFixtures.catalogueRecord(2, identifier = "abc-1"),
            ),
        )
        val finding = report.findings.first { it.title == "Duplicate product ID" }
        assertEquals(HealthSeverity.WARNING, finding.severity)
        assertEquals(HealthCategory.CONSISTENCY, finding.category)
        assertEquals(2, finding.count)
        assertTrue(finding.detail.contains("LIST!2"))
        assertTrue(finding.detail.contains("LIST!3"))
    }

    @Test
    fun `two records with the same title circle and artist are only a possible duplicate`() {
        val report = report(
            records = listOf(
                CatalogFixtures.catalogueRecord(1, identifier = "A"),
                CatalogFixtures.catalogueRecord(
                    2,
                    title = "Book 1",
                    circle = "Circle 1",
                    artist = "Artist 1",
                    identifier = "B",
                ),
            ),
        )
        val finding = report.findings.first { it.title == "Possible duplicate record" }
        assertEquals(HealthSeverity.INFO, finding.severity)
        assertEquals(2, finding.count)
    }

    @Test
    fun `an unresolved image reference is a warning that names the image`() {
        val report = report(images = listOf(CatalogFixtures.unresolved("media/x.png")))
        val finding = report.findings.first { it.category == HealthCategory.IMAGES && it.severity == HealthSeverity.WARNING }
        assertEquals("media/x.png", finding.imagePath)
        assertTrue(finding.detail.contains("LIST!B5"))
    }

    @Test
    fun `an unreferenced image is information`() {
        val report = report(images = listOf(CatalogFixtures.unreferenced("xl/media/image9.png")))
        val finding = report.findings.first { it.title == "Image is not associated with a record" }
        assertEquals(HealthSeverity.INFO, finding.severity)
        assertEquals("xl/media/image9.png", finding.imagePath)
    }

    @Test
    fun `records without a cover are summarised per worksheet`() {
        val report = report(
            records = listOf(CatalogFixtures.catalogueRecord(1), CatalogFixtures.catalogueRecord(2)),
            images = listOf(CatalogFixtures.linked("xl/media/image1.png", row = 1)),
        )
        val finding = report.findings.first { it.title.contains("have no verified cover") }
        assertEquals(1, finding.count)
        assertEquals("LIST", finding.sheetName)
        assertEquals(1, report.withCover)
        assertEquals(1, report.withoutCover)
    }

    @Test
    fun `an unreadable worksheet is a confirmed error`() {
        val report = report(unreadable = listOf("BROKEN"))
        val finding = report.findings.first { it.sheetName == "BROKEN" }
        assertEquals(HealthSeverity.ERROR, finding.severity)
        assertTrue(finding.detail.contains("could not be parsed"))
    }

    @Test
    fun `a truncated scan is reported as a lower bound`() {
        val report = report(model = model(truncated = true))
        val finding = report.findings.first { it.title.contains("safety limit") }
        assertEquals(HealthSeverity.WARNING, finding.severity)
        assertEquals(HealthResolution.INFORMATIONAL, finding.resolution)
    }

    @Test
    fun `a warning diagnostic becomes a structure warning`() {
        val report = report(
            model = model(
                diagnostics = listOf(CommentDiagnostic(DiagnosticLevel.WARNING, "xl/comments1.xml", "bad note")),
            ),
        )
        val finding = report.findings.first { it.title.contains("xl/comments1.xml") }
        assertEquals(HealthSeverity.WARNING, finding.severity)
        assertTrue(finding.detail.contains("bad note"))
    }

    @Test
    fun `a workbook with no table says so without calling it an error`() {
        val report = report(tables = emptyList())
        val finding = report.findings.first { it.title == "No catalogue table was detected" }
        assertEquals(HealthSeverity.INFO, finding.severity)
        assertEquals(0, report.records)
    }

    @Test
    fun `an ambiguous mapping is a consistency warning`() {
        val table = CatalogFixtures.table(
            fields = CatalogFixtures.fields("タイトル", "タイトル", "サークル"),
            records = listOf(CatalogFixtures.record(1, "A", "B", "C")),
        )
        val report = report(tables = listOf(table))
        val finding = report.findings.first { it.title == "Mapped column is ambiguous" }
        assertEquals(HealthSeverity.WARNING, finding.severity)
        assertEquals(CatalogField.TITLE, finding.field)
        assertEquals("LIST", finding.sheetName)
    }

    @Test
    fun `findings are ordered errors then warnings then information`() {
        val report = report(
            records = listOf(CatalogFixtures.catalogueRecord(1, title = "", identifier = "dup")),
            images = listOf(CatalogFixtures.unreferenced("xl/media/image9.png")),
        )
        val severities = report.findings.map { it.severity.ordinal }
        assertEquals(severities.sorted(), severities)
        assertEquals(report.findings.size, report.summary.total)
    }

    @Test
    fun `no finding is counted twice`() {
        val report = report(
            records = listOf(CatalogFixtures.catalogueRecord(1), CatalogFixtures.catalogueRecord(2)),
            images = listOf(CatalogFixtures.unreferenced("xl/media/image9.png")),
        )
        assertEquals(report.findings.size, report.findings.map { it.id }.distinct().size)
    }

    @Test
    fun `a record finding without an affected field still carries the record id`() {
        val report = report(records = listOf(CatalogFixtures.catalogueRecord(1, url = "not a url")))
        val finding = report.findings.first { it.title == "URL looks invalid" }
        assertEquals("xl/worksheets/sheet1.xml#1", finding.recordId)
        assertNull(finding.imagePath)
    }
}