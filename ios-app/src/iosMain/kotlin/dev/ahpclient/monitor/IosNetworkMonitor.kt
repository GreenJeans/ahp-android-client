@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.ahpclient.monitor

import dev.ahpclient.state.NetworkState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Network.nw_interface_type_cellular
import platform.Network.nw_interface_type_wifi
import platform.Network.nw_interface_type_wired
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_uses_interface_type
import platform.darwin.dispatch_get_main_queue

/**
 * Observes iOS network reachability via Apple's Network framework (`nw_path_monitor_create`) and publishes
 * [NetworkState.Available] / [NetworkState.Unavailable] so [dev.ahpclient.state.HostSession] pauses reconnect attempts
 * when offline and reconnects immediately when connectivity returns or switches interface type.
 */
class IosNetworkMonitor {
    private val _state = MutableStateFlow<NetworkState>(NetworkState.Available("initial"))
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    private val monitor = nw_path_monitor_create()

    init {
        nw_path_monitor_set_queue(monitor, dispatch_get_main_queue())
        nw_path_monitor_set_update_handler(monitor) { path ->
            val status = nw_path_get_status(path)
            _state.value = if (status == nw_path_status_satisfied) {
                val kind = when {
                    nw_path_uses_interface_type(path, nw_interface_type_wifi) -> "wifi"
                    nw_path_uses_interface_type(path, nw_interface_type_cellular) -> "cellular"
                    nw_path_uses_interface_type(path, nw_interface_type_wired) -> "wired"
                    else -> "other"
                }
                NetworkState.Available(kind)
            } else {
                NetworkState.Unavailable
            }
        }
        nw_path_monitor_start(monitor)
    }

    fun close() {
        nw_path_monitor_cancel(monitor)
    }
}
