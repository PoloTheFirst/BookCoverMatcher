// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A deterministic [ConnectivityMonitor] for tests: no platform, no callback, no clock.
 *
 * It exists so the preflight behaviour of later phases can be driven without an emulator or a
 * network, and so "offline" is a value a test can *hold* instead of a condition it hopes for. It
 * mirrors the Android implementation's two members exactly — [setOnline] moves the published state
 * and [isUsableNow] answers from the same value, because in this fake there is nothing fresher to
 * read.
 *
 * Instrumented tests (v2.6.0-dev.1 Phase 7) need the same double; when that phase arrives this class
 * moves to a shared test-fixture source set rather than being copied.
 */
class FakeConnectivityMonitor(online: Boolean = false) : ConnectivityMonitor {

    private val state = MutableStateFlow(online)
    override val isOnline: StateFlow<Boolean> = state.asStateFlow()

    /** How many times [close] was called; the contract is that extra calls are harmless, not counted. */
    var closeCount: Int = 0
        private set

    val isClosed: Boolean get() = closeCount > 0

    /** A closed monitor reports "unusable", like the Android one, so a leaked call cannot look online. */
    override fun isUsableNow(): Boolean = !isClosed && state.value

    override fun close() {
        closeCount++
        state.value = false
    }

    /** Simulates the network arriving, leaving, or a connected network that is not validated. */
    fun setOnline(online: Boolean) {
        if (isClosed) return
        state.value = online
    }
}
