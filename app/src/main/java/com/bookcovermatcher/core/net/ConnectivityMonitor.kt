// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.net

import kotlinx.coroutines.flow.StateFlow

/**
 * The app's single answer to "may a remote operation start now?" (v2.6.0-dev.1).
 *
 * Two questions, two members:
 *
 *  * [isOnline] is the observable state a screen can draw, so a row that says *Offline copy* does not
 *    have to poll;
 *  * [isUsableNow] is a **fresh** platform query for a preflight, because the flow's last value can be
 *    stale by the time the user taps a button (the network may have dropped while the app was in the
 *    background, before a callback arrived).
 *
 * "Online" here means exactly one thing: the device has a default network that declares `INTERNET`
 * **and** that the platform has validated — the rule is [UsableNetwork], and it is pure so both
 * branches are unit-tested. It is **not** a speed measurement, and it does **not** mean OneDrive is
 * reachable: a validated network can still time out, throttle or reject a token. Those are remote
 * failures (`UNSTABLE`, `TIMEOUT`, `THROTTLED`, `SERVER_ERROR`), never "no connection", and the UI
 * must keep the two apart.
 *
 * **No local workbook operation may consult this interface.** Opening, parsing, rescanning, renaming
 * and removing a saved workbook read the app's own copy under `files/workbooks/` and work with no
 * network at all; only a OneDrive import and an explicit remote check may ask anything here.
 * Injecting the monitor instead of reading `ConnectivityManager` at the call sites is what keeps that
 * rule visible and testable.
 *
 * Plain Kotlin/JVM — no `android.*` type appears in this file. The Android half is
 * `data/AndroidConnectivityMonitor`; the test half is a fake, so no test needs a device.
 */
interface ConnectivityMonitor {

    /** Current online state for the UI. Changes when the platform's default network does. */
    val isOnline: StateFlow<Boolean>

    /** A fresh check for a preflight — never the last emitted value. */
    fun isUsableNow(): Boolean

    /** Releases the platform listener and stops publishing. Idempotent; a closed monitor reports unusable. */
    fun close()
}

/**
 * The rule behind [ConnectivityMonitor.isOnline], kept pure so both inputs can be exercised without an
 * Android runtime: a network is usable only when it declares `INTERNET` **and** the platform has
 * marked it `VALIDATED`.
 *
 * `INTERNET` alone is not enough. A captive portal or a router with no working uplink advertises the
 * capability and answers nothing — that is precisely what "connected, but OneDrive will not answer"
 * looks like locally, and calling that network online would blame OneDrive for a local problem.
 * `VALIDATED` is only ever set together with `INTERNET`, but both are required here so that neither
 * check can be dropped by accident later.
 */
object UsableNetwork {
    fun accepts(hasInternet: Boolean, isValidated: Boolean): Boolean = hasInternet && isValidated
}
