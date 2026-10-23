package com.bookcovermatcher.core

import com.bookcovermatcher.core.online.MelonbooksResponseParser
import com.bookcovermatcher.core.online.OnlineAggregator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Melonbooks adapter against a **captured** search page
 * (`app/src/test/resources/fixtures/melonbooks_search.html`, three real listings) and a captured
 * empty page. The fixture is the shop's own markup, not a hand-written mock.
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
        assertEquals("3897050", first.id)
        assertEquals("mb", first.providerId)
        assertEquals(MelonbooksResponseParser.SOURCE_NAME, first.source)
        assertTrue(first.isMarketplace)
        assertEquals("エピローグはどうかあなたと", first.title)
        assertEquals(listOf("甘毒フロランタン", "らずべりぃ。"), first.authors)
        assertEquals("¥484", first.priceText)
        assertEquals("¥484", first.metaLine)
        assertEquals(
            "https://www.melonbooks.co.jp/detail/detail.php?product_id=3897050",
            first.detailUrl,
        )
        assertTrue(first.coverUrl!!.startsWith("https://melonbooks.akamaized.net/"))
        assertTrue(first.coverUrl!!.contains("image=212001618313.jpg"))
        assertNull("the listing has no year and none is invented", first.publishedYear)
        assertTrue(first.publishers.isEmpty())
    }

    @Test fun htmlEntitiesInTitlesAreDecoded() {
        val third = MelonbooksResponseParser.parse(fixture("melonbooks_search.html"), 3)[2]

        assertTrue(third.title, third.title.contains("「レミリア」&「フランドール」"))
        assertFalse("the raw entity must not survive", third.title.contains("&amp;"))
    }

    @Test fun theLimitIsRespectedInListingOrder() {
        val items = MelonbooksResponseParser.parse(fixture("melonbooks_search.html"), 2)

        assertEquals(listOf("3897050", "3797208"), items.map { it.id })
    }

    @Test fun everyParsedListingHasItsOwnStableIdentity() {
        val keys = MelonbooksResponseParser.parse(fixture("melonbooks_search.html"), 3)
            .map { OnlineAggregator.itemKey(it) }

        assertEquals(3, keys.distinct().size)
        assertEquals("mb:3897050", keys.first())
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
