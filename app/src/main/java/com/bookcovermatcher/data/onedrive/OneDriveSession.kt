// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.data.onedrive

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
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
    private val account: () -> com.bookcovermatcher.core.remote.SourceAccount? = { null },
    private val generation: () -> Long = { 0L },
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
        relinkId: String? = null,
        onProgress: (DownloadProgress) -> Unit = {},
    ): OneDriveImportOutcome {
        val identity = account()
        val epoch = generation()
        if (item.isFolder) return OneDriveImportOutcome.Failed(RemoteFailure.BadRequest)
        if (!connectivity.isUsableNow()) return OneDriveImportOutcome.Failed(RemoteFailure.Offline)

        return when (val downloaded = downloader.download(item, source, onProgress)) {
            DownloadResult.Cancelled -> OneDriveImportOutcome.Cancelled
            is DownloadResult.Failed -> OneDriveImportOutcome.Failed(downloaded.failure)
            is DownloadResult.Ready -> {
                if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                    downloaded.file.delete()
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                }
                if (epoch != generation() || identity != account()) {
                    downloaded.file.delete()
                    OneDriveImportOutcome.Cancelled
                } else if (relinkId != null) {
                    try { relink(relinkId, downloaded, item, identity) } finally { downloaded.file.delete() }
                } else commit(item, downloaded, identity)
            }
        }
    }

    private suspend fun relink(id: String, downloaded: DownloadResult.Ready, item: RemoteItem,
        identity: com.bookcovermatcher.core.remote.SourceAccount?): OneDriveImportOutcome {
        val entry = library.byId(id)
        if (identity == null || entry == null || entry.origin != com.bookcovermatcher.data.WorkbookOrigin.ONEDRIVE)
            return OneDriveImportOutcome.Failed(RemoteFailure.BadRequest, "Sign in to the source account and try again.")
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
        if (epoch != generation() || identity != account()) return OneDriveImportOutcome.Cancelled
        if (library.byId(id)?.sha256 != entry.sha256) return OneDriveImportOutcome.Cancelled
        if (hash != entry.sha256) return OneDriveImportOutcome.Failed(RemoteFailure.InvalidWorkbook,
            "Select an unchanged copy of this workbook. Nothing was linked or replaced.")
        val stamp = now()
        val saved = library.setRemote(id, RemoteOrigin(item.id, item.path, item.eTag, item.cTag,
            item.lastModified, item.sizeBytes, stamp, stamp, identity.key, identity.label),
            com.bookcovermatcher.data.WorkbookOrigin.ONEDRIVE)
        return if (saved != null) OneDriveImportOutcome.Imported(id, entry.displayName, true)
            else OneDriveImportOutcome.Failed(RemoteFailure.NoStorage)
    }

    /** Copies the validated file into the library, records where it came from, then removes it. */
    private fun commit(item: RemoteItem, downloaded: DownloadResult.Ready, identity: com.bookcovermatcher.core.remote.SourceAccount?): OneDriveImportOutcome = try {
        val imported = downloaded.file.inputStream().use { stream ->
            // `use`, not the library's own close: the library closes the stream itself, but not on the
            // early rejections (bad name, unusable directory), and a leaked descriptor per failed
            // import is not worth arguing about.
            library.import(stream, downloaded.displayName, now = now())
        }
        when (imported) {
            is ImportResult.Added -> {
                linkRemote(imported.workbook, item, identity)
                OneDriveImportOutcome.Imported(imported.workbook.id, imported.workbook.displayName, false)
            }
            is ImportResult.AlreadySaved -> {
                // The same bytes are already saved. Record the OneDrive source so a later refresh has
                // somewhere to check — `linkRemote` decides whether that is safe.
                // Duplicate content never changes source ownership.
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
    private fun linkRemote(workbook: SavedWorkbook, item: RemoteItem, identity: com.bookcovermatcher.core.remote.SourceAccount?) {
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
                accountKey = identity?.key,
                accountLabel = identity?.label,
            ),
        )
    }
}

