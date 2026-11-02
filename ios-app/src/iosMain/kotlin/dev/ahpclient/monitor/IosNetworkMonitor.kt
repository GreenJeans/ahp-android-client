@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.ahpclient.monitor

import dev.ahpclient.state.NetworkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_get_main_queue

/**
 * Observes iOS network reachability via Apple's Network framework (`nw_path_monitor_create`) and publishes
 * [NetworkState.ONLINE] / [NetworkState.OFFLINE] so [dev.ahpclient.state.HostSession] pauses reconnect attempts
 * when offline and reconnects immediately when connectivity returns.
 */
class IosNetworkMonitor {
    private val _state = MutableStateFlow(NetworkState.ONLINE)
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    private val monitor = nw_path_monitor_create()

    init {
        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_set_update_handler(monitor) { path ->
            val status = nw_path_get_status(path)
            _state.value = if (status == nw_path_status_satisfied) {
                NetworkState.ONLINE
            } else {
                NetworkState.OFFLINE
            }
        }
        nw_path_monitor_start(monitor)
    }

    fun close() {
        nw_path_monitor_cancel(monitor)
    }
}
