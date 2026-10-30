package com.bookcovermatcher.core.catalog

import com.bookcovermatcher.core.excel.comments.CatalogueField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Worksheet scope: identity-based, reconciled when the workbook changes, and expressed as a short summary.
 */
class SheetScopeTest {

    private fun catalog() = WorkbookCatalogBuilder.build(
        workbookSha256 = CatalogFixtures.SHA,
        sheets = CatalogFixtures.sheets("LIST", "DATA", "C108"),
        tables = listOf(
            CatalogFixtures.table(sheetName = "LIST", records = listOf(CatalogFixtures.catalogueRecord(1))),
            CatalogFixtures.table(
                sheetName = "DATA",
                fields = listOf(CatalogueField("Title", 0), CatalogueField("Circle", 1), CatalogueField("Note", 2)),
                records = listOf(CatalogFixtures.record(1, "One", "Circle", "n")),
            ),
        ),
        images = emptyList(),
        profile = CatalogFixtures.profile(),
    )

    @Test
    fun `a sheet identity is its part path, and its name only when the path is unknown`() {
        assertEquals("xl/worksheets/sheet5.xml", SheetScope.keyOf("xl/worksheets/sheet5.xml", "LIST"))
        assertEquals("name:LIST", SheetScope.keyOf("", "LIST"))
    }

    @Test
    fun `the default scope allows every worksheet`() {
        val scope = SheetScope.ALL
        assertTrue(scope.isAll)
        assertTrue(scope.allows("xl/worksheets/sheet1.xml", "LIST"))
        assertTrue(SheetScope().allows("", "anything"))
    }

    @Test
    fun `an explicit scope allows only the sheets it names, by path or by name`() {
        val byPath = SheetScope(setOf("xl/worksheets/sheet1.xml"))
        assertTrue(byPath.allows("xl/worksheets/sheet1.xml", "LIST"))
        assertFalse(byPath.allows("xl/worksheets/sheet2.xml", "DATA"))
        // A sheet whose part path the analysis could not read is matched by its name identity.
        val byName = SheetScope(setOf("name:LIST"))
        assertTrue(byName.allows("", "LIST"))
        assertFalse(byName.allows("xl/worksheets/sheet3.xml", "C108"))
    }

    @Test
    fun `a stale selection is reconciled against the workbook, never left filtering everything out`() {
        val available = setOf("xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml")
        val partial = SheetScope(setOf("xl/worksheets/sheet1.xml", "xl/worksheets/sheet9.xml")).reconciled(available)
        assertEquals(setOf("xl/worksheets/sheet1.xml"), partial.keys)
        assertFalse(partial.isAll)

        // Nothing survived: the report goes back to "all worksheets" instead of reporting on nothing.
        val gone = SheetScope(setOf("xl/worksheets/sheet9.xml")).reconciled(available)
        assertTrue(gone.isAll)
        assertTrue(SheetScope.ALL.reconciled(emptySet()).isAll)
    }

    @Test
    fun `the picker lists the workbook's own sheets, in order, with their record counts`() {
        val choices = catalog().sheetChoices
        assertEquals(listOf("LIST", "DATA", "C108"), choices.map { it.name })
        assertEquals(listOf(1, 1, 0), choices.map { it.records })
        assertEquals(listOf(true, true, false), choices.map { it.isTable })
        // Identity is the part path the fixture gave each sheet, not the display name.
        assertEquals("xl/worksheets/sheet1.xml", choices.first().key)
        assertEquals(
            setOf("xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml", "xl/worksheets/sheet3.xml"),
            catalog().sheetKeys,
        )
    }
}
