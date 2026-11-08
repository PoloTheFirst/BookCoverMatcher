// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.remote

/**
 * Why a OneDrive operation did not produce what was asked for.
 *
 * One value per *thing the user can do about it* — never one generic "something went wrong". The
 * sentences in [message] are the ones the UI shows; keeping them here means the mapping is unit-tested
 * once instead of being re-invented in every call site, and that no failure can reach the user as an
 * empty or duplicated message.
 *
 * No value here ever carries a token, a response body or an account identifier.
 */
sealed interface RemoteFailure {

    /** No usable network at all — a preflight answer, not a request that failed. */
    data object Offline : RemoteFailure

    /** There is no signed-in account, so a remote operation cannot even start. */
    data object AuthRequired : RemoteFailure

    /** There is an account, but its tokens can no longer be renewed silently. */
    data object AuthExpired : RemoteFailure

    /** The service asked us to slow down; [retryAfterSeconds] is its `Retry-After` when it sent one. */
    data class Throttled(val retryAfterSeconds: Long? = null) : RemoteFailure

    /** The remote item is gone (deleted, moved, or no longer shared with the signed-in account). */
    data object NotFound : RemoteFailure

    /** Connect/read deadline expired. */
    data object Timeout : RemoteFailure

    /** Transport failed, or a read stalled — described to the user as slow or unstable, never as "slow speed". */
    data object Unstable : RemoteFailure

    /** The device could not hold the file. */
    data object NoStorage : RemoteFailure

    /** The bytes arrived but are not a workbook the library accepts. */
    data object InvalidWorkbook : RemoteFailure

    /** Graph answered 5xx. */
    data class ServerError(val status: Int) : RemoteFailure

    /** Graph rejected the request itself (400/403 for a request we should never have made). */
    data object BadRequest : RemoteFailure

    /** This build has no usable sign-in configuration (no `.env`, so only placeholders were generated). */
    data object NotConfigured : RemoteFailure
}

/**
 * Whether trying the same thing again could plausibly work (v2.6.0-dev.1 Phase 6).
 *
 * This is the rule behind every "Retry" affordance, in one place instead of one per screen:
 *
 *  * **Yes** for the failures that describe a *moment*: offline, a timeout, a dropped connection, a
 *    throttled service, a 5xx. Nothing about the request or the file was wrong, and the next attempt may
 *    well succeed.
 *  * **No** for the failures that describe a *state* the user has to change first: signing in again,
 *    freeing space, importing a different file, freeing the remote file itself. Offering Retry there is a
 *    button that can only fail — and, for authentication, offering it as if it were a network blip is
 *    exactly the confusion Phase 6 is meant to remove.
 */
fun RemoteFailure.isRetryable(): Boolean = when (this) {
    RemoteFailure.Offline,
    RemoteFailure.Timeout,
    RemoteFailure.Unstable,
    is RemoteFailure.Throttled,
    is RemoteFailure.ServerError,
    -> true

    RemoteFailure.AuthRequired,
    RemoteFailure.AuthExpired,
    RemoteFailure.NotFound,
    RemoteFailure.NoStorage,
    RemoteFailure.InvalidWorkbook,
    RemoteFailure.BadRequest,
    RemoteFailure.NotConfigured,
    -> false
}

/** The one sentence the UI shows for [failure]. */
fun RemoteFailure.message(): String = when (this) {
    RemoteFailure.Offline ->
        "No internet connection. OneDrive workbooks can only be imported online. " +
            "Workbooks you already saved still open and rescan offline."
    RemoteFailure.AuthRequired ->
        "Sign in with a personal Microsoft account to use OneDrive."
    RemoteFailure.AuthExpired ->
        "Your OneDrive sign-in expired. Sign in again to check for changes."
    is RemoteFailure.Throttled ->
        if (retryAfterSeconds != null) {
            "OneDrive is busy. Try again in about $retryAfterSeconds second(s)."
        } else {
            "OneDrive is busy. Try again shortly."
        }
    RemoteFailure.NotFound ->
        "This workbook is no longer in OneDrive. The saved copy still opens; rescan it locally, " +
            "or import the file again."
    RemoteFailure.Timeout ->
        "OneDrive took too long to answer. Try again when the connection is better."
    RemoteFailure.Unstable ->
        "The connection to OneDrive failed, so nothing was saved. Try again when you are back online."
    RemoteFailure.NoStorage ->
        "There is not enough device storage for this workbook. Free some space and try again."
    RemoteFailure.InvalidWorkbook ->
        "That file is not a valid .xlsx or .xlsm workbook, so nothing was saved."
    is RemoteFailure.ServerError ->
        "OneDrive failed (HTTP $status). Try again later."
    RemoteFailure.BadRequest ->
        "OneDrive rejected the request."
    RemoteFailure.NotConfigured ->
        "OneDrive sign-in is not configured in this build."
}
