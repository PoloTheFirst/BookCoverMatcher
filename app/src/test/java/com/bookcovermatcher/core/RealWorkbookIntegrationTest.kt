package com.bookcovermatcher.core

import com.bookcovermatcher.core.excel.JavaZipSource
import com.bookcovermatcher.core.excel.comments.CommentImageScanner
import com.bookcovermatcher.core.excel.comments.CommentScanResult
import com.bookcovermatcher.core.excel.comments.ImageState
import com.bookcovermatcher.core.excel.comments.WorkbookAnalysis
import com.bookcovermatcher.core.excel.comments.WorkbookAnalyzer
import com.bookcovermatcher.core.excel.comments.WorkbookComparison
import com.bookcovermatcher.core.excel.comments.WorkbookCommentImages
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the **production** entry points (`JavaZipSource` → `CommentImageScanner` → `WorkbookAnalyzer`)
 * against the real workbooks in `test_excel/`.
 *
 * The workbooks are 36–40 MB and are not committed fixtures: when `test_excel/` is absent these tests
 * are skipped instead of failing, so the suite stays reproducible anywhere. The expected counts were
 * established independently from the package parts (zip inventory: one `comments1.xml` with 999
 * `<comment>` elements, one VML drawing with 999 shapes, 124/103 image relationships, 165/139 media
 * entries) — they are not copied from the app's own output.
 */
class RealWorkbookIntegrationTest {

    private val root = File("../test_excel")
    private val newFile = File(root, "DoujinNew.xlsx")
    private val oldFile = File(root, "DoujinOld.xlsx")

    private fun <T> withWorkbook(file: File, block: (WorkbookCommentImages, WorkbookAnalysis) -> T): T {
        assumeTrue("real workbook not present: ${file.path}", file.isFile)
        ZipFile(file).use { zip ->
            JavaZipSource(zip).use { source ->
                val scan = CommentImageScanner.scan(source, file.name, "sha-" + file.name)
                assertTrue(
                    "scan failed: " + (scan as? CommentScanResult.Failure)?.message,
                    scan is CommentScanResult.Success,
                )
                val model = (scan as CommentScanResult.Success).workbook
                return block(model, WorkbookAnalyzer.analyze(source, model))
            }
        }
    }

    @Test fun doujinNewMatchesThePackageInventory() = withWorkbook(newFile) { model, analysis ->
        assertEquals(
            listOf("PIVOT - BAR ", "PIVOT - Tree", "PIVOT - Pie", "PIVOT - Area", "LIST", "DATA", "C108", "C109"),
            model.sheets,
        )
        assertEquals(999, model.comments.size)
        assertEquals(165, model.media.size)

        assertEquals("the package has 4 hidden pivot sheets", 4, analysis.sheets.count { it.hidden })
        assertEquals("A1:X1000", analysis.sheets.first { it.name == "LIST" }.dimension)
        assertEquals("A1", analysis.sheets.first { it.name == "C109" }.dimension)

        // 124 of the 165 media parts are referenced by the VML image relationships of comments1.xml.
        assertEquals(124, analysis.images.count { it.state == ImageState.LINKED })
        assertEquals(0, analysis.images.count { it.state == ImageState.UNRESOLVED })
        assertEquals(41, analysis.images.count { it.state == ImageState.UNREFERENCED })
        assertEquals(41, analysis.coverage.unreferencedMedia)
        assertEquals(124, analysis.coverage.recordsWithImage)

        // The package has one pivot sheet (PIVOT - Area, via a pivotTable relationship) and one summary
        // sheet (DATA). The pivot is excluded; LIST and DATA both carry a real header row.
        assertEquals(listOf("LIST", "DATA"), analysis.tables.map { it.sheetName })
        val table = analysis.tables.first()
        assertEquals(24, table.fields.size)
        // LIST holds 1000 rows but only 125 have content in column A (1 header + 124 records); the 999
        // comments sit on column D, including rows that carry no catalogue data at all.
        assertEquals(124, table.records.size)
        assertTrue("fields: " + table.fields.map { it.name }, table.fields.map { it.name }.contains("ブック名"))
        assertTrue("fields: " + table.fields.map { it.name }, table.fields.map { it.name }.contains("サークル名"))
        assertNotNull(table.titleField)
        assertEquals("the DATA summary sheet repeats header names (数, %)", 15, analysis.tables[1].fields.size)
    }

    @Test fun doujinOldMatchesThePackageInventory() = withWorkbook(oldFile) { model, analysis ->
        assertEquals(
            listOf("PIVOT - BAR ", "PIVOT - Tree", "PIVOT - Pie", "PIVOT - Area", "LIST", "DATA", "C108"),
            model.sheets,
        )
        assertEquals(999, model.comments.size)
        assertEquals(139, model.media.size)
        assertEquals("the VML has 103 image relationships", 103, analysis.images.count { it.state == ImageState.LINKED })
        assertEquals(36, analysis.images.count { it.state == ImageState.UNREFERENCED })
        assertEquals("A1:T1000", analysis.sheets.first { it.name == "LIST" }.dimension)
    }

    @Test fun theComparisonSeesTheRealVersionDifference() {
        val old = withWorkbook(oldFile) { model, analysis ->
            WorkbookComparison.metrics("old", oldFile.name, "old", model, analysis)
        }
        val new = withWorkbook(newFile) { model, analysis ->
            WorkbookComparison.metrics("new", newFile.name, "new", model, analysis)
        }

        val diff = WorkbookComparison.compare(old, new)

        assertEquals(listOf("C109"), diff.additions.map { it.name })
        assertTrue("no sheet was removed", diff.removals.isEmpty())
        assertFalse("order of shared sheets is unchanged", diff.orderChanged)
        val pivot = diff.changed.first { it.name == "PIVOT - BAR " }
        assertTrue(
            "PIVOT - BAR went from visible to hidden: " + pivot.changes,
            pivot.changes.any { it.field == "Visibility" && it.right == "hidden" },
        )
        val list = diff.changed.first { it.name == "LIST" }
        assertTrue("LIST widened: " + list.changes, list.changes.any { it.field == "Used range" })
        assertTrue(
            "media count grew: " + diff.totals,
            diff.totals.any { it.field == "Media files" && it.left == "139" && it.right == "165" },
        )
        assertTrue("both sides stay usable", diff.left.usable && diff.right.usable)
    }
}
