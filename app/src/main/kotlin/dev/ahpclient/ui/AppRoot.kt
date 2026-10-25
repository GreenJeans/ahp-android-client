package dev.ahpclient.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
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
import dev.ahpclient.app.AppController
import dev.ahpclient.app.AppState
import dev.ahpclient.app.OpenChat
import dev.ahpclient.app.SignInStep
import dev.ahpclient.data.UiSettings
import dev.ahpclient.state.ChangeStats
import dev.ahpclient.state.ChatInteractor
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.HostSession
import dev.ahpclient.state.ModelOptions
import dev.ahpclient.tunnel.TunnelInfo
import dev.ahpclient.ui.chat.ChatActionsUi
import dev.ahpclient.ui.chat.ChatPane
import dev.ahpclient.ui.chat.ChatScreen
import dev.ahpclient.ui.chat.rememberTranscript
import dev.ahpclient.ui.theme.AhpTheme
import com.microsoft.agenthostprotocol.generated.SessionSummary
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
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val theme by ui.theme.collectAsStateWithLifecycle()
    BackHandler(enabled = (state as? AppState.SignedOut)?.step is SignInStep.AwaitingApproval) { controller.cancelSignIn() }
    AhpTheme(theme) {
        // Every screen but the loading one brings its own bar and applies the system-bar insets itself.
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val current = state) {
                AppState.Restoring -> Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).testTag("restoring"), contentAlignment = Alignment.Center) { Busy("Loading…") }
                is AppState.SignedOut -> SignInScreen(current, controller::signIn, controller::cancelSignIn, onOpenGitHub, onCopyCode)
                is AppState.Hosts -> Shell(controller, ui, current, null, monitorEnabled, notificationsAllowed, onMonitorChange)
                is AppState.Connected -> Shell(controller, ui, null, current, monitorEnabled, notificationsAllowed, onMonitorChange)
            }
        }
    }
}

/** The screens a signed-in person moves between: the main page (an empty one, or a session) with the side menu, the host, and settings. */
private enum class Page { MAIN, HOST, SETTINGS }

/**
 * Everything once signed in: a side menu (the host, a new session, the sessions, Settings) over the main page, which is the open
 * session or an empty page that says what to do. The host and Settings are screens of their own, left by Back.
 */
@Composable
private fun Shell(
    controller: AppController,
    ui: UiSettings,
    hosts: AppState.Hosts?,
    connected: AppState.Connected?,
    monitorEnabled: Boolean,
    notificationsAllowed: Boolean,
    onMonitorChange: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val interactionAllowed by controller.interactionAllowed.collectAsStateWithLifecycle()
    val managementAllowed by controller.managementAllowed.collectAsStateWithLifecycle()
    val textScale by ui.textScale.collectAsStateWithLifecycle()
    val theme by ui.theme.collectAsStateWithLifecycle()
    val keepAwake by ui.keepAwake.collectAsStateWithLifecycle()
    val browser by ui.browser.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
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
    BackHandler(enabled = open != null && !overlayOpen) { controller.closeSession() }
    BackHandler(enabled = subagentOpen && !terminalOpen && !changesetOpen) { controller.closeChat() }
    BackHandler(enabled = terminalOpen) { controller.closeTerminal() }
    BackHandler(enabled = changesetOpen && !diffOpen) { controller.closeChangeset() }
    BackHandler(enabled = diffOpen) { controller.closeDiff() }
    BackHandler(enabled = page != Page.MAIN) { page = Page.MAIN }
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    // Connecting from the host screen brings the main page back.
    LaunchedEffect(connected != null) { if (connected != null) page = Page.MAIN }

    val connection = connected?.let { it.session.connection.collectAsStateWithLifecycle().value }
    val canManage = connected?.let { it.session.canManage.collectAsStateWithLifecycle().value } == true
    val rootState = connected?.let { it.session.root.root.collectAsStateWithLifecycle().value }
    val listed: List<SessionSummary> = connected?.let { it.session.root.sessions.collectAsStateWithLifecycle().value }.orEmpty()
    val now by rememberNow()
    // Nothing to look after sessions with unless the connection is on the manage tier: no controls, not disabled ones.
    val manageActions = if (canManage) {
        remember(controller.manager, rootState, listed) {
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
            )
        }
    } else {
        null
    }
    val closeDrawer = { scope.launch { drawerState.close() }; Unit }
    val openDrawer = { scope.launch { drawerState.open() }; Unit }
    val showSession = { uri: String -> closeDrawer(); controller.showSession(uri) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = page == Page.MAIN && !overlayOpen,
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
                onOpenHost = { closeDrawer(); page = Page.HOST },
                onOpenSession = showSession,
                onOpenSettings = { closeDrawer(); page = Page.SETTINGS },
                onSessionCreated = showSession,
            )
        },
    ) {
        when (page) {
            Page.HOST -> {
                val browsers = remember { Browsers.installed(context) }
                HostScreen(
                    list = hosts?.list,
                    connectedHost = connected?.host,
                    connection = connection,
                    onBack = { page = Page.MAIN },
                    onRefresh = controller::refreshHosts,
                    onConnect = controller::connect,
                    onDisconnect = controller::disconnect,
                    onSignOut = controller::signOut,
                    browsers = browsers,
                    browser = browser,
                    onBrowser = ui::setBrowser,
                    tokenExpiresAt = hosts?.tokenExpiresAt,
                    renews = hosts?.renews == true,
                )
            }
            Page.SETTINGS -> SettingsScreen(
                onBack = { page = Page.MAIN },
                monitorEnabled = monitorEnabled,
                notificationsAllowed = notificationsAllowed,
                onMonitorChange = onMonitorChange,
                interactionAllowed = interactionAllowed,
                interactionSupported = controller.interactionSupported,
                onInteractionChange = { on ->
                    scope.launch { if (!controller.setInteraction(on)) Toast.makeText(context, "Could not save the setting", Toast.LENGTH_SHORT).show() }
                },
                managementAllowed = managementAllowed,
                onManagementChange = { on ->
                    scope.launch { if (!controller.setManagement(on)) Toast.makeText(context, "Could not save the setting", Toast.LENGTH_SHORT).show() }
                },
                textScale = textScale,
                onTextScale = ui::setTextScale,
                theme = theme,
                onTheme = ui::setTheme,
                keepAwake = keepAwake,
                onKeepAwake = ui::setKeepAwake,
            )
            Page.MAIN -> if (connected == null || connection == null) {
                ScreenScaffold(title = "AHP Client", onMenu = { openDrawer() }) {
                    MainEmpty(connected = false, connection = null, manage = null, onConnect = { page = Page.HOST }, onOpenSessions = { openDrawer() }, onSessionCreated = {})
                }
            } else if (open == null) {
                ScreenScaffold(title = connected.host.name, onMenu = { openDrawer() }) {
                    MainEmpty(connected = true, connection = connection, manage = manageActions, onConnect = { page = Page.HOST }, onOpenSessions = { openDrawer() }, onSessionCreated = showSession)
                }
            } else {
                SessionPage(
                    controller, ui, connected, open, connection, rootState, canManage, manageActions, listed, changesetTitle,
                    onChangesetTitle = { changesetTitle = it }, onMenu = { openDrawer() },
                )
            }
        }
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
) {
    val chat = open.chat
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
            )
        }
    } else if (chat != null && chat.parent != null) {
        // A subagent's transcript is a screen of its own over the session.
        OpenChatView(chat, connected.session, connection, rootState, controller, ui, screen = true, modifier = Modifier)
    } else {
        val sessionState by open.store.state.collectAsStateWithLifecycle()
        val error by open.store.error.collectAsStateWithLifecycle()
        val now by rememberNow()
        val changes = remember(listed, open.uri) { ChangeStats.of(listed.firstOrNull { it.resource == open.uri }?.changes) }
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
        ) {
            if (chat != null) OpenChatView(chat, connected.session, connection, rootState, controller, ui, screen = false, modifier = Modifier)
        }
    }
}

/** The chat the user has open, wired to what the host says about it and to what this app may do there. */
@Composable
private fun OpenChatView(
    chat: OpenChat,
    session: HostSession,
    connection: ConnState,
    rootState: Any?,
    controller: AppController,
    ui: UiSettings,
    screen: Boolean,
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
    val browser by ui.browser.collectAsStateWithLifecycle()
    val keepAwake by ui.keepAwake.collectAsStateWithLifecycle()
    val running = chatState?.activeTurn != null
    // The screen stays on while an agent works, when the person asked for that, and never otherwise.
    val view = LocalView.current
    DisposableEffect(running, keepAwake) {
        view.keepScreenOn = keepAwake && running
        onDispose { view.keepScreenOn = false }
    }
    val models = remember(chat.interactor, rootState) { chat.interactor.modelOptions() }
    val inUse = ModelOptions.inUse(chatState, models)
    // Nothing to act with unless the connection allows it and the host shows this chat as interactive.
    val actions = if (ChatInteractor.available(chatState, canInteract)) {
        remember(chat.interactor, running, models, inUse) {
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
            )
        }
    } else {
        null
    }
    val onOpenLink = { link: String -> if (!openLink(context, link, browser)) Toast.makeText(context, "Nothing can open this link", Toast.LENGTH_SHORT).show() }
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
            expanded = expanded,
            onToggle = chat.expansion::toggle,
            actions = actions,
            onOpenSubagent = controller::openSubagentChat,
            onOpenTerminal = controller::openTerminal,
            onRetry = chat.store::retry,
            backLabel = "Back to chat",
            textScale = textScale,
            onTextScale = ui::setTextScale,
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
            expanded = expanded,
            onToggle = chat.expansion::toggle,
            actions = actions,
            onOpenSubagent = controller::openSubagentChat,
            onOpenTerminal = controller::openTerminal,
            onRetry = chat.store::retry,
            textScale = textScale,
            onTextScale = ui::setTextScale,
            modifier = modifier,
        )
    }
}