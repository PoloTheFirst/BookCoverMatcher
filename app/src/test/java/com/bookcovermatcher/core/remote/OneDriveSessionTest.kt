// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

import com.bookcovermatcher.core.TestWorkbooks
import com.bookcovermatcher.core.net.FakeConnectivityMonitor
import com.bookcovermatcher.data.WorkbookLibrary
import com.bookcovermatcher.data.WorkbookOrigin
import com.bookcovermatcher.data.onedrive.OneDriveImportOutcome
import com.bookcovermatcher.data.onedrive.OneDriveListing
import com.bookcovermatcher.data.onedrive.OneDriveSession
import com.bookcovermatcher.data.onedrive.SafeWorkbookDownload
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OneDrive import session (v2.6.0-dev.1 Phase 4): preflight, listing, download, commit, cleanup.
 *
 * Every test uses the deterministic fakes — no network, no Graph, no MSAL — and a real
 * [WorkbookLibrary] on a temporary directory, because the point is precisely that an imported OneDrive
 * workbook becomes an ordinary library entry.
 */
class OneDriveSessionTest {

    private val dir: File = Files.createTempDirectory("onedrive-session").toFile()
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

    /** A genuine minimal OOXML package, so the download validator is really exercised. */
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

    private fun filesInLibraryDirectory(): List<String> = dir.listFiles().orEmpty().map { it.name }

    // ---- preflight ---------------------------------------------------------------------------

    @Test fun offlinePreflightBlocksListing() = runBlocking {
        monitor.setOnline(false)

        val result = session().list(null)

        assertEquals(RemoteFailure.Offline, (result as OneDriveListing.Failed).failure)
        assertTrue("no request may be attempted without a network", source.listingRequests.isEmpty())
    }

    @Test fun offlinePreflightBlocksImportAndTouchesNothing() = runBlocking {
        monitor.setOnline(false)

        val result = session().importItem(item)

        assertEquals(RemoteFailure.Offline, (result as OneDriveImportOutcome.Failed).failure)
        assertEquals(0, source.downloadRequests)
        assertTrue(library.list().isEmpty())
        assertTrue(filesInLibraryDirectory().isEmpty())
    }

    // ---- listing ------------------------------------------------------------------------------

    @Test fun aListingReturnsFoldersFilesAndTheNextPageToken() = runBlocking {
        val folder = RemoteItem(id = "F1", name = "Catalogue", isFolder = true)
        source.firstPage(
            RemoteResult.Ok(
                RemoteListing(
                    folderId = null,
                    folders = listOf(folder),
                    files = listOf(item),
                    nextPageToken = "https://graph.microsoft.com/v1.0/me/drive/root/children?\$skiptoken=N",
                ),
            ),
        )

        val loaded = session().list(null) as OneDriveListing.Loaded

        assertEquals(listOf("F1"), loaded.folders.map { it.id })
        assertEquals(listOf("01ABCDEF!12"), loaded.files.map { it.id })
        assertNotNull("pagination is passed through untouched", loaded.nextPageToken)
        assertEquals(listOf<Pair<String?, String?>>(null to null), source.listingRequests)
    }

    @Test fun aSecondPageIsRequestedWithTheTokenAndTheSameFolder() = runBlocking {
        source.pages["token2"] = RemoteResult.Ok(RemoteListing("F1", emptyList(), emptyList(), null))

        session().list("F1", pageToken = "token2")

        assertEquals(listOf("F1" to "token2"), source.listingRequests)
    }

    @Test fun aListingFailureIsReportedWithItsOwnReason() = runBlocking {
        source.firstPage(RemoteResult.Err(RemoteFailure.Throttled(12)))

        val result = session().list(null)

        assertEquals(RemoteFailure.Throttled(12), (result as OneDriveListing.Failed).failure)
    }

    @Test fun anExpiredSignInSurfacesAsAnExpiredSignIn() = runBlocking {
        // The source reports the auth failure; the session must not flatten it into a generic error.
        source.firstPage(RemoteResult.Err(RemoteFailure.AuthExpired))

        val result = session().list(null)

        assertEquals(RemoteFailure.AuthExpired, (result as OneDriveListing.Failed).failure)
    }


    // ---- import: the happy path --------------------------------------------------------------

    @Test fun importingAddsAWorkbookWithItsOneDriveOrigin() = runBlocking {
        source.content = workbookBytes()

        val result = session().importItem(item) as OneDriveImportOutcome.Imported

        assertFalse("a first import is not a duplicate", result.alreadySaved)
        val entry = library.byId(result.workbookId)
        assertNotNull("the workbook must be in the existing library", entry)
        assertEquals("covers.xlsx", entry!!.displayName)
        assertEquals(WorkbookOrigin.ONEDRIVE, entry.origin)
        assertEquals("01ABCDEF!12", entry.remote?.itemId)
        assertEquals("/drive/root:/Catalogue", entry.remote?.path)
        assertEquals("\"{AAA},3\"", entry.remote?.eTag)
        assertEquals(1_794_141_296_000L, entry.remote?.lastModified)
        // The remote block records the size OneDrive reported (the remote file's size, for later
        // comparison), not our own byte count of what was written.
        assertEquals(4_096L, entry.remote?.sizeBytes)
        assertEquals("a fresh import counts as the first check", 7_000L, entry.remote?.syncedAt)
        assertEquals(7_000L, entry.remote?.checkedAt)
        assertTrue("the local copy exists", library.fileOf(entry).isFile)
        assertEquals("the entry is what the saved-workbook picker lists", listOf(entry.id), library.list().map { it.id })
    }

    @Test fun theTemporaryFileIsRemovedAfterASuccessfulImport() = runBlocking {
        source.content = workbookBytes()

        session().importItem(item)

        assertFalse(downloader.partFileFor(item).exists())
        assertTrue("only the library copy remains", filesInLibraryDirectory().none { it.endsWith(".part") })
    }

    @Test fun progressReachesTheCaller() = runBlocking {
        source.content = workbookBytes()
        source.chunkBytes = 16
        val seen = mutableListOf<Long>()

        session().importItem(item) { progress -> seen += progress.bytesWritten }

        assertTrue("progress must be reported", seen.isNotEmpty())
        assertEquals("progress must be monotonic", seen.sorted(), seen)
        assertEquals(source.content.size.toLong(), seen.last())
    }

    @Test fun aFolderCannotBeImported() = runBlocking {
        val folder = RemoteItem(id = "F1", name = "Catalogue", isFolder = true)

        val result = session().importItem(folder)

        assertEquals(RemoteFailure.BadRequest, (result as OneDriveImportOutcome.Failed).failure)
        assertEquals(0, source.downloadRequests)
        assertTrue(library.list().isEmpty())
    }


    // ---- duplicates and existing entries -------------------------------------------------------

    @Test fun importingTheSameBytesTwiceReusesTheEntry() = runBlocking {
        source.content = workbookBytes()
        val first = session().importItem(item) as OneDriveImportOutcome.Imported
        val second = session().importItem(item) as OneDriveImportOutcome.Imported

        assertTrue("the second import reports the duplicate", second.alreadySaved)
        assertEquals("and points at the same library entry", first.workbookId, second.workbookId)
        assertEquals(1, library.list().size)
        assertFalse("the temporary file never survives", downloader.partFileFor(item).exists())
    }

    @Test fun aLocallyImportedWorkbookGainsTheOneDriveSourceButKeepsItsIdentity() = runBlocking {
        val bytes = workbookBytes()
        val local = (library.import(bytes.inputStream(), "covers.xlsx", now = 100L)
            as com.bookcovermatcher.data.ImportResult.Added).workbook
        assertEquals(WorkbookOrigin.LOCAL, local.origin)
        source.content = bytes

        val result = session().importItem(item) as OneDriveImportOutcome.Imported

        assertTrue(result.alreadySaved)
        assertEquals("the existing entry is reused, not duplicated", local.id, result.workbookId)
        assertEquals(1, library.list().size)
        val linked = library.byId(local.id)!!
        assertEquals("a local entry gains the source it never had", WorkbookOrigin.ONEDRIVE, linked.origin)
        assertEquals("01ABCDEF!12", linked.remote?.itemId)
        assertEquals("its own name is untouched", "covers.xlsx", linked.displayName)
        assertEquals("its import time is untouched", 100L, linked.importedAt)
    }

    @Test fun anEntryPointingAtADifferentRemoteFileIsNotRepointed() = runBlocking {
        val bytes = workbookBytes()
        val local = (library.import(bytes.inputStream(), "covers.xlsx", now = 100L)
            as com.bookcovermatcher.data.ImportResult.Added).workbook
        library.setRemote(local.id, com.bookcovermatcher.data.RemoteOrigin(itemId = "OTHER-ITEM", path = "/x"))
        source.content = bytes

        val result = session().importItem(item) as OneDriveImportOutcome.Imported

        assertTrue(result.alreadySaved)
        assertEquals(
            "an entry already sourced from another remote file is left alone",
            "OTHER-ITEM",
            library.byId(local.id)?.remote?.itemId,
        )
    }

    // ---- failures ------------------------------------------------------------------------------

    @Test fun anInvalidWorkbookIsRejectedAndNothingIsSaved() = runBlocking {
        source.content = "<html>Sign in</html>".toByteArray()

        val result = session().importItem(item)

        assertEquals(RemoteFailure.InvalidWorkbook, (result as OneDriveImportOutcome.Failed).failure)
        assertTrue(library.list().isEmpty())
        assertTrue("the rejected bytes are deleted", filesInLibraryDirectory().isEmpty())
    }

    @Test fun aDownloadFailureLeavesNothingBehind() = runBlocking {
        source.downloadFailure = RemoteFailure.Throttled(30)

        val result = session().importItem(item)

        assertEquals(RemoteFailure.Throttled(30), (result as OneDriveImportOutcome.Failed).failure)
        assertTrue(library.list().isEmpty())
        assertTrue(filesInLibraryDirectory().isEmpty())
    }

    @Test fun aCancelledDownloadLeavesNothingBehind() = runBlocking {
        source.content = workbookBytes()
        source.chunkBytes = 16
        source.cancelAfterBytes = 32

        val result = session().importItem(item)

        assertEquals(OneDriveImportOutcome.Cancelled, result)
        assertTrue(library.list().isEmpty())
        assertTrue(filesInLibraryDirectory().isEmpty())
    }

    @Test fun anExhaustedDeviceIsReportedAndCleanedUp() = runBlocking {
        source.content = workbookBytes()
        source.chunkBytes = 16
        source.throwAfterBytes = 32 to IOException("write failed: ENOSPC (No space left on device)")

        val result = session().importItem(item)

        assertEquals(RemoteFailure.NoStorage, (result as OneDriveImportOutcome.Failed).failure)
        assertTrue(library.list().isEmpty())
        assertTrue(filesInLibraryDirectory().isEmpty())
    }

    @Test fun aFileWhoseNameIsNotAWorkbookIsRejectedBeforeTheLibrarySeesIt() = runBlocking {
        // The download validator applies the library's own extension rule, so a misnamed file never
        // reaches `WorkbookLibrary.import` and carries no library message.
        val misnamed = item.copy(name = "covers.zip")
        source.content = workbookBytes()

        val result = session().importItem(misnamed) as OneDriveImportOutcome.Failed

        assertEquals(RemoteFailure.InvalidWorkbook, result.failure)
        assertNull(result.detail)
        assertTrue(library.list().isEmpty())
        assertTrue("the rejected bytes are deleted", filesInLibraryDirectory().isEmpty())
    }
}

