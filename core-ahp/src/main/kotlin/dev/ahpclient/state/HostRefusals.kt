package dev.ahpclient.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What one host has refused on this connection, so the app stops offering it. Memory only: it belongs to one [HostSession], so it
 * lasts as long as that connection to the host (dropping and coming back on the same connection keeps it) and a new connection to
 * the host starts with nothing refused. Only the host's own refusal is kept here, never a send that failed or a request this app's
 * own policy stopped.
 */
class HostRefusals {
    private val _addFolder = MutableStateFlow<String?>(null)

    /**
     * The host's own words the first time it refused to add a folder to a session (`session/workingDirectorySet`) on this
     * connection, or null while it has not. Untrusted display text, bounded. The VS Code host refuses it from any client that is
     * not an Editor Window.
     */
    val addFolder: StateFlow<String?> = _addFolder.asStateFlow()

    /** Notes that the host refused to add a folder. The first refusal is kept; later ones do not replace it. */
    fun refuseAddFolder(reason: String) {
        _addFolder.compareAndSet(null, reason.take(MAX_REASON))
    }

    private val _removeFolder = MutableStateFlow<String?>(null)

    /**
     * The host's own words the first time it refused to remove a folder from a session (`session/workingDirectoryRemoved`) on this
     * connection, or null while it has not. Kept apart from [addFolder]: a refused add does not withdraw removing, nor the other
     * way round. Untrusted display text, bounded.
     */
    val removeFolder: StateFlow<String?> = _removeFolder.asStateFlow()

    /** Notes that the host refused to remove a folder. The first refusal is kept; later ones do not replace it. */
    fun refuseRemoveFolder(reason: String) {
        _removeFolder.compareAndSet(null, reason.take(MAX_REASON))
    }

    companion object {
        const val MAX_REASON = 500
    }
}
