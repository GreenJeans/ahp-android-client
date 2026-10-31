package dev.ahpclient.app

import dev.ahpclient.auth.DeviceFlowResult
import dev.ahpclient.auth.GitHubAuthException
import dev.ahpclient.auth.GitHubDeviceAuth
import dev.ahpclient.auth.GitHubToken
import dev.ahpclient.auth.RefreshResult
import dev.ahpclient.auth.Secret
import dev.ahpclient.direct.DirectHost
import dev.ahpclient.direct.DirectHostCodec
import dev.ahpclient.direct.DirectHostStore
import dev.ahpclient.direct.HostAddress
import dev.ahpclient.direct.HostAddressCheck
import dev.ahpclient.direct.InMemoryDirectHostStore
import dev.ahpclient.security.DirectHostVault
import dev.ahpclient.security.SessionListCache
import dev.ahpclient.security.TokenVault
import dev.ahpclient.security.VaultCryptoException
import dev.ahpclient.security.VaultLoad
import dev.ahpclient.state.Backoff
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.CustomAgents
import dev.ahpclient.state.ApprovalGate
import dev.ahpclient.state.ChatConfig
import dev.ahpclient.state.RequestRead
import dev.ahpclient.state.ApprovalTarget
import dev.ahpclient.state.QuestionTarget
import dev.ahpclient.state.ChannelStore
import dev.ahpclient.state.ChangesetDiffs
import dev.ahpclient.state.ChangesetEntry
import dev.ahpclient.state.ChangesetReviewer
import dev.ahpclient.state.ChatInteractor
import dev.ahpclient.state.ChatStore
import dev.ahpclient.state.ExpansionState
import dev.ahpclient.state.HostSession
import dev.ahpclient.state.InteractResult
import dev.ahpclient.state.ManageResult
import dev.ahpclient.state.ManagementCommands
import dev.ahpclient.state.ModelOption
import dev.ahpclient.state.ModelOptions
import dev.ahpclient.state.SessionManager
import dev.ahpclient.state.SubagentLinks
import dev.ahpclient.state.TerminalLinks
import dev.ahpclient.state.displayPath
import dev.ahpclient.state.extras
import dev.ahpclient.tunnel.DevTunnelsClient
import dev.ahpclient.tunnel.DevTunnelsException
import dev.ahpclient.tunnel.TunnelInfo
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement

/**
 * Everything between launching the app and looking at a host: restore the saved sign-in, sign in with GitHub, list the
 * user's hosts, connect to one. Screens render [state] and call the methods; nothing else decides what is shown.
 *
 * Rules worth knowing:
 * - A sign-in GitHub or the Dev Tunnels service no longer accepts (401) wipes the saved token and goes back to the
 *   sign-in screen with a notice, from any point, including mid-session.
 * - The token lives in memory only while signed in, and in the [TokenVault] at rest. It never appears in [state].
 * - Failures are shown as messages here, never thrown to the caller, because the callers are UI event handlers.
 * - A saved sign-in restored at process start connects to the last host by itself, once, if [autoConnect] allows and that host is
 *   online. Going back to the host list, refreshing it or signing in afresh never does.
 */
class AppController(
    private val auth: GitHubDeviceAuth,
    private val vault: TokenVault,
    private val devTunnels: DevTunnelsClient,
    private val lastHost: LastHostStore,
    private val sessions: HostSessionFactory,
    private val scope: CoroutineScope,
    private val now: () -> Instant = Instant::now,
    private val interaction: InteractionConsent = InteractionConsent(),
    /** The device check in front of every tool approval. The default refuses everything, so a build with no gate can never approve. */
    private val approvalGate: ApprovalGate = ApprovalGate { false },
    /** How close to running out a sign-in is renewed. */
    private val renewBefore: Duration = Duration.ofMinutes(5),
    /** Where renewals are noted, for a debug build's log: only what happened, never a token. */
    private val note: (String) -> Unit = {},
    /** How long the app must be out of sight before the live session lets go of its chats and channels (see [HostSession.park]). */
    private val parkGraceMs: Long = ParkScheduler.DEFAULT_GRACE_MS,
    /**
     * Whether the app may connect to the last host by itself: after the process was restarted, and for a notification button when no
     * connection is live. On Android it is the Monitoring switch. It never signs in and never changes what the app is allowed to do.
     */
    private val autoConnect: suspend () -> Boolean = { false },
    /**
     * Runs after the user's own Sign out, once it has taken effect; not after a sign-in that GitHub or the host refused. On Android it
     * turns Monitoring off, so nothing keeps asking the person to resume what they chose to leave. A failure to save is not reported.
     */
    private val afterSignOut: suspend () -> Unit = {},
    /**
     * Where the sessions' lists are kept between runs. The controller only wipes it, at sign-out, so no session title outlives the sign-in;
     * the sessions made by [sessions] are given the same cache, keyed by their host, to read and write.
     */
    private val sessionCache: SessionListCache? = null,
    /** The hosts added by address. With none given (and no [directSessions]) the app has only its tunnel hosts. */
    private val directStore: DirectHostStore = InMemoryDirectHostStore(),
    /** Where the connection tokens of those hosts are kept encrypted. Null keeps none, which suits a host that takes no token. */
    private val directVault: DirectHostVault? = null,
    /** Builds the connection to one of them, under that host's own permission. Null means direct hosts cannot be connected to. */
    private val directSessions: DirectSessionFactory? = null,
    /** The permission of one direct host, kept apart from every other host's. Read once per host, so loading it makes it stick. */
    private val directConsent: (DirectHost) -> InteractionConsent = { InteractionConsent() },
    /** How long to wait between retries when the cold-start host list fails to load while [autoConnect] is pending. */
    private val backoff: Backoff = Backoff(),
    /** Controls whether Pro features (act/manage, background monitoring, and concurrent multi-host) are unlocked. */
    private val pro: ProEntitlement = ProEntitlement(),
    /** Platform billing gateway for purchasing or restoring the Pro entitlement. */
    private val billing: BillingGateway = UnavailableBillingGateway,
) {
    private val _state = MutableStateFlow<AppState>(AppState.Restoring)
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _hosts = MutableStateFlow(HostCatalog(HostList.Loading))

    /**
     * The hosts that can be connected to: the account's tunnel hosts and the ones added by address. It lives outside [state] so that it
     * can be read, and refreshed with [refreshHosts], while a host is connected. It holds no tunnel hosts once signed out.
     */
    val hosts: StateFlow<HostCatalog> = _hosts.asStateFlow()

    private val _connectedHosts = MutableStateFlow<List<ConnectedHost>>(emptyList())

    /**
     * All currently connected hosts, in connection order. When one host is connected this holds that one [ConnectedHost]; when more
     * than one host is connected via [connectHost], all stay online concurrently so their session lists merge in the drawer and can
     * be filtered by host. [state] holds the currently focused host (and its [OpenSession], if any).
     */
    val connectedHosts: StateFlow<List<ConnectedHost>> = _connectedHosts.asStateFlow()

    /**
     * The permission of the host that is connected: a direct host has its own, and with none connected (or a tunnel host) it is the one
     * for the signed-in account's tunnel hosts. Everything below that asks "may this app act" asks this.
     */
    private fun consentOf(state: AppState): InteractionConsent {
        val direct = (state as? AppState.Connected)?.host as? HostRef.Direct ?: return interaction
        return consentFor(direct.host)
    }

    private val directConsents = HashMap<String, InteractionConsent>()

    private fun consentFor(host: DirectHost): InteractionConsent = synchronized(directConsents) { directConsents.getOrPut(host.id) { directConsent(host) } }

    private val activeConsent get() = consentOf(_state.value)

    /** Whether the user has allowed this app to act for them, on the host that is connected. Off until they turn it on, and off again after signing out (for the tunnel hosts). */
    val interactionAllowed: StateFlow<Boolean> = ActiveFlag({ activeConsent.allowed.value }, _state.map { consentOf(it).allowed })

    /** False on a phone that cannot run the device check in front of approvals (Android before 11): the switch is shown but cannot be turned on. */
    val interactionSupported: Boolean get() = interaction.supported

    /** Whether the user has also allowed this app to look after sessions (mark read, archive, start and remove). Only ever on while [interactionAllowed] is. */
    val managementAllowed: StateFlow<Boolean> = ActiveFlag({ activeConsent.managementAllowed.value }, _state.map { consentOf(it).managementAllowed })

    /** What the permissions on the Settings screen apply to: the connected direct host's name, or null for the tunnel hosts. */
    val consentScope: String? get() = ((_state.value as? AppState.Connected)?.host as? HostRef.Direct)?.host?.name

    /** Whether a GitHub sign-in is held. A direct host needs none. */
    val isSignedIn: Boolean get() = token != null

    /** Whether Pro features (acting, managing, background monitoring, and concurrent multi-host) are unlocked. */
    val proUnlocked: StateFlow<Boolean> get() = pro.unlocked

    private fun connectedSession(): HostSession? = (_state.value as? AppState.Connected)?.session

    private fun allLiveSessions(): List<HostSession> =
        (_connectedHosts.value.map { it.session } + listOfNotNull(connectedSession())).distinct()

    private fun liveSession(): HostSession = connectedSession() ?: throw IOException("Not connected to the host")

    /** Parks whichever sessions are live once the app has been out of sight for [parkGraceMs], and takes them up again when the app returns. */
    private val parking = ParkScheduler(
        scope,
        parkGraceMs,
        park = { allLiveSessions().forEach { it.park() } },
        unpark = { allLiveSessions().forEach { it.unpark() } },
    )

    /**
     * Tells the controller whether the app can be seen, from the process lifecycle so that another screen of this app in front counts.
     * Out of sight for the grace period, the live session stops streaming chats and channels it was keeping for the screen, and the
     * host list and alerts carry on; coming back subscribes them again. Nothing on screen changes: a chat keeps what it showed until
     * its new snapshot replaces it.
     */
    fun setAppVisible(visible: Boolean) = parking.visible(visible)

    /** Looks after sessions and chats on the live host. Every request checks the tier, the target and, where it matters, the device check first. */
    val manager = SessionManager(
        // Never from a list that is only what was saved last time: the host may no longer have those sessions.
        allowed = { connectedSession()?.let { it.canManage.value && !it.root.stale.value } == true },
        sessions = { connectedSession()?.root?.sessions?.value.orEmpty() },
        agents = { connectedSession()?.root?.root?.value?.agents.orEmpty() },
        sessionState = { uri -> (_state.value as? AppState.Connected)?.open?.takeIf { it.uri == uri }?.store?.state?.value },
        dispatch = { channel, action -> liveSession().dispatch(channel, action) },
        commands = object : ManagementCommands {
            override suspend fun createSession(uri: String, provider: String?, workingDirectories: List<String>?, config: Map<String, JsonElement>?) =
                liveSession().createSession(uri, provider, workingDirectories, config)

            override suspend fun resolveSessionConfig(provider: String, workingDirectory: String?, config: Map<String, JsonElement>?) =
                liveSession().resolveSessionConfig(provider, workingDirectory, config)

            override suspend fun createChat(sessionUri: String, chatUri: String) = liveSession().createChat(sessionUri, chatUri)

            override suspend fun disposeSession(uri: String) = liveSession().disposeSession(uri)

            override suspend fun disposeChat(uri: String) = liveSession().disposeChat(uri)
        },
        gate = approvalGate,
    )

    /** Creates a [SessionManager] bound to [session], so multi-host dialogs can query or start sessions on any connected host. */
    fun managerFor(session: HostSession): SessionManager = SessionManager(
        allowed = { session.canManage.value && !session.root.stale.value },
        sessions = { session.root.sessions.value },
        agents = { session.root.root.value?.agents.orEmpty() },
        sessionState = { uri -> (_state.value as? AppState.Connected)?.open?.takeIf { it.uri == uri }?.store?.state?.value },
        dispatch = { channel, action -> session.dispatch(channel, action) },
        commands = object : ManagementCommands {
            override suspend fun createSession(uri: String, provider: String?, workingDirectories: List<String>?, config: Map<String, JsonElement>?) =
                session.createSession(uri, provider, workingDirectories, config)

            override suspend fun resolveSessionConfig(provider: String, workingDirectory: String?, config: Map<String, JsonElement>?) =
                session.resolveSessionConfig(provider, workingDirectory, config)

            override suspend fun createChat(sessionUri: String, chatUri: String) = session.createChat(sessionUri, chatUri)

            override suspend fun disposeSession(uri: String) = session.disposeSession(uri)

            override suspend fun disposeChat(uri: String) = session.disposeChat(uri)
        },
        gate = approvalGate,
    )

    /**
     * Reads the two sides of a changed file of the open changeset, for its line-by-line changes. It is asked only for files the
     * open changeset lists, and reads only the content addresses that file's own entry names.
     */
    private val diffs = ChangesetDiffs(
        files = { (_state.value as? AppState.Connected)?.open?.changeset?.store?.state?.value?.files.orEmpty() },
        read = { uri -> liveSession().readResource(uri) },
    )

    /** Marks changed files of the open changeset reviewed. Needs the manage tier and a changeset the host says is reviewable. */
    private val reviewer = ChangesetReviewer(
        allowed = { connectedSession()?.canManage?.value == true },
        reviewableFiles = { uri ->
            (_state.value as? AppState.Connected)?.open?.changeset
                ?.takeIf { it.uri == uri && it.reviewable }
                ?.store?.state?.value?.files?.map { it.id }?.toSet()
        },
        dispatch = { channel, action -> liveSession().dispatch(channel, action) },
    )

    /** Sets the reviewed flag of one file of the open changeset. Nothing is shown as changed until the host echoes it. */
    suspend fun setFileReviewed(fileId: String, reviewed: Boolean): ManageResult {
        val uri = (_state.value as? AppState.Connected)?.open?.changeset?.uri ?: return ManageResult.NoSuchTarget
        return reviewer.setReviewed(uri, fileId, reviewed)
    }

    @Volatile
    private var token: GitHubToken? = null

    /** One renewal at a time: a refresh token can be used once, so a second caller must see the first one's result, not spend it again. */
    private val renewLock = Mutex()

    /** The refresh token GitHub refused, so it is not offered again on every request until the user signs in afresh. */
    @Volatile
    private var refusedRefresh: Secret? = null
    private var signInJob: Job? = null
    private var hostsJob: Job? = null
    private var watchJob: Job? = null
    private var diffJob: Job? = null

    /** Set when a saved sign-in is restored and taken by the first host list that loads after it, so that only the cold start auto-connects. */
    private val autoConnectPending = AtomicBoolean(false)

    /** Which blocks the user opened in each chat, kept while connected so leaving a chat and coming back finds them as they were. */
    private val expansions = LinkedHashMap<String, ExpansionState>()

    fun start() {
        // The saved choice is read before anything can connect, so the first connection already uses the right tier.
        scope.launch {
            pro.load()
            interaction.load()
            loadDirectHosts()
            restore()
        }
    }

    /**
     * Allows or forbids this app to send messages, answers and approvals as the user, and reconnects the live session so
     * the new tier applies now rather than at the next drop. Turning it off ends the connection that held the permission.
     *
     * @return true when the choice was saved. Turning it off takes effect either way.
     */
    suspend fun setInteraction(on: Boolean): Boolean {
        if (on && !pro.unlocked.value) return false
        val at = _state.value
        val consent = consentOf(at)
        val saved = consent.set(on)
        // Reconnect only when the permission really changed: a turn-on that could not be saved changed nothing.
        if (consent.allowed.value == on) restartIfStillConnected(at)
        return saved
    }

    /** Reconnects the session of [at], the state a permission was changed in, unless a switch has since moved to another host: that one's permission did not change. */
    private fun restartIfStillConnected(at: AppState) {
        val connected = at as? AppState.Connected ?: return
        if (connected.host is HostRef.Tunnel) {
            val tunnels = _connectedHosts.value.filter { it.host is HostRef.Tunnel }.map { it.session }
            if (tunnels.isNotEmpty()) {
                tunnels.forEach { it.restart() }
                return
            }
        }
        val session = connected.session
        if ((_state.value as? AppState.Connected)?.session === session) session.restart()
    }

    /**
     * Allows or forbids this app to look after sessions (mark read, archive, start and remove) as well, and reconnects the live
     * session so the new tier applies now. Refused, with nothing changed, unless the app may already act for the user.
     *
     * @return true when the choice was saved. Turning it off takes effect either way.
     */
    suspend fun setManagement(on: Boolean): Boolean {
        if (on && !pro.unlocked.value) return false
        val at = _state.value
        val consent = consentOf(at)
        val saved = consent.setManagement(on)
        if (consent.managementAllowed.value == on) restartIfStillConnected(at)
        return saved
    }

    /**
     * Grants or revokes the Pro entitlement. When revoked (on == false), active write/management permissions are cleared,
     * any extra background hosts in [connectedHosts] are disconnected so at most one host stays connected, and the remaining
     * session reconnects on the read-only tier.
     */
    suspend fun setProUnlocked(on: Boolean): Boolean {
        val saved = pro.setUnlocked(on)
        if (!pro.unlocked.value) {
            val hadWrite = interaction.allowed.value || synchronized(directConsents) { directConsents.values.any { it.allowed.value } }
            interaction.revokeEntitlement()
            val directs = synchronized(directConsents) { directConsents.values.toList() }
            directs.forEach { it.revokeEntitlement() }
            keepOnlyFocusedHost()
            if (hadWrite) {
                allLiveSessions().forEach { it.restart() }
            }
        }
        return saved
    }

    /**
     * Launches the platform purchase flow for the Pro unlock ([BillingGateway.PRODUCT_ID]), persisting the unlock if purchased.
     */
    suspend fun purchasePro(): BillingResult {
        val result = billing.purchasePro()
        if (result is BillingResult.Unlocked && !setProUnlocked(true)) {
            return BillingResult.Unavailable("Could not save the setting on this phone.")
        }
        return result
    }

    /**
     * Queries the platform billing store to restore an existing Pro purchase ([BillingGateway.PRODUCT_ID]), persisting the unlock if found.
     */
    suspend fun restorePro(): BillingResult {
        val result = billing.restorePurchases()
        if (result is BillingResult.Unlocked && !setProUnlocked(true)) {
            return BillingResult.Unavailable("Could not save the setting on this phone.")
        }
        return result
    }

    @Synchronized
    private fun keepOnlyFocusedHost() {
        val current = _state.value as? AppState.Connected ?: return
        val extra = _connectedHosts.value.filter { it.host.id != current.host.id }
        if (extra.isEmpty()) return
        extra.forEach { it.session.stop() }
        _connectedHosts.value = listOf(ConnectedHost(current.host, current.session))
        launchWatch()
    }

    fun signIn() {
        val current = _state.value
        // From the host list of a person who has not signed in (they came for a host added by address), signing in starts from the sign-in screen.
        if (current is AppState.Hosts && _hosts.value.list == HostList.NotSignedIn) {
            if (_state.compareAndSet(current, AppState.SignedOut(SignInStep.Idle, null))) launchSignIn()
            return
        }
        if (current !is AppState.SignedOut) return
        if (current.step is SignInStep.RequestingCode || current.step is SignInStep.AwaitingApproval) return
        launchSignIn()
    }

    /**
     * Skips the GitHub sign-in to use only the hosts added by address. Ignored while a sign-in is under way. Nothing is signed in afterwards:
     * Remote Tunnel Access hosts are not listed until the person signs in, and signing in from the host list is [signIn].
     */
    fun useDirectHosts() {
        val current = _state.value as? AppState.SignedOut ?: return
        if (current.step is SignInStep.RequestingCode || current.step is SignInStep.AwaitingApproval) return
        _hosts.value = HostCatalog(HostList.NotSignedIn, directEntries())
        _state.compareAndSet(current, AppState.Hosts)
    }

    /** What [addDirectHost] came to. */
    sealed interface AddHostResult {
        data class Added(val host: DirectHost) : AddHostResult

        /** [reason] is written for the person who typed it. */
        data class Invalid(val reason: String) : AddHostResult

        /** The host or its token could not be saved on this phone. Nothing was added. */
        data object NotSaved : AddHostResult
    }

    /**
     * Adds a host by address and keeps its [token] (if any) encrypted in the vault, under its own id; the address is checked by
     * [HostAddress.check] first. A new host starts with no permission to act: that is chosen per host, after it is connected to.
     */
    suspend fun addDirectHost(name: String, address: String, token: String): AddHostResult {
        if (directSessions == null) return AddHostResult.Invalid("Adding a host by its address is not available here.")
        val checked = HostAddress.check(address)
        val ok = checked as? HostAddressCheck.Ok ?: return AddHostResult.Invalid((checked as HostAddressCheck.Refused).reason)
        val current = directHosts
        if (current.size >= DirectHostCodec.MAX_HOSTS) return AddHostResult.Invalid("That is as many hosts as can be kept (${DirectHostCodec.MAX_HOSTS}). Remove one first.")
        current.firstOrNull { it.url == ok.url }?.let { return AddHostResult.Invalid("That address is already added as “${it.name}”.") }
        val host = DirectHost(DirectHost.ID_PREFIX + java.util.UUID.randomUUID(), DirectHostCodec.cleanName(name).ifEmpty { ok.host }, ok.url)
        val secret = token.trim()
        return try {
            if (secret.isNotEmpty()) (directVault ?: return AddHostResult.NotSaved).put(host.id, Secret(secret))
            try {
                directStore.save(current + host)
            } catch (e: IOException) {
                // The token is not kept for a host that was not added.
                try { directVault?.remove(host.id) } catch (_: IOException) {} catch (_: VaultCryptoException) {}
                throw e
            }
            directHosts = current + host
            consentFor(host).load()
            publishDirect()
            AddHostResult.Added(host)
        } catch (_: IOException) {
            AddHostResult.NotSaved
        } catch (_: VaultCryptoException) {
            AddHostResult.NotSaved
        }
    }

    /**
     * Removes a direct host: its token, its saved permissions and the saved session list go with it. If it is connected, its
     * connection is ended first. Signing out of GitHub never does any of this.
     */
    suspend fun removeDirectHost(id: String) {
        val host = directHosts.firstOrNull { it.id == id } ?: return
        disconnectFrom(id)
        directHosts = directHosts.filter { it.id != id }
        try {
            directStore.save(directHosts)
        } catch (_: IOException) {
            // Gone for this run; if it was not saved it comes back at the next start, token already removed, and can be removed again.
        }
        try {
            directVault?.remove(id)
        } catch (_: IOException) {
        } catch (_: VaultCryptoException) {
        }
        synchronized(directConsents) { directConsents.remove(id) }?.forget() ?: directConsent(host).forget()
        safeClearCache()
        publishDirect()
    }

    /** Leaves the host with [id] if it is connected when this gets the lock: another connected host stays up. */
    @Synchronized
    private fun disconnectFrom(id: String) {
        if (_connectedHosts.value.size > 1 && _connectedHosts.value.any { it.host.id == id }) {
            disconnectHost(id)
        } else if ((_state.value as? AppState.Connected)?.host?.id == id) {
            disconnect()
        }
    }

    /** The hosts added by address, with the last used first and the rest by name. */
    private fun directEntries(): List<DirectEntry> =
        directHosts.map { DirectEntry(it, isLastUsed = it.id == lastId) }.sortedWith(compareByDescending<DirectEntry> { it.isLastUsed }.thenBy { it.host.name.lowercase() })

    /** Puts the current direct hosts into the catalog, whatever is showing. */
    private fun publishDirect() {
        _hosts.update { it.copy(direct = directEntries()) }
    }

    @Volatile
    private var directHosts: List<DirectHost> = emptyList()

    /** The id of the last host connected to, tunnel or direct, as saved. Read at start and set at each connection. */
    @Volatile
    private var lastId: String? = null

    private suspend fun loadDirectHosts() {
        directHosts = try {
            directStore.load()
        } catch (_: IOException) {
            emptyList()
        }
        lastId = try {
            lastHost.get()
        } catch (_: IOException) {
            null
        }
        // Each host's permission is read before it can be connected to, so the first connection is already made under the right tier.
        directHosts.forEach { consentFor(it).load() }
    }

    fun cancelSignIn() {
        cancelSignInJob()
        _state.update { if (it is AppState.SignedOut) AppState.SignedOut(SignInStep.Idle, null) else it }
    }

    /**
     * Loads the tunnel hosts again, from the host list or while connected (a load never changes [state]). A list already loaded stays in
     * [hosts] while the new one loads, so that looking again shows no spinner, and stays if the new one fails; a failed or missing one is
     * replaced by [HostList.Loading].
     */
    @Synchronized
    fun refreshHosts() {
        if (_state.value !is AppState.Hosts && _state.value !is AppState.Connected) return
        markLoading(keepList = true)
        launchHostsLoad()
    }

    /** Connects to [tunnel] from the host list; from any other screen it is ignored. [switchHost] is how a connected host is left for another. */
    @Synchronized
    fun connect(tunnel: TunnelInfo) {
        if (_state.value !is AppState.Hosts) return
        switchHost(HostRef.Tunnel(tunnel))
    }

    /**
     * Connects to [host], one added by address, from the host list, on the same terms as [connect]. It needs no GitHub sign-in and asks
     * for no token from GitHub or the Dev Tunnels service; it is made under that host's own permission, which starts as read only.
     */
    @Synchronized
    fun connect(host: DirectHost) {
        if (_state.value !is AppState.Hosts) return
        switchHost(HostRef.Direct(host))
    }

    /**
     * Connects to [target], from the host list or from another host that is connected: the exclusive switch that stops the previous host.
     * From a connected host it goes straight to the next, with no host list in between: the old session is stopped (it saves its list)
     * and the new one is made under the target's own permission, never the old host's. The switch to the new session happens only if the
     * screen is still the old connection at that moment: a sign-out that landed while the session was being made wins, and the new
     * session is stopped. The host already connected, and a direct host that is no longer saved, are ignored.
     */
    @Synchronized
    fun switchHost(target: HostRef) {
        val from = _state.value
        if (from !is AppState.Hosts && !(from is AppState.Connected && from.host.id != target.id)) return
        val host = connectable(target) ?: return
        val previous = (_connectedHosts.value.map { it.session } + listOfNotNull((from as? AppState.Connected)?.session)).distinct()
        if (from is AppState.Connected) {
            cancelWatchJob()
            forgetExpansions()
            cancelDiffJob()
            previous.forEach { it.stop() }
            _connectedHosts.value = emptyList()
        }
        swapToConnected(from, host, newSession(host))
    }

    /**
     * Connects to [target] while keeping any already-connected hosts live in [connectedHosts], so the user can view and filter sessions
     * across several hosts at once. If [target] is already in [connectedHosts], makes it the focused host without reconnecting.
     */
    @Synchronized
    fun connectHost(target: HostRef) {
        val from = _state.value
        if (from is AppState.Hosts || !pro.unlocked.value) {
            switchHost(target)
            return
        }
        val current = from as? AppState.Connected ?: return
        if (current.host.id == target.id) return
        val existing = _connectedHosts.value.firstOrNull { it.host.id == target.id }
        if (existing != null) {
            if (current.open != null) closeSession()
            val afterClose = _state.value as? AppState.Connected ?: return
            if (_state.compareAndSet(afterClose, AppState.Connected(existing.host, existing.session))) {
                rememberLastHost(existing.host.id)
                launchWatch()
            }
            return
        }
        val host = connectable(target) ?: return
        if (current.open != null) closeSession()
        val afterClose = _state.value as? AppState.Connected ?: return
        val session = newSession(host)
        session.start()
        if (!_state.compareAndSet(afterClose, AppState.Connected(host, session))) {
            session.stop()
            return
        }
        _connectedHosts.update { list ->
            val withCurrent = if (list.none { it.host.id == afterClose.host.id }) {
                list + ConnectedHost(afterClose.host, afterClose.session)
            } else {
                list
            }
            withCurrent.filter { it.host.id != host.id } + ConnectedHost(host, session)
        }
        parking.ifParked(session::park)
        autoConnectPending.set(false)
        rememberLastHost(host.id)
        launchWatch()
    }

    /**
     * Disconnects the host with [hostId]. When other hosts remain in [connectedHosts], they stay online and the most recent remaining
     * host becomes the focused connection; when [hostId] is the last connected host, returns to [AppState.Hosts] as [disconnect] does.
     */
    @Synchronized
    fun disconnectHost(hostId: String) {
        val current = _state.value as? AppState.Connected ?: return
        val list = _connectedHosts.value
        val target = list.firstOrNull { it.host.id == hostId }
            ?: if (current.host.id == hostId) ConnectedHost(current.host, current.session) else return
        val remaining = list.filter { it.host.id != hostId }
        if (remaining.isEmpty()) {
            if (current.host.id == hostId) disconnect()
            return
        }
        if (current.host.id == hostId) {
            if (current.open != null) closeSession()
            cancelDiffJob()
            val afterClose = _state.value as? AppState.Connected ?: return
            val next = remaining.last()
            target.session.stop()
            _connectedHosts.value = remaining
            if (_state.compareAndSet(afterClose, AppState.Connected(next.host, next.session))) {
                rememberLastHost(next.host.id)
                launchWatch()
            }
        } else {
            target.session.stop()
            _connectedHosts.value = remaining
            launchWatch()
        }
    }

    /** [target] as the app holds it now, or null when it cannot be connected to: a direct host removed since the caller got it, or no way to build one. */
    private fun connectable(target: HostRef): HostRef? = when (target) {
        is HostRef.Tunnel -> target
        is HostRef.Direct -> if (directSessions == null) null else directHosts.firstOrNull { it.id == target.host.id }?.let { HostRef.Direct(it) }
    }

    private fun newSession(host: HostRef): HostSession = when (host) {
        is HostRef.Tunnel -> sessions.create(host.info) { freshToken().access }
        is HostRef.Direct -> checkNotNull(directSessions).create(host.host, { directVault?.get(host.host.id) }, consentFor(host.host))
    }

    private fun swapToConnected(from: AppState, host: HostRef, session: HostSession) {
        session.start()
        if (!_state.compareAndSet(from, AppState.Connected(host, session))) {
            session.stop()
            return
        }
        _connectedHosts.value = listOf(ConnectedHost(host, session))
        // The app may have gone out of sight before this session existed: it starts parked rather than waiting for another trip away.
        parking.ifParked(session::park)
        autoConnectPending.set(false)
        rememberLastHost(host.id)
        launchWatch()
    }

    private fun rememberLastHost(id: String) {
        lastId = id
        republishLastUsed()
        scope.launch {
            try {
                lastHost.set(id)
            } catch (_: IOException) {
                // Remembering the last host is a convenience; failing to save it must not disturb a live connection.
            }
        }
    }

    /** Opens the detail for [uri]. Ignored unless connected with no detail open, so a double tap cannot subscribe twice. */
    @Synchronized
    fun openSession(uri: String) {
        val current = _state.value as? AppState.Connected ?: return
        if (current.open != null) return
        val store = current.session.openSession(uri)
        if (!_state.compareAndSet(current, current.copy(open = OpenSession(uri, store)))) current.session.closeSession(uri)
    }

    /** Opens the session a notification points at, replacing any detail already open. Ignores a session no connected host lists. */
    @Synchronized
    fun focusSession(uri: String) {
        val current = _state.value as? AppState.Connected ?: return
        if (current.session.root.sessions.value.any { it.resource == uri }) {
            if (current.open?.uri == uri) return
            if (current.open != null) closeSession()
            openSession(uri)
            return
        }
        val owner = _connectedHosts.value.firstOrNull { entry -> entry.session.root.sessions.value.any { it.resource == uri } } ?: return
        showSession(uri, owner.host.id)
    }

    /**
     * Opens [uri] on the currently focused host, leaving the session that is open, if any, so the side menu can go from one session to
     * another. Unlike [focusSession] it does not need the host to list the session yet: one just started is not listed until the host says so.
     */
    @Synchronized
    fun showSession(uri: String) {
        val current = _state.value as? AppState.Connected ?: return
        if (current.open?.uri == uri) return
        if (current.open != null) closeSession()
        openSession(uri)
    }

    /**
     * Opens [uri] on the connected host identified by [hostId] (or the currently focused host when [hostId] is null), switching focus to
     * that host without disconnecting any other connected hosts.
     */
    @Synchronized
    fun showSession(uri: String, hostId: String?) {
        val current = _state.value as? AppState.Connected ?: return
        if (hostId == null || current.host.id == hostId) {
            showSession(uri)
            return
        }
        val target = _connectedHosts.value.firstOrNull { it.host.id == hostId } ?: return
        if (current.open != null) closeSession()
        val afterClose = _state.value as? AppState.Connected ?: return
        if (_state.compareAndSet(afterClose, AppState.Connected(target.host, target.session))) {
            openSession(uri)
        }
    }

    /** Leaves the open detail and stays connected. */
    @Synchronized
    fun closeSession() {
        val current = _state.value as? AppState.Connected ?: return
        val open = current.open ?: return
        if (_state.compareAndSet(current, current.copy(open = null))) {
            // Every chat of the chain (a subagent's transcript and the ones it was opened from) holds a subscription.
            generateSequence(open.chat) { it.parent }.forEach { current.session.closeChat(it.uri) }
            open.warm.forEach { current.session.closeChat(it.uri) }
            cancelDiffJob()
            open.changeset?.let { current.session.closeChannel(it.uri) }
            open.terminal?.let { current.session.closeChannel(it.uri) }
            current.session.closeSession(open.uri)
        }
    }

    /**
     * Opens a changeset the open session advertises, to look at the files it changed. Read only. Only a changeset that the
     * session itself lists, whose address needs nothing filled in, is opened, and not a second one, nor over a terminal or a
     * subagent's transcript. It may open over the session's own chat: that chat is the session's main view.
     */
    @Synchronized
    fun openChangeset(uri: String) {
        val current = _state.value as? AppState.Connected ?: return
        val open = current.open ?: return
        if (open.changeset != null || open.terminal != null || open.chat?.parent != null) return
        val entry = open.store.state.value?.changesets.orEmpty().map(ChangesetEntry::from).firstOrNull { it.uri == uri }
        if (entry == null || uri.length > MAX_CHANNEL_URI) return
        val store = current.session.openChangeset(uri)
        if (!_state.compareAndSet(current, current.copy(open = open.copy(changeset = OpenChangeset(uri, store, entry.reviewable))))) current.session.closeChannel(uri)
    }

    /**
     * Opens the line-by-line changes of one file of the open changeset, over it. Ignored unless the changeset lists that file
     * and no file is open, so a stale or made-up id reads nothing and a double tap does not read twice. The reading happens
     * in the background; the screen follows [OpenDiff.load].
     */
    @Synchronized
    fun openDiff(fileId: String) {
        val current = _state.value as? AppState.Connected ?: return
        val open = current.open ?: return
        val changeset = open.changeset ?: return
        if (changeset.diff != null) return
        val file = changeset.store.state.value?.files?.firstOrNull { it.id == fileId } ?: return
        val load = MutableStateFlow<DiffLoad>(DiffLoad.Loading)
        val shown = OpenDiff(fileId, displayPath(file.edit.after?.uri ?: file.edit.before?.uri ?: file.id), load.asStateFlow())
        if (!_state.compareAndSet(current, current.copy(open = open.copy(changeset = changeset.copy(diff = shown))))) return
        diffJob?.cancel()
        diffJob = scope.launch { load.value = DiffLoad.Loaded(diffs.load(fileId)) }
    }

    /** Leaves the file and returns to the changeset's files. Anything still being read is dropped. */
    @Synchronized
    fun closeDiff() {
        val current = _state.value as? AppState.Connected ?: return
        val open = current.open ?: return
        val changeset = open.changeset ?: return
        if (changeset.diff == null) return
        if (_state.compareAndSet(current, current.copy(open = open.copy(changeset = changeset.copy(diff = null))))) cancelDiffJob()
    }

    /** Leaves the changeset and returns to the session detail. */
    @Synchronized
    fun closeChangeset() {
        val current = _state.value as? AppState.Connected ?: return
        val open = current.open ?: return
        val changeset = open.changeset ?: return
        if (_state.compareAndSet(current, current.copy(open = open.copy(changeset = null)))) {
            cancelDiffJob()
            current.session.closeChannel(changeset.uri)
        }
    }

    /** Opens the transcript of one of the open session's chats. Ignores a chat the session does not list, and a second tap while one is open. */
    @Synchronized
    fun openChat(uri: String) {
        val current = _state.value as? AppState.Connected ?: return
        val open = current.open ?: return
        if (open.chat != null) return
        val listed = open.store.state.value?.chats?.any { it.resource == uri } == true
        if (!listed) return
        val next = newChat(current.session, uri, parent = null)
        if (!_state.compareAndSet(current, current.copy(open = open.copy(chat = next)))) current.session.closeChat(uri)
    }

    /**
     * Makes [uri], one of the open session's chats, the one on screen: opens it when none is, or swaps it for the one that
     * is (which stays subscribed in [OpenSession.warm], with what the person had expanded in it). The session screen is the chat plus the
     * tabs that call this, so it is the way between chats. Ignored for a chat the session does not list, the chat already
     * shown, and while a subagent's transcript, a terminal or a changeset is over the chat: those are left first.
     */
    @Synchronized
    fun selectChat(uri: String) {
        val current = _state.value as? AppState.Connected ?: return
        val open = current.open ?: return
        val shown = open.chat
        if (shown == null) return openChat(uri)
        if (shown.parent != null || shown.uri == uri || open.terminal != null || open.changeset != null) return
        val listed = open.store.state.value?.chats?.map { it.resource }?.toSet().orEmpty()
        if (uri !in listed) return
        val back = open.warm.firstOrNull { it.uri == uri }
        val next = back ?: newChat(current.session, uri, parent = null)
        // The chat left behind stays subscribed, so its tab is instant when the person comes back. One that has left the session is let go,
        // and so is the oldest beyond a few, so a long session does not hold a subscription for every chat it ever had.
        val kept = (open.warm.filter { it !== back } + shown).filter { it.uri in listed }.takeLast(MAX_WARM_CHATS)
        val dropped = (open.warm + shown).filter { it !== next && it !in kept }
        if (!_state.compareAndSet(current, current.copy(open = open.copy(chat = next, warm = kept)))) {
            if (back == null) current.session.closeChat(uri)
            return
        }
        dropped.forEach { current.session.closeChat(it.uri) }
    }

    /**
     * Opens the transcript of a subagent the open chat started, on top of it. Only an address that a subagent result in the
     * open chat names is accepted, never one that came from anywhere else, and never one that is already open above it.
     */
    @Synchronized
    fun openSubagentChat(uri: String) {
        val current = _state.value as? AppState.Connected ?: return
        val open = current.open ?: return
        val parent = open.chat ?: return
        if (generateSequence(parent) { it.parent }.count() > MAX_SUBAGENT_DEPTH) return
        if (generateSequence(parent) { it.parent }.any { it.uri == uri }) return
        val shown = parent.store.state.value ?: return
        if (uri !in SubagentLinks.referencedBy(shown)) return
        val next = newChat(current.session, uri, parent)
        if (!_state.compareAndSet(current, current.copy(open = open.copy(chat = next)))) current.session.closeChat(uri)
    }

    private fun newChat(session: HostSession, uri: String, parent: OpenChat?): OpenChat {
        val store = session.openChat(uri)
        return OpenChat(uri, store, expansionFor(uri), interactorFor(session, uri, store), parent)
    }

    private fun interactorFor(session: HostSession, uri: String, store: ChatStore) = ChatInteractor(
        chatUri = uri,
        chat = { store.state.value },
        canInteract = { session.canInteract.value },
        dispatch = session::dispatch,
        gate = approvalGate,
        models = { modelsFor(session) },
        agents = { CustomAgents.of((_state.value as? AppState.Connected)?.open?.store?.state?.value) },
        config = {
            val open = (_state.value as? AppState.Connected)?.open
            ChatConfig.view(open?.store?.state?.value, uri, open?.store?.chatMutableKeys?.value.orEmpty(), store.ownConfig.value)
        },
        sessionModel = { sessionModelFor(session) },
    )

    /** The model the host reports on the open session's state or summary (E6), used when the chat itself has not named one yet. */
    private fun sessionModelFor(session: HostSession): String? {
        val open = (_state.value as? AppState.Connected)?.open ?: return null
        return open.store.state.value?.extras?.model
            ?: session.root.sessions.value.firstOrNull { it.resource == open.uri }?.extras?.model
    }

    /** The models the host offers for the agent of the session on screen; none when no session is open or the agent is not listed. */
    private fun modelsFor(session: HostSession): List<ModelOption> {
        val provider = (_state.value as? AppState.Connected)?.open?.store?.state?.value?.provider ?: return emptyList()
        return ModelOptions.forProvider(session.root.root.value?.agents.orEmpty(), provider)
    }

    /**
     * Approves or denies one waiting tool call for a notification button, with no screen showing it. The chat is opened for
     * as long as it takes to read the call and send the answer, then closed again (a chat already open is only shared, never
     * closed). The same [ChatInteractor] that the chat screen uses does the deciding, so everything that guards it there guards
     * this: the write tier must be on, the call must still be waiting in the live chat, an approval goes through the device
     * check (and is looked up again after it), denying needs none, and nothing counts as done until the host echoes it. With an
     * [optionId] it is one of the host's own options, and only if the host offers it now and it means what the button says.
     *
     * With [background] set, for a caller with no screen in front of the person, there may be no live connection when the button is
     * pressed (the process was killed): [ensureConnected] makes one first (within [CONNECT_MS]) when monitoring allows it, and when it
     * cannot the result is [InteractResult.NotConnected] with nothing sent. Reaching the host and reading the chat together take at
     * most [PREPARE_MS], so the answer is not left waiting behind a connection that came up late. The in-app inbox leaves it unset:
     * it neither connects nor waits for a connection to come back, and a connection that is down is [InteractResult.NotSent].
     */
    suspend fun decideToolCall(chatUri: String, toolCallId: String, approve: Boolean, optionId: String? = null, background: Boolean = false): InteractResult {
        if (chatUri.isBlank() || chatUri.length > ApprovalTarget.MAX_CHAT_URI) return InteractResult.NotAllowed
        if (optionId != null && (optionId.isBlank() || optionId.length > ApprovalTarget.MAX_OPTION_ID)) return InteractResult.NotAllowed
        // Set once the chat is open, so that exactly what was opened is closed again.
        var opened: HostSession? = null
        try {
            val store = withTimeoutOrNull(PREPARE_MS) {
                val session = hostForChat(chatUri, background) ?: return@withTimeoutOrNull null
                // A background opener: this runs from a notification with the app out of sight, when a parked session would otherwise let the chat go.
                val chat = session.openChat(chatUri, background = true)
                opened = session
                // A chat a screen kept while the app was out of sight still shows the transcript from before: wait for the snapshot that replaces it.
                withTimeoutOrNull(CHAT_LOAD_MS) {
                    chat.subscribeNeeded.first { !it }
                    chat.state.first { it != null }
                } ?: return@withTimeoutOrNull null
                chat
            }
            val session = opened ?: return if (background && autoConnect()) InteractResult.NotConnected else InteractResult.NotAllowed
            if (store == null) return InteractResult.NotSent(IOException("The chat did not load in time"))
            if (store.error.value != null) return InteractResult.NotSent(IOException("The chat could not be loaded"))
            val interactor = interactorFor(session, chatUri, store)
            return if (approve) interactor.approve(toolCallId, optionId) else interactor.deny(toolCallId, optionId)
        } finally {
            opened?.closeChat(chatUri, background = true)
        }
    }

    /**
     * Answers one waiting free-text question for a notification's text field, with no screen showing it. Like [decideToolCall],
     * the chat is opened long enough to read the live request and send the answer, then closed again, and the same
     * [ChatInteractor] checks that the write tier is on, the question is still waiting and still a single free-text question,
     * and the answer meets its bounds. Answering a question needs no device check: it only replies to what the agent asked.
     */
    suspend fun answerQuestion(chatUri: String, requestId: String, text: String, background: Boolean = false): InteractResult {
        if (chatUri.isBlank() || chatUri.length > ApprovalTarget.MAX_CHAT_URI) return InteractResult.NotAllowed
        if (requestId.isBlank() || requestId.length > QuestionTarget.MAX_REQUEST_ID) return InteractResult.NotAllowed
        if (text.isBlank()) return InteractResult.Blank
        if (text.length > QuestionTarget.MAX_ANSWER_CHARS) return InteractResult.NotAllowed
        var opened: HostSession? = null
        try {
            val store = withTimeoutOrNull(PREPARE_MS) {
                val session = hostForChat(chatUri, background) ?: return@withTimeoutOrNull null
                val chat = session.openChat(chatUri, background = true)
                opened = session
                withTimeoutOrNull(CHAT_LOAD_MS) {
                    chat.subscribeNeeded.first { !it }
                    chat.state.first { it != null }
                } ?: return@withTimeoutOrNull null
                chat
            }
            val session = opened ?: return if (background && autoConnect()) InteractResult.NotConnected else InteractResult.NotAllowed
            if (store == null) return InteractResult.NotSent(IOException("The chat did not load in time"))
            if (store.error.value != null) return InteractResult.NotSent(IOException("The chat could not be loaded"))
            return interactorFor(session, chatUri, store).answerText(requestId, text)
        } finally {
            opened?.closeChat(chatUri, background = true)
        }
    }

    /**
     * What a session that needs the person is waiting for, read once and closed again; null when not connected, gone, or nothing is
     * pending. [background] is as for [decideToolCall]: only then does it wait for a connection to be made.
     */
    suspend fun readWaiting(resource: String, background: Boolean = false): RequestRead? {
        val session = hostForResource(resource, background) ?: return null
        if (resource.isBlank() || resource.length > ApprovalTarget.MAX_CHAT_URI) return null
        return WaitingRequests.read(session, resource)
    }

    /**
     * The live session, for a caller that acts on it; a [background] caller first waits for one to be made (see [ensureConnected]). A
     * session still showing the list saved last time is not one to act on: the sessions in it may be gone.
     */
    private suspend fun hostFor(background: Boolean): HostSession? {
        if (background && !ensureConnected(CONNECT_MS)) return null
        return connectedSession()?.takeUnless { it.root.stale.value }
    }

    private suspend fun hostForResource(resource: String, background: Boolean): HostSession? {
        val primary = hostFor(background) ?: return null
        if (primary.root.sessions.value.any { it.resource == resource }) return primary
        return _connectedHosts.value
            .map { it.session }
            .firstOrNull { !it.root.stale.value && it.root.sessions.value.any { s -> s.resource == resource } }
            ?: primary
    }

    private suspend fun hostForChat(chatUri: String, background: Boolean): HostSession? {
        val primary = hostFor(background) ?: return null
        if (primary.root.sessions.value.any { s -> s.chats.orEmpty().any { it.resource == chatUri } || s.defaultChat == chatUri }) return primary
        return _connectedHosts.value
            .map { it.session }
            .firstOrNull { !it.root.stale.value && it.root.sessions.value.any { s -> s.chats.orEmpty().any { it.resource == chatUri } || s.defaultChat == chatUri } }
            ?: primary
    }

    /**
     * For background callers (a notification button): makes sure there is a live host connection, waiting at most [timeoutMs]. It waits
     * for the saved sign-in to be restored, and from the host list connects to the last host the way the cold start does, if
     * [autoConnect] allows and that host is online. Then it waits for the connection to be online.
     *
     * @return true when connected and online. False on timeout, when signed out, when [autoConnect] says no, when the host is offline
     * or not listed, or when the connection fails for good. It never signs in, never changes what the app is allowed to do and shows nothing.
     */
    suspend fun ensureConnected(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) { reachOnline() } ?: false

    private suspend fun reachOnline(): Boolean {
        var current = combine(_state, _hosts) { state, catalog -> state to catalog }
            .first { (state, catalog) -> state !is AppState.Restoring && !(state is AppState.Hosts && (catalog.list is HostList.Loading || catalog.autoConnectPending)) }
            .first
        if (current is AppState.Hosts) {
            if (!connectToLastHost()) return false
            current = _state.value
        }
        val session = (current as? AppState.Connected)?.session ?: return false
        return session.connection.first { it == ConnState.Online || it is ConnState.Failed || it == ConnState.Stopped } == ConnState.Online
    }

    /**
     * Connects to the last used host from the host list when [autoConnect] allows and that host is a direct one or an online tunnel host;
     * the one place both the cold start and [ensureConnected] do it. Does nothing, and says so, for an offline or unlisted host, or a
     * screen that has moved on from the host list.
     *
     * @return true when the app is connected afterwards.
     */
    private suspend fun connectToLastHost(): Boolean {
        val catalog = _hosts.value
        // A host added by address is tried whether or not it is known to be up: nothing tells the app that before it connects. A tunnel host must be online.
        val lastDirect = catalog.direct.firstOrNull { it.isLastUsed }
        val lastTunnel = (catalog.list as? HostList.Loaded)?.hosts?.firstOrNull { it.isLastUsed && it.tunnel.isOnline }
        if (lastDirect == null && lastTunnel == null) return false
        if (!autoConnect()) return false
        if (lastDirect != null) connect(lastDirect.host) else connect(lastTunnel!!.tunnel)
        return _state.value is AppState.Connected
    }

    /**
     * Opens the live output of a terminal that a tool call in the open chat ran a command in, over the chat. Read only: the
     * terminal is only ever watched, and typing into one is not something this app can do at any tier. Only an address that a
     * tool result in the open chat names is opened, so a host cannot make the app subscribe to one of its choosing by putting it
     * somewhere else, and not a second one over an open one.
     */
    @Synchronized
    fun openTerminal(uri: String) {
        val current = _state.value as? AppState.Connected ?: return
        val open = current.open ?: return
        val chat = open.chat ?: return
        if (open.terminal != null) return
        val shown = chat.store.state.value ?: return
        if (uri !in TerminalLinks.referencedBy(shown)) return
        val store = current.session.openTerminal(uri)
        if (!_state.compareAndSet(current, current.copy(open = open.copy(terminal = OpenTerminal(uri, store))))) current.session.closeChannel(uri)
    }

    /** Leaves the terminal and returns to the chat it was opened from. */
    @Synchronized
    fun closeTerminal() {
        val current = _state.value as? AppState.Connected ?: return
        val open = current.open ?: return
        val terminal = open.terminal ?: return
        if (_state.compareAndSet(current, current.copy(open = open.copy(terminal = null)))) current.session.closeChannel(terminal.uri)
    }

    /** Leaves the open transcript and returns to where it was opened from: the chat above it for a subagent's, else the session detail. */
    @Synchronized
    fun closeChat() {
        val current = _state.value as? AppState.Connected ?: return
        val open = current.open ?: return
        val chat = open.chat ?: return
        // A terminal opened from the chat goes with it.
        if (_state.compareAndSet(current, current.copy(open = open.copy(chat = chat.parent, terminal = null)))) {
            open.terminal?.let { current.session.closeChannel(it.uri) }
            current.session.closeChat(chat.uri)
        }
    }

    /** Leaves all live hosts and returns to the host list, unless a sign-out took the screen first: then that stands. */
    @Synchronized
    fun disconnect() {
        val connected = _state.value as? AppState.Connected ?: return
        val sessionsToStop = (_connectedHosts.value.map { it.session } + connected.session).distinct()
        cancelWatchJob()
        forgetExpansions()
        cancelDiffJob()
        _connectedHosts.value = emptyList()
        sessionsToStop.forEach { it.stop() }
        showHosts(from = connected)
    }

    fun signOut() {
        scope.launch {
            endSignIn(notice = null, offerRevoke = true)
            try {
                afterSignOut()
            } catch (_: IOException) {
                // The sign-out has happened; a setting that could not be saved must not look like it failed.
            }
        }
    }

    private suspend fun restore() {
        val loaded = try {
            vault.load()
        } catch (e: IOException) {
            _state.value = AppState.SignedOut(SignInStep.Idle, "The saved sign-in could not be read. Sign in again.")
            return
        }
        when (loaded) {
            is VaultLoad.Present -> {
                val saved = loaded.token
                // An expired token that can be renewed is kept: it is renewed when it is first needed, which is when the hosts are listed.
                if (saved.refresh == null && saved.expiresAt?.let { !now().isBefore(it) } == true) {
                    safeClear()
                    safeClearCache()
                    _state.value = AppState.SignedOut(SignInStep.Idle, "Your GitHub sign-in expired. Sign in again.")
                } else {
                    token = saved
                    // Once per process start: when a last host is saved and autoConnect allows, the first list that loads connects to it.
                    autoConnectPending.set(lastId != null && autoConnect())
                    showHosts()
                }
            }
            VaultLoad.Absent -> {
                if (directHosts.isEmpty()) {
                    _state.value = AppState.SignedOut(SignInStep.Idle, null)
                } else {
                    // Nobody is signed in, but hosts were added by address: they are what the app opens on, and the last one is connected to by itself like any other.
                    _hosts.value = HostCatalog(HostList.NotSignedIn, directEntries())
                    _state.value = AppState.Hosts
                    connectToLastHost()
                }
            }
            is VaultLoad.Unreadable -> {
                // The vault has wiped itself, and with it the key to the saved session lists.
                safeClearCache()
                _state.value = AppState.SignedOut(SignInStep.Idle, "The saved sign-in could not be read and was removed. Sign in again.")
            }
        }
    }

    @Synchronized
    private fun launchSignIn() {
        signInJob?.cancel()
        signInJob = scope.launch { runSignIn() }
    }

    private suspend fun runSignIn() {
        setStep(SignInStep.RequestingCode)
        try {
            val code = auth.start()
            setStep(SignInStep.AwaitingApproval(code.userCode, code.verificationUri, now().plusSeconds(code.expiresInSeconds.toLong())))
            when (val result = auth.poll(code)) {
                is DeviceFlowResult.Success -> {
                    vault.save(result.token)
                    token = result.token
                    refusedRefresh = null
                    showHosts()
                }
                DeviceFlowResult.Expired -> failSignIn("The code expired before it was approved. Start again.")
                DeviceFlowResult.Denied -> failSignIn("The sign-in was denied on GitHub.")
                is DeviceFlowResult.Failed -> failSignIn("GitHub refused the sign-in (${result.error}).")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubAuthException) {
            failSignIn(e.message ?: "GitHub returned an unexpected response.")
        } catch (e: IOException) {
            failSignIn("Could not reach GitHub. Check the connection and try again.")
        } catch (e: VaultCryptoException) {
            failSignIn("Signed in, but the token could not be stored securely on this device.")
        } catch (e: Exception) {
            failSignIn("Sign-in failed unexpectedly (${e.javaClass.simpleName}).")
        }
    }

    private fun setStep(step: SignInStep) {
        _state.update { if (it is AppState.SignedOut) AppState.SignedOut(step, null) else it }
    }

    private fun failSignIn(message: String) {
        _state.update { if (it is AppState.SignedOut) AppState.SignedOut(SignInStep.Failed(message), null) else it }
    }

    /**
     * The sign-in to use now. A GitHub user token lasts eight hours, so one that is about to run out (or has) is traded for a new
     * one first, when a refresh token is held; the new pair is saved before anything else uses it. When GitHub will not renew it the
     * old token is used for as long as it is valid, and when it is not valid any more the user has to sign in again.
     */
    private suspend fun freshToken(): GitHubToken {
        val held = token ?: throw DevTunnelsException.AuthExpired()
        val expiresAt = held.expiresAt
        val expired = expiresAt != null && !now().isBefore(expiresAt)
        val due = expired || (expiresAt != null && !now().plus(renewBefore).isBefore(expiresAt))
        if (!due) return held
        val usable = !expired
        val refresh = held.refresh
        if (refresh == null || refresh == refusedRefresh) return if (usable) held else throw DevTunnelsException.AuthExpired()
        return renewLock.withLock {
            val current = token ?: throw DevTunnelsException.AuthExpired()
            if (current !== held) return@withLock current
            // Not cancellable: once GitHub has been asked, the old refresh token is spent and the new pair must be saved.
            withContext(NonCancellable) {
                val result = try {
                    auth.refresh(refresh)
                } catch (e: IOException) {
                    note("renewal not attempted: ${e.javaClass.simpleName}")
                    return@withContext if (usable) held else throw e
                } catch (e: GitHubAuthException) {
                    return@withContext if (usable) held else throw IOException("Could not renew the GitHub sign-in", e)
                }
                when (result) {
                    is RefreshResult.Renewed -> {
                        note("sign-in renewed")
                        adopt(result.token)
                    }
                    is RefreshResult.Rejected -> {
                        note("renewal refused: ${result.error}")
                        refusedRefresh = refresh
                        if (usable) held else throw DevTunnelsException.AuthExpired()
                    }
                }
            }
        }
    }

    /** Makes a renewed sign-in the one in use, and keeps it at rest: a storage error loses only the saved copy, not this session. */
    private suspend fun adopt(renewed: GitHubToken): GitHubToken {
        token = renewed
        try {
            vault.save(renewed)
        } catch (_: IOException) {
            // The in-memory token still works; the next launch will find the old pair and sign in again if that was already spent.
        } catch (_: VaultCryptoException) {
            // As above.
        }
        // Not after a sign-out, which has taken the token (and the catalog's expiry with it) while this was renewing.
        _hosts.update { if (token === renewed) it.copy(tokenExpiresAt = renewed.expiresAt, renews = renewed.refresh != null) else it }
        return renewed
    }

    /** Shows the host list and loads it. With [from], only that state is replaced; if the screen has moved on, the change that moved it stands. */
    @Synchronized
    private fun showHosts(from: AppState? = null) {
        markLoading(keepList = false)
        if (from == null) _state.value = AppState.Hosts else if (!_state.compareAndSet(from, AppState.Hosts)) return
        launchHostsLoad()
    }

    /** Puts the catalog into the shape of a load that is about to start; with [keepList], a list already loaded stays in it meanwhile. */
    private fun markLoading(keepList: Boolean) {
        val signedIn = token
        _hosts.update {
            if (signedIn == null) {
                // Not signed in: the way here is a host added by address, which is listed on its own.
                HostCatalog(HostList.NotSignedIn, directEntries())
            } else {
                val shown = if (keepList && (it.list is HostList.Loaded || it.list is HostList.Empty)) it.list else HostList.Loading
                HostCatalog(shown, directEntries(), signedIn.expiresAt, signedIn.refresh != null, autoConnectPending.get())
            }
        }
    }

    private fun launchHostsLoad() {
        if (token == null) return
        hostsJob?.cancel()
        hostsJob = scope.launch { loadHosts() }
    }

    private suspend fun loadHosts() {
        var attempt = 0
        while (true) {
            val list = try {
                val tunnels = devTunnels.listAgentHostTunnels(freshToken().access)
                val last = try {
                    lastHost.get()
                } catch (_: IOException) {
                    null
                }
                val hosts = ordered(tunnels, last)
                if (hosts.isEmpty()) HostList.Empty else HostList.Loaded(hosts)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DevTunnelsException.AuthExpired) {
                // A direct host needs no GitHub sign-in, so one that is connected stays up; its screen is not the sign-in's to take.
                val hasDirect = (_state.value as? AppState.Connected)?.host is HostRef.Direct || _connectedHosts.value.any { it.host is HostRef.Direct }
                if (hasDirect) dropSignInKeepingConnection() else endSignIn("GitHub no longer accepts this sign-in. Sign in again.")
                return
            } catch (e: DevTunnelsException.Forbidden) {
                HostList.Failed("GitHub refused access (403). If your organization uses SSO, authorize this app for it, then try again.")
            } catch (e: DevTunnelsException) {
                HostList.Failed(e.message ?: "The Dev Tunnels service returned an unexpected response.")
            } catch (e: IOException) {
                HostList.Failed("Could not reach the Dev Tunnels service. Check the connection and try again.")
            } catch (e: Exception) {
                HostList.Failed("Could not load your hosts (${e.javaClass.simpleName}).")
            }
            val direct = directEntries()
            // While the cold start still needs the tunnel list to reach the last host, a failed load stays pending and is retried with backoff.
            val retryAutoConnect = list is HostList.Failed && autoConnectPending.get() && _state.value is AppState.Hosts && direct.none { it.isLastUsed }
            if (!retryAutoConnect) {
                // Taken by the first list that loads (or when a direct host is the last used), so a later refresh or a return to the list never connects on its own.
                val connectNow = autoConnectPending.getAndSet(false) && _state.value is AppState.Hosts
                // Kept whether or not a host got connected meanwhile, since the list is read while connected; not after a sign-out, which emptied the catalog.
                // A refresh that fails leaves the list that was on show (it was good a moment ago); only a first load says why it failed.
                _hosts.update {
                    if (token != null) {
                        it.copy(list = if (list is HostList.Failed && it.list is HostList.Loaded) it.list else list, direct = direct, autoConnectPending = connectNow)
                    } else {
                        it
                    }
                }
                if (connectNow && !connectToLastHost()) {
                    _hosts.update { it.copy(autoConnectPending = false) }
                }
                return
            }
            _hosts.update {
                if (token != null) {
                    it.copy(list = if (it.list is HostList.Loaded) it.list else list, direct = direct, autoConnectPending = autoConnectPending.get())
                } else {
                    it
                }
            }
            attempt++
            delay(backoff.delayMs(attempt))
            if (!autoConnectPending.get() || _state.value !is AppState.Hosts || token == null) return
        }
    }

    /** The tunnel hosts as the catalog lists them: the last used first, then the online ones, then by name. */
    private fun ordered(tunnels: List<TunnelInfo>, last: String?): List<HostEntry> =
        tunnels
            .sortedWith(compareByDescending<TunnelInfo> { it.tunnelId == last }.thenByDescending { it.isOnline }.thenBy { it.name.lowercase() })
            .map { HostEntry(it, isLastUsed = it.tunnelId == last) }

    /** Puts the catalog's last-used marks and order in step with [lastId], after a switch that did not pass through the host list. */
    private fun republishLastUsed() {
        _hosts.update { catalog ->
            val list = catalog.list
            val remarked = if (list is HostList.Loaded) HostList.Loaded(ordered(list.hosts.map { it.tunnel }, lastId)) else list
            catalog.copy(list = remarked, direct = directEntries(), autoConnectPending = autoConnectPending.get())
        }
    }

    @Synchronized
    private fun launchWatch() {
        watchJob?.cancel()
        val watched = allLiveSessions()
        if (watched.isEmpty()) return
        watchJob = scope.launch {
            watched.forEach { session ->
                launch {
                    session.connection.collect { connection ->
                        val stillCurrent = allLiveSessions().any { it === session }
                        if (stillCurrent && connection is ConnState.Failed && connection.cause is DevTunnelsException.AuthExpired) {
                            val hasDirect = _connectedHosts.value.any { it.host is HostRef.Direct }
                            if (hasDirect) dropSignInKeepingConnection() else endSignIn("GitHub no longer accepts this sign-in. Sign in again.")
                        }
                    }
                }
            }
        }
    }

    /**
     * Back to the sign-in screen: stops any live session, forgets the token in memory and on disk. It is often called by the very job
     * it cancels (the host list that was refused, the watch of a connection that lost its sign-in), so it must not be cancellable:
     * it would stop at the first disk write, leave the screen as it was and the expired token in memory.
     */
    private suspend fun endSignIn(notice: String?, offerRevoke: Boolean = false) = withContext(NonCancellable) {
        cancelSignInJob()
        cancelHostsJob()
        cancelWatchJob()
        forgetExpansions()
        // Stopped without saving its session list: the cache is wiped below, and nothing may write it again after that.
        val activeSessions = allLiveSessions()
        _connectedHosts.value = emptyList()
        activeSessions.forEach { it.stopAndForget() }
        cancelDiffJob()
        interaction.set(false)
        autoConnectPending.set(false)
        token = null
        refusedRefresh = null
        safeClear()
        // The host list stayed tappable while the disk was written, so a connection may have been made meanwhile: it is displaced, and stopped.
        val displaced = _state.getAndUpdate { AppState.SignedOut(SignInStep.Idle, notice, offerRevoke) }
        val displacedHosts = _connectedHosts.getAndUpdate { emptyList() }
        // The account's tunnel hosts are not kept past its sign-in; the hosts added by address are.
        _hosts.value = HostCatalog(HostList.NotSignedIn, directEntries())
        (displaced as? AppState.Connected)?.session?.stopAndForget()
        displacedHosts.forEach { it.session.stopAndForget() }
        // After every session is stopped, so none is left to write it again; no session title stays on the phone.
        safeClearCache()
        cancelWatchJob()
    }

    /**
     * A refused GitHub sign-in while a direct host is connected: as [endSignIn] it forgets the token and the account's tunnel hosts, but
     * leaves the direct connection(s) and their saved session list alone, since those hosts never used the sign-in. The sign-in screen is not shown.
     */
    private suspend fun dropSignInKeepingConnection() = withContext(NonCancellable) {
        cancelHostsJob()
        interaction.set(false)
        autoConnectPending.set(false)
        token = null
        refusedRefresh = null
        safeClear()
        val tunnels = _connectedHosts.value.filter { it.host is HostRef.Tunnel }
        val directs = _connectedHosts.value.filter { it.host is HostRef.Direct }
        tunnels.forEach { it.session.stopAndForget() }
        _connectedHosts.value = directs
        val current = _state.value as? AppState.Connected
        if (current != null && current.host is HostRef.Tunnel && directs.isNotEmpty()) {
            val next = directs.last()
            _state.compareAndSet(current, AppState.Connected(next.host, next.session))
        }
        _hosts.value = HostCatalog(HostList.NotSignedIn, directEntries())
    }

    @Synchronized
    private fun cancelDiffJob() {
        diffJob?.cancel()
        diffJob = null
    }

    /** A storage error while wiping must not keep the user signed in: the in-memory token is already gone. */
    private suspend fun safeClear() {
        try {
            vault.clear()
        } catch (_: IOException) {
            // The next launch reads the old blob; if it is still valid the user is simply signed in again, which is safe.
        }
    }

    /** As [safeClear], for the saved session lists: a list that cannot be removed now is only a stale convenience, and is replaced or dropped at the next load. */
    private suspend fun safeClearCache() {
        try {
            sessionCache?.clear()
        } catch (_: IOException) {
            // The list is useless without the key the vault wipe has just destroyed.
        }
    }

    @Synchronized
    private fun cancelSignInJob() {
        signInJob?.cancel()
        signInJob = null
    }

    @Synchronized
    private fun cancelHostsJob() {
        hostsJob?.cancel()
        hostsJob = null
    }

    @Synchronized
    private fun cancelWatchJob() {
        watchJob?.cancel()
        watchJob = null
    }

    @Synchronized
    private fun expansionFor(chatUri: String): ExpansionState {
        expansions[chatUri]?.let { return it }
        // A phone user opens a handful of chats; the cap only stops a long session from growing this without bound.
        if (expansions.size >= MAX_REMEMBERED_CHATS) expansions.remove(expansions.keys.first())
        return ExpansionState().also { expansions[chatUri] = it }
    }

    @Synchronized
    private fun forgetExpansions() {
        expansions.clear()
    }

    private companion object {
        const val MAX_REMEMBERED_CHATS = 20

        /** How many chats a session keeps subscribed besides the one on screen. */
        const val MAX_WARM_CHATS = 3

        /** How many subagent transcripts may be opened on top of one another. */
        const val MAX_SUBAGENT_DEPTH = 4

        /** How long a notification button waits for the chat's first snapshot before giving up. */
        const val CHAT_LOAD_MS = 8_000L

        /** How long a background caller waits for a connection to be made or restored before it gives up. */
        const val CONNECT_MS = 10_000L

        /** How long a notification button may spend reaching the host and reading the chat in all, a little under the 12 s its receiver is held open. */
        const val PREPARE_MS = 11_000L

        /** No channel address this app opens is longer than this. */
        const val MAX_CHANNEL_URI = 512
    }
}
