package com.bookcovermatcher.core.catalog

import com.bookcovermatcher.core.excel.comments.CatalogueFiltering
import com.bookcovermatcher.core.excel.comments.CatalogueReader
import com.bookcovermatcher.core.excel.comments.CellValue
import com.bookcovermatcher.core.excel.comments.SheetWindows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every column of a worksheet survives parsing, in its own order, with its own header.
 *
 * The shapes here are deliberately *not* the sample workbook's: a 24-column sheet, a reversed English
 * sheet, a sheet with a blank header in the middle and one with an empty column. If the reader only worked
 * for one particular workbook, these would fail.
 */
class WorksheetColumnsTest {

    /** A header row plus data rows, built the way the SAX reader reports cells. */
    private fun sheet(headers: List<Pair<Int, String>>, vararg rows: Map<Int, String>): List<CellValue> {
        val cells = ArrayList<CellValue>()
        headers.forEach { (col, text) -> cells.add(CellValue(0, col, text)) }
        rows.forEachIndexed { index, row ->
            row.forEach { (col, text) -> cells.add(CellValue(index + 1, col, text)) }
        }
        return cells
    }

    /** Twenty-four headers A..X, the width of the sample `LIST` sheet. */
    private val wideHeaders: List<Pair<Int, String>> = (0 until 24).map { col ->
        col to "Header ${CatalogueFiltering.columnLetter(col)}"
    }

    @Test
    fun `a twenty four column sheet keeps every column in workbook order`() {
        val cells = sheet(wideHeaders, mapOf(0 to "a", 23 to "x"))
        val table = CatalogueReader.fromCells(cells, "WIDE")!!

        assertEquals(24, table.fields.size)
        assertEquals("A", table.fields.first().letter)
        assertEquals("X", table.fields.last().letter)
        assertEquals((0 until 24).toList(), table.fields.map { it.column })
        assertEquals((0 until 24).toList(), table.fields.map { it.index })
        assertEquals("Header X", table.fields.last().name)
        // The value in the last column is reachable, and the blank ones in between are blanks, not shifts.
        val record = table.records.single()
        assertEquals(24, record.values.size)
        assertEquals("x", record.value(table.fields.last()))
        assertEquals("", record.value(table.fields[12]))
    }

    @Test
    fun `a blank header in the middle keeps its column and its alignment`() {
        // A B (blank) D, with data under all four: without the blank column every value from C onwards
        // would be read one column to the left of its own header.
        val cells = listOf(
            CellValue(0, 0, "Title"),
            CellValue(0, 1, "Circle"),
            CellValue(0, 3, "Price"),
            CellValue(1, 0, "Book"),
            CellValue(1, 1, "Circle 1"),
            CellValue(1, 2, "nameless value"),
            CellValue(1, 3, "1200"),
        )
        val table = CatalogueReader.fromCells(cells, "GAP")!!
        val record = table.records.single()

        assertEquals(4, table.fields.size)
        assertEquals(listOf("Title", "Circle", "", "Price"), table.fields.map { it.name })
        assertFalse(table.fields[2].hasHeader)
        assertEquals("Column C", table.fields[2].label)
        assertEquals("nameless value", record.value(table.fields[2]))
        assertEquals("1200", record.value(table.fields[3]))
    }

    @Test
    fun `a column with neither header nor data is not invented`() {
        // Headers at A, C and E; nothing at all under B and D, so those columns add nothing to the table.
        val cells = listOf(
            CellValue(0, 0, "Title"),
            CellValue(0, 2, "Price"),
            CellValue(0, 4, "Notes"),
            CellValue(1, 0, "Book"),
            CellValue(1, 2, "1200"),
            CellValue(1, 4, "note"),
        )
        val table = CatalogueReader.fromCells(cells, "HOLE")!!
        assertEquals(listOf("Title", "Price", "Notes"), table.fields.map { it.name })
        assertEquals(listOf(0, 2, 4), table.fields.map { it.column })
        assertEquals(listOf("Book", "1200", "note"), table.records.single().values)
    }

    @Test
    fun `an english sheet in a different column order reads the same way`() {
        // No Japanese, no `LIST`, columns in another order: the parser must not know the sample workbook.
        val cells = listOf(
            CellValue(0, 0, "Price"),
            CellValue(0, 1, "Artist"),
            CellValue(0, 2, "Title"),
            CellValue(1, 0, "980"),
            CellValue(1, 1, "Someone"),
            CellValue(1, 2, "A Book"),
        )
        val table = CatalogueReader.fromCells(cells, "Inventory 2026")!!
        assertEquals(listOf("Price", "Artist", "Title"), table.fields.map { it.name })
        assertEquals(listOf(0, 1, 2), table.fields.map { it.column })
        assertEquals("A Book", table.records.single().value(table.fields[2]))
    }

    @Test
    fun `the filter list names a blank header column by its letter`() {
        // B has values but no header: the filter list must still offer it, named after its column.
        val cells = listOf(
            CellValue(0, 0, "Title"),
            CellValue(0, 2, "Price"),
            CellValue(0, 3, "Kind"),
            CellValue(1, 0, "One"), CellValue(1, 1, "A"), CellValue(1, 2, "100"), CellValue(1, 3, "x"),
            CellValue(2, 0, "Two"), CellValue(2, 1, "B"), CellValue(2, 2, "200"), CellValue(2, 3, "y"),
        )
        val table = CatalogueReader.fromCells(cells, "GAP")!!
        val field = CatalogueFiltering.fields(listOf(table)).first { it.label == "Column B" }
        assertEquals(1, field.column)
        assertEquals("", field.name)
        assertEquals(listOf("A", "B"), CatalogueFiltering.values(table, 1))
    }

    @Test
    fun `the source window draws every used column instead of the nearest sixteen`() {
        val cells = (0 until 24).flatMap { col ->
            listOf(CellValue(0, col, "h$col"), CellValue(5, col, "v$col"))
        }
        val window = SheetWindows.build(
            sheetName = "WIDE",
            sheetPath = "xl/worksheets/sheet5.xml",
            cells = cells,
            targetRow = 5,
            targetColumn = 3,
        )

        assertEquals(24, window.columns.size)
        assertEquals(24, window.totalColumns)
        assertEquals(0, window.hiddenColumns)
        assertEquals("X", window.columns.last().letter)
        assertEquals("v23", window.rows.first { it.row == 5 }.values.last())
        assertTrue("the verified column is always drawn", window.targetColumnShown)
    }

    @Test
    fun `a sheet past the hard bound reports the columns it did not draw`() {
        val cells = (0 until 40).flatMap { col -> listOf(CellValue(5, col, "v$col")) }
        val window = SheetWindows.build(
            sheetName = "HUGE",
            sheetPath = "xl/worksheets/sheet5.xml",
            cells = cells,
            targetRow = 5,
            targetColumn = 30,
            maxColumns = 16,
        )

        assertEquals(16, window.columns.size)
        assertEquals(24, window.hiddenColumns)
        assertEquals(40, window.totalColumns)
        assertTrue("the verified column survives the bound", window.targetColumnShown)
        assertEquals(window.columns.map { it.column }.sorted(), window.columns.map { it.column })
    }
}
