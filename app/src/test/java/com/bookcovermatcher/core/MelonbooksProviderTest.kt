// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core

import com.bookcovermatcher.core.online.MelonbooksResponseParser
import com.bookcovermatcher.core.online.OnlineAggregator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Melonbooks adapter against a **synthetic** search page
 * (`app/src/test/resources/fixtures/melonbooks_search.html`, three invented listings) and a synthetic
 * empty page. The markup keeps the structure the production parser reads — the `product_…` item, the
 * detail link, the title/price blocks, the person blocks and the `data-src` thumbnail — but every id,
 * title, circle, artist and price is invented by `tools/make-synthetic-fixtures.py`.
 */
class MelonbooksPageParserTest {

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/$name")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: error("fixture $name is missing")

    @Test fun aRealListingPageYieldsRealListings() {
        val items = MelonbooksResponseParser.parse(fixture("melonbooks_search.html"), 3)

        assertEquals(3, items.size)
        val first = items.first()
        assertEquals("3870001", first.id)
        assertEquals("mb", first.providerId)
        assertEquals(MelonbooksResponseParser.SOURCE_NAME, first.source)
        assertTrue(first.isMarketplace)
        assertEquals("見本タイトル はじまりの巻", first.title)
        assertEquals(listOf("見本サークル甲", "見本 著者一"), first.authors)
        assertEquals("¥484", first.priceText)
        assertEquals("¥484", first.metaLine)
        assertEquals(
            "https://www.melonbooks.co.jp/detail/detail.php?product_id=3870001",
            first.detailUrl,
        )
        assertTrue(first.coverUrl!!.startsWith("https://melonbooks.akamaized.net/"))
        assertTrue(first.coverUrl!!.contains("image=synthetic_3870001.jpg"))
        assertNull("the listing has no year and none is invented", first.publishedYear)
        assertTrue(first.publishers.isEmpty())
    }

    @Test fun htmlEntitiesInTitlesAreDecoded() {
        val third = MelonbooksResponseParser.parse(fixture("melonbooks_search.html"), 3)[2]

        assertTrue(third.title, third.title.contains("「引用」&「記号」"))
        assertFalse("the raw entity must not survive", third.title.contains("&amp;"))
    }

    @Test fun theLimitIsRespectedInListingOrder() {
        val items = MelonbooksResponseParser.parse(fixture("melonbooks_search.html"), 2)

        assertEquals(listOf("3870001", "3870002"), items.map { it.id })
    }

    @Test fun everyParsedListingHasItsOwnStableIdentity() {
        val keys = MelonbooksResponseParser.parse(fixture("melonbooks_search.html"), 3)
            .map { OnlineAggregator.itemKey(it) }

        assertEquals(3, keys.distinct().size)
        assertEquals("mb:3870001", keys.first())
    }

    @Test fun aGenuinelyEmptyResultPageIsEmptyNotAFailure() {
        assertTrue(MelonbooksResponseParser.parse(fixture("melonbooks_empty.html"), 5).isEmpty())
    }

    @Test(expected = MelonbooksResponseParser.ParseException::class)
    fun aPageThatIsNotAResultPageIsRejected() {
        MelonbooksResponseParser.parse("<html><body>メンテナンス中です</body></html>", 5)
    }

    @Test fun japaneseQueriesArePercentEncodedInTheQueryString() {
        val url = MelonbooksResponseParser.searchUrl("東方 紅魔郷")

        assertTrue(url.startsWith("https://www.melonbooks.co.jp/search/search.php?mode=search&name="))
        assertTrue(url, url.contains("%E6%9D%B1%E6%96%B9"))
        assertFalse(url.contains("東方"))
        assertFalse("spaces must be %20", url.contains("+"))
    }
}
