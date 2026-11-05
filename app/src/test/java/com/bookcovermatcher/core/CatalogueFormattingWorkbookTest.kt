// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core

import com.bookcovermatcher.core.excel.comments.CatalogueReader
import com.bookcovermatcher.core.excel.comments.CatalogueTable
import com.bookcovermatcher.core.excel.comments.WorkbookStyles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Catalogue table reading a **real** OOXML package whose cells carry number formats.
 *
 * A `styles.xml` and the cells' `s` attributes decide what a number means; the same worksheet is read with
 * dates, a percentage, a currency format, a page count and a bare identifier, and the raw values must stay
 * exactly as Excel stored them while the display text becomes readable.
 */
class CatalogueFormattingWorkbookTest {

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

    private fun cell(ref: String, value: String, style: Int? = null, type: String? = null): String {
        val attrs = buildString {
            append(" r=\"$ref\"")
            if (style != null) append(" s=\"$style\"")
            if (type != null) append(" t=\"$type\"")
        }
        return "<c$attrs><v>$value</v></c>"
    }

    private fun inlineCell(ref: String, text: String, style: Int? = null): String =
        "<c r=\"$ref\"${if (style != null) " s=\"$style\"" else ""} t=\"inlineStr\"><is><t>$text</t></is></c>"

    private fun sheet(vararg rows: String): String = """
        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
        ${rows.joinToString("")}
        </sheetData></worksheet>
    """.trimIndent()

    private val header = "<row r=\"1\">" + listOf(
        inlineCell("A1", "Title"),
        inlineCell("B1", "Published"),
        inlineCell("C1", "Rate"),
        inlineCell("D1", "Price"),
        inlineCell("E1", "Pages"),
        inlineCell("F1", "Added"),
        inlineCell("G1", "Note"),
    ).joinToString("") + "</row>"

    private val dataRow = "<row r=\"2\">" + listOf(
        inlineCell("A2", "Book"),
        cell("B2", "45292", style = 1),
        cell("C2", "0.123456", style = 2),
        cell("D2", "1200", style = 3),
        cell("E2", "42"),
        cell("F2", "45292.6041666667", style = 4),
        inlineCell("G2", "45292", style = 1),
    ).joinToString("") + "</row>"

    private fun read(
        date1904: Boolean = false,
        rows: List<String> = listOf(header, dataRow),
    ): CatalogueTable? {
        val workbook = buildString {
            append("<workbook xmlns=\"${TestWorkbooks.MAIN_NS}\" xmlns:r=\"${TestWorkbooks.R_NS}\">")
            if (date1904) append("<workbookPr date1904=\"1\"/>")
            append("<sheets><sheet name=\"LIST\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
        }
        val zip = TestWorkbooks.xlsx(
            extra = mapOf(
                "xl/workbook.xml" to workbook.toByteArray(),
                "xl/styles.xml" to styles.toByteArray(),
                "xl/worksheets/sheet1.xml" to sheet(*rows.toTypedArray()).toByteArray(),
            ),
        )
        val resolved = WorkbookStyles.read(zip)
        assertEquals(date1904, resolved.date1904)
        return CatalogueReader.read(zip, "xl/worksheets/sheet1.xml", "LIST", emptyList(), styles = resolved)
    }

    @Test
    fun `date and percentage cells are readable while the stored values are untouched`() {
        val table = read()
        assertNotNull("the fixture must detect as a table", table)
        val record = table!!.records.single()

        // Raw values: exactly what Excel stored.
        assertEquals(
            listOf("Book", "45292", "0.123456", "1200", "42", "45292.6041666667", "45292"),
            record.values,
        )
        // Displayed values: the workbook's own formats.
        assertEquals(
            listOf("Book", "2024-01-01", "12.35%", "1200", "42", "2024-01-01 14:30", "45292"),
            record.displays(),
        )
        // The text cell with a date style is a string, so it is never converted.
        assertEquals("45292", record.display(table.fields[6]))
        assertEquals("45292", record.value(table.fields[6]))
    }

    @Test
    fun `a price, a page count and an identifier are never read as dates`() {
        val table = read()!!
        val record = table.records.single()
        val price = table.fields.first { it.name == "Price" }
        val pages = table.fields.first { it.name == "Pages" }
        assertEquals("1200", record.display(price))
        assertEquals("42", record.display(pages))
        // Their raw values are unchanged too, so a filter or a stored record still matches them.
        assertEquals("1200", record.value(price))
        assertEquals("42", record.value(pages))
    }

    @Test
    fun `the 1904 date system moves the same serial to a different day`() {
        val table = read(date1904 = true)!!
        val record = table.records.single()
        val published = table.fields.first { it.name == "Published" }
        assertEquals("45292", record.value(published))
        // The serial is read with the workbook's own epoch, not the default one.
        val expected = java.time.LocalDate.of(1904, 1, 1).plusDays(45292).toString()
        assertEquals(expected, record.display(published))
        assertTrue(record.display(published) != record.value(published))
    }

    @Test
    fun `an empty cell keeps its blank and a workbook without styles keeps every raw value`() {
        val blankRow = "<row r=\"3\">" + inlineCell("A3", "Only a title") + "</row>"
        val table = read(rows = listOf(header, dataRow, blankRow))!!
        val blank = table.records.first { it.row == 2 }
        assertEquals("", blank.value(table.fields[1]))
        assertEquals("", blank.display(table.fields[1]))

        // No styles.xml at all: the display text is the stored text, exactly as before the feature.
        val zip = TestWorkbooks.xlsx(
            extra = mapOf("xl/worksheets/sheet1.xml" to sheet(header, dataRow).toByteArray()),
        )
        val plain = CatalogueReader.read(zip, "xl/worksheets/sheet1.xml", "LIST", emptyList())
        assertNotNull(plain)
        assertEquals(plain!!.records.single().values, plain.records.single().displays())
    }
}
