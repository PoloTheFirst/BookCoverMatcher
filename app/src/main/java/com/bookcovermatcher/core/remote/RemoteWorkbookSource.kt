// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

import java.io.OutputStream

/**
 * The app's only door to OneDrive (v2.6.0-dev.1 Phase 3).
 *
 * Everything above this interface — the library, the import flow, the rescan check — talks to
 * [RemoteWorkbookSource], never to Microsoft Graph. That is what makes the whole feature testable
 * against a fake, and what keeps Graph's request plumbing (URLs, headers, ETags, redirects) inside one
 * implementation instead of leaking into the app.
 *
 * **Read-only by construction.** There is no upload, update, move, copy or delete member, and there
 * must never be one: this release imports a copy and refreshes it, and never modifies the OneDrive
 * original. A test asserts the absence, so adding a write member fails the suite rather than passing
 * review unnoticed.
 *
 * Every member reports failure as a [RemoteFailure] instead of throwing, because "the workbook is gone
 * from OneDrive" and "the phone is offline" need different sentences and different recovery.
 */
interface RemoteWorkbookSource {

    /**
     * Lists one folder of the signed-in account's OneDrive, one page at a time.
     *
     * [folderId] is `null` for the drive root. [pageToken] is the `nextPageToken` of a previous page;
     * an implementation must validate it before use (it comes from the server, so it is input, not
     * configuration).
     */
    suspend fun listFolder(folderId: String?, pageToken: String? = null): RemoteResult<RemoteListing>

    /** Reads the metadata of one item, to decide whether the saved copy is still current. */
    suspend fun probe(itemId: String): RemoteResult<RemoteState>

    /**
     * Streams the item's content into [sink], reporting [onProgress] as it goes.
     *
     * The sink is the caller's: this member never decides where bytes land, never sees a file name that
     * matters, and never holds the workbook in memory. Cancellation of the calling coroutine aborts the
     * transfer.
     */
    suspend fun download(
        item: RemoteItem,
        sink: OutputStream,
        onProgress: (DownloadProgress) -> Unit = {},
    ): DownloadOutcome
}
