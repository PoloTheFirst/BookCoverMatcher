package com.bookcovermatcher.core

import com.bookcovermatcher.ProviderState
import com.bookcovermatcher.ProviderStatusUi
import com.bookcovermatcher.core.online.ProviderCatalog
import com.bookcovermatcher.withQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.2.4 — the browser-only cards follow the **query field**, not the last search.
 *
 * `OnlineSearchController.setQuery` rebuilds every row's link through `withQuery` on each edit, so a
 * user can type a title and open Melonbooks/Suruga-ya/Toranoana (or any card) without a dummy in-app
 * search first, and a search that finishes late cannot drag the links back to the query it ran for.
 */
class ProviderRowLinksTest {

    /** The rows the panel starts with, built the way the controller builds them. */
    private fun rows(query: String = ""): List<ProviderStatusUi> = ProviderCatalog.ALL.map { spec ->
        ProviderStatusUi(
            id = spec.id,
            name = spec.name,
            host = spec.host,
            manual = !spec.selectable,
            selected = false,
            state = if (spec.selectable) ProviderState.READY else ProviderState.MANUAL,
            detail = spec.note,
            searchUrl = runCatching { spec.searchUrl(query) }.getOrNull(),
        )
    }

    private fun List<ProviderStatusUi>.url(id: String): String? = first { it.id == id }.searchUrl

    @Test fun typingAQueryPointsEveryBrowserOnlyCardAtItWithoutSearching() {
        val typed = rows().withQuery("Blue Archive")

        // Nothing was searched: the rows are still in their resting state.
        assertEquals(ProviderState.MANUAL, typed.first { it.id == "tr" }.state)
        assertEquals(ProviderState.READY, typed.first { it.id == "ol" }.state)

        assertEquals(
            "https://ec.toranoana.jp/tora_r/ec/app/catalog/list?searchWord=Blue%20Archive",
            typed.url("tr"),
        )
        assertEquals(
            "https://www.melonbooks.co.jp/search/search.php?mode=search&name=Blue%20Archive",
            typed.url("mb"),
        )
        assertEquals(
            "https://www.suruga-ya.jp/search?category=&search_word=Blue%20Archive",
            typed.url("sy"),
        )
        // The in-app cards' links follow the same field.
        assertEquals("https://openlibrary.org/search?q=Blue%20Archive", typed.url("ol"))
    }

    @Test fun japaneseAndReservedTextIsEncodedOnceAndNeverCorrupted() {
        assertEquals(
            "https://ec.toranoana.jp/tora_r/ec/app/catalog/list" +
                "?searchWord=%E6%9D%B1%E6%96%B9%20%E7%B4%85%E9%AD%94%E9%83%B7",
            rows().withQuery("\u6771\u65B9 \u7D05\u9B54\u90F7").url("tr"),
        )
        assertEquals(
            "https://ec.toranoana.jp/tora_r/ec/app/catalog/list" +
                "?searchWord=A%26B%20C%2B%2B%20100%25%20%23tag",
            rows().withQuery("A&B C++ 100% #tag").url("tr"),
        )
    }

    @Test fun anEditAfterASearchKeepsTheOutcomeButMovesTheLink() {
        // The rows of a finished search for "old title"; the field is then edited.
        val afterSearch = rows("old title").map { row ->
            if (row.id == "tr") row else row.copy(state = ProviderState.RESULTS, detail = "3 results")
        }

        val edited = afterSearch.withQuery("new title")

        // The outcome still describes the search that actually ran...
        assertEquals(ProviderState.RESULTS, edited.first { it.id == "mb" }.state)
        assertEquals("3 results", edited.first { it.id == "mb" }.detail)
        // ...while every link uses the text that is in the field now.
        assertTrue(edited.url("tr")!!.endsWith("searchWord=new%20title"))
        assertFalse(edited.url("tr")!!.contains("old"))
        assertTrue(edited.url("mb")!!.endsWith("name=new%20title"))
        assertTrue(edited.url("ol")!!.endsWith("q=new%20title"))
    }

    @Test fun aLateAnswerDoesNotDragLinksBackToTheQueryThatProducedIt() {
        // What the controller does when a search for "old title" completes after the user typed on.
        val late = rows("old title").withQuery("brand new")

        assertEquals(
            "https://ec.toranoana.jp/tora_r/ec/app/catalog/list?searchWord=brand%20new",
            late.url("tr"),
        )
        assertFalse(late.url("tr")!!.contains("old%20title"))
    }

    @Test fun anEmptyOrBlankQueryFallsBackToTheLandingPages() {
        listOf("", "   ", "\t\n ").forEach { blank ->
            val blanked = rows("something").withQuery(blank)

            assertEquals("https://ec.toranoana.jp/tora_r/ec/", blanked.url("tr"))
            assertEquals("https://www.melonbooks.co.jp/", blanked.url("mb"))
            assertEquals("https://openlibrary.org/search", blanked.url("ol"))
            assertFalse(blanked.url("tr")!!, blanked.url("tr")!!.contains("searchWord="))
        }
    }

    @Test fun theQueryIsTrimmedAtTheEdgesButInternalSpacesSurvive() {
        val url = rows().withQuery("  Blue  Archive  ").url("tr")!!

        assertTrue(url, url.endsWith("searchWord=Blue%20%20Archive"))
    }

    @Test fun everyShippedCardHasALinkForTheCurrentField() {
        val typed = rows().withQuery("the hobbit")

        ProviderCatalog.ALL.forEach { spec ->
            val url = typed.url(spec.id)
            assertTrue("${spec.name} must have a link", url != null)
            assertTrue("${spec.name}: $url", url!!.startsWith("https://"))
        }
    }

    @Test fun aRowForAnUnknownProviderDropsItsStaleLinkInsteadOfCrashing() {
        val orphan = ProviderStatusUi(
            id = "gone",
            name = "Gone",
            host = "example.com",
            manual = true,
            selected = false,
            state = ProviderState.MANUAL,
            detail = null,
            searchUrl = "https://example.com/search?q=old",
        )

        val updated = listOf(orphan).withQuery("anything")

        assertNull("an id the catalogue no longer has must not keep a stale link", updated.single().searchUrl)
    }
}
