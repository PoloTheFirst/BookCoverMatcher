package com.bookcovermatcher.core

import com.bookcovermatcher.core.online.ImageFingerprint
import com.bookcovermatcher.core.online.OnlineAggregator
import com.bookcovermatcher.core.online.ProductSearchResult
import com.bookcovermatcher.core.online.SessionCache
import com.bookcovermatcher.core.wishlist.AddReport
import com.bookcovermatcher.core.wishlist.BookmarkRules
import com.bookcovermatcher.core.wishlist.CollectionKind
import com.bookcovermatcher.core.wishlist.InvalidItemException
import com.bookcovermatcher.core.wishlist.ItemDraft
import com.bookcovermatcher.core.wishlist.ItemIdentity
import com.bookcovermatcher.core.wishlist.ItemKind
import com.bookcovermatcher.core.wishlist.MatchType
import com.bookcovermatcher.core.wishlist.SavedItem
import com.bookcovermatcher.core.wishlist.SessionDraft
import com.bookcovermatcher.core.wishlist.SessionKind
import com.bookcovermatcher.core.wishlist.WishlistRecord
import com.bookcovermatcher.core.wishlist.WishlistMatch
import com.bookcovermatcher.core.wishlist.WishlistRepository
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun tmp(name: String = "lib"): File = Files.createTempDirectory(name).toFile()

private fun jpeg(tag: Int = 1) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), tag.toByte())

private fun onlineDraft(id: String, title: String = "Book $id", thumb: ByteArray? = jpeg(7)) = ItemDraft(
    kind = ItemKind.ONLINE_RESULT,
    title = title,
    url = "https://openlibrary.org/works/$id",
    provider = "Open Library",
    providerKey = "ol:$id",
    identifier = id,
    authors = listOf("Author"),
    matchType = MatchType.TEXT_QUERY,
    matchTypeVerified = true,
    thumbJpeg = thumb,
)

private fun workbookDraft(cell: String, percent: Float = 91.5f) = ItemDraft(
    kind = ItemKind.WORKBOOK_MATCH,
    title = cell,
    description = "sheet row",
    matchType = MatchType.WORKBOOK,
    matchTypeVerified = true,
    cell = cell,
    cosine = 0.87f,
    percent = percent,
    thumbJpeg = jpeg(cell.hashCode() and 0xFF),
)

private fun manualDraft(
    title: String = "Hand typed",
    url: String = "https://example.com/item",
    authors: String = "Someone",
    description: String = "notes",
    identifier: String = "ID-1",
    price: String = "12.00",
) = ItemDraft(
    kind = ItemKind.MANUAL_BOOKMARK,
    title = title,
    url = url,
    authors = authors.split(',').map(String::trim).filter(String::isNotEmpty),
    description = description,
    identifier = identifier,
    price = price,
    matchType = MatchType.MANUAL,
    matchTypeVerified = false,
)

private class Clock(var now: Long = 1_000L) {
    fun next(): Long = now++
}

private class Ids {
    var item = 0
    var folder = 0
    var session = 0
    fun itemId(): String = "itm_${++item}"
    fun folderId(): String = "col_${++folder}"
    fun sessionId(): String = "ses_${++session}"
}

private fun repo(dir: File, clock: Clock = Clock(), ids: Ids = Ids()) = WishlistRepository(
    root = dir,
    clock = clock::next,
    newItemId = ids::itemId,
    newFolderId = ids::folderId,
    newSessionId = ids::sessionId,
)

private fun WishlistRepository.saveOffline(folderId: String, cells: List<String>): AddReport =
    saveSession(
        SessionDraft(
            folderId = folderId,
            kind = SessionKind.OFFLINE_MATCH,
            title = "",
            results = cells.map { workbookDraft(it) },
        ),
    )

private fun WishlistRepository.bookmarkOnline(id: String, title: String = "Book $id"): AddReport =
    bookmark(onlineDraft(id, title))

class BookmarksCollectionTest {

    @Test fun `the bookmarks folder is created when missing and only once`() {
        val dir = tmp()
        val r = repo(dir)

        val first = r.library().bookmarksFolder
        assertNotNull(first)
        assertEquals("Bookmarks", first!!.name)
        assertEquals(CollectionKind.RESERVED, first.kind)

        val second = repo(dir).library().bookmarksFolder
        assertEquals(first.id, second!!.id)
    }

    @Test fun `the reserved folder cannot be renamed or deleted and survives a restart`() {
        val dir = tmp()
        val r = repo(dir)
        val id = r.library().bookmarksFolder!!.id

        assertFalse(r.renameFolder(id, "Something else"))
        assertFalse(r.deleteFolder(id))
        assertEquals("Bookmarks", repo(dir).library().bookmarksFolder!!.name)
    }

    @Test fun `a corrupt file is quarantined, not overwritten`() {
        val dir = tmp()
        File(dir, "records.json").writeText("{not json", Charsets.UTF_8)

        val library = repo(dir).library()

        assertNotNull(library.bookmarksFolder)
        assertTrue(File(dir, "records.json.corrupt").isFile)
    }

    @Test fun `bookmarking creates no folder and no session`() {
        val r = repo(tmp())

        val report = r.bookmarkOnline("OL1")
        val library = r.library()

        assertEquals(1, report.added)
        assertNull(report.sessionId)
        assertEquals(0, library.profileFolders.size)
        assertEquals(0, library.sessions.size)
        assertTrue(library.isBookmarked(library.items.single().id))
        assertFalse(library.isWishlisted(library.items.single().id))
    }

    @Test fun `bookmarking is idempotent and survives a restart`() {
        val dir = tmp()
        val first = repo(dir)

        assertEquals(1, first.bookmarkOnline("OL1").added)
        val again = first.bookmarkOnline("OL1")
        assertEquals(0, again.added)
        assertEquals(1, again.alreadyThere)

        val reopened = repo(dir).library()
        assertEquals(1, reopened.bookmarkedItems().size)
        assertEquals(1, reopened.items.size)
    }

    @Test fun `bookmark all writes every result and never creates a folder`() {
        val r = repo(tmp())
        val drafts = listOf(onlineDraft("OL1"), onlineDraft("OL2"), onlineDraft("OL3"))

        val report = r.bookmarkAll(drafts)
        val library = r.library()

        assertEquals(3, report.added)
        assertEquals(0, library.profileFolders.size)
        assertEquals(3, library.bookmarkedItems().size)
        assertTrue(library.items.all { library.isBookmarked(it.id) })
        assertTrue(library.items.none { library.isWishlisted(it.id) })

        val second = r.bookmarkAll(drafts)
        assertEquals(0, second.added)
        assertEquals(3, second.alreadyThere)
    }

    @Test fun `offline matches cannot be bookmarked`() {
        val r = repo(tmp())
        try {
            r.bookmark(workbookDraft("C97"))
            fail("a workbook comparison result is not a bookmark")
        } catch (expected: InvalidItemException) {
            assertTrue(expected.message!!.contains("session", ignoreCase = true))
        }
    }
}


class MigrationTest {

    private fun v1Record() = WishlistRecord(
        id = "rec_1",
        name = "#1",
        createdAtMillis = 500,
        scanThumbName = "rec_1_s.jpg",
        matches = listOf(
            WishlistMatch("C97", 0.91f, 93.0f, "sheet1", "rec_1_0.jpg"),
            WishlistMatch("C98", 0.88f, 90.0f, "sheet1", "rec_1_1.jpg"),
        ),
    )

    @Test fun `a v1 record becomes one unfiled session, never a folder`() {
        val dir = tmp()
        File(dir, "records.json").writeText(WishlistRepository.encode(listOf(v1Record())), Charsets.UTF_8)

        val library = repo(dir).library()

        assertEquals(0, library.profileFolders.size)
        assertEquals(1, library.sessions.size)
        val session = library.sessions.single()
        assertNull("a v1 record has no folder", session.folderId)
        assertEquals(SessionKind.OFFLINE_MATCH, session.kind)
        assertEquals("#1", session.title)
        assertEquals(2, library.sessionItems(session.id).size)
        assertTrue(library.sessionItems(session.id).all { it.kind == ItemKind.WORKBOOK_MATCH })
        assertTrue(library.memberships.isEmpty())
    }

    @Test fun `the v1 payload is preserved and the file is rewritten once`() {
        val dir = tmp()
        File(dir, "records.json").writeText(WishlistRepository.encode(listOf(v1Record())), Charsets.UTF_8)

        val first = repo(dir)
        assertEquals(1, first.load().size)
        val text = File(dir, "records.json").readText(Charsets.UTF_8)
        assertTrue("the v1 view is kept for recovery", text.contains("\"legacy\""))
        assertTrue("the file is now v3", text.contains("\"version\":3"))

        val second = repo(dir)
        assertEquals(1, second.library().sessions.size)
        assertEquals(1, second.load().size)
    }

    @Test fun `a v2 profile that was really a scan becomes one session in that folder`() {
        val dir = tmp()
        val json = """
            {
              "version": 2,
              "collections": [
                {"id":"col_bookmarks","name":"Bookmarks","kind":"RESERVED","reserved":"BOOKMARKS","createdAt":1},
                {"id":"rec_1","name":"#1","kind":"PROFILE","createdAt":2}
              ],
              "items": [
                {"id":"itm_a","kind":"WORKBOOK_MATCH","title":"C97","cell":"C97","cosine":0.91,"percent":93.0,
                 "matchType":"WORKBOOK","matchTypeVerified":true,"createdAt":2,"fingerprint":"wb:rec_1:C97:0","thumb":"a.jpg"},
                {"id":"itm_b","kind":"WORKBOOK_MATCH","title":"C98","cell":"C98","cosine":0.88,"percent":90.0,
                 "matchType":"WORKBOOK","matchTypeVerified":true,"createdAt":3,"fingerprint":"wb:rec_1:C98:1","thumb":"b.jpg"},
                {"id":"itm_c","kind":"ONLINE_RESULT","title":"A bookmark","url":"https://example.com/c",
                 "matchType":"TEXT_QUERY","createdAt":4,"fingerprint":"url:https://example.com/c"}
              ],
              "memberships": [
                {"itemId":"itm_a","collectionId":"rec_1","addedAt":2},
                {"itemId":"itm_b","collectionId":"rec_1","addedAt":3},
                {"itemId":"itm_c","collectionId":"col_bookmarks","addedAt":4}
              ],
              "legacy": [
                {"id":"rec_1","name":"#1","createdAt":2,"scanThumb":"rec_1_s.jpg",
                 "matches":[{"cell":"C97"},{"cell":"C98"}]}
              ]
            }
        """.trimIndent()
        File(dir, "records.json").writeText(json, Charsets.UTF_8)

        val library = repo(dir).library()
        val folder = library.folder("rec_1")!!

        assertEquals(1, library.profileFolders.size)
        assertEquals(1, library.sessionsIn(folder.id).size)
        assertEquals(2, library.sessionItems(folder.id.let { library.sessionsIn(it).single().id }).size)
        assertTrue("workbook rows are no longer direct items", library.directItems(folder.id).isEmpty())
        assertEquals(1, library.bookmarkedItems().size)
    }

    @Test fun `a future version is quarantined instead of overwritten`() {
        val dir = tmp()
        val original = """{"version":99,"folders":[],"sessions":[]}"""
        File(dir, "records.json").writeText(original, Charsets.UTF_8)

        val library = repo(dir).library()

        assertEquals(0, library.sessions.size)
        assertTrue(File(dir, "records.json.corrupt").isFile)
        assertEquals(original, File(dir, "records.json.corrupt").readText(Charsets.UTF_8))
    }

    @Test fun `a directory that does not exist yet starts empty`() {
        val dir = File(tmp(), "not-yet-created")
        val library = repo(dir).library()

        assertEquals(0, library.sessions.size)
        assertEquals(1, library.folders.size)
    }
}


class FolderSessionTest {

    @Test fun `a folder can hold several sessions and is not capped at five results`() {
        val r = repo(tmp())
        val folder = r.createFolder("Reading")

        repeat(8) { round -> r.saveOffline(folder.id, (1..5).map { "C${round * 5 + it}" }) }

        val library = r.library()
        assertEquals(1, library.profileFolders.size)
        assertEquals(8, library.sessionCount(folder.id))
        assertEquals(40, library.itemCount(folder.id))
        assertEquals(8, library.sessionsIn(folder.id).size)
        assertTrue(library.directItems(folder.id).isEmpty())
    }

    @Test fun `a new session never overwrites the previous one and survives a restart`() {
        val dir = tmp()
        val r = repo(dir)
        val folder = r.createFolder("Reading")
        val first = r.saveOffline(folder.id, listOf("A1", "A2"))
        val second = r.saveOffline(folder.id, listOf("B1"))

        val reopened = repo(dir).library()
        assertEquals(2, reopened.sessionsIn(folder.id).size)
        assertEquals(2, reopened.sessionItems(first.sessionId!!).size)
        assertEquals(1, reopened.sessionItems(second.sessionId!!).size)
        assertNotEquals(first.sessionId, second.sessionId)
    }

    @Test fun `a scan never creates a folder by itself`() {
        val r = repo(tmp())
        assertEquals(0, r.library().profileFolders.size)
        // saving needs an explicit destination; there is no implicit folder
        try {
            r.saveOffline("col_missing", listOf("A1"))
            fail("a session needs an existing folder")
        } catch (expected: InvalidItemException) {
            // expected
        }
        assertEquals(0, r.library().profileFolders.size)
    }

    @Test fun `workbook matches are comparison results, not removable items`() {
        val r = repo(tmp())
        val folder = r.createFolder("Reading")
        val session = r.saveOffline(folder.id, listOf("C97", "C98", "C99"))

        val library = r.library()
        val items = library.sessionItems(session.sessionId!!)
        assertEquals(3, items.size)
        assertTrue(items.all { it.kind == ItemKind.WORKBOOK_MATCH })

        try {
            r.addDrafts(listOf(workbookDraft("C97")), folder.id)
            fail("offline matches cannot be direct items")
        } catch (expected: InvalidItemException) {
            assertTrue(expected.message!!.contains("session", ignoreCase = true))
        }

        val workbookItemId = items.first().id
        assertFalse(
            "a session result cannot be promoted to a direct item",
            r.addExistingItemToFolder(workbookItemId, folder.id),
        )
    }

    @Test fun `deleting a session keeps its bookmarked item and the folder`() {
        val r = repo(tmp())
        val folder = r.createFolder("Reading")
        val session = r.saveSession(
            SessionDraft(
                folderId = folder.id,
                kind = SessionKind.ONLINE_SEARCH,
                title = "Search",
                query = "the hobbit",
                results = listOf(onlineDraft("OL1"), onlineDraft("OL2")),
            ),
        )
        val item = r.library().sessionItems(session.sessionId!!).first()
        r.bookmark(onlineDraft(item.identifier!!))

        assertTrue(r.deleteSession(session.sessionId))

        val library = r.library()
        assertEquals(0, library.sessions.size)
        assertEquals(0, library.itemCount(folder.id))
        assertEquals(1, library.bookmarkedItems().size)
        assertNotNull(library.folder(folder.id))
    }

    @Test fun `deleting a folder keeps bookmarks and items shared with another folder`() {
        val r = repo(tmp())
        val one = r.createFolder("One")
        val two = r.createFolder("Two")
        val item = onlineDraft("OL1")
        r.bookmark(item)
        r.addDrafts(listOf(item), one.id)
        r.addDrafts(listOf(item), two.id)

        assertTrue(r.deleteFolder(one.id))

        val library = r.library()
        assertNull(library.folder(one.id))
        assertEquals(1, library.itemCount(two.id))
        assertEquals(1, library.bookmarkedItems().size)
    }

    @Test fun `a session can be moved to another folder without losing its results`() {
        val r = repo(tmp())
        val one = r.createFolder("One")
        val two = r.createFolder("Two")
        val session = r.saveOffline(one.id, listOf("A1", "A2"))

        assertTrue(r.moveSession(session.sessionId!!, two.id))

        val library = r.library()
        assertEquals(0, library.sessionCount(one.id))
        assertEquals(1, library.sessionCount(two.id))
        assertEquals(2, library.sessionItems(session.sessionId).size)
    }

    @Test fun `bookmarks are shown first inside their session`() {
        val r = repo(tmp())
        val folder = r.createFolder("Reading")
        val report = r.saveSession(
            SessionDraft(
                folderId = folder.id,
                kind = SessionKind.ONLINE_SEARCH,
                title = "Search",
                results = listOf(onlineDraft("OL1"), onlineDraft("OL2"), onlineDraft("OL3")),
            ),
        )
        r.bookmark(onlineDraft("OL3"))

        val items = r.library().sessionItems(report.sessionId!!)
        assertEquals(listOf("OL3", "OL1", "OL2"), items.map { it.identifier })
    }
}


class BookmarkWishlistWorkflowTest {

    /** Workflow A: add to a wishlist first, then bookmark the same online result. */
    @Test fun `workflow A - wishlist first, bookmark second, both survive`() {
        val dir = tmp()
        val r = repo(dir)
        val folder = r.createFolder("Reading")
        val session = r.saveSession(
            SessionDraft(
                folderId = folder.id,
                kind = SessionKind.ONLINE_SEARCH,
                title = "Search",
                results = listOf(onlineDraft("OL1"), onlineDraft("OL2")),
            ),
        )
        r.bookmark(onlineDraft("OL2"))

        val library = repo(dir).library()
        val item = library.items.first { it.identifier == "OL2" }
        assertTrue(library.isBookmarked(item.id))
        assertTrue(library.isWishlisted(item.id))
        assertEquals("OL2", library.sessionItems(session.sessionId!!).first().identifier)
        assertEquals(2, library.itemCount(folder.id))
        assertEquals(1, library.bookmarkedItems().size)
    }

    /** Workflow B: bookmark first, no folder is created, then add it explicitly. */
    @Test fun `workflow B - bookmark first, no folder, then add to one`() {
        val r = repo(tmp())
        val bookmark = r.bookmarkOnline("OL1")

        var library = r.library()
        assertEquals(0, library.profileFolders.size)
        assertNotNull(library.bookmarksFolder)
        val itemId = bookmark.itemIds.single()

        val folder = r.createFolder("Later")
        assertTrue(r.addExistingItemToFolder(itemId, folder.id))

        library = r.library()
        assertTrue(library.isBookmarked(itemId))
        assertTrue(library.isWishlisted(itemId))
        assertEquals(1, library.itemCount(folder.id))
        assertEquals(1, library.bookmarkedItems().size)
    }

    /** Workflow C: bookmark, restart (session gone), then add it to a new folder. */
    @Test fun `workflow C - bookmark survives a restart and joins a folder later`() {
        val dir = tmp()
        val first = repo(dir)
        val bookmark = first.bookmarkOnline("OL1")
        val itemId = bookmark.itemIds.single()

        val reopened = repo(dir)
        var library = reopened.library()
        assertEquals(0, library.profileFolders.size)
        assertEquals(1, library.bookmarkedItems().size)

        val folder = reopened.createFolder("Saved later")
        assertTrue(reopened.addExistingItemToFolder(itemId, folder.id))

        library = reopened.library()
        assertTrue(library.isBookmarked(itemId))
        assertTrue(library.isWishlisted(itemId))
        assertEquals(1, library.itemCount(folder.id))
    }

    /** Workflow C also works for a hand-typed bookmark, after the app was restarted. */
    @Test fun `a manual bookmark can join a folder after the form was closed`() {
        val dir = tmp()
        val first = repo(dir)
        first.bookmark(manualDraft(title = "Hand typed", url = "https://example.com/x"))
        val itemId = first.library().bookmarkedItems().single().id

        val reopened = repo(dir)
        val folder = reopened.createFolder("Doujinshi")
        assertTrue(reopened.addExistingItemToFolder(itemId, folder.id))

        val library = reopened.library()
        assertEquals(1, library.itemCount(folder.id))
        assertEquals("Hand typed", library.itemsInFolder(folder.id).single().title)
        assertTrue(library.isBookmarked(itemId))
    }
}


class MembershipAndBulkTest {

    @Test fun `bookmark membership and folder membership are independent`() {
        val r = repo(tmp())
        val folder = r.createFolder("Reading")
        val bookmark = r.bookmarkOnline("OL1")
        val itemId = bookmark.itemIds.single()
        r.addExistingItemToFolder(itemId, folder.id)

        assertTrue(r.removeBookmark(itemId))
        var library = r.library()
        assertFalse(library.isBookmarked(itemId))
        assertTrue(library.isWishlisted(itemId))
        assertEquals(1, library.itemCount(folder.id))

        r.bookmark(onlineDraft("OL1"))
        assertTrue(r.removeMembership(itemId, folder.id))
        library = r.library()
        assertTrue(library.isBookmarked(itemId))
        assertFalse(library.isWishlisted(itemId))
        assertEquals(0, library.itemCount(folder.id))
    }

    @Test fun `a bookmark in two folders is removed from one only`() {
        val r = repo(tmp())
        val one = r.createFolder("One")
        val two = r.createFolder("Two")
        val itemId = r.bookmarkOnline("OL1").itemIds.single()
        r.addExistingItemToFolder(itemId, one.id)
        r.addExistingItemToFolder(itemId, two.id)

        assertTrue(r.removeMembership(itemId, one.id))

        val library = r.library()
        assertEquals(0, library.itemCount(one.id))
        assertEquals(1, library.itemCount(two.id))
        assertTrue(library.isBookmarked(itemId))
    }

    @Test fun `an individual add adds exactly one item and never the whole session`() {
        val r = repo(tmp())
        val folder = r.createFolder("Reading")
        val session = r.saveSession(
            SessionDraft(
                folderId = folder.id,
                kind = SessionKind.ONLINE_SEARCH,
                title = "Search",
                results = listOf(onlineDraft("OL1"), onlineDraft("OL2"), onlineDraft("OL3")),
            ),
        )

        val report = r.addDrafts(listOf(onlineDraft("OL2")), folder.id)

        assertEquals(1, report.added)
        assertEquals(3, r.library().itemCount(folder.id))
        assertEquals(3, r.library().sessionItems(session.sessionId!!).size)
        assertEquals(1, r.library().directItems(folder.id).size)
    }

    @Test fun `bulk add and individual add do not perform each other`() {
        val r = repo(tmp())
        val folder = r.createFolder("Reading")
        val bulk = r.saveSession(
            SessionDraft(
                folderId = folder.id,
                kind = SessionKind.RESULT_PROFILE,
                title = "",
                results = listOf(workbookDraft("A1"), onlineDraft("OL1")),
            ),
        )
        val single = r.addDrafts(listOf(onlineDraft("OL9")), folder.id)

        assertEquals(2, bulk.itemIds.size)
        assertEquals(1, single.itemIds.size)
        assertEquals(3, r.library().itemCount(folder.id))
        assertEquals(2, r.library().sessionItems(bulk.sessionId!!).size)
    }

    @Test fun `duplicate drafts are skipped and the real counts are reported`() {
        val r = repo(tmp())
        val folder = r.createFolder("Reading")
        val drafts = listOf(onlineDraft("OL1"), onlineDraft("OL1"), onlineDraft("OL2"))

        val first = r.addDrafts(drafts, folder.id)
        assertEquals(2, first.added)
        assertEquals(1, first.alreadyThere)

        val again = r.addDrafts(drafts, folder.id)
        assertEquals(0, again.added)
        assertEquals(3, again.alreadyThere)
        assertEquals(2, r.library().itemCount(folder.id))
    }

    @Test fun `saving a session twice with the same results keeps one item but two sessions`() {
        val r = repo(tmp())
        val folder = r.createFolder("Reading")
        val one = r.saveOffline(folder.id, listOf("A1"))
        val two = r.saveOffline(folder.id, listOf("A1"))

        val library = r.library()
        assertEquals(2, library.sessions.size)
        assertEquals(1, library.items.size)
        assertEquals(2, library.entries.size)
        assertEquals(1, library.itemCount(folder.id))
        assertNotEquals(one.sessionId, two.sessionId)
    }
}


class CustomBookmarkTest {

    @Test fun `validation rejects what the app cannot open`() {
        assertEquals(BookmarkRules.ERROR_TITLE, BookmarkRules.validate("   ", "https://example.com/x"))
        assertEquals(BookmarkRules.ERROR_URL_MISSING, BookmarkRules.validate("Title", " "))
        assertEquals(BookmarkRules.ERROR_HTTPS, BookmarkRules.validate("Title", "http://example.com/x"))
        assertEquals(BookmarkRules.ERROR_URL_SHAPE, BookmarkRules.validate("Title", "https://"))
        assertNull(BookmarkRules.validate("Title", "https://example.com/x"))
    }

    @Test fun `the store refuses a non-https manual bookmark`() {
        val r = repo(tmp())
        try {
            r.bookmark(manualDraft(url = "http://example.com/x"))
            fail("an unopenable link must not be stored")
        } catch (expected: InvalidItemException) {
            assertEquals(BookmarkRules.ERROR_HTTPS, expected.message)
        }
    }

    @Test fun `a manual bookmark keeps exactly what was typed`() {
        val dir = tmp()
        val r = repo(dir)
        val report = r.bookmark(manualDraft())
        val item = r.library().item(report.itemIds.single())!!

        assertEquals(ItemKind.MANUAL_BOOKMARK, item.kind)
        assertEquals(MatchType.MANUAL, item.matchType)
        assertFalse("a typed field is not verified", item.matchTypeVerified)
        assertEquals("Hand typed", item.title)
        assertEquals("https://example.com/item", item.url)
        assertEquals("example.com", item.sourceDomain)
        assertEquals(listOf("Someone"), item.authors)
        assertEquals("ID-1", item.identifier)
        assertEquals("12.00", item.price)
        assertNull(item.cell)
        assertNull(item.percent)
        assertTrue(r.library().sessions.isEmpty())

        val reopened = repo(dir).library()
        assertEquals("Hand typed", reopened.bookmarkedItems().single().title)
    }
}


class ItemIdentityTest {

    @Test fun `a provider id wins over a url`() {
        assertEquals(
            ItemIdentity.fingerprintFor(providerKey = "OL27482W", url = "https://example.com/a"),
            ItemIdentity.fingerprintFor(providerKey = "OL27482W", url = "https://example.com/b"),
        )
    }

    @Test fun `urls are normalised without losing the destination`() {
        assertEquals(
            ItemIdentity.fingerprintFor(url = "https://Example.com/item/?utm_source=x&pid=7#frag"),
            ItemIdentity.fingerprintFor(url = "https://example.com/item?pid=7"),
        )
        assertNotEquals(
            ItemIdentity.fingerprintFor(url = "https://example.com/item?pid=7"),
            ItemIdentity.fingerprintFor(url = "https://example.com/item?pid=8"),
        )
    }

    @Test fun `editions are not merged and identical items are`() {
        assertNotEquals(
            ItemIdentity.fingerprintFor(url = "https://example.com/edition/1", title = "Book"),
            ItemIdentity.fingerprintFor(url = "https://example.com/edition/2", title = "Book"),
        )
        assertEquals(
            ItemIdentity.fingerprintFor(title = "The Hobbit"),
            ItemIdentity.fingerprintFor(title = "  the hobbit "),
        )
    }

    @Test fun `source domain is derived from the url`() {
        assertEquals("www.openlibrary.org", ItemIdentity.domainOf("https://www.openlibrary.org/works/OL1W"))
        assertNull(ItemIdentity.domainOf("not a url"))
    }
}

class SessionCacheTest {

    @Test fun `the fingerprint is the sha-256 of the image bytes`() {
        assertEquals(ImageFingerprint.of(byteArrayOf(1, 2, 3)), ImageFingerprint.of(byteArrayOf(1, 2, 3)))
        assertNotEquals(ImageFingerprint.of(byteArrayOf(1, 2, 3)), ImageFingerprint.of(byteArrayOf(1, 2, 4)))
    }

    @Test fun `the same image reuses the cached results`() {
        val cache = SessionCache<String>()
        assertNull(cache.select("cover-a"))
        val token = cache.token()
        cache.store("cover-a", "results")

        assertEquals("results", cache.select("cover-a"))
        assertTrue(cache.isCurrent(token))
    }

    @Test fun `another image invalidates the previous results`() {
        val cache = SessionCache<String>()
        cache.store("cover-a", "results")
        val before = cache.token()

        assertNull(cache.select("cover-b"))
        assertNull(cache.cached("cover-a"))
        assertFalse("a stale callback must be dropped", cache.isCurrent(before))
    }

    @Test fun `clearing the session cache touches no saved data`() {
        val r = repo(tmp())
        val item = r.bookmarkOnline("OL1").itemIds.single()
        val cache = SessionCache<String>()
        cache.store("cover-a", "results")

        cache.invalidate()

        val library = r.library()
        assertTrue(library.isBookmarked(item))
        assertNotNull(library.bookmarksFolder)
        assertNull(cache.fingerprint)
    }

    @Test fun `a stale generation token is dropped`() {
        val cache = SessionCache<String>()
        val token = cache.token()
        cache.invalidate()

        assertFalse(cache.isCurrent(token))
    }
}

class OnlineAggregatorTest {

    private fun result(
        id: String,
        url: String = "https://openlibrary.org/works/$id",
        title: String = "Book $id",
        provider: String = "ol",
    ) = ProductSearchResult(
        id = id,
        title = title,
        authors = listOf("Author"),
        publishedYear = 2001,
        publishers = listOf("Publisher"),
        pageCount = 100,
        languages = listOf("eng"),
        coverUrl = null,
        detailUrl = url,
        source = if (provider == "gb") "Google Books" else "Open Library",
        providerId = provider,
    )

    @Test fun `merge deduplicates and the count equals what is drawn`() {
        val merged = OnlineAggregator.merge(
            listOf(
                listOf(result("OL1"), result("OL2")),
                listOf(result("OL1", title = "Duplicate")),
            ),
            limit = 10,
        )
        assertEquals(2, merged.size)
        assertEquals(listOf("OL1", "OL2"), merged.map { it.id })
    }

    @Test fun `two providers with the same id are distinct records`() {
        val merged = OnlineAggregator.merge(
            listOf(listOf(result("X1", provider = "ol"), result("X1", provider = "gb"))),
            limit = 10,
        )
        assertEquals(2, merged.size)
        assertEquals("ol:x1", OnlineAggregator.itemKey(merged[0]))
        assertEquals("gb:x1", OnlineAggregator.itemKey(merged[1]))
    }

    @Test fun `a bookmarked result is promoted to the front of the live list`() {
        val one = result("OL1")
        val two = result("OL2")
        val merged = OnlineAggregator.merge(
            listOf(listOf(one, two)),
            promotedKeys = setOf(OnlineAggregator.itemKey(two)),
            limit = 10,
        )
        assertEquals(listOf("OL2", "OL1"), merged.map { it.id })
    }

    @Test fun `the limit is respected and never pads with placeholders`() {
        val merged = OnlineAggregator.merge(
            listOf((1..10).map { result("OL$it") }),
            limit = 3,
        )
        assertEquals(3, merged.size)
        assertEquals(0, OnlineAggregator.merge(emptyList(), limit = 5).size)
    }

    @Test fun `the match type is text, never visual`() {
        assertEquals(MatchType.TEXT_QUERY, OnlineAggregator.matchTypeOf(result("OL1")))
    }
}

