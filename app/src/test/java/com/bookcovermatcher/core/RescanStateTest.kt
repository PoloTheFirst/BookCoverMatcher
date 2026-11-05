package com.bookcovermatcher.core

import com.bookcovermatcher.CommentsSection
import com.bookcovermatcher.WorkbookCommentsUi
import com.bookcovermatcher.core.catalog.CatalogField
import com.bookcovermatcher.core.catalog.CatalogFixtures
import com.bookcovermatcher.core.catalog.CollectionAnalyticsBuilder
import com.bookcovermatcher.core.catalog.CollectionTimelineBuilder
import com.bookcovermatcher.core.catalog.GalleryFilter
import com.bookcovermatcher.core.catalog.HealthReportBuilder
import com.bookcovermatcher.core.catalog.SheetMapping
import com.bookcovermatcher.core.catalog.SheetScope
import com.bookcovermatcher.core.catalog.TimelineFilter
import com.bookcovermatcher.core.catalog.TimelineMode
import com.bookcovermatcher.core.catalog.WorkbookCatalogBuilder
import com.bookcovermatcher.core.excel.comments.WorkbookAnalysis
import com.bookcovermatcher.core.excel.comments.WorkbookCommentImages
import com.bookcovermatcher.insightsLoaded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a rescan does to the state.
 *
 * The bug: the reports were only rebuilt when the user left the Insights directory and came back, because
 * `reload()` dropped the derived results and nothing rebuilt them. The fix has two halves — this state
 * (drops old results, keeps the user's choices, shows the reading state) and the controller, which rebuilds
 * from the new analysis in place. These tests pin the half that is pure.
 */
class RescanStateTest {

    private val list = "xl/worksheets/sheet1.xml"

    private fun analysis(): WorkbookAnalysis = CatalogFixtures.analysis(
        tables = listOf(CatalogFixtures.table(records = listOf(CatalogFixtures.catalogueRecord(1)))),
    )

    private fun emptyModel() = WorkbookCommentImages(
        workbookName = "book.xlsx",
        sha256 = CatalogFixtures.SHA,
        sheets = listOf("LIST"),
        comments = emptyList(),
        media = emptyList(),
        diagnostics = emptyList(),
        threadedCommentParts = emptyList(),
    )

    private fun loaded(section: CommentsSection = CommentsSection.INSIGHTS): WorkbookCommentsUi {
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
            section = section,
            profiles = listOf(CatalogFixtures.profile("Mine")),
            sheetMappings = mapOf(
                "xl/worksheets/sheet1.xml" to SheetMapping(
                    profileName = "Mine",
                    columns = mapOf(CatalogField.TITLE to "\u30BF\u30A4\u30C8\u30EB"),
                ),
            ),
            healthScope = SheetScope(setOf(list)),
            analyticsScope = SheetScope(setOf(list)),
            galleryFilter = GalleryFilter(sheets = SheetScope(setOf(list)), query = "a"),
            timelineFilters = mapOf(TimelineMode.PUBLICATION to TimelineFilter(fromYear = 2019)),
            mappingDraft = mapOf(CatalogField.TITLE to "タイトル"),
            mappingSheet = "LIST",
        ).insightsLoaded(
            catalog = catalog,
            health = HealthReportBuilder.build(catalog, analysis, null),
            analytics = CollectionAnalyticsBuilder.build(catalog),
            publication = CollectionTimelineBuilder.published(catalog, CatalogFixtures.dates()),
            acquisition = CollectionTimelineBuilder.acquired(catalog, CatalogFixtures.dates()),
        )
    }

    @Test
    fun `a rescan drops the results and keeps every user choice`() {
        val before = loaded()
        val reloading = before.reloadingForRescan()

        // Old bytes: nothing derived from them is kept.
        assertNull(reloading.catalog)
        assertNull(reloading.health)
        assertNull(reloading.analytics)
        assertNull(reloading.publicationTimeline)
        assertNull(reloading.acquisitionTimeline)
        assertTrue(reloading.mappingPreview.isEmpty())
        assertTrue(reloading.loading)
        // Choices: everything the user set survives.
        assertEquals(before.healthScope, reloading.healthScope)
        assertEquals(before.analyticsScope, reloading.analyticsScope)
        assertEquals(before.galleryFilter, reloading.galleryFilter)
        assertEquals(before.timelineFilters, reloading.timelineFilters)
        assertEquals(before.mappingDraft, reloading.mappingDraft)
        assertEquals(before.profiles, reloading.profiles)
        assertEquals(before.sheetMappings, reloading.sheetMappings)
        assertEquals(CommentsSection.INSIGHTS, reloading.section)
    }

    @Test
    fun `a rescan from another section does not start a rebuild`() {
        val comments = loaded(CommentsSection.COMMENTS)
        assertFalse(comments.wantsInsights)
        assertFalse(comments.reloadingForRescan().insightsBusy)
    }

    @Test
    fun `a rescan in Insights shows the reading state instead of an empty report`() {
        val reloading = loaded().reloadingForRescan()
        assertTrue(reloading.wantsInsights)
        assertTrue(reloading.insightsBusy)
        // A finished scan keeps the busy state, so the panels cannot flash an empty report.
        val finished = reloading.analysisLoaded(emptyModel(), analysis())
        assertFalse(finished.loading)
        assertTrue(finished.insightsBusy)
        // A failure clears it: an error must not leave the panels stuck on "reading".
        val failed = reloading.reloadFailed("could not read")
        assertFalse(failed.insightsBusy)
        assertEquals("could not read", failed.error)
    }

    @Test
    fun `the rebuilt results are the new ones, so no navigation is needed`() {
        // What the controller does after a successful rescan: build again from the new analysis, in place.
        val reloading = loaded().reloadingForRescan()
        val analysis = analysis()
        val catalog = WorkbookCatalogBuilder.build(
            CatalogFixtures.SHA,
            analysis.sheets,
            analysis.tables,
            analysis.images,
            CatalogFixtures.profile(),
        )
        val rebuilt = reloading
            .analysisLoaded(emptyModel(), analysis)
            .insightsLoaded(
                catalog = catalog,
                health = HealthReportBuilder.build(catalog, analysis, null, reloading.healthScope),
                analytics = CollectionAnalyticsBuilder.build(catalog, reloading.analyticsScope),
                publication = CollectionTimelineBuilder.published(catalog, CatalogFixtures.dates()),
                acquisition = CollectionTimelineBuilder.acquired(catalog, CatalogFixtures.dates()),
            )
        assertFalse(rebuilt.insightsBusy)
        assertEquals(catalog, rebuilt.catalog)
        assertTrue(rebuilt.visibleCatalog.isNotEmpty())
        // The gallery and the timeline still honour the filters the user had set.
        assertEquals(reloading.galleryFilter, rebuilt.galleryFilter)
        assertEquals(reloading.timelineFilters, rebuilt.timelineFilters)
    }
}
