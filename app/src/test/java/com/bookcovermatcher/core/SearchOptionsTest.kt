package com.bookcovermatcher.core

import com.bookcovermatcher.core.online.ProviderCatalog
import com.bookcovermatcher.core.online.SearchLimits
import com.bookcovermatcher.core.online.SearchRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user-configurable online result count.
 *
 * The offline workbook comparison is deliberately *not* configurable; the last test pins that so a
 * future change cannot quietly move the offline top-5 too.
 */
class SearchLimitsTest {

    @Test fun thePanelOffersFiveAscendingChoicesWithAFiveResultDefault() {
        assertEquals(listOf(5, 10, 20, 30, 50), SearchLimits.OPTIONS)
        assertEquals(5, SearchLimits.DEFAULT)
        assertEquals(50, SearchLimits.MAX)
    }

    @Test fun anUnknownOrCorruptValueFallsBackToTheDefault() {
        assertEquals(5, SearchLimits.sanitize(0))
        assertEquals(5, SearchLimits.sanitize(-20))
        assertEquals(5, SearchLimits.sanitize(7))
        assertEquals(5, SearchLimits.sanitize(51))
        assertEquals(5, SearchLimits.sanitize(Int.MAX_VALUE))
    }

    @Test fun everyOfferedValueIsAcceptedUnchanged() {
        SearchLimits.OPTIONS.forEach { assertEquals(it, SearchLimits.sanitize(it)) }
    }

    @Test fun aProviderIsNeverAskedForMoreThanItCanReturn() {
        // DLsite shows one page of 30, Google Books stops at 40, Melonbooks page holds 100.
        assertEquals(30, SearchLimits.forProvider(50, 30))
        assertEquals(40, SearchLimits.forProvider(50, 40))
        assertEquals(50, SearchLimits.forProvider(50, 50))
        assertEquals(5, SearchLimits.forProvider(5, 30))
        // A nonsensical provider cap still yields a usable request.
        assertEquals(1, SearchLimits.forProvider(20, 0))
    }

    @Test fun theOfflineWorkbookComparisonKeepsItsFixedFive() {
        assertEquals("offline matching must stay at five results", 5, Config.TOP_MATCHES)
        assertFalse("the online limit options must never be empty", SearchLimits.OPTIONS.isEmpty())
    }
}

/**
 * Platform selection: only automated providers are selectable, so a browser-only card can never
 * enable an in-app search.
 */
class SearchRoutingTest {

    @Test fun onlyTheInAppProvidersAreSelectedByDefault() {
        assertEquals(setOf("ol", "gb", "dl"), SearchRouting.DEFAULT_SELECTION)
        assertEquals(SearchRouting.DEFAULT_SELECTION, SearchRouting.sanitize(null))
    }

    @Test fun anExplicitlyEmptySelectionStaysEmpty() {
        assertTrue(SearchRouting.sanitize(emptyList()).isEmpty())
    }

    @Test fun unknownAndDifferentlyCasedIdsAreNormalised() {
        assertEquals(setOf("ol", "dl"), SearchRouting.sanitize(listOf("OL", " dl ", "nope")))
    }

    @Test fun togglingAddsThenRemovesOnePlatform() {
        val one = SearchRouting.toggle(setOf("ol"), "dl")
        assertEquals(setOf("ol", "dl"), one)

        val none = SearchRouting.toggle(one, "dl")
        assertEquals(setOf("ol"), none)
    }

    @Test fun togglingAnUnknownPlatformChangesNothing() {
        assertEquals(setOf("ol"), SearchRouting.toggle(setOf("ol"), "twitter"))
    }

    @Test fun melonbooksAndSurugaYaCanNeverBeSelected() {
        // Stored selections from an older build are cleaned on read, not trusted.
        assertEquals(emptySet<String>(), SearchRouting.sanitize(listOf("mb")))
        assertEquals(setOf("ol"), SearchRouting.sanitize(listOf("ol", "mb", "sy")))
        // A tap on either card cannot change the selection.
        assertEquals(setOf("ol"), SearchRouting.toggle(setOf("ol"), "mb"))
        assertEquals(setOf("ol"), SearchRouting.toggle(setOf("ol"), "sy"))
        assertEquals(emptySet<String>(), SearchRouting.toggle(emptySet(), "mb"))
        // And neither counts as an in-app provider.
        assertTrue(SearchRouting.automated(setOf("mb")).isEmpty())
        assertTrue(SearchRouting.automated(setOf("sy")).isEmpty())
        assertFalse(SearchRouting.hasAutomated(setOf("mb", "sy")))
    }

    @Test fun theNewProvidersAreShortcutsOnly() {
        assertEquals(setOf("ol"), SearchRouting.sanitize(listOf("ol", "dm", "eh", "di", "mal", "nh")))
        listOf("dm", "eh", "di", "mal", "nh").forEach { id ->
            assertEquals("$id must never be selectable", setOf("ol"), SearchRouting.toggle(setOf("ol"), id))
        }
        assertTrue(SearchRouting.automated(setOf("dm", "eh", "di", "mal", "nh")).isEmpty())
        assertFalse(SearchRouting.hasAutomated(setOf("di", "mal", "nh")))
        assertEquals(
            listOf("mb", "sy", "dm", "eh", "di", "mal", "nh"),
            SearchRouting.shortcuts().map { it.id },
        )
    }

    @Test fun onlySelectedPlatformsAreRoutedToInAppSearch() {
        assertEquals(listOf("dl"), SearchRouting.automated(setOf("dl")).map { it.id })
        assertEquals(listOf("ol", "gb"), SearchRouting.automated(setOf("gb", "ol")).map { it.id })
        assertEquals(listOf("ol", "gb", "dl"), SearchRouting.automated(setOf("dl", "ol", "gb")).map { it.id })
    }

    @Test fun aBrowserOnlyShortcutNeverEnablesASearch() {
        assertTrue(SearchRouting.automated(setOf("sy")).isEmpty())
        assertFalse("a shortcut must not enable an in-app search", SearchRouting.hasAutomated(setOf("sy", "mb")))
        assertTrue(SearchRouting.hasAutomated(setOf("ol")))
    }

    @Test fun theCacheKeyIgnoresSelectionOrder() {
        assertEquals(SearchRouting.key(setOf("ol", "dl")), SearchRouting.key(setOf("dl", "ol")))
        assertTrue(SearchRouting.key(setOf("ol")) != SearchRouting.key(setOf("ol", "gb")))
    }

    @Test fun theRegistryDeclaresTenPlatformsOfWhichThreeAreSelectable() {
        assertEquals(10, ProviderCatalog.ALL.size)
        assertEquals(3, ProviderCatalog.SELECTABLE.size)
        assertEquals(7, ProviderCatalog.BROWSER_ONLY.size)
    }
}