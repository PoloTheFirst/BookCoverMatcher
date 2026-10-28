package com.bookcovermatcher.core

import com.bookcovermatcher.data.ImportResult
import com.bookcovermatcher.data.WorkbookLibrary
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The saved-workbook library: copies, identity, rename, pin, recent, removal, recovery. */
class WorkbookLibraryTest {

    private val dir: File = Files.createTempDirectory("workbook-library").toFile()

    @After fun cleanUp() {
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    /** Bytes that pass the import validation (ZIP signature) with distinguishable content. */
    private fun workbookBytes(tag: String): ByteArray =
        byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "[Content_Types].xml $tag".toByteArray()

    private fun import(library: WorkbookLibrary, bytes: ByteArray, name: String) =
        library.import(ByteArrayInputStream(bytes), name, now = 1_000L)

    @Test fun aValidWorkbookIsCopiedByteForByte() {
        val library = WorkbookLibrary(dir)
        val bytes = workbookBytes("one")

        val result = import(library, bytes, "同人誌.xlsm")

        assertTrue(result is ImportResult.Added)
        val entry = (result as ImportResult.Added).workbook
        assertEquals("同人誌.xlsm", entry.displayName)
        assertEquals("xlsm", entry.format)
        assertEquals(bytes.size.toLong(), entry.sizeBytes)
        assertArrayEquals("the copy must be byte-identical", bytes, library.fileOf(entry).readBytes())
        assertEquals(1, library.list().size)
    }

    @Test fun importingTheSameBytesTwiceDoesNotCopyThemAgain() {
        val library = WorkbookLibrary(dir)
        val bytes = workbookBytes("same")

        import(library, bytes, "a.xlsx")
        val second = import(library, bytes, "a.xlsx")

        assertTrue(second is ImportResult.AlreadySaved)
        assertEquals(1, library.list().size)
        assertEquals(bytes.size.toLong(), library.bytes())
    }

    @Test fun theSameFilenameWithDifferentContentGetsItsOwnEntry() {
        val library = WorkbookLibrary(dir)
        import(library, workbookBytes("first"), "book.xlsx")
        import(library, workbookBytes("second"), "book.xlsx")

        val entries = library.list()
        assertEquals(2, entries.size)
        assertNotEquals(entries[0].id, entries[1].id)
        assertNotEquals(entries[0].fileName, entries[1].fileName)
        val copies = entries.map { library.fileOf(it).readBytes() }
        assertTrue("the first copy must survive", copies.any { it.contentEquals(workbookBytes("first")) })
        assertTrue("the second copy must exist", copies.any { it.contentEquals(workbookBytes("second")) })
    }

    @Test fun nonWorkbookFilesAreRejectedWithoutLeavingAnything() {
        val library = WorkbookLibrary(dir)

        assertTrue(import(library, "plain text".toByteArray(), "notes.txt") is ImportResult.Rejected)
        assertTrue(import(library, "not a zip".toByteArray(), "fake.xlsx") is ImportResult.Rejected)

        assertTrue(library.list().isEmpty())
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".xlsx") || it.name.endsWith(".tmp") })
    }

    @Test fun renamingChangesOnlyTheDisplayName() {
        val library = WorkbookLibrary(dir)
        val entry = (import(library, workbookBytes("rename"), "orig.xlsx") as ImportResult.Added).workbook
        val before = library.fileOf(entry).readBytes()

        val renamed = library.rename(entry.id, "  My catalogue  ")

        assertEquals("My catalogue", renamed?.displayName)
        assertEquals(entry.id, renamed?.id)
        assertEquals(entry.fileName, renamed?.fileName)
        assertArrayEquals(before, library.fileOf(entry).readBytes())
        assertEquals("orig.xlsx", library.byId(entry.id)?.originalName)
    }

    @Test fun pinningAndRecencySurviveARestart() {
        val library = WorkbookLibrary(dir)
        val a = (import(library, workbookBytes("a"), "a.xlsx") as ImportResult.Added).workbook
        val b = (import(library, workbookBytes("b"), "b.xlsx") as ImportResult.Added).workbook
        library.touch(b.id, now = 5_000L)
        library.setPinned(a.id, true)

        val reopened = WorkbookLibrary(dir).list()

        assertEquals(listOf(a.id, b.id), reopened.map { it.id })
        assertTrue(reopened.first().pinned)
        assertEquals(5_000L, reopened.last().lastOpenedAt)
    }

    @Test fun removingDeletesTheCopyAndTheEntryOnly() {
        val library = WorkbookLibrary(dir)
        val entry = (import(library, workbookBytes("gone"), "gone.xlsx") as ImportResult.Added).workbook
        val file = library.fileOf(entry)

        val (entryRemoved, fileDeleted) = library.remove(entry.id)

        assertTrue(entryRemoved)
        assertTrue(fileDeleted)
        assertFalse(file.exists())
        assertNull(library.byId(entry.id))
        assertEquals(false, library.remove("nope").first)
    }

    @Test fun aCorruptIndexIsQuarantinedAndTheCopiesSurvive() {
        val library = WorkbookLibrary(dir)
        val entry = (import(library, workbookBytes("keep"), "keep.xlsx") as ImportResult.Added).workbook
        File(dir, "library.json").writeText("{ this is not json")

        val reopened = WorkbookLibrary(dir)

        assertTrue(reopened.list().isEmpty())
        assertTrue(File(dir, "library.json.bad").isFile)
        assertTrue("the workbook copy must not be deleted", reopened.fileOf(entry).isFile)
    }

    @Test fun aMissingCopyIsReportedInsteadOfCrashing() {
        val library = WorkbookLibrary(dir)
        val entry = (import(library, workbookBytes("missing"), "missing.xlsx") as ImportResult.Added).workbook
        library.fileOf(entry).delete()

        val (removed, deleted) = library.remove(entry.id)

        assertTrue(removed)
        assertTrue("nothing left to delete counts as cleaned", deleted)
        assertEquals(0L, library.bytes())
    }
}
