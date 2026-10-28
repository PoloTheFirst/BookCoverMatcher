package com.bookcovermatcher.core

import com.bookcovermatcher.core.wishlist.ItemDraft
import com.bookcovermatcher.core.wishlist.ItemKind
import com.bookcovermatcher.core.wishlist.WishlistRepository
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Editing a bookmark updates the same record; the image follows save/cancel rules. */
class WishlistBookmarkEditTest {

    private fun repo(): WishlistRepository = WishlistRepository(File(Files.createTempDirectory("bm").toFile(), "w"))

    private fun draft(title: String, url: String) = ItemDraft(
        kind = ItemKind.MANUAL_BOOKMARK,
        title = title,
        url = url,
    )

    private fun WishlistRepository.saved(id: String) = library().items.first { it.id == id }

    @Test fun editingKeepsTheIdentityAndEveryMembership() {
        val repo = repo()
        val report = repo.bookmark(draft("First", "https://example.com/a"))
        val id = report.itemIds.single()
        val before = repo.saved(id)
        val membershipsBefore = repo.library().memberships.count { it.itemId == id }

        assertTrue(repo.updateBookmark(id, draft("Second", "https://example.com/b")))

        val after = repo.saved(id)
        assertEquals(id, after.id)
        assertEquals("Second", after.title)
        assertEquals("https://example.com/b", after.url)
        assertEquals(before.createdAtMillis, after.createdAtMillis)
        assertEquals(before.fingerprint, after.fingerprint)
        assertEquals(membershipsBefore, repo.library().memberships.count { it.itemId == id })
    }

    @Test fun editingWithoutAnImageKeepsTheExistingOne() {
        val repo = repo()
        val id = repo.bookmark(draft("First", "https://example.com/a")).itemIds.single()
        val image = repo.stageThumb(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), "png")
        assertTrue(repo.updateBookmark(id, draft("First", "https://example.com/a"), thumbName = image))

        assertTrue(repo.updateBookmark(id, draft("Renamed", "https://example.com/a")))

        assertEquals(image, repo.saved(id).thumbName)
        assertTrue(repo.thumbFile(image).isFile)
    }

    @Test fun aReplacementDeletesTheOldImageOnlyAfterTheUpdate() {
        val repo = repo()
        val id = repo.bookmark(draft("First", "https://example.com/a")).itemIds.single()
        val first = repo.stageThumb(byteArrayOf(1, 2, 3), "png")
        val second = repo.stageThumb(byteArrayOf(4, 5, 6), "png")
        repo.updateBookmark(id, draft("First", "https://example.com/a"), thumbName = first)

        repo.updateBookmark(id, draft("First", "https://example.com/a"), thumbName = second)

        assertEquals(second, repo.saved(id).thumbName)
        assertFalse("the replaced file is gone", repo.thumbFile(first).isFile)
        assertTrue(repo.thumbFile(second).isFile)
    }

    @Test fun removingAnImageClearsTheRecordAndDeletesTheFile() {
        val repo = repo()
        val id = repo.bookmark(draft("First", "https://example.com/a")).itemIds.single()
        val image = repo.stageThumb(byteArrayOf(7, 8, 9), "png")
        repo.updateBookmark(id, draft("First", "https://example.com/a"), thumbName = image)

        repo.updateBookmark(id, draft("First", "https://example.com/a"), clearThumb = true)

        assertNull(repo.saved(id).thumbName)
        assertFalse(repo.thumbFile(image).isFile)
    }

    @Test fun aStagedImageThatIsNeverSavedCanBeDiscarded() {
        val repo = repo()
        val image = repo.stageThumb(byteArrayOf(1, 2, 3), "png")
        assertTrue(repo.thumbFile(image).isFile)

        repo.discardThumb(image)

        assertFalse(repo.thumbFile(image).isFile)
    }

    @Test fun discardingAnImageStillUsedByABookmarkKeepsTheFile() {
        val repo = repo()
        val id = repo.bookmark(draft("First", "https://example.com/a")).itemIds.single()
        val image = repo.stageThumb(byteArrayOf(1, 2, 3), "png")
        repo.updateBookmark(id, draft("First", "https://example.com/a"), thumbName = image)

        repo.discardThumb(image)

        assertTrue(repo.thumbFile(image).isFile)
        assertNotNull(repo.saved(id).thumbName)
    }

    @Test fun cancellingAnEditLeavesTheStoredBookmarkUntouched() {
        val repo = repo()
        val id = repo.bookmark(draft("First", "https://example.com/a")).itemIds.single()
        val before = repo.saved(id)
        // A cancelled form never calls updateBookmark; a staged image is merely discarded.
        val staged = repo.stageThumb(byteArrayOf(9, 9), "png")
        repo.discardThumb(staged)

        val after = repo.saved(id)
        assertEquals(before.title, after.title)
        assertEquals(before.url, after.url)
        assertNull(after.thumbName)
    }

    @Test fun anExistingBookmarkWithoutAnImageStillLoads() {
        val repo = repo()
        val id = repo.bookmark(draft("No image", "https://example.com/a")).itemIds.single()

        assertNull(repo.saved(id).thumbName)
        assertTrue(repo.library().items.any { it.id == id })
    }
}
