package com.bookcovermatcher.core

import com.bookcovermatcher.core.excel.comments.CatalogueReader
import com.bookcovermatcher.core.excel.comments.CellLink
import com.bookcovermatcher.core.excel.comments.LinkTarget
import com.bookcovermatcher.core.excel.comments.SheetLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hyperlinks as the workbook stores them.
 *
 * The rule that matters: a link is whatever the file says, never what the text looks like. An external target
 * lives in the worksheet's relationships, an internal one in the `location` attribute, and a reference with no
 * usable destination produces no link at all.
 */
class SheetLinksTest {

    private val rels = mapOf(
        "rId1" to LinkTarget("https://example.com/item/1", external = true),
        "rId2" to LinkTarget("https://example.com/item/2", external = true),
    )

    private fun sheet(vararg links: String): String = """
        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <sheetData/>
          <hyperlinks>${links.joinToString("")}</hyperlinks>
        </worksheet>
    """.trimIndent()

    private fun keyOf(key: Long): Pair<Int, Int> = (key / 100_000L).toInt() to (key % 100_000L).toInt()

    @Test
    fun `an external link keeps its destination and its own display text`() {
        val parsed = SheetLinks.parse(
            sheet("""<hyperlink ref="E2" r:id="rId1" display="Product page"/>"""),
            rels,
        )
        val link = parsed[SheetLinks.key(1, 4)]!!
        assertEquals("https://example.com/item/1", link.target)
        assertEquals("Product page", link.label)
        assertFalse(link.internal)
        assertTrue(link.isWeb)
        assertTrue(link.isOpenable)
    }

    @Test
    fun `an internal link points inside the workbook`() {
        val parsed = SheetLinks.parse(sheet("""<hyperlink ref="F2" location="LIST!B4"/>"""), rels)
        val link = parsed[SheetLinks.key(1, 5)]!!
        assertTrue(link.internal)
        assertFalse(link.isWeb)
        assertTrue(link.isOpenable)
        assertEquals("LIST" to "B4", link.internalTarget())
        // The label comes from the link's own destination, not from something read off the cell's text.
        assertEquals("LIST!B4", link.label)
    }

    @Test
    fun `a reference covers the cells it names`() {
        val parsed = SheetLinks.parse(sheet("""<hyperlink ref="G2:G4" r:id="rId2"/>"""), rels)
        assertEquals(setOf(1 to 6, 2 to 6, 3 to 6), parsed.keys.map { keyOf(it) }.toSet())
        assertTrue(parsed.values.all { it.target == "https://example.com/item/2" })
        assertEquals(1, SheetLinks.cellsOf("B2").size)
        assertEquals(3, SheetLinks.cellsOf("B2:B4").size)
        assertEquals(4, SheetLinks.cellsOf("A1:B2").size)
    }

    @Test
    fun `a link with no usable destination is not invented`() {
        val parsed = SheetLinks.parse(
            sheet(
                """<hyperlink ref="H2" r:id="rId99"/>""",   // the relationship is missing
                """<hyperlink r:id="rId1"/>""",             // no reference at all
                """<hyperlink ref="I2"/>""",                // neither a location nor an id
            ),
            rels,
        )
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun `ordinary text is never treated as a link, and odd destinations stay safe`() {
        // A workbook that stores no hyperlinks for a URL-looking cell yields no link at all.
        assertTrue(SheetLinks.parse(sheet(), rels).isEmpty())
        // A destination the app cannot hand to a browser is kept, but not offered as a web link.
        val file = CellLink("file:///C:/docs/price.pdf")
        assertFalse(file.isWeb)
        assertFalse(file.isOpenable)
        // A defined name has no sheet part, so it cannot be followed internally.
        assertNull(CellLink("MyRange", internal = true).internalTarget())
        assertNull(CellLink("LIST!", internal = true).internalTarget())
        assertEquals("Order sheet" to "A1", CellLink("'Order sheet'!A1", internal = true).internalTarget())
    }

    @Test
    fun `a huge range is not expanded into the map`() {
        // A whole-column link must not allocate a hundred thousand entries.
        val cells = SheetLinks.cellsOf("A1:A100000")
        assertEquals(1, cells.size)
        assertEquals(0 to 0, cells.single())
    }

    @Test
    fun `links survive a real package and reach the right record and column`() {
        val workbook = """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
              <row r="1">
                <c r="A1" t="inlineStr"><is><t>Title</t></is></c>
                <c r="B1" t="inlineStr"><is><t>URL</t></is></c>
                <c r="C1" t="inlineStr"><is><t>Note</t></is></c>
              </row>
              <row r="2">
                <c r="A2" t="inlineStr"><is><t>One</t></is></c>
                <c r="B2" t="inlineStr"><is><t>example</t></is></c>
                <c r="C2" t="inlineStr"><is><t>plain</t></is></c>
              </row>
            </sheetData>
            <hyperlinks>
              <hyperlink ref="B2" r:id="rId1" display="Item page"/>
              <hyperlink ref="C2" location="LIST!A2"/>
            </hyperlinks></worksheet>
        """.trimIndent()
        val relsXml = """
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="https://example.com/item/1" TargetMode="External"/>
            </Relationships>
        """.trimIndent()
        val zip = TestWorkbooks.xlsx(
            extra = mapOf(
                "xl/worksheets/sheet1.xml" to workbook.toByteArray(),
                "xl/worksheets/_rels/sheet1.xml.rels" to relsXml.toByteArray(),
            ),
        )
        val table = CatalogueReader.read(zip, "xl/worksheets/sheet1.xml", "LIST", emptyList())
        val record = table!!.records.single()
        val title = table.fields.first { it.name == "Title" }
        val url = table.fields.first { it.name == "URL" }
        val note = table.fields.first { it.name == "Note" }

        // The link is attached to the cell the workbook names, and only to that one.
        assertNull(record.linkOf(title))
        assertEquals("https://example.com/item/1", record.linkOf(url)?.target)
        assertEquals("Item page", record.linkOf(url)?.display)
        assertEquals("LIST!A2", record.linkOf(note)?.target)

        // The displayed value is the cell's, not the link's: the destination never replaces the text.
        assertEquals("example", record.display(url))
        assertEquals("example", record.value(url))
    }
}
