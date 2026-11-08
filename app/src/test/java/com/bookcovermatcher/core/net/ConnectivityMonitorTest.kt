// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.net

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connectivity contract of v2.6.0-dev.1: what counts as online, and what a caller may conclude.
 *
 * The capability rule is tested directly (it is pure), and the monitor is tested through the fake, so
 * no test needs a device, a network or a `ConnectivityManager`.
 */
class ConnectivityMonitorTest {

    // ---- the rule: INTERNET and VALIDATED, both required -------------------------------------

    @Test fun aValidatedInternetNetworkIsUsable() {
        assertTrue(UsableNetwork.accepts(hasInternet = true, isValidated = true))
    }

    @Test fun aConnectedButUnvalidatedNetworkIsNotUsable() {
        // A captive portal or a router with no uplink: the device is "connected" and nothing works.
        assertFalse(UsableNetwork.accepts(hasInternet = true, isValidated = false))
    }

    @Test fun aNetworkThatDoesNotDeclareInternetIsNotUsable() {
        assertFalse(UsableNetwork.accepts(hasInternet = false, isValidated = true))
        assertFalse(UsableNetwork.accepts(hasInternet = false, isValidated = false))
    }

    // ---- the state a screen draws ------------------------------------------------------------

    @Test fun aFreshFakeIsOfflineUnlessItIsToldOtherwise() {
        assertFalse(FakeConnectivityMonitor().isOnline.value)
        assertFalse(FakeConnectivityMonitor().isUsableNow())
        assertTrue(FakeConnectivityMonitor(online = true).isOnline.value)
    }

    @Test fun aNetworkComingBackIsPublishedToACollector() = runBlocking {
        val monitor = FakeConnectivityMonitor(online = false)
        // UNDISPATCHED subscribes before the state changes, so this asserts a real emission rather
        // than reading a value that was already there.
        val sawOnline = async(start = CoroutineStart.UNDISPATCHED) { monitor.isOnline.first { it } }

        monitor.setOnline(true)

        assertTrue(withTimeout(1_000) { sawOnline.await() })
    }

    @Test fun aNetworkDroppingIsPublishedToACollector() = runBlocking {
        val monitor = FakeConnectivityMonitor(online = true)
        val sawOffline = async(start = CoroutineStart.UNDISPATCHED) { monitor.isOnline.first { !it } }

        monitor.setOnline(false)

        assertFalse(withTimeout(1_000) { sawOffline.await() })
    }

    @Test fun losingValidationMovesTheStateBackToOffline() {
        // The edge the OneDrive preflight depends on: connected-but-unvalidated is offline, and the
        // state must move when validation is lost again (the Android callback re-reads capabilities).
        val monitor = FakeConnectivityMonitor(online = false)
        monitor.setOnline(true)
        assertTrue(monitor.isOnline.value)

        monitor.setOnline(false)

        assertFalse(monitor.isOnline.value)
        assertFalse("a preflight must not see a stale online state", monitor.isUsableNow())
    }

    // ---- the preflight ------------------------------------------------------------------------

    @Test fun isUsableNowAnswersWithoutACollector() {
        val monitor = FakeConnectivityMonitor(online = false)
        assertFalse("a preflight must not depend on someone collecting the flow", monitor.isUsableNow())

        monitor.setOnline(true)

        assertTrue(monitor.isUsableNow())
    }

    @Test fun aClosedMonitorReportsUnusableAndIgnoresLaterChanges() {
        val monitor = FakeConnectivityMonitor(online = true)
        assertTrue(monitor.isUsableNow())

        monitor.close()

        assertFalse(monitor.isUsableNow())
        assertFalse(monitor.isOnline.value)

        monitor.setOnline(true)

        assertFalse("a released monitor must not start answering again", monitor.isUsableNow())
    }

    @Test fun closingTwiceIsHarmless() {
        val monitor = FakeConnectivityMonitor(online = true)

        monitor.close()
        monitor.close()

        assertFalse(monitor.isUsableNow())
        assertEquals(2, monitor.closeCount)
    }
}
