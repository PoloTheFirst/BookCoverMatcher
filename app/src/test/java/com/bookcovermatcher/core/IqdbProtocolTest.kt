package com.bookcovermatcher.core

import com.bookcovermatcher.core.online.reverse.IqdbProtocol
import com.bookcovermatcher.core.online.reverse.IqdbResultParser
import com.bookcovermatcher.core.online.reverse.ReverseImageProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.5.0-dev.2 — IQDB: what this build verified, and what it therefore refuses to do.
 *
 * The assertions encode the decision, not a wish: no API, robots-disallowed search routes, no automation, and a
 * parser that only ever normalises what the **user** brought back.
 */
class IqdbProtocolTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `automation is off, and the reason names both facts`() {
        assertFalse(IqdbProtocol.AUTOMATION_SUPPORTED)
        val reason = IqdbProtocol.manualOnlyReason()
        assertTrue(reason.contains("no documented public API"))
        assertTrue(reason.contains("robots.txt"))
        // The provider is still first-class: it keeps its documented public page.
        assertEquals("https://iqdb.org/", IqdbProtocol.SEARCH_PAGE)
        assertEquals(ReverseImageProviders.IQDB_PRIORITY, ReverseImageProviders.priorityOf("iqdb"))
    }

    @Test
    fun `every search route its robots file disallows is known to the app`() {
        assertTrue(IqdbProtocol.isDisallowedPath("/multi-search?url=x"))
        assertTrue(IqdbProtocol.isDisallowedPath("/db-search"))
        assertTrue(IqdbProtocol.isDisallowedPath("/kc-search"))
        assertTrue(IqdbProtocol.isDisallowedPath("/mi-search"))
        assertTrue(IqdbProtocol.isDisallowedPath("/index"))
        assertTrue(IqdbProtocol.isDisallowedPath("/?url=https://example.test/a.jpg"))
        assertFalse("the public search page itself is not disallowed", IqdbProtocol.isDisallowedPath("/"))
    }

    @Test
    fun `a saved result page is normalised with its own similarity and service`() {
        val page = """
            <html><body>
              <div class="pages">
                <div>
                  <table>
                    <tr>
                      <td><a href="//img.example.test/thumb.jpg"><img src="//img.example.test/thumb.jpg"></a></td>
                      <td>
                        <div>Best match</div>
                        <div><a href="//danbooru.example.test/posts/123">Danbooru</a> 123</div>
                        <div>92% similarity</div>
                      </td>
                    </tr>
                  </table>
                </div>
                <div>
                  <table>
                    <tr>
                      <td><a href="https://other.example.test/item/9"><img src="https://other.example.test/i.jpg"></a></td>
                      <td><div><a href="https://other.example.test/item/9">Gelbooru</a></div><div>77% similarity</div></td>
                    </tr>
                  </table>
                </div>
                <div><table><tr><td>no links here</td></tr></table></div>
              </div>
            </body></html>
        """.trimIndent()

        val hits = IqdbResultParser.parsePage(page, now)
        assertEquals(2, hits.size)
        assertEquals("https://danbooru.example.test/posts/123", hits[0].sourceUrl)
        assertEquals("https://img.example.test/thumb.jpg", hits[0].imageUrl)
        assertEquals(92.0, hits[0].providerSimilarity!!, 0.0001)
        assertEquals("Danbooru", hits[0].title)
        assertEquals(ReverseImageProviders.IQDB_ID, hits[0].providerId)
        assertEquals(now, hits[0].searchedAtMillis)
        assertEquals("https://other.example.test/item/9", hits[1].sourceUrl)
        assertEquals(77.0, hits[1].providerSimilarity!!, 0.0001)
    }

    @Test
    fun `a page with no result tables yields nothing rather than an invented row`() {
        assertTrue(IqdbResultParser.parsePage("<html><body>No relevant matches.</body></html>", now).isEmpty())
        assertTrue(IqdbResultParser.parsePage("", now).isEmpty())
    }

    @Test
    fun `pasted lines are accepted only with a url, and must not invent a source`() {
        val text = """
            92% https://danbooru.example.test/posts/123
            just a title with no link
            https://art.example.test/456
            100% similarity https://shop.example.test/work/=/product_id/RJ01234567.html
        """.trimIndent()

        val hits = IqdbResultParser.parsePasted(text, now)
        assertEquals("only the three lines with a URL become rows", 3, hits.size)
        assertEquals("92%", hits[0].metadata["similarity"])
        assertEquals("https://danbooru.example.test/posts/123", hits[0].sourceUrl)
        assertTrue(hits[0].providerSimilarity!! == 92.0)
        assertEquals(null, hits[1].providerSimilarity)
        assertEquals("100", hits[2].providerSimilarity!!.toInt().toString())
        assertTrue(hits.all { it.providerId == ReverseImageProviders.IQDB_ID })
        assertTrue(hits.all { it.metadata["submitted"] == "pasted by the user" })
    }

    @Test
    fun `the pasted row limit is respected`() {
        val many = (1..30).joinToString("\n") { "https://example.test/$it" }
        assertEquals(10, IqdbResultParser.parsePasted(many, now, limit = 10).size)
        assertTrue(IqdbResultParser.parsePasted(many, now, limit = 0).isEmpty())
    }
}
