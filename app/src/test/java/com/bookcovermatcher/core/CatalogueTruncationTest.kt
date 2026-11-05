package com.bookcovermatcher.core

import com.bookcovermatcher.core.catalog.CatalogFixtures
import com.bookcovermatcher.core.catalog.HealthCategory
import com.bookcovermatcher.core.catalog.HealthReportBuilder
import com.bookcovermatcher.core.catalog.HealthSeverity
import com.bookcovermatcher.core.catalog.WorkbookCatalogBuilder
import com.bookcovermatcher.core.excel.comments.CatalogueReader
import com.bookcovermatcher.core.excel.comments.CatalogueTruncation
import com.bookcovermatcher.core.excel.comments.CellValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **XLS-01** — a blank row ends a table, and the reader must say so when data continues below it.
 *
 * The stop rule itself is deliberate and is *not* changed here: skipping blank rows would merge two unrelated
 * blocks that share one sheet. What the defect was about is the silence — the app showed 3 records for a sheet
 * holding 8 and explained nothing. These tests drive the production reader over cells (no ZIP, no emulator) and
 * then the health report, which is where the consequence is surfaced.
 */
class CatalogueTruncationTest {

    /** A three-column header, then the given rows; `null` is an all-empty row. */
    private fun sheet(vararg rows: List<String>?): List<CellValue> {
        val out = ArrayList<CellValue>()
        val header = listOf("タイトル", "サークル", "通し番号")
        header.forEachIndexed { col, text -> out.add(CellValue(0, col, text)) }
        rows.forEachIndexed { index, values ->
            val row = index + 1
            values?.forEachIndexed { col, text ->
                if (text.isNotEmpty()) out.add(CellValue(row, col, text))
            }
        }
        return out
    }

    @Test
    fun `records stop at the first blank row and the rows below it are counted`() {
        val cells = sheet(
            listOf("Book 1", "Circle 1", "1"),
            listOf("Book 2", "Circle 2", "2"),
            null,
            listOf("Book 3", "Circle 3", "3"),
            listOf("Book 4", "Circle 4", "4"),
        )
        val table = CatalogueReader.fromCells(cells, "LIST")!!
        // The rule is unchanged: only the records above the blank row are read.
        assertEquals(2, table.records.size)
        assertEquals(
            CatalogueTruncation(blankRow = 3, skippedRows = 2, firstSkippedRow = 4),
            table.truncation,
        )
    }

    @Test
    fun `a table that ends with the sheet reports nothing to explain`() {
        val table = CatalogueReader.fromCells(
            sheet(listOf("Book 1", "Circle 1", "1"), listOf("Book 2", "Circle 2", "2")),
            "LIST",
        )!!
        assertEquals(2, table.records.size)
        assertNull(table.truncation)
    }

    @Test
    fun `blank rows above the first record are skipped without a truncation`() {
        val table = CatalogueReader.fromCells(
            sheet(null, null, listOf("Book 1", "Circle 1", "1"), listOf("Book 2", "Circle 2", "2")),
            "LIST",
        )!!
        assertEquals(2, table.records.size)
        assertNull("nothing was left below the table", table.truncation)
    }

    @Test
    fun `a blank row followed only by cells outside the table is not a truncation`() {
        val cells = ArrayList(
            sheet(listOf("Book 1", "Circle 1", "1"), null),
        )
        // A private note in a far column is not a record of this table.
        cells.add(CellValue(3, 9, "scratch note"))
        val table = CatalogueReader.fromCells(cells, "LIST")!!
        assertEquals(1, table.records.size)
        assertNull(table.truncation)
    }

    @Test
    fun `the health report warns about the truncation once, naming the rows`() {
        val table = CatalogFixtures.table(
            records = listOf(CatalogFixtures.catalogueRecord(1), CatalogFixtures.catalogueRecord(2)),
            truncation = CatalogueTruncation(blankRow = 3, skippedRows = 5, firstSkippedRow = 4),
        )
        val sheets = CatalogFixtures.sheets("LIST")
        val catalog = WorkbookCatalogBuilder.build(
            workbookSha256 = CatalogFixtures.SHA,
            sheets = sheets,
            tables = listOf(table),
            images = emptyList(),
            profile = null,
        )
        val report = HealthReportBuilder.build(catalog, CatalogFixtures.analysis(tables = listOf(table), sheets = sheets), null)

        val finding = report.findings.firstOrNull { it.id == "structure:blank-row:LIST" }
        assertNotNull("the truncation is in the report", finding)
        assertEquals(HealthSeverity.WARNING, finding!!.severity)
        assertEquals(HealthCategory.STRUCTURE, finding.category)
        assertEquals("LIST", finding.sheetName)
        assertTrue("the row the table stopped at", finding.detail.contains("row 4"))
        assertTrue("how many rows were left", finding.detail.contains("5 rows"))
        assertTrue("where they start", finding.detail.contains("from row 5"))
        // It is a warning about one worksheet, not an error about the workbook.
        assertEquals(0, report.summary.errors)
        assertEquals(1, report.findings.count { it.id.startsWith("structure:blank-row:") })
    }

    @Test
    fun `a workbook without truncation carries no such warning`() {
        val table = CatalogFixtures.table(records = listOf(CatalogFixtures.catalogueRecord(1)))
        val sheets = CatalogFixtures.sheets("LIST")
        val catalog = WorkbookCatalogBuilder.build(
            workbookSha256 = CatalogFixtures.SHA,
            sheets = sheets,
            tables = listOf(table),
            images = emptyList(),
            profile = null,
        )
        val report = HealthReportBuilder.build(catalog, CatalogFixtures.analysis(tables = listOf(table), sheets = sheets), null)
        assertTrue(report.findings.none { it.id.startsWith("structure:blank-row:") })
    }
}
