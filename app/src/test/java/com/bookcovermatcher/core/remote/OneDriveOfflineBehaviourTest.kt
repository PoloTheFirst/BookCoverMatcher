// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

import com.bookcovermatcher.core.TestWorkbooks
import com.bookcovermatcher.core.excel.JavaZipSource
import com.bookcovermatcher.core.excel.WorkbookImageExtractor
import com.bookcovermatcher.core.excel.comments.CommentImageScanner
import com.bookcovermatcher.core.excel.comments.CommentScanResult
import com.bookcovermatcher.core.net.FakeConnectivityMonitor
import com.bookcovermatcher.data.ImportResult
import com.bookcovermatcher.data.RemoteOrigin
import com.bookcovermatcher.data.SavedWorkbook
import com.bookcovermatcher.data.WorkbookLibrary
import com.bookcovermatcher.data.WorkbookOrigin
import com.bookcovermatcher.data.onedrive.OneDriveImportOutcome
import com.bookcovermatcher.data.onedrive.OneDriveListing
import com.bookcovermatcher.data.onedrive.OneDriveSession
import com.bookcovermatcher.data.onedrive.OneDriveUpdateSession
import com.bookcovermatcher.data.onedrive.RemoteCheck
import com.bookcovermatcher.data.onedrive.SafeWorkbookDownload
import com.bookcovermatcher.data.onedrive.UpdateOutcome
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens to a OneDrive flow when the network comes and goes (v2.6.0-dev.1 Phase 6).
 *
 * The point of every test here is the pair `(remote answer, local copy)`: the remote side may fail in any
 * way it likes, and the workbook on the device must still open, still parse and still be the last
 * known-good version. Connectivity is driven through the injected [FakeConnectivityMonitor], so nothing
 * here needs a device, an account or a network.
 */
class OneDriveOfflineBehaviourTest {

    private val dir: File = Files.createTempDirectory("onedrive-offline").toFile()
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
        cTag = "c:3",
        lastModified = 1_794_100_000_000L,
        path = "/drive/root:/Catalogue",
    )

    @After fun cleanUp() {
        dir.listFiles()?.forEach { it.deleteRecursively() }
        dir.delete()
    }

    private fun browseSession() = OneDriveSession(
        source = source,
        connectivity = monitor,
        library = library,
        downloader = downloader,
        now = { 9_000L },
    )

    private fun updateSession() = OneDriveUpdateSession(
        source = source,
        connectivity = monitor,
        library = library,
        downloader = downloader,
        now = { 9_000L },
    )

    /** A saved OneDrive workbook, exactly as Phase 4/5 would have left it. */
    private fun savedWorkbook(): SavedWorkbook {
        val bytes = TestWorkbooks.xlsxBytes("old")
        val added = (library.import(bytes.inputStream(), "covers.xlsx") as ImportResult.Added).workbook
        return library.setRemote(
            added.id,
            RemoteOrigin(
                itemId = item.id,
                path = item.path,
                eTag = item.eTag,
                cTag = item.cTag,
                lastModified = item.lastModified,
                sizeBytes = bytes.size.toLong(),
                syncedAt = 5_000L,
                checkedAt = 5_000L,
            ),
        )!!
    }

    /** The local half of `WorkbookCommentsController.reload()`: open the file and scan it, no network. */
    private fun localRescan(entry: SavedWorkbook): CommentScanResult {
        val file = library.fileOf(entry)
        JavaZipSource(ZipFile(file)).use { zip ->
            WorkbookImageExtractor.requireWorkbook(zip)
            return CommentImageScanner.scan(zip, entry.displayName, entry.sha256)
        }
    }

    // ---- offline: browsing and importing are refused before any request ----------------------

    @Test fun offlineBrowsingMakesNoRequestAtAll() = runBlocking {
        monitor.setOnline(false)

        val listing = browseSession().list(null)

        assertEquals(RemoteFailure.Offline, (listing as OneDriveListing.Failed).failure)
        assertTrue(source.listingRequests.isEmpty())
    }

    @Test fun offlineImportingMakesNoRequestAndSavesNothing() = runBlocking {
        monitor.setOnline(false)

        val outcome = browseSession().importItem(item)

        assertEquals(RemoteFailure.Offline, (outcome as OneDriveImportOutcome.Failed).failure)
        assertEquals(0, source.downloadRequests)
        assertTrue(library.list().isEmpty())
    }

    // ---- offline: the local copy still opens and still rescans -------------------------------

    @Test fun offlineRescanStillReloadsTheLocalCopy() = runBlocking {
        val entry = savedWorkbook()
        monitor.setOnline(false)

        // The remote half: refused, and reported as "showing the copy on this device".
        val check = updateSession().check(entry)
        assertEquals(RemoteFailure.Offline, (check as RemoteCheck.Failed).failure)
        assertEquals(RemoteNotices.OFFLINE_CHECK, RemoteNotices.forCheck(check.failure))

        // The local half the caller then runs: the workbook opens and parses with no network at all.
        val scanned = localRescan(entry)
        assertTrue("the saved copy is still a readable workbook", scanned is CommentScanResult.Success)
        assertNotNull("and it still has its own cache key", entry.sha256)
        assertEquals(WorkbookOrigin.ONEDRIVE, library.byId(entry.id)?.origin)
    }

    @Test fun aLocalWorkbookIsUnaffectedByConnectivityAtAll() {
        val entry = savedWorkbook()
        monitor.setOnline(false)

        // Opening and parsing never consult the monitor: nothing here asks whether there is a network.
        assertTrue(localRescan(entry) is CommentScanResult.Success)
        assertTrue(localRescan(entry) is CommentScanResult.Success)
    }

    // ---- connectivity lost between operations, and recovery when it returns ------------------

    @Test fun losingConnectivityBetweenTwoListingsStopsTheSecond() = runBlocking {
        source.firstPage(RemoteResult.Ok(RemoteListing(null, emptyList(), listOf(item), null)))
        val session = browseSession()
        assertEquals(OneDriveListing.Loaded::class, session.list(null)::class)

        monitor.setOnline(false)
        val second = session.list(null)
        assertEquals(RemoteFailure.Offline, (second as OneDriveListing.Failed).failure)
        assertEquals("the refused call never reached the source", 1, source.listingRequests.size)

        // Back online: the same session works again, which is what "the user can retry" means.
        monitor.setOnline(true)
        assertTrue(session.list(null) is OneDriveListing.Loaded)
        assertEquals(2, source.listingRequests.size)
    }

    @Test fun losingConnectivityBetweenTwoChecksStopsTheSecond() = runBlocking {
        val entry = savedWorkbook()
        source.probeResult = RemoteResult.Ok(RemoteState.Present(item))
        val session = updateSession()
        assertTrue(session.check(entry) is RemoteCheck.Unchanged)

        monitor.setOnline(false)
        assertEquals(
            RemoteFailure.Offline,
            (session.check(entry) as RemoteCheck.Failed).failure,
        )
        assertEquals("only the first check probed", 1, source.probeRequests.size)

        monitor.setOnline(true)
        assertTrue("recovery needs no restart", session.check(entry) is RemoteCheck.Unchanged)
    }


    // ---- authentication: expiry, re-authentication, and a cancelled sign-in -------------------

    @Test fun anExpiredSignInAsksForASignInAndNothingElse() = runBlocking {
        val entry = savedWorkbook()
        source.probeResult = RemoteResult.Err(RemoteFailure.AuthExpired)

        val check = updateSession().check(entry) as RemoteCheck.Failed

        assertEquals(RemoteFailure.AuthExpired, check.failure)
        assertEquals(RemoteFailure.AuthExpired.message(), RemoteNotices.forCheck(check.failure))
        assertFalse("an auth failure is not a moment to retry", check.failure.isRetryable())
        assertTrue("and the local copy is untouched", localRescan(entry) is CommentScanResult.Success)
    }

    @Test fun signingInAgainMakesTheCheckWorkWithoutRestarting() = runBlocking {
        val entry = savedWorkbook()
        source.probeResult = RemoteResult.Err(RemoteFailure.AuthExpired)
        val session = updateSession()
        assertEquals(RemoteFailure.AuthExpired, (session.check(entry) as RemoteCheck.Failed).failure)

        // What an interactive sign-in changes, seen from here: the source can get a token again.
        source.probeResult = RemoteResult.Ok(RemoteState.Present(item))

        assertTrue("the same session recovers", session.check(entry) is RemoteCheck.Unchanged)
    }

    @Test fun aCancelledSignInIsQuiet() {
        // `MsalOneDriveAuth` answers a closed page with SignedOut; the rule below is what keeps that from
        // turning into a message the user did not cause.
        assertEquals(null, AuthState.SignedOut.failureOrNull())
        assertEquals(null, AuthState.SignedIn("someone@example.com").failureOrNull())
        assertEquals(RemoteFailure.AuthExpired, AuthState.Expired("someone@example.com").failureOrNull())
        assertFalse("an expired account is not usable", AuthState.Expired(null).isUsable())
        assertTrue(AuthState.SignedIn(null).isUsable())
        assertFalse(AuthState.SignedOut.isUsable())
    }

    // ---- deletion versus an ordinary network failure -----------------------------------------

    @Test fun aDeletedRemoteFileAndANetworkFailureAreDifferentAnswers() = runBlocking {
        val entry = savedWorkbook()
        val session = updateSession()

        source.probeResult = RemoteResult.Ok(RemoteState.Missing)
        val missing = session.check(entry)
        assertEquals(RemoteCheck.Missing, missing)

        source.probeResult = RemoteResult.Err(RemoteFailure.Unstable)
        val failed = session.check(entry) as RemoteCheck.Failed
        assertEquals(RemoteFailure.Unstable, failed.failure)

        assertEquals(RemoteNotices.missing(), RemoteNotices.missing())
        assertNotEquals(
            "a dropped connection must never be reported as a deletion",
            RemoteNotices.missing(),
            RemoteNotices.forCheck(failed.failure),
        )
        assertTrue("and the copy is still there after both", localRescan(entry) is CommentScanResult.Success)
    }

    // ---- recovery after connectivity returns, for the update itself --------------------------

    @Test fun anUpdateInterruptedByTheNetworkCanBeRetriedAndThenSucceeds() = runBlocking {
        val entry = savedWorkbook()
        val fresh = TestWorkbooks.xlsxBytes("new")
        val before = library.fileOf(entry).readBytes()
        source.content = fresh
        source.chunkBytes = 16
        source.throwAfterBytes = 32 to java.io.IOException("connection reset by peer")
        val session = updateSession()

        val first = session.apply(entry, item)
        assertEquals(RemoteFailure.Unstable, (first as UpdateOutcome.Failed).failure)
        assertArrayEquals("nothing changed on the device", before, library.fileOf(entry).readBytes())
        assertFalse("and no temporary file is left", downloader.partFileFor(item).exists())

        // The connection is back and the retry is a plain second attempt.
        source.throwAfterBytes = null
        val second = session.apply(entry, item)

        val applied = second as UpdateOutcome.Applied
        assertArrayEquals(fresh, library.fileOf(applied.workbook).readBytes())
        assertFalse(downloader.partFileFor(item).exists())
    }
}

