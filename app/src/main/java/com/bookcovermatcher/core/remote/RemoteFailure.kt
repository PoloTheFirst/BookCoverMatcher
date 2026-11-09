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

    /**
     * The permission grant does not cover what the app needs to read the user's files.
     *
     * Reached when the user declines the requested Graph scopes during sign-in — a *decision*, not a
     * connection problem, which is why it is not `Unstable` (v2.6.0-dev.1 crash-fix-4).
     */
    data object PermissionDenied : RemoteFailure

    /**
     * Microsoft rejected the sign-in / token request itself: a redirect or client-id mismatch, an
     * unregistered `redirect_uri`, or a service refusal (`invalid_grant`, `invalid_client`, …).
     *
     * This is the failure that used to be reported as "the connection failed, try when you are back
     * online" even though the device was online and the real cause was the app registration
     * (v2.6.0-dev.1 crash-fix-4). Carries no code, because the service's own text can contain account
     * data; the *class* is enough to tell the user what to do.
     */
    data object SignInRejected : RemoteFailure

    /**
     * OneDrive answered, but not in a shape this app can use: a body that is not the documented JSON, a
     * content redirect with no `Location`, or a transfer that failed in a way that is not a transport
     * error. Retrying may work; being "back online" is not the issue, so it must not say so.
     */
    data object UnexpectedResponse : RemoteFailure

    /**
     * The interactive sign-in was started but never came back with an answer.
     *
     * MSAL answers through `BrowserTabActivity`; when the browser never redirects (for example because the
     * sign-in page reported a redirect-URI mismatch for this build and never handed control back), no
     * callback ever arrives. The flow used to wait forever and leave the panel on a spinner with nothing
     * to act on, which reads to the user as "OneDrive folders do not open"
     * (v2.6.0-dev.1 crash-fix-5).
     */
    data object SignInNotCompleted : RemoteFailure

    /**
     * A sign-in or token failure this app could not attribute to any known cause.
     *
     * It exists so that "I do not know what happened" is never reported as a connectivity problem. That is
     * exactly what cost four earlier attempts: an `IllegalArgumentException` from MSAL's own silent-token
     * validator was mapped to `UNKNOWN` → `Unstable` → *"try again when you are back online"*, when the
     * truth was that no notification, browser or network had anything to do with it
     * (v2.6.0-dev.1 crash-fix-6).
     */
    data object AuthUnexpected : RemoteFailure


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
    // The request and the file were fine; the answer was not understood. A second attempt is cheap and
    // a proxy or a half-written page can genuinely succeed next time.
    RemoteFailure.UnexpectedResponse,
    -> true

    RemoteFailure.AuthRequired,
    RemoteFailure.AuthExpired,
    RemoteFailure.NotFound,
    RemoteFailure.NoStorage,
    RemoteFailure.InvalidWorkbook,
    RemoteFailure.BadRequest,
    RemoteFailure.NotConfigured,
    // A decision the user has to change (allow access) or a configuration the owner has to fix.
    RemoteFailure.PermissionDenied,
    RemoteFailure.SignInRejected,
    // Retrying the *listing* cannot finish a sign-in; the user has to start it again.
    RemoteFailure.SignInNotCompleted,
    // Nothing to retry: the cause is unidentified, so a second identical attempt would fail the same way.
    RemoteFailure.AuthUnexpected,
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
    RemoteFailure.PermissionDenied ->
        "OneDrive did not get permission to read your files, so nothing was saved. " +
            "Sign in again and allow access when Microsoft asks."
    RemoteFailure.SignInRejected ->
        "Microsoft rejected the OneDrive sign-in request, so nothing was saved. Sign in again; if it " +
            "keeps happening, the Azure app registration for this build needs checking."
    RemoteFailure.UnexpectedResponse ->
        "OneDrive sent an answer this app could not use, so nothing was saved. " +
            "Try again; if it keeps happening, update the app."
    RemoteFailure.SignInNotCompleted ->
        "The OneDrive sign-in did not finish, so no folders were loaded. Sign in again; if the browser " +
            "showed an error instead of returning to the app, the Azure app registration for this build " +
            "needs checking."
    RemoteFailure.AuthUnexpected ->
        "The OneDrive sign-in hit an unexpected error, so no folders were loaded. Try again; if it keeps " +
            "happening, this build needs updating. Your saved workbooks are unaffected."
    is RemoteFailure.ServerError ->
        "OneDrive failed (HTTP $status). Try again later."
    RemoteFailure.BadRequest ->
        "OneDrive rejected the request."
    RemoteFailure.NotConfigured ->
        "OneDrive sign-in is not configured in this build."
}
