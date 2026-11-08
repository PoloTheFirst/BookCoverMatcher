// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

import com.bookcovermatcher.core.TestWorkbooks
import com.bookcovermatcher.core.net.FakeConnectivityMonitor
import com.bookcovermatcher.data.ImportResult
import com.bookcovermatcher.data.RemoteOrigin
import com.bookcovermatcher.data.ReplaceResult
import com.bookcovermatcher.data.SavedWorkbook
import com.bookcovermatcher.data.WorkbookLibrary
import com.bookcovermatcher.data.WorkbookOrigin
import com.bookcovermatcher.data.onedrive.OneDriveUpdateSession
import com.bookcovermatcher.data.onedrive.RemoteCheck
import com.bookcovermatcher.data.onedrive.SafeWorkbookDownload
import com.bookcovermatcher.data.onedrive.UpdateOutcome
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The explicit remote check and the verified replacement (v2.6.0-dev.1 Phase 5).
 *
 * Every case that ends badly asserts the same two things: the old bytes are still on disk **and** the
 * library still describes them. A download that failed, was cancelled or produced rubbish must leave the
 * user exactly where they were.
 */
class OneDriveUpdateSessionTest {

    private val dir: File = Files.createTempDirectory("onedrive-update").toFile()
    private val library = WorkbookLibrary(dir)
    private val downloader = SafeWorkbookDownload(dir)
    private val monitor = FakeConnectivityMonitor(online = true)
    private val source = FakeRemoteWorkbookSource()

    private val eTag = "\"{AAA},3\""
    private val cTag = "c:3"
    private val modified = 1_794_100_000_000L

    @After fun cleanUp() {
        dir.listFiles()?.forEach { it.deleteRecursively() }
        dir.delete()
    }

    private fun session(now: Long = 9_000L) = OneDriveUpdateSession(
        source = source,
        connectivity = monitor,
        library = library,
        downloader = downloader,
        now = { now },
    )

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A saved OneDrive workbook whose local copy is the "old" bytes. */
    private fun savedWorkbook(bytes: ByteArray = TestWorkbooks.xlsxBytes("old")): SavedWorkbook {
        val added = (library.import(bytes.inputStream(), "covers.xlsx") as ImportResult.Added).workbook
        return library.setRemote(
            added.id,
            RemoteOrigin(
                itemId = "01ABCDEF!12",
                path = "/drive/root:/Catalogue",
                eTag = eTag,
                cTag = cTag,
                lastModified = modified,
                sizeBytes = bytes.size.toLong(),
                syncedAt = 5_000L,
                checkedAt = 5_000L,
            ),
        )!!
    }

    private fun probe(
        eTag: String? = this.eTag,
        cTag: String? = this.cTag,
        modified: Long? = this.modified,
        size: Long? = 4_000L,
        name: String = "covers.xlsx",
    ): RemoteItem = RemoteItem(
        id = "01ABCDEF!12",
        name = name,
        isFolder = false,
        sizeBytes = size,
        eTag = eTag,
        cTag = cTag,
        lastModified = modified,
        path = "/drive/root:/Catalogue",
    )

    private fun filesInDir(): List<String> = dir.listFiles().orEmpty().map { it.name }.sorted()

    // ---- the check ---------------------------------------------------------------------------

    @Test fun anOfflineDeviceMakesNoProbeAtAll() = runBlocking {
        val entry = savedWorkbook()
        monitor.setOnline(false)

        val check = session().check(entry)

        assertEquals(RemoteFailure.Offline, (check as RemoteCheck.Failed).failure)
        assertTrue("no request may leave the device", source.probeRequests.isEmpty())
    }

    @Test fun anEqualETagIsReportedUnchanged() = runBlocking {
        val entry = savedWorkbook()
        source.probeResult = RemoteResult.Ok(RemoteState.Present(probe()))

        val check = session().check(entry)

        assertTrue(check is RemoteCheck.Unchanged)
        assertEquals(listOf("01ABCDEF!12"), source.probeRequests)
    }

    @Test fun aDifferentETagIsReportedChanged() = runBlocking {
        val entry = savedWorkbook()
        source.probeResult = RemoteResult.Ok(RemoteState.Present(probe(eTag = "\"{AAA},4\"")))

        assertTrue(session().check(entry) is RemoteCheck.Changed)
    }

    @Test fun aChangedCTagIsReportedChangedWhenNoETagIsComparable() = runBlocking {
        val entry = savedWorkbook()
        source.probeResult = RemoteResult.Ok(
            RemoteState.Present(probe(eTag = null, cTag = "c:4", modified = null, size = null)),
        )

        assertTrue(session().check(entry) is RemoteCheck.Changed)
    }

    @Test fun anEqualCTagIsReportedUnchangedWhenNoETagIsComparable() = runBlocking {
        val entry = savedWorkbook()
        source.probeResult = RemoteResult.Ok(
            RemoteState.Present(probe(eTag = null, cTag = cTag, modified = null, size = null)),
        )

        assertTrue(session().check(entry) is RemoteCheck.Unchanged)
    }

    @Test fun theTimestampAndSizeAreUsedWhenNoTagIsAvailable() = runBlocking {
        val entry = savedWorkbook()
        source.probeResult = RemoteResult.Ok(
            RemoteState.Present(probe(eTag = null, cTag = null, modified = modified + 1_000L, size = null)),
        )

        assertTrue("a newer timestamp is a change", session().check(entry) is RemoteCheck.Changed)
    }

    @Test fun missingMetadataIsUnknownNotUnchanged() = runBlocking {
        val entry = savedWorkbook()
        source.probeResult = RemoteResult.Ok(
            RemoteState.Present(probe(eTag = null, cTag = null, modified = null, size = null)),
        )

        assertEquals(RemoteCheck.Unknown, session().check(entry))
    }

    @Test fun aProbeThatFindsNothingIsReportedAsMissing() = runBlocking {
        val entry = savedWorkbook()
        source.probeResult = RemoteResult.Ok(RemoteState.Missing)

        assertEquals(RemoteCheck.Missing, session().check(entry))
    }

    @Test fun throttlingKeepsItsRetryAfter() = runBlocking {
        val entry = savedWorkbook()
        source.probeResult = RemoteResult.Err(RemoteFailure.Throttled(42))

        assertEquals(RemoteFailure.Throttled(42), (session().check(entry) as RemoteCheck.Failed).failure)
    }

    @Test fun anExpiredSignInIsReportedAsSuch() = runBlocking {
        val entry = savedWorkbook()
        source.probeResult = RemoteResult.Err(RemoteFailure.AuthExpired)

        assertEquals(RemoteFailure.AuthExpired, (session().check(entry) as RemoteCheck.Failed).failure)
    }

    @Test fun anEntryWithNoRemoteMetadataCannotBeChecked() = runBlocking {
        val local = (library.import(TestWorkbooks.xlsxBytes().inputStream(), "local.xlsx") as ImportResult.Added).workbook

        assertEquals(RemoteCheck.Unknown, session().check(local))
        assertTrue(source.probeRequests.isEmpty())
    }

    // ---- the replacement ---------------------------------------------------------------------

    @Test fun aNewerVersionIsInstalledWithANewCacheKeyAndTheRemoteMetadata() = runBlocking {
        val entry = savedWorkbook()
        val fresh = TestWorkbooks.xlsxBytes("new")
        source.content = fresh

        val outcome = session().apply(entry, probe(eTag = "\"{AAA},4\"", cTag = "c:4", size = fresh.size.toLong()))

        val applied = outcome as UpdateOutcome.Applied
        assertEquals("the new bytes have their own cache key", sha(fresh), applied.workbook.sha256)
        assertTrue("which is not the old one", applied.workbook.sha256 != entry.sha256)
        assertEquals(fresh.size.toLong(), applied.workbook.sizeBytes)
        assertEquals("the entry keeps its identity", entry.id, applied.workbook.id)
        assertEquals(WorkbookOrigin.ONEDRIVE, applied.workbook.origin)
        assertEquals("\"{AAA},4\"", applied.workbook.remote?.eTag)
        assertEquals("c:4", applied.workbook.remote?.cTag)
        assertEquals(9_000L, applied.workbook.remote?.syncedAt)
        assertEquals(9_000L, applied.workbook.remote?.checkedAt)
        // The new cTag has to survive the index round-trip, or the next comparison loses its evidence.
        assertEquals("c:4", library.byId(entry.id)?.remote?.cTag)
        assertEquals(sha(fresh), library.byId(entry.id)?.sha256)
        assertArrayEquals("the library file is the new version", fresh, library.fileOf(applied.workbook).readBytes())
        assertFalse("the .part file is gone", downloader.partFileFor(probe()).exists())
        assertEquals(
            "only the workbook and the index remain",
            listOf(applied.workbook.fileName, "library.json"),
            filesInDir(),
        )
    }

    @Test fun theDownloadedBytesAreReportedAsProgress() = runBlocking {
        val entry = savedWorkbook()
        source.content = TestWorkbooks.xlsxBytes("new")
        source.chunkBytes = 16
        val seen = mutableListOf<Long>()

        session().apply(entry, probe(size = source.content.size.toLong())) { seen += it.bytesWritten }

        assertTrue(seen.isNotEmpty())
        assertEquals("progress is monotonic", seen.sorted(), seen)
    }

    @Test fun aDownloadFailureKeepsTheOldCopyAndSaysWhy() = runBlocking {
        val entry = savedWorkbook()
        val before = library.fileOf(entry).readBytes()
        source.downloadFailure = RemoteFailure.Throttled(30)

        val outcome = session().apply(entry, probe(eTag = "\"{AAA},4\""))

        assertEquals(RemoteFailure.Throttled(30), (outcome as UpdateOutcome.Failed).failure)
        assertArrayEquals("the old bytes are intact", before, library.fileOf(entry).readBytes())
        assertEquals("the metadata was not advanced", entry.remote, library.byId(entry.id)?.remote)
        assertFalse("no temporary file survives", downloader.partFileFor(probe()).exists())
    }

    @Test fun aCancelledDownloadKeepsTheOldCopy() = runBlocking {
        val entry = savedWorkbook()
        val before = library.fileOf(entry).readBytes()
        source.content = TestWorkbooks.xlsxBytes("new")
        source.chunkBytes = 16
        source.cancelAfterBytes = 32

        val outcome = session().apply(entry, probe(eTag = "\"{AAA},4\""))

        assertEquals(UpdateOutcome.Cancelled, outcome)
        assertArrayEquals(before, library.fileOf(entry).readBytes())
        assertEquals(entry.remote, library.byId(entry.id)?.remote)
        assertEquals("nothing is left behind", listOf(entry.fileName, "library.json"), filesInDir())
    }

    @Test fun aNetworkInterruptionKeepsTheOldCopy() = runBlocking {
        val entry = savedWorkbook()
        val before = library.fileOf(entry).readBytes()
        source.content = TestWorkbooks.xlsxBytes("new")
        source.chunkBytes = 16
        source.throwAfterBytes = 32 to IOException("connection reset by peer")

        val outcome = session().apply(entry, probe(eTag = "\"{AAA},4\""))

        assertEquals(RemoteFailure.Unstable, (outcome as UpdateOutcome.Failed).failure)
        assertArrayEquals(before, library.fileOf(entry).readBytes())
        assertFalse(downloader.partFileFor(probe()).exists())
    }

    @Test fun anExhaustedDeviceKeepsTheOldCopy() = runBlocking {
        val entry = savedWorkbook()
        val before = library.fileOf(entry).readBytes()
        source.content = TestWorkbooks.xlsxBytes("new")
        source.chunkBytes = 16
        source.throwAfterBytes = 32 to IOException("write failed: ENOSPC (No space left on device)")

        val outcome = session().apply(entry, probe(eTag = "\"{AAA},4\""))

        assertEquals(RemoteFailure.NoStorage, (outcome as UpdateOutcome.Failed).failure)
        assertArrayEquals(before, library.fileOf(entry).readBytes())
        assertEquals(entry.remote, library.byId(entry.id)?.remote)
    }

    @Test fun anInvalidReplacementIsRejectedAndTheOldCopySurvives() = runBlocking {
        val entry = savedWorkbook()
        val before = library.fileOf(entry).readBytes()
        source.content = "<html>Sign in to continue</html>".toByteArray()

        val outcome = session().apply(entry, probe(eTag = "\"{AAA},4\""))

        assertEquals(RemoteFailure.InvalidWorkbook, (outcome as UpdateOutcome.Failed).failure)
        assertArrayEquals(before, library.fileOf(entry).readBytes())
        assertEquals("the metadata still describes the saved bytes", entry.remote, library.byId(entry.id)?.remote)
        assertFalse("the rejected bytes are deleted", downloader.partFileFor(probe()).exists())
    }

    @Test fun anOfflineDeviceDoesNotStartADownload() = runBlocking {
        val entry = savedWorkbook()
        monitor.setOnline(false)

        val outcome = session().apply(entry, probe())

        assertEquals(RemoteFailure.Offline, (outcome as UpdateOutcome.Failed).failure)
        assertEquals(0, source.downloadRequests)
    }

    @Test fun identicalBytesOnlyRefreshTheMetadata() = runBlocking {
        val entry = savedWorkbook()
        val before = library.fileOf(entry).readBytes()
        source.content = before

        val outcome = session().apply(entry, probe(eTag = "\"{AAA},9\"", size = before.size.toLong()))

        val same = outcome as UpdateOutcome.SameContent
        assertEquals("the cache key is the same bytes", entry.sha256, same.workbook.sha256)
        assertEquals("\"{AAA},9\"", same.workbook.remote?.eTag)
        assertArrayEquals("the copy was not rewritten", before, library.fileOf(entry).readBytes())
    }

    @Test fun aRejectedInstallKeepsTheOldCopyAndReportsTheLibrarysReason() = runBlocking {
        val entry = savedWorkbook()
        val before = library.fileOf(entry).readBytes()
        source.content = TestWorkbooks.xlsxBytes("new")
        // Blocking the index temp file makes the library's write fail after the swap; the rollback must
        // put the old bytes back (the same seam `WorkbookLibraryReplaceTest` uses).
        assertTrue(File(dir, "library.json.tmp").mkdir())

        val outcome = session().apply(entry, probe(eTag = "\"{AAA},4\""))

        assertTrue(outcome is UpdateOutcome.Rejected)
        assertArrayEquals("the old bytes are restored", before, library.fileOf(entry).readBytes())
        assertEquals(entry.sha256, library.byId(entry.id)?.sha256)
        assertFalse("the .part file is gone", downloader.partFileFor(probe()).exists())
    }

    // ---- recording what the check found ------------------------------------------------------

    @Test fun recordingAnUnchangedCheckMovesOnlyTheCheckTime() = runBlocking {
        val entry = savedWorkbook()
        val probed = probe(eTag = eTag, cTag = cTag, modified = modified)

        val recorded = session(now = 11_000L).recordUnchanged(entry, probed)!!

        assertEquals("the version is the same", entry.remote?.eTag, recorded.remote?.eTag)
        assertEquals("the sync time is untouched", 5_000L, recorded.remote?.syncedAt)
        assertEquals("only the check time moved", 11_000L, recorded.remote?.checkedAt)
        assertEquals("and it is persisted", 11_000L, library.byId(entry.id)?.remote?.checkedAt)
    }

    @Test fun recordingADeclinedUpdateDoesNotClaimTheSavedCopyMatchesOneDrive() = runBlocking {
        val entry = savedWorkbook()
        val newer = probe(eTag = "\"{AAA},4\"", cTag = "c:4", modified = modified + 5_000L, size = 9_999L)

        val recorded = session(now = 11_000L).recordCheckedOnly(entry, newer)!!

        assertEquals("the saved copy's ETag is kept", eTag, recorded.remote?.eTag)
        assertEquals("its cTag too", cTag, recorded.remote?.cTag)
        assertEquals(modified, recorded.remote?.lastModified)
        assertEquals(entry.sizeBytes, recorded.remote?.sizeBytes)
        assertEquals("the sync time still describes the local bytes", 5_000L, recorded.remote?.syncedAt)
        assertEquals(11_000L, recorded.remote?.checkedAt)
        assertEquals(
            "so the next check still sees a newer version",
            RemoteChange.CHANGED,
            RemoteChangeDetector.compare(
                recorded.remote?.eTag,
                recorded.remote?.cTag,
                recorded.remote?.lastModified,
                recorded.remote?.sizeBytes,
                newer,
            ),
        )
    }

    @Test fun aProbeWithoutADisplayPathDoesNotEraseTheRecordedOne() = runBlocking {
        val entry = savedWorkbook()

        val recorded = session().recordUnchanged(entry, probe().copy(path = ""))!!

        assertEquals("/drive/root:/Catalogue", recorded.remote?.path)
    }

    @Test fun nothingIsRecordedForAnEntryWithNoRemoteBlock() = runBlocking {
        val local = (library.import(TestWorkbooks.xlsxBytes("l").inputStream(), "local.xlsx") as ImportResult.Added).workbook

        assertNull(session().recordUnchanged(local, probe()))
        assertNull(session().recordCheckedOnly(local, probe()))
        assertNull(library.byId(local.id)?.remote)
    }
}

