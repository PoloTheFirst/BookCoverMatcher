// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

import com.bookcovermatcher.OneDriveDownloadUi
import com.bookcovermatcher.RemoteUpdatePrompt
import com.bookcovermatcher.RemoteUpdateUi
import com.bookcovermatcher.core.format.Formatting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update question's wording and the guards around it (v2.6.0-dev.1 Phase 5).
 *
 * The sentence is asserted **verbatim** because it is the promise the user is agreeing to; the flags are
 * asserted because they are what stops a second Rescan (and a second download) from starting while one is
 * already in flight.
 */
class RemoteUpdateUiTest {

    private val modified = 1_794_141_296_000L

    private fun item(size: Long? = 4_096L, modified: Long? = this.modified) =
        RemoteItem(id = "A1", name = "covers.xlsx", isFolder = false, sizeBytes = size, lastModified = modified)

    @Test fun theQuestionIsTheAgreedWordingWithTheRemoteSizeAndDate() {
        val text = RemoteUpdatePrompt.text(item())

        assertEquals(
            "A newer version is on OneDrive (${Formatting.fileSize(4_096L)}, " +
                "${Formatting.timestamp(modified)}). " +
                "Update the saved copy? The current copy stays until the new one is verified.",
            text,
        )
        assertTrue("the promise about the current copy is part of it", text.contains(RemoteUpdatePrompt.QUESTION))
    }

    @Test fun anUnknownSizeOrDateIsSaidOutLoudRatherThanInvented() {
        assertEquals("size unknown", RemoteUpdatePrompt.sizeText(null))
        assertEquals("date unknown", RemoteUpdatePrompt.dateText(null))
        assertEquals("date unknown", RemoteUpdatePrompt.dateText(0L))
        assertEquals(
            "A newer version is on OneDrive (size unknown, date unknown). " + RemoteUpdatePrompt.QUESTION,
            RemoteUpdatePrompt.text(item(size = null, modified = null)),
        )
    }

    @Test fun aSecondRescanCannotStartWhileOneIsRunning() {
        assertTrue("idle", RemoteUpdateUi().canRescan)
        assertFalse("a probe is in flight", RemoteUpdateUi(checking = true).canRescan)
        assertFalse(
            "a download is in flight",
            RemoteUpdateUi(download = OneDriveDownloadUi("A1", "covers.xlsx")).canRescan,
        )
        assertFalse(
            "the question is unanswered",
            RemoteUpdateUi(
                offer = com.bookcovermatcher.RemoteUpdateOffer(
                    workbookId = "id",
                    displayName = "covers.xlsx",
                    remote = item(),
                    text = RemoteUpdatePrompt.text(item()),
                ),
            ).canRescan,
        )
    }

    @Test fun busyCoversBothInFlightStatesButNotTheQuestion() {
        assertFalse(RemoteUpdateUi().busy)
        assertTrue(RemoteUpdateUi(checking = true).busy)
        assertTrue(RemoteUpdateUi(download = OneDriveDownloadUi("A1", "x.xlsx")).busy)
        assertFalse("waiting for an answer is not work in progress", RemoteUpdateUi(offer = null).busy)
    }

    @Test fun theOfferIsOnlyAskingWhenItExists() {
        assertFalse(RemoteUpdateUi().asking)
    }

    @Test fun aTerminalOutcomeClearsEveryInFlightFlag() {
        // What the ViewModel applies in the `finally` of every check and update: a failure, a cancellation
        // or a lost connection must never leave the spinner or the progress bar on screen.
        val settled = RemoteUpdateUi(
            checking = true,
            download = OneDriveDownloadUi("A1", "covers.xlsx", bytesWritten = 10, totalBytes = 100),
        ).settled()

        assertFalse(settled.checking)
        assertNull(settled.download)
        assertFalse("nothing is in flight any more", settled.busy)
        assertTrue("so Rescan works again", settled.canRescan)
    }

    @Test fun settlingLeavesTheQuestionToTheUser() {
        val offered = RemoteUpdateUi(
            offer = com.bookcovermatcher.RemoteUpdateOffer(
                workbookId = "id",
                displayName = "covers.xlsx",
                remote = item(),
                text = RemoteUpdatePrompt.text(item()),
            ),
        )

        assertTrue("an unanswered question is not an operation to cancel", offered.settled().asking)
        assertFalse(offered.settled().canRescan)
    }
}
