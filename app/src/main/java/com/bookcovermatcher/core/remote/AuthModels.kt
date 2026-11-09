// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

/**
 * A bearer token, in a wrapper whose [toString] is redacted.
 *
 * This exists for one reason: an access token must never reach a log line, a toast or a crash report.
 * A `String` token is one careless string template away from being printed; a value class that prints
 * `AccessToken(***)` cannot be. Callers hand it straight to the HTTP layer via [reveal].
 */
@JvmInline
value class AccessToken(private val value: String) {

    /** The raw token, for the `Authorization` header only. */
    fun reveal(): String = value

    /** Deliberately useless, so an accidental interpolation leaks nothing. */
    override fun toString(): String = "AccessToken(***)"

    companion object {
        fun of(raw: String): AccessToken = AccessToken(raw.trim())
    }
}

/** What a token request produced. */
sealed interface TokenResult {
    data class Success(val token: AccessToken) : TokenResult
    data class Failure(val failure: AuthFailure) : TokenResult
}

/** Why a sign-in or silent token request did not produce a token. */
enum class AuthFailure {
    /** The user closed the sign-in page (or dismissed it) — not an error to shout about. */
    CANCELLED,

    /** The user declined the Graph permissions the app asks for (v2.6.0-dev.1 crash-fix-4). */
    CONSENT_DECLINED,

    /** No account, or the account needs an interactive sign-in again. */
    INTERACTION_REQUIRED,

    /** The network was unavailable or the service could not be reached. */
    NETWORK,

    /** MSAL refused the request locally: bad parameters, an unusable redirect, an unreadable cache. */
    CLIENT_ERROR,

    /** Microsoft's sign-in service refused the request (`invalid_grant`, `invalid_client`, 4xx/5xx). */
    SERVICE_ERROR,

    /** The packaged MSAL configuration is missing or invalid. */
    CONFIGURATION,

    /** Anything else MSAL reported; carries no message because those can contain account data. */
    UNKNOWN,
}

/**
 * MSAL's own error kind, reduced to what this app distinguishes (v2.6.0-dev.1 crash-fix-4).
 *
 * Kept separate from the MSAL types so the classification is a pure, unit-tested function rather than a
 * `when` buried in an Android-only class: `MsalOneDriveAuth` maps an exception to a [MsalFailureKind] and
 * [MsalFailureRules.classify] decides the app's vocabulary.
 */
enum class MsalFailureKind {
    CANCELLED,
    CONSENT_DECLINED,
    INTERACTION_REQUIRED,
    CLIENT,
    SERVICE,
    NETWORK,
    UNKNOWN,
}

/** MSAL's classification rules, in one testable place. */
object MsalFailureRules {

    /** `MsalClientException.DEVICE_NETWORK_NOT_AVAILABLE` — the only client error that *is* connectivity. */
    const val DEVICE_NETWORK_NOT_AVAILABLE = "device_network_not_available"

    fun classify(kind: MsalFailureKind, clientErrorCode: String? = null): AuthFailure = when (kind) {
        MsalFailureKind.CANCELLED -> AuthFailure.CANCELLED
        MsalFailureKind.CONSENT_DECLINED -> AuthFailure.CONSENT_DECLINED
        MsalFailureKind.INTERACTION_REQUIRED -> AuthFailure.INTERACTION_REQUIRED
        // A client error is MSAL's local refusal, except for the one code that really is "no network".
        MsalFailureKind.CLIENT ->
            if (clientErrorCode.equals(DEVICE_NETWORK_NOT_AVAILABLE, ignoreCase = true)) {
                AuthFailure.NETWORK
            } else {
                AuthFailure.CLIENT_ERROR
            }
        MsalFailureKind.SERVICE -> AuthFailure.SERVICE_ERROR
        MsalFailureKind.NETWORK -> AuthFailure.NETWORK
        MsalFailureKind.UNKNOWN -> AuthFailure.UNKNOWN
    }
}

/** What the app knows about the signed-in account. Never carries a token. */
sealed interface AuthState {
    data object SignedOut : AuthState

    /** [label] is the account's username as MSAL reports it (e.g. an e-mail); it is never logged. */
    data class SignedIn(val label: String?) : AuthState

    /** An account exists but needs an interactive sign-in before it can be used again. */
    data class Expired(val label: String?) : AuthState

    /**
     * A sign-in attempt failed for a reason the user should be told (v2.6.0-dev.1 crash-fix-4).
     *
     * This is a **one-shot result** of `OneDriveAuth.signIn`, never stored in `OneDriveAuth.state`: a
     * failed sign-in still leaves the app signed out, but the reason must reach the UI. Before this
     * existed every failed sign-in was flattened to [SignedOut], which the ViewModel treats as "the user
     * cancelled", so an MSAL refusal was invisible and the user only met the failure later as a
     * mislabelled connection error.
     */
    data class Failed(val failure: RemoteFailure) : AuthState
}

/**
 * The failure a finished sign-in should report, or `null` when there is nothing to report
 * (v2.6.0-dev.1 Phase 6).
 *
 * This is the rule that keeps a **cancelled** sign-in quiet: the user closed the page, so they are still
 * signed out and saying anything would be inventing an error. Only an account that exists but needs an
 * interactive sign-in produces a message, and that message is the agreed "your sign-in expired" sentence.
 *
 * Pure and separate from the ViewModel so it can be asserted without MSAL, an Activity or a device.
 */
fun AuthState.failureOrNull(): RemoteFailure? = when (this) {
    AuthState.SignedOut -> null
    is AuthState.SignedIn -> null
    is AuthState.Expired -> RemoteFailure.AuthExpired
    // The whole point of the variant: a failed sign-in is reported, not treated as "cancelled".
    is AuthState.Failed -> failure
}

/** True only when an account is usable right now; [AuthState.Expired] is **not** usable. */
fun AuthState.isUsable(): Boolean = this is AuthState.SignedIn

/** A failure during sign-in or silent acquisition, in the app's own vocabulary. */
fun AuthFailure.toRemoteFailure(): RemoteFailure = when (this) {
    AuthFailure.CANCELLED -> RemoteFailure.AuthRequired
    AuthFailure.INTERACTION_REQUIRED -> RemoteFailure.AuthExpired
    AuthFailure.NETWORK -> RemoteFailure.Offline
    AuthFailure.CONFIGURATION -> RemoteFailure.NotConfigured
    // Added by v2.6.0-dev.1 crash-fix-4: none of these three is a connectivity problem, and saying so
    // sent the user to "try again when you are back online" for a decision and a configuration mistake.
    AuthFailure.CONSENT_DECLINED -> RemoteFailure.PermissionDenied
    AuthFailure.CLIENT_ERROR -> RemoteFailure.SignInRejected
    AuthFailure.SERVICE_ERROR -> RemoteFailure.SignInRejected
    // crash-fix-6: an unattributed failure is still not a network failure. Mapping it to `Unstable` is what
    // turned MSAL's own "Do not provide callback for synchronous methods" into "you are back offline".
    AuthFailure.UNKNOWN -> RemoteFailure.AuthUnexpected
}
