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
    }

    @Test fun anAccessTokenNeverPrintsItself() {
        val token = AccessToken.of("super-secret-bearer-value")

        assertEquals("super-secret-bearer-value", token.reveal())
        assertFalse("toString must not leak the token", token.toString().contains("super-secret"))
        assertFalse("string interpolation must not leak the token", "$token".contains("super-secret"))
    }
}
