package dev.ahpclient.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ahpclient.app.AppController
import dev.ahpclient.app.AppState
import dev.ahpclient.app.SignInStep
import dev.ahpclient.ui.theme.AhpTheme

/** Picks the screen for the current [AppState]. Holds no state of its own beyond what the controller exposes. */
@Composable
fun AppRoot(
    controller: AppController,
    onOpenGitHub: (String) -> Unit,
    onCopyCode: (String) -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state is AppState.Connected) { controller.disconnect() }
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
                    val sessions by current.session.root.sessions.collectAsStateWithLifecycle()
                    ConnectedScreen(current.host, connection, sessions.size, controller::disconnect, content)
                }
            }
        }
    }
}
