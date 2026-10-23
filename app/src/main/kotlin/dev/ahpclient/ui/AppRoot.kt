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
import dev.ahpclient.ui.chat.ChatScreen
import dev.ahpclient.ui.chat.rememberTranscript
import dev.ahpclient.ui.theme.AhpTheme

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
    var showArchived by rememberSaveable { mutableStateOf(false) }
    // Kept here, above the screen switch, so the list keeps its scroll position across a trip into a session and back.
    val listState = rememberLazyListState()
    val detailOpen = (state as? AppState.Connected)?.open != null
    val chatOpen = (state as? AppState.Connected)?.open?.chat != null
    BackHandler(enabled = state is AppState.Connected && !detailOpen) { controller.disconnect() }
    BackHandler(enabled = detailOpen && !chatOpen) { controller.closeSession() }
    BackHandler(enabled = chatOpen) { controller.closeChat() }
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
                            onOpen = controller::openSession,
                            onDisconnect = controller::disconnect,
                            modifier = content,
                        )
                    } else {
                        val chat = open.chat
                        if (chat == null) {
                            val sessionState by open.store.state.collectAsStateWithLifecycle()
                            val error by open.store.error.collectAsStateWithLifecycle()
                            val now by rememberNow()
                            SessionDetailScreen(sessionState, error, connection, now, controller::closeSession, content, onOpenChat = controller::openChat)
                        } else {
                            val transcript by rememberTranscript(chat.store)
                            val error by chat.store.error.collectAsStateWithLifecycle()
                            val loadingOlder by chat.store.loadingOlder.collectAsStateWithLifecycle()
                            val olderError by chat.store.olderError.collectAsStateWithLifecycle()
                            val context = LocalContext.current
                            ChatScreen(
                                transcript = transcript,
                                error = error,
                                loadingOlder = loadingOlder,
                                olderError = olderError,
                                connection = connection,
                                onBack = controller::closeChat,
                                onLoadOlder = chat.store::loadOlder,
                                onOpenLink = { link -> if (!openWebLink(context, link)) Toast.makeText(context, "No app could open this link", Toast.LENGTH_SHORT).show() },
                                modifier = content,
                            )
                        }
                    }
                }
            }
        }
    }
}
