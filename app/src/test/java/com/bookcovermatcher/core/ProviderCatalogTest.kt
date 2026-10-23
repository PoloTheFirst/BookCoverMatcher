package com.bookcovermatcher.core

import com.bookcovermatcher.core.online.DlsiteResponseParser
import com.bookcovermatcher.core.online.OnlineAggregator
import com.bookcovermatcher.core.online.OpenLibraryResponseParser
import com.bookcovermatcher.core.online.ProductSearchResult
import com.bookcovermatcher.core.online.ProviderAccess
import com.bookcovermatcher.core.online.ProviderCatalog
import com.bookcovermatcher.core.online.ProviderLinks
import com.bookcovermatcher.core.online.ProviderSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The registry: ten providers, three of which the app queries in-app and seven of which are
 * browser-only shortcuts that can never be selected. Each browser-only entry carries the measured
 * reason it is not queried.
 */
class ProviderCatalogTest {

    @Test fun allTenProvidersAreDeclaredInDisplayOrder() {
        assertEquals(
            listOf("ol", "gb", "dl", "mb", "sy", "dm", "eh", "di", "mal", "nh"),
            ProviderCatalog.ALL.map { it.id },
        )
        assertEquals(
            listOf(
                "Open Library", "Google Books", "DLsite", "Melonbooks", "Suruga-ya",
                "Doujin no Mori", "E-Hentai", "Doujinshi.info", "MyAnimeList", "nhentai",
            ),
            ProviderCatalog.ALL.map { it.name },
        )
        assertEquals("every provider id must be unique", 10, ProviderCatalog.ALL.map { it.id }.toSet().size)
    }

    @Test fun onlyTheInAppProvidersAreAutomatedAndSelectable() {
        assertEquals(listOf("ol", "gb", "dl"), ProviderCatalog.AUTOMATED.map { it.id })
        assertEquals(listOf("ol", "gb", "dl"), ProviderCatalog.SELECTABLE.map { it.id })
        assertEquals(
            listOf("mb", "sy", "dm", "eh", "di", "mal", "nh"),
            ProviderCatalog.BROWSER_ONLY.map { it.id },
        )

        ProviderCatalog.SELECTABLE.forEach { assertTrue("${it.name} must be automated", it.selectable) }
        ProviderCatalog.BROWSER_ONLY.forEach { assertFalse("${it.name} must never be selectable", it.selectable) }
        assertEquals(ProviderAccess.AUTOMATED, ProviderCatalog.OPEN_LIBRARY.access)
        assertEquals(ProviderAccess.AUTOMATED, ProviderCatalog.GOOGLE_BOOKS.access)
        assertEquals(ProviderAccess.AUTOMATED, ProviderCatalog.DLSITE.access)
        assertEquals(ProviderAccess.BROWSER_ONLY, ProviderCatalog.MELONBOOKS.access)
        assertEquals(ProviderAccess.BROWSER_ONLY, ProviderCatalog.SURUGAYA.access)
        assertEquals(ProviderAccess.BROWSER_ONLY, ProviderCatalog.DOUJIN_NO_MORI.access)
        assertEquals(ProviderAccess.BROWSER_ONLY, ProviderCatalog.EHENTAI.access)
        assertEquals(ProviderAccess.BROWSER_ONLY, ProviderCatalog.DOUJINSHI_INFO.access)
        assertEquals(ProviderAccess.BROWSER_ONLY, ProviderCatalog.MYANIMELIST.access)
        assertEquals(ProviderAccess.BROWSER_ONLY, ProviderCatalog.NHENTAI.access)
    }

    @Test fun everyPlatformHasItsOwnSearchAndLandingLink() {
        assertEquals("every card must have a usable link", 10, ProviderCatalog.ALL.count { it.hasLink })
        ProviderCatalog.ALL.forEach { spec ->
            assertTrue("${spec.name} search", spec.searchTemplate!!.startsWith("https://"))
            assertTrue("${spec.name} landing", spec.landingUrl!!.startsWith("https://"))
            assertTrue("${spec.name} template keeps the placeholder", spec.searchTemplate!!.contains("{q}"))
            val url = spec.searchUrl("the hobbit")
            assertTrue("${spec.name}: $url", url.startsWith("https://"))
            val host = java.net.URI(url).host.lowercase()
            assertTrue("${spec.name}: $host", host == spec.host || host.endsWith(".${spec.host}"))
            // A blank query must not leave an empty search term in the URL.
            assertFalse("${spec.name} landing still has a placeholder", spec.searchUrl("").contains("{q}"))
        }
    }

    @Test fun doujinNoMoriUsesTheCorrectedDomainAndKeywordUrl() {
        val spec = ProviderCatalog.DOUJIN_NO_MORI

        assertEquals("Doujin no Mori", spec.name)
        assertEquals("doujinnomori.com", spec.host)
        assertEquals("dm", spec.id)
        assertTrue("the wrong domains must no longer be used", spec.searchTemplate!!.startsWith("https://doujinnomori.com/"))
        assertEquals(
            "https://doujinnomori.com/comics/free-search?keyword=%E6%9D%B1%E6%96%B9%20%E7%B4%85%E9%AD%94%E9%83%B7",
            spec.searchUrl("\u6771\u65B9 \u7D05\u9B54\u90F7"),
        )
        assertEquals("https://doujinnomori.com/", spec.searchUrl("   "))
    }

    @Test fun theThreeNewProvidersAreBrowserShortcutsWithEncodedQueries() {
        assertEquals(
            "https://www.doujinshi.info/works?query=the%20hobbit",
            ProviderCatalog.DOUJINSHI_INFO.searchUrl("the hobbit"),
        )
        assertEquals(
            "https://myanimelist.net/manga.php?q=the%20hobbit&cat=manga",
            ProviderCatalog.MYANIMELIST.searchUrl("the hobbit"),
        )
        assertEquals(
            "https://nhentai.net/search/?q=the%20hobbit",
            ProviderCatalog.NHENTAI.searchUrl("the hobbit"),
        )
        assertEquals("https://www.doujinshi.info/", ProviderCatalog.DOUJINSHI_INFO.searchUrl(""))
        assertEquals("https://myanimelist.net/manga.php", ProviderCatalog.MYANIMELIST.searchUrl(""))
        assertEquals("https://nhentai.net/", ProviderCatalog.NHENTAI.searchUrl(""))
    }

    @Test fun aProviderWithNoConfiguredPageIsRefusedInsteadOfBuildingABlankLink() {
        val unlinked = ProviderSpec(
            id = "xx",
            name = "Unlinked",
            host = "example.com",
            access = ProviderAccess.BROWSER_ONLY,
            note = "test fixture",
            searchTemplate = null,
            landingUrl = null,
        )

        assertFalse(unlinked.hasLink)
        try {
            unlinked.searchUrl("anything")
            fail("a provider with no verified page must not produce a link")
        } catch (e: ProviderLinks.UnsafeLinkException) {
            assertTrue(e.message!!.contains("Unlinked"))
        }
    }

    @Test fun idsHostsAndNotesArePresentForEveryProvider() {
        for (provider in ProviderCatalog.ALL) {
            assertTrue(provider.id.isNotBlank())
            assertTrue(provider.name.isNotBlank())
            assertTrue(provider.host.contains('.'))
            assertTrue(provider.note.isNotBlank())
            assertTrue(provider.searchUrl("x").startsWith("https://"))
            assertNotNull(ProviderCatalog.byId(provider.id))
        }
        assertNull(ProviderCatalog.byId("nope"))
    }
}

/**
 * Ranking and identity across providers. The rule that matters: a DLsite work and a library record
 * of a similar title are **two** results, while the same work returned twice is one.
 */
class MarketplaceAggregationTest {

    private fun shop(id: String, title: String) = ProductSearchResult(
        id = id,
        title = title,
        authors = listOf("circle"),
        publishedYear = null,
        publishers = emptyList(),
        pageCount = null,
        languages = emptyList(),
        coverUrl = null,
        detailUrl = "https://www.dlsite.com/books/work/=/product_id/$id.html",
        source = DlsiteResponseParser.SOURCE_NAME,
        providerId = DlsiteResponseParser.PROVIDER_ID,
        priceText = "924\u5186",
        isMarketplace = true,
    )

    private fun library(id: String, title: String) = ProductSearchResult(
        id = id,
        title = title,
        authors = listOf("Tolkien"),
        publishedYear = 1937,
        publishers = listOf("Allen & Unwin"),
        pageCount = 310,
        languages = listOf("en"),
        coverUrl = null,
        detailUrl = "https://openlibrary.org/works/$id",
        source = OpenLibraryResponseParser.SOURCE_NAME,
        providerId = OpenLibraryResponseParser.PROVIDER_ID,
    )

    @Test fun aShopListingAndALibraryRecordOfTheSameTitleStayTwoResults() {
        val merged = OnlineAggregator.merge(
            listOf(listOf(shop("RJ01730150", "The Hobbit")), listOf(library("OL27482W", "The Hobbit"))),
            limit = 10,
        )

        assertEquals(2, merged.size)
        assertEquals(setOf("dl", "ol"), merged.map { it.providerId }.toSet())
    }

    @Test fun shopListingsRankAheadOfCatalogueRecords() {
        assertEquals(OnlineAggregator.RANK_MARKETPLACE, OnlineAggregator.rankOf(shop("RJ01730150", "A")))
        assertEquals(OnlineAggregator.RANK_CATALOGUE, OnlineAggregator.rankOf(library("OL27482W", "A")))
        assertTrue(
            OnlineAggregator.rankOf(shop("RJ01730150", "A")) <
                OnlineAggregator.rankOf(library("OL27482W", "A")),
        )
    }

    @Test fun theSameWorkFromTheSameProviderIsDeduplicated() {
        val merged = OnlineAggregator.merge(
            listOf(listOf(shop("RJ01730150", "A")), listOf(shop("RJ01730150", "A"))),
            limit = 10,
        )

        assertEquals(1, merged.size)
    }

    @Test fun aBookmarkedShopListingIsPromotedFirst() {
        val promoted = OnlineAggregator.itemKey(shop("RJ01730150", "A"))
        val merged = OnlineAggregator.merge(
            listOf(listOf(shop("RJ01730150", "A"), shop("RJ01610330", "B"))),
            promotedKeys = setOf(promoted),
            limit = 10,
        )

        assertEquals("RJ01730150", merged.first().id)
        assertEquals(2, merged.size)
    }

    @Test fun aMarketplaceResultCarriesItsOwnPriceAndNoInventedFields() {
        val item = shop("RJ01730150", "A")

        assertEquals("924\u5186", item.metaLine)
        assertNull(item.publishedYear)
        assertTrue(item.publishers.isEmpty())
    }
}