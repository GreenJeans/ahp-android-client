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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahpclient.app.HostEntry
import dev.ahpclient.app.HostList
import dev.ahpclient.state.ConnState
import dev.ahpclient.tunnel.TunnelInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val dateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

/**
 * The host: the machines running VS Code with Remote Tunnel Access on, for the signed-in account, or, while connected, the one
 * connected to, with a way to switch. Also the browser that links open in, and signing out, which ends this app's access.
 * Refreshing is the arrow in the bar. [list] is null while connected.
 */
@Composable
fun HostScreen(
    list: HostList?,
    connectedHost: TunnelInfo?,
    connection: ConnState?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onConnect: (TunnelInfo) -> Unit,
    onDisconnect: () -> Unit,
    onSignOut: () -> Unit,
    browsers: List<BrowserOption>,
    browser: String?,
    onBrowser: (String?) -> Unit,
    modifier: Modifier = Modifier,
    tokenExpiresAt: Instant? = null,
    renews: Boolean = false,
) {
    var pickingBrowser by rememberSaveable { mutableStateOf(false) }
    var confirmingSignOut by rememberSaveable { mutableStateOf(false) }
    ScreenScaffold(
        title = "Host",
        onBack = onBack,
        backLabel = "Back",
        titleTag = "host_title",
        modifier = modifier,
        actions = {
            if (connectedHost == null) {
                IconButton(onClick = onRefresh, modifier = Modifier.testTag("refresh_hosts")) { Icon(AppIcons.Refresh, contentDescription = "Refresh") }
            }
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Design.ScreenPadding, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(Design.Gap),
        ) {
            if (connectedHost != null) {
                item(key = "connected") { ConnectedCard(connectedHost, connection, onDisconnect) }
            } else {
                when (list) {
                    null, HostList.Loading -> item(key = "loading") { Busy("Looking for hosts\u2026", Modifier.padding(8.dp).testTag("hosts_loading")) }
                    is HostList.Failed -> item(key = "failed") {
                        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            item(key = "browser") {
                val label = browsers.firstOrNull { it.packageName == browser }?.label ?: "System default"
                TileCard(onClick = { pickingBrowser = true }, onClickLabel = "Choose browser", modifier = Modifier.testTag("browser_row")) {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.Globe, contentDescription = null)
                        Column(Modifier.weight(1f)) {
                            Text("Open links in", style = MaterialTheme.typography.titleSmall)
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("browser_label"))
                        }
                        Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item(key = "account") {
                Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { confirmingSignOut = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("sign_out")) {
                        Icon(AppIcons.SignOut, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Sign out")
                    }
                    // A sign-in the app renews on its own has no end worth showing: the access token it holds lasts hours, but the sign-in does not.
                    val signedIn = if (renews) "Signed in. Renews itself." else tokenExpiresAt?.let { "Signed in until ${dateTimeFormat.format(it)}." }
                    signedIn?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp).testTag("token_expiry"))
                    }
                }
            }
        }
    }
    if (pickingBrowser) {
        BrowserPickerDialog(browsers, browser, onDismiss = { pickingBrowser = false }, onPick = { pickingBrowser = false; onBrowser(it) })
    }
    if (confirmingSignOut) {
        AlertDialog(
            onDismissRequest = { confirmingSignOut = false },
            modifier = Modifier.testTag("sign_out_dialog"),
            title = { Text("Sign out?") },
            text = { Text("This phone forgets its GitHub sign-in. You will need to sign in again.") },
            confirmButton = { TextButton(onClick = { confirmingSignOut = false; onSignOut() }, modifier = Modifier.testTag("sign_out_confirm")) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmingSignOut = false }, modifier = Modifier.testTag("sign_out_cancel")) { Text("Cancel") } },
        )
    }
}

/** The host connected to now, and the way to leave it for another. */
@Composable
private fun ConnectedCard(host: TunnelInfo, connection: ConnState?, onDisconnect: () -> Unit) {
    TileCard(modifier = Modifier.testTag("connected_host")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Host, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(host.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (connection != null) ConnectionLine(connection, Modifier.testTag("connection_status"))
            }
            TextButton(onClick = onDisconnect, modifier = Modifier.heightIn(min = 48.dp).testTag("disconnect")) { Text("Switch host") }
        }
    }
}

@Composable
private fun EmptyHosts(onRefresh: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TileCard(modifier = Modifier.testTag("hosts_empty")) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("No hosts found", style = MaterialTheme.typography.titleSmall)
                Text(
                    "In VS Code, click the blue A in the Agents panel to turn on Remote Tunnel Access, signed in to the same GitHub account as this app. Then refresh.",
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
    TileCard(
        shape = shape,
        onClick = { onConnect(tunnel) },
        onClickLabel = "Connect to ${tunnel.name}",
        modifier = Modifier.heightIn(min = 56.dp).testTag("host_${tunnel.tunnelId}"),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tunnel.name, style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Decorative: the word next to it carries the meaning, so colour is never the only cue.
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (tunnel.isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                        .clearAndSetSemantics {},
                )
                Text(
                    buildString {
                        append(if (tunnel.isOnline) "Online" else "Offline")
                        append(" \u00b7 v").append(tunnel.protocolVersion)
                        if (entry.isLastUsed) append(" \u00b7 last used")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!tunnel.isOnline) Text("VS Code is not connected. Try anyway, or turn on Remote Tunnel Access there.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Picks the browser links open in: the system's choice, or one of those installed. */
@Composable
private fun BrowserPickerDialog(browsers: List<BrowserOption>, current: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("browser_dialog"),
        title = { Text("Open links in") },
        text = {
            Column {
                BrowserChoice("System default", current == null, "browser_default") { onPick(null) }
                browsers.forEach { BrowserChoice(it.label, current == it.packageName, "browser_option") { onPick(it.packageName) } }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("browser_cancel")) { Text("Cancel") } },
    )
}

@Composable
private fun BrowserChoice(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick).testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}