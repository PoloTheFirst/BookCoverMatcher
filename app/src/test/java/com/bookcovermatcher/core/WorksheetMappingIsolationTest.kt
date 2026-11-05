package com.bookcovermatcher.core

import com.bookcovermatcher.WorkbookCommentsUi
import com.bookcovermatcher.applySheetMapping
import com.bookcovermatcher.core.catalog.CatalogEntry
import com.bookcovermatcher.core.catalog.CatalogField
import com.bookcovermatcher.core.catalog.CatalogFixtures
import com.bookcovermatcher.core.catalog.ColumnMappingProfile
import com.bookcovermatcher.core.catalog.FieldState
import com.bookcovermatcher.core.catalog.ResolvedMapping
import com.bookcovermatcher.core.catalog.WorkbookCatalog
import com.bookcovermatcher.core.catalog.WorkbookCatalogBuilder
import com.bookcovermatcher.profileSnapshot
import com.bookcovermatcher.selectProfileView
import com.bookcovermatcher.withField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Bug B:** saving a profile must record **each worksheet's own** mapping.
 *
 * A profile used to be one column set for the whole workbook, so saving it while `LIST` was open applied `LIST`'s
 * header names to every worksheet — `DATA` and `NOTES` lost their own detected mappings, and `LIST`'s blanks
 * appeared as unmapped everywhere. A profile is now a per-worksheet snapshot: the worksheet being edited takes the
 * draft, and every other worksheet inherits the mapping it already has (the source profile's, or its own detection).
 * These tests assert the **values** in the snapshot and in the catalog each worksheet resolves, not the UI.
 *
 * The fixture has three worksheets: `LIST` (Japanese headers), `DATA` (English `Title`/`Circle` plus `説明`) and
 * `NOTES` (a mixture that also has a column no alias recognises, `Reference`).
 */
class WorksheetMappingIsolationTest {

    private val listKey = "xl/worksheets/sheet1.xml"
    private val dataKey = "xl/worksheets/sheet2.xml"
    private val notesKey = "xl/worksheets/sheet3.xml"

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

    private fun notesTable() = CatalogFixtures.table(
        sheetName = "NOTES",
        fields = CatalogFixtures.fields("書名", "サークル", "備考", "Reference"),
        records = listOf(CatalogFixtures.record(1, "Book C", "Circle C", "note C", "ref C")),
    )

    /** The workbook, with the Fields screen on [sheet] and the draft seeded from that worksheet's mapping. */
    private fun state(sheet: String = "LIST"): WorkbookCommentsUi {
        val analysis = CatalogFixtures.analysis(tables = listOf(listTable(), dataTable(), notesTable()))
        val base = WorkbookCommentsUi(
            open = true,
            sha256 = CatalogFixtures.SHA,
            analysis = analysis,
            mappingSheet = sheet,
        )
        return base.copy(mappingDraft = draftFor(base, sheet))
    }

    /** What `startMapping` does when the Fields screen opens: the draft starts from that worksheet's mapping. */
    private fun draftFor(state: WorkbookCommentsUi, sheet: String): Map<CatalogField, String?> {
        val table = state.analysis!!.tables.first { it.sheetName == sheet }
        val columns = state.mappingFor(table).columns
        return CatalogField.ordered.associateWith { columns[it] }
    }

    /** The catalog each worksheet resolves under [state]'s selected configuration. */
    private fun WorkbookCommentsUi.catalog(): WorkbookCatalog = WorkbookCatalogBuilder.build(
        workbookSha256 = sha256,
        sheets = analysis!!.sheets,
        tables = analysis!!.tables,
        images = analysis!!.images,
        profile = selectedProfile,
        sheetMappings = sheetMappings,
    )

    private fun WorkbookCommentsUi.resolved(sheet: String): Map<CatalogField, String> {
        val mapping: ResolvedMapping = catalog().mappings.getValue(sheet)
        return mapping.columns.mapValues { it.value.header }
    }

    private fun WorkbookCommentsUi.entry(sheet: String): CatalogEntry =
        catalog().entries.first { it.sheetName == sheet }

    /** The draft of the inspected worksheet with [changes] applied (null clears a field). */
    private fun WorkbookCommentsUi.edited(vararg changes: Pair<CatalogField, String?>): WorkbookCommentsUi =
        copy(mappingDraft = changes.fold(mappingDraft) { draft, (field, header) -> draft.withField(field, header) })

    // ---- the baseline ----------------------------------------------------------------------------

    @Test
    fun `each worksheet starts on its own detected headers`() {
        val fresh = state()
        assertEquals("タイトル", fresh.resolved("LIST")[CatalogField.TITLE])
        assertEquals("Title", fresh.resolved("DATA")[CatalogField.TITLE])
        assertEquals("書名", fresh.resolved("NOTES")[CatalogField.TITLE])
        assertTrue(fresh.sheetMappings.isEmpty())
        assertTrue(fresh.isDetectedView)
    }

    // ---- Scenario A: one modified worksheet ------------------------------------------------------

    @Test
    fun `a saved profile keeps the modified worksheet and inherits the untouched ones`() {
        val edited = state().edited(CatalogField.TITLE to "サークル")
        val snapshot = edited.profileSnapshot("Custom A")!!
        assertEquals(setOf(listKey, dataKey, notesKey), snapshot.sheetKeys)

        // The worksheet being edited takes the draft…
        assertEquals("サークル", snapshot.sheets[listKey]?.get(CatalogField.TITLE))
        // …and every other worksheet keeps its **own** detected mapping, not LIST's columns.
        assertEquals("Title", snapshot.sheets[dataKey]?.get(CatalogField.TITLE))
        assertEquals("書名", snapshot.sheets[notesKey]?.get(CatalogField.TITLE))
        // Nothing of LIST's edit leaked: no other worksheet reads its Title from LIST's column.
        assertNotEquals("サークル", snapshot.sheets[dataKey]?.get(CatalogField.TITLE))
        assertNotEquals("サークル", snapshot.sheets[notesKey]?.get(CatalogField.TITLE))
        // The column no alias recognises is not invented into a mapping for NOTES.
        assertNull(snapshot.sheets[notesKey]?.get(CatalogField.URL))
    }

    // ---- Scenario B: several modified assignments -------------------------------------------------

    @Test
    fun `several changes stay on the edited worksheet`() {
        val edited = state().edited(
            CatalogField.TITLE to "サークル",
            CatalogField.DEFAULT_ORDER to "タイトル",
        )
        val snapshot = edited.profileSnapshot("Custom A")!!
        assertEquals("サークル", snapshot.sheets[listKey]?.get(CatalogField.TITLE))
        assertEquals("タイトル", snapshot.sheets[listKey]?.get(CatalogField.DEFAULT_ORDER))
        // DATA's entry is exactly its detected mapping, unchanged.
        assertEquals(edited.analysis!!.tables.first { it.sheetName == "DATA" }.let { table ->
            com.bookcovermatcher.core.catalog.ColumnMappings.detectedProfile(table).columns
        }, snapshot.sheets[dataKey])
    }

    // ---- Scenario C: no modifications -------------------------------------------------------------

    @Test
    fun `saving without changes still carries every worksheet's own mapping`() {
        val untouched = state()
        val snapshot = untouched.profileSnapshot("Custom A")!!
        assertEquals(setOf(listKey, dataKey, notesKey), snapshot.sheetKeys)
        // Each entry equals that worksheet's own detection — the inspected worksheet is not a template.
        assertEquals(untouched.activeColumnsFor("LIST"), snapshot.sheets[listKey])
        assertEquals(untouched.activeColumnsFor("DATA"), snapshot.sheets[dataKey])
        assertEquals(untouched.activeColumnsFor("NOTES"), snapshot.sheets[notesKey])
        assertNotEquals("タイトル", snapshot.sheets[dataKey]?.get(CatalogField.TITLE))
    }

    // ---- Scenario D: empty values stay where they are ----------------------------------------------

    @Test
    fun `clearing a field on one worksheet does not clear it on another`() {
        val edited = state().edited(CatalogField.TITLE to null)
        val snapshot = edited.profileSnapshot("Custom A")!!
        // LIST no longer maps Title…
        assertNull(snapshot.sheets[listKey]?.get(CatalogField.TITLE))
        // …while DATA and NOTES still map their own.
        assertEquals("Title", snapshot.sheets[dataKey]?.get(CatalogField.TITLE))
        assertEquals("書名", snapshot.sheets[notesKey]?.get(CatalogField.TITLE))
    }

    @Test
    fun `an empty value on the edited worksheet cannot unmap a sibling in the catalog`() {
        val profile = state().edited(
            CatalogField.TITLE to "サークル",
            CatalogField.DESCRIPTION to null,
            CatalogField.URL to "",
        ).profileSnapshot("Custom A")!!
        val applied = state().copy(profiles = listOf(profile)).selectProfileView("Custom A")

        assertEquals("サークル", applied.resolved("LIST")[CatalogField.TITLE])
        assertEquals(FieldState.UNMAPPED, applied.entry("LIST").state(CatalogField.DESCRIPTION))
        // DATA detects its own `説明` and keeps reading it; NOTES keeps its own `備考`.
        assertEquals("説明", applied.resolved("DATA")[CatalogField.DESCRIPTION])
        assertEquals("note B", applied.entry("DATA").text(CatalogField.DESCRIPTION))
        assertEquals("備考", applied.resolved("NOTES")[CatalogField.DESCRIPTION])
        assertNull(applied.resolved("DATA")[CatalogField.URL])
    }

    // ---- Scenario E: a different worksheet is being inspected --------------------------------------

    @Test
    fun `saving while inspecting another worksheet is just as correct`() {
        // DATA edited: LIST keeps its own detection, DATA takes the change, NOTES is untouched.
        val onData = state("DATA").edited(CatalogField.TITLE to "Circle")
        val dataSnapshot = onData.profileSnapshot("From DATA")!!
        assertEquals("Circle", dataSnapshot.sheets[dataKey]?.get(CatalogField.TITLE))
        assertEquals("タイトル", dataSnapshot.sheets[listKey]?.get(CatalogField.TITLE))
        assertEquals("書名", dataSnapshot.sheets[notesKey]?.get(CatalogField.TITLE))

        // NOTES edited: an unrecognised column may be mapped by hand there and nowhere else.
        val onNotes = state("NOTES").edited(CatalogField.URL to "Reference")
        val notesSnapshot = onNotes.profileSnapshot("From NOTES")!!
        assertEquals("Reference", notesSnapshot.sheets[notesKey]?.get(CatalogField.URL))
        assertNull(notesSnapshot.sheets[listKey]?.get(CatalogField.URL))
        assertNull(notesSnapshot.sheets[dataKey]?.get(CatalogField.URL))
    }

    @Test
    fun `deriving a profile from another profile keeps its customisations`() {
        // Custom A: LIST reads its Title from サークル.
        val profileA = state().edited(CatalogField.TITLE to "サークル").profileSnapshot("Custom A")!!
        val onProfileA = state().copy(profiles = listOf(profileA)).selectProfileView("Custom A")
        // Now DATA is edited while that profile is selected, and saved as Custom B.
        val profileB = onProfileA
            .copy(mappingSheet = "DATA", mappingDraft = draftFor(onProfileA, "DATA"))
            .edited(CatalogField.TITLE to "Circle")
            .profileSnapshot("Custom B")!!

        // B carries A's customisation for LIST, B's own change for DATA, and NOTES as A had it.
        assertEquals("サークル", profileB.sheets[listKey]?.get(CatalogField.TITLE))
        assertEquals("Circle", profileB.sheets[dataKey]?.get(CatalogField.TITLE))
        assertEquals(profileA.sheets[notesKey], profileB.sheets[notesKey])
        // And A itself is unchanged.
        assertEquals("サークル", profileA.sheets[listKey]?.get(CatalogField.TITLE))
        assertEquals("Title", profileA.sheets[dataKey]?.get(CatalogField.TITLE))
    }

    // ---- isolation through the catalog ------------------------------------------------------------

    @Test
    fun `a saved profile applies to every worksheet from its own entry`() {
        val profile = state().edited(CatalogField.TITLE to "サークル").profileSnapshot("Custom A")!!
        val applied = state().copy(profiles = listOf(profile)).selectProfileView("Custom A")

        assertEquals("サークル", applied.resolved("LIST")[CatalogField.TITLE])
        assertEquals("Title", applied.resolved("DATA")[CatalogField.TITLE])
        assertEquals("書名", applied.resolved("NOTES")[CatalogField.TITLE])
        // The values the records read are their own cells, not the edited worksheet's column.
        assertEquals("Book B", applied.entry("DATA").value(CatalogField.TITLE).text)
        assertNotNull(applied.entry("LIST").value(CatalogField.TITLE).text)
    }

    @Test
    fun `applying an edit to one worksheet leaves the others alone`() {
        val base = state()
        val applied = base.applySheetMapping(base.activeColumnsFor("LIST") + (CatalogField.TITLE to "サークル"))
        assertEquals("サークル", applied.resolved("LIST")[CatalogField.TITLE])
        assertEquals("Title", applied.resolved("DATA")[CatalogField.TITLE])
        assertEquals("書名", applied.resolved("NOTES")[CatalogField.TITLE])
        assertEquals(setOf(listKey), applied.sheetMappings.keys)
    }

    @Test
    fun `a profile that says nothing about a worksheet leaves it on its own detection`() {
        // A profile saved for another workbook: none of its worksheet identities exist here.
        val foreign = ColumnMappingProfile(
            "Elsewhere",
            sheets = mapOf("xl/worksheets/sheet99.xml" to mapOf(CatalogField.TITLE to "Whatever")),
        )
        val applied = state().copy(profiles = listOf(foreign)).selectProfileView("Elsewhere")
        assertEquals("タイトル", applied.resolved("LIST")[CatalogField.TITLE])
        assertEquals("Title", applied.resolved("DATA")[CatalogField.TITLE])
        assertEquals("書名", applied.resolved("NOTES")[CatalogField.TITLE])
    }
}
