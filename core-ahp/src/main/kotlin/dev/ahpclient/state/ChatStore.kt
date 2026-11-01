package dev.ahpclient.state

import dev.ahpclient.ahp.ChatConfigKeys
import dev.ahpclient.ahp.ConfigFacts
import com.microsoft.agenthostprotocol.chatReducer
import com.microsoft.agenthostprotocol.generated.ActionEnvelope
import com.microsoft.agenthostprotocol.generated.ChatState
import com.microsoft.agenthostprotocol.generated.Snapshot
import com.microsoft.agenthostprotocol.generated.SnapshotState
import com.microsoft.agenthostprotocol.generated.StateActionChatTurnsLoaded
import com.microsoft.agenthostprotocol.generated.StateActionUnknown
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** The host reported that this chat no longer exists. */
class ChatGoneException(val uri: String) : Exception("Chat $uri is gone")

/**
 * This chat was not loaded, and the app stopped trying on its own: the host was too slow to send it
 * ([interrupted] false: no answer within the request timeout on a connection that stayed up), or the connection was lost while
 * it was loading, over and over ([interrupted] true). A long chat is the usual reason. [ChatStore.retry] tries again.
 */
class ChatNotLoadedException(val uri: String, val interrupted: Boolean) : Exception("Chat $uri was not loaded (interrupted=$interrupted)")

/**
 * One subscribed chat: a snapshot plus the ordered actions after it, folded with the generated `chatReducer`.
 * Ordering and duplicate handling follow [SessionStore]: actions before the snapshot are held, and anything at or below
 * the last applied `serverSeq` is dropped. A chat that was let go of and is being subscribed again keeps its old state, but
 * holds actions the same way until the new snapshot arrives, so that none is lost between the request and the reply.
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
    /** Asks the connection to subscribe to this chat again, for [retry]. */
    private val subscribeAgain: () -> Unit = {},
) {
    private val _state = MutableStateFlow<ChatState?>(null)

    /** Null until the snapshot arrives. */
    val state: StateFlow<ChatState?> = _state.asStateFlow()

    private val _error = MutableStateFlow<Throwable?>(null)
    val error: StateFlow<Throwable?> = _error.asStateFlow()

    private val _ownConfig = MutableStateFlow<Map<String, JsonElement>>(emptyMap())

    /**
     * The settings this chat has set for itself (its mode, its approvals), from the snapshot and then from `chat/configChanged` actions.
     * The 1.0.0 library has no field for them, so they are read from the raw reply (see [ConfigFacts]). What a chat shows is these over
     * the session's own values. Only the keys in [ChatConfigKeys.READ] are kept.
     */
    val ownConfig: StateFlow<Map<String, JsonElement>> = _ownConfig.asStateFlow()

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

    private val _needsSubscribe = MutableStateFlow(true)

    internal var needsSubscribe: Boolean
        get() = _needsSubscribe.value
        private set(value) {
            _needsSubscribe.value = value
        }

    /**
     * [needsSubscribe] as a flow: true until a snapshot arrives, and again once the chat has been let go of. While it is true what
     * [state] shows may be old, so a caller that acts on the chat waits for it to turn false (`AppController.decideToolCall` does).
     */
    internal val subscribeNeeded: StateFlow<Boolean> = _needsSubscribe.asStateFlow()

    /** Counts openers; the host connection drops the subscription at zero. */
    internal var openers = 0

    /** How many of [openers] hold this chat for a job running out of sight (see [HostSession.park]), not for a screen. */
    internal var backgroundOpeners = 0

    /** Connections lost, one after another, while this chat's subscription was waiting for its snapshot. A snapshot clears it. */
    private var interrupted = 0

    /** @return false when [snapshot] holds no chat state, so nothing was seeded. */
    internal fun seed(snapshot: Snapshot, facts: ConfigFacts? = null): Boolean {
        val chat = (snapshot.state as? SnapshotState.Chat)?.value ?: return false
        synchronized(lock) {
            latest = chat
            _error.value = null
            interrupted = 0
            lastApplied = snapshot.fromSeq
            // The snapshot's own values come first: the held actions are later than it and are applied over them.
            facts?.chatValues?.let { _ownConfig.value = it }
            val held = early.sortedBy { it.serverSeq }
            early.clear()
            held.forEach(::reduce)
            publishNow()
            // Last, so that whoever waits on [subscribeNeeded] finds the new snapshot in [state] the moment it turns false.
            needsSubscribe = false
            olderArrived?.complete(Unit)
        }
        return true
    }

    internal fun apply(envelope: ActionEnvelope) {
        synchronized(lock) {
            if (latest == null || needsSubscribe) {
                if (early.size < MAX_EARLY_ACTIONS) early += envelope
                return
            }
            reduce(envelope)
            if (cooling) dirty = true else publishNow()
        }
    }

    /**
     * The old state stays visible, but the next connection subscribes again to get a fresh snapshot. A chat the app has given up
     * on (see [markNotLoaded]) stays given up until [retry], whatever connection comes next: that is what stops a reconnect loop.
     */
    internal fun invalidate() {
        synchronized(lock) {
            if (_error.value is ChatNotLoadedException) return
            needsSubscribe = true
        }
    }

    internal fun markGone() {
        synchronized(lock) {
            needsSubscribe = false
            _error.value = ChatGoneException(uri)
            olderArrived?.complete(Unit)
        }
    }

    /**
     * Gives up loading this chat for now: [error] says why and nothing subscribes it again until [retry]. Any state already
     * shown stays, but it is no longer being kept up to date.
     */
    internal fun markNotLoaded(interrupted: Boolean) {
        synchronized(lock) {
            needsSubscribe = false
            _error.value = ChatNotLoadedException(uri, interrupted)
            olderArrived?.complete(Unit)
        }
    }

    /** Notes a connection lost while waiting for the snapshot. @return true once that has happened [MAX_INTERRUPTED] times in a row. */
    internal fun noteInterrupted(): Boolean = synchronized(lock) { ++interrupted >= MAX_INTERRUPTED }

    /** Clears the error and asks the connection to subscribe again. For the button that follows [ChatNotLoadedException]. */
    fun retry() {
        synchronized(lock) {
            if (_error.value is ChatGoneException) return
            _error.value = null
            interrupted = 0
            needsSubscribe = true
        }
        subscribeAgain()
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
        if (envelope.action is StateActionChatTurnsLoaded) {
            if ((envelope.action as StateActionChatTurnsLoaded).value.turns.isEmpty()) latest = latest?.copy(turnsNextCursor = null)
            olderArrived?.complete(Unit)
        }
        (envelope.action as? StateActionUnknown)?.let { noteConfig(it.raw) }
    }

    /** A `chat/configChanged` the library cannot decode: merges (or, with `replace`, swaps) the chat's own values. A null value takes a setting away. */
    private fun noteConfig(raw: JsonObject) {
        if ((raw["type"] as? JsonPrimitive)?.contentOrNull != "chat/configChanged") return
        val sent = (raw["config"] as? JsonObject)?.filterKeys { it in ChatConfigKeys.READ } ?: return
        val replace = ((raw["replace"] as? JsonPrimitive)?.takeIf { !it.isString })?.booleanOrNull == true
        val base = if (replace) emptyMap() else _ownConfig.value
        _ownConfig.value = (base + sent).filterValues { it !is JsonNull }
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

        /** Connections lost in a row while waiting for this chat's snapshot, after which the app stops asking on its own. */
        const val MAX_INTERRUPTED = 3
        private const val MAX_EARLY_ACTIONS = 1_000
    }
}
