package com.neutrino.shared.android.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the device has a usable network, and whether it is metered — the Android counterpart of
 * `NetworkMonitor` in neutrino_shared_ios. Each app had its own copy of this callback; they now
 * share this one.
 *
 * The state is read from the active network when the monitor is built, not left at an optimistic
 * `true`: the default-network callback says nothing at all when the app starts with no network, so
 * a monitor that waited for it would report "online" for the whole of an offline launch.
 *
 * Needs `ACCESS_NETWORK_STATE`, which this library's manifest declares.
 */
class NetworkMonitor(context: Context) {

    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val _isOnline = MutableStateFlow(currentCapabilities()?.let(::isUsable) ?: (manager == null))
    private val _isMetered = MutableStateFlow(currentCapabilities()?.let(::isMetered) ?: false)

    /** Whether there is a network with internet access. Updated from a binder thread; collect it anywhere. */
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    /** True on cellular or a metered hotspot: still online, just a network the user may not want used. */
    val isMetered: StateFlow<Boolean> = _isMetered.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    /** Whether a sync may transfer right now, honouring the "sync on cellular" setting. */
    fun shouldSync(allowMetered: Boolean): Boolean = _isOnline.value && (allowMetered || !_isMetered.value)

    /** Begins observing the default network. Safe to call more than once. */
    fun start() {
        if (callback != null || manager == null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _isMetered.value = isMetered(caps)
                _isOnline.value = isUsable(caps)
            }

            override fun onLost(network: Network) {
                _isOnline.value = false
            }
        }
        // Registration can be refused (too many callbacks for this uid); the initial state stands.
        if (runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess) this.callback = callback
    }

    fun stop() {
        val callback = callback ?: return
        runCatching { manager?.unregisterNetworkCallback(callback) }
        this.callback = null
    }

    /** Drives the state without a real network, for tests and previews. */
    fun setForTesting(online: Boolean, metered: Boolean = false) {
        _isMetered.value = metered
        _isOnline.value = online
    }

    private fun currentCapabilities(): NetworkCapabilities? =
        runCatching { manager?.getNetworkCapabilities(manager.activeNetwork) }.getOrNull()

    private fun isUsable(caps: NetworkCapabilities) = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

    private fun isMetered(caps: NetworkCapabilities) = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}
