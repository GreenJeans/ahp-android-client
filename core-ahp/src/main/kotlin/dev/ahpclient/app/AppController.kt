package dev.ahpclient.app

import dev.ahpclient.auth.DeviceFlowResult
import dev.ahpclient.auth.GitHubAuthException
import dev.ahpclient.auth.GitHubDeviceAuth
import dev.ahpclient.auth.GitHubToken
import dev.ahpclient.security.TokenVault
import dev.ahpclient.security.VaultCryptoException
import dev.ahpclient.security.VaultLoad
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.ApprovalGate
import dev.ahpclient.state.ChatInteractor
import dev.ahpclient.state.ExpansionState
import dev.ahpclient.state.HostSession
import dev.ahpclient.state.SubagentLinks
import dev.ahpclient.tunnel.DevTunnelsClient
import dev.ahpclient.tunnel.DevTunnelsException
import dev.ahpclient.tunnel.TunnelInfo
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything between launching the app and looking at a host: restore the saved sign-in, sign in with GitHub, list the
 * user's hosts, connect to one. Screens render [state] and call the methods; nothing else decides what is shown.
 *
 * Rules worth knowing:
 * - A sign-in GitHub or the Dev Tunnels service no longer accepts (401) wipes the saved token and goes back to the
 *   sign-in screen with a notice, from any point, including mid-session.
 * - The token lives in memory only while signed in, and in the [TokenVault] at rest. It never appears in [state].
 * - Failures are shown as messages here, never thrown to the caller, because the callers are UI event handlers.
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
) {
    private val _state = MutableStateFlow<AppState>(AppState.Restoring)
    val state: StateFlow<AppState> = _state.asStateFlow()

    /** Whether the user has allowed this app to act for them. Off until they turn it on, and off again after signing out. */
    val interactionAllowed: StateFlow<Boolean> get() = interaction.allowed

    /** False on a phone that cannot run the device check in front of approvals (Android before 11): the switch is shown but cannot be turned on. */
    val interactionSupported: Boolean get() = interaction.supported

    @Volatile
    private var token: GitHubToken? = null
    private var signInJob: Job? = null
    private var hostsJob: Job? = null
    private var watchJob: Job? = null

    /** Which blocks the user opened in each chat, kept while connected so leaving a chat and coming back finds them as they were. */
    private val expansions = LinkedHashMap<String, ExpansionState>()

    fun start() {
        // The saved choice is read before anything can connect, so the first connection already uses the right tier.
        scope.launch {
            interaction.load()
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
        val saved = interaction.set(on)
        // Reconnect only when the permission really changed: a turn-on that could not be saved changed nothing.
        if (interaction.allowed.value == on) (_state.value as? AppState.Connected)?.session?.restart()
        return saved
    }

    fun signIn() {
        val current = _state.value
        if (current !is AppState.SignedOut) return
        if (current.step is SignInStep.RequestingCode || current.step is SignInStep.AwaitingApproval) return
        launchSignIn()
    }

    fun cancelSignIn() {
        cancelSignInJob()
        _state.update { if (it is AppState.SignedOut) AppState.SignedOut(SignInStep.Idle, null) else it }
    }

    fun refreshHosts() {
        if (_state.value is AppState.Hosts) showHosts()
    }

    fun connect(tunnel: TunnelInfo) {
        if (_state.value !is AppState.Hosts) return
        cancelHostsJob()
        val session = sessions.create(tunnel) { token?.access ?: throw DevTunnelsException.AuthExpired() }
        session.start()
        _state.value = AppState.Connected(tunnel, session)
        scope.launch {
            try {
                lastHost.set(tunnel.tunnelId)
            } catch (_: IOException) {
                // Remembering the last host is a convenience; failing to save it must not disturb a live connection.
            }
        }
        launchWatch(session)
    }

    /** Opens the detail for [uri]. Ignored unless connected with no detail open, so a double tap cannot subscribe twice. */
    @Synchronized
    fun openSession(uri: String) {
        val current = _state.value as? AppState.Connected ?: return
        if (current.open != null) return
        val store = current.session.openSession(uri)
        if (!_state.compareAndSet(current, current.copy(open = OpenSession(uri, store)))) current.session.closeSession(uri)
    }

    /** Opens the session a notification points at, replacing any detail already open. Ignores a session the host does not list. */
    @Synchronized
    fun focusSession(uri: String) {
        val current = _state.value as? AppState.Connected ?: return
        if (current.session.root.sessions.value.none { it.resource == uri }) return
        if (current.open?.uri == uri) return
        if (current.open != null) closeSession()
        openSession(uri)
    }

    /** Leaves the open detail and stays connected. */
    @Synchronized
    fun closeSession() {
        val current = _state.value as? AppState.Connected ?: return
        val open = current.open ?: return
        if (_state.compareAndSet(current, current.copy(open = null))) {
            // Every chat of the chain (a subagent's transcript and the ones it was opened from) holds a subscription.
            generateSequence(open.chat) { it.parent }.forEach { current.session.closeChat(it.uri) }
            current.session.closeSession(open.uri)
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
        val interactor = ChatInteractor(
            chatUri = uri,
            chat = { store.state.value },
            canInteract = { session.canInteract.value },
            dispatch = session::dispatch,
            gate = approvalGate,
        )
        return OpenChat(uri, store, expansionFor(uri), interactor, parent)
    }

    /** Leaves the open transcript and returns to where it was opened from: the chat above it for a subagent's, else the session detail. */
    @Synchronized
    fun closeChat() {
        val current = _state.value as? AppState.Connected ?: return
        val open = current.open ?: return
        val chat = open.chat ?: return
        if (_state.compareAndSet(current, current.copy(open = open.copy(chat = chat.parent)))) current.session.closeChat(chat.uri)
    }

    /** Leaves the live host and returns to the host list. */
    fun disconnect() {
        val connected = _state.value as? AppState.Connected ?: return
        cancelWatchJob()
        forgetExpansions()
        connected.session.stop()
        showHosts()
    }

    fun signOut() {
        scope.launch { endSignIn(notice = null, offerRevoke = true) }
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
                if (saved.expiresAt?.let { !now().isBefore(it) } == true) {
                    safeClear()
                    _state.value = AppState.SignedOut(SignInStep.Idle, "Your GitHub sign-in expired. Sign in again.")
                } else {
                    token = saved
                    showHosts()
                }
            }
            VaultLoad.Absent -> _state.value = AppState.SignedOut(SignInStep.Idle, null)
            is VaultLoad.Unreadable -> _state.value = AppState.SignedOut(SignInStep.Idle, "The saved sign-in could not be read and was removed. Sign in again.")
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

    @Synchronized
    private fun showHosts() {
        val signedIn = token
        if (signedIn == null) {
            _state.value = AppState.SignedOut(SignInStep.Idle, null)
            return
        }
        _state.value = AppState.Hosts(HostList.Loading, signedIn.expiresAt)
        hostsJob?.cancel()
        hostsJob = scope.launch { loadHosts(signedIn) }
    }

    private suspend fun loadHosts(signedIn: GitHubToken) {
        val list = try {
            val tunnels = devTunnels.listAgentHostTunnels(signedIn.access)
            val last = try {
                lastHost.get()
            } catch (_: IOException) {
                null
            }
            val hosts = tunnels
                .sortedWith(compareByDescending<TunnelInfo> { it.tunnelId == last }.thenByDescending { it.isOnline }.thenBy { it.name.lowercase() })
                .map { HostEntry(it, isLastUsed = it.tunnelId == last) }
            if (hosts.isEmpty()) HostList.Empty else HostList.Loaded(hosts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DevTunnelsException.AuthExpired) {
            endSignIn("GitHub no longer accepts this sign-in. Sign in again.")
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
        // Only update while still on the host list: the user may have connected or signed out while this was loading.
        _state.update { if (it is AppState.Hosts) AppState.Hosts(list, it.tokenExpiresAt) else it }
    }

    @Synchronized
    private fun launchWatch(session: HostSession) {
        watchJob?.cancel()
        watchJob = scope.launch {
            session.connection.collect { connection ->
                val stillCurrent = (_state.value as? AppState.Connected)?.session === session
                if (stillCurrent && connection is ConnState.Failed && connection.cause is DevTunnelsException.AuthExpired) {
                    endSignIn("GitHub no longer accepts this sign-in. Sign in again.")
                }
            }
        }
    }

    /** Back to the sign-in screen: stops any live session, forgets the token in memory and on disk. */
    private suspend fun endSignIn(notice: String?, offerRevoke: Boolean = false) {
        cancelSignInJob()
        cancelHostsJob()
        cancelWatchJob()
        forgetExpansions()
        (_state.value as? AppState.Connected)?.session?.stop()
        interaction.set(false)
        token = null
        safeClear()
        _state.value = AppState.SignedOut(SignInStep.Idle, notice, offerRevoke)
    }

    /** A storage error while wiping must not keep the user signed in: the in-memory token is already gone. */
    private suspend fun safeClear() {
        try {
            vault.clear()
        } catch (_: IOException) {
            // The next launch reads the old blob; if it is still valid the user is simply signed in again, which is safe.
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

        /** How many subagent transcripts may be opened on top of one another. */
        const val MAX_SUBAGENT_DEPTH = 4
    }
}
