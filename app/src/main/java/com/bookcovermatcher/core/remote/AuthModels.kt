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

    /** No account, or the account needs an interactive sign-in again. */
    INTERACTION_REQUIRED,

    /** The network was unavailable or the service could not be reached. */
    NETWORK,

    /** The packaged MSAL configuration is missing or invalid. */
    CONFIGURATION,

    /** Anything else MSAL reported; carries no message because those can contain account data. */
    UNKNOWN,
}

/** What the app knows about the signed-in account. Never carries a token. */
sealed interface AuthState {
    data object SignedOut : AuthState

    /** [label] is the account's username as MSAL reports it (e.g. an e-mail); it is never logged. */
    data class SignedIn(val label: String?) : AuthState

    /** An account exists but needs an interactive sign-in before it can be used again. */
    data class Expired(val label: String?) : AuthState
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
}

/** True only when an account is usable right now; [AuthState.Expired] is **not** usable. */
fun AuthState.isUsable(): Boolean = this is AuthState.SignedIn

/** A failure during sign-in or silent acquisition, in the app's own vocabulary. */
fun AuthFailure.toRemoteFailure(): RemoteFailure = when (this) {
    AuthFailure.CANCELLED -> RemoteFailure.AuthRequired
    AuthFailure.INTERACTION_REQUIRED -> RemoteFailure.AuthExpired
    AuthFailure.NETWORK -> RemoteFailure.Offline
    AuthFailure.CONFIGURATION -> RemoteFailure.NotConfigured
    AuthFailure.UNKNOWN -> RemoteFailure.Unstable
}
