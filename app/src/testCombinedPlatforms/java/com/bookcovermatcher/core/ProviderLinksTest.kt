// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core

import com.bookcovermatcher.core.online.OnlineAggregator
import com.bookcovermatcher.core.online.ProductSearchResult
import com.bookcovermatcher.core.online.ProviderCatalog
import com.bookcovermatcher.core.online.ProviderLinks
import com.bookcovermatcher.core.online.QueryForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every platform's browser link: built from the user's own query, encoded as UTF-8, validated to stay
 * on https and on the platform's own host.
 */
class ProviderLinksTest {

    private fun unsafe(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected UnsafeLinkException")
        } catch (expected: ProviderLinks.UnsafeLinkException) {
            // expected
        }
    }

    @Test fun everyPlatformBuildsAnHttpsLinkOnItsOwnHost() {
        ProviderCatalog.ALL.forEach { spec ->
            val url = spec.searchUrl("the hobbit")
            assertTrue("${spec.name}: $url", url.startsWith("https://"))
            val host = java.net.URI(url).host.lowercase()
            assertTrue("${spec.name}: $host", host == spec.host || host.endsWith(".${spec.host}"))
            assertFalse("${spec.name} leaves a raw space", url.contains(" "))
        }
    }

    @Test fun japaneseQueriesArePercentEncodedNotRaw() {
        val url = requireNotNull(ProviderCatalog.byId("mb")).searchUrl("\u6771\u65B9 \u7D05\u9B54\u90F7")

        assertTrue(url, url.contains("%E6%9D%B1%E6%96%B9"))
        assertTrue("spaces must be %20", url.contains("%20"))
        assertFalse("a path/query mix must not use +", url.contains("+"))
    }

    @Test fun doujinNoMoriSearchUrlKeepsTheKeywordParameter() {
        val url = requireNotNull(ProviderCatalog.byId("dm")).searchUrl("\u6771\u65B9")

        assertEquals(
            "https://doujinnomori.com/comics/free-search?keyword=%E6%9D%B1%E6%96%B9",
            url,
        )
        assertTrue(url.startsWith("https://doujinnomori.com/comics/free-search?keyword="))
        assertFalse("the wrong domain must not appear anywhere", url.contains("doujinmori.com"))
    }

    @Test fun theDlsiteCardLinkUsesTheSameFormEncodingAsTheAdapter() {
        // The card's Open button must not 403 either: same route, same encoding as the adapter.
        assertEquals(
            "https://www.dlsite.com/books/fsr/=/keyword/Blue+Archive",
            requireNotNull(ProviderCatalog.byId("dl")).searchUrl("Blue Archive"),
        )
        assertEquals(QueryForm.PLUS, requireNotNull(ProviderCatalog.byId("dl")).queryForm)
        // Every other platform keeps %20 in its query string.
        assertEquals("Blue%20Archive", ProviderLinks.encode("Blue Archive"))
        assertTrue(requireNotNull(ProviderCatalog.byId("mb")).searchUrl("Blue Archive").contains("Blue%20Archive"))
        assertEquals("Blue%20Archive", ProviderLinks.encode("Blue Archive", QueryForm.PERCENT_20))
        assertEquals("Blue+Archive", ProviderLinks.encode("Blue Archive", QueryForm.PLUS))
    }

    @Test fun theTwoQueryFormsNeverConfuseASpaceWithALiteralPlus() {
        assertEquals("C%2B%2B", ProviderLinks.encode("C++", QueryForm.PLUS))
        assertEquals("C%2B%2B", ProviderLinks.encode("C++", QueryForm.PERCENT_20))
        assertEquals("A+B", ProviderLinks.encode("A B", QueryForm.PLUS))
        assertEquals("A%20B", ProviderLinks.encode("A B", QueryForm.PERCENT_20))
    }

    @Test fun aBlankQueryOpensThePlatformLandingPage() {
        assertEquals("https://openlibrary.org/search", requireNotNull(ProviderCatalog.byId("ol")).searchUrl("   "))
        assertEquals("https://books.google.com/", requireNotNull(ProviderCatalog.byId("gb")).searchUrl(""))
        assertEquals("https://www.melonbooks.co.jp/", requireNotNull(ProviderCatalog.byId("mb")).searchUrl(""))
        assertEquals("https://www.suruga-ya.jp/", requireNotNull(ProviderCatalog.byId("sy")).searchUrl(""))
        assertEquals("https://www.dlsite.com/books/", requireNotNull(ProviderCatalog.byId("dl")).searchUrl(""))
        assertEquals("https://e-hentai.org/", requireNotNull(ProviderCatalog.byId("eh")).searchUrl(""))
        assertEquals("https://doujinnomori.com/", requireNotNull(ProviderCatalog.byId("dm")).searchUrl(""))
        assertEquals("https://www.doujinshi.info/", requireNotNull(ProviderCatalog.byId("di")).searchUrl(""))
        assertEquals("https://myanimelist.net/manga.php", requireNotNull(ProviderCatalog.byId("mal")).searchUrl(""))
        assertEquals("https://nhentai.net/", requireNotNull(ProviderCatalog.byId("nh")).searchUrl(""))
        assertEquals("https://ec.toranoana.jp/tora_r/ec/", requireNotNull(ProviderCatalog.byId("tr")).searchUrl("   "))
    }

    @Test fun toranoanaSearchUrlKeepsTheSearchWordParameterAndEncodesTheQueryOnce() {
        // Browser-only card: the app builds the link, the user's browser performs the search.
        assertEquals(
            "https://ec.toranoana.jp/tora_r/ec/app/catalog/list?searchWord=Blue%20Archive",
            requireNotNull(ProviderCatalog.byId("tr")).searchUrl("Blue Archive"),
        )
        assertEquals(QueryForm.PERCENT_20, requireNotNull(ProviderCatalog.byId("tr")).queryForm)

        val japanese = requireNotNull(ProviderCatalog.byId("tr")).searchUrl("\u6771\u65B9 \u7D05\u9B54\u90F7")
        assertEquals(
            "https://ec.toranoana.jp/tora_r/ec/app/catalog/list?searchWord=%E6%9D%B1%E6%96%B9%20%E7%B4%85%E9%AD%94%E9%83%B7",
            japanese,
        )
        assertEquals("\u6771\u65B9 \u7D05\u9B54\u90F7", searchWord(japanese))

        // Reserved characters are escaped exactly once: the parameter is the encoder's own output
        // (not a second encoding of it) and decodes back to exactly what the user typed.
        listOf("A&B", "C++", "100%", "hash#tag", "a=b?c", "Blue  Archive").forEach { query ->
            val parameter = parameterOf(requireNotNull(ProviderCatalog.byId("tr")).searchUrl(query))
            assertEquals(query, ProviderLinks.encode(query), parameter)
            assertEquals(query, searchWord(requireNotNull(ProviderCatalog.byId("tr")).searchUrl(query)))
        }
        assertEquals("A%26B", parameterOf(requireNotNull(ProviderCatalog.byId("tr")).searchUrl("A&B")))
        assertEquals("C%2B%2B", parameterOf(requireNotNull(ProviderCatalog.byId("tr")).searchUrl("C++")))
        assertEquals("100%25", parameterOf(requireNotNull(ProviderCatalog.byId("tr")).searchUrl("100%")))
        assertEquals("hash%23tag", parameterOf(requireNotNull(ProviderCatalog.byId("tr")).searchUrl("hash#tag")))
    }

    /** The still-encoded `searchWord` value of a Toranoana link. */
    private fun parameterOf(url: String): String =
        java.net.URI(url).rawQuery.substringAfter("searchWord=")

    /** The decoded `searchWord` value of a Toranoana link — the text the browser will submit. */
    private fun searchWord(url: String): String =
        java.net.URLDecoder.decode(parameterOf(url), "UTF-8")

    @Test fun aLinkOnTheWrongHostSchemeOrPortIsRefused() {
        val spec = requireNotNull(ProviderCatalog.byId("mb"))

        unsafe { ProviderLinks.validate("http://www.melonbooks.co.jp/", spec) }
        unsafe { ProviderLinks.validate("https://evil.example.com/search", spec) }
        unsafe { ProviderLinks.validate("https://melonbooks.co.jp.evil.example/", spec) }
        unsafe { ProviderLinks.validate("https://user:pass@www.melonbooks.co.jp/", spec) }
        unsafe { ProviderLinks.validate("https://www.melonbooks.co.jp:8443/", spec) }
    }

    @Test fun aSubdomainOfTheDeclaredHostIsAllowed() {
        assertEquals(
            "https://img.melonbooks.co.jp/x",
            ProviderLinks.validate("https://img.melonbooks.co.jp/x", requireNotNull(ProviderCatalog.byId("mb"))),
        )
    }
}

/** The configured maximum is honoured after deduplication, never before. */
class OnlineResultLimitTest {

    private fun result(provider: String, id: String) = ProductSearchResult(
        id = id,
        title = "Title $id",
        authors = emptyList(),
        publishedYear = null,
        publishers = emptyList(),
        pageCount = null,
        languages = emptyList(),
        coverUrl = null,
        detailUrl = "https://example.org/$provider/$id",
        source = provider,
        providerId = provider,
    )

    @Test fun theLimitCapsTheMergedList() {
        val batch = (1..30).map { result("ol", "OL$it") }

        assertEquals(5, OnlineAggregator.merge(listOf(batch), limit = 5).size)
        assertEquals(20, OnlineAggregator.merge(listOf(batch), limit = 20).size)
        assertEquals(30, OnlineAggregator.merge(listOf(batch), limit = 50).size)
    }

    @Test fun duplicatesAreRemovedBeforeTheLimitIsApplied() {
        val unique = (1..12).map { result("ol", "OL$it") }
        val duplicates = (1..8).map { result("ol", "OL$it") }

        val merged = OnlineAggregator.merge(listOf(unique, duplicates), limit = 10)

        assertEquals(10, merged.size)
        assertEquals(10, merged.map { OnlineAggregator.itemKey(it) }.distinct().size)
    }

    @Test fun aLimitOfZeroAsksForNothing() {
        val batch = (1..5).map { result("ol", "OL$it") }
        assertTrue(OnlineAggregator.merge(listOf(batch), limit = 0).isEmpty())
    }
}