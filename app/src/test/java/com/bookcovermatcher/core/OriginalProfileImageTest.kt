// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core

import com.bookcovermatcher.core.wishlist.ItemDraft
import com.bookcovermatcher.core.wishlist.ItemKind
import com.bookcovermatcher.core.wishlist.MatchType
import com.bookcovermatcher.core.wishlist.SessionDraft
import com.bookcovermatcher.core.wishlist.SessionKind
import com.bookcovermatcher.core.wishlist.WishlistRepository
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The original captured/uploaded image of a saved result profile.
 *
 * The defect this pins: a profile kept only a thumbnail (`scanThumb`, a 44×58 row image) and, for
 * online result profiles, no image at all — so opening a profile inside its folder showed nothing. The
 * fix stores the original as its own file in `originals/`, separate from the thumbnail, with a
 * nullable reference so records written before v2.5.0 keep loading.
 */
class OriginalProfileImageTest {

    private fun tmp(): File = Files.createTempDirectory("wl-orig").toFile()
    private fun jpeg(tag: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), tag.toByte(), 0x00)

    private fun save(
        repo: WishlistRepository,
        folderId: String,
        cell: String,
        tag: Int,
        original: ByteArray?,
        kind: SessionKind = SessionKind.RESULT_PROFILE,
    ) {
        repo.saveSession(
            SessionDraft(
                folderId = folderId,
                kind = kind,
                title = "",
                query = "tifa",
                scanThumbJpeg = jpeg(tag),
                originalJpeg = original,
                results = listOf(
                    ItemDraft(
                        kind = ItemKind.ONLINE_RESULT,
                        title = cell,
                        url = "https://example.test/$cell",
                        matchType = MatchType.TEXT_QUERY,
                        matchTypeVerified = true,
                    ),
                ),
            ),
        )
    }

    @Test
    fun aSavedProfileKeepsItsOriginalImageInItsOwnFile() {
        val repo = WishlistRepository(tmp())
        val folder = repo.createFolder("Doujinshi")
        val original = jpeg(42)
        save(repo, folder.id, "RJ01234567", tag = 7, original = original)

        val session = repo.library().sessionsIn(folder.id).single()
        val name = session.originalImageName
        assertNotNull("the profile must reference an original image", name)
        val file = repo.originalFile(name!!)
        assertTrue("the original image file must exist", file.isFile)
        assertArrayEquals(original, file.readBytes())

        // Separate from the row thumbnail: different file, different bytes.
        val thumbName = session.scanThumbName
        assertNotNull(thumbName)
        assertFalse("original and thumbnail must not be the same file", name == thumbName)
        assertArrayEquals(jpeg(7), repo.thumbFile(thumbName!!).readBytes())
        assertEquals("originals live in their own directory", "originals", file.parentFile!!.name)
    }

    @Test
    fun theOriginalImageSurvivesAReopenedRepository() {
        val dir = tmp()
        val repo = WishlistRepository(dir)
        val folder = repo.createFolder("Doujinshi")
        save(repo, folder.id, "RJ1", tag = 1, original = jpeg(11))

        // A new instance on the same root is what a process restart looks like to the store.
        val reopened = WishlistRepository(dir)
        val session = reopened.library().sessionsIn(folder.id).single()
        val file = reopened.originalFile(session.originalImageName!!)
        assertTrue("the image is still on disk after reload", file.isFile)
        assertArrayEquals(jpeg(11), file.readBytes())
    }

    @Test
    fun twoProfilesNeverShareOrOverwriteOneAnothersImage() {
        // An explicit clock keeps the reading order of the two profiles deterministic.
        var now = 1_000L
        val repo = WishlistRepository(tmp(), clock = { now++ })
        val folder = repo.createFolder("Doujinshi")
        save(repo, folder.id, "RJ1", tag = 1, original = jpeg(11))
        save(repo, folder.id, "RJ2", tag = 2, original = jpeg(22))

        val sessions = repo.library().sessionsIn(folder.id)
        assertEquals(2, sessions.size)
        val names = sessions.mapNotNull { it.originalImageName }
        assertEquals("each profile owns its own file", 2, names.distinct().size)

        // Match by the item each profile holds: `sessionsIn` is newest-first, not insertion order.
        val library = repo.library()
        val byItem = sessions.associateBy { session -> library.sessionItems(session.id).single().title }
        assertArrayEquals(
            jpeg(11),
            repo.originalFile(byItem.getValue("RJ1").originalImageName!!).readBytes(),
        )
        assertArrayEquals(
            jpeg(22),
            repo.originalFile(byItem.getValue("RJ2").originalImageName!!).readBytes(),
        )
    }

    @Test
    fun deletingAProfileRemovesItsOwnImagesAndNothingElse() {
        val repo = WishlistRepository(tmp())
        val folder = repo.createFolder("Doujinshi")
        save(repo, folder.id, "RJ1", tag = 1, original = jpeg(11))
        save(repo, folder.id, "RJ2", tag = 2, original = jpeg(22))

        val doomed = repo.library().sessionsIn(folder.id).first()
        val kept = repo.library().sessionsIn(folder.id).last()
        val doomedOriginal = repo.originalFile(doomed.originalImageName!!)
        val doomedThumb = repo.thumbFile(doomed.scanThumbName!!)
        val keptOriginal = repo.originalFile(kept.originalImageName!!)

        assertTrue(repo.deleteSession(doomed.id))

        assertFalse("the deleted profile's image is gone", doomedOriginal.exists())
        assertFalse("its thumbnail is gone too", doomedThumb.exists())
        assertTrue("the other profile keeps its image", keptOriginal.isFile)
        assertEquals(1, repo.library().sessionsIn(folder.id).size)
    }

    @Test
    fun aProfileSavedWithoutAnOriginalImageStillLoads() {
        val repo = WishlistRepository(tmp())
        val folder = repo.createFolder("Imported")
        save(repo, folder.id, "RJ1", tag = 1, original = null)

        val session = repo.library().sessionsIn(folder.id).single()
        assertNull("no image means no reference, not a placeholder file", session.originalImageName)
        assertNotNull(session.scanThumbName)
    }

    @Test
    fun aRecordsFileFromBeforeTheFieldStillLoads() {
        val dir = tmp()
        val repo = WishlistRepository(dir)
        val folder = repo.createFolder("Doujinshi")
        save(repo, folder.id, "RJ1", tag = 1, original = jpeg(11))

        // Drop the key entirely, the way a v2.4.0 file simply does not have it. The comma before it
        // goes too, so the JSON stays valid — this is the decoding path that must stay tolerant.
        val records = File(dir, "records.json")
        val legacy = records.readText()
            .replace(Regex(",\"originalImage\":(\"[^\"]*\"|null)"), "")
            .replace(Regex("\"originalImage\":(\"[^\"]*\"|null),?"), "")
        assertFalse("the key must be gone", legacy.contains("originalImage"))
        records.writeText(legacy.replace(",,", ","))

        val session = WishlistRepository(dir).library().sessionsIn(folder.id).single()
        assertNull("an older record has no original image, and that is not an error", session.originalImageName)
    }

    @Test
    fun aStorageFailureLeavesTheLibraryReadable() {
        val dir = tmp()
        val repo = WishlistRepository(dir)
        val folder = repo.createFolder("Doujinshi")
        // Block the originals directory by putting a file where it belongs.
        File(dir, "originals").writeText("not a directory")

        val failed = runCatching { save(repo, folder.id, "RJ1", tag = 1, original = jpeg(11)) }
        assertTrue("the save must fail rather than record a dangling reference", failed.isFailure)

        // The store is still readable and holds no half-saved session.
        val library = repo.library()
        assertTrue(library.sessionsIn(folder.id).isEmpty())
        assertNotNull(library.folder(folder.id))
    }

    @Test
    fun copyingAFolderGivesTheCopyItsOwnImageFile() {
        val repo = WishlistRepository(tmp())
        val folder = repo.createFolder("Doujinshi")
        save(repo, folder.id, "RJ1", tag = 1, original = jpeg(11))

        repo.copyFolders(listOf(folder.id))

        val library = repo.library()
        val copy = library.folders.single { it.id != folder.id && it.name.startsWith("Doujinshi") }
        val source = library.sessionsIn(folder.id).single()
        val copied = library.sessionsIn(copy.id).single()
        assertNotNull(copied.originalImageName)
        assertFalse(
            "a copy must not share the source file name",
            copied.originalImageName == source.originalImageName,
        )
        val copyFile = repo.originalFile(copied.originalImageName!!)
        assertTrue(copyFile.isFile)
        assertArrayEquals(jpeg(11), copyFile.readBytes())

        // Deleting the original folder leaves the copy's picture intact.
        repo.deleteFolder(folder.id)
        assertTrue("the copy keeps its own image after the source folder is deleted", copyFile.isFile)
    }
}
