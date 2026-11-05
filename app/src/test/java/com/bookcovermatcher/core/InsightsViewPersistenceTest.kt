package com.bookcovermatcher.core

import com.bookcovermatcher.ScopeTarget
import com.bookcovermatcher.TimelineYearEndpoint
import com.bookcovermatcher.WorkbookCommentsUi
import com.bookcovermatcher.core.catalog.CatalogFixtures
import com.bookcovermatcher.core.catalog.InsightsView
import com.bookcovermatcher.core.catalog.InsightsViewCodec
import com.bookcovermatcher.core.catalog.SheetScope
import com.bookcovermatcher.core.catalog.TimelineMode
import com.bookcovermatcher.core.catalog.WorkbookCatalogBuilder
import com.bookcovermatcher.core.catalog.YearRange
import com.bookcovermatcher.data.InsightsViewStore
import com.bookcovermatcher.openScopeDialog
import com.bookcovermatcher.persistableView
import com.bookcovermatcher.pickTimelineYear
import com.bookcovermatcher.restoredView
import com.bookcovermatcher.scopeCommitted
import com.bookcovermatcher.toggleScopeSheet
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the Insights screens remember: three independent worksheet selections and each timeline's year range,
 * stored per workbook.
 *
 * Covered here: the codec (versioned and tolerant), the file store (one entry per workbook), and the state
 * mapping in both directions — including the rule that a saved worksheet key the workbook no longer has is
 * reconciled away instead of matching another sheet.
 */
class InsightsViewPersistenceTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("bcm-views").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val list = "xl/worksheets/sheet1.xml"
    private val data = "xl/worksheets/sheet2.xml"

    private fun state(): WorkbookCommentsUi {
        val analysis = CatalogFixtures.analysis(
            tables = listOf(
                CatalogFixtures.table(sheetName = "LIST", records = listOf(CatalogFixtures.catalogueRecord(1))),
                CatalogFixtures.table(
                    sheetName = "DATA",
                    fields = CatalogFixtures.fields("Title", "Circle", "Note"),
                    records = listOf(CatalogFixtures.record(1, "Other", "Circle O", "n")),
                ),
            ),
        )
        val catalog = WorkbookCatalogBuilder.build(
            CatalogFixtures.SHA,
            analysis.sheets,
            analysis.tables,
            analysis.images,
            CatalogFixtures.profile(),
        )
        return WorkbookCommentsUi(open = true, sha256 = CatalogFixtures.SHA, analysis = analysis, catalog = catalog)
    }

    // ---- codec ---------------------------------------------------------------------------------

    @Test
    fun `the codec keeps the three scopes apart and the year ranges per mode`() {
        val view = InsightsView(
            workbookSha256 = "sha",
            health = SheetScope(setOf(list)),
            analytics = SheetScope(setOf(list, data)),
            gallery = SheetScope(setOf(data)),
            years = mapOf(
                TimelineMode.PUBLICATION to YearRange(2019, 2024),
                TimelineMode.ACQUISITION to YearRange(null, 2023),
            ),
        )
        val decoded = InsightsViewCodec.decode(InsightsViewCodec.encode(listOf(view))).single()
        assertEquals(view, decoded)
        assertEquals(SheetScope(setOf(list)), decoded.health)
        assertEquals(SheetScope(setOf(data)), decoded.gallery)
        assertEquals(YearRange(2019, 2024), decoded.yearsOf(TimelineMode.PUBLICATION))
        assertEquals(YearRange(null, 2023), decoded.yearsOf(TimelineMode.ACQUISITION))
    }

    @Test
    fun `an empty view is not written, and a broken file loads as nothing`() {
        assertTrue(InsightsViewCodec.decode(InsightsViewCodec.encode(listOf(InsightsView("sha")))).isEmpty())
        assertTrue(InsightsViewCodec.decode("{ not json").isEmpty())
        assertTrue(InsightsViewCodec.decode("").isEmpty())
        // An entry without a sha is skipped; the readable one still loads.
        val mixed = """{"schema":1,"workbooks":[{"health":["a"]},{"sha256":"sha-1","gallery":["$data"]}]}"""
        assertEquals(listOf("sha-1"), InsightsViewCodec.decode(mixed).map { it.workbookSha256 })
    }

    // ---- store ---------------------------------------------------------------------------------

    @Test
    fun `the store keeps one entry per workbook across instances`() {
        val store = InsightsViewStore(dir)
        store.save(InsightsView("sha-1", gallery = SheetScope(setOf(data))))
        store.save(InsightsView("sha-2", health = SheetScope(setOf(list))))
        val reloaded = InsightsViewStore(dir)
        assertEquals(SheetScope(setOf(data)), reloaded.load("sha-1").gallery)
        assertEquals(SheetScope(setOf(list)), reloaded.load("sha-2").health)
        // A workbook with no entry gets the defaults, never another workbook's choice.
        assertTrue(reloaded.load("sha-3").isEmpty)
    }

    @Test
    fun `clearing every selection removes the workbook's entry`() {
        val store = InsightsViewStore(dir)
        store.save(InsightsView("sha-1", gallery = SheetScope(setOf(data))))
        store.save(InsightsView("sha-1"))
        assertTrue(store.load().isEmpty())
        assertTrue(store.load("sha-1").isEmpty)
    }

    // ---- state in both directions ---------------------------------------------------------------

    @Test
    fun `what the user chose is what is saved, and restoring it brings it back`() {
        val chosen = state()
            .openScopeDialog(ScopeTarget.HEALTH).toggleScopeSheet(list).scopeCommitted()
            .openScopeDialog(ScopeTarget.ANALYTICS).toggleScopeSheet(data).scopeCommitted()
            .openScopeDialog(ScopeTarget.GALLERY).toggleScopeSheet(data).scopeCommitted()
            .pickTimelineYear(TimelineYearEndpoint.FROM, 2019)
            .pickTimelineYear(TimelineYearEndpoint.TO, 2024)

        val saved = chosen.persistableView()
        assertEquals(SheetScope(setOf(list)), saved.health)
        assertEquals(SheetScope(setOf(data)), saved.analytics)
        assertEquals(SheetScope(setOf(data)), saved.gallery)
        assertEquals(YearRange(2019, 2024), saved.yearsOf(TimelineMode.PUBLICATION))
        assertNull(saved.yearsOf(TimelineMode.ACQUISITION).fromYear)

        // Reopening the workbook starts from the defaults and restores exactly those selections.
        val source = state()
        val reopened = WorkbookCommentsUi(
            open = true,
            sha256 = CatalogFixtures.SHA,
            catalog = source.catalog,
            analysis = source.analysis,
        ).restoredView(saved)
        assertEquals(SheetScope(setOf(list)), reopened.healthScope)
        assertEquals(SheetScope(setOf(data)), reopened.analyticsScope)
        assertEquals(SheetScope(setOf(data)), reopened.galleryFilter.sheets)
        assertEquals(2019, reopened.timelineFilters[TimelineMode.PUBLICATION]?.fromYear)
        assertEquals(2024, reopened.timelineFilters[TimelineMode.PUBLICATION]?.toYear)
        assertNull(reopened.timelineFilters[TimelineMode.ACQUISITION])
    }

    @Test
    fun `the search text is a query, not a saved view setting`() {
        val searched = state().let { it.copy(galleryFilter = it.galleryFilter.copy(query = "alpha")) }
        assertTrue(searched.persistableView().isEmpty)
    }

    @Test
    fun `a saved worksheet key the workbook no longer has is reconciled away`() {
        val stale = InsightsView(
            workbookSha256 = CatalogFixtures.SHA,
            health = SheetScope(setOf(list, "xl/worksheets/sheet99.xml")),
            gallery = SheetScope(setOf("xl/worksheets/sheet99.xml")),
        )
        val source = state()
        val restored = WorkbookCommentsUi(
            open = true,
            sha256 = CatalogFixtures.SHA,
            catalog = source.catalog,
            analysis = source.analysis,
        ).restoredView(stale)
        // The surviving key is kept; a selection that matches nothing falls back to all worksheets.
        assertEquals(setOf(list), restored.healthScope.keys)
        assertTrue(restored.galleryFilter.sheets.isAll)
    }

    @Test
    fun `a workbook with no saved entry keeps the defaults`() {
        val fresh = WorkbookCommentsUi(open = true, sha256 = "other-sha")
            .restoredView(InsightsView("other-sha"))
        assertTrue(fresh.healthScope.isAll)
        assertTrue(fresh.analyticsScope.isAll)
        assertTrue(fresh.galleryFilter.sheets.isAll)
        assertTrue(fresh.timelineFilters.isEmpty())
        assertEquals("All worksheets", fresh.scopeLabel(fresh.healthScope))
    }
}
