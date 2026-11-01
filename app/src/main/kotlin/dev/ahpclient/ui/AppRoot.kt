package dev.ahpclient.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.microsoft.agenthostprotocol.generated.SessionState
import com.microsoft.agenthostprotocol.generated.SessionSummary
import dev.ahpclient.ai.GeminiNanoQuickReplyProvider
import dev.ahpclient.app.AppController
import dev.ahpclient.app.AppState
import dev.ahpclient.app.ConnectedHost
import dev.ahpclient.app.HostCatalog
import dev.ahpclient.app.HostRef
import dev.ahpclient.app.OpenChat
import dev.ahpclient.app.SignInStep
import dev.ahpclient.data.UiSettings
import dev.ahpclient.state.ChangeStats
import dev.ahpclient.state.ChatConfig
import dev.ahpclient.state.ChatInteractor
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.CustomAgents
import dev.ahpclient.state.CustomizationCategory
import dev.ahpclient.state.HostFileTarget
import dev.ahpclient.state.SlashCommands
import dev.ahpclient.ui.chat.RichModalRequest
import com.microsoft.agenthostprotocol.generated.RootState
import dev.ahpclient.state.HostSession
import dev.ahpclient.state.HostSessionEntry
import dev.ahpclient.state.Inbox
import dev.ahpclient.state.InboxReader
import dev.ahpclient.state.ManageResult
import dev.ahpclient.state.ModelOptions
import dev.ahpclient.ui.chat.ChatActionsUi
import dev.ahpclient.ui.chat.ChatPane
import dev.ahpclient.ui.chat.ChatScreen
import dev.ahpclient.ui.chat.rememberTranscript
import dev.ahpclient.ui.theme.AhpTheme
import kotlinx.coroutines.launch

/** Picks the screen for the current [AppState]. Holds no state of its own beyond what the controller and the settings expose. */
@Composable
fun AppRoot(
    controller: AppController,
    ui: UiSettings,
    onOpenGitHub: (String) -> Unit,
    onCopyCode: (String) -> Unit,
    monitorEnabled: Boolean = false,
    notificationsAllowed: Boolean = true,
    onMonitorChange: (Boolean) -> Unit = {},
    alerts: FinishedAlertsUi = FinishedAlertsUi(),
    /** How many notification taps asked for a session; each one closes the side menu, even when that session is already open. */
    sessionRequests: Int = 0,
    quickReplyProvider: GeminiNanoQuickReplyProvider? = null,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val catalog by controller.hosts.collectAsStateWithLifecycle()
    val theme by ui.theme.collectAsStateWithLifecycle()
    BackHandler(enabled = (state as? AppState.SignedOut)?.step is SignInStep.AwaitingApproval || catalog.signInStep is SignInStep.AwaitingApproval) { controller.cancelSignIn() }
    AhpTheme(theme) {
        // Every screen but the loading one brings its own bar and applies the system-bar insets itself.
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val current = state) {
                AppState.Restoring -> Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).testTag("restoring"), contentAlignment = Alignment.Center) { Busy("Loading…") }
                is AppState.SignedOut -> SignInScreen(current, controller::signIn, controller::cancelSignIn, onOpenGitHub, onCopyCode, onUseDirect = controller::useDirectHosts)
                // One call for both, so that going from the host list to a host (and back) keeps the menu, and the sheet over it, as they are.
                AppState.Hosts, is AppState.Connected -> Shell(controller, ui, catalog, current as? AppState.Connected, monitorEnabled, notificationsAllowed, onMonitorChange, alerts, sessionRequests, quickReplyProvider, onOpenGitHub, onCopyCode)
            }
        }
    }
}

/** The screens a signed-in person moves between: the main page (the open session, or nothing but the side menu) with the side menu, settings, and the inbox. */
private enum class Page { MAIN, SETTINGS, INBOX }

/**
 * Everything once signed in: a side menu (the host, the inbox, a new session, Settings, the sessions) over the main page, which is the
 * open session. With no session the menu is the home page: open, and held open, over an empty background. The host row of the menu
 * opens the hosts in a sheet over it; Settings and the inbox are screens of their own, left by Back to the menu. [connected] is null
 * while no host is connected, and [hosts] is what can be connected to either way.
 */
@Composable
private fun Shell(
    controller: AppController,
    ui: UiSettings,
    hosts: HostCatalog,
    connected: AppState.Connected?,
    monitorEnabled: Boolean,
    notificationsAllowed: Boolean,
    onMonitorChange: (Boolean) -> Unit,
    alerts: FinishedAlertsUi,
    sessionRequests: Int,
    quickReplyProvider: GeminiNanoQuickReplyProvider? = null,
    onOpenGitHub: (String) -> Unit = {},
    onCopyCode: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val proUnlocked by controller.proUnlocked.collectAsStateWithLifecycle()
    val interactionAllowed by controller.interactionAllowed.collectAsStateWithLifecycle()
    val managementAllowed by controller.managementAllowed.collectAsStateWithLifecycle()
    val textScale by ui.textScale.collectAsStateWithLifecycle()
    val theme by ui.theme.collectAsStateWithLifecycle()
    val keepAwake by ui.keepAwake.collectAsStateWithLifecycle()
    val quickReplyMode by ui.quickReplyMode.collectAsStateWithLifecycle()
    val googleAiConfig by ui.googleAiConfig.collectAsStateWithLifecycle()
    val nanoStatus = quickReplyProvider?.status?.collectAsStateWithLifecycle()?.value
    val savedFilter by ui.sessionFilter.collectAsStateWithLifecycle()
    val connectedHosts by controller.connectedHosts.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    var hostSheet by rememberSaveable { mutableStateOf(false) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    // The label of the changeset being opened, kept here so the screen can name it; the host's own title is not needed.
    var changesetTitle by rememberSaveable { mutableStateOf<String?>(null) }
    // Kept here, above the page switch, so the list keeps its scroll position while the menu is closed and opened.
    val listState = rememberLazyListState()

    val open = connected?.open
    // A session's own chat is part of the session page, left by leaving the session. Only a subagent's transcript is a screen over it.
    val subagentOpen = open?.chat?.parent != null
    val changesetOpen = open?.changeset != null
    val diffOpen = open?.changeset?.diff != null
    val terminalOpen = open?.terminal != null
    val overlayOpen = subagentOpen || changesetOpen || diffOpen || terminalOpen
    // The screens over a session. Back from the session itself, from Settings and the inbox, and from the menu is in HomeDrawer.
    BackHandler(enabled = subagentOpen && !terminalOpen && !changesetOpen) { controller.closeChat() }
    BackHandler(enabled = terminalOpen) { controller.closeTerminal() }
    BackHandler(enabled = changesetOpen && !diffOpen) { controller.closeChangeset() }
    BackHandler(enabled = diffOpen) { controller.closeDiff() }
    val menu = rememberHomeMenu(open?.uri, page != Page.MAIN)
    CloseMenuOnRequest(sessionRequests, menu)
    // A host that connects, or goes (removing the connected host from Settings), brings the menu back: Settings and the inbox are of the
    // host they were opened on.
    OnConnectionChange(connected != null) { if (page != Page.MAIN) page = Page.MAIN }
    OfferHostsOnce(connected != null, hosts) { hostSheet = true }

    val activeHostList = remember(connectedHosts, connected?.host, connected?.session) {
        if (connectedHosts.isNotEmpty()) {
            connectedHosts
        } else if (connected != null) {
            listOf(ConnectedHost(connected.host, connected.session))
        } else {
            emptyList()
        }
    }

    // When all connected hosts change completely, reset the transient search and archive toggle.
    var shownHostSet by rememberSaveable { mutableStateOf(activeHostList.map { it.host.id }.toSet()) }
    LaunchedEffect(activeHostList.map { it.host.id }.toSet()) {
        val currentIds = activeHostList.map { it.host.id }.toSet()
        if (currentIds.isEmpty() || (shownHostSet.isNotEmpty() && currentIds.intersect(shownHostSet).isEmpty())) {
            query = ""
            showArchived = false
            listState.scrollToItem(0)
        }
        shownHostSet = currentIds
    }

    val multiHostEntries = ArrayList<HostSessionEntry>()
    val hostConnections = ArrayList<Pair<HostRef, ConnState?>>(activeHostList.size)
    val rawManageTargets = ArrayList<HostManageTarget>(activeHostList.size)
    for (ch in activeHostList) {
        androidx.compose.runtime.key(ch.host.id) {
            val hostSessions by ch.session.root.sessions.collectAsStateWithLifecycle()
            val hostConn by ch.session.connection.collectAsStateWithLifecycle()
            val hostCanManage by ch.session.canManage.collectAsStateWithLifecycle()
            val hostStale by ch.session.root.stale.collectAsStateWithLifecycle()
            val hostRoot by ch.session.root.root.collectAsStateWithLifecycle()
            hostSessions.mapTo(multiHostEntries) { s -> HostSessionEntry(host = ch.host, summary = s) }
            hostConnections += ch.host to hostConn
            if (hostCanManage && !hostStale) {
                val hostManager = remember(controller, ch.session) { controller.managerFor(ch.session) }
                val target = remember(hostManager, ch.host.id, ch.host.name, hostRoot, hostSessions) {
                    HostManageTarget(
                        hostId = ch.host.id,
                        hostName = ch.host.name,
                        agents = hostManager.offeredAgents().map { AgentOption(it.provider, it.displayName) },
                        folders = hostManager.offeredFolders(),
                        newSession = hostManager::newSession,
                        sessionSettings = hostManager::sessionSettings,
                    )
                }
                rawManageTargets += target
            }
        }
    }
    val orderedManageTargets = remember(rawManageTargets, connected?.host?.id) {
        val focusedId = connected?.host?.id
        rawManageTargets.sortedByDescending { it.hostId == focusedId }
    }

    LaunchedEffect(proUnlocked, interactionAllowed, managementAllowed) {
        if (proUnlocked && interactionAllowed && !managementAllowed) {
            controller.setManagement(true)
        }
    }

    val connection = connected?.let { it.session.connection.collectAsStateWithLifecycle().value }
    val canManage = connected?.let { it.session.canManage.collectAsStateWithLifecycle().value } == true
    val rootState = connected?.let { it.session.root.root.collectAsStateWithLifecycle().value }
    val listed: List<SessionSummary> = connected?.let { it.session.root.sessions.collectAsStateWithLifecycle().value }.orEmpty()
    // The list is only what was saved last time until the host sends its own: to look at, with nothing in it to act on.
    val stale = connected?.let { it.session.root.stale.collectAsStateWithLifecycle().value } == true
    val now by rememberNow()
    // Where the person is needed, across all connected hosts: what waits on them, and what finished unread.
    val inbox = remember(multiHostEntries, now) { Inbox.fromEntries(multiHostEntries, now) }
    // Nothing to look after sessions with unless the connection is on the manage tier: no controls, not disabled ones. Nor from a stale list.
    val manageActions = if (canManage && !stale) {
        remember(controller.manager, rootState, listed, orderedManageTargets) {
            ManageActionsUi(
                agents = controller.manager.offeredAgents().map { AgentOption(it.provider, it.displayName) },
                folders = controller.manager.offeredFolders(),
                setRead = controller.manager::setRead,
                setArchived = controller.manager::setArchived,
                newChat = controller.manager::newChat,
                newSession = controller.manager::newSession,
                sessionSettings = controller.manager::sessionSettings,
                disposeSession = controller.manager::disposeSession,
                disposeChat = controller.manager::disposeChat,
                hosts = orderedManageTargets,
                setTitle = controller.manager::setTitle,
            )
        }
    } else {
        null
    }
    // With no session the menu is held open, and the session that opens closes it (HomeMenu); over a session it is closed here, so that
    // picking the session that is already open, or another, closes it too.
    val showSession = { uri: String -> menu.close(); controller.showSession(uri) }
    val showSessionOnHost = { hostId: String?, uri: String -> menu.close(); controller.showSession(uri, hostId) }
    val addHost: suspend (String, String, String) -> String? = { name, address, token ->
        when (val result = controller.addDirectHost(name, address, token)) {
            is AppController.AddHostResult.Added -> null
            is AppController.AddHostResult.Invalid -> result.reason
            AppController.AddHostResult.NotSaved -> "The host could not be saved on this phone."
        }
    }

    HomeDrawer(
        menu = menu,
        session = open?.uri,
        pageOpen = page != Page.MAIN,
        overlayOpen = overlayOpen,
        onClosePage = { page = Page.MAIN },
        onLeaveApp = { leaveApp(context) },
        drawerContent = {
            DrawerContent(
                host = connected?.host,
                connection = connection,
                sessions = listed,
                now = now,
                selected = open?.uri,
                showArchived = showArchived,
                onToggleArchived = { showArchived = !showArchived },
                query = query,
                onQueryChange = { query = it },
                listState = listState,
                manage = manageActions,
                watched = alerts.watch.sessions,
                onOpenHost = { hostSheet = true },
                onOpenSession = showSession,
                onOpenSettings = { page = Page.SETTINGS; menu.close() },
                onSessionCreated = showSession,
                inboxCount = if (connected != null) inbox.waiting.size else null,
                onOpenInbox = { page = Page.INBOX; menu.close() },
                stale = stale,
                entries = multiHostEntries,
                connectedHosts = activeHostList.map { it.host },
                filter = savedFilter,
                onFilterChange = ui::setSessionFilter,
                onOpenSessionOnHost = showSessionOnHost,
                onSessionCreatedOnHost = showSessionOnHost,
            )
        },
    ) {
        when (page) {
            Page.SETTINGS -> {
                if (quickReplyProvider != null) {
                    LaunchedEffect(quickReplyProvider) { quickReplyProvider.refreshStatus() }
                }
                SettingsScreen(
                    onBack = { page = Page.MAIN },
                    signedIn = controller.isSignedIn,
                    tokenExpiresAt = hosts.tokenExpiresAt,
                    renews = hosts.renews,
                    onSignIn = controller::signIn,
                    onSignOut = controller::signOut,
                    direct = hosts.direct,
                    onAddHost = addHost,
                    // Removing the host that is connected ends its connection first (the controller does that).
                    onRemoveHost = { host -> scope.launch { controller.removeDirectHost(host.id) } },
                    connectedId = connected?.host?.id,
                    monitorEnabled = monitorEnabled,
                    notificationsAllowed = notificationsAllowed,
                    onMonitorChange = onMonitorChange,
                    finishedAll = alerts.watch.all,
                    onFinishedAll = alerts.onAll,
                    interactionAllowed = interactionAllowed,
                    interactionSupported = controller.interactionSupported,
                    onInteractionChange = { on ->
                        scope.launch {
                            val saved = controller.setInteraction(on) && (!on || controller.setManagement(true))
                            if (!saved) Toast.makeText(context, "Could not save the setting", Toast.LENGTH_SHORT).show()
                        }
                    },
                    managementAllowed = managementAllowed,
                    appliesTo = controller.consentScope,
                    onManagementChange = {},
                    textScale = textScale,
                    onTextScale = ui::setTextScale,
                    theme = theme,
                    onTheme = ui::setTheme,
                    keepAwake = keepAwake,
                    onKeepAwake = ui::setKeepAwake,
                    quickReplyMode = quickReplyMode,
                    onQuickReplyMode = ui::setQuickReplyMode,
                    nanoStatus = nanoStatus,
                    onDownloadNano = quickReplyProvider?.let { { it.triggerDownload() } },
                    googleAiConfig = googleAiConfig,
                    onSaveGoogleAiConfig = quickReplyProvider?.let { provider ->
                        { enabled, modelName, systemPrompt, newApiKey, clearKey ->
                            provider.saveGoogleAiConfig(enabled, modelName, systemPrompt, newApiKey, clearKey)
                        }
                    },
                    onSaveExternalAiConfig = quickReplyProvider?.let { provider ->
                        { enabled, providerKind, endpointUrl, modelName, systemPrompt, newApiKey, clearKey ->
                            provider.saveGoogleAiConfig(
                                enabled = enabled,
                                modelName = modelName,
                                systemPrompt = systemPrompt,
                                newApiKey = newApiKey,
                                clearKey = clearKey,
                                providerKind = providerKind,
                                endpointUrl = endpointUrl,
                            )
                        }
                    },
                    onFetchAvailableModels = quickReplyProvider?.let { provider ->
                        { providerKind, endpointUrl, overrideApiKey ->
                            provider.fetchAvailableModels(providerKind, endpointUrl, overrideApiKey)
                        }
                    },
                    proUnlocked = proUnlocked,
                    onPurchasePro = controller::purchasePro,
                    onRestorePro = controller::restorePro,
                    onDebugProChange = if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                        { on -> scope.launch { controller.setProUnlocked(on) } }
                    } else null,
                )
            }
            Page.INBOX -> {
                val reader = remember(controller) { InboxReader(controller::readWaiting) }
                val details by reader.details.collectAsStateWithLifecycle()
                // Read again whenever a session starts waiting, stops waiting, or changes: what a card says it waits for must be what it waits for.
                // Not from a stale list: those sessions may be gone, and a read that came to nothing would be kept until the session changes.
                LaunchedEffect(stale, inbox.waiting.map { it.row.resource to it.stamp }) { if (!stale) reader.refresh(inbox.waiting) }
                InboxScreen(
                    inbox = inbox,
                    details = details,
                    onBack = { page = Page.MAIN },
                    onOpen = { uri ->
                        page = Page.MAIN
                        val targetHostId = (inbox.waiting + inbox.finished).firstOrNull { it.row.resource == uri }?.row?.hostId
                        controller.showSession(uri, targetHostId)
                    },
                    canDecide = interactionAllowed,
                    onDecide = { chat, call, approve, option -> controller.decideToolCall(chat, call, approve, option) },
                    manage = manageActions,
                    stale = stale,
                )
            }
            Page.MAIN -> if (connected != null && connection != null && open != null) {
                SessionPage(
                    controller, ui, connected, open, connection, rootState, canManage, manageActions, listed, changesetTitle,
                    onChangesetTitle = { changesetTitle = it }, onMenu = menu::open, alerts = alerts,
                    quickReplyProvider = quickReplyProvider,
                )
            } else {
                // The menu is the page here, held open over this strip of background; there is nothing to say behind it.
                Surface(modifier = Modifier.fillMaxSize().testTag("home_background"), color = MaterialTheme.colorScheme.background) {}
            }
        }
    }
    if (hostSheet) {
        HostSheet(
            catalog = hosts,
            connectedHost = connected?.host,
            connection = connection,
            onDismiss = { hostSheet = false },
            onRefresh = controller::refreshHosts,
            onSwitch = controller::switchHost,
            onDisconnect = controller::disconnect,
            // Settings is where hosts are managed; it opens over the menu as the gear does.
            onManageHosts = { page = Page.SETTINGS; menu.close() },
            onAddHost = addHost,
            onSignIn = controller::signIn,
            connectedHosts = hostConnections,
            onConnectHost = controller::connectHost,
            onDisconnectHost = controller::disconnectHost,
        )
    }
    if (hosts.signInStep !is SignInStep.Idle) {
        GitHubSignInDialog(
            step = hosts.signInStep,
            onRetry = controller::signIn,
            onCancel = controller::cancelSignIn,
            onOpenGitHub = onOpenGitHub,
            onCopyCode = onCopyCode,
        )
    }
}

/** The open session: its page (a bar, tabs, the chat), or the screen over it that a terminal, a set of changes or a subagent's transcript is. */
@Composable
private fun SessionPage(
    controller: AppController,
    ui: UiSettings,
    connected: AppState.Connected,
    open: dev.ahpclient.app.OpenSession,
    connection: ConnState,
    rootState: Any?,
    canManage: Boolean,
    manageActions: ManageActionsUi?,
    listed: List<SessionSummary>,
    changesetTitle: String?,
    onChangesetTitle: (String?) -> Unit,
    onMenu: () -> Unit,
    alerts: FinishedAlertsUi,
    quickReplyProvider: GeminiNanoQuickReplyProvider? = null,
) {
    val chat = open.chat
    val sessionState by open.store.state.collectAsStateWithLifecycle()
    val chatMutable by open.store.chatMutableKeys.collectAsStateWithLifecycle()
    val changeset = open.changeset
    val terminal = open.terminal
    if (terminal != null) {
        val terminalState by terminal.store.state.collectAsStateWithLifecycle()
        val terminalError by terminal.store.error.collectAsStateWithLifecycle()
        TerminalScreen(terminalState, terminalError, connection, controller::closeTerminal)
    } else if (changeset != null) {
        val changesetState by changeset.store.state.collectAsStateWithLifecycle()
        val changesetError by changeset.store.error.collectAsStateWithLifecycle()
        val diff = changeset.diff
        if (diff != null) {
            val diffLoad by diff.load.collectAsStateWithLifecycle()
            FileDiffScreen(path = diff.path, load = diffLoad, connection = connection, onBack = controller::closeDiff)
        } else {
            ChangesetScreen(
                title = changesetTitle ?: "Changes",
                state = changesetState,
                error = changesetError,
                connection = connection,
                onBack = controller::closeChangeset,
                onSetReviewed = if (canManage && changeset.reviewable) controller::setFileReviewed else null,
                onOpenFile = controller::openDiff,
                onInvokeOperation = if (canManage) controller::invokeChangesetOperation else null,
            )
        }
    } else if (chat != null && chat.parent != null) {
        // A subagent's transcript is a screen of its own over the session.
        OpenChatView(open.uri, chat, connected.session, connection, rootState, sessionState, chatMutable, canManage, controller, ui, screen = true, quickReplyProvider = quickReplyProvider, modifier = Modifier)
    } else {
        val error by open.store.error.collectAsStateWithLifecycle()
        val now by rememberNow()
        val context = LocalContext.current
        val summary = remember(listed, open.uri) { listed.firstOrNull { it.resource == open.uri } }
        val changes = remember(summary) { ChangeStats.of(summary?.changes) }
        var requestedChat by remember { mutableStateOf<String?>(null) }
        var prefillText by rememberSaveable { mutableStateOf<String?>(null) }
        var agentToUse by rememberSaveable { mutableStateOf<String?>(null) }
        var externalRichRequest by remember { mutableStateOf<RichModalRequest?>(null) }
        var openCustomizationsRequest by remember { mutableStateOf<Pair<CustomizationCategory?, String>?>(null) }
        SessionScreen(
            state = sessionState,
            error = error,
            connection = connection,
            now = now,
            onBack = controller::closeSession,
            onMenu = onMenu,
            changes = changes,
            shownChat = chat?.uri,
            onSelectChat = controller::selectChat,
            manage = manageActions,
            sessionUri = open.uri,
            onRemoved = controller::closeSession,
            onOpenChangeset = { uri, label -> onChangesetTitle(label); controller.openChangeset(uri) },
            alerts = alerts,
            summary = summary,
            onOpenLink = { link -> if (!openLink(context, link)) Toast.makeText(context, "Nothing can open this link", Toast.LENGTH_SHORT).show() },
            onShareLink = { link -> if (!shareLink(context, link)) Toast.makeText(context, "Nothing can share this link", Toast.LENGTH_SHORT).show() },
            requestedChat = requestedChat,
            rootState = rootState as? RootState,
            onUseSlashCommand = { name -> prefillText = "/$name " },
            onUseAgent = { uri -> agentToUse = uri },
            onViewHostFile = { target -> externalRichRequest = RichModalRequest.HostFile(target) },
            openCustomizationsRequest = openCustomizationsRequest,
            onCustomizationsRequestConsumed = { openCustomizationsRequest = null },
        ) {
            if (chat != null) {
                OpenChatView(
                    sessionUri = open.uri,
                    chat = chat,
                    session = connected.session,
                    connection = connection,
                    rootState = rootState as? RootState,
                    sessionState = sessionState,
                    chatMutable = chatMutable,
                    canManage = canManage,
                    controller = controller,
                    ui = ui,
                    screen = false,
                    onChatCreated = { requestedChat = it },
                    prefillText = prefillText,
                    onPrefillConsumed = { prefillText = null },
                    agentToUse = agentToUse,
                    onAgentToUseConsumed = { agentToUse = null },
                    externalRichRequest = externalRichRequest,
                    onExternalRichConsumed = { externalRichRequest = null },
                    onOpenCustomizations = { cat, q -> openCustomizationsRequest = cat to q },
                    quickReplyProvider = quickReplyProvider,
                    modifier = Modifier,
                )
            }
        }
    }
}

/** The chat the user has open, wired to what the host says about it and to what this app may do there. */
@Composable
private fun OpenChatView(
    sessionUri: String,
    chat: OpenChat,
    session: HostSession,
    connection: ConnState,
    rootState: Any?,
    sessionState: SessionState?,
    chatMutable: Set<String>,
    canManage: Boolean,
    controller: AppController,
    ui: UiSettings,
    screen: Boolean,
    onChatCreated: (String) -> Unit = {},
    prefillText: String? = null,
    onPrefillConsumed: () -> Unit = {},
    agentToUse: String? = null,
    onAgentToUseConsumed: () -> Unit = {},
    externalRichRequest: RichModalRequest? = null,
    onExternalRichConsumed: () -> Unit = {},
    onOpenCustomizations: ((CustomizationCategory?, String) -> Unit)? = null,
    quickReplyProvider: GeminiNanoQuickReplyProvider? = null,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val transcript by rememberTranscript(chat.store)
    val expanded by chat.expansion.expanded.collectAsStateWithLifecycle()
    val error by chat.store.error.collectAsStateWithLifecycle()
    val loadingOlder by chat.store.loadingOlder.collectAsStateWithLifecycle()
    val olderError by chat.store.olderError.collectAsStateWithLifecycle()
    val chatState by chat.store.state.collectAsStateWithLifecycle()
    val canInteract by session.canInteract.collectAsStateWithLifecycle()
    val textScale by ui.textScale.collectAsStateWithLifecycle()
    val keepAwake by ui.keepAwake.collectAsStateWithLifecycle()
    val quickReplyMode by ui.quickReplyMode.collectAsStateWithLifecycle()
    val googleAiConfig by ui.googleAiConfig.collectAsStateWithLifecycle()
    val running = chatState?.activeTurn != null
    // The screen stays on while an agent works, when the person asked for that, and never otherwise.
    val view = LocalView.current
    DisposableEffect(running, keepAwake) {
        view.keepScreenOn = keepAwake && running
        onDispose { view.keepScreenOn = false }
    }
    val models = remember(chat.interactor, rootState) { chat.interactor.modelOptions() }
    val inUse = ModelOptions.inUse(chatState, models)
    val rootTyped = rootState as? RootState
    val agents = remember(sessionState, rootTyped) { CustomAgents.of(sessionState, rootTyped) }
    var localRichRequest by remember { mutableStateOf<RichModalRequest?>(null) }
    val agentInUse = CustomAgents.inUse(chatState)
    // The chat's own settings sit over the session's, so what is shown is what the host has in force for this chat.
    val ownConfig by chat.store.ownConfig.collectAsStateWithLifecycle()
    val config = remember(sessionState, chat.uri, chatMutable, ownConfig) { ChatConfig.view(sessionState, chat.uri, chatMutable, ownConfig) }
    val canFork = remember(controller.manager, sessionState, sessionUri, canManage, screen) {
        !screen && canManage && controller.manager.canFork(sessionUri)
    }
    val canSideChat = remember(controller.manager, sessionState, sessionUri, canManage, screen) {
        !screen && canManage && controller.manager.canSideChat(sessionUri)
    }
    // Nothing to act with unless the connection allows it and the host shows this chat as interactive.
    val customizations = remember(sessionState, rootTyped) { SlashCommands.customizationsOf(sessionState, rootTyped) }
    val slashCommands = remember(sessionState, rootTyped, canFork, canSideChat) {
        SlashCommands.of(sessionState, rootTyped, canFork = canFork, canSideChat = canSideChat)
    }
    val actions = if (ChatInteractor.available(chatState, canInteract)) {
        remember(chat.interactor, running, models, inUse, agents, agentInUse, config, canFork, canSideChat, slashCommands, customizations, prefillText, agentToUse, sessionUri, chat.uri, quickReplyMode, googleAiConfig, quickReplyProvider) {
            ChatActionsUi(
                running = running,
                send = chat.interactor::send,
                steer = chat.interactor::steer,
                cancel = chat.interactor::cancel,
                approve = chat.interactor::approve,
                deny = chat.interactor::deny,
                approveResult = chat.interactor::approveResult,
                denyResult = chat.interactor::denyResult,
                answer = chat.interactor::answer,
                saveDraft = chat.interactor::saveDraft,
                decline = chat.interactor::declineInput,
                dismiss = chat.interactor::dismissInput,
                models = models,
                modelInUse = inUse,
                agents = agents,
                agentInUse = agentInUse,
                config = config,
                setConfig = chat.interactor::setConfig,
                restoreCheckpoint = chat.interactor::restoreCheckpoint,
                startOver = chat.interactor::startOver,
                editAndResend = chat.interactor::editAndResend,
                retryTurn = chat.interactor::retryTurn,
                stopAndSend = chat.interactor::stopAndSend,
                removePending = chat.interactor::removePending,
                reorderQueue = chat.interactor::reorderQueue,
                canFork = canFork,
                forkFromTurn = { turnId ->
                    val res = controller.manager.forkChat(sessionUri, chat.uri, turnId)
                    (res as? ManageResult.Done)?.created?.let(onChatCreated)
                    res
                },
                editAsFork = { prevTurnId, newText, model, agent ->
                    val msg = chat.interactor.buildInitialMessage(newText, model, agent)
                    if (msg == null) {
                        ManageResult.InvalidChoice
                    } else {
                        val res = controller.manager.forkChat(sessionUri, chat.uri, prevTurnId, msg)
                        (res as? ManageResult.Done)?.created?.let(onChatCreated)
                        res
                    }
                },
                canSideChat = canSideChat,
                slashCommands = slashCommands,
                customizations = customizations,
                onOpenCustomizations = onOpenCustomizations,
                onViewHostFile = { target -> localRichRequest = RichModalRequest.HostFile(target) },
                prefillText = prefillText,
                onPrefillConsumed = onPrefillConsumed,
                agentToUse = agentToUse,
                onAgentToUseConsumed = onAgentToUseConsumed,
                quickReplyMode = quickReplyMode,
                quickReplyProvider = quickReplyProvider,
                quickReplyCacheKey = googleAiConfig.toString(),
                openSideChat = { turnId, initialText, model, agent ->
                    val msg = initialText?.let { chat.interactor.buildInitialMessage(it, model, agent) }
                    val res = controller.manager.createSideChat(sessionUri, chat.uri, turnId, msg)
                    (res as? ManageResult.Done)?.created?.let(onChatCreated)
                    res
                },
            )
        }
    } else {
        null
    }
    val onOpenLink = { link: String -> if (!openLink(context, link)) Toast.makeText(context, "Nothing can open this link", Toast.LENGTH_SHORT).show() }
    val onShareLink = { link: String -> if (!shareLink(context, link)) Toast.makeText(context, "Nothing can share this link", Toast.LENGTH_SHORT).show() }
    if (screen) {
        ChatScreen(
            transcript = transcript,
            error = error,
            loadingOlder = loadingOlder,
            olderError = olderError,
            connection = connection,
            onBack = controller::closeChat,
            onLoadOlder = chat.store::loadOlder,
            onOpenLink = onOpenLink,
            onShareLink = onShareLink,
            expanded = expanded,
            onToggle = chat.expansion::toggle,
            actions = actions,
            onOpenSubagent = controller::openSubagentChat,
            onOpenTerminal = controller::openTerminal,
            onRetry = chat.store::retry,
            backLabel = "Back to chat",
            textScale = textScale,
            onTextScale = ui::setTextScale,
            onReadResource = session::readResource,
            workingDirectories = sessionState?.workingDirectories.orEmpty(),
            externalRichRequest = externalRichRequest ?: localRichRequest,
            onExternalRichConsumed = {
                onExternalRichConsumed()
                localRichRequest = null
            },
            modifier = modifier,
        )
    } else {
        ChatPane(
            transcript = transcript,
            error = error,
            loadingOlder = loadingOlder,
            olderError = olderError,
            connection = connection,
            onLoadOlder = chat.store::loadOlder,
            onOpenLink = onOpenLink,
            onShareLink = onShareLink,
            expanded = expanded,
            onToggle = chat.expansion::toggle,
            actions = actions,
            onOpenSubagent = controller::openSubagentChat,
            onOpenTerminal = controller::openTerminal,
            onRetry = chat.store::retry,
            textScale = textScale,
            onTextScale = ui::setTextScale,
            onReadResource = session::readResource,
            workingDirectories = sessionState?.workingDirectories.orEmpty(),
            externalRichRequest = externalRichRequest ?: localRichRequest,
            onExternalRichConsumed = {
                onExternalRichConsumed()
                localRichRequest = null
            },
            modifier = modifier,
        )
    }
}
