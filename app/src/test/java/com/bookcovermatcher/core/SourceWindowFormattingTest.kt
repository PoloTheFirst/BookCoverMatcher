// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core

import com.bookcovermatcher.WorkbookCommentsUi
import com.bookcovermatcher.closeCellPeek
import com.bookcovermatcher.core.excel.comments.CatalogueReader
import com.bookcovermatcher.core.excel.comments.CatalogueTable
import com.bookcovermatcher.core.excel.comments.CellValue
import com.bookcovermatcher.core.excel.comments.SheetCells
import com.bookcovermatcher.core.excel.comments.SheetLinks
import com.bookcovermatcher.core.excel.comments.SheetWindow
import com.bookcovermatcher.core.excel.comments.SheetWindows
import com.bookcovermatcher.core.excel.comments.WorkbookStyles
import com.bookcovermatcher.peekSourceCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The **Source location** worksheet preview: the same cells as the Catalogue table, read the same way.
 *
 * The fixture is a real OOXML package with its own `styles.xml` and hyperlinks, so what is exercised here is what
 * the app reads from a workbook: a date-formatted serial, a percentage fraction, a currency format, a plain
 * number, an external link, an internal link and a text cell that merely *looks* like a URL. The two views must
 * never disagree about a value, a link must stay attached to its own cell, and the preview's row numbers must be
 * the worksheet's own rows — never the position on screen.
 */
class SourceWindowFormattingTest {

    private val sheetPath = "xl/worksheets/sheet1.xml"
    private val sheetName = "LIST"

    private val styles = """
        <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <numFmts count="3">
            <numFmt numFmtId="164" formatCode="yyyy&quot;年&quot;m&quot;月&quot;d&quot;日&quot;;@"/>
            <numFmt numFmtId="165" formatCode="0.00%"/>
            <numFmt numFmtId="166" formatCode="#,##0.00 &quot;円&quot;"/>
          </numFmts>
          <cellStyleXfs count="1"><xf numFmtId="0"/></cellStyleXfs>
          <cellXfs count="5">
            <xf numFmtId="0"/>
            <xf numFmtId="164"/>
            <xf numFmtId="165"/>
            <xf numFmtId="166"/>
            <xf numFmtId="22"/>
          </cellXfs>
        </styleSheet>
    """.trimIndent()

    private fun cell(ref: String, value: String, style: Int? = null): String =
        "<c r=\"$ref\"${if (style != null) " s=\"$style\"" else ""}><v>$value</v></c>"

    private fun text(ref: String, value: String): String =
        "<c r=\"$ref\" t=\"inlineStr\"><is><t>$value</t></is></c>"

    private val header = "<row r=\"1\">" + listOf(
        text("A1", "Title"),
        text("B1", "Published"),
        text("C1", "Rate"),
        text("D1", "Price"),
        text("E1", "Pages"),
        text("F1", "Shop"),
        text("G1", "Jump"),
        text("H1", "Plain"),
    ).joinToString("") + "</row>"

    /** One data row; only row 2 carries the interesting formats and the two links. */
    private fun dataRow(row: Int): String {
        val values = if (row == 2) {
            listOf(
                text("A2", "Book two"),
                cell("B2", "45292", style = 1),
                cell("C2", "0.123456", style = 2),
                cell("D2", "1200", style = 3),
                cell("E2", "42"),
                text("F2", "Shop page"),
                text("G2", "Row 2"),
                text("H2", "https://example.com/not-linked"),
            )
        } else {
            listOf(
                text("A$row", "Book $row"),
                cell("B$row", "45292", style = 1),
                cell("C$row", "0.5", style = 2),
                cell("D$row", "1200", style = 3),
                cell("E$row", "42"),
                text("F$row", "Shop $row"),
                text("G$row", "Row $row"),
                text("H$row", "plain $row"),
            )
        }
        return "<row r=\"$row\">" + values.joinToString("") + "</row>"
    }

    private fun sheet(): String = buildString {
        append("<worksheet xmlns=\"${TestWorkbooks.MAIN_NS}\" xmlns:r=\"${TestWorkbooks.R_NS}\">")
        append("<hyperlinks>")
        // An external destination (its URL lives in the worksheet's relationships)…
        append("<hyperlink ref=\"F2\" r:id=\"rId1\"/>")
        // …and an internal one, which names a cell of this workbook.
        append("<hyperlink ref=\"G2\" location=\"LIST!B2\"/>")
        append("</hyperlinks>")
        append("<sheetData>")
        append(header)
        (2..12).forEach { append(dataRow(it)) }
        append("</sheetData></worksheet>")
    }

    private fun sheetRels(): String = buildString {
        append("<Relationships xmlns=\"${TestWorkbooks.RELS_NS}\">")
        append(
            "<Relationship Id=\"rId1\" Type=\"${TestWorkbooks.R_NS}/hyperlink\" " +
                "Target=\"https://shop.example.com/item/2\" TargetMode=\"External\"/>",
        )
        append("</Relationships>")
    }

    private fun zip() = TestWorkbooks.xlsx(
        extra = mapOf(
            "xl/styles.xml" to styles.toByteArray(),
            sheetPath to sheet().toByteArray(),
            "xl/worksheets/_rels/sheet1.xml.rels" to sheetRels().toByteArray(),
        ),
    )

    private fun read(): CatalogueTable {
        val source = zip()
        val formats = WorkbookStyles.read(source)
        val cells: List<CellValue> = SheetCells.read(source, sheetPath)
        val table = CatalogueReader.fromCells(cells, sheetName, styles = formats)
        assertNotNull("the fixture must detect as a catalogue table", table)
        return table!!
    }

    /** The window the preview draws around [targetRow], with the workbook's own formats and links. */
    private fun window(targetRow: Int, targetColumn: Int = 0): SheetWindow {
        val source = zip()
        return SheetWindows.build(
            sheetName = sheetName,
            sheetPath = sheetPath,
            cells = SheetCells.read(source, sheetPath),
            targetRow = targetRow,
            targetColumn = targetColumn,
            headerRow = 0,
            styles = WorkbookStyles.read(source),
            links = SheetLinks.read(source, sheetPath),
        )
    }

    // ---- one value, one reading ----------------------------------------------------------------

    @Test
    fun `every cell reads the same in the catalogue table and in the source window`() {
        val table = read()
        val record = table.records.first { it.row == 1 }
        val preview = window(targetRow = 1)

        for (field in table.fields) {
            assertEquals(
                "column ${field.letter} reads the same in both views",
                record.display(field),
                preview.cellAt(record.row, field.column)?.shown,
            )
        }
        // The stored values are untouched in both views: a date is still a serial, a percentage a fraction.
        assertEquals("45292", record.value(table.fields[1]))
        assertEquals("45292", preview.cellAt(record.row, 1)?.text)
        assertEquals("0.123456", preview.cellAt(record.row, 2)?.text)
    }

    @Test
    fun `a date cell shows the date, not the serial number`() {
        val preview = window(targetRow = 1)
        val published = preview.cellAt(1, 1)!!
        assertEquals("45292", published.text)
        assertEquals("2024-01-01", published.shown)
    }

    @Test
    fun `a percentage is scaled once and shown with at most two decimals`() {
        assertEquals("12.35%", window(targetRow = 1).cellAt(1, 2)?.shown)
    }

    @Test
    fun `a currency cell and a plain count keep their own text`() {
        val preview = window(targetRow = 1)
        // `#,##0.00 "円"` is a measurement, so it follows the app's two-decimal display rule…
        assertEquals("1200", preview.cellAt(1, 3)?.shown)
        // …while a `General` integer is as likely an identifier and is never reformatted.
        assertEquals("42", preview.cellAt(1, 4)?.shown)
        assertEquals("42", preview.cellAt(1, 4)?.text)
    }

    @Test
    fun `a workbook without a styles part shows every stored value unchanged`() {
        val source = zip()
        val cells = SheetCells.read(source, sheetPath)
        val plain = SheetWindows.build(sheetName, sheetPath, cells, targetRow = 1, targetColumn = 1)
        assertEquals("45292", plain.cellAt(1, 1)?.shown)
        // Nothing is invented: with no formats and no links passed in, the cell has neither.
        assertNull(plain.cellAt(1, 5)?.link)
    }

    // ---- links ---------------------------------------------------------------------------------

    @Test
    fun `the workbook's own links reach the right cells and nothing else`() {
        val preview = window(targetRow = 1)
        val external = preview.cellAt(1, 5)?.link
        assertEquals("https://shop.example.com/item/2", external?.target)
        assertTrue("an external link is openable", external!!.isOpenable)

        val internal = preview.cellAt(1, 6)?.link
        assertTrue("an in-workbook link stays internal", internal!!.internal)
        assertEquals("LIST" to "B2", internal.internalTarget())

        // A cell that only reads like a URL is not a link, and a link never replaces the cell's value.
        assertNull(preview.cellAt(1, 7)?.link)
        assertEquals("https://example.com/not-linked", preview.cellAt(1, 7)?.shown)
        assertEquals("Shop page", preview.cellAt(1, 5)?.shown)
        assertEquals("Row 2", preview.cellAt(1, 6)?.shown)

        // A link belongs to its own row: the next row has none.
        assertNull(preview.cellAt(2, 5)?.link)
    }

    @Test
    fun `an empty cell stays blank and carries no link`() {
        // A verified cell can sit on a row the worksheet has no values for; the window still draws it, blank.
        val preview = window(targetRow = 20)
        val blank = preview.rowAt(20)?.cells?.first()
        assertNotNull("the verified row is always drawn", blank)
        assertTrue(blank!!.isBlank)
        assertEquals("", blank.shown)
        assertFalse(blank.isLinked)
        assertNull("a row the window does not draw has no cell", preview.cellAt(21, 0))
    }

    // ---- identity ------------------------------------------------------------------------------

    @Test
    fun `a nearby row keeps its original worksheet row number`() {
        val preview = window(targetRow = 6)
        // Six rows of context around row index 6 (worksheet row 7).
        assertEquals(0, preview.firstRow)
        assertEquals(11, preview.lastRow)
        assertEquals("LIST", preview.sheetName)
        // The row drawn tenth is worksheet row 11, not "row 10 of the window".
        val drawn = preview.rows.first { it.row == 10 }
        assertEquals("Book 11", drawn.cells.first().shown)
        assertEquals(10, drawn.row)
        assertNotNull(preview.cellAt(10, 0))
        assertNull("a row outside the window has no cell", preview.cellAt(13, 0))
        assertNull("a column outside the window has no cell", preview.cellAt(10, 20))
    }

    @Test
    fun `a header carried outside the window is still a cell of its own row`() {
        val preview = window(targetRow = 10)
        assertFalse("the header is outside this window", preview.rows.any { it.row == 0 })
        assertEquals(
            listOf<String?>("Title", "Published", "Rate", "Price", "Pages", "Shop", "Jump", "Plain"),
            preview.headerValues,
        )
        // It can be inspected like any other cell, and it still reports the header row's own identity.
        assertEquals("Published", preview.cellAt(0, 1)?.shown)
        assertEquals("Published", preview.headerOf(1))
    }

    // ---- the dialog ----------------------------------------------------------------------------

    @Test
    fun `tapping a source cell opens the shared dialog with its displayed value and its own link`() {
        val preview = window(targetRow = 1)
        val state = WorkbookCommentsUi(open = true).peekSourceCell(preview, 1, 5)
        val peek = state.cellPeek
        assertNotNull(peek)
        assertEquals("LIST", peek!!.sheetName)
        assertEquals("F2", peek.cell)
        assertEquals("LIST!F2", peek.locationLabel)
        assertEquals("Shop", peek.fieldLabel)
        assertEquals("Shop page", peek.value)
        assertEquals("https://shop.example.com/item/2", peek.link?.target)
        assertTrue("Open Link is offered for a real destination", peek.canOpenLink)
        // Back closes it and leaves the preview untouched.
        assertNull(state.closeCellPeek().cellPeek)
    }

    @Test
    fun `a date cell copies what it shows, not its serial number`() {
        val preview = window(targetRow = 1)
        val peek = WorkbookCommentsUi(open = true).peekSourceCell(preview, 1, 1).cellPeek!!
        assertEquals("2024-01-01", peek.value)
        assertEquals("Published", peek.fieldLabel)
    }

    @Test
    fun `a plain cell offers no Open Link`() {
        val preview = window(targetRow = 1)
        val peek = WorkbookCommentsUi(open = true).peekSourceCell(preview, 1, 4).cellPeek!!
        assertNull(peek.link)
        assertFalse(peek.canOpenLink)
    }

    @Test
    fun `a cell the window does not draw opens nothing`() {
        val preview = window(targetRow = 1)
        val state = WorkbookCommentsUi(open = true)
        assertNull(state.peekSourceCell(preview, 40, 0).cellPeek)
        assertNull(state.peekSourceCell(preview, 1, 30).cellPeek)
    }

    @Test
    fun `a worksheet with no header row labels a cell by its column letter`() {
        val source = zip()
        val preview = SheetWindows.build(
            sheetName = "NOTES",
            sheetPath = sheetPath,
            cells = SheetCells.read(source, sheetPath),
            targetRow = 1,
            targetColumn = 2,
            styles = WorkbookStyles.read(source),
        )
        val peek = WorkbookCommentsUi(open = true).peekSourceCell(preview, 1, 2).cellPeek!!
        assertEquals("Cell C", peek.fieldLabel)
        assertEquals("12.35%", peek.value)
        assertNull(preview.headerOf(2))
    }
}

