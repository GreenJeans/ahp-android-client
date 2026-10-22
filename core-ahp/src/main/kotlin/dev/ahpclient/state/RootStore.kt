package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.RootState
import com.microsoft.agenthostprotocol.generated.SessionAddedParams
import com.microsoft.agenthostprotocol.generated.SessionRemovedParams
import com.microsoft.agenthostprotocol.generated.SessionSummary
import com.microsoft.agenthostprotocol.generated.SessionSummaryChangedParams
import com.microsoft.agenthostprotocol.generated.StateAction
import com.microsoft.agenthostprotocol.rootReducer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject

/**
 * What the host tells every client on the root channel: its agents and the session list. The list is built from a
 * `listSessions` snapshot and kept current by the `root/session*` notifications, which are not part of the action
 * stream and are never replayed after a reconnect (the caller refetches the list instead).
 */
class RootStore {
    private val lock = Any()
    private val byUri = LinkedHashMap<String, SessionSummary>()

    private val _root = MutableStateFlow<RootState?>(null)
    val root: StateFlow<RootState?> = _root.asStateFlow()

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())

    /** Needs-input first, then newest first. See [SessionCatalog.sorted]. */
    val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()

    private val _decodeFailures = MutableStateFlow(0)

    /** Notifications this store recognized by name but could not decode. */
    val decodeFailures: StateFlow<Int> = _decodeFailures.asStateFlow()

    fun seedRoot(state: RootState) {
        _root.value = state
    }

    /** Replaces the whole list, for example after a (re)connect. */
    fun replaceCatalog(items: List<SessionSummary>) = mutate {
        byUri.clear()
        items.forEach { byUri[it.resource] = it }
    }

    fun applyAction(action: StateAction) {
        _root.update { current -> current?.let { rootReducer(it, action) } }
    }

    /** @return true when [method] is one of the root notifications this store handles. */
    fun applyNotification(method: String, params: JsonObject): Boolean = try {
        when (method) {
            "root/sessionAdded" -> {
                val added = Ahp.json.decodeFromJsonElement(SessionAddedParams.serializer(), params)
                mutate { byUri[added.summary.resource] = added.summary }
                true
            }
            "root/sessionRemoved" -> {
                val removed = Ahp.json.decodeFromJsonElement(SessionRemovedParams.serializer(), params)
                mutate { byUri.remove(removed.session) }
                true
            }
            "root/sessionSummaryChanged" -> {
                val changed = Ahp.json.decodeFromJsonElement(SessionSummaryChangedParams.serializer(), params)
                // An update for a session we have never seen cannot build a full row; the next list refresh brings it in.
                mutate { byUri[changed.session]?.let { byUri[changed.session] = SessionCatalog.merge(it, changed.changes) } }
                true
            }
            else -> false
        }
    } catch (_: SerializationException) {
        _decodeFailures.update { it + 1 }
        true
    } catch (_: IllegalArgumentException) {
        _decodeFailures.update { it + 1 }
        true
    }

    private inline fun mutate(change: () -> Unit) = synchronized(lock) {
        change()
        _sessions.value = SessionCatalog.sorted(byUri.values)
    }
}
