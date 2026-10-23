package dev.ahpclient.state

import com.microsoft.agenthostprotocol.chatReducer
import com.microsoft.agenthostprotocol.generated.ActionEnvelope
import com.microsoft.agenthostprotocol.generated.ChatState
import com.microsoft.agenthostprotocol.generated.Snapshot
import com.microsoft.agenthostprotocol.generated.SnapshotState
import com.microsoft.agenthostprotocol.generated.StateActionChatTurnsLoaded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** The host reported that this chat no longer exists. */
class ChatGoneException(val uri: String) : Exception("Chat $uri is gone")

/**
 * One subscribed chat: a snapshot plus the ordered actions after it, folded with the generated `chatReducer`.
 * Ordering and duplicate handling follow [SessionStore]: actions before the snapshot are held, and anything at or below
 * the last applied `serverSeq` is dropped.
 *
 * Every action is reduced at once, so nothing is lost, but [state] is published at most once per [publishIntervalMs]:
 * the first change shows immediately, later ones within the interval are coalesced, and the latest state is always
 * published when the interval ends. A streaming reply therefore costs the UI at most 1000 / [publishIntervalMs] updates a second.
 */
class ChatStore internal constructor(
    val uri: String,
    private val scope: CoroutineScope,
    /** Asks the host for the page that starts at the cursor. The turns arrive as a `chat/turnsLoaded` action, not as the reply. */
    private val fetchOlder: suspend (cursor: String) -> Unit,
    private val publishIntervalMs: Long = PUBLISH_INTERVAL_MS,
) {
    private val _state = MutableStateFlow<ChatState?>(null)

    /** Null until the snapshot arrives. */
    val state: StateFlow<ChatState?> = _state.asStateFlow()

    private val _error = MutableStateFlow<Throwable?>(null)
    val error: StateFlow<Throwable?> = _error.asStateFlow()

    private val _loadingOlder = MutableStateFlow(false)
    val loadingOlder: StateFlow<Boolean> = _loadingOlder.asStateFlow()

    private val _olderError = MutableStateFlow<Throwable?>(null)

    /** Why the last [loadOlder] failed; cleared when the next one starts. */
    val olderError: StateFlow<Throwable?> = _olderError.asStateFlow()

    private val lock = Any()
    private var latest: ChatState? = null
    private val early = ArrayList<ActionEnvelope>()
    private var lastApplied = -1L
    private var cooling = false
    private var dirty = false
    private var cooldown: Job? = null
    private var cooldownGeneration = 0L
    private var olderArrived: CompletableDeferred<Unit>? = null

    @Volatile
    internal var needsSubscribe = true
        private set

    /** Counts openers; the host connection drops the subscription at zero. */
    internal var openers = 0

    /** @return false when [snapshot] holds no chat state, so nothing was seeded. */
    internal fun seed(snapshot: Snapshot): Boolean {
        val chat = (snapshot.state as? SnapshotState.Chat)?.value ?: return false
        synchronized(lock) {
            latest = chat
            _error.value = null
            lastApplied = snapshot.fromSeq
            needsSubscribe = false
            val held = early.sortedBy { it.serverSeq }
            early.clear()
            held.forEach(::reduce)
            publishNow()
            olderArrived?.complete(Unit)
        }
        return true
    }

    internal fun apply(envelope: ActionEnvelope) {
        synchronized(lock) {
            if (latest == null) {
                if (early.size < MAX_EARLY_ACTIONS) early += envelope
                return
            }
            reduce(envelope)
            if (cooling) dirty = true else publishNow()
        }
    }

    /** The old state stays visible, but the next connection subscribes again to get a fresh snapshot. */
    internal fun invalidate() {
        needsSubscribe = true
    }

    internal fun markGone() {
        synchronized(lock) {
            needsSubscribe = false
            _error.value = ChatGoneException(uri)
            olderArrived?.complete(Unit)
        }
    }

    /**
     * Asks for the next page of older turns. They arrive as a `chat/turnsLoaded` action, which the reducer merges in front
     * of the loaded ones. Does nothing when there is no older page or one is already on its way.
     */
    fun loadOlder() {
        val arrived = CompletableDeferred<Unit>()
        val cursor = synchronized(lock) {
            if (_loadingOlder.value) return
            val next = latest?.turnsNextCursor ?: return
            _loadingOlder.value = true
            _olderError.value = null
            olderArrived = arrived
            next
        }
        scope.launch {
            try {
                fetchOlder(cursor)
                withTimeout(OLDER_TIMEOUT_MS) { arrived.await() }
            } catch (e: TimeoutCancellationException) {
                _olderError.value = e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _olderError.value = e
            } finally {
                synchronized(lock) { if (olderArrived === arrived) olderArrived = null }
                _loadingOlder.value = false
            }
        }
    }

    private fun reduce(envelope: ActionEnvelope) {
        if (envelope.serverSeq <= lastApplied) return
        lastApplied = envelope.serverSeq
        latest = latest?.let { chatReducer(it, envelope.action) }
        if (envelope.action is StateActionChatTurnsLoaded) olderArrived?.complete(Unit)
    }

    private fun publishNow() {
        _state.value = latest
        dirty = false
        cooling = true
        // A cooldown that already woke and is waiting for the lock cannot be cancelled, so each one checks it is still the current one.
        val generation = ++cooldownGeneration
        cooldown?.cancel()
        cooldown = scope.launch {
            delay(publishIntervalMs)
            synchronized(lock) {
                if (generation != cooldownGeneration) return@launch
                cooling = false
                if (dirty) publishNow()
            }
        }
    }

    internal companion object {
        const val PUBLISH_INTERVAL_MS = 50L
        const val OLDER_TIMEOUT_MS = 15_000L
        private const val MAX_EARLY_ACTIONS = 1_000
    }
}
