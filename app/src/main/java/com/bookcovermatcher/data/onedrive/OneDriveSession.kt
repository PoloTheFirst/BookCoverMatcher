// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.data.onedrive

import com.bookcovermatcher.core.net.ConnectivityMonitor
import com.bookcovermatcher.core.remote.DownloadProgress
import com.bookcovermatcher.core.remote.RemoteFailure
import com.bookcovermatcher.core.remote.RemoteItem
import com.bookcovermatcher.core.remote.RemoteResult
import com.bookcovermatcher.core.remote.RemoteWorkbookSource
import com.bookcovermatcher.data.ImportResult
import com.bookcovermatcher.data.RemoteOrigin
import com.bookcovermatcher.data.SavedWorkbook
import com.bookcovermatcher.data.WorkbookLibrary
import com.bookcovermatcher.data.WorkbookOrigin

/** One page of a OneDrive folder, or why it could not be read. */
sealed interface OneDriveListing {
    data class Loaded(
        val folders: List<RemoteItem>,
        val files: List<RemoteItem>,
        val nextPageToken: String?,
    ) : OneDriveListing

    data class Failed(val failure: RemoteFailure) : OneDriveListing
}

/** What importing one remote workbook produced. */
sealed interface OneDriveImportOutcome {
    /**
     * The workbook is in the library. [alreadySaved] is true when identical bytes were already there, so
     * the UI can say "already saved" rather than "saved".
     */
    data class Imported(
        val workbookId: String,
        val displayName: String,
        val alreadySaved: Boolean,
    ) : OneDriveImportOutcome

    /** [detail] carries the library's own wording when it rejected the file, if there is one. */
    data class Failed(val failure: RemoteFailure, val detail: String? = null) : OneDriveImportOutcome

    data object Cancelled : OneDriveImportOutcome
}

/**
 * The OneDrive import session as the ViewModel uses it (v2.6.0-dev.1 Phase 4).
 *
 * Everything that decides *what happens* lives here — the connectivity preflight, the listing call, the
 * download, the commit into [WorkbookLibrary] and the remote metadata — so all of it is unit-tested with
 * fakes. The ViewModel keeps the `UiState` and the coroutine lifetime; the UI keeps the drawing.
 *
 * Guarantees this class is responsible for:
 *
 *  * **no remote call without a usable network** — the preflight is here, not in a composable;
 *  * **no incomplete workbook in the library** — bytes only arrive through [SafeWorkbookDownload], which
 *    returns a validated `*.part`, and the library's own `import` is what copies it in;
 *  * **no temporary file left behind** — the `*.part` is deleted after the commit, on every path;
 *  * **no write to OneDrive** — the only remote operations reachable from here are list and download.
 */
class OneDriveSession(
    private val source: RemoteWorkbookSource,
    private val connectivity: ConnectivityMonitor,
    private val library: WorkbookLibrary,
    private val downloader: SafeWorkbookDownload,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Lists one folder, or one page of it.
     *
     * [folderId] `null` is the drive root. [pageToken] is a `nextPageToken` from a previous page; the
     * source validates it before any request, so a hostile or stale link becomes a reported failure.
     */
    suspend fun list(folderId: String?, pageToken: String? = null): OneDriveListing {
        if (!connectivity.isUsableNow()) return OneDriveListing.Failed(RemoteFailure.Offline)
        return when (val result = source.listFolder(folderId, pageToken)) {
            is RemoteResult.Ok -> OneDriveListing.Loaded(
                folders = result.value.folders,
                files = result.value.files,
                nextPageToken = result.value.nextPageToken,
            )
            is RemoteResult.Err -> OneDriveListing.Failed(result.failure)
        }
    }

    /**
     * Downloads [item], validates it, and commits it through [WorkbookLibrary].
     *
     * The remote metadata (item id, display path, ETag, modified time, size) is recorded on the entry, so
     * the library knows where the copy came from without reading anything from the network again —
     * opening the workbook stays a purely local operation.
     */
    suspend fun importItem(
        item: RemoteItem,
        onProgress: (DownloadProgress) -> Unit = {},
    ): OneDriveImportOutcome {
        if (item.isFolder) return OneDriveImportOutcome.Failed(RemoteFailure.BadRequest)
        if (!connectivity.isUsableNow()) return OneDriveImportOutcome.Failed(RemoteFailure.Offline)

        return when (val downloaded = downloader.download(item, source, onProgress)) {
            DownloadResult.Cancelled -> OneDriveImportOutcome.Cancelled
            is DownloadResult.Failed -> OneDriveImportOutcome.Failed(downloaded.failure)
            is DownloadResult.Ready -> commit(item, downloaded)
        }
    }

    /** Copies the validated file into the library, records where it came from, then removes it. */
    private fun commit(item: RemoteItem, downloaded: DownloadResult.Ready): OneDriveImportOutcome = try {
        val imported = downloaded.file.inputStream().use { stream ->
            // `use`, not the library's own close: the library closes the stream itself, but not on the
            // early rejections (bad name, unusable directory), and a leaked descriptor per failed
            // import is not worth arguing about.
            library.import(stream, downloaded.displayName, now = now())
        }
        when (imported) {
            is ImportResult.Added -> {
                linkRemote(imported.workbook, item)
                OneDriveImportOutcome.Imported(imported.workbook.id, imported.workbook.displayName, false)
            }
            is ImportResult.AlreadySaved -> {
                // The same bytes are already saved. Record the OneDrive source so a later refresh has
                // somewhere to check — `linkRemote` decides whether that is safe.
                linkRemote(imported.workbook, item)
                OneDriveImportOutcome.Imported(imported.workbook.id, imported.workbook.displayName, true)
            }
            is ImportResult.Rejected -> OneDriveImportOutcome.Failed(
                RemoteFailure.InvalidWorkbook,
                imported.message,
            )
        }
    } finally {
        // The library has its own copy now (or the import failed); the temporary file is never kept.
        runCatching { downloaded.file.delete() }
    }

    /**
     * Records the remote origin on [workbook], when that cannot mislabel it.
     *
     * Safe means: the entry is local (it gains a source it never had), or it already points at this same
     * remote item (its metadata is refreshed). An entry that came from a *different* OneDrive file with
     * identical bytes is left alone rather than silently re-pointed.
     */
    private fun linkRemote(workbook: SavedWorkbook, item: RemoteItem) {
        val current = library.byId(workbook.id) ?: return
        val safe = current.origin == WorkbookOrigin.LOCAL ||
            (current.origin == WorkbookOrigin.ONEDRIVE && current.remote?.itemId == item.id)
        if (!safe) return
        val stamp = now()
        library.setRemote(
            workbook.id,
            RemoteOrigin(
                itemId = item.id,
                path = item.path,
                eTag = item.eTag,
                lastModified = item.lastModified,
                sizeBytes = item.sizeBytes ?: current.sizeBytes,
                // The bytes were just fetched and validated, so this import is also the first check.
                syncedAt = stamp,
                checkedAt = stamp,
            ),
        )
    }
}

