// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

/**
 * A file or folder in the user's OneDrive, reduced to what this app needs.
 *
 * [path] is for display only: the app never resolves it, and the identity of the remote file is
 * [id] (a Graph driveItem id). Metadata is nullable rather than defaulted, so "OneDrive did not say"
 * stays distinguishable from "OneDrive said zero".
 */
data class RemoteItem(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val sizeBytes: Long? = null,
    val eTag: String? = null,
    val cTag: String? = null,
    val lastModified: Long? = null,
    val path: String = "",
)

/** One page of a folder listing. [nextPageToken] is an opaque `@odata.nextLink` or null at the end. */
data class RemoteListing(
    val folderId: String?,
    val folders: List<RemoteItem>,
    val files: List<RemoteItem>,
    val nextPageToken: String? = null,
)

/** What a metadata probe found. */
sealed interface RemoteState {
    data class Present(val item: RemoteItem) : RemoteState
    data object Missing : RemoteState
}

/** Result of one remote call: a value, or the reason it is not there. */
sealed interface RemoteResult<out T> {
    data class Ok<T>(val value: T) : RemoteResult<T>
    data class Err(val failure: RemoteFailure) : RemoteResult<Nothing>
}

/** Progress of a streaming download. [totalBytes] is null when the server sent no `Content-Length`. */
data class DownloadProgress(val bytesWritten: Long, val totalBytes: Long?) {
    /** 0..1, or null when the size is unknown — never a fabricated percentage. */
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0L }?.let { (bytesWritten.toDouble() / it).toFloat().coerceIn(0f, 1f) }
}

/** How a download ended. */
sealed interface DownloadOutcome {
    /** Every byte arrived. The caller still has to validate the file before using it. */
    data class Completed(val bytesWritten: Long) : DownloadOutcome

    /** The user cancelled. Whatever had been written is the caller's to discard. */
    data object Cancelled : DownloadOutcome

    data class Failed(val failure: RemoteFailure) : DownloadOutcome
}
