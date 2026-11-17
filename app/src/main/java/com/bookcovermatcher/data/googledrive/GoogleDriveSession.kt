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
    private val account: () -> com.bookcovermatcher.core.remote.SourceAccount? = { null },
    private val generation: () -> Long = { 0L },
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
        relinkId: String? = null,
        onProgress: (DownloadProgress) -> Unit = {},
    ): GoogleDriveImportOutcome {
        val identity = account()
        val epoch = generation()
        if (item.isFolder || !GoogleDriveFiles.allowedName(item.name)) return GoogleDriveImportOutcome.Failed(RemoteFailure.BadRequest)
        if (!connectivity.isUsableNow()) return GoogleDriveImportOutcome.Failed(RemoteFailure.Offline)

        return when (val downloaded = downloader.download(item, source, onProgress)) {
            DownloadResult.Cancelled -> GoogleDriveImportOutcome.Cancelled
            is DownloadResult.Failed -> GoogleDriveImportOutcome.Failed(downloaded.failure)
            is DownloadResult.Ready -> {
                try {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (epoch != generation() || identity != account()) GoogleDriveImportOutcome.Cancelled
                    else if (relinkId != null) relink(relinkId, downloaded, item, identity)
                    else commit(downloaded, item, identity)
                } finally { downloaded.file.delete() }
            }
        }
    }

    private fun linkSource(workbook: SavedWorkbook, item: RemoteItem, identity: com.bookcovermatcher.core.remote.SourceAccount?) {
        val stamp = now()
        library.setRemote(workbook.id, RemoteOrigin(itemId = item.id, path = item.path,
            eTag = item.eTag, cTag = item.cTag, lastModified = item.lastModified,
            sizeBytes = item.sizeBytes, syncedAt = stamp, checkedAt = stamp, accountKey = identity?.key, accountLabel = identity?.label), WorkbookOrigin.GOOGLE_DRIVE)
    }

    private suspend fun relink(id: String, downloaded: DownloadResult.Ready, item: RemoteItem,
        identity: com.bookcovermatcher.core.remote.SourceAccount?): GoogleDriveImportOutcome {
        val entry = library.byId(id)
        if (identity == null || entry == null || entry.origin != com.bookcovermatcher.data.WorkbookOrigin.GOOGLE_DRIVE)
            return GoogleDriveImportOutcome.Failed(RemoteFailure.BadRequest, "Sign in to the source account and try again.")
        val epoch = generation()
        val hash = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            downloaded.file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (epoch != generation() || identity != account()) return GoogleDriveImportOutcome.Cancelled
        if (library.byId(id)?.sha256 != entry.sha256) return GoogleDriveImportOutcome.Cancelled
        if (hash != entry.sha256) return GoogleDriveImportOutcome.Failed(RemoteFailure.InvalidWorkbook,
            "Select an unchanged copy of this workbook. Nothing was linked or replaced.")
        val stamp = now()
        val saved = library.setRemote(id, RemoteOrigin(item.id, item.path, item.eTag, item.cTag,
            item.lastModified, item.sizeBytes, stamp, stamp, identity.key, identity.label),
            com.bookcovermatcher.data.WorkbookOrigin.GOOGLE_DRIVE)
        return if (saved != null) GoogleDriveImportOutcome.Imported(id, entry.displayName, true)
            else GoogleDriveImportOutcome.Failed(RemoteFailure.NoStorage)
    }

    /** Copies the validated file into the library, records where it came from, then removes it. */
    private fun commit(downloaded: DownloadResult.Ready, item: RemoteItem, identity: com.bookcovermatcher.core.remote.SourceAccount?): GoogleDriveImportOutcome = try {
        val imported = downloaded.file.inputStream().use { stream ->
            // `use`, not the library's own close: the library closes the stream itself, but not on the
            // early rejections (bad name, unusable directory), and a leaked descriptor per failed
            // import is not worth arguing about.
            library.import(stream, downloaded.displayName, now = now(), origin = WorkbookOrigin.GOOGLE_DRIVE)
        }
        when (imported) {
            is ImportResult.Added -> {
                linkSource(imported.workbook, item, identity)
                GoogleDriveImportOutcome.Imported(imported.workbook.id, imported.workbook.displayName, false)
            }
            is ImportResult.AlreadySaved -> {
                // A duplicate preserves its existing source, including unknown legacy identity.
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
