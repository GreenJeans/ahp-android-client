package dev.ahpclient.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether the phone has a network a connection could use, and which one. What a [HostSession] reconnects on, instead of retrying blind. */
sealed interface NetworkState {
    /** No usable network: nothing can connect, so nothing should try. */
    data object Unavailable : NetworkState

    /** [id] changes whenever the default network does (Wi-Fi to mobile, a new Wi-Fi), because a socket made on the old one is dead. */
    data class Available(val id: String) : NetworkState

    companion object {
        /** A network that is always there and never changes, for callers that have no monitor (the probe, tests). */
        val ALWAYS_AVAILABLE: StateFlow<NetworkState> = MutableStateFlow<NetworkState>(Available("default"))
    }
}
