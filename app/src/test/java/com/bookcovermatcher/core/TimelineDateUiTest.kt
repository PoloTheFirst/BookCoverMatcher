package com.bookcovermatcher.core

import com.bookcovermatcher.CatalogueView
import com.bookcovermatcher.WorkbookCommentsUi
import com.bookcovermatcher.closeDateSetup
import com.bookcovermatcher.dateSettingsLoaded
import com.bookcovermatcher.dateSetupAssign
import com.bookcovermatcher.dateSetupAssignTo
import com.bookcovermatcher.dateSetupCleared
import com.bookcovermatcher.dateSetupCommitted
import com.bookcovermatcher.dateSetupOn
import com.bookcovermatcher.openDateSetup
import com.bookcovermatcher.showCatalogueView
import com.bookcovermatcher.core.catalog.CatalogFixtures
import com.bookcovermatcher.core.catalog.DateColumnState
import com.bookcovermatcher.core.catalog.SheetDateChoice
import com.bookcovermatcher.core.catalog.TimelineDateSettings
import com.bookcovermatcher.core.catalog.TimelineMode
import com.bookcovermatcher.core.catalog.WorkbookCatalogBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The date-column dialog as state: nothing is chosen, saved or discarded without these transitions. */
class TimelineDateUiTest {

    private fun catalog() = WorkbookCatalogBuilder.build(
        workbookSha256 = CatalogFixtures.SHA,
        sheets = CatalogFixtures.sheets("LIST", "DATA"),
        tables = listOf(
            CatalogFixtures.table(sheetName = "LIST", records = listOf(CatalogFixtures.catalogueRecord(1))),
            CatalogFixtures.table(sheetName = "DATA", records = listOf(CatalogFixtures.catalogueRecord(1))),
        ),
        images = emptyList(),
        profile = CatalogFixtures.profile(),
    )

    private fun state(settings: TimelineDateSettings = TimelineDateSettings(CatalogFixtures.SHA)) =
        WorkbookCommentsUi(
            open = true,
            workbookName = "doujin.xlsx",
            sha256 = CatalogFixtures.SHA,
            catalog = catalog(),
        ).dateSettingsLoaded(settings)

    @Test
    fun `the dialog opens with no column chosen even when a header looks like a date`() {
        val opened = state().openDateSetup()
        assertTrue(opened.dateSetupOpen)
        assertEquals("LIST", opened.dateSetupSheet)
        assertNull("発行日 must not be pre-selected", opened.dateSetupPublished)
        assertNull(opened.dateSetupAcquired)
        assertEquals(CatalogFixtures.DEFAULT_HEADERS.size, opened.dateSetupColumns.size)
    }

    @Test
    fun `the two slots take different columns and neither fills the other`() {
        val opened = state().openDateSetup()
            .dateSetupAssignTo(TimelineMode.PUBLICATION)
            .dateSetupAssign("発行日")
            .dateSetupAssignTo(TimelineMode.ACQUISITION)
            .dateSetupAssign("メモ")
        assertEquals("発行日", opened.dateSetupPublished)
        assertEquals("メモ", opened.dateSetupAcquired)
        assertEquals(DateColumnState.READY, opened.dateSetupReport(TimelineMode.PUBLICATION)!!.state)
        // メモ holds notes, not dates: the dialog says so instead of showing an empty timeline.
        assertEquals(DateColumnState.NO_READABLE_DATES, opened.dateSetupReport(TimelineMode.ACQUISITION)!!.state)
    }

    @Test
    fun `saving writes both slots and closes the dialog`() {
        val saved = state().openDateSetup()
            .dateSetupAssignTo(TimelineMode.PUBLICATION).dateSetupAssign("発行日")
            .dateSetupAssignTo(TimelineMode.ACQUISITION).dateSetupAssign("ID")
            .dateSetupCommitted()

        assertFalse(saved.dateSetupOpen)
        assertEquals(SheetDateChoice("発行日", "ID"), saved.dateSettings.choiceOf("LIST"))
        assertTrue(saved.dateSettings.isConfigured)
    }

    @Test
    fun `closing without saving leaves the stored choice exactly as it was`() {
        val stored = TimelineDateSettings(CatalogFixtures.SHA)
            .withChoice("LIST", SheetDateChoice("発行日", "メモ"))
        val closed = state(stored).openDateSetup().dateSetupAssign("ID").closeDateSetup()
        assertEquals("発行日", closed.dateSettings.choiceOf("LIST").publishedHeader)
        assertFalse(closed.dateSetupOpen)
        // The draft is gone with the dialog; reopening starts from what was saved.
        assertEquals("発行日", closed.openDateSetup().dateSetupPublished)
    }

    @Test
    fun `clearing both slots and saving removes the workbook's entry`() {
        val stored = TimelineDateSettings(CatalogFixtures.SHA).withChoice("LIST", SheetDateChoice("発行日", "メモ"))
        val cleared = state(stored).openDateSetup().dateSetupCleared().dateSetupCommitted()
        assertNull(cleared.dateSettings.choiceOf("LIST").publishedHeader)
        assertFalse(cleared.dateSettings.isConfigured)
    }

    @Test
    fun `each worksheet keeps its own choice`() {
        val first = state().openDateSetup()
            .dateSetupAssign("発行日")
            .dateSetupCommitted()
        val second = first.openDateSetup("DATA")
        assertEquals("DATA", second.dateSetupSheet)
        assertNull("DATA has no choice yet", second.dateSetupPublished)

        val both = second.dateSetupAssign("タイトル").dateSetupCommitted()
        assertEquals("発行日", both.dateSettings.choiceOf("LIST").publishedHeader)
        assertEquals("タイトル", both.dateSettings.choiceOf("DATA").publishedHeader)
    }

    @Test
    fun `switching worksheet in the dialog loads that worksheet's saved choice`() {
        val stored = TimelineDateSettings(CatalogFixtures.SHA)
            .withChoice("LIST", SheetDateChoice("発行日", null))
            .withChoice("DATA", SheetDateChoice(null, "メモ"))
        val switched = state(stored).openDateSetup().dateSetupOn("DATA")
        assertEquals("DATA", switched.dateSetupSheet)
        assertNull(switched.dateSetupPublished)
        assertEquals("メモ", switched.dateSetupAcquired)
    }

    @Test
    fun `the import timeline cannot be a target of the dialog`() {
        val opened = state().openDateSetup().dateSetupAssignTo(TimelineMode.IMPORT)
        assertEquals(TimelineMode.PUBLICATION, opened.dateSetupTarget)
    }

    @Test
    fun `the catalogue tab switches between cards and the whole worksheet table`() {
        val state = state()
        assertEquals(CatalogueView.CARDS, state.catalogueView)
        assertEquals(CatalogueView.TABLE, state.showCatalogueView(CatalogueView.TABLE).catalogueView)
    }
}

