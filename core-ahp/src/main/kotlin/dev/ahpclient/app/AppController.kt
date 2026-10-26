package dev.ahpclient.app

import dev.ahpclient.auth.DeviceFlowResult
import dev.ahpclient.auth.GitHubAuthException
import dev.ahpclient.auth.GitHubDeviceAuth
import dev.ahpclient.auth.GitHubToken
import dev.ahpclient.auth.RefreshResult
import dev.ahpclient.auth.Secret
import dev.ahpclient.security.TokenVault
import dev.ahpclient.security.VaultCryptoException
import dev.ahpclient.security.VaultLoad
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.CustomAgents
import dev.ahpclient.state.ApprovalGate
import dev.ahpclient.state.ChatConfig
import dev.ahpclient.state.RequestRead
import dev.ahpclient.state.ApprovalTarget
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
import dev.ahpclient.tunnel.DevTunnelsClient
import dev.ahpclient.tunnel.DevTunnelsException
import dev.ahpclient.tunnel.TunnelInfo
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
) {
    private val _state = MutableStateFlow<AppState>(AppState.Restoring)
    val state: StateFlow<AppState> = _state.asStateFlow()

    /** Whether the user has allowed this app to act for them. Off until they turn it on, and off again after signing out. */
    val interactionAllowed: StateFlow<Boolean> get() = interaction.allowed

    /** False on a phone that cannot run the device check in front of approvals (Android before 11): the switch is shown but cannot be turned on. */
    val interactionSupported: Boolean get() = interaction.supported

    /** Whether the user has also allowed this app to look after sessions (mark read, archive, start and remove). Only ever on while [interactionAllowed] is. */
    val managementAllowed: StateFlow<Boolean> get() = interaction.managementAllowed

    private fun connectedSession(): HostSession? = (_state.value as? AppState.Connected)?.session

    private fun liveSession(): HostSession = connectedSession() ?: throw IOException("Not connected to the host")

    /** Looks after sessions and chats on the live host. Every request checks the tier, the target and, where it matters, the device check first. */
    val manager = SessionManager(
        allowed = { connectedSession()?.canManage?.value == true },
        sessions = { connectedSession()?.root?.sessions?.value.orEmpty() },
        agents = { connectedSession()?.root?.root?.value?.agents.orEmpty() },
        sessionState = { uri -> (_state.value as? AppState.Connected)?.open?.takeIf { it.uri == uri }?.store?.state?.value },
        dispatch = { channel, action -> liveSession().dispatch(channel, action) },
        commands = object : ManagementCommands {
            override suspend fun createSession(uri: String, provider: String?, workingDirectories: List<String>?, config: Map<String, JsonElement>?) =
                liveSession().createSession(uri, provider, workingDirectories, config)

            override suspend fun resolveSessionConfig(provider: String, workingDirectory: String, config: Map<String, JsonElement>?) =
                liveSession().resolveSessionConfig(provider, workingDirectory, config)

            override suspend fun createChat(sessionUri: String, chatUri: String) = liveSession().createChat(sessionUri, chatUri)

            override suspend fun disposeSession(uri: String) = liveSession().disposeSession(uri)

            override suspend fun disposeChat(uri: String) = liveSession().disposeChat(uri)
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

    /**
     * Allows or forbids this app to look after sessions (mark read, archive, start and remove) as well, and reconnects the live
     * session so the new tier applies now. Refused, with nothing changed, unless the app may already act for the user.
     *
     * @return true when the choice was saved. Turning it off takes effect either way.
     */
    suspend fun setManagement(on: Boolean): Boolean {
        val saved = interaction.setManagement(on)
        if (interaction.managementAllowed.value == on) (_state.value as? AppState.Connected)?.session?.restart()
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
        val session = sessions.create(tunnel) { freshToken().access }
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

    /**
     * Opens [uri], leaving the session that is open, if any, so the side menu can go from one session to another. Unlike [focusSession]
     * it does not need the host to list the session yet: one just started is not listed until the host says so.
     */
    @Synchronized
    fun showSession(uri: String) {
        val current = _state.value as? AppState.Connected ?: return
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
    )

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
     */
    suspend fun decideToolCall(chatUri: String, toolCallId: String, approve: Boolean, optionId: String? = null): InteractResult {
        val session = (_state.value as? AppState.Connected)?.session ?: return InteractResult.NotAllowed
        if (chatUri.isBlank() || chatUri.length > ApprovalTarget.MAX_CHAT_URI) return InteractResult.NotAllowed
        if (optionId != null && (optionId.isBlank() || optionId.length > ApprovalTarget.MAX_OPTION_ID)) return InteractResult.NotAllowed
        val store = session.openChat(chatUri)
        try {
            withTimeoutOrNull(CHAT_LOAD_MS) { store.state.first { it != null } }
                ?: return InteractResult.NotSent(IOException("The chat did not load in time"))
            val interactor = interactorFor(session, chatUri, store)
            return if (approve) interactor.approve(toolCallId, optionId) else interactor.deny(toolCallId, optionId)
        } finally {
            session.closeChat(chatUri)
        }
    }

    /** What a session that needs the person is waiting for, read once and closed again; null when not connected, gone, or nothing is pending. */
    suspend fun readWaiting(resource: String): RequestRead? {
        val session = (_state.value as? AppState.Connected)?.session ?: return null
        if (resource.isBlank() || resource.length > ApprovalTarget.MAX_CHAT_URI) return null
        return WaitingRequests.read(session, resource)
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

    /** Leaves the live host and returns to the host list. */
    fun disconnect() {
        val connected = _state.value as? AppState.Connected ?: return
        cancelWatchJob()
        forgetExpansions()
        cancelDiffJob()
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
                // An expired token that can be renewed is kept: it is renewed when it is first needed, which is when the hosts are listed.
                if (saved.refresh == null && saved.expiresAt?.let { !now().isBefore(it) } == true) {
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
        _state.update { if (it is AppState.Hosts) it.copy(tokenExpiresAt = renewed.expiresAt, renews = renewed.refresh != null) else it }
        return renewed
    }
    @Synchronized
    private fun showHosts() {
        val signedIn = token
        if (signedIn == null) {
            _state.value = AppState.SignedOut(SignInStep.Idle, null)
            return
        }
        _state.value = AppState.Hosts(HostList.Loading, signedIn.expiresAt, renews = signedIn.refresh != null)
        hostsJob?.cancel()
        hostsJob = scope.launch { loadHosts() }
    }

    private suspend fun loadHosts() {
        val list = try {
            val tunnels = devTunnels.listAgentHostTunnels(freshToken().access)
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
        _state.update { if (it is AppState.Hosts) it.copy(list = list) else it }
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
        (_state.value as? AppState.Connected)?.session?.stop()
        cancelDiffJob()
        interaction.set(false)
        token = null
        refusedRefresh = null
        safeClear()
        _state.value = AppState.SignedOut(SignInStep.Idle, notice, offerRevoke)
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

        /** No channel address this app opens is longer than this. */
        const val MAX_CHANNEL_URI = 512
    }
}
