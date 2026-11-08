// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core

import com.bookcovermatcher.core.remote.FakeRemoteWorkbookSource
import com.bookcovermatcher.core.remote.RemoteFailure
import com.bookcovermatcher.core.remote.RemoteItem
import com.bookcovermatcher.data.onedrive.DownloadResult
import com.bookcovermatcher.data.onedrive.SafeWorkbookDownload
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Streaming download, progress, cancellation and cleanup (Phase 3).
 *
 * Every test here asserts the same invariant from a different angle: **after a download ends in any way
 * other than "ready", no file is left behind**, and a file that is returned has already passed the
 * library's own workbook checks.
 */
class SafeWorkbookDownloadTest {

    private val dir: File = Files.createTempDirectory("safe-download").toFile()
    private val downloader = SafeWorkbookDownload(dir)
    private val item = RemoteItem(id = "01ABC!def", name = "covers.xlsx", isFolder = false)

    @After fun cleanUp() {
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    private fun workbookBytes(): ByteArray {
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
            put("xl/worksheets/sheet1.xml", "<worksheet/>")
        }
        return out.toByteArray()
    }

    private fun filesInDirectory(): List<String> = dir.listFiles().orEmpty().map { it.name }

    @Test fun aValidDownloadIsReturnedAsAValidatedPartFile() = runBlocking {
        val bytes = workbookBytes()
        val source = FakeRemoteWorkbookSource().apply { content = bytes }

        val result = downloader.download(item, source)

        assertTrue("expected Ready, got $result", result is DownloadResult.Ready)
        val ready = result as DownloadResult.Ready
        assertEquals("covers.xlsx", ready.displayName)
        assertEquals(bytes.size.toLong(), ready.sizeBytes)
        assertTrue("the file must exist", ready.file.isFile)
        assertTrue("it stays a .part until the caller commits it", ready.file.name.endsWith(".part"))
        assertEquals("only the temporary file is present", 1, filesInDirectory().size)
    }

    @Test fun progressIsReportedAndNeverGoesBackwards() = runBlocking {
        val bytes = workbookBytes()
        val source = FakeRemoteWorkbookSource().apply {
            content = bytes
            chunkBytes = 16
        }

        downloader.download(item, source)

        assertTrue("progress must be reported", source.progressReports.isNotEmpty())
        val written = source.progressReports.map { it.bytesWritten }
        assertEquals("progress must be monotonic", written.sorted(), written)
        assertEquals(bytes.size.toLong(), written.last())
        assertEquals("the last report knows the total", bytes.size.toLong(), source.progressReports.last().totalBytes)
    }

    @Test fun aTransportFailureDeletesThePartialFile() = runBlocking {
        val source = FakeRemoteWorkbookSource().apply { downloadFailure = RemoteFailure.Unstable }

        val result = downloader.download(item, source)

        assertEquals(RemoteFailure.Unstable, (result as DownloadResult.Failed).failure)
        assertTrue("nothing may be left behind", filesInDirectory().isEmpty())
    }

    @Test fun aFailureAfterSomeBytesDeletesThePartialFile() = runBlocking {
        val source = FakeRemoteWorkbookSource().apply {
            content = workbookBytes()
            chunkBytes = 32
            throwAfterBytes = 64 to IOException("connection reset by peer")
        }

        val result = downloader.download(item, source)

        assertEquals(RemoteFailure.Unstable, (result as DownloadResult.Failed).failure)
        assertTrue("a half-written file must be removed", filesInDirectory().isEmpty())
    }

    @Test fun anExhaustedDeviceIsReportedAsNoStorageAndLeavesNothing() = runBlocking {
        val source = FakeRemoteWorkbookSource().apply {
            content = workbookBytes()
            chunkBytes = 32
            throwAfterBytes = 64 to IOException("write failed: ENOSPC (No space left on device)")
        }

        val result = downloader.download(item, source)

        assertEquals(RemoteFailure.NoStorage, (result as DownloadResult.Failed).failure)
        assertTrue(filesInDirectory().isEmpty())
    }

    @Test fun aCancelledTransferLeavesNothing() = runBlocking {
        val source = FakeRemoteWorkbookSource().apply {
            content = workbookBytes()
            chunkBytes = 32
            cancelAfterBytes = 64
        }

        val result = downloader.download(item, source)

        assertEquals(DownloadResult.Cancelled, result)
        assertTrue(filesInDirectory().isEmpty())
    }

    @Test fun cancellingTheCoroutineRemovesThePartialFile() = runBlocking {
        val source = FakeRemoteWorkbookSource().apply {
            content = workbookBytes()
            chunkBytes = 32
            stallAfterBytes = true
        }
        val part = downloader.partFileFor(item)

        val job = launch { downloader.download(item, source) }
        // Wait until the transfer has actually started writing.
        withTimeout(5_000) {
            while (!part.exists()) delay(10)
        }
        job.cancel()
        job.join()

        assertFalse("a cancelled download must not keep its temporary file", part.exists())
        assertTrue(filesInDirectory().isEmpty())
    }

    @Test fun bytesThatAreNotAWorkbookAreRejectedAndDeleted() = runBlocking {
        val source = FakeRemoteWorkbookSource().apply { content = "<html>error</html>".toByteArray() }

        val result = downloader.download(item, source)

        assertEquals(RemoteFailure.InvalidWorkbook, (result as DownloadResult.Failed).failure)
        assertTrue(filesInDirectory().isEmpty())
    }

    @Test fun theTemporaryNameComesFromTheIdNotTheRemoteName() = runBlocking {
        // A remote name is server-controlled and may contain path separators; the id is what is sanitised.
        val hostile = RemoteItem(id = "A/../../etc/passwd", name = "../../evil.xlsx", isFolder = false)
        val source = FakeRemoteWorkbookSource().apply { content = workbookBytes() }

        val result = downloader.download(hostile, source)

        val ready = result as DownloadResult.Ready
        assertEquals("the file stays inside the download directory", dir.absolutePath, ready.file.parentFile.absolutePath)
        assertTrue(ready.file.name.endsWith(".part"))
        assertFalse(ready.file.name.contains("/"))
        assertFalse(ready.file.name.contains(".."))
    }

    @Test fun aStalePartFileIsReplacedNotAppendedTo() = runBlocking {
        val part = downloader.partFileFor(item)
        part.writeText("leftover from a killed process")
        val bytes = workbookBytes()
        val source = FakeRemoteWorkbookSource().apply { content = bytes }

        val result = downloader.download(item, source)

        assertEquals(bytes.size.toLong(), (result as DownloadResult.Ready).sizeBytes)
        assertEquals("the stale bytes must be gone", bytes.size.toLong(), part.length())
    }
}

