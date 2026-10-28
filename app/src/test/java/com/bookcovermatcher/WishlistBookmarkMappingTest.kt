package com.bookcovermatcher

import com.bookcovermatcher.core.wishlist.ItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DLsite result → bookmark mapping, and the drawer state rule.
 *
 * The display line (`ProductResultUi.authors`) falls back to the shop's circle for marketplace
 * listings, so it must never be turned back into artist data.
 */
class WishlistBookmarkMappingTest {

    private fun result(
        displayAuthors: String,
        artistNames: List<String> = emptyList(),
        circle: String? = null,
    ) = ProductResultUi(
        id = "RJ01266914",
        key = "dlsite:RJ01266914",
        title = "みつ",
        authors = displayAuthors,
        meta = "¥770",
        languages = "",
        coverJpeg = null,
        detailUrl = "https://www.dlsite.com/maniax-touch/work/=/product_id/RJ01266914.html",
        source = "DLsite",
        provider = "DLsite",
        sourceDomain = "dlsite.com",
        identifier = "RJ01266914",
        price = "¥770",
        circle = circle,
        artistNames = artistNames,
    )

    @Test fun aShopResultNeverTurnsItsCircleIntoAnArtist() {
        val draft = result(displayAuthors = "榎屋", circle = "榎屋").toDraft()

        assertEquals("the circle belongs in its own field", "榎屋", draft.circle)
        assertTrue("no artist may be invented from the circle", draft.authors.isEmpty())
        assertEquals(ItemKind.ONLINE_RESULT, draft.kind)
    }

    @Test fun aRealAuthorIsKeptNextToTheCircle() {
        val draft = result(
            displayAuthors = "原作者",
            artistNames = listOf("原作者"),
            circle = "榎屋",
        ).toDraft()

        assertEquals(listOf("原作者"), draft.authors)
        assertEquals("榎屋", draft.circle)
    }

    @Test fun aResultWithoutAuthorMetadataSavesAnEmptyArtist() {
        val draft = result(displayAuthors = "circle not shown", circle = "榎屋").toDraft()

        assertTrue(draft.authors.isEmpty())
        assertEquals("榎屋", draft.circle)
    }

    @Test fun aBibliographicResultKeepsItsAuthorsAndHasNoCircle() {
        val draft = ProductResultUi(
            id = "OL1W",
            key = "ol:OL1W",
            title = "A Book",
            authors = "eno",
            meta = "1937",
            languages = "eng",
            coverJpeg = null,
            detailUrl = "https://openlibrary.org/works/OL1W",
            source = "Open Library",
            provider = "Open Library",
            sourceDomain = "openlibrary.org",
            identifier = "OL1W",
            artistNames = listOf("eno"),
        ).toDraft()

        assertEquals(listOf("eno"), draft.authors)
        assertEquals(null, draft.circle)
    }

    @Test fun theDrawerStartsExpandedAndTogglesBackAndForth() {
        val initial = UiState()

        assertTrue("a new session starts with the panel open", initial.bookmarkPanelOpen)
        val closed = initial.toggleBookmarkPanel()
        assertTrue(!closed.bookmarkPanelOpen)
        val reopened = closed.toggleBookmarkPanel()
        assertTrue(reopened.bookmarkPanelOpen)
    }

    @Test fun togglingTheDrawerLeavesUnrelatedStateAlone() {
        val state = UiState(wishlistSection = WishlistSection.BOOKMARKS, bookmarkPanelOpen = false)

        val toggled = state.toggleBookmarkPanel()

        assertEquals(WishlistSection.BOOKMARKS, toggled.wishlistSection)
        assertTrue("the drawer is the only thing that changed", toggled.bookmarkPanelOpen)
        assertEquals(state.savedWorkbooks, toggled.savedWorkbooks)
    }
}
