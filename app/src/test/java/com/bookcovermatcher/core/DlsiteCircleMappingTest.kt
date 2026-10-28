package com.bookcovermatcher.core

import com.bookcovermatcher.core.online.DlsiteResponseParser
import com.bookcovermatcher.core.online.ProductSearchResult
import com.bookcovermatcher.core.wishlist.ItemDraft
import com.bookcovermatcher.core.wishlist.ItemKind
import com.bookcovermatcher.core.wishlist.WishlistRepository
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shop's circle is its own value. It must never be folded into the author list, and editing a
 * bookmark must not move it back there.
 */
class DlsiteCircleMappingTest {

    private fun marketplace(circle: String?, authors: List<String> = emptyList()) = ProductSearchResult(
        id = "RJ01266914",
        title = "みつ",
        authors = authors,
        publishedYear = null,
        publishers = emptyList(),
        pageCount = null,
        languages = emptyList(),
        coverUrl = null,
        detailUrl = "https://www.dlsite.com/maniax-touch/work/=/product_id/RJ01266914.html",
        source = "DLsite",
        circle = circle,
        isMarketplace = true,
    )

    @Test fun aShopCircleIsShownAsTheCircleNotAsAnAuthor() {
        val result = marketplace(circle = "榎屋")

        assertTrue("no author is invented for a shop listing", result.authors.isEmpty())
        assertEquals("the card line shows the circle", "榎屋", result.authorLine)
    }

    @Test fun aBibliographyKeepsItsAuthorsAndHasNoCircle() {
        val book = ProductSearchResult(
            id = "OL1W",
            title = "A Book",
            authors = listOf("eno"),
            publishedYear = 2020,
            publishers = listOf("Publisher"),
            pageCount = 100,
            languages = listOf("eng"),
            coverUrl = null,
            detailUrl = "https://openlibrary.org/works/OL1W",
            source = "Open Library",
        )

        assertEquals(listOf("eno"), book.authors)
        assertNull(book.circle)
        assertEquals("eno", book.authorLine)
    }

    private fun repo(): WishlistRepository = WishlistRepository(File(Files.createTempDirectory("circ").toFile(), "w"))

    private fun draft(circle: String?, artists: List<String> = emptyList()) = ItemDraft(
        kind = ItemKind.ONLINE_RESULT,
        title = "みつ",
        url = "https://www.dlsite.com/maniax-touch/work/=/product_id/RJ01266914.html",
        authors = artists,
        circle = circle,
    )

    @Test fun theCircleAndArtistsPersistInTheirOwnFields() {
        val repo = repo()

        val id = repo.bookmark(draft(circle = "榎屋", artists = listOf("eno"))).itemIds.single()
        val item = repo.library().items.first { it.id == id }

        assertEquals("榎屋", item.circle)
        assertEquals(listOf("eno"), item.authors)
        assertTrue("the circle must not be stored as an author", "榎屋" !in item.authors)
    }

    @Test fun editingKeepsTheCircleAndDoesNotMoveItToAuthors() {
        val repo = repo()
        val id = repo.bookmark(draft(circle = "榎屋")).itemIds.single()

        repo.updateBookmark(id, draft(circle = "榎屋").let { it })

        val edited = repo.library().items.first { it.id == id }
        assertEquals("榎屋", edited.circle)
        assertTrue(edited.authors.isEmpty())
    }

    @Test fun anEmptyCircleIsValidAndStaysEmpty() {
        val repo = repo()
        val id = repo.bookmark(draft(circle = null)).itemIds.single()

        assertNull(repo.library().items.first { it.id == id }.circle)
    }

    @Test fun theAuthorIsOnlyReadWhenThePageReallyStatesOne() {
        val withBlock = "<dd class=\"author_name\"><a>原作者</a></dd>"
        val withLabel = "<tr><th>作者</th><td>原作者</td></tr>"
        val without = "<dd class=\"maker_name\"><a>榎屋</a></dd>"

        assertEquals(listOf("原作者"), DlsiteResponseParser.authorOf(withBlock))
        assertEquals(listOf("原作者"), DlsiteResponseParser.authorOf(withLabel))
        assertEquals(
            "the circle is never borrowed as an author",
            emptyList<String>(),
            DlsiteResponseParser.authorOf(without),
        )
    }
}
