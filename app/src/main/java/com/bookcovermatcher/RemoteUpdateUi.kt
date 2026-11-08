// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher

import com.bookcovermatcher.core.format.Formatting
import com.bookcovermatcher.core.remote.RemoteItem

/**
 * The question the app asks before replacing a saved workbook (v2.6.0-dev.1 Phase 5).
 *
 * [remote] is the probe that found the newer version: the download uses its id, so accepting the offer
 * needs no second lookup and no second network decision.
 */
data class RemoteUpdateOffer(
    val workbookId: String,
    val displayName: String,
    val remote: RemoteItem,
    val text: String,
)

/**
 * The explicit remote check's state (v2.6.0-dev.1 Phase 5).
 *
 * Three moments, never overlapping: [checking] (a metadata probe is in flight), [offer] (a newer version
 * was found and the user has not answered), [download] (the replacement is being fetched). Reusing
 * [OneDriveDownloadUi] keeps one progress shape in the app rather than two.
 */
data class RemoteUpdateUi(
    val checking: Boolean = false,
    val offer: RemoteUpdateOffer? = null,
    val download: OneDriveDownloadUi? = null,
) {
    /** True while a probe or a download is running: a second Rescan must not start. */
    val busy: Boolean get() = checking || download != null

    /** True while the question is on screen. */
    val asking: Boolean get() = offer != null

    /** Rescan is a no-op while something is in flight or a question is unanswered. */
    val canRescan: Boolean get() = !busy && !asking

    /**
     * Nothing is in flight any more (v2.6.0-dev.1 Phase 6).
     *
     * Applied in the `finally` of the check and of the update download, so a failure, a cancellation or a
     * connectivity loss can never leave the probe spinner or the progress bar on screen. The unanswered
     * question ([offer]) is **not** cleared here: it is the user's to answer, not the operation's to erase.
     */
    fun settled(): RemoteUpdateUi = copy(checking = false, download = null)
}

/**
 * The wording of the update question, in one place so it can be asserted verbatim.
 *
 * The size and the date are what the user needs to judge the offer. When OneDrive did not supply either,
 * the sentence says so ("size unknown", "date unknown") rather than printing a fabricated number or an
 * epoch date.
 */
object RemoteUpdatePrompt {

    const val QUESTION = "Update the saved copy? The current copy stays until the new one is verified."

    fun text(item: RemoteItem): String =
        "A newer version is on OneDrive (${sizeText(item.sizeBytes)}, ${dateText(item.lastModified)}). $QUESTION"

    fun sizeText(bytes: Long?): String = bytes?.takeIf { it >= 0L }?.let { Formatting.fileSize(it) } ?: "size unknown"

    fun dateText(modified: Long?): String = modified?.takeIf { it > 0L }?.let { Formatting.timestamp(it) } ?: "date unknown"
}
