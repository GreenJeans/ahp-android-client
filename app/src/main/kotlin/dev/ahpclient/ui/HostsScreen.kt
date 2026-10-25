package dev.ahpclient.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import dev.ahpclient.app.AppState
import dev.ahpclient.app.HostEntry
import dev.ahpclient.app.HostList
import dev.ahpclient.tunnel.TunnelInfo
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val dateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

/**
 * The machines running VS Code with Remote Tunnel Access on, for the signed-in account. Refreshing is the arrow in the bar;
 * signing out, which ends this app's access, is a menu item, not a button next to the list.
 */
@Composable
fun HostsScreen(
    state: AppState.Hosts,
    onRefresh: () -> Unit,
    onConnect: (TunnelInfo) -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    ScreenScaffold(
        title = "Your hosts",
        modifier = modifier,
        actions = {
            IconButton(onClick = onRefresh, modifier = Modifier.testTag("refresh_hosts")) {
                Icon(AppIcons.Refresh, contentDescription = "Refresh")
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("hosts_menu")) {
                    Icon(AppIcons.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Sign out") }, onClick = { menuOpen = false; onSignOut() }, modifier = Modifier.testTag("sign_out"))
                }
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = Design.ScreenPadding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(Design.Gap),
            ) {
                item(key = "intro") {
                    Text(
                        "Machines running VS Code with Remote Tunnel Access on, for your GitHub account.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 12.dp),
                    )
                }
                when (val list = state.list) {
                    HostList.Loading -> item(key = "loading") { Busy("Looking for your hosts…", Modifier.padding(8.dp).testTag("hosts_loading")) }
                    is HostList.Failed -> item(key = "failed") {
                        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ErrorText(list.message, Modifier.testTag("hosts_error"))
                            Button(onClick = onRefresh) { Text("Try again") }
                        }
                    }
                    HostList.Empty -> item(key = "empty") { EmptyHosts(onRefresh) }
                    is HostList.Loaded -> itemsIndexed(list.hosts, key = { _, entry -> entry.tunnel.tunnelId }) { index, entry ->
                        HostRow(entry, connectedShape(index, list.hosts.size), onConnect)
                    }
                }
            }
            // A sign-in the app renews on its own has no end worth showing: the access token it holds lasts hours, but the sign-in does not.
            val signedIn = if (state.renews) "Signed in. The app renews this on its own." else state.tokenExpiresAt?.let { "Signed in until ${dateTimeFormat.format(it)}." }
            signedIn?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp).testTag("token_expiry"),
                )
            }
        }
    }
}

@Composable
private fun EmptyHosts(onRefresh: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TileCard(modifier = Modifier.testTag("hosts_empty")) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("No hosts found", style = MaterialTheme.typography.titleMedium)
                Text(
                    "In VS Code, turn on Remote Tunnel Access: click the blue A in the Agents panel. Make sure VS Code is signed in with the same GitHub account as this app, then refresh.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Button(onClick = onRefresh) { Text("Refresh") }
    }
}

@Composable
private fun HostRow(entry: HostEntry, shape: Shape, onConnect: (TunnelInfo) -> Unit) {
    val tunnel = entry.tunnel
    val status = if (tunnel.isOnline) "Online" else "Offline"
    TileCard(
        shape = shape,
        onClick = { onConnect(tunnel) },
        onClickLabel = "Connect to ${tunnel.name}",
        modifier = Modifier.heightIn(min = 72.dp).testTag("host_${tunnel.tunnelId}"),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
