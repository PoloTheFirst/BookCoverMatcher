// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core

import com.bookcovermatcher.ScopeTarget
import com.bookcovermatcher.WorkbookCommentsUi
import com.bookcovermatcher.openScopeDialog
import com.bookcovermatcher.scopeCommitted
import com.bookcovermatcher.scopeSelectAll
import com.bookcovermatcher.toggleScopeSheet
import com.bookcovermatcher.core.catalog.CatalogFixtures
import com.bookcovermatcher.core.catalog.GalleryFilter
import com.bookcovermatcher.core.catalog.GallerySort
import com.bookcovermatcher.core.catalog.SheetScope
import com.bookcovermatcher.core.catalog.WorkbookCatalogBuilder
import com.bookcovermatcher.core.catalog.sheetKeys
import com.bookcovermatcher.core.excel.comments.CatalogueField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Gallery's worksheet filter: all worksheets, one, or several.
 *
 * The selection is a set of worksheet **identities**, and it filters the records before any other filter
 * runs, so it changes what the grid holds rather than hiding cards that were already chosen. These tests
 * pin the four selection modes, the composition with every other gallery filter, the stale-selection rule
 * and the scroll key.
 */
class GallerySheetFilterTest {

    private val list = "xl/worksheets/sheet1.xml"
    private val data = "xl/worksheets/sheet2.xml"

    private fun analysis() = CatalogFixtures.analysis(
        tables = listOf(
            CatalogFixtures.table(
                sheetName = "LIST",
                records = listOf(
                    CatalogFixtures.catalogueRecord(1, title = "Alpha"),
                    CatalogFixtures.catalogueRecord(2, title = "", price = ""),
                    CatalogFixtures.catalogueRecord(3, title = "Gamma"),
                ),
            ),
            CatalogFixtures.table(
                sheetName = "DATA",
                fields = listOf(
                    CatalogueField("タイトル", 0),
                    CatalogueField("サークル", 1),
                    CatalogueField("メモ", 2),
                ),
                records = listOf(
                    CatalogFixtures.record(1, "Delta", "Circle D", ""),
                    CatalogFixtures.record(2, "Epsilon", "Circle E", "note"),
                ),
            ),
        ),
        images = listOf(
            CatalogFixtures.linked("xl/media/image1.png", sheetName = "LIST", row = 1),
            CatalogFixtures.linked("xl/media/image2.png", sheetName = "DATA", row = 1),
        ),
    )

    private fun state(sheetScope: SheetScope = SheetScope.ALL): WorkbookCommentsUi {
        val analysis = analysis()
        val catalog = WorkbookCatalogBuilder.build(
            CatalogFixtures.SHA,
            analysis.sheets,
            analysis.tables,
            analysis.images,
            CatalogFixtures.profile(),
        )
        return WorkbookCommentsUi(
            open = true,
            sha256 = CatalogFixtures.SHA,
            analysis = analysis,
            catalog = catalog,
            galleryFilter = GalleryFilter(sheets = sheetScope),
        )
    }

    private fun titles(state: WorkbookCommentsUi): List<String> =
        state.visibleCatalog.map { it.title.orEmpty() }

    private fun scope(vararg keys: String) = SheetScope(keys.toSet())

    // ---- the four selection modes ---------------------------------------------------------------

    @Test
    fun `all worksheets is the default and shows every record`() {
        val state = state()
        assertTrue(state.galleryFilter.sheets.isAll)
        assertEquals(5, state.visibleCatalog.size)
        assertEquals(state.catalog!!.entries.size, state.galleryEligible.size)
        assertEquals("All worksheets", state.scopeLabel(state.galleryFilter.sheets))
    }

    @Test
    fun `one worksheet shows only that worksheet's records`() {
        val state = state(scope(list))
        // Source order is explicit; the default sort is by title, and a missing title sorts last.
        assertEquals(listOf("Alpha", "Gamma", ""), titles(state))
        assertEquals(listOf(1, 3, 2), state.visibleCatalog.map { it.row })
        assertTrue(state.visibleCatalog.all { it.sheetName == "LIST" })
        assertEquals(3, state.galleryEligible.size)
        assertEquals("LIST", state.scopeLabel(state.galleryFilter.sheets))
    }

    @Test
    fun `several worksheets combine exactly those`() {
        val state = state(scope(list, data))
        assertEquals(5, state.visibleCatalog.size)
        assertEquals(setOf("LIST", "DATA"), state.visibleCatalog.map { it.sheetName }.toSet())
        assertEquals("2 of 2 worksheets", state.scopeLabel(state.galleryFilter.sheets))
    }

    @Test
    fun `an empty selection is all worksheets, not an empty gallery`() {
        val state = state(SheetScope(emptySet()))
        assertTrue(state.galleryFilter.sheets.isAll)
        assertEquals(5, state.visibleCatalog.size)
    }

    @Test
    fun `the picker commits a set and the gallery reads it`() {
        val committed = state()
            .openScopeDialog(ScopeTarget.GALLERY)
            .toggleScopeSheet(data)
            .scopeCommitted()
        assertEquals(setOf(data), committed.galleryFilter.sheets.keys)
        assertEquals(listOf("Delta", "Epsilon"), titles(committed))
        val all = committed.openScopeDialog(ScopeTarget.GALLERY).scopeSelectAll().scopeCommitted()
        assertTrue(all.galleryFilter.sheets.isAll)
    }

    // ---- composition with the other filters -----------------------------------------------------

    @Test
    fun `the worksheet selection composes with every existing gallery filter`() {
        val onlyList = state(scope(list))
        fun filtered(update: GalleryFilter.() -> GalleryFilter) =
            onlyList.copy(galleryFilter = onlyList.galleryFilter.let(update))

        val withCover = filtered { copy(onlyWithCover = true) }
        assertEquals(listOf("Alpha"), titles(withCover))
        assertEquals(listOf("Gamma", ""), titles(filtered { copy(onlyWithoutCover = true) }))
        assertEquals(listOf(""), titles(filtered { copy(onlyMissingTitle = true) }))
        val missingMeta = filtered { copy(onlyMissingMetadata = true) }
        assertTrue(missingMeta.visibleCatalog.all { it.sheetName == "LIST" })
        // One worksheet and No cover together: only that worksheet's coverless records.
        assertEquals(listOf("Gamma", ""), titles(filtered { copy(onlyWithoutCover = true) }))
    }

    @Test
    fun `the worksheet selection composes with the search`() {
        val searched = state(scope(list)).let {
            it.copy(galleryFilter = it.galleryFilter.copy(query = "Alpha"))
        }
        assertEquals(listOf("Alpha"), titles(searched))
        // The same query on another worksheet finds nothing instead of falling back to every sheet.
        val elsewhere = state(scope(data)).let {
            it.copy(galleryFilter = it.galleryFilter.copy(query = "Alpha"))
        }
        assertTrue(elsewhere.visibleCatalog.isEmpty())
        val found = state(scope(data)).let { it.copy(galleryFilter = it.galleryFilter.copy(query = "delta")) }
        assertEquals(listOf("Delta"), titles(found))
    }

    @Test
    fun `sorting still applies inside the selection`() {
        val state = state(scope(data))
        assertEquals(listOf("Delta", "Epsilon"), titles(state.copy(gallerySort = GallerySort.TITLE)))
        assertEquals(
            listOf("Epsilon", "Delta"),
            titles(state.copy(gallerySort = GallerySort.TITLE, galleryAscending = false)),
        )
    }

    // ---- stale selections, replacement and scroll ------------------------------------------------

    @Test
    fun `a selection the workbook no longer has is reconciled instead of hiding every card`() {
        // Before reconciliation a stale key matches nothing — which is exactly why it must be reconciled.
        val stale = state(scope("xl/worksheets/sheet99.xml"))
        assertTrue(stale.visibleCatalog.isEmpty())
        val reconciled = stale.copy(
            galleryFilter = stale.galleryFilter.copy(
                sheets = stale.galleryFilter.sheets.reconciled(stale.catalog!!.sheetKeys),
            ),
        )
        assertTrue(reconciled.galleryFilter.sheets.isAll)
        assertEquals(5, reconciled.visibleCatalog.size)

        // A mixture keeps the worksheets that still exist and drops only the missing one.
        val mixed = state(scope(list, "xl/worksheets/sheet99.xml"))
        val kept = mixed.copy(
            galleryFilter = mixed.galleryFilter.copy(
                sheets = mixed.galleryFilter.sheets.reconciled(mixed.catalog!!.sheetKeys),
            ),
        )
        assertEquals(setOf(list), kept.galleryFilter.sheets.keys)
        assertTrue(kept.visibleCatalog.all { it.sheetName == "LIST" })
    }

    @Test
    fun `another workbook starts from all worksheets`() {
        // Opening a workbook builds a fresh state, so a selection cannot follow the user into another file.
        val fresh = WorkbookCommentsUi(open = true, sha256 = "other-sha")
        assertTrue(fresh.galleryFilter.sheets.isAll)
        assertTrue(fresh.visibleCatalog.isEmpty())
        assertTrue(fresh.galleryEligible.isEmpty())
    }

    @Test
    fun `the scroll key follows the worksheet selection and nothing else`() {
        val base = state()
        assertNotEquals(base.galleryOrderKey, state(scope(list)).galleryOrderKey)
        assertNotEquals(
            "one worksheet and two worksheets are different datasets",
            state(scope(list)).galleryOrderKey,
            state(scope(list, data)).galleryOrderKey,
        )
        // Selecting them in another order is the same dataset.
        assertEquals(state(scope(list, data)).galleryOrderKey, state(scope(data, list)).galleryOrderKey)
        // A picker opening, a thumbnail or a busy report must not move the grid.
        assertEquals(base.galleryOrderKey, base.copy(scopeDialog = null).galleryOrderKey)
        assertEquals(base.galleryOrderKey, base.copy(insightsBusy = true).galleryOrderKey)
    }
}
