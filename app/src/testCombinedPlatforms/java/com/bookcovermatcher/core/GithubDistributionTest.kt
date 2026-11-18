// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core

import com.bookcovermatcher.core.online.*
import org.junit.Assert.*
import org.junit.Test

class GithubDistributionTest {
    @Test fun fullProviderInventoryAndStoredItemsRemainAvailable() {
        assertTrue(DistributionEdition.catalogueEnabled)
        assertEquals(setOf("ol", "gb", "dl", "mb", "sy", "tr", "nh", "eh", "dm", "mal", "di"), ProviderCatalog.ALL.map { it.id }.toSet())
        assertEquals(3, ProviderCatalog.AUTOMATED.size)
        assertEquals(8, SearchRouting.shortcuts().size)
        assertTrue(DistributionPolicy.showSavedItem("ONLINE_RESULT", "DLsite"))
        assertTrue(DistributionPolicy.showSavedItem("ONLINE_RESULT", null))
    }
}
