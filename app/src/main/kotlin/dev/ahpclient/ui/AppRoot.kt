package dev.ahpclient.ui

import androidx.activity.compose.BackHandler
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ahpclient.app.AppController
import dev.ahpclient.app.AppState
import dev.ahpclient.app.OpenChat
import dev.ahpclient.app.SignInStep
import dev.ahpclient.state.ChatInteractor
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.HostSession
import dev.ahpclient.ui.chat.ChatActionsUi
import dev.ahpclient.ui.chat.ChatPane
import dev.ahpclient.ui.chat.ChatScreen
import dev.ahpclient.ui.chat.rememberTranscript
import dev.ahpclient.ui.theme.AhpTheme
import kotlinx.coroutines.launch

/** Picks the screen for the current [AppState]. Holds no state of its own beyond what the controller exposes. */
@Composable
fun AppRoot(
    controller: AppController,
    onOpenGitHub: (String) -> Unit,
    onCopyCode: (String) -> Unit,
    monitorEnabled: Boolean = false,
    notificationsAllowed: Boolean = true,
    onMonitorChange: (Boolean) -> Unit = {},
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val interactionAllowed by controller.interactionAllowed.collectAsStateWithLifecycle()
    val managementAllowed by controller.managementAllowed.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    // The label of the changeset being opened, kept here so the screen can name it; the host's own title is not needed.
    var changesetTitle by rememberSaveable { mutableStateOf<String?>(null) }
    // Kept here, above the screen switch, so the list keeps its scroll position across a trip into a session and back.
    val listState = rememberLazyListState()
    val detailOpen = (state as? AppState.Connected)?.open != null
    // A session's own chat is part of the session screen, left by leaving the session. Only a subagent's transcript is a screen over it.
    val subagentOpen = (state as? AppState.Connected)?.open?.chat?.parent != null
    val changesetOpen = (state as? AppState.Connected)?.open?.changeset != null
    val diffOpen = (state as? AppState.Connected)?.open?.changeset?.diff != null
    val terminalOpen = (state as? AppState.Connected)?.open?.terminal != null
    BackHandler(enabled = state is AppState.Connected && !detailOpen) { controller.disconnect() }
    BackHandler(enabled = detailOpen && !subagentOpen && !changesetOpen && !terminalOpen) { controller.closeSession() }
    BackHandler(enabled = subagentOpen && !terminalOpen && !changesetOpen) { controller.closeChat() }
    BackHandler(enabled = terminalOpen) { controller.closeTerminal() }
    BackHandler(enabled = changesetOpen && !diffOpen) { controller.closeChangeset() }
    BackHandler(enabled = diffOpen) { controller.closeDiff() }
    BackHandler(enabled = (state as? AppState.SignedOut)?.step is SignInStep.AwaitingApproval) { controller.cancelSignIn() }
    BackHandler(enabled = settingsOpen) { settingsOpen = false }
    // Settings belong to the session list: leaving it (into a session, or off the host) closes them.
    val connected = state is AppState.Connected
    LaunchedEffect(connected, detailOpen) { if (!connected || detailOpen) settingsOpen = false }

    AhpTheme {
        // Every screen but the loading one brings its own bar and applies the system-bar insets itself.
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val current = state) {
                AppState.Restoring -> Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).testTag("restoring"), contentAlignment = Alignment.Center) { Busy("Loading…") }
                is AppState.SignedOut -> SignInScreen(current, controller::signIn, controller::cancelSignIn, onOpenGitHub, onCopyCode)
                is AppState.Hosts -> HostsScreen(current, controller::refreshHosts, controller::connect, controller::signOut)
                is AppState.Connected -> {
                    val connection by current.session.connection.collectAsStateWithLifecycle()
                    val canManage by current.session.canManage.collectAsStateWithLifecycle()
                    val rootState by current.session.root.root.collectAsStateWithLifecycle()
                    val listed by current.session.root.sessions.collectAsStateWithLifecycle()
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
                    val open = current.open
                    if (open == null) {
                        val sessions by current.session.root.sessions.collectAsStateWithLifecycle()
                        val now by rememberNow()
                        if (settingsOpen) {
                            SettingsScreen(
                                onBack = { settingsOpen = false },
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
                            )
                        } else {
                            SessionsScreen(
                                host = current.host,
                                connection = connection,
                                sessions = sessions,
                                now = now,
                                showArchived = showArchived,
                                listState = listState,
                                onToggleArchived = { showArchived = !showArchived },
                                onOpen = controller::openSession,
                                onDisconnect = controller::disconnect,
                                onOpenSettings = { settingsOpen = true },
                                manage = manageActions,
                                query = query,
                                onQueryChange = { query = it },
                            )
                        }
                    } else {
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
                            } else ChangesetScreen(
                                title = changesetTitle ?: "Changes",
                                state = changesetState,
                                error = changesetError,
                                connection = connection,
                                onBack = controller::closeChangeset,
                                onSetReviewed = if (canManage && changeset.reviewable) controller::setFileReviewed else null,
                                onOpenFile = controller::openDiff,
                            )
                        } else if (chat != null && chat.parent != null) {
                            // A subagent's transcript is a screen of its own over the session.
                            OpenChatView(chat, current.session, connection, rootState, controller, screen = true, modifier = Modifier)
                        } else {
                            val sessionState by open.store.state.collectAsStateWithLifecycle()
                            val error by open.store.error.collectAsStateWithLifecycle()
                            val now by rememberNow()
                            SessionScreen(
                                state = sessionState,
                                error = error,
                                connection = connection,
                                now = now,
                                onBack = controller::closeSession,
                                shownChat = chat?.uri,
                                onSelectChat = controller::selectChat,
                                manage = manageActions,
                                sessionUri = open.uri,
                                onRemoved = controller::closeSession,
                                onOpenChangeset = { uri, label -> changesetTitle = label; controller.openChangeset(uri) },
                            ) {
                                if (chat != null) OpenChatView(chat, current.session, connection, rootState, controller, screen = false, modifier = Modifier)
                            }
                        }
                    }
                }
            }
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
    val running = chatState?.activeTurn != null
    // Nothing to act with unless the connection allows it and the host shows this chat as interactive.
    val actions = if (ChatInteractor.available(chatState, canInteract)) {
        remember(chat.interactor, running, rootState) {
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
                models = chat.interactor.modelOptions(),
            )
        }
    } else {
        null
    }
    val onOpenLink = { link: String -> if (!openWebLink(context, link)) Toast.makeText(context, "No app could open this link", Toast.LENGTH_SHORT).show() }
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
            backLabel = "Back to chat",
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
            modifier = modifier,
        )
    }
}
