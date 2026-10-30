package com.bookcovermatcher.core

import com.bookcovermatcher.core.catalog.DateColumnState
import com.bookcovermatcher.core.catalog.TimelineDates
import com.bookcovermatcher.core.catalog.TimelineMode
import com.bookcovermatcher.core.excel.JavaZipSource
import com.bookcovermatcher.core.excel.comments.CatalogueFiltering
import com.bookcovermatcher.core.excel.comments.CatalogueReader
import com.bookcovermatcher.core.excel.comments.CatalogueTable
import com.bookcovermatcher.core.excel.comments.CellValue
import com.bookcovermatcher.core.excel.comments.SheetCells
import com.bookcovermatcher.core.excel.comments.SheetWindows
import com.bookcovermatcher.core.excel.comments.WorkbookSheets
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The supplied sample workbook (`test_excel/doujin.xlsx`) as a **representative** fixture.
 *
 * It is 40 MB and is not committed, so these tests are skipped when it is absent. They check the shape of
 * the workbook that was reported as broken — 24 columns, the Twitter and Misskey columns at the right edge,
 * a serial-number publication date — and nothing in the app depends on those names: the same behaviour is
 * pinned with synthetic workbooks in `WorksheetColumnsTest` and `TimelineDatesTest`.
 */
class SampleWorkbookColumnsTest {

    private val file = File("../test_excel/doujin.xlsx")

    private fun <T> withListSheet(block: (CatalogueTable, List<CellValue>) -> T): T {
        assumeTrue("sample workbook not present: ${file.path}", file.isFile)
        ZipFile(file).use { zip ->
            JavaZipSource(zip).use { source ->
                val sheet = WorkbookSheets.list(source).first { it.name == "LIST" }
                val table = CatalogueReader.read(source, sheet.partPath, sheet.name)
                assertNotNull("LIST must read as a catalogue table", table)
                return block(table!!, SheetCells.read(source, sheet.partPath))
            }
        }
    }

    @Test
    fun `the list worksheet keeps all twenty four columns`() = withListSheet { table, _ ->
        assertEquals(24, table.fields.size)
        assertEquals("A", table.fields.first().letter)
        assertEquals("X", table.fields.last().letter)
        assertEquals((0 until 24).toList(), table.fields.map { it.column })
        assertTrue("every column keeps a header", table.fields.all { it.hasHeader })
        // One value per column on every record: a dropped column would shorten the row.
        assertTrue("records keep 24 values", table.records.all { it.values.size == 24 })
        assertTrue(table.records.size > 100)
    }

    @Test
    fun `the twitter and misskey columns at the right edge are reachable`() = withListSheet { table, _ ->
        val twitter = table.fields.first { it.name == "\u30C4\u30A4\u30C3\u30BF\u30FC" }
        val twitterUser = table.fields.first { it.name == "\u30C4\u30A4\u30C3\u30BF\u30FC (\u30E6\u30FC\u30B6\u30FC\u540D)" }
        val misskey = table.fields.first { it.name == "\u30DF\u30B9\u30AD\u30FC" }
        val misskeyUser = table.fields.first { it.name == "\u30DF\u30B9\u30AD\u30FC (\u30E6\u30FC\u30B6\u30FC\u540D)" }

        // S, T, U, V — the columns that used to be cut off at the sixteenth.
        assertEquals(listOf("S", "T", "U", "V"), listOf(twitter, twitterUser, misskey, misskeyUser).map { it.letter })
        assertEquals(listOf(18, 19, 20, 21), listOf(twitter, twitterUser, misskey, misskeyUser).map { it.column })

        val first = table.records.first()
        assertTrue("the twitter handle column has a value", first.value(twitterUser).isNotEmpty())
        // The tweet text and the handle are two different columns, not one value read twice.
        assertTrue(first.value(twitter) != first.value(twitterUser))
    }

    @Test
    fun `publication and purchase dates can be chosen and read`() = withListSheet { table, _ ->
        val published = TimelineDates.inspect(table, TimelineMode.PUBLICATION, "\u51FA\u7248\u65E5\u4ED8")
        assertEquals("G", CatalogueFiltering.columnLetter(published.column!!))
        assertEquals(DateColumnState.READY, published.state)
        assertTrue(published.readable > 0)

        val acquired = TimelineDates.inspect(table, TimelineMode.ACQUISITION, "\u8CFC\u5165\u65E5")
        assertEquals("R", CatalogueFiltering.columnLetter(acquired.column!!))
        assertEquals(DateColumnState.READY, acquired.state)
        // These cells are Excel serial numbers, not text: the app reads them because the user chose the
        // column, and the parsed day lands in this century.
        val sample = acquired.samples.first { it.isReadable }
        assertTrue("serial date read: ${sample.raw}", sample.date!!.year in 1990..2100)
    }

    @Test
    fun `the source window draws the whole width of the list worksheet`() = withListSheet { table, cells ->
        val window = SheetWindows.build(
            sheetName = "LIST",
            sheetPath = "xl/worksheets/sheet5.xml",
            cells = cells,
            targetRow = table.records.first().row,
            targetColumn = 0,
            headerRow = table.headerRow,
        )
        assertEquals(24, window.columns.size)
        assertEquals(0, window.hiddenColumns)
        assertEquals("X", window.columns.last().letter)
        assertEquals(24, window.rows.first().values.size)
    }

    @Test
    fun `the workbook still holds the worksheets the report listed`() {
        assumeTrue("sample workbook not present: ${file.path}", file.isFile)
        ZipFile(file).use { zip ->
            JavaZipSource(zip).use { source ->
                assertEquals(
                    listOf("PIVOT - BAR ", "PIVOT - Tree", "PIVOT - Pie", "PIVOT - Area", "LIST", "DATA", "C108", "C109"),
                    WorkbookSheets.list(source).map { it.name },
                )
            }
        }
    }
}

