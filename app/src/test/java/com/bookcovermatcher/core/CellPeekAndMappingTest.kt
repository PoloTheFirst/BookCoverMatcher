// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core

import com.bookcovermatcher.WorkbookCommentsUi
import com.bookcovermatcher.applySheetMapping
import com.bookcovermatcher.closeCellPeek
import com.bookcovermatcher.selectProfileView
import com.bookcovermatcher.core.catalog.CatalogField
import com.bookcovermatcher.core.catalog.CatalogFixtures
import com.bookcovermatcher.core.catalog.ColumnMappingProfile
import com.bookcovermatcher.core.excel.comments.CatalogueRecord
import com.bookcovermatcher.core.excel.comments.CellLink
import com.bookcovermatcher.core.excel.comments.WorkbookCommentImages
import com.bookcovermatcher.peekCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Catalogue table's cell dialog and the precedence of a manual mapping.
 *
 * The dialog must describe **one source cell**: its displayed value and its own hyperlink. The mapping must
 * keep the user's manual choice in front of automatic detection until they explicitly reset it.
 */
class CellPeekAndMappingTest {

    private val table = CatalogFixtures.table()

    private fun record(links: Map<Int, CellLink> = emptyMap()): CatalogueRecord = CatalogFixtures
        .catalogueRecord(7, title = "Alpha", url = "https://example.com/a")
        .copy(displayValues = CatalogFixtures.catalogueRecord(7, title = "Alpha").values, links = links)

    private fun state() = WorkbookCommentsUi(open = true, sha256 = CatalogFixtures.SHA, catalog = CatalogFixtures.catalog())

    @Test
    fun `the dialog carries the cell's own value, column and location`() {
        val record = record()
        val peeked = state().peekCell("LIST", record, table.fields[1]) // サークル
        val peek = peeked.cellPeek!!
        assertEquals("LIST", peek.sheetName)
        assertEquals(7, peek.row)
        assertEquals(1, peek.column)
        assertEquals("B", peek.columnLetter)
        assertEquals("サークル", peek.fieldLabel)
        assertEquals("Circle 7", peek.value)
        assertEquals("LIST!B8", peek.locationLabel)
        assertFalse(peek.isBlank)
        assertFalse("no link was stored, so the action is not offered", peek.canOpenLink)
        assertNull(peek.link)
    }

    @Test
    fun `only a usable link offers Open Link`() {
        val web = record(mapOf(5 to CellLink("https://example.com/a")))
        assertTrue(state().peekCell("LIST", web, table.fields[5]).cellPeek!!.canOpenLink)
        // An internal reference can be followed by the app's own navigation.
        val inside = record(mapOf(5 to CellLink("LIST!A2", internal = true)))
        assertTrue(state().peekCell("LIST", inside, table.fields[5]).cellPeek!!.canOpenLink)
        // A destination the app cannot open keeps the value and the link, but not the action.
        val unsupported = record(mapOf(5 to CellLink("file:///C:/a.pdf")))
        val peek = state().peekCell("LIST", unsupported, table.fields[5]).cellPeek!!
        assertEquals("file:///C:/a.pdf", peek.link?.target)
        assertFalse(peek.canOpenLink)
    }

    @Test
    fun `an empty cell opens a dialog that says so, and a value with line breaks keeps them`() {
        val blank = CatalogFixtures.catalogueRecord(7, price = "").copy(
            displayValues = CatalogFixtures.catalogueRecord(7, price = "").values,
        )
        val peek = state().peekCell("LIST", blank, table.fields[6]).cellPeek!! // 価格 is blank here
        assertTrue(peek.isBlank)
        assertEquals("", peek.value)
        val multi = CatalogFixtures.catalogueRecord(7).let { base ->
            CatalogueRecord(
                base.row,
                base.values.toMutableList().also { it[8] = "first line\nsecond line" },
            )
        }
        assertEquals("first line\nsecond line", state().peekCell("LIST", multi, table.fields[8]).cellPeek!!.value)
    }

    @Test
    fun `closing the dialog is idempotent`() {
        val open = state().peekCell("LIST", record(), table.fields[0])
        assertEquals(null, open.closeCellPeek().cellPeek)
        assertEquals(null, state().closeCellPeek().cellPeek)
    }

    // ---- manual mapping beats detection ---------------------------------------------------------

    /** A state on one worksheet, like the Fields screen shows: the analysis, the sheet, no applied mapping. */
    private fun mappingState(sheet: String = "LIST"): WorkbookCommentsUi {
        val table = CatalogFixtures.table(
            sheetName = sheet,
            records = listOf(CatalogFixtures.catalogueRecord(1)),
        )
        val analysis = CatalogFixtures.analysis(tables = listOf(table))
        return WorkbookCommentsUi(
            open = true,
            sha256 = CatalogFixtures.SHA,
            analysis = analysis,
            mappingSheet = sheet,
        )
    }

    @Test
    fun `a manual mapping is the mapping in effect until a profile or Detected is chosen`() {
        val detected = mappingState()
        assertTrue(detected.isDetectedMapping)
        assertNull(detected.appliedProfile())
        assertEquals("Detected from this workbook's headers", detected.mappingSourceLabel)

        // A manual mapping that really differs from the detected headers is what the reports read.
        val manualColumns = detected.detectedMappingColumns + (CatalogField.TITLE to "ブック名")
        val manual = detected.applySheetMapping(manualColumns)
        assertFalse(manual.isDetectedMapping)
        assertEquals(manualColumns, manual.appliedProfile()?.columns)
        assertEquals("Manual mapping, not saved as a profile", manual.mappingSourceLabel)

        // Selecting a profile switches the view: its saved configuration is what is in effect.
        val profile = ColumnMappingProfile("Mine", mapOf(CatalogField.TITLE to "書名"))
        val saved = manual.copy(profiles = listOf(profile)).selectProfileView(profile.name)
        assertEquals("Mine", saved.activeProfile)
        assertEquals(profile.columns, saved.appliedProfile()?.columns)
        assertEquals("Saved profile \u201CMine\u201D", saved.mappingSourceLabel)

        // Back to Detected: the view is detection again, and the applied edit was dropped with the switch.
        val detectedAgain = saved.selectProfileView(null)
        assertTrue(detectedAgain.isDetectedMapping)
        assertNull(detectedAgain.appliedProfile())
        assertTrue(detectedAgain.sheetMappings.isEmpty())
    }

    @Test
    fun `a manual mapping survives the states a rebuild passes through`() {
        val applied = mappingState().let { state ->
            state.applySheetMapping(state.detectedMappingColumns + (CatalogField.TITLE to "ブック名"))
        }
        // Derived results come and go; the choice must not be dropped on the way.
        val reloading = applied.reloadingForRescan()
        assertEquals(applied.sheetMappings, reloading.sheetMappings)
        assertFalse(reloading.isDetectedMapping)
        val model = WorkbookCommentImages(
            workbookName = "book.xlsx",
            sha256 = CatalogFixtures.SHA,
            sheets = emptyList(),
            comments = emptyList(),
            media = emptyList(),
            diagnostics = emptyList(),
            threadedCommentParts = emptyList(),
        )
        assertEquals(applied.sheetMappings, reloading.analysisLoaded(model, null).sheetMappings)
        assertEquals(applied.sheetMappings, reloading.reloadFailed("nope").sheetMappings)
    }
}
