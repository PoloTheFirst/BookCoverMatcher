// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.data.onedrive

import com.bookcovermatcher.core.net.ConnectivityMonitor
import com.bookcovermatcher.core.remote.DownloadProgress
import com.bookcovermatcher.core.remote.RemoteChange
import com.bookcovermatcher.core.remote.RemoteChangeDetector
import com.bookcovermatcher.core.remote.RemoteFailure
import com.bookcovermatcher.core.remote.RemoteItem
import com.bookcovermatcher.core.remote.RemoteResult
import com.bookcovermatcher.core.remote.RemoteState
import com.bookcovermatcher.core.remote.RemoteWorkbookSource
import com.bookcovermatcher.data.RemoteOrigin
import com.bookcovermatcher.data.ReplaceResult
import com.bookcovermatcher.data.SavedWorkbook
import com.bookcovermatcher.data.WorkbookLibrary

/** What one explicit remote check found (v2.6.0-dev.1 Phase 5). */
sealed interface RemoteCheck {
    /** Confirmed the same version. [remote] is the probe that proved it, for the metadata refresh. */
    data class Unchanged(val remote: RemoteItem) : RemoteCheck

    /** A different version exists; nothing has been downloaded. */
    data class Changed(val remote: RemoteItem) : RemoteCheck

    /** The metadata could not be compared. Not "unchanged" — the honest answer is "cannot tell". */
    data object Unknown : RemoteCheck

    /** OneDrive no longer has this driveItem. The local copy is untouched. */
    data object Missing : RemoteCheck

    /** The check itself failed: offline, expired sign-in, throttled, timed out, unstable. */
    data class Failed(val failure: RemoteFailure) : RemoteCheck
}

/** What replacing the local copy with the newer remote bytes produced. */
sealed interface UpdateOutcome {
    /** The new bytes are installed and recorded; [workbook] carries the **new** `sha256` cache key. */
    data class Applied(val workbook: SavedWorkbook) : UpdateOutcome

    /** OneDrive's bytes turned out to be identical to the saved ones: only the metadata moved. */
    data class SameContent(val workbook: SavedWorkbook) : UpdateOutcome

    /** The library refused the file; [message] is its own sentence and the old copy is intact. */
    data class Rejected(val message: String) : UpdateOutcome

    /** The download failed. Nothing was installed and the old copy is intact. */
    data class Failed(val failure: RemoteFailure) : UpdateOutcome

    data object Cancelled : UpdateOutcome
}

/**
 * The explicit remote check and the verified replacement behind it (v2.6.0-dev.1 Phase 5).
 *
 * This is the only place that decides *whether* the remote copy differs and *how* a newer one is
 * installed. The ViewModel owns the state and the coroutine; the UI owns the question and the bar.
 *
 * Guarantees:
 *
 *  * **Nothing happens without an explicit check.** No method here is called from opening, listing or
 *    importing; the app never refreshes a workbook on its own and never syncs in the background.
 *  * **The local copy is the source of truth until a replacement is verified.** [check] reads metadata
 *    only; [apply] downloads to a `.part`, validates it, and only then asks the library to install it.
 *  * **A failed or cancelled update changes nothing.** The `.part` is deleted on every path, the library
 *    is never told about a version that was not installed, and the old entry keeps working.
 *  * **No remote write.** The only remote calls reachable here are a metadata probe and a download.
 */
class OneDriveUpdateSession(
    private val source: RemoteWorkbookSource,
    private val connectivity: ConnectivityMonitor,
    private val library: WorkbookLibrary,
    private val downloader: SafeWorkbookDownload,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Probes OneDrive for [entry]'s remote file and compares it with the recorded version.
     *
     * Order matters: connectivity first (an offline device must not make a request), then the probe,
     * then the pure comparison. `Missing` is not an error — the file may have been deleted, or moved out
     * of reach — and it is reported as itself so the UI can say so.
     */
    suspend fun check(entry: SavedWorkbook): RemoteCheck {
        val remote = entry.remote ?: return RemoteCheck.Unknown
        if (!connectivity.isUsableNow()) return RemoteCheck.Failed(RemoteFailure.Offline)

        return when (val probed = source.probe(remote.itemId)) {
            is RemoteResult.Err -> RemoteCheck.Failed(probed.failure)
            is RemoteResult.Ok -> when (val state = probed.value) {
                RemoteState.Missing -> RemoteCheck.Missing
                is RemoteState.Present -> when (
                    RemoteChangeDetector.compare(
                        savedETag = remote.eTag,
                        savedCTag = remote.cTag,
                        savedLastModified = remote.lastModified,
                        savedSizeBytes = remote.sizeBytes,
                        item = state.item,
                    )
                ) {
                    RemoteChange.UNCHANGED -> RemoteCheck.Unchanged(state.item)
                    RemoteChange.CHANGED -> RemoteCheck.Changed(state.item)
                    RemoteChange.UNKNOWN -> RemoteCheck.Unknown
                }
            }
        }
    }

    /**
     * Downloads [item] and installs it over [entry]'s local copy, once the bytes are validated.
     *
     * The downloaded `.part` is deleted whatever happens. A library rejection (a full disk, an index this
     * build may not write, a file that is not a workbook) leaves the previous copy byte-for-byte intact,
     * and the remote metadata is only ever written *with* the bytes it describes.
     */
    suspend fun apply(
        entry: SavedWorkbook,
        item: RemoteItem,
        onProgress: (DownloadProgress) -> Unit = {},
    ): UpdateOutcome {
        if (item.isFolder) return UpdateOutcome.Failed(RemoteFailure.BadRequest)
        val remote = entry.remote ?: return UpdateOutcome.Failed(RemoteFailure.BadRequest)
        if (!connectivity.isUsableNow()) return UpdateOutcome.Failed(RemoteFailure.Offline)

        return when (val downloaded = downloader.download(item, source, onProgress)) {
            DownloadResult.Cancelled -> UpdateOutcome.Cancelled
            is DownloadResult.Failed -> UpdateOutcome.Failed(downloaded.failure)
            is DownloadResult.Ready -> try {
                val stamp = now()
                val origin = RemoteOrigin(
                    itemId = item.id,
                    // A probe without a display path must not erase the one already recorded.
                    path = item.path.ifBlank { remote.path },
                    eTag = item.eTag,
                    cTag = item.cTag,
                    lastModified = item.lastModified,
                    sizeBytes = item.sizeBytes ?: downloaded.sizeBytes,
                    syncedAt = stamp,
                    // The bytes were just fetched and validated, so this is also a successful check.
                    checkedAt = stamp,
                )
                when (val replaced = library.replaceFrom(entry.id, downloaded.file, origin)) {
                    is ReplaceResult.Replaced -> UpdateOutcome.Applied(replaced.workbook)
                    is ReplaceResult.SameContent -> UpdateOutcome.SameContent(replaced.workbook)
                    is ReplaceResult.Rejected -> UpdateOutcome.Rejected(replaced.message)
                    ReplaceResult.NotFound -> UpdateOutcome.Failed(RemoteFailure.NotFound)
                }
            } finally {
                // The library has its own copy (or refused it); the temporary file is never kept.
                runCatching { downloaded.file.delete() }
            }
        }
    }

    /**
     * Records a check that proved the local copy is current: the version metadata keeps describing the
     * same content and only `checkedAt` moves, plus any tag OneDrive had not given us before — which makes
     * the *next* comparison stronger.
     */
    fun recordUnchanged(entry: SavedWorkbook, item: RemoteItem): SavedWorkbook? {
        val remote = entry.remote ?: return null
        val stamp = now()
        return library.setRemote(
            entry.id,
            remote.copy(
                path = item.path.ifBlank { remote.path },
                eTag = item.eTag ?: remote.eTag,
                cTag = item.cTag ?: remote.cTag,
                lastModified = item.lastModified ?: remote.lastModified,
                sizeBytes = item.sizeBytes ?: remote.sizeBytes,
                checkedAt = stamp,
            ),
        )
    }

    /**
     * Records a check that found a newer version the user chose **not** to take.
     *
     * Only `checkedAt` moves. `syncedAt`/`eTag`/`cTag`/`lastModified`/`sizeBytes` keep describing the
     * bytes that are actually on disk, so the next check reports "newer version available" again instead
     * of pretending the saved copy matches OneDrive.
     */
    fun recordCheckedOnly(entry: SavedWorkbook, item: RemoteItem): SavedWorkbook? {
        val remote = entry.remote ?: return null
        return library.setRemote(
            entry.id,
            remote.copy(
                path = item.path.ifBlank { remote.path },
                checkedAt = now(),
            ),
        )
    }
}

