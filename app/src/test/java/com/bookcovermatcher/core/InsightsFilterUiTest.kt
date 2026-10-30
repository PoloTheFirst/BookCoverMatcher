package com.bookcovermatcher.core

import com.bookcovermatcher.ScopeTarget
import com.bookcovermatcher.TimelineYearEndpoint
import com.bookcovermatcher.WorkbookCommentsUi
import com.bookcovermatcher.clearTimelineFilter
import com.bookcovermatcher.closeScopeDialog
import com.bookcovermatcher.filterTimeline
import com.bookcovermatcher.insightsFailed
import com.bookcovermatcher.insightsLoaded
import com.bookcovermatcher.insightsStarted
import com.bookcovermatcher.openScopeDialog
import com.bookcovermatcher.pickTimelineYear
import com.bookcovermatcher.scopeCommitted
import com.bookcovermatcher.scopeSelectAll
import com.bookcovermatcher.selectTimeline
import com.bookcovermatcher.timelineYearPicker
import com.bookcovermatcher.toggleScopeSheet
import com.bookcovermatcher.core.catalog.CatalogFixtures
import com.bookcovermatcher.core.catalog.CollectionAnalyticsBuilder
import com.bookcovermatcher.core.catalog.CollectionTimelineBuilder
import com.bookcovermatcher.core.catalog.HealthReportBuilder
import com.bookcovermatcher.core.catalog.HealthSeverity
import com.bookcovermatcher.core.catalog.SheetScope
import com.bookcovermatcher.core.catalog.TimelineFilter
import com.bookcovermatcher.core.catalog.TimelineMode
import com.bookcovermatcher.core.catalog.WorkbookCatalogBuilder
import com.bookcovermatcher.core.excel.comments.CatalogueField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Timeline filters and worksheet scopes as state: what the two screens read, and what they never share. */
class InsightsFilterUiTest {

    private fun analysis() = CatalogFixtures.analysis(
        tables = listOf(
            CatalogFixtures.table(
                sheetName = "LIST",
                records = listOf(
                    CatalogFixtures.catalogueRecord(1),
                    CatalogFixtures.catalogueRecord(2, published = "2020"),
                    CatalogFixtures.catalogueRecord(3, title = "", published = "17/05/2024"),
                ),
            ),
            CatalogFixtures.table(
                sheetName = "DATA",
                fields = listOf(
                    CatalogueField("タイトル", 0),
                    CatalogueField("サークル", 1),
                    CatalogueField("メモ", 2),
                    CatalogueField("発行日", 3),
                ),
                records = listOf(CatalogFixtures.record(1, "", "Other circle", "n", "2024-06-01")),
            ),
        ),
    )

    private fun loaded(): WorkbookCommentsUi {
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
            workbookName = "doujin.xlsx",
            sha256 = CatalogFixtures.SHA,
            analysis = analysis,
        ).insightsLoaded(
            catalog = catalog,
            health = HealthReportBuilder.build(catalog, analysis, null),
            analytics = CollectionAnalyticsBuilder.build(catalog),
            publication = CollectionTimelineBuilder.published(catalog, CatalogFixtures.dates()),
            acquisition = CollectionTimelineBuilder.acquired(catalog, CatalogFixtures.dates()),
            imported = CollectionTimelineBuilder.imported(catalog, null),
        )
    }

    // ---- timeline filter ---------------------------------------------------------------------

    @Test
    fun `each date meaning keeps its own filter`() {
        val filtered = loaded().filterTimeline(TimelineFilter(query = "Book 1"))
        assertEquals("Book 1", filtered.currentTimelineFilter.query)
        // The other meaning is untouched: switching modes must not smuggle a filter into it.
        val switched = filtered.selectTimeline(TimelineMode.ACQUISITION)
        assertEquals("", switched.currentTimelineFilter.query)
        assertFalse(switched.currentTimelineFilter.isActive)
    }

    @Test
    fun `clearing a filter restores that meaning and leaves the other alone`() {
        val both = loaded()
            .filterTimeline(TimelineFilter(query = "Book 1"), TimelineMode.PUBLICATION)
            .filterTimeline(TimelineFilter(query = "Book 2"), TimelineMode.ACQUISITION)
            .clearTimelineFilter(TimelineMode.PUBLICATION)
        assertEquals("", both.timelineFilter(TimelineMode.PUBLICATION).query)
        assertEquals("Book 2", both.timelineFilter(TimelineMode.ACQUISITION).query)
        assertFalse(both.timelineFilters.containsKey(TimelineMode.PUBLICATION))
    }

    @Test
    fun `the year picker sets one bound at a time and closes itself`() {
        val opened = loaded().timelineYearPicker(TimelineYearEndpoint.FROM)
        assertEquals(TimelineYearEndpoint.FROM, opened.timelineYearPicker)

        val from = opened.pickTimelineYear(TimelineYearEndpoint.FROM, 2020)
        assertNull("the picker closes after a choice", from.timelineYearPicker)
        assertEquals(2020, from.currentTimelineFilter.fromYear)
        assertEquals("from 2020", from.currentTimelineFilter.rangeLabel)

        val ranged = from.pickTimelineYear(TimelineYearEndpoint.TO, 2024)
        assertEquals(2020, ranged.currentTimelineFilter.fromYear)
        assertEquals(2024, ranged.currentTimelineFilter.toYear)
        assertEquals("2020\u20132024", ranged.currentTimelineFilter.rangeLabel)

        val cleared = ranged.pickTimelineYear(TimelineYearEndpoint.TO, null)
        assertNull(cleared.currentTimelineFilter.toYear)
        assertEquals(2020, cleared.currentTimelineFilter.fromYear)
    }

    @Test
    fun `the filter narrows what the timeline shows and counts what it could not judge`() {
        val base = loaded()
        val baseView = base.timelineView!!
        // 2 dated rows on LIST + 2 records with no readable date: LIST row 3, and DATA, whose worksheet has
        // no date column assigned at all (the fixture only configures LIST).
        assertEquals(4, baseView.shown)
        assertEquals(2, baseView.timeline.unknownCount)

        val year = base.filterTimeline(TimelineFilter(fromYear = 2020, toYear = 2020))
        val yearView = year.timelineView!!
        assertEquals(1, yearView.shown)
        assertEquals("a date range cannot judge the two undated records", 2, yearView.withoutDateExcluded)

        val noMatch = base.filterTimeline(TimelineFilter(query = "nothing here"))
        assertTrue(noMatch.timelineView!!.isEmpty)

        // A text search still applies to the unknown-date bucket, which is not given a year to fit.
        val byCircle = base.filterTimeline(TimelineFilter(query = "Circle 3"))
        val byCircleView = byCircle.timelineView!!
        assertEquals(0, byCircleView.timeline.datedCount)
        assertEquals(1, byCircleView.timeline.unknownCount)
    }

    // ---- worksheet scope ----------------------------------------------------------------------

    @Test
    fun `the scope dialog edits a draft and only commits on request`() {
        val base = loaded()
        val opened = base.openScopeDialog(ScopeTarget.HEALTH)
        assertEquals(ScopeTarget.HEALTH, opened.scopeDialog!!.target)
        assertTrue("both sheets start selected", opened.scopeDialog.draft.isEmpty())

        val one = opened.toggleScopeSheet("xl/worksheets/sheet1.xml")
        assertEquals(setOf("xl/worksheets/sheet1.xml"), one.scopeDialog!!.draft)
        assertTrue("the report is unchanged until it is applied", one.healthScope.isAll)

        val applied = one.scopeCommitted()
        assertNull("the dialog closes on commit", applied.scopeDialog)
        assertEquals(setOf("xl/worksheets/sheet1.xml"), applied.healthScope.keys)

        // Cancelling keeps the committed scope.
        val reopened = applied.openScopeDialog(ScopeTarget.HEALTH).toggleScopeSheet("xl/worksheets/sheet2.xml")
        val cancelled = reopened.closeScopeDialog()
        assertEquals(setOf("xl/worksheets/sheet1.xml"), cancelled.healthScope.keys)
    }

    @Test
    fun `health and analytics never share a selection`() {
        val healthOnly = loaded()
            .openScopeDialog(ScopeTarget.HEALTH)
            .toggleScopeSheet("xl/worksheets/sheet1.xml")
            .scopeCommitted()
        assertFalse(healthOnly.healthScope.isAll)
        assertTrue("the analytics are untouched", healthOnly.analyticsScope.isAll)

        val analyticsOnly = healthOnly
            .openScopeDialog(ScopeTarget.ANALYTICS)
            .toggleScopeSheet("xl/worksheets/sheet2.xml")
            .scopeCommitted()
        assertEquals(setOf("xl/worksheets/sheet1.xml"), analyticsOnly.healthScope.keys)
        assertEquals(setOf("xl/worksheets/sheet2.xml"), analyticsOnly.analyticsScope.keys)
    }

    @Test
    fun `all worksheets is an empty selection and is reachable from the dialog`() {
        val narrowed = loaded()
            .openScopeDialog(ScopeTarget.ANALYTICS)
            .toggleScopeSheet("xl/worksheets/sheet1.xml")
            .scopeCommitted()
        val all = narrowed.openScopeDialog(ScopeTarget.ANALYTICS).scopeSelectAll().scopeCommitted()
        assertTrue(all.analyticsScope.isAll)
        assertEquals("All worksheets", all.scopeLabel(all.analyticsScope))
    }

    @Test
    fun `the scope summary names one sheet, counts several, and says all`() {
        val base = loaded()
        assertEquals("All worksheets", base.scopeLabel(SheetScope.ALL))
        assertEquals("LIST", base.scopeLabel(SheetScope(setOf("xl/worksheets/sheet1.xml"))))
        assertEquals(
            "2 of 2 worksheets",
            base.scopeLabel(SheetScope(setOf("xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml"))),
        )
    }

    @Test
    fun `a stored selection that the workbook no longer has falls back to all worksheets`() {
        val stale = loaded().copy(healthScope = SheetScope(setOf("xl/worksheets/sheet99.xml")))
        val reconciled = WorkbookCommentsUi(
            open = true,
            sha256 = CatalogFixtures.SHA,
            analysis = stale.analysis,
            healthScope = stale.healthScope,
        ).insightsLoaded(
            catalog = stale.catalog!!,
            health = stale.health!!,
            analytics = stale.analytics!!,
            publication = stale.publicationTimeline!!,
            acquisition = stale.acquisitionTimeline!!,
            imported = stale.importTimeline!!,
        )
        assertTrue(reconciled.healthScope.isAll)
    }

    // ---- the directory count ------------------------------------------------------------------

    @Test
    fun `the Insights count is always visible, and never a fake zero`() {
        assertEquals("2 errors", loaded().insightCountLabel)
        assertEquals("—", WorkbookCommentsUi().insightCountLabel)
        assertEquals("reading…", WorkbookCommentsUi().insightsStarted().insightCountLabel)
        assertEquals("not available", WorkbookCommentsUi().insightsFailed("nope").insightCountLabel)
        // A report with no confirmed problem still shows its count instead of hiding it.
        val clean = loaded().copy(health = loaded().health!!.copy(
            findings = loaded().health!!.findings.filter { it.severity != com.bookcovermatcher.core.catalog.HealthSeverity.ERROR },
            summary = loaded().health!!.summary.copy(errors = 0),
        ))
        assertEquals("0 errors", clean.insightCountLabel)
    }

    @Test
    fun `the scroll key of the timeline ignores unrelated state and follows its own`() {
        val base = loaded()
        assertNotEquals(base.timelineOrderKey, base.selectTimeline(TimelineMode.ACQUISITION).timelineOrderKey)
        assertNotEquals(base.timelineOrderKey, base.filterTimeline(TimelineFilter(query = "a")).timelineOrderKey)
        assertNotEquals(base.timelineOrderKey, base.copy(timelineAscending = true).timelineOrderKey)
        assertEquals(base.timelineOrderKey, base.insightsStarted().timelineOrderKey)
        assertEquals(base.timelineOrderKey, base.copy(healthSeverity = HealthSeverity.ERROR).timelineOrderKey)
        assertEquals(base.timelineOrderKey, base.copy(insightsError = "x").timelineOrderKey)
    }
}
