// SPDX-License-Identifier: GPL-3.0-or-later
package com.bookcovermatcher.core.remote

import com.bookcovermatcher.core.TestWorkbooks
import com.bookcovermatcher.core.net.FakeConnectivityMonitor
import com.bookcovermatcher.data.*
import com.bookcovermatcher.data.googledrive.*
import com.bookcovermatcher.data.onedrive.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class WorkbookSourceBindingTest {
    private val dir = Files.createTempDirectory("workbook-source-binding").toFile()
    private val library = WorkbookLibrary(dir)
    private val source = FakeRemoteWorkbookSource()
    private val network = FakeConnectivityMonitor(online = true)
    private val downloader = SafeWorkbookDownload(dir)
    private val account = SourceAccount("google:account-a", "Example account")
    private val item = RemoteItem("file-id", "sample.xlsx", false, path = "My Drive/Books/sample.xlsx")
    @After fun cleanup() { dir.deleteRecursively() }

    private fun bytes(): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            mapOf("[Content_Types].xml" to "<Types/>",
                "xl/workbook.xml" to TestWorkbooks.workbook("Sheet1" to 1),
                "xl/_rels/workbook.xml.rels" to TestWorkbooks.workbookRels(1),
                "xl/worksheets/sheet1.xml" to "<worksheet/>").forEach { (path, text) ->
                zip.putNextEntry(ZipEntry(path).apply { time = 0L }); zip.write(text.toByteArray()); zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun local() = (library.import(bytes().inputStream(), "sample.xlsx") as ImportResult.Added).workbook

    @Test fun legacyFieldsStayUnknownAndNewMetadataSurvivesUnrelatedEdits() {
        val entry = local()
        assertNull(entry.local)
        library.setRemote(entry.id, RemoteOrigin(item.id), WorkbookOrigin.GOOGLE_DRIVE)
        assertNull(WorkbookLibrary(dir).byId(entry.id)!!.remote!!.accountKey)
        library.setRemote(entry.id, RemoteOrigin(item.id, item.path, accountKey = account.key, accountLabel = account.label), WorkbookOrigin.GOOGLE_DRIVE)
        library.rename(entry.id, "Renamed")
        library.setSummary(entry.id, 2, 1, 3)
        val restored = WorkbookLibrary(dir).byId(entry.id)!!
        assertEquals(account.key, restored.remote!!.accountKey)
        assertEquals(account.label, restored.remote.accountLabel)
        assertEquals(item.path, restored.remote.path)
    }

    @Test fun localUriSurvivesReloadAndCannotOverwriteCloudOrigin() {
        val entry = local()
        val original = LocalOrigin("content://test/item", "Internal storage/Books/sample.xlsx", true)
        assertNotNull(library.setLocal(entry.id, original))
        library.setPinned(entry.id, true)
        assertEquals(original, WorkbookLibrary(dir).byId(entry.id)!!.local)
        library.setRemote(entry.id, RemoteOrigin(item.id))
        assertNull(library.setLocal(entry.id, original.copy(uri = "content://other/item")))
    }

    @Test fun accountMatchingUsesProviderIdentityNotLabel() {
        val entry = local().copy(origin = WorkbookOrigin.GOOGLE_DRIVE,
            remote = RemoteOrigin(item.id, accountKey = account.key, accountLabel = account.label))
        assertNull(WorkbookSourceStatus.accountProblem(entry, account.copy(label = "Renamed"), true))
        assertEquals("Different account signed in", WorkbookSourceStatus.accountProblem(entry, account.copy(key = "google:b"), true))
        assertEquals("Sign in to check source", WorkbookSourceStatus.accountProblem(entry, account, false))
        assertEquals("Account unverified · confirm source account", WorkbookSourceStatus.accountProblem(entry.copy(remote = RemoteOrigin(item.id)), account, true))
    }

    @Test fun failuresNeverClaimDeletion() {
        for (failure in listOf(RemoteFailure.NotFound, RemoteFailure.AuthExpired, RemoteFailure.PermissionDenied,
            RemoteFailure.Offline, RemoteFailure.Timeout, RemoteFailure.Throttled(), RemoteFailure.ServerError(503))) {
            assertFalse(WorkbookSourceStatus.failure(failure).contains("Deleted", ignoreCase = true))
        }
        assertEquals("Permission denied", WorkbookSourceStatus.failure(RemoteFailure.PermissionDenied))
    }

    @Test fun googleImportBindsAccountAndDuplicateCannotStealIt() = runBlocking {
        source.content = bytes()
        var current = account
        val session = GoogleDriveSession(source, network, library, downloader, account = { current })
        val first = session.importItem(item) as GoogleDriveImportOutcome.Imported
        current = SourceAccount("google:another", "Another")
        val duplicate = session.importItem(item.copy(id = "different-file")) as GoogleDriveImportOutcome.Imported
        assertTrue(duplicate.alreadySaved)
        assertEquals(first.workbookId, duplicate.workbookId)
        assertEquals(account.key, library.byId(first.workbookId)!!.remote!!.accountKey)
        assertEquals(item.id, library.byId(first.workbookId)!!.remote!!.itemId)
    }

    @Test fun explicitRelinkRequiresSameContentAndNeverAddsAnotherWorkbook() = runBlocking {
        val entry = local()
        val linked = library.setRemote(entry.id, RemoteOrigin("old-id"), WorkbookOrigin.GOOGLE_DRIVE)!!
        val session = GoogleDriveSession(source, network, library, downloader, account = { account })
        source.content = bytes() + byteArrayOf(1)
        assertTrue(session.importItem(item, relinkId = entry.id) is GoogleDriveImportOutcome.Failed)
        assertEquals(linked.remote, library.byId(entry.id)!!.remote)
        source.content = bytes()
        assertTrue(session.importItem(item, relinkId = entry.id) is GoogleDriveImportOutcome.Imported)
        assertEquals(account.key, library.byId(entry.id)!!.remote!!.accountKey)
        assertEquals(item.id, library.byId(entry.id)!!.remote!!.itemId)
        assertEquals(1, library.list().size)
    }

    @Test fun accountSwitchDuringDownloadDiscardsBothProviderResults() = runBlocking {
        source.content = bytes()
        var generation = 1L
        val google = GoogleDriveSession(source, network, library, downloader, account = { account }, generation = { generation })
        assertEquals(GoogleDriveImportOutcome.Cancelled, google.importItem(item) { generation++ })
        val microsoft = OneDriveSession(source, network, library, downloader, account = { account }, generation = { generation })
        assertEquals(OneDriveImportOutcome.Cancelled, microsoft.importItem(item) { generation++ })
        assertTrue(library.list().isEmpty())
        assertFalse(downloader.partFileFor(item).exists())
    }

    @Test fun mismatchedAccountBlocksProbeAndReplacement() = runBlocking {
        val entry = local()
        val linked = library.setRemote(entry.id, RemoteOrigin(item.id))!!
        val updates = OneDriveUpdateSession(source, network, library, downloader, mayAccess = { false })
        assertEquals(RemoteCheck.Failed(RemoteFailure.AuthRequired), updates.check(linked))
        assertEquals(UpdateOutcome.Failed(RemoteFailure.AuthRequired), updates.apply(linked, item))
        assertTrue(source.probeRequests.isEmpty())
        assertEquals(0, source.downloadRequests)
        assertArrayEquals(bytes(), library.fileOf(linked).readBytes())
    }

    @Test fun updateRetainsAccountAndRejectsLateAccountSwitch() = runBlocking {
        val entry = local()
        val linked = library.setRemote(entry.id, RemoteOrigin(item.id, accountKey = account.key, accountLabel = account.label))!!
        source.content = bytes()
        var generation = 1L
        val updates = OneDriveUpdateSession(source, network, library, downloader, generation = { generation })
        assertTrue(updates.apply(linked, item) is UpdateOutcome.SameContent)
        assertEquals(account.key, library.byId(entry.id)!!.remote!!.accountKey)
        assertEquals(UpdateOutcome.Cancelled, updates.apply(linked, item) { generation++ })
    }
}
