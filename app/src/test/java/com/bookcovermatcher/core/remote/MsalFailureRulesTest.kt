// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How MSAL's failures become this app's vocabulary (v2.6.0-dev.1 crash-fix-4).
 *
 * The defect this pins: everything that was not `MsalUiRequiredException` or `IOException` became
 * `UNKNOWN`, and `UNKNOWN` is shown to the user as *"the connection to OneDrive failed … try again when
 * you are back online"*. A cancelled page, a declined consent and an Azure registration mismatch all
 * looked exactly like being offline.
 */
class MsalFailureRulesTest {

    @Test fun aCancelledSignInIsNotAFailureToShoutAbout() {
        assertEquals(AuthFailure.CANCELLED, MsalFailureRules.classify(MsalFailureKind.CANCELLED))
    }

    @Test fun aDeclinedConsentIsAPermissionDecision() {
        assertEquals(AuthFailure.CONSENT_DECLINED, MsalFailureRules.classify(MsalFailureKind.CONSENT_DECLINED))
        assertEquals(RemoteFailure.PermissionDenied, AuthFailure.CONSENT_DECLINED.toRemoteFailure())
    }

    @Test fun aServiceRefusalIsASignInRejectionNotAConnectionProblem() {
        val authFailure = MsalFailureRules.classify(MsalFailureKind.SERVICE)

        assertEquals(AuthFailure.SERVICE_ERROR, authFailure)
        val remote = authFailure.toRemoteFailure()
        assertEquals(RemoteFailure.SignInRejected, remote)
        val message = remote.message()
        assertEquals(
            "the user must be told to sign in again, not to wait for a network",
            false,
            message.contains("back online"),
        )
        assertEquals(false, message.contains("connection", ignoreCase = true))
    }

    @Test fun aClientRefusalIsASignInRejectionToo() {
        assertEquals(AuthFailure.CLIENT_ERROR, MsalFailureRules.classify(MsalFailureKind.CLIENT))
        assertEquals(
            RemoteFailure.SignInRejected,
            MsalFailureRules.classify(MsalFailureKind.CLIENT, "invalid_redirect_uri").toRemoteFailure(),
        )
    }

    @Test fun theOneClientCodeThatMeansNoNetworkStaysANetworkFailure() {
        assertEquals(
            AuthFailure.NETWORK,
            MsalFailureRules.classify(MsalFailureKind.CLIENT, MsalFailureRules.DEVICE_NETWORK_NOT_AVAILABLE),
        )
        // Case-insensitive: MSAL's own constant is lower case, but nothing guarantees the casing of a code.
        assertEquals(
            AuthFailure.NETWORK,
            MsalFailureRules.classify(MsalFailureKind.CLIENT, "DEVICE_NETWORK_NOT_AVAILABLE"),
        )
        assertEquals(RemoteFailure.Offline, AuthFailure.NETWORK.toRemoteFailure())
    }

    @Test fun interactiveAndNetworkFailuresKeepTheirOwnMeanings() {
        assertEquals(AuthFailure.INTERACTION_REQUIRED, MsalFailureRules.classify(MsalFailureKind.INTERACTION_REQUIRED))
        assertEquals(AuthFailure.NETWORK, MsalFailureRules.classify(MsalFailureKind.NETWORK))
        assertEquals(AuthFailure.UNKNOWN, MsalFailureRules.classify(MsalFailureKind.UNKNOWN))
    }

    @Test fun everyKindHasAnAnswerWithASentence() {
        for (kind in MsalFailureKind.entries) {
            val failure = MsalFailureRules.classify(kind)
            assertEquals("$kind maps to an empty sentence", true, failure.toRemoteFailure().message().isNotBlank())
        }
    }

    @Test fun aFailedSignInIsReportedInsteadOfBeingSwallowedAsCancelled() {
        val failed: AuthState = AuthState.Failed(RemoteFailure.SignInRejected)

        assertEquals(RemoteFailure.SignInRejected, failed.failureOrNull())
        assertEquals(false, failed.isUsable())
        // The control that made the old behaviour wrong: a cancelled sign-in really is silent.
        assertEquals(null, AuthState.SignedOut.failureOrNull())
    }
}
