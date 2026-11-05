package com.bookcovermatcher.core

import com.bookcovermatcher.WorkbookCommentsUi
import com.bookcovermatcher.applySheetMapping
import com.bookcovermatcher.core.catalog.CatalogField
import com.bookcovermatcher.core.catalog.CatalogFixtures
import com.bookcovermatcher.core.catalog.ColumnMappingProfile
import com.bookcovermatcher.core.catalog.WorkbookCatalog
import com.bookcovermatcher.core.catalog.WorkbookCatalogBuilder
import com.bookcovermatcher.persistableMapping
import com.bookcovermatcher.restoredMapping
import com.bookcovermatcher.selectProfileView
import com.bookcovermatcher.withField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Profile view switching:** selecting a profile changes which configuration is displayed and read.
 *
 * It is *not* an exchange of data. Detected is computed from the workbook and every custom profile keeps its own
 * saved record; switching in either direction must leave both untouched, and switching back must show exactly what
 * was there before. These tests assert the resolved mapping of each worksheet after every switch, and that the
 * profiles themselves are unchanged.
 */
class ProfileViewSwitchingTest {

    private val listKey = "xl/worksheets/sheet1.xml"
    private val dataKey = "xl/worksheets/sheet2.xml"

    private fun listTable() = CatalogFixtures.table(
        sheetName = "LIST",
        fields = CatalogFixtures.fields("タイトル", "サークル", "通し番号"),
        records = listOf(CatalogFixtures.record(1, "Book A", "Circle A", "10")),
    )

    private fun dataTable() = CatalogFixtures.table(
        sheetName = "DATA",
        fields = CatalogFixtures.fields("Title", "Circle", "説明"),
        records = listOf(CatalogFixtures.record(1, "Book B", "Circle B", "note B")),
    )

    /** Custom A maps LIST's Title from サークル; Custom B maps it from 通し番号. */
    private val customA = ColumnMappingProfile(
        name = "Custom A",
        sheets = mapOf(
            listKey to mapOf(CatalogField.TITLE to "サークル"),
            dataKey to mapOf(CatalogField.TITLE to "Title"),
        ),
    )
    private val customB = ColumnMappingProfile(
        name = "Custom B",
        sheets = mapOf(
            listKey to mapOf(CatalogField.TITLE to "通し番号"),
            dataKey to mapOf(CatalogField.TITLE to "Circle"),
        ),
    )

    private fun state(sheet: String = "LIST"): WorkbookCommentsUi {
        val analysis = CatalogFixtures.analysis(tables = listOf(listTable(), dataTable()))
        val base = WorkbookCommentsUi(
            open = true,
            sha256 = CatalogFixtures.SHA,
            analysis = analysis,
            profiles = listOf(customA, customB),
            mappingSheet = sheet,
        )
        return base.copy(mappingDraft = draftFor(base, sheet))
    }

    private fun draftFor(state: WorkbookCommentsUi, sheet: String): Map<CatalogField, String?> {
        val table = state.analysis!!.tables.first { it.sheetName == sheet }
        return CatalogField.ordered.associateWith { state.mappingFor(table).columns[it] }
    }

    private fun WorkbookCommentsUi.catalog(): WorkbookCatalog = WorkbookCatalogBuilder.build(
        workbookSha256 = sha256,
        sheets = analysis!!.sheets,
        tables = analysis!!.tables,
        images = analysis!!.images,
        profile = selectedProfile,
        sheetMappings = sheetMappings,
    )

    private fun WorkbookCommentsUi.resolved(sheet: String, field: CatalogField = CatalogField.TITLE): String? =
        catalog().mappings.getValue(sheet).columns[field]?.header

    // ---- §6.1–6.3: switching shows the selected configuration -------------------------------------

    @Test
    fun `switching between Detected and a custom profile shows the selected configuration`() {
        val detected = state()
        assertEquals("タイトル", detected.resolved("LIST"))
        assertTrue(detected.isDetectedView)

        val onA = detected.selectProfileView("Custom A")
        assertEquals("Custom A", onA.activeProfile)
        assertEquals("サークル", onA.resolved("LIST"))
        // The other worksheet is read from the same profile's own entry, not from LIST's columns.
        assertEquals("Title", onA.resolved("DATA"))

        val backToDetected = onA.selectProfileView(null)
        assertTrue(backToDetected.isDetectedView)
        assertEquals("タイトル", backToDetected.resolved("LIST"))
        assertEquals("Title", backToDetected.resolved("DATA"))

        val onAAgain = backToDetected.selectProfileView("Custom A")
        assertEquals("サークル", onAAgain.resolved("LIST"))
        assertEquals("Title", onAAgain.resolved("DATA"))
    }

    @Test
    fun `switching between two custom profiles shows each one's own mapping`() {
        val onA = state().selectProfileView("Custom A")
        val onB = onA.selectProfileView("Custom B")
        assertEquals("Custom B", onB.activeProfile)
        assertEquals("通し番号", onB.resolved("LIST"))
        assertEquals("Circle", onB.resolved("DATA"))

        // Back to A: its saved mapping is what is displayed, unchanged.
        val backToA = onB.selectProfileView("Custom A")
        assertEquals("サークル", backToA.resolved("LIST"))
        assertEquals("Title", backToA.resolved("DATA"))
    }

    @Test
    fun `the draft follows the selected profile`() {
        val onA = state().selectProfileView("Custom A")
        val draftA = draftFor(onA, "LIST")
        assertEquals("サークル", draftA[CatalogField.TITLE])
        val onB = onA.selectProfileView("Custom B")
        assertEquals("通し番号", draftFor(onB, "LIST")[CatalogField.TITLE])
        assertEquals("タイトル", draftFor(onB.selectProfileView(null), "LIST")[CatalogField.TITLE])
    }

    @Test
    fun `switching to a profile that does not exist falls back to Detected`() {
        val unknown = state().selectProfileView("No such profile")
        assertNull(unknown.activeProfile)
        assertTrue(unknown.isDetectedView)
        assertEquals("タイトル", unknown.resolved("LIST"))
    }

    // ---- §6.4/§7.3: switching never mutates saved profile data ------------------------------------

    @Test
    fun `selecting a profile never changes the profiles themselves`() {
        val before = state()
        val visited = before
            .selectProfileView("Custom A")
            .selectProfileView("Custom B")
            .selectProfileView(null)
            .selectProfileView("Custom A")
        // The profile objects in the state are the very same values they started as.
        assertEquals(before.profiles, visited.profiles)
        assertEquals(customA, visited.profiles.first { it.name == "Custom A" })
        assertEquals(customB, visited.profiles.first { it.name == "Custom B" })
        // And Detected was never written into a profile.
        assertFalse(visited.profiles.any { it.sheets[listKey]?.get(CatalogField.TITLE) == "タイトル" && it.name == "Custom B" })
    }

    @Test
    fun `an applied edit does not leak into the profile and is dropped when the view switches`() {
        val edited = state()
            .selectProfileView("Custom A")
            .applySheetMapping(mapOf(CatalogField.TITLE to "通し番号"))
        // The edit is in effect for this worksheet only…
        assertEquals("通し番号", edited.resolved("LIST"))
        assertEquals("Title", edited.resolved("DATA"))
        // …and the profile it was applied over is untouched.
        assertEquals(customA, edited.profiles.first { it.name == "Custom A" })

        // Switching drops the edit (it belonged to the previous view) and shows the saved configuration.
        val switched = edited.selectProfileView("Custom B")
        assertEquals("通し番号", switched.resolved("LIST")) // Custom B's own mapping
        assertTrue("no stale edit survives the switch", switched.sheetMappings.isEmpty())
        val backToA = switched.selectProfileView("Custom A")
        assertEquals("サークル", backToA.resolved("LIST"))
    }

    @Test
    fun `unsaved draft changes are not written into another profile by switching`() {
        // A draft edit that is never applied must not end up in either profile.
        val drafting = state().selectProfileView("Custom A").copy(
            mappingDraft = draftFor(state(), "LIST").withField(CatalogField.TITLE, "タイトル"),
        )
        val switched = drafting.selectProfileView("Custom B")
        assertEquals("通し番号", switched.resolved("LIST"))
        assertEquals(customA, switched.profiles.first { it.name == "Custom A" })
        assertEquals(customB, switched.profiles.first { it.name == "Custom B" })
        assertTrue(switched.sheetMappings.isEmpty())
    }
}
