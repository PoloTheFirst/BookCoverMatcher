package com.bookcovermatcher.core

import com.bookcovermatcher.core.online.DlsiteResponseParser
import com.bookcovermatcher.core.online.OnlineAggregator
import com.bookcovermatcher.core.online.OpenLibraryResponseParser
import com.bookcovermatcher.core.online.ProductSearchResult
import com.bookcovermatcher.core.online.ProviderAccess
import com.bookcovermatcher.core.online.ProviderCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The provider list itself: five providers, three of which the app queries and two of which it
 * deliberately does not. These are the assertions the README's provider table must keep matching.
 */
class ProviderCatalogTest {

    @Test fun allFiveProvidersAreDeclared() {
        assertEquals(listOf("ol", "gb", "mb", "sy", "dl"), ProviderCatalog.ALL.map { it.id })
        assertEquals(
            listOf("Open Library", "Google Books", "Melonbooks", "Suruga-ya", "DLsite"),
            ProviderCatalog.ALL.map { it.name },
        )
    }

    @Test fun onlyTheThreeWorkingProvidersAreAutomated() {
        assertEquals(listOf("ol", "gb", "dl"), ProviderCatalog.AUTOMATED.map { it.id })
        assertEquals(listOf("mb", "sy"), ProviderCatalog.BROWSER_ONLY.map { it.id })
        assertEquals(ProviderAccess.BROWSER_ONLY, ProviderCatalog.MELONBOOKS.access)
        assertEquals(ProviderAccess.BROWSER_ONLY, ProviderCatalog.SURUGAYA.access)
        assertEquals(ProviderAccess.AUTOMATED, ProviderCatalog.DLSITE.access)
    }

    @Test fun aManualProviderStillGetsAUsableSearchLink() {
        val melonbooks = ProviderCatalog.MELONBOOKS.searchUrl("東方 紅魔郷")

        assertTrue(melonbooks.startsWith("https://www.melonbooks.co.jp/search/search.php?mode=search&name="))
        assertTrue(melonbooks.contains("%E6%9D%B1%E6%96%B9"))
        assertFalse("the query must be encoded, never sent raw", melonbooks.contains("東方"))
        assertFalse(melonbooks.contains(" "))

        assertEquals(
            "https://www.suruga-ya.jp/search?category=&search_word=%E6%9D%B1%E6%96%B9",
            ProviderCatalog.SURUGAYA.searchUrl("東方"),
        )
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
        priceText = "924円",
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

        assertEquals("924円", item.metaLine)
        assertNull(item.publishedYear)
        assertTrue(item.publishers.isEmpty())
    }
}
