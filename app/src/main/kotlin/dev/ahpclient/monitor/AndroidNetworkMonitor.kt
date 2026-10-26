package dev.ahpclient.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.ahpclient.state.NetworkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Follows the phone's default network, so a [dev.ahpclient.state.HostSession] stops trying while there is none and
 * reconnects as soon as there is one. [NetworkState.Available] carries the network's own id, which changes when the phone
 * moves from Wi-Fi to mobile data.
 *
 * It does not require NET_CAPABILITY_VALIDATED: a corporate network that blocks Android's connectivity probe would never be
 * validated, and the app would wait for ever on a network that reaches the host. For the same reason it fails open: if the
 * callback cannot be registered, the state stays [NetworkState.Available] and the session retries as it did without a monitor.
 *
 * The callback is registered once and never unregistered. The one monitor lives as long as the process, and so does what it feeds.
 */
class AndroidNetworkMonitor(context: Context) {
    private val _state = MutableStateFlow<NetworkState>(NetworkState.Available(UNKNOWN))
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _state.value = stateOf(network, capabilities)
        }

        override fun onLost(network: Network) {
            // The default network can change to another one before the old one is reported lost: only the one in use ending counts.
            _state.update { if (it == NetworkState.Available(network.toString())) NetworkState.Unavailable else it }
        }
    }

    init {
        try {
            val connectivity = checkNotNull(context.applicationContext.getSystemService(ConnectivityManager::class.java))
            val active = connectivity.activeNetwork
            _state.value = if (active == null) NetworkState.Unavailable else stateOf(active, connectivity.getNetworkCapabilities(active))
            connectivity.registerDefaultNetworkCallback(callback)
        } catch (_: RuntimeException) {
            // No permission, no service, or too many callbacks already: behave as if there were no monitor.
            _state.value = NetworkState.Available(UNKNOWN)
        }
    }

    private fun stateOf(network: Network, capabilities: NetworkCapabilities?): NetworkState =
        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) NetworkState.Available(network.toString()) else NetworkState.Unavailable

    private companion object {
        const val UNKNOWN = "unknown"
    }
}
