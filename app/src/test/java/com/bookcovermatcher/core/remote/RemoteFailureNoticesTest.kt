// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user-facing sentences and the Retry rule (v2.6.0-dev.1 Phase 6).
 *
 * Every string here is compared **verbatim**, because the wording is the deliverable: a user who is
 * offline during a rescan must read that the copy on the device was shown, not that importing needs a
 * connection. The tests also pin the two judgement calls: `UNKNOWN` never reads as "up to date", and the
 * deletion sentence appears only for a real `NOT_FOUND`.
 */
class RemoteFailureNoticesTest {

    private val everyFailure = listOf(
        RemoteFailure.Offline,
        RemoteFailure.AuthRequired,
        RemoteFailure.AuthExpired,
        RemoteFailure.Throttled(12),
        RemoteFailure.NotFound,
        RemoteFailure.Timeout,
        RemoteFailure.Unstable,
        RemoteFailure.NoStorage,
        RemoteFailure.InvalidWorkbook,
        RemoteFailure.ServerError(503),
        RemoteFailure.BadRequest,
        RemoteFailure.NotConfigured,
        // v2.6.0-dev.1 crash-fix-4: distinct from connectivity, and distinct from each other.
        RemoteFailure.PermissionDenied,
        RemoteFailure.SignInRejected,
        RemoteFailure.UnexpectedResponse,
        // crash-fix-5: a sign-in whose browser never came back.
        RemoteFailure.SignInNotCompleted,
        // crash-fix-6: an unattributed sign-in failure has its own sentence.
        RemoteFailure.AuthUnexpected,
    )

    // ---- the agreed wording, verbatim --------------------------------------------------------

    @Test fun anOfflineImportSaysSavedWorkbooksStillWork() {
        assertEquals(
            "No internet connection. OneDrive workbooks can only be imported online. " +
                "Workbooks you already saved still open and rescan offline.",
            RemoteFailure.Offline.message(),
        )
    }

    @Test fun aLostConnectionDuringADownloadNamesTheFile() {
        assertEquals(
            "The connection was lost while downloading covers.xlsx. Nothing was saved. " +
                "Retry when you are back online.",
            RemoteNotices.forDownload(RemoteFailure.Unstable, "covers.xlsx"),
        )
    }

    @Test fun anOfflineCheckSaysTheCopyOnTheDeviceWasShown() {
        assertEquals(
            "Offline: showing the copy saved on this device. " +
                "The workbook was not checked for changes on OneDrive.",
            RemoteNotices.forCheck(RemoteFailure.Offline),
        )
    }

    @Test fun aSlowOrUnstableCheckSaysTheCopyWasRescannedInstead() {
        assertEquals(
            "OneDrive took too long to answer. The saved copy was rescanned instead.",
            RemoteNotices.forCheck(RemoteFailure.Timeout),
        )
        assertEquals(
            "a dropped connection is the same situation for the user, so it reads the same",
            RemoteNotices.SLOW_CHECK,
            RemoteNotices.forCheck(RemoteFailure.Unstable),
        )
    }

    @Test fun aDeletedRemoteFileKeepsItsAgreedWording() {
        assertEquals(
            "This workbook is no longer in OneDrive. The saved copy still opens; rescan it locally, " +
                "or import the file again.",
            RemoteNotices.missing(),
        )
    }

    @Test fun anExpiredSignInPointsAtSigningInAgain() {
        assertEquals(
            "Your OneDrive sign-in expired. Sign in again to check for changes.",
            RemoteFailure.AuthExpired.message(),
        )
        assertEquals(RemoteFailure.AuthExpired.message(), RemoteNotices.forCheck(RemoteFailure.AuthExpired))
    }

    @Test fun aCancelledUpdateIsNeitherASuccessNorAFailure() {
        assertEquals("Update cancelled. The saved copy was not changed.", RemoteNotices.CANCELLED_UPDATE)
    }

    @Test fun anUnknownRemoteStateIsNeverReportedAsUpToDate() {
        val text = RemoteNotices.UNKNOWN_CHECK

        assertEquals("Could not tell whether the OneDrive copy changed. The saved copy is unchanged.", text)
        assertFalse("must not claim the workbook is current", text.contains("up to date", ignoreCase = true))
        assertTrue("must say the check did not conclude", text.contains("Could not tell"))
    }

    // ---- what each category says, and whether Retry is offered -------------------------------

    @Test fun everyCategoryHasItsOwnSentence() {
        everyFailure.forEach { failure ->
            assertTrue("$failure has a message", failure.message().isNotBlank())
        }
        // The categories the brief lists as distinct must not collapse into one another.
        val distinct = everyFailure.map { it.message() }.toSet()
        assertEquals("each category reads differently", everyFailure.size, distinct.size)
    }

    @Test fun retryIsOfferedOnlyForABadMoment() {
        listOf(
            RemoteFailure.Offline,
            RemoteFailure.Timeout,
            RemoteFailure.Unstable,
            RemoteFailure.Throttled(5),
            RemoteFailure.ServerError(500),
            // A response we could not use may well come through correctly next time.
            RemoteFailure.UnexpectedResponse,
        ).forEach { assertTrue("$it is worth retrying", it.isRetryable()) }

        listOf(
            RemoteFailure.AuthRequired,
            RemoteFailure.AuthExpired,
            RemoteFailure.NotFound,
            RemoteFailure.NoStorage,
            RemoteFailure.InvalidWorkbook,
            RemoteFailure.BadRequest,
            RemoteFailure.NotConfigured,
            // A decision (allow access) and a configuration have to change before a retry can work.
            RemoteFailure.PermissionDenied,
            RemoteFailure.SignInRejected,
            // Retrying a listing cannot finish a sign-in.
            RemoteFailure.SignInNotCompleted,
            // An unidentified cause would fail a second attempt the same way.
            RemoteFailure.AuthUnexpected,
        ).forEach { assertFalse("$it needs the user to change something first", it.isRetryable()) }
    }

    @Test fun throttlingRespectsRetryAfter() {
        assertEquals("OneDrive is busy. Try again in about 30 second(s).", RemoteFailure.Throttled(30).message())
        assertEquals("OneDrive is busy. Try again shortly.", RemoteFailure.Throttled(null).message())
        assertEquals(
            "the wait survives into the check context",
            "OneDrive is busy. Try again in about 30 second(s).",
            RemoteNotices.forCheck(RemoteFailure.Throttled(30)),
        )
    }

    @Test fun aServerErrorCarriesItsStatus() {
        assertEquals("OneDrive failed (HTTP 503). Try again later.", RemoteFailure.ServerError(503).message())
    }

    @Test fun onlyANotFoundReadsAsDeleted() {
        val deletion = RemoteNotices.missing()
        everyFailure.filterNot { it == RemoteFailure.NotFound }.forEach { failure ->
            assertNotEquals("$failure must not be described as a deletion", deletion, failure.message())
            assertFalse(
                "$failure must not mention the file being gone",
                failure.message().contains("no longer in OneDrive"),
            )
        }
        assertEquals(deletion, RemoteNotices.forCheck(RemoteFailure.NotFound))
    }

    @Test fun aDownloadFailureThatIsNotAboutTheConnectionKeepsItsOwnSentence() {
        // A full disk is not "the connection was lost", and saying so would send the user to fix the wrong
        // thing.
        assertEquals(
            RemoteFailure.NoStorage.message(),
            RemoteNotices.forDownload(RemoteFailure.NoStorage, "covers.xlsx"),
        )
        assertEquals(
            RemoteFailure.InvalidWorkbook.message(),
            RemoteNotices.forDownload(RemoteFailure.InvalidWorkbook, "covers.xlsx"),
        )
        assertEquals(
            "the preflight sentence, since no byte was ever requested",
            RemoteFailure.Offline.message(),
            RemoteNotices.forDownload(RemoteFailure.Offline, "covers.xlsx"),
        )
    }

    @Test fun aDownloadTimeoutAlsoSaysNothingWasSaved() {
        assertEquals(
            "OneDrive took too long to answer while downloading covers.xlsx. Nothing was saved. " +
                "Retry when you are back online.",
            RemoteNotices.forDownload(RemoteFailure.Timeout, "covers.xlsx"),
        )
    }

    @Test fun aMissingFileNameStillReadsLikeASentence() {
        assertEquals(
            "The connection was lost while downloading the workbook. Nothing was saved. " +
                "Retry when you are back online.",
            RemoteNotices.forDownload(RemoteFailure.Unstable, ""),
        )
    }
}

