// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core

import com.bookcovermatcher.data.ImportResult
import com.bookcovermatcher.data.RemoteOrigin
import com.bookcovermatcher.data.ReplaceResult
import com.bookcovermatcher.data.WorkbookLibrary
import com.bookcovermatcher.data.WorkbookOrigin
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library's replacement primitive (v2.6.0-dev.1 Phase 5).
 *
 * This is where "the old workbook is never damaged" stops being a promise and becomes an assertion: every
 * failure case here checks the old bytes **byte for byte** as well as the metadata.
 */
class WorkbookLibraryReplaceTest {

    private val dir: File = Files.createTempDirectory("library-replace").toFile()
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

    /** Imports the "old" workbook and returns its entry. */
    private fun seed(): com.bookcovermatcher.data.SavedWorkbook =
        (library.import(oldBytes().inputStream(), "covers.xlsx") as ImportResult.Added).workbook

    private fun origin(eTag: String = "\"{NEW},1\""): RemoteOrigin = RemoteOrigin(
        itemId = "01ABCDEF!12",
        path = "/drive/root:/Catalogue",
        eTag = eTag,
        cTag = "c:1",
        lastModified = 1_794_141_296_000L,
        sizeBytes = newBytes().size.toLong(),
        syncedAt = 9_000L,
        checkedAt = 9_000L,
    )

    /** The download lives outside the library directory, exactly as it does in the app. */
    private fun temp(bytes: ByteArray): File =
        File.createTempFile("replacement", ".xlsx").also {
            it.writeBytes(bytes)
            tempFiles += it
        }

    private fun filesInDir(): List<String> = dir.listFiles().orEmpty().map { it.name }.sorted()

    @Test fun aNewVersionReplacesTheBytesAndTheMetadataTogether() {
        val entry = seed()
        val fresh = newBytes()

        val result = library.replaceFrom(entry.id, temp(fresh), origin()) as ReplaceResult.Replaced

        assertEquals("the entry keeps its identity", entry.id, result.workbook.id)
        assertEquals("and its name", "covers.xlsx", result.workbook.displayName)
        assertEquals("and its import time", entry.importedAt, result.workbook.importedAt)
        assertEquals("and its file reference", entry.fileName, result.workbook.fileName)

        assertEquals("the new hash is the cache key", sha(fresh), result.workbook.sha256)
        assertEquals(fresh.size.toLong(), result.workbook.sizeBytes)
        assertEquals(WorkbookOrigin.ONEDRIVE, result.workbook.origin)
        assertEquals("\"{NEW},1\"", result.workbook.remote?.eTag)
        assertEquals("c:1", result.workbook.remote?.cTag)
        assertEquals(9_000L, result.workbook.remote?.syncedAt)

        assertArrayEquals("the file on disk is the new version", fresh, library.fileOf(result.workbook).readBytes())
        assertEquals("nothing else is left behind", listOf("${entry.fileName}", "library.json"), filesInDir())
    }

    @Test fun theOldParseCountsAreDroppedBecauseTheyDescribedTheOldBytes() {
        val entry = seed()
        library.setSummary(entry.id, sheets = 3, comments = 7, images = 2)

        val result = library.replaceFrom(entry.id, temp(newBytes()), origin()) as ReplaceResult.Replaced

        assertNull(result.workbook.sheetCount)
        assertNull(result.workbook.commentCount)
        assertNull(result.workbook.imageCount)
    }

    @Test fun aFreshLibraryReadAgreesWithTheFileItPointsAt() {
        val entry = seed()
        val fresh = newBytes()

        library.replaceFrom(entry.id, temp(fresh), origin())

        // What "atomic" has to mean from outside: re-reading the index describes exactly the bytes on disk.
        val reopened = WorkbookLibrary(dir).byId(entry.id)
        assertNotNull(reopened)
        assertEquals(sha(library.fileOf(reopened!!).readBytes()), reopened.sha256)
        assertEquals(library.fileOf(reopened).length(), reopened.sizeBytes)
    }

    @Test fun identicalBytesOnlyMoveTheMetadata() {
        val entry = seed()
        val before = library.fileOf(entry).readBytes()

        val result = library.replaceFrom(entry.id, temp(oldBytes()), origin()) as ReplaceResult.SameContent

        assertEquals("the cache key does not change", entry.sha256, result.workbook.sha256)
        assertEquals("the version is now the remote one", "\"{NEW},1\"", result.workbook.remote?.eTag)
        assertArrayEquals("the copy is untouched", before, library.fileOf(entry).readBytes())
        assertEquals(listOf("${entry.fileName}", "library.json"), filesInDir())
    }

    @Test fun anInvalidReplacementIsRefusedAndTheOldCopyIsUntouched() {
        val entry = seed()
        val before = library.fileOf(entry).readBytes()

        val result = library.replaceFrom(entry.id, temp("<html>not a workbook</html>".toByteArray()), origin())

        assertTrue(result is ReplaceResult.Rejected)
        assertArrayEquals("the old bytes are intact", before, library.fileOf(entry).readBytes())
        assertEquals("the metadata still describes the old bytes", entry.sha256, library.byId(entry.id)?.sha256)
        assertEquals("no staging file survives", listOf("${entry.fileName}", "library.json"), filesInDir())
    }

    @Test fun anEmptyReplacementIsRefused() {
        val entry = seed()
        val before = library.fileOf(entry).readBytes()

        val result = library.replaceFrom(entry.id, temp(ByteArray(0)), origin())

        assertTrue(result is ReplaceResult.Rejected)
        assertArrayEquals(before, library.fileOf(entry).readBytes())
    }

    @Test fun aMissingNewFileIsRefusedWithoutTouchingAnything() {
        val entry = seed()

        val result = library.replaceFrom(entry.id, File(dir, "not-there.xlsx"), origin())

        assertTrue(result is ReplaceResult.Rejected)
        assertEquals(entry.sha256, library.byId(entry.id)?.sha256)
        assertEquals(listOf("${entry.fileName}", "library.json"), filesInDir())
    }

    @Test fun anUnknownIdIsNotFoundRatherThanCreated() {
        seed()

        assertEquals(ReplaceResult.NotFound, library.replaceFrom("no-such-id", temp(newBytes()), origin()))
    }

    @Test fun whenTheIndexCannotBeWrittenTheOldBytesArePutBack() {
        val entry = seed()
        val before = library.fileOf(entry).readBytes()
        // A directory where the index temp file belongs: every write fails, deterministically, on every
        // platform — the same effect as a full disk or a read-only profile, without depending on either.
        val blocked = File(dir, "library.json.tmp")
        assertTrue(blocked.mkdir())

        val result = library.replaceFrom(entry.id, temp(newBytes()), origin())

        assertTrue("the write failure is reported, not swallowed", result is ReplaceResult.Rejected)
        assertArrayEquals(
            "the old bytes are restored, so the file and the index still agree",
            before,
            library.fileOf(entry).readBytes(),
        )
        assertEquals(entry.sha256, library.byId(entry.id)?.sha256)
        assertEquals(entry.sizeBytes, library.byId(entry.id)?.sizeBytes)
        assertFalse("no backup is left behind", File(dir, "${entry.fileName}.previous").exists())
        assertFalse("no staging file is left behind", File(dir, "${entry.fileName}.replacing").exists())
    }

    @Test fun replacingOneWorkbookLeavesEveryOtherEntryAlone() {
        val first = seed()
        val second = (library.import(TestWorkbooks.xlsxBytes("other").inputStream(), "other.xlsm") as ImportResult.Added).workbook

        library.replaceFrom(first.id, temp(newBytes()), origin())

        val untouched = library.byId(second.id)
        assertNotNull(untouched)
        assertEquals(second.sha256, untouched!!.sha256)
        assertArrayEquals(
            TestWorkbooks.xlsxBytes("other"),
            library.fileOf(untouched).readBytes(),
        )
        assertEquals("both entries are still listed", 2, library.list().size)
    }
}

