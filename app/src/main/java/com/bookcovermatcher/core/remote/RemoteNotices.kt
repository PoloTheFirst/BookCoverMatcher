// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

/**
 * The sentences the OneDrive screens use when the *same* failure means something different depending on
 * what the user was doing (v2.6.0-dev.1 Phase 6).
 *
 * [RemoteFailure.message] answers "what went wrong". This object answers "and what happened to my
 * workbook", which is the part the user actually needs:
 *
 *  * an **import** that cannot reach OneDrive says the saved workbooks still open offline;
 *  * a **check** that cannot reach OneDrive says the copy on the device was shown instead;
 *  * a **download** that dies mid-transfer names the file and says nothing was saved.
 *
 * Keeping them here — rather than as literals in the ViewModel — is what makes the wording assertable:
 * every string in this file is compared character for character in `RemoteFailureNoticesTest`. No value
 * here contains a token, an account or a remote URL.
 */
object RemoteNotices {

    /**
     * A remote check could not run because the device is offline. Deliberately different from the import
     * sentence: the user asked to *rescan*, and a message about importing online would be about the wrong
     * action.
     */
    const val OFFLINE_CHECK =
        "Offline: showing the copy saved on this device. " +
            "The workbook was not checked for changes on OneDrive."

    /** The agreed wording for a check while the saved copy is still perfectly usable. */
    const val SLOW_CHECK = "OneDrive took too long to answer. The saved copy was rescanned instead."

    /** A cancelled update. Never phrased as a success, and never phrased as a failure either. */
    const val CANCELLED_UPDATE = "Update cancelled. The saved copy was not changed."

    /**
     * The metadata could not be compared, so the app refuses to claim the workbook is up to date. This is
     * the sentence that keeps "unknown" from being reported as "unchanged".
     */
    const val UNKNOWN_CHECK =
        "Could not tell whether the OneDrive copy changed. The saved copy is unchanged."

    /** The remote file is gone. Only ever used for a genuine `NOT_FOUND` / `Missing` answer. */
    fun missing(): String = RemoteFailure.NotFound.message()

    /**
     * A check (`Rescan` on a saved OneDrive workbook) failed.
     *
     * The two cases that need their own words are the ones where the *local copy still worked*: offline
     * and slow/unstable. Every other failure keeps its taxonomy sentence, which already says whether the
     * user must sign in, wait, free space or re-import.
     */
    fun forCheck(failure: RemoteFailure): String = when (failure) {
        RemoteFailure.Offline -> OFFLINE_CHECK
        // "Slow or unstable" is one situation for the user: OneDrive did not answer in time. Both report
        // the same thing, because in both cases the saved copy was rescanned and nothing was lost.
        RemoteFailure.Timeout, RemoteFailure.Unstable -> SLOW_CHECK
        else -> failure.message()
    }

    /**
     * A download failed, in either flow (a new import or a replacement).
     *
     * [name] is the workbook's display name, because "the connection was lost" without saying *what* was
     * being downloaded is not something a user can act on. An `Offline` answer here can only come from the
     * preflight, before a byte was requested, so it keeps the import sentence instead.
     */
    fun forDownload(failure: RemoteFailure, name: String): String {
        val file = name.ifBlank { "the workbook" }
        return when (failure) {
            RemoteFailure.Unstable ->
                "The connection was lost while downloading $file. Nothing was saved. " +
                    "Retry when you are back online."
            RemoteFailure.Timeout ->
                "OneDrive took too long to answer while downloading $file. Nothing was saved. " +
                    "Retry when you are back online."
            else -> failure.message()
        }
    }
}
