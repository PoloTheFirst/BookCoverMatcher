// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.data.googledrive

import com.bookcovermatcher.data.onedrive.SafeWorkbookDownload
import com.bookcovermatcher.data.onedrive.DownloadResult

import kotlinx.coroutines.ensureActive
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

/** One page of a GoogleDrive folder, or why it could not be read. */
sealed interface GoogleDriveListing {
    data class Loaded(
        val folders: List<RemoteItem>,
        val files: List<RemoteItem>,
        val nextPageToken: String?,
    ) : GoogleDriveListing

    data class Failed(val failure: RemoteFailure) : GoogleDriveListing
}

/** What importing one remote workbook produced. */
sealed interface GoogleDriveImportOutcome {
    /**
     * The workbook is in the library. [alreadySaved] is true when identical bytes were already there, so
     * the UI can say "already saved" rather than "saved".
     */
    data class Imported(
        val workbookId: String,
        val displayName: String,
        val alreadySaved: Boolean,
    ) : GoogleDriveImportOutcome

    /** [detail] carries the library's own wording when it rejected the file, if there is one. */
    data class Failed(val failure: RemoteFailure, val detail: String? = null) : GoogleDriveImportOutcome

    data object Cancelled : GoogleDriveImportOutcome
}

/**
 * The GoogleDrive import session as the ViewModel uses it (v2.6.0-dev.1 Phase 4).
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
 *  * **no write to GoogleDrive** — the only remote operations reachable from here are list and download.
 */
class GoogleDriveSession(
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
    suspend fun list(folderId: String?, pageToken: String? = null): GoogleDriveListing {
        if (!connectivity.isUsableNow()) return GoogleDriveListing.Failed(RemoteFailure.Offline)
        return when (val result = source.listFolder(folderId, pageToken)) {
            is RemoteResult.Ok -> GoogleDriveListing.Loaded(
                folders = result.value.folders,
                files = result.value.files,
                nextPageToken = result.value.nextPageToken,
            )
            is RemoteResult.Err -> GoogleDriveListing.Failed(result.failure)
        }
    }

    /** Imports a validated local copy; remote refresh and account identity persistence are out of scope. */
    suspend fun importItem(
        item: RemoteItem,
        onProgress: (DownloadProgress) -> Unit = {},
    ): GoogleDriveImportOutcome {
        if (item.isFolder || !GoogleDriveFiles.allowedName(item.name)) return GoogleDriveImportOutcome.Failed(RemoteFailure.BadRequest)
        if (!connectivity.isUsableNow()) return GoogleDriveImportOutcome.Failed(RemoteFailure.Offline)

        return when (val downloaded = downloader.download(item, source, onProgress)) {
            DownloadResult.Cancelled -> GoogleDriveImportOutcome.Cancelled
            is DownloadResult.Failed -> GoogleDriveImportOutcome.Failed(downloaded.failure)
            is DownloadResult.Ready -> {
                try {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    commit(downloaded)
                } finally { downloaded.file.delete() }
            }
        }
    }

    /** Copies the validated file into the library, records where it came from, then removes it. */
    private fun commit(downloaded: DownloadResult.Ready): GoogleDriveImportOutcome = try {
        val imported = downloaded.file.inputStream().use { stream ->
            // `use`, not the library's own close: the library closes the stream itself, but not on the
            // early rejections (bad name, unusable directory), and a leaked descriptor per failed
            // import is not worth arguing about.
            library.import(stream, downloaded.displayName, now = now(), origin = WorkbookOrigin.GOOGLE_DRIVE)
        }
        when (imported) {
            is ImportResult.Added -> {
                GoogleDriveImportOutcome.Imported(imported.workbook.id, imported.workbook.displayName, false)
            }
            is ImportResult.AlreadySaved -> {
                // The same bytes are already saved. Record the GoogleDrive source so a later refresh has
                // somewhere to check — `linkRemote` decides whether that is safe.
                // Keep an existing duplicate entry and its original provider unchanged.
                GoogleDriveImportOutcome.Imported(imported.workbook.id, imported.workbook.displayName, true)
            }
            is ImportResult.Rejected -> GoogleDriveImportOutcome.Failed(
                RemoteFailure.InvalidWorkbook,
                imported.message,
            )
        }
    } finally {
        // The library has its own copy now (or the import failed); the temporary file is never kept.
        runCatching { downloaded.file.delete() }
    }

}
