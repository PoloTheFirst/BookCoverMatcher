// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core

import com.bookcovermatcher.core.online.*
import org.junit.Assert.*
import org.junit.Test

class PlayDistributionTest {
    @Test fun restoredCatalogueSelectionsCannotEnableSearchOrShortcuts() {
        assertFalse(DistributionEdition.catalogueEnabled)
        assertTrue(ProviderCatalog.ALL.isEmpty())
        assertTrue(SearchRouting.DEFAULT_SELECTION.isEmpty())
        val restored = listOf("ol", "gb", "dl", "eh", "nh", "tr", "mb")
        assertTrue(SearchRouting.sanitize(restored).isEmpty())
        assertTrue(SearchRouting.automated(restored.toSet()).isEmpty())
        assertTrue(SearchRouting.shortcuts().isEmpty())
        assertTrue(ProviderCapabilities.ALL.isEmpty())
    }

    @Test fun savedWorkbookManualAndReverseItemsSurviveWhileOldCatalogueCardsAreUnavailable() {
        assertTrue(DistributionPolicy.showSavedItem("WORKBOOK_MATCH", null))
        assertTrue(DistributionPolicy.showSavedItem("MANUAL_BOOKMARK", null))
        for (provider in listOf("SauceNAO", "IQDB", "SauceNAO + IQDB")) {
            assertTrue(provider, DistributionPolicy.showSavedItem("ONLINE_RESULT", provider))
        }
        for (provider in listOf("DLsite", "Google Books", "Open Library", "SauceNAO-lookalike", "DLsite + SauceNAO")) {
            assertFalse(provider, DistributionPolicy.showSavedItem("ONLINE_RESULT", provider))
        }
        assertFalse(DistributionPolicy.showSavedItem("ONLINE_RESULT", null))
    }
}
