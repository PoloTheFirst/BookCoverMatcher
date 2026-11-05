package com.bookcovermatcher.core

import com.bookcovermatcher.WorkbookCommentsUi
import com.bookcovermatcher.applySheetMapping
import com.bookcovermatcher.clearSheetMapping
import com.bookcovermatcher.core.catalog.CatalogField
import com.bookcovermatcher.core.catalog.CatalogFixtures
import com.bookcovermatcher.core.catalog.ColumnMappingProfile
import com.bookcovermatcher.core.catalog.SheetMapping
import com.bookcovermatcher.core.catalog.WorkbookMapping
import com.bookcovermatcher.core.catalog.WorkbookMappingCodec
import com.bookcovermatcher.data.WorkbookMappingStore
import com.bookcovermatcher.persistableMapping
import com.bookcovermatcher.restoredMapping
import com.bookcovermatcher.selectProfileView
import com.bookcovermatcher.withField
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What a workbook is left looking at: the profile view selected and the edits applied on top of it.
 *
 * The saved profiles themselves live in their own store (see `ColumnMappingStoreTest`); this file covers the
 * per-workbook state, its codec, and the migration of a file written before mappings were per worksheet.
 */
class MappingPersistenceTest {

    private lateinit var dir: File

    private val listKey = "xl/worksheets/sheet1.xml"
    private val dataKey = "xl/worksheets/sheet2.xml"

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("bcm-mapping").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** A workbook whose third column is the sample workbook's own order header, detected by its alias. */
    private fun state(): WorkbookCommentsUi {
        val table = CatalogFixtures.table(
            sheetName = "LIST",
            fields = CatalogFixtures.fields("タイトル", "サークル", "通し番号"),
            records = listOf(CatalogFixtures.record(1, "Book A", "Circle A", "10")),
        )
        return WorkbookCommentsUi(
            open = true,
            sha256 = CatalogFixtures.SHA,
            analysis = CatalogFixtures.analysis(tables = listOf(table)),
            mappingSheet = "LIST",
        )
    }

    /** Two worksheets with different header rows — the case that used to leak between them. */
    private fun twoSheetState(): WorkbookCommentsUi {
        val list = CatalogFixtures.table(
            sheetName = "LIST",
            fields = CatalogFixtures.fields("タイトル", "サークル", "通し番号"),
            records = listOf(CatalogFixtures.record(1, "Book A", "Circle A", "10")),
        )
        val data = CatalogFixtures.table(
            sheetName = "DATA",
            fields = CatalogFixtures.fields("Title", "Circle", "説明"),
            records = listOf(CatalogFixtures.record(1, "Book B", "Circle B", "note B")),
        )
        return WorkbookCommentsUi(
            open = true,
            sha256 = CatalogFixtures.SHA,
            analysis = CatalogFixtures.analysis(tables = listOf(list, data)),
            mappingSheet = "LIST",
        )
    }

    // ---- the codec -----------------------------------------------------------------------------

    @Test
    fun `the codec keeps the selected profile and one entry per worksheet`() {
        val mapping = WorkbookMapping(
            workbookSha256 = CatalogFixtures.SHA,
            activeProfile = "Custom A",
            sheets = mapOf(
                listKey to SheetMapping(columns = mapOf(CatalogField.TITLE to "タイトル")),
                dataKey to SheetMapping(columns = mapOf(CatalogField.TITLE to "Title")),
            ),
        )
        val decoded = WorkbookMappingCodec.decode(WorkbookMappingCodec.encode(listOf(mapping))).single()
        assertEquals(CatalogFixtures.SHA, decoded.workbookSha256)
        assertEquals("Custom A", decoded.activeProfile)
        assertEquals(2, decoded.sheets.size)
        assertEquals("タイトル", decoded.sheets[listKey]?.columns?.get(CatalogField.TITLE))
        assertEquals("Title", decoded.sheets[dataKey]?.columns?.get(CatalogField.TITLE))
        assertNull("a v2 file has nothing to migrate", decoded.legacy)
    }

    @Test
    fun `entries without a workbook or without columns are dropped, and broken JSON is not fatal`() {
        assertEquals(emptyList<WorkbookMapping>(), WorkbookMappingCodec.decode("not json at all"))
        assertEquals(emptyList<WorkbookMapping>(), WorkbookMappingCodec.decode("""{"schema":3}"""))
        // A workbook entry whose worksheets carry no columns is "nothing stored", so it is not written at all.
        assertEquals(
            emptyList<WorkbookMapping>(),
            WorkbookMappingCodec.decode(
                WorkbookMappingCodec.encode(
                    listOf(
                        WorkbookMapping(
                            workbookSha256 = CatalogFixtures.SHA,
                            sheets = mapOf(listKey to SheetMapping(), dataKey to SheetMapping(profileName = "X")),
                        ),
                    ),
                ),
            ),
        )
        // An unknown field name (a newer schema) is skipped instead of guessed; the known one survives.
        val decoded = WorkbookMappingCodec.decode(
            """{"schema":3,"workbooks":[{"sha256":"sha","sheets":{"k":{"columns":{"TITLE":"Title","NOPE":"x"}}}}]}""",
        ).single()
        assertEquals(mapOf(CatalogField.TITLE to "Title"), decoded.sheets["k"]?.columns)
    }

    @Test
    fun `a schema-1 file decodes into the migration slot instead of being applied to every worksheet`() {
        // What v2.4.0-rc.4 wrote: one column set for the whole workbook, with no worksheet identity at all.
        val old = """
            {"schema":1,"workbooks":[{"sha256":"${CatalogFixtures.SHA}",
            "profile":"My profile","columns":{"TITLE":"タイトル","DEFAULT_ORDER":"通し番号"}}]}
        """.trimIndent()
        val decoded = WorkbookMappingCodec.decode(old).single()
        assertTrue("no worksheet can be named by a v1 file", decoded.sheets.isEmpty())
        assertEquals("My profile", decoded.legacy?.profileName)
        assertEquals("タイトル", decoded.legacy?.columns?.get(CatalogField.TITLE))
    }

    // ---- the store -----------------------------------------------------------------------------

    @Test
    fun `the store remembers one entry per workbook and forgets it when it is cleared`() {
        val store = WorkbookMappingStore(dir)
        val mapping = WorkbookMapping(
            workbookSha256 = CatalogFixtures.SHA,
            activeProfile = "Custom A",
            sheets = mapOf(listKey to SheetMapping(columns = mapOf(CatalogField.DEFAULT_ORDER to "通し番号"))),
        )
        store.save(mapping)
        assertEquals(
            "通し番号",
            store.load(CatalogFixtures.SHA).sheets[listKey]?.columns?.get(CatalogField.DEFAULT_ORDER),
        )
        assertEquals("Custom A", store.load(CatalogFixtures.SHA).activeProfile)

        // Another workbook keeps its own entry, and an empty mapping removes this one.
        store.save(
            WorkbookMapping(
                workbookSha256 = "other-sha",
                sheets = mapOf(listKey to SheetMapping(columns = mapOf(CatalogField.TITLE to "Name"))),
            ),
        )
        assertEquals(2, store.load().size)
        store.save(WorkbookMapping(CatalogFixtures.SHA))
        assertEquals(1, store.load().size)
        assertTrue(store.load(CatalogFixtures.SHA).isEmpty)

        store.save(mapping)
        store.delete(CatalogFixtures.SHA)
        assertTrue(store.load(CatalogFixtures.SHA).isEmpty)
        // A file that was never written reads as "nothing stored" instead of failing.
        assertTrue(WorkbookMappingStore(File(dir, "missing")).load().isEmpty())
    }

    // ---- the state in both directions ----------------------------------------------------------

    @Test
    fun `the selected view and the applied edits come back after a restart`() {
        val profile = ColumnMappingProfile("Custom A", mapOf(CatalogField.TITLE to "サークル"))
        val applied = state()
            .copy(profiles = listOf(profile))
            .selectProfileView("Custom A")
            .applySheetMapping(mapOf(CatalogField.TITLE to "通し番号"))
        val stored = applied.persistableMapping()
        assertEquals(CatalogFixtures.SHA, stored.workbookSha256)
        assertEquals("Custom A", stored.activeProfile)
        assertEquals(setOf(listKey), stored.sheets.keys)

        // A reopened workbook restores the view and the edit, and nothing more.
        val reopened = state().copy(profiles = listOf(profile)).restoredMapping(stored)
        assertEquals("Custom A", reopened.activeProfile)
        assertFalse(reopened.isDetectedMapping)
        assertEquals("通し番号", reopened.appliedProfile()?.columns?.get(CatalogField.TITLE))
    }

    @Test
    fun `a view whose profile has been deleted falls back to Detected`() {
        val stored = WorkbookMapping(
            workbookSha256 = CatalogFixtures.SHA,
            activeProfile = "Gone",
            sheets = mapOf(listKey to SheetMapping(columns = mapOf(CatalogField.TITLE to "サークル"))),
        )
        val reopened = state().restoredMapping(stored)
        assertNull("no profile of that name exists any more", reopened.activeProfile)
        assertTrue(reopened.isDetectedView)
        // The applied edit is still replayed: it is this workbook's own state, not the profile's.
        assertEquals("サークル", reopened.appliedProfile()?.columns?.get(CatalogField.TITLE))
    }

    @Test
    fun `an entry that means the selected view is not restored as an applied edit`() {
        // A profile whose columns are exactly detection's, plus a stored edit that repeats them.
        val detection = state().detectedMappingColumns
        val profile = ColumnMappingProfile("Same", detection)
        val stored = WorkbookMapping(
            workbookSha256 = CatalogFixtures.SHA,
            activeProfile = "Same",
            sheets = mapOf(listKey to SheetMapping(columns = detection)),
        )
        val reopened = state().copy(profiles = listOf(profile)).restoredMapping(stored)
        assertEquals("Same", reopened.activeProfile)
        assertTrue("the edit repeats the profile, so there is nothing to replay", reopened.sheetMappings.isEmpty())
        // And that profile means detection, so the Detected status is blue.
        assertTrue(reopened.isDetectedMapping)
    }

    @Test
    fun `nothing stored keeps the Detected view, and applying nothing stores nothing`() {
        val fresh = state().restoredMapping(WorkbookMapping(CatalogFixtures.SHA))
        assertTrue(fresh.isDetectedView)
        assertTrue(fresh.isDetectedMapping)
        assertNull(fresh.appliedProfile())

        val applied = state().let { it.applySheetMapping(it.detectedMappingColumns + (CatalogField.TITLE to "書名")) }
        assertFalse(applied.persistableMapping().isEmpty)
        // "Detected" switches the view back and drops the applied edit, which is what empties the file.
        assertTrue(applied.selectProfileView(null).persistableMapping().isEmpty)
        assertTrue(applied.clearSheetMapping().persistableMapping().isEmpty)
    }

    // ---- the v1 migration ----------------------------------------------------------------------

    @Test
    fun `a v1 column set is migrated to the worksheets that really have those headers`() {
        val legacy = WorkbookMapping(
            workbookSha256 = CatalogFixtures.SHA,
            legacy = SheetMapping(profileName = "Old", mapOf(CatalogField.TITLE to "タイトル")),
        )
        val reopened = twoSheetState().restoredMapping(legacy)
        // LIST has that header, so it keeps the choice — as a manual mapping, because v1 never named a worksheet.
        assertEquals("タイトル", reopened.sheetMappings[listKey]?.columns?.get(CatalogField.TITLE))
        assertNull(reopened.sheetMappings[listKey]?.profileName)
        // DATA does not have it, so it is not given anything: its own detection is left alone.
        assertNull(reopened.sheetMappings[dataKey])
        assertEquals("Title", twoSheetState().copy(mappingSheet = "DATA").detectedHeader(CatalogField.TITLE))
    }

    @Test
    fun `a v1 column set that no worksheet has leaves every worksheet on its own detection`() {
        val legacy = WorkbookMapping(
            workbookSha256 = CatalogFixtures.SHA,
            legacy = SheetMapping(columns = mapOf(CatalogField.TITLE to "名前")),
        )
        val reopened = twoSheetState().restoredMapping(legacy)
        assertTrue(reopened.sheetMappings.isEmpty())
        assertTrue(reopened.persistableMapping().isEmpty)
    }

    // ---- the picker's edit rule ----------------------------------------------------------------

    @Test
    fun `mapping one field changes that field only`() {
        val draft = mapOf<CatalogField, String?>(CatalogField.TITLE to "Title")
        val next = draft.withField(CatalogField.DEFAULT_ORDER, "通し番号")
        assertEquals("Title", next[CatalogField.TITLE])
        assertEquals("通し番号", next[CatalogField.DEFAULT_ORDER])
        // The original draft is untouched, so an unapplied edit can always be discarded.
        assertFalse(CatalogField.DEFAULT_ORDER in draft)
        // Clearing is the same rule with null.
        assertNull(next.withField(CatalogField.DEFAULT_ORDER, null)[CatalogField.DEFAULT_ORDER])
    }

    @Test
    fun `the detected order column is the one detection would choose`() {
        val detected = state()
        assertEquals("通し番号", detected.detectedHeader(CatalogField.DEFAULT_ORDER))

        val agreeing = detected.copy(
            mappingDraft = detected.mappingDraft.withField(CatalogField.DEFAULT_ORDER, "通し番号"),
        )
        assertTrue(agreeing.defaultOrderIsDetected)
        val changed = detected.copy(
            mappingDraft = detected.mappingDraft.withField(CatalogField.DEFAULT_ORDER, "サークル"),
        )
        assertFalse(changed.defaultOrderIsDetected)
        assertEquals("サークル", changed.defaultOrderHeader)
    }

    @Test
    fun `a worksheet with no candidate column detects nothing and says so`() {
        val table = CatalogFixtures.table(
            sheetName = "DATA",
            fields = CatalogFixtures.fields("Title", "Circle"),
            records = listOf(CatalogFixtures.record(1, "Book", "Circle")),
        )
        val state = WorkbookCommentsUi(
            open = true,
            sha256 = CatalogFixtures.SHA,
            analysis = CatalogFixtures.analysis(tables = listOf(table)),
            mappingSheet = "DATA",
        )
        assertNull(state.detectedHeader(CatalogField.DEFAULT_ORDER))
        // Nothing detected and nothing mapped: the field is honestly "not mapped", not guessed.
        assertNull(state.defaultOrderHeader)
        assertTrue(state.defaultOrderIsDetected)
        assertNotNull(state.mappingTable)
    }
}
