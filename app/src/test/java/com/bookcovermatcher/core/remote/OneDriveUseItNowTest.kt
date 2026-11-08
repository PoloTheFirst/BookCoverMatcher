// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

import com.bookcovermatcher.core.TestWorkbooks
import com.bookcovermatcher.core.excel.JavaZipSource
import com.bookcovermatcher.core.excel.WorkbookImageExtractor
import com.bookcovermatcher.core.net.FakeConnectivityMonitor
import com.bookcovermatcher.data.WorkbookLibrary
import com.bookcovermatcher.data.WorkbookOrigin
import com.bookcovermatcher.data.onedrive.OneDriveImportOutcome
import com.bookcovermatcher.data.onedrive.OneDriveSession
import com.bookcovermatcher.data.onedrive.SafeWorkbookDownload
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens after `From OneDrive` succeeds (v2.6.0-dev.1 Phase 4).
 *
 * The Compose buttons cannot be driven in a JVM test here (no Compose UI-test dependency), so this class
 * asserts the *contract* the buttons rely on, using the same calls `useSavedWorkbook` makes:
 * `library.byId` → `library.fileOf` → `JavaZipSource` + `WorkbookImageExtractor.requireWorkbook`. If any
 * of those stops holding for an imported OneDrive workbook, `Use it now` would break — and this fails.
 */
class OneDriveUseItNowTest {

    private val dir: File = Files.createTempDirectory("onedrive-use-it-now").toFile()
    private val library = WorkbookLibrary(dir)
    private val downloader = SafeWorkbookDownload(dir)
    private val monitor = FakeConnectivityMonitor(online = true)
    private val source = FakeRemoteWorkbookSource()

    private val item = RemoteItem(
        id = "01ABCDEF!12",
        name = "covers.xlsx",
        isFolder = false,
        sizeBytes = 4_096L,
        eTag = "\"{AAA},3\"",
        lastModified = 1_794_141_296_000L,
        path = "/drive/root:/Catalogue",
    )

    @After fun cleanUp() {
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    private fun session(now: Long = 7_000L) = OneDriveSession(
        source = source,
        connectivity = monitor,
        library = library,
        downloader = downloader,
        now = { now },
    )

    private fun workbookBytes(tag: String = "x"): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            put("[Content_Types].xml", "<Types/>")
            put("xl/workbook.xml", TestWorkbooks.workbook("Sheet1" to 1))
            put("xl/_rels/workbook.xml.rels", TestWorkbooks.workbookRels(1))
            put("xl/worksheets/sheet1.xml", "<worksheet>$tag</worksheet>")
        }
        return out.toByteArray()
    }

    /** Exactly what `useSavedWorkbook` does with the id the dialog hands it. */
    private fun stageAsTheAppWould(workbookId: String): File {
        val entry = library.byId(workbookId)
        assertNotNull("Use it now needs the entry to exist", entry)
        val file = library.fileOf(entry!!)
        assertTrue("Use it now needs the local copy to exist", file.isFile)
        JavaZipSource(ZipFile(file)).use { zip -> WorkbookImageExtractor.requireWorkbook(zip) }
        return file
    }

    @Test fun anImportedWorkbookIsInThePickerAndCanBeUsedImmediately() = runBlocking {
        source.content = workbookBytes()

        val imported = session().importItem(item) as OneDriveImportOutcome.Imported

        assertTrue(
            "the imported workbook appears in the existing Saved workbooks list",
            library.list().any { it.id == imported.workbookId },
        )
        val file = stageAsTheAppWould(imported.workbookId)
        assertEquals("the staged copy is the library's own file", library.fileOf(library.byId(imported.workbookId)!!), file)
        assertEquals("covers.xlsx", library.byId(imported.workbookId)!!.displayName)
    }

    @Test fun usingItNowNeedsNoNetworkAndNoOneDrive() = runBlocking {
        source.content = workbookBytes()
        val imported = session().importItem(item) as OneDriveImportOutcome.Imported

        // The network is gone, and so is the OneDrive item; opening must not care.
        monitor.setOnline(false)
        source.content = ByteArray(0)
        source.pages.clear()

        stageAsTheAppWould(imported.workbookId)

        assertEquals("importing was the only remote read", 1, source.downloadRequests)
        assertTrue("no listing happened while opening", source.listingRequests.isEmpty())
    }

    @Test fun theOneDriveOriginSurvivesARestart() = runBlocking {
        source.content = workbookBytes()
        val imported = session().importItem(item) as OneDriveImportOutcome.Imported

        // A fresh library instance reads the same index file: the schema carried the remote block.
        val reopened = WorkbookLibrary(dir).byId(imported.workbookId)

        assertNotNull(reopened)
        assertEquals(WorkbookOrigin.ONEDRIVE, reopened!!.origin)
        assertEquals("01ABCDEF!12", reopened.remote?.itemId)
        assertEquals("/drive/root:/Catalogue", reopened.remote?.path)
        assertTrue("the local copy is still there", File(dir, reopened.fileName).isFile)
    }

    @Test fun aSecondSuccessfulImportKeepsPointingUseItNowAtOneEntry() = runBlocking {
        source.content = workbookBytes()
        val first = session().importItem(item) as OneDriveImportOutcome.Imported

        // Same bytes, fetched again (for example the user tapped the row twice).
        val second = session().importItem(item) as OneDriveImportOutcome.Imported

        assertEquals(first.workbookId, second.workbookId)
        assertTrue(second.alreadySaved)
        assertEquals(1, library.list().size)
        stageAsTheAppWould(second.workbookId)
        assertFalse("no temporary file survives the second import", downloader.partFileFor(item).exists())
    }
}
