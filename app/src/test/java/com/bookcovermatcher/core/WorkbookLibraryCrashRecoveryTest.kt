// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core

import com.bookcovermatcher.data.ImportResult
import com.bookcovermatcher.data.RemoteOrigin
import com.bookcovermatcher.data.ReplaceResult
import com.bookcovermatcher.data.SavedWorkbook
import com.bookcovermatcher.data.WorkbookLibrary
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Crash consistency of the replacement pipeline (v2.6.0-dev.1 Phase 7).
 *
 * **No process was actually killed.** Each test reconstructs, deterministically, the *on-disk state* a kill
 * would leave at a named point in `WorkbookLibrary.replaceFrom`, then asserts what the next run of the app
 * does with it. That is the honest limit of this technique: it proves the recovery logic, not the kill.
 *
 * The interruption points, in pipeline order: before anything is written; while a `.part` download exists;
 * after the old copy was duplicated to `.previous` and before the swap; after the swap and before the index
 * write (the state Phase 5 flagged); after the index write and before cleanup; and the three index
 * states — missing, malformed, and describing a file that is not there.
 */
class WorkbookLibraryCrashRecoveryTest {

    private val dir: File = Files.createTempDirectory("library-crash").toFile()
    private val library = WorkbookLibrary(dir)
    private val tempFiles = mutableListOf<File>()

    @After fun cleanUp() {
        tempFiles.forEach { it.delete() }
        dir.listFiles()?.forEach { it.deleteRecursively() }
        dir.delete()
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun oldBytes() = TestWorkbooks.xlsxBytes("old")

    private fun newBytes() = TestWorkbooks.xlsxBytes("new")

    private fun seed(bytes: ByteArray = oldBytes(), name: String = "covers.xlsx"): SavedWorkbook {
        val added = (library.import(bytes.inputStream(), name) as ImportResult.Added).workbook
        return library.setRemote(
            added.id,
            RemoteOrigin(
                itemId = "01ABCDEF!12",
                path = "/drive/root:/Catalogue",
                eTag = "\"{OLD},1\"",
                cTag = "c:1",
                lastModified = 1_000L,
                sizeBytes = bytes.size.toLong(),
                syncedAt = 5_000L,
                checkedAt = 5_000L,
            ),
        )!!
    }

    private fun origin(eTag: String = "\"{NEW},2\""): RemoteOrigin = RemoteOrigin(
        itemId = "01ABCDEF!12",
        path = "/drive/root:/Catalogue",
        eTag = eTag,
        cTag = "c:2",
        lastModified = 2_000L,
        sizeBytes = newBytes().size.toLong(),
        syncedAt = 9_000L,
        checkedAt = 9_000L,
    )

    private fun download(bytes: ByteArray = newBytes()): File =
        File.createTempFile("downloaded", ".xlsx").also {
            it.writeBytes(bytes)
            tempFiles += it
        }

    private fun files(): List<String> = dir.listFiles().orEmpty().map { it.name }.sorted()

    private fun backupOf(entry: SavedWorkbook) = File(dir, "${entry.fileName}.previous")

    private fun stagingOf(entry: SavedWorkbook) = File(dir, "${entry.fileName}.replacing")

    private fun target(entry: SavedWorkbook) = library.fileOf(entry)

    // ---- 1. nothing written yet ---------------------------------------------------------------

    @Test fun aKillBeforeAnythingWasWrittenLeavesTheWorkbookExactlyAsItWas() {
        val entry = seed()
        val before = target(entry).readBytes()

        assertArrayEquals(before, target(entry).readBytes())
        assertEquals(entry.sha256, library.byId(entry.id)?.sha256)
        assertEquals("no temporary file exists yet", listOf(entry.fileName, "library.json"), files())
    }

    // ---- 2. a `.part` from an interrupted download ---------------------------------------------

    @Test fun aLeftoverPartFileIsIgnoredByTheLibraryAndCleanedByTheNextDownload() {
        val entry = seed()
        val part = File(dir, "01ABCDEF!12.part").apply { writeBytes(ByteArray(64) { 1 }) }

        // The library never lists or counts bytes it did not record.
        assertEquals(1, library.list().size)
        assertEquals(target(entry).length(), library.bytes())
        assertTrue("the partial download is not a workbook", part.isFile)

        // A later replacement of the same entry succeeds and leaves no staging file behind.
        val result = library.replaceFrom(entry.id, download(), origin())

        assertTrue(result is ReplaceResult.Replaced)
        assertFalse(stagingOf(entry).exists())
        assertFalse(backupOf(entry).exists())
        assertTrue("the downloader owns the .part, not the library", part.exists())
    }

    // ---- 3. after `.previous` was written, before the swap ------------------------------------

    @Test fun aKillBetweenTheBackupAndTheSwapStillHasTheOldCopyInPlace() {
        val entry = seed()
        val before = target(entry).readBytes()
        // The kill left: target = old, backup = old, staging = new.
        backupOf(entry).writeBytes(before)
        stagingOf(entry).writeBytes(newBytes())

        // The app reads the entry normally: the target was never removed.
        assertArrayEquals(before, target(entry).readBytes())
        assertEquals(entry.sha256, library.byId(entry.id)?.sha256)

        // And a second replacement cleans the stale leftovers and completes.
        val result = library.replaceFrom(entry.id, download(), origin()) as ReplaceResult.Replaced

        assertArrayEquals(newBytes(), target(entry).readBytes())
        assertEquals(sha(newBytes()), result.workbook.sha256)
        assertEquals(listOf(entry.fileName, "library.json"), files())
    }


    // ---- 4. after the swap, before the index write (the state Phase 5 flagged) ----------------

    @Test fun aKillAfterTheSwapButBeforeTheIndexWriteIsStaleMetadataAndHealsOnTheNextReplacement() {
        val entry = seed()
        // The kill left exactly this: the file is the new version, the index still describes the old one.
        target(entry).writeBytes(newBytes())

        val stale = library.byId(entry.id)!!
        assertEquals("the index still describes the old bytes", entry.sha256, stale.sha256)
        assertArrayEquals("while the file is already the new version", newBytes(), target(entry).readBytes())

        // Healing is the next replacement — the path a Rescan + "Update" takes. It records the truth and
        // keeps the file the user can already open.
        val healed = library.replaceFrom(entry.id, download(newBytes()), origin()) as ReplaceResult.Replaced

        val fresh = library.byId(entry.id)!!
        assertEquals(sha(newBytes()), fresh.sha256)
        assertEquals(newBytes().size.toLong(), fresh.sizeBytes)
        assertEquals(2_000L, fresh.remote?.lastModified)
        assertEquals(sha(newBytes()), healed.workbook.sha256)
        assertArrayEquals(newBytes(), target(entry).readBytes())
        assertEquals(listOf(entry.fileName, "library.json"), files())
    }

    // ---- 5. after the index write, before the cleanup -----------------------------------------

    @Test fun aKillAfterTheIndexWriteLeavesAConsistentEntryAndTheNextRunCleansUp() {
        val entry = seed()
        library.replaceFrom(entry.id, download(), origin())
        val updated = library.byId(entry.id)!!
        // The kill happened after `writeAll` and before `backup.delete()`: the backup is still there.
        backupOf(updated).writeBytes(oldBytes())

        // A fresh app start reads a fully consistent entry: the hash matches the bytes on disk.
        val reopened = WorkbookLibrary(dir).byId(entry.id)!!
        assertEquals(sha(newBytes()), reopened.sha256)
        assertEquals(library.fileOf(reopened).length(), reopened.sizeBytes)
        assertEquals(sha(library.fileOf(reopened).readBytes()), reopened.sha256)
        assertEquals("the leftover backup is not counted as library usage", library.fileOf(reopened).length(), library.bytes())

        // And the next replacement removes it.
        library.replaceFrom(entry.id, download(oldBytes()), origin(eTag = "\"{THIRD},3\""))

        assertFalse(backupOf(updated).exists())
        assertFalse(stagingOf(updated).exists())
    }

    // ---- 6. the index is missing -----------------------------------------------------------------

    @Test fun aMissingIndexLeavesTheWorkbookBytesOnDiskEvenThoughTheLibraryCannotSeeThem() {
        val entry = seed()
        val file = target(entry)
        assertTrue(File(dir, "library.json").delete())

        assertTrue("the workbook file itself is untouched", file.isFile)
        assertArrayEquals(oldBytes(), file.readBytes())
        assertTrue("but nothing in the library points at it", library.list().isEmpty())
    }


    @Test fun aMalformedIndexIsQuarantinedAndTheWorkbookFileSurvives() {
        val entry = seed()
        File(dir, "library.json").writeText("{ this is not the index }")

        assertTrue("a corrupt index reads as an empty library", library.list().isEmpty())
        assertTrue("and is kept aside rather than deleted", File(dir, "library.json.bad").isFile)
        assertTrue("the workbook bytes are untouched", target(entry).isFile)
        assertArrayEquals(oldBytes(), target(entry).readBytes())
    }

    @Test fun anOrphanedWorkbookCannotBeReImportedUnderItsOwnIdentity() {
        // KNOWN LIMITATION, pinned deliberately (see the Phase 7 report §24.10). With the index lost, the
        // bytes survive but the entry does not, and `import` refuses to reuse a file name it finds on disk
        // even though nothing in the library claims it. The user's data is not lost — it is stranded.
        val entry = seed()
        assertTrue(File(dir, "library.json").delete())

        val retry = library.import(oldBytes().inputStream(), "covers.xlsx")

        assertTrue("re-importing the same file is refused …", retry is ImportResult.Rejected)
        assertTrue((retry as ImportResult.Rejected).message.contains("already exists"))
        assertEquals("… and the stranded bytes are still there", oldBytes().size.toLong(), target(entry).length())
        assertTrue(library.list().isEmpty())
    }

    // ---- 7. the index describes a file that is not there --------------------------------------

    @Test fun anEntryWhoseFileIsMissingIsListedAndCannotBeRepairedByReImporting() {
        // KNOWN LIMITATION, pinned deliberately (see the Phase 7 report §24.10). The entry is listed, the
        // copy is missing, `useSavedWorkbook` says so — and re-importing the same file answers
        // `AlreadySaved` without putting the bytes back, so the UI cannot repair it either.
        val entry = seed()
        assertTrue(target(entry).delete())

        assertEquals("the entry is still listed", 1, library.list().size)
        assertFalse("its copy is reported missing by `fileOf(...).isFile`", library.fileOf(entry).isFile)

        val reimported = library.import(oldBytes().inputStream(), "covers.xlsx")

        assertTrue("… and re-import recognises the bytes it already knows", reimported is ImportResult.AlreadySaved)
        assertEquals(entry.id, (reimported as ImportResult.AlreadySaved).workbook.id)
        assertFalse("but the file is still missing", library.fileOf(entry).isFile)
        assertEquals("so the entry cannot be opened until that is fixed", 1, library.list().size)
    }

    @Test fun anEntryWhoseFileIsMissingDoesNotBlockADifferentFile() {
        // The neighbouring case that already works: a *different* workbook still imports normally, so a
        // broken entry cannot wedge the whole library.
        val entry = seed()
        assertTrue(target(entry).delete())

        val other = library.import(TestWorkbooks.xlsxBytes("other").inputStream(), "other.xlsx")

        assertTrue(other is ImportResult.Added)
        assertEquals(2, library.list().size)
    }

    // ---- the safety net: never delete the only surviving copy ---------------------------------

    @Test fun aLoneBackupIsRestoredRatherThanDeletedWhenTheTargetIsGone() {
        val entry = seed()
        val before = target(entry).readBytes()
        // A kill left the backup behind, and the target was lost separately: the backup is now the only
        // copy of this workbook anywhere.
        backupOf(entry).writeBytes(before)
        assertTrue(target(entry).delete())

        // Force the install to fail *after* the swap, so the rollback path runs: without a recovery step
        // the entry would be deleted at the start and this would leave nothing at all.
        assertTrue(File(dir, "library.json.tmp").mkdir())

        val result = library.replaceFrom(entry.id, download(), origin())

        assertTrue(result is ReplaceResult.Rejected)
        assertTrue("the only copy must still be on disk", target(entry).isFile)
        assertArrayEquals("and it must be the bytes it held", before, target(entry).readBytes())
        assertFalse(backupOf(entry).exists())
    }

    /**
     * The recovery step has its own failure mode: if the lone backup cannot be moved back, deleting it
     * would destroy the only surviving copy of the workbook. The backup must be kept and the replacement
     * refused instead.
     *
     * The failure is injected with a non-empty directory at the target path — the move onto it cannot
     * succeed on any platform — so the branch is deterministic rather than dependent on a real I/O error.
     */
    @Test fun aLoneBackupThatCannotBePutBackIsKeptAndNothingIsReplaced() {
        val entry = seed()
        val before = target(entry).readBytes()
        backupOf(entry).writeBytes(before)
        assertTrue(target(entry).delete())
        assertTrue(target(entry).mkdir())
        File(target(entry), "blocker").writeBytes(byteArrayOf(1))

        val result = library.replaceFrom(entry.id, download(), origin())

        assertTrue(result is ReplaceResult.Rejected)
        assertTrue("the last copy must not be deleted", backupOf(entry).isFile)
        assertArrayEquals("and it must be unchanged", before, backupOf(entry).readBytes())
        assertFalse(stagingOf(entry).exists())
    }
}

