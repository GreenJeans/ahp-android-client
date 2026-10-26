package dev.ahpclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.ConnState

/**
 * What the main page says when no session is open: not connected, with a way to connect, or connected, with ways to pick a session or
 * start one. [connection] is shown only when it is not simply online, so a drop or a failure is not hidden behind the buttons.
 */
@Composable
fun MainEmpty(
    connected: Boolean,
    connection: ConnState?,
    manage: ManageActionsUi?,
    onConnect: () -> Unit,
    onOpenSessions: () -> Unit,
    onSessionCreated: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** How many sessions wait on the user. When above zero (and [onOpenInbox] is given) the page leads with a button to them. */
    needYou: Int = 0,
    onOpenInbox: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp).testTag("main_empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(AppIcons.Host, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        if (!connected) {
            Text("Not connected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("empty_title"))
            Button(onClick = onConnect, modifier = Modifier.testTag("connect_host")) { Text("Connect to a host") }
        } else {
            Text("No session open", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("empty_title"))
            Text("Pick one, or start a new one.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            if (connection != null && connection != ConnState.Online) ConnectionLine(connection, Modifier.testTag("empty_connection"))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (needYou > 0 && onOpenInbox != null) {
                    Button(onClick = onOpenInbox, modifier = Modifier.heightIn(min = 48.dp).testTag("open_inbox")) { Text("$needYou need${if (needYou == 1) "s" else ""} you") }
                }
                OutlinedButton(onClick = onOpenSessions, modifier = Modifier.testTag("open_sessions")) { Text("Sessions") }
                if (manage != null) NewSessionButton(manage, onSessionCreated)
            }
        }
    }
}