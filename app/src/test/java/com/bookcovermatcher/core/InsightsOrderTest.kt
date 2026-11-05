package com.bookcovermatcher.core

import com.bookcovermatcher.InsightsTab
import com.bookcovermatcher.WorkbookCommentsUi
import com.bookcovermatcher.showInsights
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The order of the Insights directory.
 *
 * Health and Analytics both analyse the collection and produce findings and figures; Gallery and Timeline
 * both browse individual records. The two pairs sit together in that order, and Fields — the mapping screen
 * that configures the data the others read — stays last.
 */
class InsightsOrderTest {

    @Test
    fun `the directory lists its functions in the agreed order`() {
        assertEquals(
            listOf("HEALTH", "ANALYTICS", "GALLERY", "TIMELINE", "FIELDS"),
            InsightsTab.entries.map { it.name },
        )
        assertEquals(
            listOf("Health", "Analytics", "Gallery", "Timeline", "Fields"),
            InsightsTab.entries.map { it.label },
        )
    }

    @Test
    fun `each function keeps its own tab, and the default is Health`() {
        val base = WorkbookCommentsUi()
        assertEquals(InsightsTab.HEALTH, base.insightsTab)
        for (tab in InsightsTab.entries) {
            assertEquals(tab, base.showInsights(tab).insightsTab)
            assertEquals(tab.label, base.showInsights(tab).insightsTab.label)
        }
    }

    @Test
    fun `the order is the list order, not the enum ordinal of the screens`() {
        // Guards the swap itself: Analytics must come before Gallery in the directory.
        val labels = InsightsTab.entries.map { it.label }
        assertEquals(2, labels.indexOf("Analytics") + 1)
        assertEquals(3, labels.indexOf("Gallery") + 1)
    }
}
