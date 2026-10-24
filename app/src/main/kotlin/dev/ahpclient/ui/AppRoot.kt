package dev.ahpclient.ui

import androidx.activity.compose.BackHandler
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
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
import dev.ahpclient.app.SignInStep
import dev.ahpclient.state.ChatInteractor
import dev.ahpclient.ui.chat.ChatActionsUi
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
    // The label of the changeset being opened, kept here so the screen can name it; the host's own title is not needed.
    var changesetTitle by rememberSaveable { mutableStateOf<String?>(null) }
    // Kept here, above the screen switch, so the list keeps its scroll position across a trip into a session and back.
    val listState = rememberLazyListState()
    val detailOpen = (state as? AppState.Connected)?.open != null
    val chatOpen = (state as? AppState.Connected)?.open?.chat != null
    val changesetOpen = (state as? AppState.Connected)?.open?.changeset != null
    val diffOpen = (state as? AppState.Connected)?.open?.changeset?.diff != null
    BackHandler(enabled = state is AppState.Connected && !detailOpen) { controller.disconnect() }
    BackHandler(enabled = detailOpen && !chatOpen && !changesetOpen) { controller.closeSession() }
    BackHandler(enabled = chatOpen) { controller.closeChat() }
    BackHandler(enabled = changesetOpen && !diffOpen) { controller.closeChangeset() }
    BackHandler(enabled = diffOpen) { controller.closeDiff() }
    BackHandler(enabled = (state as? AppState.SignedOut)?.step is SignInStep.AwaitingApproval) { controller.cancelSignIn() }

    AhpTheme {
        // Scaffold applies the system-bar insets, so nothing draws under the status or navigation bar.
        Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
            val content = Modifier.padding(padding)
            when (val current = state) {
                AppState.Restoring -> Box(content.fillMaxSize().testTag("restoring"), contentAlignment = Alignment.Center) { Busy("Loading…") }
                is AppState.SignedOut -> SignInScreen(current, controller::signIn, controller::cancelSignIn, onOpenGitHub, onCopyCode, content)
                is AppState.Hosts -> HostsScreen(current, controller::refreshHosts, controller::connect, controller::signOut, content)
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
                        SessionsScreen(
                            host = current.host,
                            connection = connection,
                            sessions = sessions,
                            now = now,
                            showArchived = showArchived,
                            listState = listState,
                            onToggleArchived = { showArchived = !showArchived },
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
                            manage = manageActions,
                            onOpen = controller::openSession,
                            onDisconnect = controller::disconnect,
                            modifier = content,
                        )
                    } else {
                        val chat = open.chat
                        val changeset = open.changeset
                        if (changeset != null) {
                            val changesetState by changeset.store.state.collectAsStateWithLifecycle()
                            val changesetError by changeset.store.error.collectAsStateWithLifecycle()
                            val diff = changeset.diff
                            if (diff != null) {
                                val diffLoad by diff.load.collectAsStateWithLifecycle()
                                FileDiffScreen(path = diff.path, load = diffLoad, connection = connection, onBack = controller::closeDiff, modifier = content)
                            } else ChangesetScreen(
                                title = changesetTitle ?: "Changes",
                                state = changesetState,
                                error = changesetError,
                                connection = connection,
                                onBack = controller::closeChangeset,
                                onSetReviewed = if (canManage && changeset.reviewable) controller::setFileReviewed else null,
                                onOpenFile = controller::openDiff,
                                modifier = content,
                            )
                        } else if (chat == null) {
                            val sessionState by open.store.state.collectAsStateWithLifecycle()
                            val error by open.store.error.collectAsStateWithLifecycle()
                            val now by rememberNow()
                            SessionDetailScreen(
                                sessionState, error, connection, now, controller::closeSession, content,
                                onOpenChat = controller::openChat,
                                manage = manageActions,
                                sessionUri = open.uri,
                                onRemoved = controller::closeSession,
                                onOpenChangeset = { uri, label -> changesetTitle = label; controller.openChangeset(uri) },
                            )
                        } else {
                            val transcript by rememberTranscript(chat.store)
                            val expanded by chat.expansion.expanded.collectAsStateWithLifecycle()
                            val error by chat.store.error.collectAsStateWithLifecycle()
                            val loadingOlder by chat.store.loadingOlder.collectAsStateWithLifecycle()
                            val olderError by chat.store.olderError.collectAsStateWithLifecycle()
                            val chatState by chat.store.state.collectAsStateWithLifecycle()
                            val canInteract by current.session.canInteract.collectAsStateWithLifecycle()
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
                            ChatScreen(
                                transcript = transcript,
                                error = error,
                                loadingOlder = loadingOlder,
                                olderError = olderError,
                                connection = connection,
                                onBack = controller::closeChat,
                                onLoadOlder = chat.store::loadOlder,
                                onOpenLink = { link -> if (!openWebLink(context, link)) Toast.makeText(context, "No app could open this link", Toast.LENGTH_SHORT).show() },
                                expanded = expanded,
                                onToggle = chat.expansion::toggle,
                                actions = actions,
                                onOpenSubagent = controller::openSubagentChat,
                                backLabel = if (chat.parent != null) "Back to chat" else "Back to session",
                                modifier = content,
                            )
                        }
                    }
                }
            }
        }
    }
}
