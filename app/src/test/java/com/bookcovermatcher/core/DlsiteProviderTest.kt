package com.bookcovermatcher.core

import com.bookcovermatcher.core.online.DlsiteResponseParser
import com.bookcovermatcher.core.online.HtmlText
import com.bookcovermatcher.core.online.OnlineAggregator
import com.bookcovermatcher.core.online.ProductSearchResult
import com.bookcovermatcher.core.online.ProviderAccess
import com.bookcovermatcher.core.online.ProviderCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DLsite adapter, exercised against a **captured** search page
 * (`app/src/test/resources/fixtures/dlsite_books_search.html`, three real works) and a captured
 * empty page. Nothing here is a mock: the fixture is the site's own markup.
 */
class DlsitePageParserTest {

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/fixtures/$name")
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: error("fixture $name is missing")

    @Test fun aRealListingPageYieldsRealWorks() {
        val items = DlsiteResponseParser.parse(fixture("dlsite_books_search.html"), 3)

        assertEquals(3, items.size)
        val first = items.first()
        assertEquals("RJ01730150", first.id)
        assertEquals("dl", first.providerId)
        assertEquals(DlsiteResponseParser.SOURCE_NAME, first.source)
        assertTrue(first.isMarketplace)
        assertTrue("title was '${first.title}'", first.title.contains("ふしだら幻想郷"))
        assertEquals(listOf("大アザラシ帝国"), first.authors)
        // The first price block on DLsite is the current price; the struck-through one is the old
        // price and is deliberately not the one shown.
        assertEquals("924円", first.priceText)
        assertEquals("924円", first.metaLine)
        assertTrue(first.detailUrl.startsWith("https://www.dlsite.com/"))
        assertTrue(first.detailUrl.contains("product_id/RJ01730150.html"))
        assertEquals(
            "https://img.dlsite.jp/resize/images2/work/doujin/RJ01731000/RJ01730150_img_main_240x240.jpg",
            first.coverUrl,
        )
        // A shop listing has no bibliographic fields, and none are invented for it.
        assertNull(first.publishedYear)
        assertNull(first.pageCount)
        assertTrue(first.publishers.isEmpty())
        assertTrue(first.languages.isEmpty())
    }

    @Test fun theLimitIsRespectedInListingOrder() {
        val items = DlsiteResponseParser.parse(fixture("dlsite_books_search.html"), 2)

        assertEquals(listOf("RJ01730150", "RJ01610330"), items.map { it.id })
    }

    @Test fun everyParsedWorkHasItsOwnStableIdentity() {
        val keys = DlsiteResponseParser.parse(fixture("dlsite_books_search.html"), 3)
            .map { OnlineAggregator.itemKey(it) }

        assertEquals(3, keys.distinct().size)
        assertEquals("dl:rj01730150", keys.first())
    }

    @Test fun aGenuinelyEmptyResultPageIsEmptyNotAFailure() {
        assertTrue(DlsiteResponseParser.parse(fixture("dlsite_books_empty.html"), 5).isEmpty())
    }

    @Test(expected = DlsiteResponseParser.ParseException::class)
    fun aPageThatIsNotAResultPageIsRejected() {
        // An age gate, a block page or a JS-only shell must never be reported as "no results".
        DlsiteResponseParser.parse("<html><body>ご利用のブラウザでは表示できません</body></html>", 5)
    }

    @Test fun multiWordQueriesUseTheShopsOwnFormEncoding() {
        // Measured 2026-10-23: keyword/Blue%20Archive -> HTTP 403, keyword/Blue+Archive -> HTTP 200.
        assertEquals(
            "https://www.dlsite.com/books/fsr/=/keyword/HelloWorld+Bobby",
            DlsiteResponseParser.searchUrl("HelloWorld Bobby"),
        )
        assertEquals(
            "https://www.dlsite.com/books/fsr/=/keyword/Blue+Archive",
            DlsiteResponseParser.searchUrl("  Blue Archive  "),
        )
        assertFalse("a space must never reach the URL raw", DlsiteResponseParser.searchUrl("Blue Archive").contains(" "))
    }

    @Test fun aSingleWordQueryIsStillJustTheWord() {
        assertEquals(
            "https://www.dlsite.com/books/fsr/=/keyword/BlueArchive",
            DlsiteResponseParser.searchUrl("BlueArchive"),
        )
        assertEquals(
            "https://www.dlsite.com/books/fsr/=/keyword/%E6%9D%B1%E6%96%B9",
            DlsiteResponseParser.searchUrl("東方"),
        )
        assertFalse(DlsiteResponseParser.searchUrl("東方").contains("東方"))
    }

    @Test fun consecutiveSpacesArePreservedNotCollapsed() {
        // The app trims the ends only; word boundaries inside the phrase are the user's.
        assertEquals(
            "https://www.dlsite.com/books/fsr/=/keyword/A++B",
            DlsiteResponseParser.searchUrl("A  B"),
        )
    }

    @Test fun unicodeWithSpacesKeepsBothWords() {
        val url = DlsiteResponseParser.searchUrl("東方 紅魔郷")

        assertTrue(url.startsWith("https://www.dlsite.com/books/fsr/=/keyword/"))
        assertEquals(
            "https://www.dlsite.com/books/fsr/=/keyword/%E6%9D%B1%E6%96%B9+%E7%B4%85%E9%AD%94%E9%83%B7",
            url,
        )
        assertFalse(url.contains("東方"))
    }

    @Test fun reservedCharactersCannotSplitOrTruncateTheQuery() {
        val cases = linkedMapOf(
            "A&B" to "A%26B",
            "C++" to "C%2B%2B",
            "100% Example" to "100%25+Example",
            "hash#tag" to "hash%23tag",
        )
        cases.forEach { (query, encoded) ->
            assertEquals(
                "https://www.dlsite.com/books/fsr/=/keyword/$encoded",
                DlsiteResponseParser.searchUrl(query),
            )
            val uri = java.net.URI(DlsiteResponseParser.searchUrl(query))
            assertNull("$query must not become a query parameter", uri.query)
            assertNull("$query must not become a fragment", uri.fragment)
        }
    }

    @Test fun theDecodedKeywordIsExactlyWhatTheUserTyped() {
        listOf(
            "HelloWorld Bobby",
            "Blue Archive",
            "東方 紅魔郷",
            "A&B",
            "C++",
            "100% Example",
            "hash#tag",
            "A  B",
        ).forEach { query ->
            val encoded = DlsiteResponseParser.searchUrl(query).substringAfterLast('/')
            assertEquals(query, java.net.URLDecoder.decode(encoded, "UTF-8"))
        }
    }

    @Test fun missingPriceOrCircleIsNotInvented() {
        val page = """
            <div id="search_result_list"></div>
            <li data-list_item_product_id="RJ01234567">
            <dd class="work_name"><a href="https://www.dlsite.com/books/work/=/product_id/RJ01234567.html">Only a title</a></dd>
            </li>
        """.trimIndent()

        val item = DlsiteResponseParser.parse(page, 5).single()

        assertNull("a price the page did not show must stay null", item.priceText)
        assertEquals("price not shown", item.metaLine)
        assertEquals("circle not shown", item.authorLine)
        assertNull(item.coverUrl)
    }
}

/** The small HTML helpers the DLsite adapter is built on. */
class HtmlTextTest {

    @Test fun entitiesAndTagsAreDecodedOnce() {
        assertEquals("A & B <tag> 東方", HtmlText.clean("A &amp; B &lt;tag&gt; <b>東方</b>"))
        assertEquals("'quoted'", HtmlText.clean("&#39;quoted&#39;"))
        // `&amp;lt;` is one level of escaping, not two.
        assertEquals("&lt;", HtmlText.clean("&amp;lt;"))
    }

    @Test fun tagsBecomeSpacesSoWordsDoNotRunTogether() {
        assertEquals("a b", HtmlText.clean("<b>a</b><b>b</b>"))
    }
}
