package dev.ahpclient.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.ahpclient.state.ConnState
import dev.ahpclient.tunnel.TunnelInfo

/** The words for each connection state. Never includes a token: the causes are typed errors with fixed messages. */
fun describe(connection: ConnState): String = when (connection) {
    ConnState.Stopped -> "Disconnected"
    ConnState.Connecting -> "Connecting…"
    ConnState.Online -> "Connected"
    is ConnState.Reconnecting -> "Connection lost. Reconnecting (attempt ${connection.attempt})…"
    is ConnState.Failed -> connection.cause.message ?: "Could not connect (${connection.cause.javaClass.simpleName})."
}

/** Placeholder for the session list, which replaces this screen's body in the next step. */
@Composable
fun ConnectedScreen(
    host: TunnelInfo,
    connection: ConnState,
    sessionCount: Int,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenColumn {
            Text(host.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            if (connection is ConnState.Failed) {
                ErrorText(describe(connection), Modifier.testTag("connection_status"))
            } else {
                Text(describe(connection), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("connection_status"))
            }
            if (connection == ConnState.Online) {
                Text(
                    if (sessionCount == 1) "1 session on this host" else "$sessionCount sessions on this host",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("session_count"),
                )
            }
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth().testTag("disconnect")) { Text("Back to hosts") }
        }
    }
}
