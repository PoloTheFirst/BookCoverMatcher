// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core

import com.bookcovermatcher.core.online.reverse.CoverImageExtractor
import com.bookcovermatcher.core.online.reverse.DiscoveryImageSource
import com.bookcovermatcher.core.online.reverse.SafeImageUrl
import com.bookcovermatcher.online.DiscoveryWebFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.5.0-dev.4 — how a SauceNAO (or IQDB) result gets a picture, and what it is allowed to fetch.
 *
 * Two rules are pinned here, both of them security-relevant:
 *
 *  1. only **declared** metadata is read (`og:image`, Twitter cards, `image_src`), in priority order — never "the
 *     first `<img>` on the page";
 *  2. every URL — the page, the image, and every redirect hop — must pass [SafeImageUrl], so a provider result can
 *     never point the app at loopback, a private network or a cloud metadata endpoint.
 *
 * No network is used: markup is inline, and the URL/redirect rules are pure functions.
 */
class DiscoveryCoverImageTest {

    private val dlsite = "https://www.dlsite.com/maniax/work/=/product_id/RJ01234567.html"

    @Test
    fun `open graph image is used when it is the only metadata`() {
        val html = """
            <html><head>
              <meta property="og:image" content="https://img.example.com/cover.jpg">
            </head><body></body></html>
        """.trimIndent()
        val found = CoverImageExtractor.fromHtml(html, dlsite)
        assertEquals("https://img.example.com/cover.jpg", found?.url)
        assertEquals(DiscoveryImageSource.WEBPAGE_OPEN_GRAPH, found?.source)
    }

    @Test
    fun `secure open graph image wins over the plain one`() {
        val html = """
            <meta property="og:image" content="https://img.example.com/plain.jpg">
            <meta property="og:image:secure_url" content="https://img.example.com/secure.jpg">
        """.trimIndent()
        assertEquals("https://img.example.com/secure.jpg", CoverImageExtractor.fromHtml(html, dlsite)?.url)
    }

    @Test
    fun `twitter card is used when open graph is missing`() {
        val html = """<meta name="twitter:image" content="https://img.example.com/twitter.png">"""
        val found = CoverImageExtractor.fromHtml(html, dlsite)
        assertEquals("https://img.example.com/twitter.png", found?.url)
        assertEquals(DiscoveryImageSource.WEBPAGE_TWITTER, found?.source)
    }

    @Test
    fun `twitter image src is the twitter fallback`() {
        val html = """<meta name="twitter:image:src" content="https://img.example.com/tw-src.png">"""
        assertEquals("https://img.example.com/tw-src.png", CoverImageExtractor.fromHtml(html, dlsite)?.url)
    }

    @Test
    fun `link image_src is the last resort`() {
        val html = """<link rel="image_src" href="https://img.example.com/linked.jpg">"""
        val found = CoverImageExtractor.fromHtml(html, dlsite)
        assertEquals("https://img.example.com/linked.jpg", found?.url)
        assertEquals(DiscoveryImageSource.WEBPAGE_IMAGE_SRC, found?.source)
    }

    @Test
    fun `a page with no declared image yields nothing`() {
        val html = """
            <html><head><title>Work</title></head>
            <body><img src="https://img.example.com/first-img-on-page.jpg"></body></html>
        """.trimIndent()
        assertNull(CoverImageExtractor.fromHtml(html, dlsite))
    }

    @Test
    fun `empty and blank pages yield nothing`() {
        assertNull(CoverImageExtractor.fromHtml("", dlsite))
        assertNull(CoverImageExtractor.fromHtml("   ", dlsite))
    }

    @Test
    fun `attribute order and quoting do not matter`() {
        val single = "<meta content='https://img.example.com/a.jpg' property='og:image'>"
        val upper = """<META PROPERTY="og:image" CONTENT="https://img.example.com/b.jpg">"""
        assertEquals("https://img.example.com/a.jpg", CoverImageExtractor.fromHtml(single, dlsite)?.url)
        assertEquals("https://img.example.com/b.jpg", CoverImageExtractor.fromHtml(upper, dlsite)?.url)
    }

    @Test
    fun `a relative image url resolves against the page`() {
        val html = """<meta property="og:image" content="/img/cover_l.jpg">"""
        assertEquals(
            "https://www.dlsite.com/img/cover_l.jpg",
            CoverImageExtractor.fromHtml(html, dlsite)?.url,
        )
    }

    @Test
    fun `a protocol relative image url becomes https`() {
        val html = """<meta property="og:image" content="//img.example.com/cover.jpg">"""
        assertEquals("https://img.example.com/cover.jpg", CoverImageExtractor.fromHtml(html, dlsite)?.url)
    }

    @Test
    fun `cleartext and non http schemes are refused`() {
        val http = """<meta property="og:image" content="http://img.example.com/cover.jpg">"""
        val data = """<meta property="og:image" content="data:image/png;base64,AAAA">"""
        val script = """<meta property="og:image" content="javascript:alert(1)">"""
        assertNull(CoverImageExtractor.fromHtml(http, dlsite))
        assertNull(CoverImageExtractor.fromHtml(data, dlsite))
        assertNull(CoverImageExtractor.fromHtml(script, dlsite))
    }

    @Test
    fun `private loopback and metadata hosts are refused`() {
        val blocked = listOf(
            "http://127.0.0.1/a.jpg",
            "https://127.0.0.1/a.jpg",
            "https://10.1.2.3/a.jpg",
            "https://172.16.4.4/a.jpg",
            "https://192.168.0.10/a.jpg",
            "https://169.254.169.254/latest/meta-data/",
            "https://localhost/a.jpg",
            "https://nas.local/a.jpg",
            "https://router/a.jpg",
            "https://[::1]/a.jpg",
            "https://[fe80::1]/a.jpg",
            "https://[::ffff:10.0.0.1]/a.jpg",
        )
        blocked.forEach { url ->
            assertFalse("$url must not be fetchable", SafeImageUrl.isFetchable(url))
        }
    }

    @Test
    fun `public https urls are fetchable`() {
        assertTrue(SafeImageUrl.isFetchable("https://img.example.com/cover.jpg"))
        assertTrue(SafeImageUrl.isFetchable("https://www.dlsite.com/maniax/work/=/product_id/RJ01234567.html"))
        assertTrue(SafeImageUrl.isFetchable("https://s2.example.co.jp/a/b.png?x=1&y=2"))
        assertEquals("img.example.com", SafeImageUrl.host("https://img.example.com/cover.jpg"))
    }

    @Test
    fun `credentials odd ports and blank values are refused`() {
        assertFalse(SafeImageUrl.isFetchable("https://user:secret@img.example.com/a.jpg"))
        assertFalse(SafeImageUrl.isFetchable("https://img.example.com:8080/a.jpg"))
        assertFalse(SafeImageUrl.isFetchable(" "))
        assertFalse(SafeImageUrl.isFetchable(null))
        assertFalse(SafeImageUrl.isFetchable("https://img.example.com/a.jpg with a space"))
    }

    @Test
    fun `redirect hops are resolved against the hop that sent them`() {
        assertEquals(
            "https://www.example.com/img/cover.jpg",
            DiscoveryWebFetcher.resolveRedirect("/img/cover.jpg", "https://www.example.com/work/123"),
        )
        assertEquals(
            "https://cdn.example.com/img/cover.jpg",
            DiscoveryWebFetcher.resolveRedirect("/img/cover.jpg", "https://cdn.example.com/work/123"),
        )
        assertEquals(
            "https://other.example.com/cover.jpg",
            DiscoveryWebFetcher.resolveRedirect(
                "https://other.example.com/cover.jpg",
                "https://www.example.com/work/123",
            ),
        )
        assertEquals("", DiscoveryWebFetcher.resolveRedirect("   ", dlsite))
    }

    @Test
    fun `a redirect to a private address is refused by the guard`() {
        val hop = DiscoveryWebFetcher.resolveRedirect("http://192.168.0.1/router.jpg", dlsite)
        assertFalse(SafeImageUrl.isFetchable(hop))
    }

    @Test
    fun `only decodable image content types are accepted`() {
        assertTrue("image/jpeg" in DiscoveryWebFetcher.SUPPORTED_IMAGE_TYPES)
        assertTrue("image/png" in DiscoveryWebFetcher.SUPPORTED_IMAGE_TYPES)
        assertFalse("text/html" in DiscoveryWebFetcher.SUPPORTED_IMAGE_TYPES)
        assertFalse("application/octet-stream" in DiscoveryWebFetcher.SUPPORTED_IMAGE_TYPES)
    }
}
