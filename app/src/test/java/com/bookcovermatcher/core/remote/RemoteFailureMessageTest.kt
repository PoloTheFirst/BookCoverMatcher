// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every remote failure maps to exactly one sentence (Phase 3), and no two failures collapse into the
 * same generic message — that is the whole point of having a taxonomy instead of an exception string.
 */
class RemoteFailureMessageTest {

    private val all: List<RemoteFailure> = listOf(
        RemoteFailure.Offline,
        RemoteFailure.AuthRequired,
        RemoteFailure.AuthExpired,
        RemoteFailure.Throttled(null),
        RemoteFailure.Throttled(30),
        RemoteFailure.NotFound,
        RemoteFailure.Timeout,
        RemoteFailure.Unstable,
        RemoteFailure.NoStorage,
        RemoteFailure.InvalidWorkbook,
        RemoteFailure.ServerError(500),
        RemoteFailure.BadRequest,
        RemoteFailure.NotConfigured,
        // v2.6.0-dev.1 crash-fix-4: the three failures that used to be reported as connectivity.
        RemoteFailure.PermissionDenied,
        RemoteFailure.SignInRejected,
        RemoteFailure.UnexpectedResponse,
        RemoteFailure.SignInNotCompleted,
        RemoteFailure.AuthUnexpected,
    )

    @Test fun everyFailureHasANonEmptySentence() {
        for (failure in all) {
            val message = failure.message()
            assertTrue("$failure has no message", message.isNotBlank())
            assertTrue("$failure is not a sentence", message.first().isUpperCase())
        }
    }

    @Test fun theSentencesAreDistinct() {
        val messages = all.map { it.message() }
        assertEquals("two failures share a message: $messages", messages.size, messages.toSet().size)
    }

    @Test fun aThrottledFailureReportsTheRetryAfterItWasGiven() {
        assertTrue(RemoteFailure.Throttled(30).message().contains("30"))
        assertFalse(RemoteFailure.Throttled(null).message().contains("second"))
        assertTrue(RemoteFailure.Throttled(null).message().contains("busy"))
    }

    @Test fun aServerErrorReportsItsStatus() {
        assertTrue(RemoteFailure.ServerError(503).message().contains("503"))
        assertNotEquals(RemoteFailure.ServerError(500).message(), RemoteFailure.ServerError(502).message())
    }

    @Test fun theOfflineSentencePromisesWhatStillWorks() {
        // The prompt's baseline message: it must say that saved workbooks keep working.
        val message = RemoteFailure.Offline.message()
        assertTrue(message.contains("offline", ignoreCase = true))
        assertTrue(message.contains("open", ignoreCase = true))
    }

    @Test fun theExpiredSentenceAsksForASignInAgain() {
        assertTrue(RemoteFailure.AuthExpired.message().contains("expired", ignoreCase = true))
        assertTrue(RemoteFailure.AuthExpired.message().contains("again", ignoreCase = true))
    }

    @Test fun everyAuthFailureMapsToARemoteFailure() {
        for (authFailure in AuthFailure.entries) {
            assertTrue("$authFailure maps to a failure with no sentence", authFailure.toRemoteFailure().message().isNotBlank())
        }

        assertEquals(RemoteFailure.AuthRequired, AuthFailure.CANCELLED.toRemoteFailure())
        assertEquals(RemoteFailure.AuthExpired, AuthFailure.INTERACTION_REQUIRED.toRemoteFailure())
        assertEquals(RemoteFailure.Offline, AuthFailure.NETWORK.toRemoteFailure())
        assertEquals(RemoteFailure.NotConfigured, AuthFailure.CONFIGURATION.toRemoteFailure())
        assertEquals(RemoteFailure.PermissionDenied, AuthFailure.CONSENT_DECLINED.toRemoteFailure())
        assertEquals(RemoteFailure.SignInRejected, AuthFailure.CLIENT_ERROR.toRemoteFailure())
        assertEquals(RemoteFailure.SignInRejected, AuthFailure.SERVICE_ERROR.toRemoteFailure())
        // crash-fix-6: an unattributed sign-in failure must never claim the network is down. MSAL's own
        // "Do not provide callback for synchronous methods" reached the user as "try again when you are
        // back online" because this mapped to `Unstable`.
        assertEquals(RemoteFailure.AuthUnexpected, AuthFailure.UNKNOWN.toRemoteFailure())
        val unexpected = AuthFailure.UNKNOWN.toRemoteFailure().message()
        assertFalse(
            "an unattributed auth failure must not read as connectivity: $unexpected",
            unexpected.contains("back online") || unexpected.contains("connection to OneDrive"),
        )
    }

    @Test fun onlyConnectivityFailuresBlameTheConnection() {
        // crash-fix-4: the user met "the connection to OneDrive failed … try again when you are back
        // online" while online. A failure that is not transport must not name the network at all.
        val notConnectivity = listOf(
            RemoteFailure.AuthRequired,
            RemoteFailure.AuthExpired,
            RemoteFailure.NotFound,
            RemoteFailure.NoStorage,
            RemoteFailure.InvalidWorkbook,
            RemoteFailure.BadRequest,
            RemoteFailure.NotConfigured,
            RemoteFailure.PermissionDenied,
            RemoteFailure.SignInRejected,
            RemoteFailure.UnexpectedResponse,
            RemoteFailure.SignInNotCompleted,
            RemoteFailure.AuthUnexpected,
            RemoteFailure.ServerError(500),
            RemoteFailure.Throttled(30),
        )

        for (failure in notConnectivity) {
            val message = failure.message()
            assertFalse("$failure blames the connection: $message", message.contains("back online"))
            assertFalse("$failure blames the connection: $message", message.contains("connection to OneDrive"))
        }

        // The control: the two failures that really are about the network still say so.
        assertTrue(RemoteFailure.Offline.message().contains("internet", ignoreCase = true))
        assertTrue(RemoteFailure.Unstable.message().contains("connection", ignoreCase = true))
    }

    @Test fun anAccessTokenNeverPrintsItself() {
        val token = AccessToken.of("super-secret-bearer-value")

        assertEquals("super-secret-bearer-value", token.reveal())
        assertFalse("toString must not leak the token", token.toString().contains("super-secret"))
        assertFalse("string interpolation must not leak the token", "$token".contains("super-secret"))
    }
}
