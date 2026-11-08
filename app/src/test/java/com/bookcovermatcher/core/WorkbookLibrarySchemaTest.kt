// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core

import com.bookcovermatcher.data.ImportResult
import com.bookcovermatcher.data.RemoteOrigin
import com.bookcovermatcher.data.WorkbookLibrary
import com.bookcovermatcher.data.WorkbookOrigin
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Schema 1 → schema 2 (v2.6.0-dev.1 Phase 2).
 *
 * The rule under test is the one a released app must never break: **an existing saved-workbook library
 * survives the upgrade**. Every migration test builds the old file by hand — the exact JSON a schema-1
 * build wrote — because a fixture produced by the new writer would prove nothing about the old reader.
 */
class WorkbookLibrarySchemaTest {

    private val dir: File = Files.createTempDirectory("workbook-library-schema").toFile()

    @After fun cleanUp() {
        deleteTree(dir)
    }

    private fun deleteTree(file: File) {
        file.listFiles()?.forEach { deleteTree(it) }
        file.delete()
    }

    /** A copy that exists on disk, so a migrated entry's file reference can really be resolved. */
    private fun workbookFile(name: String): File {
        dir.mkdirs()
        return File(dir, name).apply {
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "copied bytes of $name".toByteArray())
        }
    }

    private fun workbookFileBytes(tag: String): ByteArray =
        byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "[Content_Types].xml $tag".toByteArray()

    private fun indexText(): String = File(dir, "library.json").readText(Charsets.UTF_8)

    private fun writeIndex(text: String) {
        dir.mkdirs()
        File(dir, "library.json").writeText(text, Charsets.UTF_8)
    }

    /** The schema-1 shape: no `origin`, no `remote`, and a `schema` key of 1. */
    private fun schemaOneEntry(
        id: String,
        name: String,
        file: String,
        bytes: Int,
        sha: String,
        format: String = file.substringAfterLast('.'),
        opened: Long? = null,
        pinned: Boolean = false,
        sheets: Int? = null,
    ): String = """{"id":"$id","name":"$name","original":"$name","format":"$format","file":"$file",""" +
        """"bytes":$bytes,"sha256":"$sha","imported":1700000000000,"opened":${opened ?: "null"},""" +
        """"pinned":$pinned,"sheets":${sheets ?: "null"},"comments":null,"images":null}"""

    private fun schemaOneIndex(vararg entries: String): String =
        """{"schema":1,"workbooks":[${entries.joinToString(",")}]}"""

    // ---- the migration itself ----------------------------------------------------------------

    @Test fun aSchemaOneLibraryMigratesToSchemaTwoAndKeepsEveryField() {
        workbookFile("aaaaaaaaaaaa.xlsx")
        workbookFile("bbbbbbbbbbbb.xlsm")
        writeIndex(
            schemaOneIndex(
                schemaOneEntry(
                    id = "aaaaaaaaaaaa", name = "同人誌 2026", file = "aaaaaaaaaaaa.xlsx", bytes = 4_096,
                    sha = "aaaa", opened = 1_700_000_500_000L, pinned = true, sheets = 8,
                ),
                schemaOneEntry(
                    id = "bbbbbbbbbbbb", name = "second.xlsm", file = "bbbbbbbbbbbb.xlsm", bytes = 512,
                    sha = "bbbb",
                ),
            ),
        )

        val entries = WorkbookLibrary(dir).list()

        assertEquals("every schema-1 entry must survive", 2, entries.size)
        val first = entries.first { it.id == "aaaaaaaaaaaa" }
        assertEquals("同人誌 2026", first.displayName)
        assertEquals("同人誌 2026", first.originalName)
        assertEquals("xlsx", first.format)
        assertEquals("aaaaaaaaaaaa.xlsx", first.fileName)
        assertEquals(4_096L, first.sizeBytes)
        assertEquals("aaaa", first.sha256)
        assertEquals(1_700_000_000_000L, first.importedAt)
        assertEquals(1_700_000_500_000L, first.lastOpenedAt)
        assertTrue(first.pinned)
        assertEquals(8, first.sheetCount)
        assertNull(first.commentCount)
        assertNull(first.imageCount)
        assertEquals("a schema-1 entry is local", WorkbookOrigin.LOCAL, first.origin)
        assertNull(first.remote)

        val second = entries.first { it.id == "bbbbbbbbbbbb" }
        assertEquals("xlsm", second.format)
        assertEquals(WorkbookOrigin.LOCAL, second.origin)
        assertNull(second.remote)
    }

    @Test fun theMigratedFileIsWrittenAsSchemaTwo() {
        workbookFile("cccccccccccc.xlsx")
        writeIndex(
            schemaOneIndex(
                schemaOneEntry(
                    id = "cccccccccccc", name = "c.xlsx", file = "cccccccccccc.xlsx", bytes = 16, sha = "cccc",
                ),
            ),
        )

        WorkbookLibrary(dir).list()

        val text = indexText()
        assertTrue("the index must be schema 2 now: $text", text.contains("\"schema\":2"))
        assertTrue("the origin must be recorded: $text", text.contains("\"origin\":\"local\""))
        assertTrue("the entry must still be there: $text", text.contains("cccccccccccc"))
        assertFalse("a local entry carries no remote block: $text", text.contains("\"remote\""))
    }

    @Test fun aFileWithoutASchemaFieldIsTreatedAsSchemaOne() {
        workbookFile("dddddddddddd.xlsx")
        // The earliest builds wrote the list with no `schema` key at all.
        writeIndex(
            """{"workbooks":[${schemaOneEntry("dddddddddddd", "d.xlsx", "dddddddddddd.xlsx", 32, "dddd")}]}""",
        )

        val entries = WorkbookLibrary(dir).list()

        assertEquals(1, entries.size)
        assertEquals(WorkbookOrigin.LOCAL, entries.single().origin)
        assertTrue("it is migrated like schema 1", indexText().contains("\"schema\":2"))
    }

    @Test fun migrationKeepsTheLocalFileReferenceWorking() {
        val copy = workbookFile("eeeeeeeeeeee.xlsx")
        val expected = copy.readBytes()
        writeIndex(
            schemaOneIndex(
                schemaOneEntry(
                    id = "eeeeeeeeeeee", name = "e.xlsx", file = "eeeeeeeeeeee.xlsx", bytes = 32, sha = "eeee",
                ),
            ),
        )

        val library = WorkbookLibrary(dir)
        val entry = library.list().single()

        assertTrue("the entry must still point at the app's own copy", library.fileOf(entry).isFile)
        assertArrayEquals(expected, library.fileOf(entry).readBytes())
        assertEquals(entry.id, library.byId("eeeeeeeeeeee")?.id)
    }


    // ---- the new origin ----------------------------------------------------------------------

    @Test fun aSchemaTwoLibraryRoundTripsAnOneDriveEntryAndLeavesALocalOneAlone() {
        val library = WorkbookLibrary(dir)
        val local = (library.import(ByteArrayInputStream(workbookFileBytes("local")), "local.xlsx", now = 1L)
            as ImportResult.Added).workbook
        val fromOneDrive = (library.import(ByteArrayInputStream(workbookFileBytes("drive")), "drive.xlsm", now = 2L)
            as ImportResult.Added).workbook
        assertEquals(
            "an imported workbook is local until it is told otherwise",
            WorkbookOrigin.LOCAL,
            fromOneDrive.origin,
        )

        val remote = RemoteOrigin(
            itemId = "01ABCDEF",
            path = "Catalogue/2026/covers.xlsm",
            eTag = "\"{1234},7\"",
            lastModified = 1_700_000_111_000L,
            sizeBytes = 4_096L,
            syncedAt = 1_700_000_222_000L,
            checkedAt = 1_700_000_333_000L,
        )
        assertNotNull(library.setRemote(fromOneDrive.id, remote))

        // Reopened from disk: this asserts the JSON round trip, not the in-memory copy.
        val reopened = WorkbookLibrary(dir)
        val drive = reopened.byId(fromOneDrive.id)!!
        assertEquals(WorkbookOrigin.ONEDRIVE, drive.origin)
        assertEquals(remote, drive.remote)
        assertEquals("the local copy must not move", fromOneDrive.fileName, drive.fileName)
        assertEquals(fromOneDrive.sha256, drive.sha256)
        assertEquals(fromOneDrive.sizeBytes, drive.sizeBytes)

        val untouched = reopened.byId(local.id)!!
        assertEquals(WorkbookOrigin.LOCAL, untouched.origin)
        assertNull(untouched.remote)
    }

    @Test fun anUnknownOriginKeepsTheEntryAndReadsAsLocal() {
        workbookFile("ffffffffffff.xlsx")
        writeIndex(
            """{"schema":2,"workbooks":[{"id":"ffffffffffff","name":"f.xlsx",""" +
                """"file":"ffffffffffff.xlsx","bytes":32,"origin":"dropbox"}]}""",
        )

        val entry = WorkbookLibrary(dir).list().single()

        assertEquals(
            "an entry must never be hidden because a field has a new value",
            WorkbookOrigin.LOCAL,
            entry.origin,
        )
        assertNull(entry.remote)
    }

    @Test fun anOneDriveOriginWithoutARemoteBlockReadsAsLocal() {
        workbookFile("999999999999.xlsx")
        writeIndex(
            """{"schema":2,"workbooks":[{"id":"999999999999","name":"g.xlsx",""" +
                """"file":"999999999999.xlsx","bytes":32,"origin":"onedrive"}]}""",
        )

        val entry = WorkbookLibrary(dir).list().single()

        assertEquals(
            "a half-written OneDrive entry cannot be tracked, so it is local",
            WorkbookOrigin.LOCAL,
            entry.origin,
        )
        assertNull(entry.remote)
    }


    // ---- what must NOT happen ------------------------------------------------------------------

    @Test fun aNewerSchemaIsLeftExactlyAsItIs() {
        val future = """{"schema":3,"workbooks":[{"id":"newer","name":"newer.xlsx","file":"newer.xlsx",""" +
            """"bytes":1,"origin":"onedrive","remote":{"itemId":"x","path":"p"}}]}"""
        writeIndex(future)

        val entries = WorkbookLibrary(dir).list()

        assertTrue("this build cannot read a newer schema, so it shows nothing", entries.isEmpty())
        assertEquals("the newer file must be byte-identical", future, indexText())
        assertFalse("it is not corrupt, so it is not quarantined", File(dir, "library.json.bad").exists())
    }

    @Test fun anImportNeverDowngradesANewerSchema() {
        val future = """{"schema":3,"workbooks":[{"id":"newer","name":"newer.xlsx","file":"newer.xlsx"}]}"""
        writeIndex(future)

        val result = WorkbookLibrary(dir).import(
            ByteArrayInputStream(workbookFileBytes("imported")),
            "imported.xlsx",
            now = 5L,
        )

        assertTrue(result is ImportResult.Rejected)
        val message = (result as ImportResult.Rejected).message
        assertTrue("the message must explain the real cause: $message", message.contains("newer version"))
        assertEquals("the newer file must be byte-identical", future, indexText())
        assertFalse("the rejected import must not leave a copy behind", File(dir, "imported.xlsx").exists())
    }

    @Test fun aFailedMigrationKeepsTheSchemaOneFileAndTheEntriesUsable() {
        workbookFile("111111111111.xlsx")
        writeIndex(
            schemaOneIndex(
                schemaOneEntry(
                    id = "111111111111", name = "h.xlsx", file = "111111111111.xlsx", bytes = 32, sha = "1111",
                ),
            ),
        )
        val before = indexText()
        // Occupying the temporary name with a directory makes every write of the index fail — the
        // deterministic stand-in for a full disk or a read-only profile.
        assertTrue(File(dir, "library.json.tmp").mkdirs())

        val entries = WorkbookLibrary(dir).list()

        assertEquals("the entries must still be returned", 1, entries.size)
        assertEquals(WorkbookOrigin.LOCAL, entries.single().origin)
        assertEquals("the schema-1 file must be preserved", before, indexText())

        // And the next read keeps working instead of degrading further.
        val library = WorkbookLibrary(dir)
        assertEquals(1, library.list().size)
        assertTrue(library.fileOf(library.list().single()).isFile)
    }

    @Test fun aMalformedIndexIsStillQuarantinedAndItsContentPreserved() {
        val broken = "{ this is not json"
        writeIndex(broken)

        val entries = WorkbookLibrary(dir).list()

        assertTrue(entries.isEmpty())
        val quarantine = File(dir, "library.json.bad")
        assertTrue("the malformed file must be preserved for inspection", quarantine.isFile)
        assertEquals(broken, quarantine.readText(Charsets.UTF_8))
        assertFalse("nothing was migrated", File(dir, "library.json").exists())
    }

    @Test fun oneBrokenEntryDoesNotHideTheValidOnes() {
        workbookFile("222222222222.xlsx")
        // The first object has no `file`, so it cannot describe a workbook; the second one is valid.
        writeIndex(
            schemaOneIndex(
                """{"id":"broken","name":"no file"}""",
                schemaOneEntry(
                    id = "222222222222", name = "i.xlsx", file = "222222222222.xlsx", bytes = 32, sha = "2222",
                ),
            ),
        )
        val before = indexText()

        val entries = WorkbookLibrary(dir).list()

        assertEquals("a broken entry must not cost the user the valid one", 1, entries.size)
        assertEquals("222222222222", entries.single().id)
        assertTrue("the valid entry is still usable", File(dir, entries.single().fileName).isFile)
        // And the record this build cannot read is not deleted by the migration.
        assertEquals("an unreadable record must survive untouched", before, indexText())
        assertTrue("the unreadable object is still in the file", indexText().contains("no file"))
    }

    // ---- the existing workflows still work -------------------------------------------------------

    @Test fun theExistingLibraryOperationsStillWorkAfterMigration() {
        workbookFile("333333333333.xlsx")
        writeIndex(
            schemaOneIndex(
                schemaOneEntry(
                    id = "333333333333", name = "migrated.xlsx", file = "333333333333.xlsx",
                    bytes = 32, sha = "3333",
                ),
            ),
        )
        val library = WorkbookLibrary(dir)
        assertEquals(1, library.list().size) // the migration happens on this read

        val added = library.import(ByteArrayInputStream(workbookFileBytes("fresh")), "fresh.xlsx", now = 9L)
        assertTrue("import still works next to a migrated entry", added is ImportResult.Added)
        val fresh = (added as ImportResult.Added).workbook
        assertEquals(WorkbookOrigin.LOCAL, fresh.origin)

        assertEquals("renamed", library.rename(fresh.id, "renamed")?.displayName)
        assertTrue(library.setPinned(fresh.id, true)?.pinned == true)
        assertEquals(9_500L, library.touch(fresh.id, now = 9_500L)?.lastOpenedAt)
        assertEquals(7, library.setSummary(fresh.id, sheets = 7, comments = 3, images = 1)?.sheetCount)

        // Duplicate detection and the migration coexist: the same bytes are not copied twice.
        val again = library.import(ByteArrayInputStream(workbookFileBytes("fresh")), "fresh copy.xlsx", now = 10L)
        assertTrue(again is ImportResult.AlreadySaved)

        val (removed, fileDeleted) = library.remove(fresh.id)
        assertTrue(removed)
        assertTrue(fileDeleted)
        assertEquals("the migrated entry is untouched by a removal", 1, library.list().size)
        assertEquals("333333333333", library.list().single().id)
    }
}

