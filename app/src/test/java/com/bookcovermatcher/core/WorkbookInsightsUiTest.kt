package com.bookcovermatcher.core

import com.bookcovermatcher.CommentsSection
import com.bookcovermatcher.InsightsTab
import com.bookcovermatcher.WorkbookCommentsUi
import com.bookcovermatcher.core.catalog.CatalogFixtures
import com.bookcovermatcher.core.catalog.CatalogField
import com.bookcovermatcher.core.catalog.CollectionAnalyticsBuilder
import com.bookcovermatcher.core.catalog.CollectionTimelineBuilder
import com.bookcovermatcher.core.catalog.GalleryFilter
import com.bookcovermatcher.core.catalog.GallerySort
import com.bookcovermatcher.core.catalog.HealthCategory
import com.bookcovermatcher.core.catalog.HealthReportBuilder
import com.bookcovermatcher.core.catalog.HealthSeverity
import com.bookcovermatcher.core.catalog.MappingIssueKind
import com.bookcovermatcher.core.catalog.TimelineMode
import com.bookcovermatcher.core.catalog.WorkbookCatalogBuilder
import com.bookcovermatcher.filterGallery
import com.bookcovermatcher.filterHealth
import com.bookcovermatcher.insightsBelongTo
import com.bookcovermatcher.insightsFailed
import com.bookcovermatcher.insightsLoaded
import com.bookcovermatcher.insightsStarted
import com.bookcovermatcher.mappingEdited
import com.bookcovermatcher.recordOf
import com.bookcovermatcher.selectTimeline
import com.bookcovermatcher.showInsights
import com.bookcovermatcher.sortGallery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Insights state transitions, tested without Compose: what the panels read is what these set. */
class WorkbookInsightsUiTest {

    private fun loaded(): WorkbookCommentsUi {
        val records = listOf(
            CatalogFixtures.catalogueRecord(1),
            CatalogFixtures.catalogueRecord(2, title = "", published = "unknown"),
        )
        val table = CatalogFixtures.table(records = records)
        val images = listOf(CatalogFixtures.linked("xl/media/image1.png", row = 1))
        val analysis = CatalogFixtures.analysis(listOf(table), images)
        val catalog = WorkbookCatalogBuilder.build(
            CatalogFixtures.SHA,
            analysis.sheets,
            analysis.tables,
            analysis.images,
            CatalogFixtures.profile(),
        )
        return WorkbookCommentsUi(
            open = true,
            workbookName = "book.xlsx",
            sha256 = CatalogFixtures.SHA,
            analysis = analysis,
        ).insightsLoaded(
            catalog = catalog,
            health = HealthReportBuilder.build(catalog, analysis, null),
            analytics = CollectionAnalyticsBuilder.build(catalog),
            publication = CollectionTimelineBuilder.publication(catalog),
            acquisition = CollectionTimelineBuilder.acquisition(catalog),
            imported = CollectionTimelineBuilder.imported(catalog, null),
        )
    }

    @Test
    fun `a finished build fills every panel and clears the busy state`() {
        val state = loaded()
        assertFalse(state.insightsBusy)
        assertNull(state.insightsError)
        assertNotNull(state.catalog)
        assertNotNull(state.health)
        assertNotNull(state.analytics)
        assertNotNull(state.publicationTimeline)
        assertNotNull(state.importTimeline)
        assertEquals(2, state.catalog?.entries?.size)
    }

    @Test
    fun `the sub-tab is part of the state, not of the controller`() {
        val state = loaded()
        assertEquals(InsightsTab.HEALTH, state.insightsTab)
        assertEquals(InsightsTab.TIMELINE, state.showInsights(InsightsTab.TIMELINE).insightsTab)
        assertEquals(CommentsSection.INSIGHTS, state.copy(section = CommentsSection.INSIGHTS).section)
    }

    @Test
    fun `starting and failing a build are visible states`() {
        val started = loaded().insightsStarted()
        assertTrue(started.insightsBusy)
        val failed = started.insightsFailed("The workbook is still being read.")
        assertFalse(failed.insightsBusy)
        assertEquals("The workbook is still being read.", failed.insightsError)
    }

    @Test
    fun `a late build for another workbook is rejected`() {
        val state = loaded()
        assertTrue(state.insightsBelongTo(CatalogFixtures.SHA))
        assertFalse(state.insightsBelongTo("other-sha"))
    }

    @Test
    fun `health filters narrow the findings and keep the counts`() {
        val state = loaded()
        val all = state.visibleFindings.size
        val errors = state.filterHealth(HealthSeverity.ERROR)
        assertEquals(HealthSeverity.ERROR, errors.healthSeverity)
        assertTrue(errors.visibleFindings.all { it.severity == HealthSeverity.ERROR })
        assertTrue(errors.visibleFindings.size < all)
        assertEquals(state.insightErrors, errors.visibleFindings.size)
        val recordsOnly = state.filterHealth(null, HealthCategory.RECORDS)
        assertTrue(recordsOnly.visibleFindings.all { it.category == HealthCategory.RECORDS })
    }

    @Test
    fun `gallery filters and sorting change what the panel lists`() {
        val state = loaded()
        assertEquals(2, state.visibleCatalog.size)
        val withCover = state.filterGallery(GalleryFilter(onlyWithCover = true))
        assertEquals(1, withCover.visibleCatalog.size)
        assertTrue(withCover.visibleCatalog.single().hasVerifiedCover)
        val bySource = state.sortGallery(GallerySort.ROW)
        assertEquals(listOf(1, 2), bySource.visibleCatalog.map { it.row })
    }

    @Test
    fun `the timeline panel reads the mode that was selected`() {
        val state = loaded()
        assertEquals(TimelineMode.PUBLICATION, state.timeline?.mode)
        assertEquals(TimelineMode.ACQUISITION, state.selectTimeline(TimelineMode.ACQUISITION).timeline?.mode)
        val imported = state.selectTimeline(TimelineMode.IMPORT).timeline
        assertEquals(TimelineMode.IMPORT, imported?.mode)
        assertFalse(imported?.available ?: true)
    }

    @Test
    fun `a draft mapping carries its preview and issues without touching the applied catalog`() {
        val state = loaded()
        val applied = state.catalog?.profileName
        val draft = state.mappingEdited(
            draft = mapOf(CatalogField.TITLE to "タイトル", CatalogField.CIRCLE to "missing-header"),
            sheetName = "LIST",
            headers = CatalogFixtures.DEFAULT_HEADERS,
            preview = state.catalog?.entries.orEmpty(),
            issues = listOf(
                com.bookcovermatcher.core.catalog.MappingIssue(
                    MappingIssueKind.HEADER_MISSING,
                    CatalogField.CIRCLE,
                    "missing-header",
                    "LIST",
                    "No such column.",
                ),
            ),
        )
        assertTrue(draft.mappingDirty)
        assertEquals("LIST", draft.mappingSheet)
        assertEquals(2, draft.mappingPreview.size)
        assertEquals(1, draft.mappingIssues.size)
        assertEquals(applied, draft.catalog?.profileName)
    }

    @Test
    fun `a catalog entry resolves back to its table and record`() {
        val state = loaded()
        val entry = state.catalog!!.entries.first()
        val pair = state.recordOf(entry)
        assertNotNull(pair)
        assertEquals("LIST", pair!!.first.sheetName)
        assertEquals(entry.row, pair.second.row)
        val stranger = entry.copy(sheetName = "ELSEWHERE")
        assertNull(state.recordOf(stranger))
    }

    @Test
    fun `the tab badge counts confirmed errors only`() {
        val state = loaded()
        assertEquals(state.health?.summary?.errors, state.insightErrors)
        assertTrue(state.insightErrors >= 1)
    }
}