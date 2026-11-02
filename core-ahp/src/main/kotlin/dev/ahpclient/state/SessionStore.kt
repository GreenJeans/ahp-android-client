package dev.ahpclient.state

import dev.ahpclient.platform.Synchronized

import kotlin.concurrent.Volatile

import dev.ahpclient.ahp.ConfigFacts
import com.microsoft.agenthostprotocol.generated.ActionEnvelope
import com.microsoft.agenthostprotocol.generated.SessionState
import com.microsoft.agenthostprotocol.generated.Snapshot
import com.microsoft.agenthostprotocol.generated.SnapshotState
import com.microsoft.agenthostprotocol.sessionReducer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The host reported that this session no longer exists. */
class SessionGoneException(val uri: String) : Exception("Session $uri is gone")

/**
 * One subscribed session: a snapshot plus the ordered actions after it, folded with the generated `sessionReducer`.
 * Actions that arrive before the snapshot are held and applied once it lands; `serverSeq` is host-wide and strictly
 * increasing, so anything at or below the last applied sequence is a duplicate and is dropped.
 */
class SessionStore internal constructor(val uri: String) {
    private val _state = MutableStateFlow<SessionState?>(null)

    /** Null until the snapshot arrives. */
    val state: StateFlow<SessionState?> = _state.asStateFlow()

    private val _error = MutableStateFlow<Throwable?>(null)
    val error: StateFlow<Throwable?> = _error.asStateFlow()

    private val _chatMutableKeys = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Which of the per-chat settings this app reads (mode, approvals) the host's schema lets a chat set for itself. The 1.0.0 library has
     * no field for it, so it is read from the raw reply (see [ConfigFacts]). Empty until the snapshot arrives, and for a host without any.
     */
    val chatMutableKeys: StateFlow<Set<String>> = _chatMutableKeys.asStateFlow()

    private val early = ArrayList<ActionEnvelope>()
    private var lastApplied = -1L

    @Volatile
    internal var needsSubscribe = true
        private set

    /** Counts openers (list screen, detail screen); the host connection drops the subscription at zero. */
    internal var openers = 0

    /** @return false when [snapshot] holds no session state, so nothing was seeded. */
    @Synchronized
    internal fun seed(snapshot: Snapshot, facts: ConfigFacts? = null): Boolean {
        val session = (snapshot.state as? SnapshotState.Session)?.value ?: return false
        _state.value = session
        facts?.chatMutableKeys?.let { _chatMutableKeys.value = it }
        _error.value = null
        lastApplied = snapshot.fromSeq
        needsSubscribe = false
        val held = early.sortedBy { it.serverSeq }
        early.clear()
        held.forEach(::applyInOrder)
        return true
    }

    @Synchronized
    internal fun apply(envelope: ActionEnvelope) {
        if (_state.value == null) {
            if (early.size < MAX_EARLY_ACTIONS) early += envelope
            return
        }
        applyInOrder(envelope)
    }

    /** The old state stays visible, but the next connection subscribes again to get a fresh snapshot. */
    @Synchronized
    internal fun invalidate() {
        needsSubscribe = true
    }

    @Synchronized
    internal fun markGone() {
        needsSubscribe = false
        _error.value = SessionGoneException(uri)
    }

    private fun applyInOrder(envelope: ActionEnvelope) {
        if (envelope.serverSeq <= lastApplied) return
        lastApplied = envelope.serverSeq
        _state.value = _state.value?.let { sessionReducer(it, envelope.action) }
    }

    private companion object {
        const val MAX_EARLY_ACTIONS = 1_000
    }
}
