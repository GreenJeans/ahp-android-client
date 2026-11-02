package dev.ahpclient.state

import dev.ahpclient.platform.Synchronized

import kotlin.concurrent.Volatile

import com.microsoft.agenthostprotocol.generated.ActionEnvelope
import com.microsoft.agenthostprotocol.generated.Snapshot
import com.microsoft.agenthostprotocol.generated.SnapshotState
import com.microsoft.agenthostprotocol.generated.StateAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The host reported that this channel no longer exists. */
class ChannelGoneException(val uri: String) : Exception("Channel $uri is gone")

/** What [HostSession] needs from any store it keeps subscribed besides sessions and chats. */
internal interface AuxStore {
    val uri: String
    val needsSubscribe: Boolean
    var openers: Int

    /**
     * True for a channel the library cannot decode a snapshot of (a terminal): it is subscribed with a hand-read reply, and is
     * never named in `initialize` or `reconnect`, whose replies would carry that snapshot and fail to decode as a whole.
     */
    val byHand: Boolean

    fun seed(snapshot: Snapshot): Boolean

    fun apply(envelope: ActionEnvelope)

    fun invalidate()

    fun markGone()

    /** The host's answer for this channel could not be read. The old state, if any, stays; nothing is retried on its own. */
    fun markFailed(cause: Throwable)
}

/**
 * One subscribed channel this client only reads (a terminal, a changeset): a snapshot plus the ordered actions after it,
 * folded with the library's own reducer for that kind. Actions that arrive before the snapshot are held and applied once it
 * lands, and anything at or below the last applied sequence is a duplicate and is dropped. There is no way to send anything
 * through a store: it only reflects what the host says.
 */
class ChannelStore<S : Any> internal constructor(
    override val uri: String,
    private val pick: (SnapshotState) -> S?,
    private val reduce: (S, StateAction) -> S,
    override val byHand: Boolean = false,
) : AuxStore {
    private val _state = MutableStateFlow<S?>(null)

    /** Null until the snapshot arrives. */
    val state: StateFlow<S?> = _state.asStateFlow()

    private val _error = MutableStateFlow<Throwable?>(null)
    val error: StateFlow<Throwable?> = _error.asStateFlow()

    private val early = ArrayList<ActionEnvelope>()
    private var lastApplied = -1L

    @Volatile
    override var needsSubscribe = true
        private set

    override var openers = 0

    /** @return false when [snapshot] holds no state of this kind, so nothing was seeded. */
    @Synchronized
    override fun seed(snapshot: Snapshot): Boolean {
        val next = pick(snapshot.state) ?: return false
        _state.value = next
        _error.value = null
        lastApplied = snapshot.fromSeq
        needsSubscribe = false
        val held = early.sortedBy { it.serverSeq }
        early.clear()
        held.forEach(::applyInOrder)
        return true
    }

    @Synchronized
    override fun apply(envelope: ActionEnvelope) {
        if (_state.value == null) {
            if (early.size < MAX_EARLY_ACTIONS) early += envelope
            return
        }
        applyInOrder(envelope)
    }

    /** The old state stays visible, but the next connection subscribes again to get a fresh snapshot. */
    @Synchronized
    override fun invalidate() {
        needsSubscribe = true
    }

    @Synchronized
    override fun markGone() {
        needsSubscribe = false
        _error.value = ChannelGoneException(uri)
    }

    @Synchronized
    override fun markFailed(cause: Throwable) {
        needsSubscribe = false
        _error.value = cause
    }

    private fun applyInOrder(envelope: ActionEnvelope) {
        if (envelope.serverSeq <= lastApplied) return
        lastApplied = envelope.serverSeq
        _state.value = _state.value?.let { reduce(it, envelope.action) }
    }

    private companion object {
        const val MAX_EARLY_ACTIONS = 1_000
    }
}
