package dev.ahpclient.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ahpclient.app.AppState
import dev.ahpclient.app.HostEntry
import dev.ahpclient.app.HostList
import dev.ahpclient.tunnel.TunnelInfo
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val dateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

@Composable
fun HostsScreen(
    state: AppState.Hosts,
    onRefresh: () -> Unit,
    onConnect: (TunnelInfo) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenColumn {
            Text("Your hosts", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text(
                "Machines running VS Code with Remote Tunnel Access on, for your GitHub account.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onRefresh, modifier = Modifier.testTag("refresh_hosts")) { Text("Refresh") }
                TextButton(onClick = onSignOut, modifier = Modifier.testTag("sign_out")) { Text("Sign out") }
            }
        }
        when (val list = state.list) {
            HostList.Loading -> ScreenColumn { Busy("Looking for your hosts…", Modifier.testTag("hosts_loading")) }
            is HostList.Failed -> ScreenColumn {
                ErrorText(list.message, Modifier.testTag("hosts_error"))
                Button(onClick = onRefresh) { Text("Try again") }
            }
            HostList.Empty -> ScreenColumn { EmptyHosts(onRefresh) }
            is HostList.Loaded -> LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(list.hosts, key = { it.tunnel.tunnelId }) { HostRow(it, onConnect) }
            }
        }
        state.tokenExpiresAt?.let {
            Text(
                "Signed in until ${dateTimeFormat.format(it)}.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).testTag("token_expiry"),
            )
        }
    }
}

@Composable
private fun EmptyHosts(onRefresh: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().testTag("hosts_empty")) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No hosts found", style = MaterialTheme.typography.titleMedium)
            Text(
                "In VS Code, turn on Remote Tunnel Access: click the blue A in the Agents panel. Make sure VS Code is signed in with the same GitHub account as this app, then refresh.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    Button(onClick = onRefresh) { Text("Refresh") }
}

@Composable
private fun HostRow(entry: HostEntry, onConnect: (TunnelInfo) -> Unit) {
    val tunnel = entry.tunnel
    val status = if (tunnel.isOnline) "Online" else "Offline"
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .testTag("host_${tunnel.tunnelId}")
            .clickable(role = Role.Button, onClickLabel = "Connect to ${tunnel.name}") { onConnect(tunnel) },
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tunnel.name, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Decorative: the word next to it carries the meaning, so colour is never the only cue.
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (tunnel.isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        .clearAndSetSemantics {},
                )
                Text(
                    buildString {
                        append(status)
                        append(" · protocol v").append(tunnel.protocolVersion)
                        if (entry.isLastUsed) append(" · last used")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!tunnel.isOnline) {
                Text(
                    "VS Code is not connected. Open it and turn on Remote Tunnel Access, or try anyway.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
