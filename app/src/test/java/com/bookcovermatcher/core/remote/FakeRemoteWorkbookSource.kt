// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.delay

/**
 * A scripted [RemoteWorkbookSource] for deterministic tests (v2.6.0-dev.1 Phase 3).
 *
 * No network, no clock beyond an explicit [stall] hook, and every request recorded — so a test can
 * assert *what* was asked (folder id, page token, item id) as well as what came back. It can also be
 * told to fail at a chosen byte, to stall (for cancellation tests) or to throw the `ENOSPC` an
 * exhausted device would produce.
 */
class FakeRemoteWorkbookSource : RemoteWorkbookSource {

    // ---- listing ---------------------------------------------------------------------------

    /** Pages keyed by the page token that requests them; `null` key is the first page. */
    val pages: MutableMap<String?, RemoteResult<RemoteListing>> = mutableMapOf()

    /** Every (folderId, pageToken) pair the code under test asked for, in order. */
    val listingRequests = mutableListOf<Pair<String?, String?>>()

    override suspend fun listFolder(folderId: String?, pageToken: String?): RemoteResult<RemoteListing> {
        listingRequests += folderId to pageToken
        val key = pageToken ?: FIRST_PAGE
        return pages[key] ?: RemoteResult.Err(RemoteFailure.NotFound)
    }

    /** Seeds the page requested without a token: the first page of any folder. */
    fun firstPage(result: RemoteResult<RemoteListing>) {
        pages[FIRST_PAGE] = result
    }

    // ---- probing ---------------------------------------------------------------------------

    val probeRequests = mutableListOf<String>()
    var probeResult: RemoteResult<RemoteState> = RemoteResult.Err(RemoteFailure.NotFound)

    override suspend fun probe(itemId: String): RemoteResult<RemoteState> {
        probeRequests += itemId
        return probeResult
    }

    // ---- downloading -----------------------------------------------------------------------

    /** Bytes the "server" sends. */
    var content: ByteArray = ByteArray(0)

    /** Chunk size per `write`, so progress reporting can be observed over several steps. */
    var chunkBytes: Int = 64 * 1024

    /** When set, the download fails with this failure before sending anything. */
    var downloadFailure: RemoteFailure? = null

    /** When set, the download reports `Cancelled` after this many bytes. */
    var cancelAfterBytes: Int? = null

    /** When set, [download] throws this after writing that many bytes (a mid-transfer failure). */
    var throwAfterBytes: Pair<Int, IOException>? = null

    /** When true, [download] writes what it can and then suspends, so a test can cancel it. */
    var stallAfterBytes: Boolean = false

    /** Every progress report the source made, in order. */
    val progressReports = mutableListOf<DownloadProgress>()

    var downloadRequests = 0
        private set

    override suspend fun download(
        item: RemoteItem,
        sink: OutputStream,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadOutcome {
        downloadRequests++
        downloadFailure?.let { return DownloadOutcome.Failed(it) }

        val total = content.size.toLong()
        var offset = 0
        while (offset < content.size) {
            val limit = minOf(chunkBytes, content.size - offset)
            val chunk = limit.coerceAtLeast(1)
            sink.write(content, offset, chunk)
            offset += chunk
            val progress = DownloadProgress(offset.toLong(), total)
            progressReports += progress
            onProgress(progress)
            throwAfterBytes?.let { (at, error) -> if (offset >= at) throw error }
            cancelAfterBytes?.let { if (offset >= it) return DownloadOutcome.Cancelled }
        }
        sink.flush()
        if (stallAfterBytes) delay(Long.MAX_VALUE / 2)
        return DownloadOutcome.Completed(offset.toLong())
    }

    private companion object {
        const val FIRST_PAGE = "\u0000first"
    }
}
