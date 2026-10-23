package dev.ahpclient.state

import dev.ahpclient.ahp.AhpConnection
import dev.ahpclient.ahp.AhpException
import dev.ahpclient.ahp.ROOT_CHANNEL
import dev.ahpclient.ahp.ServerNotification
import dev.ahpclient.tunnel.DevTunnelsException
import dev.ahpclient.tunnel.UpgradeRejectedException
import com.microsoft.agenthostprotocol.generated.ActionEnvelope
import com.microsoft.agenthostprotocol.generated.AhpErrorCodes
import com.microsoft.agenthostprotocol.generated.Implementation
import com.microsoft.agenthostprotocol.generated.ReconnectResult
import com.microsoft.agenthostprotocol.generated.ReconnectResultReplay
import com.microsoft.agenthostprotocol.generated.ReconnectResultSnapshot
import com.microsoft.agenthostprotocol.generated.SessionSummary
import com.microsoft.agenthostprotocol.generated.Snapshot
import com.microsoft.agenthostprotocol.generated.SnapshotState
import com.microsoft.agenthostprotocol.generated.StateAction
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Opens a fresh AHP connection to one host. Implementations resolve a new connect token every time. */
fun interface HostConnector {
    suspend fun connect(clientId: String, initialServerSeq: Long, scope: CoroutineScope): AhpConnection
}

sealed interface ConnState {
    data object Stopped : ConnState

    data object Connecting : ConnState

    data object Online : ConnState

    /** Waiting to retry; [attempt] counts consecutive failures since the last good connection. */
    data class Reconnecting(val attempt: Int, val cause: Throwable?) : ConnState

    /** Retrying cannot help (expired sign-in, tunnel deleted, unsupported protocol); the caller must act. */
    data class Failed(val cause: Throwable) : ConnState
}

class Backoff(private val baseMs: Long = 1_000, private val maxMs: Long = 30_000) {
    /** 1 s, 2 s, 4 s ... capped at [maxMs]. */
    fun delayMs(attempt: Int): Long = (baseMs shl (attempt - 1).coerceIn(0, MAX_SHIFT)).coerceAtMost(maxMs)

    private companion object {
        const val MAX_SHIFT = 10
    }
}

/**
 * One host, kept live: connects, mirrors the root state and the session list, and keeps open sessions and chats subscribed,
 * reconnecting with backoff when the socket drops. It only calls what [AhpConnection] allows under the capability policy
 * the [HostConnector] chose; a READ connection cannot [dispatch] at all.
 *
 * Reconnecting resumes with the AHP `reconnect` command, which replays missed actions. Notifications are not
 * replayed, so the session list is fetched again every time; if the host no longer knows this client, a fresh
 * `initialize` takes over on the same connection.
 */
class HostSession(
    private val connector: HostConnector,
    private val scope: CoroutineScope,
    private val clientInfo: Implementation,
    private val backoff: Backoff = Backoff(),
    private val clientId: String = UUID.randomUUID().toString(),
) {
    val root = RootStore()

    private val _connection = MutableStateFlow<ConnState>(ConnState.Stopped)
    val connection: StateFlow<ConnState> = _connection.asStateFlow()

    private val _undecodableFrames = MutableStateFlow(0)

    /** Frames from the host that could not be decoded. Skipped, not fatal: unknown actions already decode as no-ops. */
    val undecodableFrames: StateFlow<Int> = _undecodableFrames.asStateFlow()

    private val sessions = LinkedHashMap<String, SessionStore>()
    private val chats = LinkedHashMap<String, ChatStore>()
    private val subscribeLock = Mutex()

    // Owned here, not by a connection, so it keeps increasing when a reconnect reuses this client id.
    private val nextClientSeq = AtomicLong(1)
    private val awaiting = ConcurrentHashMap<Long, CompletableDeferred<DispatchOutcome>>()

    @Volatile
    private var current: AhpConnection? = null
    private var lastSeq = 0L
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        _connection.value = ConnState.Connecting
        job = scope.launch { supervise() }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        current?.close()
        _connection.value = ConnState.Stopped
    }

    /** The store for [uri], subscribing now if online or on the next connection. Pair every call with [closeSession]. */
    fun openSession(uri: String): SessionStore {
        val store = synchronized(sessions) { sessions.getOrPut(uri) { SessionStore(uri) }.also { it.openers++ } }
        scope.launch {
            try {
                subscribeLock.withLock { subscribeIfNeeded(current ?: return@launch, store) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The connection dropped mid-subscribe; the next one subscribes because the store still needs it.
            }
        }
        return store
    }

    fun closeSession(uri: String) {
        val dropped = synchronized(sessions) {
            val store = sessions[uri] ?: return
            store.openers--
            if (store.openers > 0) false else sessions.remove(uri) != null
        }
        if (dropped) current?.unsubscribe(uri)
    }

    /** The store for the chat at [uri], subscribing now if online or on the next connection. Pair every call with [closeChat]. */
    fun openChat(uri: String): ChatStore {
        val store = synchronized(chats) { chats.getOrPut(uri) { ChatStore(uri, scope, fetchOlder = { cursor -> fetchTurns(uri, cursor) }) }.also { it.openers++ } }
        scope.launch {
            try {
                subscribeLock.withLock { subscribeIfNeeded(current ?: return@launch, store) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The connection dropped mid-subscribe; the next one subscribes because the store still needs it.
            }
        }
        return store
    }

    fun closeChat(uri: String) {
        val dropped = synchronized(chats) {
            val store = chats[uri] ?: return
            store.openers--
            if (store.openers > 0) false else chats.remove(uri) != null
        }
        if (dropped) current?.unsubscribe(uri)
    }

    private suspend fun fetchTurns(uri: String, cursor: String) {
        (current ?: throw IOException("Not connected to the host")).fetchTurns(uri, cursor)
    }

    /**
     * Sends [action] on [channel] and waits for the host to echo it back as ours. Nothing is applied locally: the chat
     * changes only when the accepted echo arrives, so what is shown is what the host holds.
     *
     * @throws dev.ahpclient.ahp.CapabilityDeniedException when this connection's tier does not allow the action.
     * @throws IOException when there is no connection. Nothing was sent in either case, so retrying is safe.
     */
    suspend fun dispatch(channel: String, action: StateAction, timeoutMs: Long = DISPATCH_TIMEOUT_MS): DispatchOutcome {
        val ahp = current ?: throw IOException("Not connected to the host")
        val clientSeq = nextClientSeq.getAndIncrement()
        val reply = CompletableDeferred<DispatchOutcome>()
        // Registered before sending: the echo can be read before dispatchAction returns.
        awaiting[clientSeq] = reply
        try {
            ahp.dispatchAction(channel, action, clientSeq)
            return withTimeoutOrNull(timeoutMs) { reply.await() } ?: DispatchOutcome.NoReply
        } finally {
            awaiting.remove(clientSeq)
        }
    }

    private suspend fun supervise() {
        var attempt = 0
        var resume = false
        var lastError: Throwable? = null
        while (true) {
            if (attempt == 0) _connection.value = ConnState.Connecting
            val parent = currentCoroutineContext()
            val connectionScope = CoroutineScope(parent + SupervisorJob(parent[Job]))
            var ahp: AhpConnection? = null
            try {
                ahp = connector.connect(clientId, lastSeq, connectionScope)
                attach(ahp, resume)
                current = ahp
                resume = true
                attempt = 0
                lastError = null
                _connection.value = ConnState.Online
                ahp.notifications.collect(::route)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isTerminal(e)) {
                    _connection.value = ConnState.Failed(e)
                    return
                }
                lastError = e
            } finally {
                current = null
                ahp?.let {
                    lastSeq = maxOf(lastSeq, it.lastServerSeq)
                    it.close()
                }
                // Whatever was sent and not yet answered can no longer be answered on this connection.
                awaiting.values.forEach { it.complete(DispatchOutcome.Interrupted) }
                connectionScope.cancel()
            }
            attempt++
            _connection.value = ConnState.Reconnecting(attempt, lastError)
            delay(backoff.delayMs(attempt))
        }
    }

    private fun isTerminal(e: Throwable): Boolean = when (e) {
        is DevTunnelsException.AuthExpired, is DevTunnelsException.Forbidden, is DevTunnelsException.TunnelNotFound,
        is AhpException.UnsupportedProtocolVersion,
        -> true
        is UpgradeRejectedException -> e.httpCode in TERMINAL_UPGRADE_CODES
        else -> false
    }

    private suspend fun attach(ahp: AhpConnection, resume: Boolean) {
        val open = openChannels()
        if (resume) {
            try {
                applyReconnect(ahp.reconnect(listOf(ROOT_CHANNEL) + open), open)
            } catch (e: AhpException.Rpc) {
                // The host does not know this client (for example it restarted): start over on the same socket.
                initialize(ahp, open)
            }
        } else {
            initialize(ahp, open)
        }
        refreshCatalog(ahp)
        subscribeLock.withLock {
            sessionsNeedingSubscribe().forEach { subscribeIfNeeded(ahp, it) }
            chatsNeedingSubscribe().forEach { subscribeIfNeeded(ahp, it) }
        }
    }

    private suspend fun initialize(ahp: AhpConnection, open: List<String>) {
        applySnapshots(ahp.initialize(clientInfo, listOf(ROOT_CHANNEL) + open).snapshots)
    }

    private fun applyReconnect(result: ReconnectResult, open: List<String>) {
        when (result) {
            is ReconnectResultReplay -> {
                result.value.actions.forEach { route(ServerNotification.Action(it)) }
                result.value.missing.forEach { uri ->
                    storeFor(uri)?.markGone()
                    chatFor(uri)?.markGone()
                }
            }
            is ReconnectResultSnapshot -> {
                applySnapshots(result.value.snapshots)
                val covered = result.value.snapshots.map { it.resource }.toSet()
                open.filter { it !in covered }.forEach {
                    storeFor(it)?.invalidate()
                    chatFor(it)?.invalidate()
                }
            }
        }
    }

    private fun applySnapshots(snapshots: List<Snapshot>) {
        snapshots.forEach { snapshot ->
            when (val state = snapshot.state) {
                is SnapshotState.Root -> root.seedRoot(state.value)
                is SnapshotState.Session -> storeFor(snapshot.resource)?.seed(snapshot)
                is SnapshotState.Chat -> chatFor(snapshot.resource)?.seed(snapshot)
                else -> Unit
            }
        }
    }

    private suspend fun refreshCatalog(ahp: AhpConnection) {
        val all = ArrayList<SessionSummary>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = ahp.listSessions(cursor = cursor)
            all += page.items
            cursor = page.nextCursor
        } while (cursor != null && ++pages < MAX_LIST_PAGES)
        root.replaceCatalog(all)
    }

    private fun sessionsNeedingSubscribe(): List<SessionStore> =
        synchronized(sessions) { sessions.values.filter { it.needsSubscribe } }

    private fun chatsNeedingSubscribe(): List<ChatStore> =
        synchronized(chats) { chats.values.filter { it.needsSubscribe } }

    private suspend fun subscribeIfNeeded(ahp: AhpConnection, store: ChatStore) {
        if (!store.needsSubscribe) return
        try {
            ahp.subscribe(store.uri).snapshot?.let(store::seed)
        } catch (e: AhpException.Rpc) {
            if (e.code == AhpErrorCodes.SESSION_NOT_FOUND || e.code == AhpErrorCodes.NOT_FOUND) store.markGone() else throw e
        }
    }

    private suspend fun subscribeIfNeeded(ahp: AhpConnection, store: SessionStore) {
        if (!store.needsSubscribe) return
        try {
            ahp.subscribe(store.uri).snapshot?.let(store::seed)
        } catch (e: AhpException.Rpc) {
            if (e.code == AhpErrorCodes.SESSION_NOT_FOUND) store.markGone() else throw e
        }
    }

    private fun route(note: ServerNotification) {
        when (note) {
            is ServerNotification.Action -> {
                val envelope = note.envelope
                // A rejected action changed nothing on the host, so it must change nothing here either.
                if (envelope.rejectionReason == null) {
                    if (envelope.channel == ROOT_CHANNEL) {
                        root.applyAction(envelope.action)
                    } else {
                        storeFor(envelope.channel)?.apply(envelope) ?: chatFor(envelope.channel)?.apply(envelope)
                    }
                }
                resolveDispatch(envelope)
            }
            is ServerNotification.Named -> root.applyNotification(note.method, note.params)
            is ServerNotification.Undecodable -> _undecodableFrames.update { it + 1 }
        }
    }

    /** Matches an echo of our own action to the [dispatch] waiting for it, after the state change it carries has been applied. */
    private fun resolveDispatch(envelope: ActionEnvelope) {
        val origin = envelope.origin ?: return
        if (origin.clientId != clientId) return
        val rejection = envelope.rejectionReason
        awaiting.remove(origin.clientSeq)?.complete(if (rejection == null) DispatchOutcome.Accepted else DispatchOutcome.Rejected(rejection))
    }

    private fun storeFor(uri: String): SessionStore? = synchronized(sessions) { sessions[uri] }

    private fun chatFor(uri: String): ChatStore? = synchronized(chats) { chats[uri] }

    private fun openChannels(): List<String> = synchronized(sessions) { sessions.keys.toList() } + synchronized(chats) { chats.keys.toList() }

    private companion object {
        const val MAX_LIST_PAGES = 50
        const val DISPATCH_TIMEOUT_MS = 15_000L
        val TERMINAL_UPGRADE_CODES = setOf(401, 403, 404)
    }
}
