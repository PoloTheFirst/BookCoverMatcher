package com.bookcovermatcher.core

import com.bookcovermatcher.core.wishlist.ItemDraft
import com.bookcovermatcher.core.wishlist.ItemKind
import com.bookcovermatcher.core.wishlist.MatchType
import com.bookcovermatcher.core.wishlist.SessionDraft
import com.bookcovermatcher.core.wishlist.SessionKind
import com.bookcovermatcher.core.wishlist.WishlistRepository
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private fun tmpBookmark(): File = Files.createTempDirectory("profilebookmark").toFile()

private fun onlineDraftForBookmark(id: String) = ItemDraft(
    kind = ItemKind.ONLINE_RESULT,
    title = "Book $id",
    url = "https://openlibrary.org/works/$id",
    provider = "Open Library",
    providerKey = "ol:$id",
    identifier = id,
    matchType = MatchType.TEXT_QUERY,
    matchTypeVerified = true,
)

private fun rowDraftForBookmark(cell: String) = ItemDraft(
    kind = ItemKind.WORKBOOK_MATCH,
    title = cell,
    matchType = MatchType.WORKBOOK,
    matchTypeVerified = true,
    cell = cell,
    cosine = 0.9f,
    percent = 92f,
)

/**
 * Bookmarking an online result that lives in a result profile. The profile entry (a `SessionEntry`) and
 * the bookmark (a `Membership`) are independent references to **one** item record, which is exactly what
 * these tests pin down.
 */
class ProfileBookmarkTest {

    private lateinit var root: File
    private lateinit var repo: WishlistRepository
    private lateinit var folder: String
    private lateinit var profile: String

    @Before fun setUp() {
        root = tmpBookmark()
        repo = WishlistRepository(root)
        repo.ensureReserved()
        folder = repo.createFolder("Shelf").id
        profile = requireNotNull(
            repo.saveSession(
                SessionDraft(
                    folderId = folder,
                    kind = SessionKind.RESULT_PROFILE,
                    title = "Blue Archive profile",
                    query = "blue archive",
                    results = listOf(onlineDraftForBookmark("p1"), onlineDraftForBookmark("p2")),
                ),
            ).sessionId,
        )
    }

    private fun firstResultId(): String = repo.library().sessionItems(profile).first().id

    @Test fun aProfileResultCanBeBookmarked() {
        val id = firstResultId()

        assertTrue(repo.bookmarkExisting(id))

        val library = repo.library()
        assertTrue(library.isBookmarked(id))
        assertEquals(1, library.bookmarkedItems().size)
        assertEquals("the profile keeps both results", 2, library.sessionItems(profile).size)
        assertEquals("no second copy of the work is created", 2, library.items.size)
    }

    @Test fun theBookmarkAppearsInTheBookmarksCollectionWithTheSameIdentity() {
        val item = repo.library().sessionItems(profile).first()

        assertTrue(repo.bookmarkExisting(item.id))

        val saved = repo.library().bookmarkedItems().single()
        assertEquals(item.id, saved.id)
        assertEquals(item.title, saved.title)
        assertEquals(item.url, saved.url)
        assertEquals(item.fingerprint, saved.fingerprint)
    }

    @Test fun bookmarkingTwiceCreatesNoDuplicateMembership() {
        val id = firstResultId()
        val bookmarksId = repo.library().bookmarksFolder!!.id

        assertTrue(repo.bookmarkExisting(id))
        assertFalse("the second attempt must be a no-op", repo.bookmarkExisting(id))

        val library = repo.library()
        assertEquals(1, library.memberships.count { it.itemId == id && it.collectionId == bookmarksId })
        assertEquals(1, library.bookmarkedItems().size)
    }

    @Test fun removingTheBookmarkKeepsTheProfileEntry() {
        val id = firstResultId()
        repo.bookmarkExisting(id)

        assertTrue(repo.removeBookmark(id))

        val library = repo.library()
        assertFalse(library.isBookmarked(id))
        assertEquals(2, library.sessionItems(profile).size)
        assertEquals(2, library.items.size)
    }

    @Test fun deletingTheProfileKeepsTheBookmark() {
        val id = firstResultId()
        repo.bookmarkExisting(id)

        assertTrue(repo.deleteSession(profile))

        val library = repo.library()
        assertTrue(library.isBookmarked(id))
        assertEquals(1, library.bookmarkedItems().size)
        assertEquals("the other profile result had no other reference", 1, library.items.size)
    }

    @Test fun aWorkbookComparisonRowIsNeverBookmarked() {
        val scan = requireNotNull(
            repo.saveSession(
                SessionDraft(
                    folderId = folder,
                    kind = SessionKind.OFFLINE_MATCH,
                    title = "Scan",
                    results = listOf(rowDraftForBookmark("A1")),
                ),
            ).sessionId,
        )
        val row = repo.library().sessionItems(scan).single()

        assertFalse(repo.bookmarkExisting(row.id))

        val library = repo.library()
        assertTrue(library.bookmarkedItems().isEmpty())
        assertEquals(1, library.sessionItems(scan).size)
    }

    @Test fun anUnknownItemIsNotBookmarked() {
        assertFalse(repo.bookmarkExisting("itm_does_not_exist"))
        assertTrue(repo.library().bookmarkedItems().isEmpty())
    }

    @Test fun theBookmarkSurvivesAReload() {
        val id = firstResultId()
        assertTrue(repo.bookmarkExisting(id))

        val reloaded = WishlistRepository(root).library()
        assertTrue(reloaded.isBookmarked(id))
        assertEquals(1, reloaded.bookmarkedItems().size)
        assertEquals(2, reloaded.sessionItems(profile).size)
    }

    @Test fun bookmarkingDoesNotDuplicateAWorkAlreadySavedInAFolder() {
        val id = firstResultId()
        assertTrue(repo.addExistingItemToFolder(id, folder) || repo.library().directItems(folder).any { it.id == id })

        assertTrue(repo.bookmarkExisting(id))

        val library = repo.library()
        assertEquals("one item record, two memberships", 2, library.items.size)
        assertTrue(library.isBookmarked(id))
        assertEquals(2, library.sessionItems(profile).size)
    }

    @Test fun theProfileEntryAndTheBookmarkAreIndependentReferences() {
        val id = firstResultId()
        assertTrue(repo.bookmarkExisting(id))

        val library = repo.library()
        assertTrue("bookmark membership", library.memberships.any { it.itemId == id })
        assertTrue("profile entry", library.entries.any { it.itemId == id && it.sessionId == profile })
        assertNotNull(library.item(id))
        assertEquals(2, library.sessionItems(profile).size)
    }
}