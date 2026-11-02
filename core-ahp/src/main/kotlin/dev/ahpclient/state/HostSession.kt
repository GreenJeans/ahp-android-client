package dev.ahpclient.state

import dev.ahpclient.platform.platformSynchronized

import dev.ahpclient.platform.Synchronized

import kotlin.concurrent.Volatile

import dev.ahpclient.ahp.AhpConnection
import dev.ahpclient.ahp.AhpException
import dev.ahpclient.ahp.ROOT_CHANNEL
import dev.ahpclient.ahp.ServerNotification
import dev.ahpclient.security.SessionListCache
import dev.ahpclient.tunnel.DevTunnelsException
import dev.ahpclient.tunnel.UpgradeRejectedException
import com.microsoft.agenthostprotocol.generated.ActionEnvelope
import com.microsoft.agenthostprotocol.generated.AhpErrorCodes
import com.microsoft.agenthostprotocol.generated.ChangesetOperationTarget
import com.microsoft.agenthostprotocol.generated.ChangesetState
import com.microsoft.agenthostprotocol.generated.InvokeChangesetOperationResult
import com.microsoft.agenthostprotocol.generated.ChatSource
import com.microsoft.agenthostprotocol.generated.Implementation
import com.microsoft.agenthostprotocol.generated.Message
import com.microsoft.agenthostprotocol.generated.ReconnectResult
import com.microsoft.agenthostprotocol.generated.ReconnectResultReplay
import com.microsoft.agenthostprotocol.generated.ReconnectResultSnapshot
import com.microsoft.agenthostprotocol.generated.ResolveSessionConfigResult
import com.microsoft.agenthostprotocol.generated.ResourceReadResult
import com.microsoft.agenthostprotocol.generated.SessionSummary
import com.microsoft.agenthostprotocol.generated.Snapshot
import com.microsoft.agenthostprotocol.generated.SnapshotState
import com.microsoft.agenthostprotocol.generated.SubscribeView
import com.microsoft.agenthostprotocol.generated.TerminalState
import com.microsoft.agenthostprotocol.generated.StateAction
import com.microsoft.agenthostprotocol.changesetReducer
import com.microsoft.agenthostprotocol.terminalReducer
import dev.ahpclient.platform.IOException
import dev.ahpclient.platform.randomUuid
import dev.ahpclient.platform.ConcurrentHashMap
import dev.ahpclient.platform.AtomicLong
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement

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

    /** There is no usable network, so no connection is tried. Not a failure and not an attempt: it ends when a network returns. */
    data object WaitingForNetwork : ConnState

    /** Retrying cannot help (expired sign-in, tunnel deleted, unsupported protocol); the caller must act. */
    data class Failed(val cause: Throwable) : ConnState
}

class Backoff(
    private val baseMs: Long = 1_000,
    private val maxMs: Long = 30_000,
    private val jitter: Double = 0.2,
    private val random: Random = Random.Default,
) {
    /**
     * 1 s, 2 s, 4 s ... capped at [maxMs], each spread evenly by up to [jitter] either way so that clients which lost the host
     * together do not all come back together. Never more than [maxMs]; 0.0 for [jitter] gives the plain schedule.
     */
    fun delayMs(attempt: Int): Long {
        val base = (baseMs shl (attempt - 1).coerceIn(0, MAX_SHIFT)).coerceAtMost(maxMs)
        val spread = 1.0 + jitter * (random.nextDouble() * 2 - 1)
        return (base * spread).roundToLong().coerceAtMost(maxMs)
    }

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
 *
 * With no usable [network] it makes no attempts at all ([ConnState.WaitingForNetwork]) and connects at once when one
 * returns. A change of network ends the live connection and a pending backoff wait and starts the backoff schedule over,
 * since a socket made on the old network is dead whether or not it has noticed.
 *
 * Out of sight, only the root and the sessions need to stay subscribed for alerts: [park] lets go of the chats and channels and
 * [unpark] takes them up again.
 *
 * With a [cache] and a [cacheKey] (the host's tunnel id) the session list saved last time is shown, marked [RootStore.stale], until the
 * host's own arrives, and the list is saved again as it changes. A cache that cannot be read or written is ignored: it never gets in the
 * way of the connection. The saved list is never written from itself: nothing is saved before the host's own list has been taken.
 */
class HostSession(
    private val connector: HostConnector,
    private val scope: CoroutineScope,
    private val clientInfo: Implementation,
    private val backoff: Backoff = Backoff(),
    private val clientId: String = randomUuid(),
    private val network: StateFlow<NetworkState> = NetworkState.ALWAYS_AVAILABLE,
    cache: SessionListCache? = null,
    cacheKey: String? = null,
    private val note: (String) -> Unit = {},
) : ManagementCommands {
    val root = RootStore()

    private class CacheTarget(val cache: SessionListCache, val key: String)

    private val cacheTarget = if (cache != null && cacheKey != null) CacheTarget(cache, cacheKey) else null

    /** One save at a time, so a later list is never overwritten by an earlier one; held across the check and the write. */
    private val cacheLock = Mutex()

    // Guarded by cacheLock. What was last written, so an unchanged list is not written again.
    private var lastSaved: List<SessionSummary>? = null

    /** Set by [stopAndForget]: from then on nothing is written to the cache. */
    @Volatile
    private var cacheForgotten = false
    private var cacheJob: Job? = null

    private val _connection = MutableStateFlow<ConnState>(ConnState.Stopped)
    val connection: StateFlow<ConnState> = _connection.asStateFlow()

    private val _canInteract = MutableStateFlow(false)

    /** True only while the live connection's tier lets this client send actions. What the UI shows write controls from, not a setting. */
    val canInteract: StateFlow<Boolean> = _canInteract.asStateFlow()

    private val _canManage = MutableStateFlow(false)

    /** True only while the live connection's tier lets this client create and remove sessions and chats. Not a setting: it follows the connection. */
    val canManage: StateFlow<Boolean> = _canManage.asStateFlow()

    private val _undecodableFrames = MutableStateFlow(0)

    /** Frames from the host that could not be decoded. Skipped, not fatal: unknown actions already decode as no-ops. */
    val undecodableFrames: StateFlow<Int> = _undecodableFrames.asStateFlow()

    private val sessions = LinkedHashMap<String, SessionStore>()
    private val chats = LinkedHashMap<String, ChatStore>()
    private val aux = LinkedHashMap<String, AuxStore>()
    private val subscribeLock = Mutex()

    // Owned here, not by a connection, so it keeps increasing when a reconnect reuses this client id.
    private val nextClientSeq = AtomicLong(1)
    private val awaiting = ConcurrentHashMap<Long, Pending>()

    /** One sent action waiting for its echo, with what an echo that carries no `origin` must match to answer it. */
    private class Pending(val channel: String, val key: EchoKey, val reply: CompletableDeferred<DispatchOutcome>)

    // Counts restart requests, so a connection made before one is never used after it.
    private val epoch = AtomicLong(0)

    @Volatile
    private var current: AhpConnection? = null
    private var lastSeq = 0L
    private var job: Job? = null

    /** Whether the chats and channels are let go of (see [park]). Set at once; the work that follows reads it again under [subscribeLock]. */
    @Volatile
    internal var parked = false
        private set

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        _connection.value = ConnState.Connecting
        // Launched first, so a list from disk is in place before the first connection can take its first step.
        cacheJob = cacheTarget?.let { target -> scope.launch { seedFromCache(target); saveWhenChanged(target) } }
        job = scope.launch { supervise() }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        cacheJob?.cancel()
        cacheJob = null
        current?.close()
        _canInteract.value = false
        _canManage.value = false
        _connection.value = ConnState.Stopped
        saveCatalogSoon()
    }

    /**
     * Stops as [stop] does, but saves nothing, now or later, and waits for a save that is under way. For a sign-out: the caller then
     * wipes the cache knowing that nothing here can write it again.
     */
    suspend fun stopAndForget() {
        cacheForgotten = true
        stop()
        cacheLock.withLock { }
    }

    /**
     * Drops the live connection and connects again at once, asking the [HostConnector] for its tier afresh. Used when
     * what this client may do changes: a permission taken away must end the connection that held it, not wait for the
     * next drop. A connection that was still being made when this was called is discarded, not used.
     */
    fun restart() {
        epoch.incrementAndGet()
        _canInteract.value = false
        _canManage.value = false
        current?.close()
    }

    /**
     * Lets the host stop sending what nobody can see, for when the app is out of sight: every chat, changeset and terminal that
     * is subscribed gets an `unsubscribe` and is marked as needing a subscription again, and keeps showing what it has. Sessions
     * stay subscribed: they are small, and the open one feeds the chat's settings and the custom agents. A chat held open in the
     * background (see [openChat]) is left as it is.
     *
     * While parked, a connection names only the root and the sessions to the host and subscribes no chat or channel that nothing
     * holds in the background; opening one with [openChat], [openChangeset] or [openTerminal] still subscribes it at once.
     * Safe to repeat and to call from any thread: the flag is set here and read again before the work is done, so the last call wins.
     */
    fun park() {
        parked = true
        scope.launch {
            subscribeLock.withLock {
                if (!parked) return@withLock
                platformSynchronized(chats) { chats.values.toList() }.forEach(::parkChat)
                platformSynchronized(aux) { aux.values.toList() }.forEach(::parkChannel)
            }
        }
    }

    /**
     * Undoes [park]: subscribes again whatever it let go of, and the new snapshots replace what the chats and channels were
     * showing. With no connection there is nothing to do yet: the next one subscribes as usual, since the flag is clear.
     */
    fun unpark() {
        parked = false
        scope.launch {
            try {
                subscribeLock.withLock {
                    if (parked) return@withLock
                    val ahp = current ?: return@withLock
                    restoreChats()
                    auxNeedingSubscribe().forEach { subscribeIfNeeded(ahp, it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The connection dropped mid-subscribe; the next one subscribes because the store still needs it.
            }
        }
    }

    /** Lets go of [store] if it is subscribed and nothing holds it in the background. Called under [subscribeLock], and only does anything while parked. */
    private fun parkChat(store: ChatStore) {
        if (!parked || store.needsSubscribe) return
        if (platformSynchronized(chats) { chats[store.uri] !== store || store.backgroundOpeners > 0 }) return
        store.invalidate()
        current?.unsubscribe(store.uri)
    }

    /**
     * Lets go of [store] if it is subscribed. With no live connection it does nothing: a connection that is still being made may
     * already hold the channel for the host (a changeset is named in `reconnect`), and [settleChannels] lets go of it once it is live.
     */
    private fun parkChannel(store: AuxStore) {
        val ahp = current ?: return
        if (!parked || store.needsSubscribe) return
        store.invalidate()
        ahp.unsubscribe(store.uri)
    }

    /**
     * Brings the changesets and terminals in line with [parked] on the connection that has just gone live. A [park] or [unpark] that came
     * while it was being made found nothing to act on, yet its handshake had already named the changesets to the host (or left them out),
     * so what is held there is let go of now, or subscribed, whichever the flag asks for.
     */
    private fun settleChannels() {
        scope.launch {
            try {
                subscribeLock.withLock {
                    val ahp = current ?: return@withLock
                    if (parked) {
                        platformSynchronized(aux) { aux.values.toList() }.forEach(::parkChannel)
                    } else {
                        auxNeedingSubscribe().forEach { subscribeIfNeeded(ahp, it) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The connection dropped mid-subscribe; the next one subscribes because the store still needs it.
            }
        }
    }

    /** The store for [uri], subscribing now if online or on the next connection. Pair every call with [closeSession]. */
    fun openSession(uri: String): SessionStore {
        val store = platformSynchronized(sessions) { sessions.getOrPut(uri) { SessionStore(uri) }.also { it.openers++ } }
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
        val dropped = platformSynchronized(sessions) {
            val store = sessions[uri] ?: return
            store.openers--
            if (store.openers > 0) false else sessions.remove(uri) != null
        }
        if (dropped) current?.unsubscribe(uri)
    }

    /**
     * The store for the chat at [uri], subscribing now if online or on the next connection, even while parked. Pair every call
     * with [closeChat] using the same [background]. A chat opened in the [background], by a job that runs while the app is out
     * of sight (a decision from a notification), stays subscribed through [park]; a screen's does not.
     */
    fun openChat(uri: String, background: Boolean = false): ChatStore {
        val store = platformSynchronized(chats) {
            chats.getOrPut(uri) {
                ChatStore(uri, scope, fetchOlder = { cursor -> fetchTurns(uri, cursor) }, subscribeAgain = { chatFor(uri)?.let(::subscribeNow) })
            }.also {
                it.openers++
                if (background) it.backgroundOpeners++
            }
        }
        subscribeNow(store)
        return store
    }

    /** Subscribes [store] if it needs it. [restoring] marks a subscription brought back on its own, which a parked session leaves to [unpark]; an open that asks for the chat always subscribes. */
    private fun subscribeNow(store: ChatStore, restoring: Boolean = false) {
        scope.launch {
            try {
                subscribeLock.withLock {
                    if (restoring && stayingParked(store)) return@launch
                    subscribeIfNeeded(current ?: return@launch, store)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The connection dropped mid-subscribe; the next one subscribes because the store still needs it.
            }
        }
    }

    /**
     * Gives up one opener of the chat, with the same [background] it was opened with. When the last background opener of a chat that
     * a screen still holds goes while parked, the chat is let go of again, as [park] would have.
     */
    fun closeChat(uri: String, background: Boolean = false) {
        var dropped = false
        var letGo: ChatStore? = null
        platformSynchronized(chats) {
            val store = chats[uri] ?: return
            store.openers--
            if (background) store.backgroundOpeners--
            if (store.openers <= 0) {
                dropped = chats.remove(uri) != null
            } else if (background && parked && store.backgroundOpeners == 0) {
                letGo = store
            }
        }
        if (dropped) current?.unsubscribe(uri)
        letGo?.let { store -> scope.launch { subscribeLock.withLock { parkChat(store) } } }
    }

    /** The store for the changeset at [uri], read only, subscribing now if online or on the next connection. Pair every call with [closeChannel]. */
    fun openChangeset(uri: String): ChannelStore<ChangesetState> = openAux(uri) {
        ChannelStore(uri, { (it as? SnapshotState.Changeset)?.value }, ::changesetReducer)
    }

    /**
     * The store for the terminal at [uri], read only. A terminal is subscribed by hand (see [AhpConnection.subscribeTerminal])
     * and left out of `initialize` and `reconnect`, so it is subscribed afresh on every connection. Pair every call with [closeChannel].
     * Only an address of a terminal is accepted: anything else is refused here, not subscribed.
     */
    fun openTerminal(uri: String): ChannelStore<TerminalState> {
        require(TerminalLinks.isTerminalUri(uri)) { "Not a terminal address" }
        return openAux(uri) { ChannelStore(uri, { (it as? SnapshotState.Terminal)?.value }, ::terminalReducer, byHand = true) }
    }

    fun closeChannel(uri: String) {
        val dropped = platformSynchronized(aux) {
            val store = aux[uri] ?: return
            store.openers--
            if (store.openers > 0) false else aux.remove(uri) != null
        }
        if (dropped) current?.unsubscribe(uri)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <S : Any> openAux(uri: String, create: () -> ChannelStore<S>): ChannelStore<S> {
        val store = platformSynchronized(aux) { aux.getOrPut(uri) { create() }.also { it.openers++ } } as ChannelStore<S>
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
        awaiting[clientSeq] = Pending(channel, EchoKey.of(action), reply)
        try {
            ahp.dispatchAction(channel, action, clientSeq)
            return withTimeoutOrNull(timeoutMs) { reply.await() } ?: DispatchOutcome.NoReply
        } finally {
            awaiting.remove(clientSeq)
        }
    }

    private fun live(): AhpConnection = current ?: throw IOException("Not connected to the host")

    /**
     * Reads the content a changed file's side points at. Internal on purpose: only code in this module, which hands it an
     * address taken from the open changeset's own state, may ask the host to read anything.
     */
    suspend fun readResource(uri: String): ResourceReadResult = live().resourceRead(uri)

    /**
     * The management commands, sent on the live connection. Each waits for the host's answer to the request.
     *
     * @throws dev.ahpclient.ahp.CapabilityDeniedException when this connection's tier does not allow it.
     * @throws IOException when there is no connection. Nothing was sent in either case.
     */
    override suspend fun createSession(uri: String, provider: String?, workingDirectories: List<String>?, config: Map<String, JsonElement>?) =
        live().createSession(uri, provider, workingDirectories, config)

    override suspend fun resolveSessionConfig(provider: String, workingDirectory: String?, config: Map<String, JsonElement>?): ResolveSessionConfigResult =
        live().resolveSessionConfig(provider, workingDirectory, config)

    override suspend fun createChat(sessionUri: String, chatUri: String, source: ChatSource?, initialMessage: Message?) =
        live().createChat(sessionUri, chatUri, source, initialMessage)

    override suspend fun disposeSession(uri: String) = live().disposeSession(uri)

    override suspend fun disposeChat(uri: String) = live().disposeChat(uri)

    /** Invokes [operationId] on the changeset at [changesetUri]. */
    suspend fun invokeChangesetOperation(
        changesetUri: String,
        operationId: String,
        target: ChangesetOperationTarget? = null,
    ): InvokeChangesetOperationResult =
        live().invokeChangesetOperation(changesetUri, operationId, target)

    /** Shows the list saved last time, marked stale, unless the host's own has already arrived. */
    private suspend fun seedFromCache(target: CacheTarget) {
        val saved = try {
            target.cache.load(target.key)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (!saved.isNullOrEmpty()) root.seedStale(saved)
    }

    /**
     * Saves the list [CACHE_SAVE_DEBOUNCE_MS] after it last changed. Not conditional on the connection being online: the host's list
     * arrives before the connection is declared online, and a slow subscription behind it must not let the one chance to save go by.
     * [saveCatalog] still refuses a list that is only the seed.
     */
    private suspend fun saveWhenChanged(target: CacheTarget) {
        root.sessions.collectLatest {
            delay(CACHE_SAVE_DEBOUNCE_MS)
            saveCatalog(target)
        }
    }

    /** Saves the list now, for when the connection ends and the session stops, which the debounce might not live to see. */
    private fun saveCatalogSoon() {
        val target = cacheTarget ?: return
        scope.launch { saveCatalog(target) }
    }

    /**
     * Writes the list to the cache if it is the host's own and differs from what was last written. A list that is only the seed from
     * disk is never written back, and a failure to write is not reported: the cache is a convenience. Not cancellable once it starts,
     * so a newer change cannot leave it half done.
     */
    private suspend fun saveCatalog(target: CacheTarget) = withContext(NonCancellable) {
        cacheLock.withLock {
            if (cacheForgotten || !root.hasLiveCatalog) return@withLock
            val items = root.sessions.value
            if (items == lastSaved) return@withLock
            try {
                target.cache.save(target.key, items)
                lastSaved = items
            } catch (_: Exception) {
                // Neither a full disk nor a lost key is a reason to disturb the connection.
            }
        }
    }

    private suspend fun supervise() {
        var attempt = 0
        var resume = false
        var lastError: Throwable? = null
        while (true) {
            if (network.value is NetworkState.Unavailable) {
                _connection.value = ConnState.WaitingForNetwork
                network.first { it is NetworkState.Available }
                // A network that has just returned deserves a fresh schedule, not the tail of the one that ran on the last.
                attempt = 0
            }
            if (attempt == 0) _connection.value = ConnState.Connecting
            val generation = epoch.get()
            // Taken before connecting, so a change while the connection is being made still counts against it.
            val usedNetwork = network.value
            val parent = currentCoroutineContext()
            val connectionScope = CoroutineScope(parent + SupervisorJob(parent[Job]))
            var ahp: AhpConnection? = null
            var routing = false
            try {
                ahp = connector.connect(clientId, lastSeq, connectionScope)
                attach(ahp, resume)
                resume = true
                // A restart asked for while this was being made means it was made under the old rules: drop it and go again.
                if (epoch.get() != generation) continue
                current = ahp
                attempt = 0
                lastError = null
                _canInteract.value = ahp.canDispatch
                _canManage.value = ahp.canManage
                _connection.value = ConnState.Online
                restoreChats()
                settleChannels()
                routing = true
                routeWhileOnNetwork(ahp, usedNetwork)
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
                _canInteract.value = false
                _canManage.value = false
                ahp?.let {
                    // The connection counts every action it queued, but until the collector runs none is applied: a connection that ended
                    // before then (an overload while attaching, say) must not move the resume point past actions nobody has seen.
                    if (routing) lastSeq = maxOf(lastSeq, it.lastServerSeq)
                    it.close()
                }
                // Whatever was sent and not yet answered can no longer be answered on this connection.
                awaiting.values.forEach { it.reply.complete(DispatchOutcome.Interrupted) }
                connectionScope.cancel()
                saveCatalogSoon()
            }
            if (epoch.get() != generation) continue
            val now = network.value
            // No network is neither a failure nor an attempt: the top of the loop waits for one.
            if (now is NetworkState.Unavailable) continue
            if (now != usedNetwork) {
                // The connection was tried on a network that has since gone, so its failure says nothing about this one.
                attempt = 0
                continue
            }
            attempt++
            _connection.value = ConnState.Reconnecting(attempt, lastError)
            // A different network ends the wait early and starts the schedule again: the delay was for the one that failed.
            val changed = withTimeoutOrNull(backoff.delayMs(attempt)) { network.first { it != now } }
            if (changed != null) attempt = 0
        }
    }

    /**
     * Routes what [ahp] receives until it ends, and ends it if the network is no longer the one it was made on ([usedNetwork]):
     * a socket on a network that has gone can sit silent for a minute before its keep-alive notices. This is not a [restart]:
     * the tier has not changed, so the next connection resumes with `reconnect` rather than starting over.
     */
    private suspend fun routeWhileOnNetwork(ahp: AhpConnection, usedNetwork: NetworkState) {
        coroutineScope {
            val watcher = launch {
                network.first { it != usedNetwork }
                ahp.close()
            }
            ahp.notifications.collect(::route)
            watcher.cancel()
        }
    }

    private fun isTerminal(e: Throwable): Boolean = when (e) {
        is DevTunnelsException.AuthExpired, is DevTunnelsException.Forbidden, is DevTunnelsException.TunnelNotFound,
        is AhpException.UnsupportedProtocolVersion, is DirectHostException,
        -> true
        is UpgradeRejectedException -> e.httpCode in TERMINAL_UPGRADE_CODES
        else -> false
    }

    private suspend fun attach(ahp: AhpConnection, resume: Boolean) {
        val open = openChannels()
        // A terminal is not named to the host here (its snapshot cannot be decoded in these replies) and a subscription belongs to
        // one connection, so each is subscribed afresh below. So is any other channel left out because the session is parked.
        platformSynchronized(aux) { aux.values.filter { it.uri !in open } }.forEach { it.invalidate() }
        // A chat is not named here either, so the host cannot answer with all of its history in `initialize` or `reconnect`: it is
        // subscribed afresh below, asking for only its latest turns (see [CHAT_SNAPSHOT_TURNS]). What is shown stays until it arrives.
        platformSynchronized(chats) { chats.values.toList() }.forEach { it.invalidate() }
        if (resume) {
            try {
                applyReconnect(ahp.reconnect(listOf(ROOT_CHANNEL) + open), open, ahp)
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
            if (!parked) auxNeedingSubscribe().forEach { subscribeIfNeeded(ahp, it) }
        }
        // The chats are not subscribed here: a long one is slow to arrive, and the connection is not made to wait for it. They are
        // asked for once it is online, so the sessions stay usable while a chat loads, and one slow chat cannot hold the rest hostage.
    }

    private suspend fun initialize(ahp: AhpConnection, open: List<String>) {
        applySnapshots(ahp.initialize(clientInfo, listOf(ROOT_CHANNEL) + open).snapshots, ahp)
    }

    private fun applyReconnect(result: ReconnectResult, open: List<String>, ahp: AhpConnection) {
        when (result) {
            is ReconnectResultReplay -> {
                result.value.actions.forEach { route(ServerNotification.Action(it)) }
                result.value.missing.forEach { uri ->
                    storeFor(uri)?.markGone()
                    chatFor(uri)?.markGone()
                    auxFor(uri)?.markGone()
                }
            }
            is ReconnectResultSnapshot -> {
                applySnapshots(result.value.snapshots, ahp)
                val covered = result.value.snapshots.map { it.resource }.toSet()
                open.filter { it !in covered }.forEach {
                    storeFor(it)?.invalidate()
                    chatFor(it)?.invalidate()
                    auxFor(it)?.invalidate()
                }
            }
        }
    }

    private fun applySnapshots(snapshots: List<Snapshot>, ahp: AhpConnection) {
        snapshots.forEach { snapshot ->
            when (val state = snapshot.state) {
                is SnapshotState.Root -> root.seedRoot(state.value)
                is SnapshotState.Session -> storeFor(snapshot.resource)?.seed(snapshot, ahp.configFacts(snapshot.resource))
                is SnapshotState.Chat -> chatFor(snapshot.resource)?.seed(snapshot, ahp.configFacts(snapshot.resource))
                else -> auxFor(snapshot.resource)?.seed(snapshot)
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
        platformSynchronized(sessions) { sessions.values.filter { it.needsSubscribe } }

    private fun chatsNeedingSubscribe(): List<ChatStore> =
        platformSynchronized(chats) { chats.values.filter { it.needsSubscribe && !stayingParked(it) } }

    /** Subscribes the chats that need it, as a new connection does once online and [unpark] does. While parked, that is only the ones held in the background. */
    private fun restoreChats() {
        chatsNeedingSubscribe().forEach { subscribeNow(it, restoring = true) }
    }

    /** True for a chat that parking has let go of and nothing holds in the background: no connection subscribes it until [unpark]. */
    private fun stayingParked(store: ChatStore): Boolean = parked && platformSynchronized(chats) { store.backgroundOpeners == 0 }

    private fun auxNeedingSubscribe(): List<AuxStore> =
        platformSynchronized(aux) { aux.values.filter { it.needsSubscribe } }

    private suspend fun subscribeIfNeeded(ahp: AhpConnection, store: AuxStore) {
        if (!store.needsSubscribe) return
        try {
            val snapshot = if (store.byHand) ahp.subscribeTerminal(store.uri) else ahp.subscribe(store.uri).snapshot
            snapshot?.let(store::seed)
        } catch (e: AhpException.Rpc) {
            if (e.code == AhpErrorCodes.SESSION_NOT_FOUND || e.code == AhpErrorCodes.NOT_FOUND) store.markGone() else throw e
        } catch (e: AhpException.Protocol) {
            // The host answered, but not in a shape this app can read: say so, rather than wait for ever or fail the connection.
            store.markFailed(e)
        }
    }

    private suspend fun subscribeIfNeeded(ahp: AhpConnection, store: ChatStore) {
        if (!store.needsSubscribe) return
        try {
            ahp.subscribe(store.uri, SubscribeView(turns = CHAT_SNAPSHOT_TURNS)).snapshot?.let { store.seed(it, ahp.configFacts(store.uri)) }
        } catch (e: AhpException.Rpc) {
            if (e.code == AhpErrorCodes.SESSION_NOT_FOUND || e.code == AhpErrorCodes.NOT_FOUND) store.markGone() else throw e
        } catch (_: AhpException.Timeout) {
            // The connection is fine and the host is slow with this chat: say so, instead of tearing down a good connection to ask again.
            // The host is told to stop, or it goes on building a snapshot nobody is waiting for.
            ahp.unsubscribe(store.uri)
            store.markNotLoaded(interrupted = false)
        } catch (e: AhpException.ConnectionClosed) {
            // Lost while waiting for this chat. Once it has happened a few times in a row the chat is the problem, not the network.
            if (store.noteInterrupted()) store.markNotLoaded(interrupted = true)
            throw e
        }
    }

    private suspend fun subscribeIfNeeded(ahp: AhpConnection, store: SessionStore) {
        if (!store.needsSubscribe) return
        try {
            ahp.subscribe(store.uri).snapshot?.let { store.seed(it, ahp.configFacts(store.uri)) }
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
                        storeFor(envelope.channel)?.apply(envelope) ?: chatFor(envelope.channel)?.apply(envelope) ?: auxFor(envelope.channel)?.apply(envelope)
                    }
                }
                resolveDispatch(envelope)
            }
            is ServerNotification.Named -> root.applyNotification(note.method, note.params)
            is ServerNotification.Undecodable -> _undecodableFrames.update { it + 1 }
        }
    }

    /**
     * Matches an echo of our own action to the [dispatch] waiting for it, after the state change it carries has been applied.
     *
     * An echo with no `origin` is matched by what it carries instead: a host may re-dispatch a turn without `origin` (for example, `ahpd` 0.14.0 when waking a sleeping session, fixed in `softov/ahpd#161`),
     * for a message, because the turn starts after the dispatch that asked for it has returned. Such an echo answers the oldest
     * waiting dispatch with the same [EchoKey] (on the same channel, except a turn, whose id alone names it); one that matches
     * none is only someone else's action. An echo naming another client is never ours.
     */
    private fun resolveDispatch(envelope: ActionEnvelope) {
        val rejection = envelope.rejectionReason
        val outcome = if (rejection == null) DispatchOutcome.Accepted else DispatchOutcome.Rejected(rejection)
        val origin = envelope.origin
        if (origin != null) {
            if (origin.clientId == clientId) awaiting.remove(origin.clientSeq)?.reply?.complete(outcome)
            return
        }
        val key = EchoKey.of(envelope.action)
        val seq = awaiting.entries
            .filter { (_, p) -> p.key.matches(key) && (key.anyChannel || p.channel == envelope.channel) }
            .minOfOrNull { it.key } ?: return
        if (awaiting.remove(seq)?.reply?.complete(outcome) == true) {
            note("dispatch answered by echo without origin (seq $seq)")
        }
    }

    private fun storeFor(uri: String): SessionStore? = platformSynchronized(sessions) { sessions[uri] }

    private fun chatFor(uri: String): ChatStore? = platformSynchronized(chats) { chats[uri] }

    private fun auxFor(uri: String): AuxStore? = platformSynchronized(aux) { aux[uri] }

    /** The channels named to the host in `initialize` and `reconnect`: every session, and unless parked, every changeset. */
    private fun openChannels(): List<String> {
        val channels = platformSynchronized(aux) { if (parked) emptyList() else aux.values.filter { !it.byHand }.map { it.uri } }
        return platformSynchronized(sessions) { sessions.keys.toList() } + channels
    }

    private companion object {
        const val MAX_LIST_PAGES = 50
        const val DISPATCH_TIMEOUT_MS = 15_000L

        /** How long the session list must stay as it is before it is written to the cache. */
        const val CACHE_SAVE_DEBOUNCE_MS = 5_000L
        val TERMINAL_UPGRADE_CODES = setOf(401, 403, 404)

        /**
         * How many of a chat's latest finished turns the host is asked for in its snapshot. The host sends all of a chat's history
         * in one frame unless told otherwise, and a long chat (with screenshots in its tool results) is tens of megabytes: more than
         * a phone link delivers before the keep-alive gives up on the socket. Older turns are paged in as the person scrolls.
         */
        const val CHAT_SNAPSHOT_TURNS = 10L
    }
}
