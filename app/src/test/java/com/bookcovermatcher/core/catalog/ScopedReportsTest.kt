package com.bookcovermatcher.core.catalog

import com.bookcovermatcher.core.excel.comments.CatalogueField
import com.bookcovermatcher.core.excel.comments.ImageState
import com.bookcovermatcher.core.excel.comments.WorkbookAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two reports calculated for a subset of the workbook's worksheets.
 *
 * The point of these tests is that the *numbers* change, not just what is visible: a scoped report and a
 * scoped analytics run must contain nothing that came from an excluded worksheet.
 */
class ScopedReportsTest {

    private fun analysis() = CatalogFixtures.analysis(
        tables = listOf(
            CatalogFixtures.table(
                sheetName = "LIST",
                records = listOf(
                    CatalogFixtures.catalogueRecord(1),
                    CatalogFixtures.catalogueRecord(2, title = ""),
                ),
            ),
            CatalogFixtures.table(
                sheetName = "DATA",
                fields = listOf(
                    CatalogueField("タイトル", 0),
                    CatalogueField("サークル", 1),
                    CatalogueField("メモ", 2),
                ),
                records = listOf(CatalogFixtures.record(1, "", "Other circle", "n")),
            ),
        ),
        images = listOf(CatalogFixtures.linked("xl/media/image1.png", sheetName = "LIST", row = 1)),
    )

    private fun catalog(analysis: WorkbookAnalysis) = WorkbookCatalogBuilder.build(
        workbookSha256 = CatalogFixtures.SHA,
        sheets = analysis.sheets,
        tables = analysis.tables,
        images = analysis.images,
        profile = CatalogFixtures.profile(),
    )

    private fun scope(vararg paths: String) = SheetScope(paths.toSet())

    @Test
    fun `analytics with every worksheet counts everything`() {
        val result = CollectionAnalyticsBuilder.build(catalog(analysis()))
        assertEquals(3, result.records)
        assertEquals(2, result.sheets)
        assertEquals(1, result.withVerifiedCover)
        assertEquals(2, result.withoutVerifiedCover)
    }

    @Test
    fun `analytics for one worksheet counts only that worksheet`() {
        val onlyList = CollectionAnalyticsBuilder.build(catalog(analysis()), scope("xl/worksheets/sheet1.xml"))
        assertEquals(2, onlyList.records)
        assertEquals(1, onlyList.sheets)
        assertEquals(1, onlyList.withVerifiedCover)
        assertEquals(listOf(MetricCount("LIST", 2)), onlyList.byWorksheet)
        assertEquals(1, onlyList.missingTitle)
        assertEquals(listOf(MetricCount("Circle 1", 1), MetricCount("Circle 2", 1)), onlyList.byCircle)

        val onlyData = CollectionAnalyticsBuilder.build(catalog(analysis()), scope("xl/worksheets/sheet2.xml"))
        assertEquals(1, onlyData.records)
        assertEquals(listOf(MetricCount("DATA", 1)), onlyData.byWorksheet)
        assertEquals(0, onlyData.withVerifiedCover)
        assertEquals(1, onlyData.missingTitle)
    }

    @Test
    fun `analytics for several worksheets combines exactly those`() {
        val both = CollectionAnalyticsBuilder.build(
            catalog(analysis()),
            scope("xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml"),
        )
        assertEquals(3, both.records)
        assertEquals(2, both.sheets)
        assertEquals(listOf("DATA", "LIST"), both.byWorksheet.map { it.label }.sorted())
        assertEquals(2, both.missingTitle)
    }

    @Test
    fun `an empty record set is reported as empty, not as a failure`() {
        val none = CollectionAnalyticsBuilder.build(catalog(analysis()), scope("xl/worksheets/sheet3.xml"))
        assertEquals(0, none.records)
        assertEquals(0, none.sheets)
        assertTrue(none.byCircle.isEmpty())
    }

    @Test
    fun `the health report for one worksheet contains nothing from the others`() {
        val source = analysis()
        val report = HealthReportBuilder.build(catalog(source), source, null, scope("xl/worksheets/sheet2.xml"))
        assertEquals(1, report.records)
        assertEquals(1, report.sheets)
        assertEquals(0, report.withCover)
        assertTrue(report.findings.all { it.sheetName == null || it.sheetName == "DATA" })
        assertEquals(1, report.summary.errors)
    }

    @Test
    fun `the health report with every worksheet is the unscoped report`() {
        val source = analysis()
        val all = HealthReportBuilder.build(catalog(source), source, null, SheetScope.ALL)
        val default = HealthReportBuilder.build(catalog(source), source, null)
        assertEquals(default.summary, all.summary)
        assertEquals(3, all.records)
        assertEquals(2, all.sheets)
        assertEquals(1, all.withCover)
        assertEquals(2, all.withoutCover)
    }

    @Test
    fun `an unreadable worksheet is only reported when it is in scope`() {
        val tables = listOf(
            CatalogFixtures.table(sheetName = "LIST", records = listOf(CatalogFixtures.catalogueRecord(1))),
        )
        val source = CatalogFixtures.analysis(
            tables = tables,
            unreadable = listOf("BROKEN"),
            sheets = CatalogFixtures.sheets("LIST", "BROKEN"),
        )
        val catalog = WorkbookCatalogBuilder.build(
            CatalogFixtures.SHA,
            source.sheets,
            source.tables,
            source.images,
            CatalogFixtures.profile(),
        )
        val all = HealthReportBuilder.build(catalog, source, null)
        assertTrue(all.findings.any { it.id == "structure:unreadable:BROKEN" })

        val withoutBroken = HealthReportBuilder.build(catalog, source, null, scope("xl/worksheets/sheet1.xml"))
        assertFalse(withoutBroken.findings.any { it.id == "structure:unreadable:BROKEN" })

        val onlyBroken = HealthReportBuilder.build(catalog, source, null, scope("xl/worksheets/sheet2.xml"))
        assertTrue(onlyBroken.findings.any { it.id == "structure:unreadable:BROKEN" })
        assertEquals(0, onlyBroken.records)
        assertEquals(0, onlyBroken.sheets)
    }

    @Test
    fun `an unresolved image is reported for the worksheet its note is on`() {
        val source = CatalogFixtures.analysis(
            tables = listOf(
                CatalogFixtures.table(sheetName = "LIST", records = listOf(CatalogFixtures.catalogueRecord(1))),
                CatalogFixtures.table(
                    sheetName = "DATA",
                    fields = listOf(
                        CatalogueField("タイトル", 0),
                        CatalogueField("サークル", 1),
                        CatalogueField("メモ", 2),
                    ),
                    records = listOf(CatalogFixtures.record(1, "One", "x", "y")),
                ),
            ),
            images = listOf(CatalogFixtures.unresolved("image9.png", sheetName = "LIST")),
        )
        assertEquals(ImageState.UNRESOLVED, source.images.single().state)
        val catalog = WorkbookCatalogBuilder.build(
            CatalogFixtures.SHA,
            source.sheets,
            source.tables,
            source.images,
            CatalogFixtures.profile(),
        )
        val listOnly = HealthReportBuilder.build(catalog, source, null, scope("xl/worksheets/sheet1.xml"))
        assertTrue(listOnly.findings.any { it.id.startsWith("image:unresolved") })

        val dataOnly = HealthReportBuilder.build(catalog, source, null, scope("xl/worksheets/sheet2.xml"))
        assertFalse(dataOnly.findings.any { it.id.startsWith("image:unresolved") })
    }
}

