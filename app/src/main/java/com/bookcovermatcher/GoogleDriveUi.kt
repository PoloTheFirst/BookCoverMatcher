// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher

import com.bookcovermatcher.data.googledrive.GoogleDriveBrowse

/**
 * A download in progress (v2.6.0-dev.1 Phase 4).
 *
 * [totalBytes] is the size GoogleDrive reported for the item, or `null` when it reported none — the bar is
 * then indeterminate rather than a percentage invented from nothing.
 */
data class GoogleDriveDownloadUi(
    val itemId: String,
    val name: String,
    val bytesWritten: Long = 0L,
    val totalBytes: Long? = null,
    val cancelling: Boolean = false,
) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0L }
            ?.let { (bytesWritten.toDouble() / it).toFloat().coerceIn(0f, 1f) }
}

/** The result of the last import, so the dialog can offer `Use it now`. */
data class GoogleDriveImportedUi(
    val workbookId: String,
    val displayName: String,
    val alreadySaved: Boolean = false,
)

/**
 * The GoogleDrive import surface's state (v2.6.0-dev.1 Phase 4).
 *
 * Navigation lives in [GoogleDriveBrowse] (pure and unit-tested); this block adds only what a screen draws:
 * whether the browser is open, whether there is an account, what is in flight, and the last error.
 *
 * No token, no account identifier beyond the user-facing name, and nothing derived from a `.env` value
 * ever enters this state.
 */
data class GoogleDriveUi(
    val open: Boolean = false,
    val browse: GoogleDriveBrowse = GoogleDriveBrowse.root(),
    val signedIn: Boolean = false,
    /** The account name Google authorization reports for the signed-in user. User-facing only; never logged. */
    val accountLabel: String? = null,
    /** A folder listing is in flight (the first page). */
    val busy: Boolean = false,
    /** An interactive sign-in is in flight. */
    val signingIn: Boolean = false,
    /** A further page is in flight. */
    val loadingMore: Boolean = false,
    val error: String? = null,
    /** Whether the current error is worth a Retry button (a failure, not an empty folder). */
    val canRetry: Boolean = false,
    val download: GoogleDriveDownloadUi? = null,
    val imported: GoogleDriveImportedUi? = null,
) {

    val downloading: Boolean get() = download != null

    /** The `From GoogleDrive` action: always present, but one remote operation at a time. */
    val entryEnabled: Boolean get() = !busy && !signingIn && !downloading && !loadingMore

    /**
     * Nothing is in flight any more (v2.6.0-dev.1 Phase 6).
     *
     * Applied in the `finally` of every GoogleDrive job, so a terminal outcome — success, failure,
     * cancellation or a connectivity loss — can never leave a spinner, a disabled button or a progress
     * bar behind. It deliberately keeps everything that describes *what the user has*: the open panel, the
     * account, the folder listing, the last error, the imported workbook.
     */
    fun settled(): GoogleDriveUi = copy(busy = false, loadingMore = false, signingIn = false, download = null)

    /** Show the sign-in panel rather than the folder list. */
    val needsSignIn: Boolean get() = !signedIn

    /** True when the folder really is empty (as opposed to loading, failed, or not loaded yet). */
    val emptyFolder: Boolean
        get() = signedIn && error == null && !busy && !loadingMore && browse.isEmpty

    /** Retry is offered only for an error that a second attempt could plausibly fix. */
    val retryVisible: Boolean get() = canRetry && error != null && !busy && !downloading
}
