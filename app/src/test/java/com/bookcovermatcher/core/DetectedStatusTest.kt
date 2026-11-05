package com.bookcovermatcher.core

import com.bookcovermatcher.WorkbookCommentsUi
import com.bookcovermatcher.applySheetMapping
import com.bookcovermatcher.clearSheetMapping
import com.bookcovermatcher.core.catalog.CatalogField
import com.bookcovermatcher.core.catalog.CatalogFixtures
import com.bookcovermatcher.core.catalog.ColumnMappingProfile
import com.bookcovermatcher.core.catalog.MappingEquality
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
 * **Bug A:** the *Detected* status must describe the mapping, not the click.
 *
 * The indicator used to be a pair of state flags (`activeProfile == null && appliedMapping == null`), so
 * **Apply mapping** on an unchanged mapping turned it grey: applying wrote a "manual mapping" even though the
 * assignments were exactly the ones detection had produced. The status is now a semantic comparison between the
 * mapping in effect and the mapping detection would choose for that worksheet, and a mapping that means detection is
 * not stored as an applied one at all.
 *
 * The matrix below is the one the prompt specifies, row by row.
 */
class DetectedStatusTest {

    private val listKey = "xl/worksheets/sheet1.xml"
    private val dataKey = "xl/worksheets/sheet2.xml"

    /** Two worksheets with completely different header rows, so a per-sheet decision is observable. */
    private fun state(sheet: String? = "LIST"): WorkbookCommentsUi {
        val list = CatalogFixtures.table(
            sheetName = "LIST",
            fields = CatalogFixtures.fields("タイトル", "サークル", "通し番号"),
            records = listOf(CatalogFixtures.record(1, "Book A", "Circle A", "10")),
        )
        val data = CatalogFixtures.table(
            sheetName = "DATA",
            fields = CatalogFixtures.fields("Title", "Circle", "Note"),
            records = listOf(CatalogFixtures.record(1, "Book B", "Circle B", "note")),
        )
        val analysis = CatalogFixtures.analysis(tables = listOf(list, data))
        return seedDraft(
            WorkbookCommentsUi(
                open = true,
                sha256 = CatalogFixtures.SHA,
                analysis = analysis,
                mappingSheet = sheet,
            ),
        )
    }

    /** What `startMapping` does when the Fields screen opens: the draft starts from that worksheet's mapping. */
    private fun seedDraft(state: WorkbookCommentsUi): WorkbookCommentsUi {
        val table = state.mappingTable ?: return state
        val columns = state.mappingFor(table).columns
        return state.copy(mappingDraft = CatalogField.ordered.associateWith { columns[it] })
    }

    /** The draft's own assignments, as `applyMapping` reads them (blanks dropped). */
    private fun WorkbookCommentsUi.draftColumns(): Map<CatalogField, String> = mappingDraft
        .mapNotNull { (field, header) -> header?.takeIf { it.isNotBlank() }?.let { field to it } }
        .toMap()

    private fun WorkbookCommentsUi.applyDraft(): WorkbookCommentsUi = applySheetMapping(draftColumns())

    // ---- blue -----------------------------------------------------------------------------------

    @Test
    fun `an automatically detected mapping that is applied without changes stays detected`() {
        val detected = state()
        assertTrue(detected.isDetectedMapping)

        val applied = detected.applyDraft()
        assertTrue("applying what detection chose is still detection", applied.isDetectedMapping)
        assertNull(applied.appliedProfile())
        assertEquals("Detected from this workbook's headers", applied.mappingSourceLabel)
        // Nothing was recorded as an override, so nothing about it is persisted either.
        assertTrue(applied.sheetMappings.isEmpty())
        assertTrue(applied.persistableMapping().isEmpty)
    }

    @Test
    fun `an equivalent mapping reconstructed as a new object is still detected`() {
        val detected = state()
        // Same field -> column meaning, other object, other key order, different spelling of the same headers.
        val restated = detected.detectedMappingColumns.entries.reversed().associate { (field, header) ->
            field to "  ${header.uppercase()}  "
        }
        val applied = detected.applySheetMapping(restated)
        assertTrue(applied.isDetectedMapping)
        assertTrue(applied.sheetMappings.isEmpty())
    }

    @Test
    fun `the same detected assignments selected by hand and applied stay detected`() {
        val applied = state().copy(profiles = emptyList()).applyDraft()
        assertTrue(applied.isDetectedMapping)
    }

    @Test
    fun `a change that is restored to the detected value becomes detected again`() {
        val detected = state()
        val changed = detected
            .copy(mappingDraft = detected.mappingDraft + (CatalogField.TITLE to "サークル"))
            .applyDraft()
        assertFalse(changed.isDetectedMapping)

        // Put the detected assignment back and apply: the entry that differed is gone, so this is detection again.
        val restored = changed
            .copy(
                mappingDraft = changed.mappingDraft +
                    (CatalogField.TITLE to detected.detectedHeader(CatalogField.TITLE)),
            )
            .applyDraft()
        assertTrue(restored.isDetectedMapping)
        assertTrue(restored.sheetMappings.isEmpty())
    }

    // ---- grey -----------------------------------------------------------------------------------

    @Test
    fun `one changed assignment is a different mapping`() {
        val detected = state()
        val changed = detected
            .copy(mappingDraft = detected.mappingDraft + (CatalogField.TITLE to "サークル"))
            .applyDraft()
        assertFalse(changed.isDetectedMapping)
        assertEquals("Manual mapping, not saved as a profile", changed.mappingSourceLabel)
        assertEquals("サークル", changed.appliedProfile()?.columns?.get(CatalogField.TITLE))
    }

    @Test
    fun `several changed assignments are a different mapping`() {
        val detected = state()
        val changed = detected.copy(
            mappingDraft = detected.mappingDraft +
                (CatalogField.TITLE to "サークル") +
                (CatalogField.DEFAULT_ORDER to "タイトル"),
        ).applyDraft()
        assertFalse(changed.isDetectedMapping)
        // The whole mapping is stored for the worksheet, with both changed assignments in it.
        val stored = changed.sheetMappings[listKey]?.columns.orEmpty()
        assertEquals("サークル", stored[CatalogField.TITLE])
        assertEquals("タイトル", stored[CatalogField.DEFAULT_ORDER])
    }

    @Test
    fun `clearing a field detection had populated is a different mapping`() {
        val detected = state()
        assertEquals("タイトル", detected.detectedHeader(CatalogField.TITLE))
        // "Not mapped" for a field detection mapped: no entry for it at all.
        val cleared = detected
            .copy(mappingDraft = detected.mappingDraft.withField(CatalogField.TITLE, null))
            .applyDraft()
        assertFalse(cleared.isDetectedMapping)
        assertNull(cleared.appliedProfile()?.columns?.get(CatalogField.TITLE))
        assertTrue(cleared.appliedProfile()?.columns?.isNotEmpty() == true)
    }

    // ---- empty and unassigned values -------------------------------------------------------------

    @Test
    fun `empty and unassigned values are equivalent in the comparison`() {
        val detected = state().detectedMappingColumns
        // A blank header is "not assigned": it must not make an equal mapping look different…
        assertEquals(
            MappingEquality.canonical(detected),
            MappingEquality.canonical(detected + (CatalogField.DESCRIPTION to "") + (CatalogField.URL to "   ")),
        )
        // …and a field that is unassigned on both sides is not a difference either.
        assertTrue(MappingEquality.sameColumns(detected, detected + (CatalogField.DESCRIPTION to "")))
        // A really different header is a difference, in either direction.
        assertFalse(MappingEquality.sameColumns(detected, detected + (CatalogField.TITLE to "別名")))
        assertFalse(MappingEquality.sameColumns(detected - CatalogField.TITLE, detected))
        // Two empty mappings are the same mapping; an empty one differs from a populated one.
        assertTrue(MappingEquality.sameColumns(emptyMap(), mapOf(CatalogField.TITLE to " ")))
        assertFalse(MappingEquality.sameColumns(emptyMap(), detected))
    }

    @Test
    fun `blanks in an applied mapping cannot turn a detected mapping grey`() {
        val detected = state()
        val withBlanks = detected.detectedMappingColumns +
            (CatalogField.DESCRIPTION to "") +
            (CatalogField.TAGS to "   ")
        assertTrue(detected.applySheetMapping(withBlanks).isDetectedMapping)
    }

    // ---- per worksheet, and across navigation ----------------------------------------------------

    @Test
    fun `a different worksheet is evaluated against its own detection`() {
        val detected = state()
        val listChanged = detected
            .copy(mappingDraft = detected.mappingDraft + (CatalogField.TITLE to "サークル"))
            .applyDraft()
        assertFalse(listChanged.isDetectedMapping)
        assertEquals(setOf(listKey), listChanged.sheetMappings.keys)

        // DATA has its own header row and no entry, so it is detected — the grey belongs to LIST alone.
        val onData = seedDraft(listChanged.copy(mappingSheet = "DATA"))
        assertTrue(onData.isDetectedMapping)
        assertNull(onData.appliedProfile())
        assertEquals("Title", onData.detectedHeader(CatalogField.TITLE))
    }

    @Test
    fun `navigating away and back keeps the status of each worksheet`() {
        val detected = state()
        val listChanged = detected
            .copy(mappingDraft = detected.mappingDraft + (CatalogField.TITLE to "サークル"))
            .applyDraft()

        val away = seedDraft(listChanged.copy(mappingSheet = "DATA"))
        assertTrue(away.isDetectedMapping)
        val back = seedDraft(away.copy(mappingSheet = "LIST"))
        assertFalse("LIST still has its own mapping", back.isDetectedMapping)
        assertEquals("サークル", back.appliedProfile()?.columns?.get(CatalogField.TITLE))

        // "Detected" for LIST restores the blue, and DATA still has no entry of its own.
        val cleared = back.clearSheetMapping()
        assertTrue(cleared.isDetectedMapping)
        assertNull(cleared.sheetMappingOf("DATA"))
        assertTrue(cleared.sheetMappings.isEmpty())
    }

    @Test
    fun `restoring the persisted state keeps the status correct`() {
        val detected = state()
        // A mapping equal to detection is not stored, so a reopened workbook is detected and blue.
        val unchanged = detected
            .copy(mappingDraft = detected.mappingDraft + (CatalogField.DESCRIPTION to ""))
            .applyDraft()
        val storedNothing = unchanged.persistableMapping()
        assertTrue(storedNothing.isEmpty)
        assertTrue(state().restoredMapping(storedNothing).isDetectedMapping)

        // A real change is stored for its worksheet, and restoring it is grey again — for that worksheet only.
        val changed = detected
            .copy(mappingDraft = detected.mappingDraft + (CatalogField.TITLE to "サークル"))
            .applyDraft()
        val reopened = state().restoredMapping(changed.persistableMapping())
        assertFalse(reopened.isDetectedMapping)
        assertEquals("サークル", reopened.appliedProfile()?.columns?.get(CatalogField.TITLE))
        assertTrue(seedDraft(reopened.copy(mappingSheet = "DATA")).isDetectedMapping)
        assertEquals(dataKey, listChangedKeyOf(reopened))
    }

    /** The other worksheet's identity, asserted through the model so a cross-sheet key cannot slip in unnoticed. */
    private fun listChangedKeyOf(state: WorkbookCommentsUi): String = state.sheetKeyOf("DATA") ?: ""

    @Test
    fun `applying a saved profile is only detected when the profile means detection`() {
        val detected = state()
        val sameAsDetection = ColumnMappingProfile("Same", detected.detectedMappingColumns)
        val appliedSame = detected.copy(profiles = listOf(sameAsDetection)).selectProfileView(sameAsDetection.name)
        assertTrue("a profile that changes nothing means detection", appliedSame.isDetectedMapping)
        assertEquals("Same", appliedSame.activeProfile)
        assertNull(appliedSame.appliedProfile())

        // Applying that same profile's columns changes nothing either: no edit is stored.
        val appliedSameDraft = appliedSame.applySheetMapping(sameAsDetection.columns)
        assertTrue(appliedSameDraft.isDetectedMapping)
        assertTrue(appliedSameDraft.sheetMappings.isEmpty())

        val other = ColumnMappingProfile("Other", detected.detectedMappingColumns + (CatalogField.TITLE to "書名"))
        val appliedOther = detected.copy(profiles = listOf(other)).selectProfileView(other.name)
        assertFalse(appliedOther.isDetectedMapping)
        assertEquals("Other", appliedOther.activeProfile)
        assertEquals("Saved profile \u201COther\u201D", appliedOther.mappingSourceLabel)
    }

    @Test
    fun `a state with nothing parsed is detected rather than grey`() {
        // Nothing has been read yet, so there is no applied mapping and nothing it could differ from.
        val bare = WorkbookCommentsUi(open = true, sha256 = CatalogFixtures.SHA)
        assertTrue(bare.isDetectedMapping)
        assertNull(bare.appliedProfile())
        assertTrue(bare.sheetMappings.isEmpty())
        // No worksheet is being edited, so applying does nothing instead of inventing a scope.
        assertTrue(bare.applySheetMapping(mapOf(CatalogField.TITLE to "Title")).sheetMappings.isEmpty())
    }
}
