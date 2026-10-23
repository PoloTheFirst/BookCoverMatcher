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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private fun tmpBatch(): File = Files.createTempDirectory("batch").toFile()

private fun itemDraft(id: String, title: String = "Book $id") = ItemDraft(
    kind = ItemKind.ONLINE_RESULT,
    title = title,
    url = "https://openlibrary.org/works/$id",
    provider = "Open Library",
    providerKey = "ol:$id",
    identifier = id,
    matchType = MatchType.TEXT_QUERY,
    matchTypeVerified = true,
)

private fun rowDraft(cell: String) = ItemDraft(
    kind = ItemKind.WORKBOOK_MATCH,
    title = cell,
    matchType = MatchType.WORKBOOK,
    matchTypeVerified = true,
    cell = cell,
    cosine = 0.9f,
    percent = 92f,
)

/**
 * The batch (multi-selection) operations, exercised against the real persistence model: a temp
 * directory, the real repository and its real JSON file - no mocked callbacks.
 */
class BatchOperationsTest {

    private lateinit var root: File
    private lateinit var repo: WishlistRepository
    private lateinit var bookmarks: String
    private lateinit var shelfA: String
    private lateinit var shelfB: String

    @Before fun setUp() {
        root = tmpBatch()
        repo = WishlistRepository(root)
        bookmarks = repo.ensureReserved().id
        shelfA = repo.createFolder("Shelf A").id
        shelfB = repo.createFolder("Shelf B").id
    }

    private fun addTo(folder: String, vararg ids: String): List<String> =
        repo.addDrafts(ids.map { itemDraft(it) }, folder).itemIds

    private fun session(folder: String, vararg ids: String, kind: SessionKind = SessionKind.ONLINE_SEARCH): String =
        requireNotNull(
            repo.saveSession(
                SessionDraft(
                    folderId = folder,
                    kind = kind,
                    title = "Search",
                    query = "q",
                    results = ids.map { itemDraft(it) },
                ),
            ).sessionId,
        )

    @Test fun copyingBookmarksIntoAFolderKeepsTheBookmarks() {
        val ids = repo.bookmarkAll(listOf(itemDraft("b1"), itemDraft("b2"))).itemIds

        val report = repo.copyItemsToFolder(ids, shelfA)

        assertEquals(2, report.changed)
        assertEquals(2, repo.library().directItems(shelfA).size)
        assertEquals(2, repo.library().bookmarkedItems().size)
        assertTrue(repo.library().isBookmarked(ids.first()))
    }

    @Test fun copyingTheSameItemsTwiceNeverDuplicatesAMembership() {
        val ids = addTo(shelfA, "c1", "c2")

        assertEquals(2, repo.copyItemsToFolder(ids, shelfB).changed)
        val again = repo.copyItemsToFolder(ids, shelfB)

        assertEquals(0, again.changed)
        assertEquals(2, again.skipped)
        assertEquals(2, repo.library().directItems(shelfB).size)
    }

    @Test fun movingItemsLeavesTheSourceFolderAndKeepsTheirBookmarks() {
        val ids = addTo(shelfA, "m1", "m2")
        repo.bookmarkAll(listOf(itemDraft("m1")))

        val report = repo.moveItemsToFolder(ids, shelfA, shelfB)

        assertEquals(2, report.changed)
        assertTrue("the source folder must be empty", repo.library().directItems(shelfA).isEmpty())
        assertEquals(2, repo.library().directItems(shelfB).size)
        assertTrue("only the selected item was bookmarked", repo.library().isBookmarked(ids.first()))
        assertFalse(repo.library().isBookmarked(ids.last()))
    }

    @Test fun movingToTheSameFolderIsRefusedWithoutChangingAnything() {
        val ids = addTo(shelfA, "s1")

        val report = repo.moveItemsToFolder(ids, shelfA, shelfA)

        assertEquals(0, report.changed)
        assertEquals(1, report.failed)
        assertNotNull(report.message)
        assertEquals(1, repo.library().directItems(shelfA).size)
    }

    @Test fun removingItemsFromOneFolderKeepsTheBookmarkAndTheOtherFolder() {
        val ids = addTo(shelfA, "r1")
        repo.bookmarkAll(listOf(itemDraft("r1")))
        repo.copyItemsToFolder(ids, shelfB)

        val report = repo.removeItemsFromFolder(ids, shelfA)

        assertEquals(1, report.changed)
        assertTrue(repo.library().isBookmarked(ids.first()))
        assertEquals(1, repo.library().directItems(shelfB).size)
    }

    @Test fun deletingABookmarkNeverTouchesTheWishlistMembership() {
        val ids = addTo(shelfA, "d1")
        repo.bookmarkAll(listOf(itemDraft("d1")))

        val report = repo.deleteBookmarks(ids)

        assertEquals(1, report.changed)
        assertFalse(repo.library().isBookmarked(ids.first()))
        assertEquals(1, repo.library().directItems(shelfA).size)
    }

    @Test fun movingAResultOutOfASessionKeepsItsBookmark() {
        val sessionId = session(shelfA, "e1", "e2")
        val first = repo.library().sessionItems(sessionId).first().id
        repo.bookmarkAll(listOf(itemDraft("e1"), itemDraft("e2")))

        val report = repo.moveSessionEntries(listOf(first), sessionId, shelfB)

        assertEquals(1, report.changed)
        assertTrue(repo.library().isBookmarked(first))
        assertEquals(1, repo.library().sessionItems(sessionId).size)
        assertEquals(1, repo.library().directItems(shelfB).size)
    }

    @Test fun copyingAResultOutOfASessionLeavesTheSessionIntact() {
        val sessionId = session(shelfA, "f1", "f2")
        val first = repo.library().sessionItems(sessionId).first().id

        val report = repo.copySessionEntries(listOf(first), sessionId, shelfB)

        assertEquals(1, report.changed)
        assertEquals(2, repo.library().sessionItems(sessionId).size)
        assertEquals(1, repo.library().directItems(shelfB).size)
    }

    @Test fun copyingASessionCopiesItsEntriesWithoutDuplicatingItems() {
        val sessionId = session(shelfA, "g1", "g2")

        val report = repo.copySessionsToFolder(listOf(sessionId), shelfB)

        assertEquals(1, report.changed)
        val copy = repo.library().sessionsIn(shelfB).single()
        assertNotEquals(sessionId, copy.id)
        assertTrue(copy.title.endsWith("(copy)"))
        assertEquals(
            repo.library().sessionItems(sessionId).map { it.id },
            repo.library().sessionItems(copy.id).map { it.id },
        )
        assertEquals("the same two items are shared, not duplicated", 2, repo.library().items.size)
    }

    @Test fun movingASessionRespectsTheTargetFolderAndSkipsTheSameFolder() {
        val sessionId = session(shelfA, "h1")

        assertEquals(1, repo.moveSessionsToFolder(listOf(sessionId), shelfB).changed)
        assertEquals(shelfB, repo.library().session(sessionId)?.folderId ?: "unfiled")

        val again = repo.moveSessionsToFolder(listOf(sessionId), shelfB)
        assertEquals(0, again.changed)
        assertEquals(1, again.skipped)
    }

    @Test fun copyingAFolderCopiesItsSessionsAndDirectItems() {
        val sessionId = session(shelfA, "j1")
        addTo(shelfA, "j2")

        val report = repo.copyFolders(listOf(shelfA))

        assertEquals(1, report.changed)
        val copy = repo.library().folders.first { it.name.startsWith("Shelf A (copy)") }
        assertNotEquals(shelfA, copy.id)
        assertEquals(1, repo.library().sessionsIn(copy.id).size)
        assertEquals(1, repo.library().directItems(copy.id).size)
        assertEquals("the copy shares the same items", 2, repo.library().items.size)
        assertEquals(1, repo.library().sessionsIn(shelfA).size)
        assertEquals(sessionId, repo.library().sessionsIn(shelfA).single().id)
    }

    @Test fun aCopiedFolderGetsAUniqueName() {
        repo.copyFolders(listOf(shelfA))
        repo.copyFolders(listOf(shelfA))

        val names = repo.library().folders.map { it.name }
        assertEquals(2, names.count { it.startsWith("Shelf A (copy)") })
        assertEquals(names.size, names.toSet().size)
    }

    @Test fun deletingAFolderKeepsBookmarkedItemsAndOtherFolders() {
        val ids = addTo(shelfA, "k1")
        repo.bookmarkAll(listOf(itemDraft("k1")))
        repo.copyItemsToFolder(ids, shelfB)
        session(shelfA, "k2")

        val report = repo.deleteFolders(listOf(shelfA))

        assertEquals(1, report.changed)
        assertFalse(repo.library().folders.any { it.id == shelfA })
        assertTrue(repo.library().isBookmarked(ids.first()))
        assertEquals(1, repo.library().directItems(shelfB).size)
        assertEquals(0, repo.library().sessionsIn(shelfA).size)
    }

    @Test fun theReservedCollectionIsNeverCopiedOrDeleted() {
        assertEquals(0, repo.copyFolders(listOf(bookmarks)).changed)
        assertEquals(0, repo.deleteFolders(listOf(bookmarks)).changed)
        assertNotNull(repo.library().bookmarksFolder)
    }

    @Test fun workbookComparisonRowsAreNeverPromotedToFolderItems() {
        val sessionId = requireNotNull(
            repo.saveSession(
                SessionDraft(
                    folderId = shelfA,
                    kind = SessionKind.OFFLINE_MATCH,
                    title = "Scan",
                    results = listOf(rowDraft("A1"), rowDraft("A2")),
                ),
            ).sessionId,
        )
        val row = repo.library().sessionItems(sessionId).first()

        val copy = repo.copyItemsToFolder(listOf(row.id), shelfB)
        val move = repo.moveSessionEntries(listOf(row.id), sessionId, shelfB)
        val copySession = repo.copySessionEntries(listOf(row.id), sessionId, shelfB)

        assertEquals(0, copy.changed)
        assertEquals(1, copy.skipped)
        assertEquals(0, move.changed)
        assertEquals(0, copySession.changed)
        assertEquals(2, repo.library().sessionItems(sessionId).size)
        assertTrue(repo.library().directItems(shelfB).isEmpty())
    }

    @Test fun deletingSessionsKeepsItemsUsedSomewhereElse() {
        val ids = addTo(shelfB, "n1")
        val sessionId = session(shelfA, "n1")

        val report = repo.deleteSessions(listOf(sessionId))

        assertEquals(1, report.changed)
        assertTrue(repo.library().sessions.isEmpty())
        assertTrue(repo.library().entries.isEmpty())
        assertEquals("the bookmarked copy of the item survives", 1, repo.library().directItems(shelfB).size)
        assertEquals(ids, repo.library().directItems(shelfB).map { it.id })
    }

    @Test fun anUnknownTargetIsReportedInsteadOfThrown() {
        val ids = addTo(shelfA, "p1")

        val report = repo.copyItemsToFolder(ids, "col_missing")

        assertEquals(0, report.changed)
        assertEquals(1, report.failed)
        assertNotNull(report.message)
        assertEquals(1, repo.library().directItems(shelfA).size)
    }

    @Test fun aBatchIsPersistedInOneWriteThatSurvivesAReload() {
        val ids = addTo(shelfA, "q1", "q2", "q3")
        repo.moveItemsToFolder(ids, shelfA, shelfB)

        val reloaded = WishlistRepository(root).library()

        assertTrue(reloaded.directItems(shelfA).isEmpty())
        assertEquals(3, reloaded.directItems(shelfB).size)
    }

    @Test fun aFailedWriteIsReportedAndTheStoredFileStaysUnchanged() {
        val ids = addTo(shelfA, "w1", "w2")
        repo.bookmarkAll(listOf(itemDraft("w1")))

        val records = File(root, "records.json")
        val locked = records.setReadOnly()
        org.junit.Assume.assumeTrue("this filesystem cannot mark the file read-only", locked)
        try {
            val report = repo.deleteBookmarks(ids)

            assertEquals("a failed write must not claim anything changed", 0, report.changed)
            assertEquals(2, report.failed)
            assertNotNull(report.message)
            // The stored file is untouched: a fresh repository still sees both memberships.
            val reloaded = WishlistRepository(root).library()
            assertEquals(2, reloaded.directItems(shelfA).size)
            assertEquals(1, reloaded.bookmarkedItems().size)
        } finally {
            records.setWritable(true)
        }
    }
}