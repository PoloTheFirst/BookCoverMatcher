// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

import com.bookcovermatcher.core.TestWorkbooks
import com.bookcovermatcher.core.excel.JavaZipSource
import com.bookcovermatcher.core.excel.comments.CommentImageScanner
import com.bookcovermatcher.core.excel.comments.CommentScanResult
import com.bookcovermatcher.core.net.FakeConnectivityMonitor
import com.bookcovermatcher.data.ImportResult
import com.bookcovermatcher.data.RemoteOrigin
import com.bookcovermatcher.data.WorkbookLibrary
import com.bookcovermatcher.data.WorkbookOrigin
import com.bookcovermatcher.data.onedrive.GraphHttp
import com.bookcovermatcher.data.onedrive.GraphWorkbookSource
import com.bookcovermatcher.data.onedrive.OneDriveSession
import com.bookcovermatcher.data.onedrive.OneDriveUpdateSession
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The architectural invariants of v2.6.0, asserted where they can actually break (v2.6.0-dev.1 Phase 7).
 *
 * Each test below names the invariant from the Phase 7 brief and pins the *seam* that would be crossed if
 * someone later wired the remote side into a local path. Invariants already covered elsewhere are listed in
 * the Phase 7 report rather than duplicated here.
 */
class OneDriveIntegrationInvariantsTest {

    private val dir: File = Files.createTempDirectory("invariants").toFile()
    private val library = WorkbookLibrary(dir)
    private val downloader = SafeWorkbookDownload(dir)
    private val monitor = FakeConnectivityMonitor(online = true)
    private val source = FakeRemoteWorkbookSource()

    @After fun cleanUp() {
        dir.listFiles()?.forEach { it.deleteRecursively() }
        dir.delete()
    }

    private fun savedOneDriveWorkbook(): Pair<com.bookcovermatcher.data.SavedWorkbook, ByteArray> {
        val bytes = TestWorkbooks.xlsxBytes("saved")
        val added = (library.import(bytes.inputStream(), "covers.xlsx") as ImportResult.Added).workbook
        val entry = library.setRemote(
            added.id,
            RemoteOrigin(
                itemId = "01ABCDEF!12",
                path = "/drive/root:/Catalogue",
                eTag = "\"{A},1\"",
                lastModified = 1_000L,
                sizeBytes = bytes.size.toLong(),
                syncedAt = 5_000L,
            ),
        )!!
        return entry to bytes
    }

    // ---- invariant 3: only an explicit action may touch the network --------------------------

    @Test fun buildingTheSessionsSendsNothing() {
        // Constructing the components must be inert: no request happens until a user action calls them.
        OneDriveSession(source, monitor, library, downloader, now = { 1L })
        OneDriveUpdateSession(source, monitor, library, downloader, now = { 1L })

        assertTrue(source.listingRequests.isEmpty())
        assertTrue(source.probeRequests.isEmpty())
        assertEquals(0, source.downloadRequests)
    }

    // ---- invariant 1/2: a saved workbook is opened from the device's copy ----------------------

    @Test fun openingASavedOneDriveWorkbookNeverContactsGraph() {
        val (entry, bytes) = savedOneDriveWorkbook()

        // Exactly what `useSavedWorkbook` does: resolve the entry, read the app's own file, parse it.
        val resolved = library.byId(entry.id)!!
        val file = library.fileOf(resolved)
        JavaZipSource(ZipFile(file)).use { zip ->
            assertTrue(CommentImageScanner.scan(zip, resolved.displayName, resolved.sha256) is CommentScanResult.Success)
        }

        assertArrayEquals(bytes, file.readBytes())
        assertEquals(WorkbookOrigin.ONEDRIVE, resolved.origin)
        assertTrue("no listing happened", source.listingRequests.isEmpty())
        assertTrue("no probe happened", source.probeRequests.isEmpty())
        assertEquals("no download happened", 0, source.downloadRequests)
    }

    // ---- invariant 4: the token never leaves a trusted Graph URL ------------------------------

    @Test fun anUntrustedUrlIsRefusedBeforeAnyTokenIsAttached() {
        // The gate runs *before* a connection exists, so a hostile URL cannot even be attempted.
        val hostile = listOf(
            "https://evil.example/v1.0/me/drive/root/children",
            "https://graph.microsoft.com.evil.example/v1.0/me",
            "https://graph.microsoft.com@evil.example/v1.0/me",
            "http://graph.microsoft.com/v1.0/me",
            "https://graph.microsoft.com:8443/v1.0/me",
            "https://graph.microsoft.com/beta/me",
            "https://graph.microsoft.com/v1.0", // no trailing path segment after /v1.0/
        )

        hostile.forEach { url ->
            assertEquals(
                url,
                GraphHttp.JsonOutcome.Failed(RemoteFailure.BadRequest),
                GraphHttp().getJson(url, AccessToken.of("secret-token")),
            )
        }
    }

    @Test fun theTokenIsRedactedWhereverItCouldBePrinted() {
        val token = AccessToken.of("super-secret")

        assertFalse(token.toString().contains("super-secret"))
        assertEquals("AccessToken(***)", token.toString())
        assertEquals("only reveal() hands out the value", "super-secret", token.reveal())
    }

    // ---- invariant 10: signing out is not allowed to touch local data ------------------------

    @Test fun theLibraryCannotHoldAuthenticationOrRemoteState() {
        // A structural guard, like the connectivity one in `LocalWorkbookOperationsTest`: if the library
        // could hold an auth or remote object, a future "sign out" could plausibly clear it — and with it
        // the user's workbooks. It cannot, because it has nowhere to keep one.
        val forbidden = listOf(
            AccessTokenSource::class.java,
            com.bookcovermatcher.core.net.ConnectivityMonitor::class.java,
            com.bookcovermatcher.core.remote.RemoteWorkbookSource::class.java,
            com.bookcovermatcher.data.onedrive.OneDriveAuth::class.java,
            com.bookcovermatcher.data.onedrive.GraphWorkbookSource::class.java,
        )

        val types = WorkbookLibrary::class.java.declaredFields.map { it.type } +
            WorkbookLibrary::class.java.declaredConstructors.flatMap { it.parameterTypes.toList() }

        forbidden.forEach { type ->
            assertTrue(
                "WorkbookLibrary must not depend on ${type.simpleName}, but it has: $types",
                types.none { type.isAssignableFrom(it) },
            )
        }
    }

    // ---- invariant 5/6: a failed remote operation leaves a usable workbook and honest metadata --

    @Test fun aFailedUpdateLeavesTheWorkbookOpenableAndTheMetadataUnchanged() = runBlocking {
        val (entry, bytes) = savedOneDriveWorkbook()
        source.downloadFailure = RemoteFailure.Unstable

        val outcome = OneDriveUpdateSession(source, monitor, library, downloader, now = { 9_000L })
            .apply(entry, RemoteItem(id = "01ABCDEF!12", name = "covers.xlsx", isFolder = false))

        assertTrue(outcome is com.bookcovermatcher.data.onedrive.UpdateOutcome.Failed)
        val after = library.byId(entry.id)!!
        assertEquals("the entry is still there", entry.id, after.id)
        assertArrayEquals("with the same bytes", bytes, library.fileOf(after).readBytes())
        assertEquals("and the same remote version", entry.remote, after.remote)
        assertEquals("no sync was recorded", 5_000L, after.remote?.syncedAt)
        // … and it still opens, which is the part the user cares about.
        JavaZipSource(ZipFile(library.fileOf(after))).use { zip ->
            assertTrue(CommentImageScanner.scan(zip, after.displayName, after.sha256) is CommentScanResult.Success)
        }
    }

    // ---- invariant 7/8: the replacement is validated, and its hash is the one handed on --------

    @Test fun anAppliedReplacementHandsOnAHashThatMatchesTheInstalledFile() = runBlocking {
        val (entry, _) = savedOneDriveWorkbook()
        val fresh = TestWorkbooks.xlsxBytes("fresh")
        source.content = fresh

        val outcome = OneDriveUpdateSession(source, monitor, library, downloader, now = { 9_000L })
            .apply(entry, RemoteItem(id = "01ABCDEF!12", name = "covers.xlsx", isFolder = false))

        val applied = outcome as com.bookcovermatcher.data.onedrive.UpdateOutcome.Applied
        // `MainViewModel.installUpdatedWorkbook` passes `workbook.sha256` to `WorkbookCommentsController
        // .open(...)`; if that value did not describe the installed file, the comment cache would be
        // keyed wrongly. Asserting it against the real bytes is the strongest check available off-device.
        val installed = library.fileOf(applied.workbook)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(installed.readBytes())
            .joinToString("") { "%02x".format(it) }

        assertEquals(digest, applied.workbook.sha256)
        assertFalse("and it is not the old key", applied.workbook.sha256 == entry.sha256)
    }
}
