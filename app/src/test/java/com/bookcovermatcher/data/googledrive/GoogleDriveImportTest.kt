// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.data.googledrive

import com.bookcovermatcher.core.TestWorkbooks
import com.bookcovermatcher.core.net.FakeConnectivityMonitor
import com.bookcovermatcher.core.remote.*
import com.bookcovermatcher.data.*
import com.bookcovermatcher.data.onedrive.SafeWorkbookDownload
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GoogleDriveImportTest {
    private fun metadata(name: String, mime: String = "application/octet-stream") = mapOf(
        "id" to "opaque-id", "name" to name, "mimeType" to mime,
    )

    @Test fun onlyExcelNamesAreAccepted() {
        assertTrue(GoogleDriveFiles.allowedName("Book.XLSX"))
        assertTrue(GoogleDriveFiles.allowedName("Book.XlSm"))
        for (name in listOf("book.xls", "book.csv", "book.ods", "book.xlsx.exe", "xlsx")) {
            assertFalse(GoogleDriveFiles.allowedName(name))
        }
    }

    @Test fun nativeSheetsAndShortcutsCannotMasqueradeAsExcelFiles() {
        assertNull(GoogleDriveFiles.item(metadata("book.xlsx", "application/vnd.google-apps.spreadsheet")))
        assertNull(GoogleDriveFiles.item(metadata("book.xlsx", "application/vnd.google-apps.shortcut")))
        assertNotNull(GoogleDriveFiles.item(metadata("book.xlsx")))
        assertNotNull(GoogleDriveFiles.item(metadata("folder", GoogleDriveFiles.FOLDER)))
    }

    @Test fun restrictedAndTrashedFilesAreExcluded() {
        assertNull(GoogleDriveFiles.item(metadata("book.xlsx") + ("capabilities" to mapOf("canDownload" to false))))
        assertNull(GoogleDriveFiles.item(metadata("book.xlsx") + ("trashed" to true)))
    }

    @Test fun emptyFilteredPageStillRetainsNextToken() {
        val page = GoogleDriveFiles.listing(mapOf("files" to listOf(metadata("notes.txt")), "nextPageToken" to "a+/opaque="), null)
        assertTrue(page.files.isEmpty())
        assertEquals("a+/opaque=", page.nextPageToken)
    }

    @Test fun paginationDeduplicatesIdsAndNavigationUsesIdsNotNames() {
        val folder = RemoteItem("folder-id", "A/B", true)
        val first = GoogleDriveNavigation.withFirstPage(GoogleDriveBrowse.root(), listOf(folder), emptyList(), "next")
        val next = GoogleDriveNavigation.withNextPage(first, listOf(folder), emptyList(), null)
        assertEquals(1, next.folders.size)
        val child = GoogleDriveNavigation.into(next, folder)
        assertEquals("folder-id", child.folderId)
        assertEquals("A/B", child.folderName)
        assertTrue(GoogleDriveNavigation.up(child).atRoot)
    }

    private fun workbook(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            }
            put("[Content_Types].xml", "<Types/>")
            put("xl/workbook.xml", TestWorkbooks.workbook("Sheet1" to 1))
            put("xl/_rels/workbook.xml.rels", TestWorkbooks.workbookRels(1))
            put("xl/worksheets/sheet1.xml", "<worksheet/>")
        }
        return output.toByteArray()
    }

    @Test fun bothFormatsPersistAsGoogleCopiesAndOpenFromDiskOffline() = runBlocking {
        for (extension in listOf("xlsx", "xlsm")) {
            val dir = Files.createTempDirectory("google-import").toFile()
            try {
                val library = WorkbookLibrary(dir)
                val monitor = FakeConnectivityMonitor(true)
                val source = FakeRemoteWorkbookSource().apply { content = workbook() }
                val session = GoogleDriveSession(source, monitor, library, SafeWorkbookDownload(dir))
                val result = session.importItem(RemoteItem("same-id", "book.$extension", false)) as GoogleDriveImportOutcome.Imported
                monitor.setOnline(false)
                val reloaded = WorkbookLibrary(dir)
                val saved = reloaded.byId(result.workbookId)!!
                assertEquals(WorkbookOrigin.GOOGLE_DRIVE, saved.origin)
                assertNull(saved.remote)
                assertArrayEquals(source.content, reloaded.fileOf(saved).readBytes())
                assertFalse(dir.listFiles().orEmpty().any { it.name.endsWith(".part") })
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun duplicatesPreserveExistingOneDriveIdentity() = runBlocking {
        val dir = Files.createTempDirectory("google-duplicate").toFile()
        try {
            val library = WorkbookLibrary(dir)
            val bytes = workbook()
            val added = (library.import(bytes.inputStream(), "existing.xlsx") as ImportResult.Added).workbook
            library.setRemote(added.id, RemoteOrigin("same-id", eTag = "old-tag"))
            val before = library.byId(added.id)!!
            val source = FakeRemoteWorkbookSource().apply { content = bytes }
            val session = GoogleDriveSession(source, FakeConnectivityMonitor(true), library, SafeWorkbookDownload(dir))
            val imported = session.importItem(RemoteItem("same-id", "google.xlsx", false)) as GoogleDriveImportOutcome.Imported
            assertTrue(imported.alreadySaved)
            assertEquals(before, library.byId(added.id))
        } finally { dir.deleteRecursively() }
    }

    @Test fun offlineImportAndUnsupportedNamesMakeNoDownload() = runBlocking {
        val dir = Files.createTempDirectory("google-offline").toFile()
        try {
            val source = FakeRemoteWorkbookSource()
            val session = GoogleDriveSession(source, FakeConnectivityMonitor(false), WorkbookLibrary(dir), SafeWorkbookDownload(dir))
            assertEquals(RemoteFailure.Offline, (session.importItem(RemoteItem("id", "book.xlsx", false)) as GoogleDriveImportOutcome.Failed).failure)
            assertEquals(RemoteFailure.BadRequest, (session.importItem(RemoteItem("id", "book.csv", false)) as GoogleDriveImportOutcome.Failed).failure)
            assertEquals(0, source.downloadRequests)
        } finally { dir.deleteRecursively() }
    }

    @Test fun cancelledAndInvalidDownloadsLeaveNoLibraryEntryOrPart() = runBlocking {
        for (cancel in listOf(false, true)) {
            val dir = Files.createTempDirectory("google-failure").toFile()
            try {
                val source = FakeRemoteWorkbookSource().apply {
                    content = if (cancel) workbook() else "not a workbook".toByteArray()
                    if (cancel) cancelAfterBytes = 1
                }
                val library = WorkbookLibrary(dir)
                val result = GoogleDriveSession(source, FakeConnectivityMonitor(true), library, SafeWorkbookDownload(dir))
                    .importItem(RemoteItem("id", "book.xlsx", false))
                if (cancel) assertEquals(GoogleDriveImportOutcome.Cancelled, result)
                else assertTrue(result is GoogleDriveImportOutcome.Failed)
                assertTrue(library.list().isEmpty())
                assertFalse(dir.listFiles().orEmpty().any { it.name.endsWith(".part") })
            } finally { dir.deleteRecursively() }
        }
    }
}
