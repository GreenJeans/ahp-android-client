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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahpclient.app.DirectEntry
import dev.ahpclient.app.HostEntry
import dev.ahpclient.app.HostList
import dev.ahpclient.app.HostRef
import dev.ahpclient.direct.DirectHost
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.launch
import dev.ahpclient.state.ConnState
import dev.ahpclient.tunnel.TunnelInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val dateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

/**
 * The host: the machines running VS Code with Remote Tunnel Access on, for the signed-in account, or, while connected, the one
 * connected to, with a way to switch. Also signing out, which ends this app's access.
 * Refreshing is the arrow in the bar. [list] is null while connected.
 */
@Composable
fun HostScreen(
    list: HostList?,
    connectedHost: HostRef?,
    connection: ConnState?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onConnect: (TunnelInfo) -> Unit,
    onDisconnect: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    tokenExpiresAt: Instant? = null,
    renews: Boolean = false,
    /** The hosts added by address, listed under the tunnel hosts whether or not anyone is signed in. */
    direct: List<DirectEntry> = emptyList(),
    onConnectDirect: (DirectHost) -> Unit = {},
    /** Adds a host: returns why it could not be added, or null when it was. Null here hides the add button. */
    onAddHost: (suspend (name: String, address: String, token: String) -> String?)? = null,
    onRemoveHost: (DirectHost) -> Unit = {},
    /** False when no GitHub sign-in is held: the tunnel hosts then ask to sign in, and there is nothing to sign out of. */
    signedIn: Boolean = true,
    onSignIn: () -> Unit = {},
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<DirectHost?>(null) }
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
                    HostList.NotSignedIn -> item(key = "not_signed_in") { NotSignedIn(onSignIn) }
                    HostList.Empty -> item(key = "empty") { EmptyHosts(onRefresh) }
                    is HostList.Loaded -> itemsIndexed(list.hosts, key = { _, entry -> entry.tunnel.tunnelId }) { index, entry ->
                        HostRow(entry, connectedShape(index, list.hosts.size), onConnect)
                    }
                }
            }
            if (connectedHost == null && (direct.isNotEmpty() || onAddHost != null)) {
                item(key = "direct_header") { SectionHeader("Hosts you added") }
                itemsIndexed(direct, key = { _, entry -> entry.host.id }) { index, entry ->
                    DirectRow(entry, connectedShape(index, direct.size), onConnectDirect, onRemove = { removing = entry.host })
                }
                if (onAddHost != null) {
                    item(key = "add_host") {
                        OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("add_host")) {
                            Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text("Add a host by address")
                        }
                    }
                }
            }
            if (signedIn) item(key = "account") {
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
    if (adding && onAddHost != null) {
        AddHostDialog(onDismiss = { adding = false }, onAdd = onAddHost, onAdded = { adding = false })
    }
    removing?.let { host ->
        AlertDialog(
            onDismissRequest = { removing = null },
            modifier = Modifier.testTag("remove_host_dialog"),
            title = { Text("Remove \u201c${host.name}\u201d?") },
            text = { Text("This phone forgets the host's address, its token and what you allowed this app to do there. Nothing changes on the host.") },
            confirmButton = { TextButton(onClick = { removing = null; onRemoveHost(host) }, modifier = Modifier.testTag("remove_host_confirm")) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }, modifier = Modifier.testTag("remove_host_cancel")) { Text("Cancel") } },
        )
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
private fun ConnectedCard(host: HostRef, connection: ConnState?, onDisconnect: () -> Unit) {
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

/** Shown in place of the tunnel hosts when no GitHub sign-in is held. */
@Composable
private fun NotSignedIn(onSignIn: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TileCard(modifier = Modifier.testTag("hosts_not_signed_in")) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Not signed in to GitHub", style = MaterialTheme.typography.titleSmall)
                Text("Sign in to see the VS Code hosts that have Remote Tunnel Access on. Hosts you add by address need no sign-in.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Button(onClick = onSignIn, modifier = Modifier.testTag("sign_in_from_hosts")) { Text("Sign in with GitHub") }
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

/** A host added by address: its name and where it is, with a way to take it away. */
@Composable
private fun DirectRow(entry: DirectEntry, shape: Shape, onConnect: (DirectHost) -> Unit, onRemove: () -> Unit) {
    val host = entry.host
    TileCard(
        shape = shape,
        onClick = { onConnect(host) },
        onClickLabel = "Connect to ${host.name}",
        modifier = Modifier.heightIn(min = 56.dp).testTag("direct_${host.id}"),
    ) {
        Row(modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(host.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(host.url.substringAfter("://"))
                        if (host.url.startsWith("ws://")) append(" \u00b7 not encrypted")
                        if (entry.isLastUsed) append(" \u00b7 last used")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(48.dp).testTag("remove_${host.id}")) {
                Icon(AppIcons.Clear, contentDescription = "Remove ${host.name}")
            }
        }
    }
}

/**
 * Asks for a host's address and token. [onAdd] says why it could not be added, or null when it was; the token is never shown, never kept
 * in the saved state of the screen, and is dropped from here as soon as the dialog closes.
 */
@Composable
private fun AddHostDialog(onDismiss: () -> Unit, onAdd: suspend (String, String, String) -> String?, onAdded: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.testTag("add_host_dialog"),
        title = { Text("Add a host") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "For a host that listens on its own address, such as ahpd. Use the address of the machine it runs on, with the port (9187 unless you changed it). " +
                        "A ws:// address is not encrypted, so it is only accepted for this phone or a private network address.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = address, onValueChange = { address = it; error = null }, label = { Text("Address") }, singleLine = true,
                    placeholder = { Text("192.168.1.20:9187") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().testTag("add_host_address"),
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Name (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("add_host_name"),
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it; error = null }, label = { Text("Connection token") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("add_host_token"),
                )
                error?.let { ErrorText(it, Modifier.testTag("add_host_error")) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && address.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        val problem = onAdd(name, address, token)
                        busy = false
                        if (problem == null) {
                            token = ""
                            onAdded()
                        } else {
                            error = problem
                        }
                    }
                },
                modifier = Modifier.heightIn(min = 48.dp).testTag("add_host_confirm"),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("add_host_cancel")) { Text("Cancel") } },
    )
}
