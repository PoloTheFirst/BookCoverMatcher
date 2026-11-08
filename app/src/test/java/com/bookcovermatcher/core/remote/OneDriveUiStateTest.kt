// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

import com.bookcovermatcher.OneDriveDownloadUi
import com.bookcovermatcher.OneDriveUi
import com.bookcovermatcher.data.onedrive.OneDriveBrowse
import com.bookcovermatcher.data.onedrive.OneDriveNavigation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the OneDrive panel's state says about itself (v2.6.0-dev.1 Phase 4).
 *
 * The Compose layout is not unit-tested (this project has no Compose UI-test dependency), so the rules
 * the layout branches on are asserted here instead: when the entry action is usable, when the sign-in
 * panel shows, when an empty folder is genuinely empty, and when a Retry is worth offering.
 */
class OneDriveUiStateTest {

    private fun file(id: String) = RemoteItem(id = id, name = "book.xlsx", isFolder = false, sizeBytes = 100L)

    private fun loaded(): OneDriveBrowse = OneDriveNavigation.withFirstPage(
        OneDriveBrowse.root(),
        folders = emptyList(),
        files = listOf(file("A1")),
        next = null,
    )

    @Test fun theEntryActionIsUsableOnlyWhenNothingElseIsRunning() {
        assertTrue(OneDriveUi().entryEnabled)

        assertFalse("a listing is in flight", OneDriveUi(busy = true).entryEnabled)
        assertFalse("a sign-in is in flight", OneDriveUi(signingIn = true).entryEnabled)
        assertFalse("a page is loading", OneDriveUi(loadingMore = true).entryEnabled)
        assertFalse(
            "a download is in flight",
            OneDriveUi(download = OneDriveDownloadUi("A1", "book.xlsx")).entryEnabled,
        )
    }

    @Test fun theSignInPanelShowsUntilThereIsAnAccount() {
        assertTrue(OneDriveUi().needsSignIn)
        assertFalse(OneDriveUi(signedIn = true, accountLabel = "someone@example.com").needsSignIn)
    }

    @Test fun anEmptyFolderIsNotTheSameAsLoadingOrFailing() {
        val signedIn = OneDriveUi(signedIn = true, open = true)

        assertTrue("loaded, signed in, nothing in it", signedIn.emptyFolder)
        assertFalse("still reading", signedIn.copy(busy = true).emptyFolder)
        assertFalse("loading another page", signedIn.copy(loadingMore = true).emptyFolder)
        assertFalse("the last attempt failed", signedIn.copy(error = "Offline").emptyFolder)
        assertFalse("not signed in yet", OneDriveUi(open = true).emptyFolder)
    }

    @Test fun aDeterminateDownloadReportsAFractionAndAnUnknownSizeDoesNot() {
        val known = OneDriveDownloadUi("A1", "book.xlsx", bytesWritten = 50, totalBytes = 200)
        assertEquals(0.25f, known.fraction!!, 0.0001f)

        assertNull(
            "an unknown total stays unknown instead of inventing a percentage",
            OneDriveDownloadUi("A1", "book.xlsx", bytesWritten = 50, totalBytes = null).fraction,
        )
        assertNull(
            "a zero total is not a division",
            OneDriveDownloadUi("A1", "book.xlsx", bytesWritten = 50, totalBytes = 0L).fraction,
        )
        assertEquals(
            "a fraction never exceeds the bar",
            1f,
            OneDriveDownloadUi("A1", "book.xlsx", bytesWritten = 500, totalBytes = 200).fraction!!,
            0.0001f,
        )
    }

    @Test fun retryAppearsOnlyForAFailureThatCouldImprove() {
        assertFalse("no error, no retry", OneDriveUi(canRetry = true).retryVisible)
        assertTrue(OneDriveUi(error = "Offline", canRetry = true).retryVisible)
        assertFalse("signing in is the action, not trying again", OneDriveUi(error = "Sign in", canRetry = false).retryVisible)
        assertFalse("a retry while busy would race the request", OneDriveUi(error = "x", canRetry = true, busy = true).retryVisible)
    }

    @Test fun theBrowserKeepsItsRowsOnceLoaded() {
        val state = OneDriveUi(open = true, signedIn = true, browse = loaded())

        assertEquals(listOf("A1"), state.browse.rows.map { it.id })
        assertFalse(state.browse.hasMorePages)
    }

    @Test fun aTerminalOutcomeClearsTheSpinnerAndKeepsWhatTheUserHas() {
        // The reset the ViewModel applies in the `finally` of every OneDrive job (Phase 6): a failure, a
        // cancellation or a lost connection must not leave a spinner or a dead button behind.
        val state = OneDriveUi(
            open = true,
            signedIn = true,
            busy = true,
            loadingMore = true,
            error = "No internet connection.",
            browse = loaded(),
            download = OneDriveDownloadUi("A1", "book.xlsx", bytesWritten = 5, totalBytes = 10),
        ).settled()

        assertFalse(state.busy)
        assertFalse(state.loadingMore)
        assertNull("no progress bar survives", state.download)
        assertTrue("the panel stays open", state.open)
        assertTrue("the account stays", state.signedIn)
        assertEquals("the listing stays", listOf("A1"), state.browse.rows.map { it.id })
        assertEquals("the error stays on screen", "No internet connection.", state.error)
        assertTrue("and the entry point works again", state.entryEnabled)
    }
}
