// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.bookcovermatcher.core.net.ConnectivityMonitor
import com.bookcovermatcher.core.net.UsableNetwork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Android half of [ConnectivityMonitor]: `ConnectivityManager` + `NetworkCapabilities`.
 *
 * Requires `android.permission.ACCESS_NETWORK_STATE` (declared in `AndroidManifest.xml`). One
 * default-network callback is registered in the constructor and unregistered by [close], which
 * `MainViewModel.onCleared()` calls — a finished screen must not leave a platform listener behind.
 *
 * The callback bodies run on a binder thread, which is safe here: [MutableStateFlow] is thread-safe
 * and every branch **re-reads the platform** instead of assuming which network changed, so a burst of
 * `onLost`/`onAvailable`/`onCapabilitiesChanged` events cannot leave a stale value behind.
 *
 * A default network callback also reports a network that is connected but not yet validated (a
 * captive portal), and later reports it again when validation succeeds. That is why the state is
 * derived from the capabilities rather than from "a callback fired": the transition from *connected,
 * unvalidated* to *validated* is exactly the edge the OneDrive preflight depends on.
 *
 * Both the registration and the capability read are guarded. A device that refuses either (permission
 * revoked by policy, a vendor `ConnectivityManager` fault) must degrade to a best-effort answer instead
 * of crashing the app on launch.
 */
class AndroidConnectivityMonitor(context: Context) : ConnectivityMonitor {

    private val manager: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val _isOnline = MutableStateFlow(readUsableNow())
    override val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    /** True while the platform listener is registered; also the guard that drops late callbacks after [close]. */
    private var listening = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onUnavailable() = refresh()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refresh()
    }

    init {
        val cm = manager
        listening = if (cm == null) {
            false
        } else {
            runCatching {
                cm.registerDefaultNetworkCallback(callback)
                true
            }.getOrDefault(false)
        }
        // When registration is refused the monitor still answers isUsableNow(); it just cannot push
        // updates, which is why isUsableNow() exists at all.
    }

    override fun isUsableNow(): Boolean = readUsableNow()

    override fun close() {
        val cm = manager
        if (listening) {
            listening = false
            runCatching { cm?.unregisterNetworkCallback(callback) }
        }
        // The published state is dropped even when registration never succeeded: callers use the flow
        // as "may a remote operation start?", and a released monitor must answer no.
        _isOnline.value = false
    }

    private fun refresh() {
        // A callback can already be queued when the listener is removed; it must not resurrect a state.
        if (!listening) return
        _isOnline.value = readUsableNow()
    }

    private fun readUsableNow(): Boolean {
        val cm = manager ?: return false
        return try {
            val active = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(active) ?: return false
            UsableNetwork.accepts(
                hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            )
        } catch (e: SecurityException) {
            // ACCESS_NETWORK_STATE is missing or was revoked, so the platform will not tell us. Answer
            // "possibly online": a OneDrive attempt that then fails reports the real reason (sign-in,
            // throttling), whereas a false "no internet connection" would send the user chasing a
            // network problem that does not exist.
            true
        }
    }
}
