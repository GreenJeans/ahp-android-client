package dev.ahpclient.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahpclient.app.DirectEntry
import dev.ahpclient.app.HostCatalog
import dev.ahpclient.app.HostEntry
import dev.ahpclient.app.HostList
import dev.ahpclient.app.HostRef
import dev.ahpclient.direct.DirectHost
import dev.ahpclient.state.ConnState
import dev.ahpclient.tunnel.TunnelInfo
import kotlinx.coroutines.launch

/** One host in the sheet's list of the others: one of the account's tunnel hosts, or one added by address. */
private sealed interface OtherHost {
    data class Tunnel(val entry: HostEntry) : OtherHost

    data class Direct(val entry: DirectEntry) : OtherHost
}

/**
 * The hosts other than the connected one as one list, in the order the sheet shows them: tunnel hosts that are online, hosts added by
 * address, then tunnel hosts that are offline. Each group keeps the order the catalog gave it (the last used first).
 */
private fun otherHosts(catalog: HostCatalog, connected: HostRef?): List<OtherHost> {
    val tunnels = (catalog.list as? HostList.Loaded)?.hosts.orEmpty().filter { it.tunnel.tunnelId != connected?.id }
    return tunnels.filter { it.tunnel.isOnline }.map { OtherHost.Tunnel(it) } +
        catalog.direct.filter { it.host.id != connected?.id }.map { OtherHost.Direct(it) } +
        tunnels.filterNot { it.tunnel.isOnline }.map { OtherHost.Tunnel(it) }
}

/**
 * Calls [onOffer] once when the hosts sheet should open by itself: nothing is connected, the tunnel list has settled (the cold start's
 * connection to the last host is tried when the first list arrives), and no host is marked as the last used, so there is nothing to
 * connect to by itself. Never after a host has been connected, and never a second time (a rotation does not repeat it).
 */
@Composable
internal fun OfferHostsOnce(connected: Boolean, catalog: HostCatalog, onOffer: () -> Unit) {
    var offered by rememberSaveable { mutableStateOf(false) }
    val nothingToConnectTo = !connected && catalog.list !is HostList.Loading &&
        (catalog.list as? HostList.Loaded)?.hosts.orEmpty().none { it.isLastUsed } && catalog.direct.none { it.isLastUsed }
    LaunchedEffect(connected, nothingToConnectTo) {
        if (connected) {
            offered = true
        } else if (nothingToConnectTo && !offered) {
            offered = true
            onOffer()
        }
    }
}

/**
 * The hosts, in a sheet from the bottom over the side menu: the one connected (with a way to leave it), every other host to switch to
 * with one tap, a way to add a host by its address, and, with no GitHub sign-in, a way to sign in. [onRefresh] is called when the
 * sheet opens and by the button in its header. A tap on a host calls [onSwitch], Disconnect calls [onDisconnect] and Manage hosts calls
 * [onManageHosts], and each then calls [onDismiss]. Switching goes from one host to the next in the controller, with no "not connected" between.
 *
 * [catalog] is what the controller lists; [connectedHost] and [connection] are null with nothing connected. A failed tunnel list is
 * said in place of the tunnel hosts and leaves the hosts added by address. [onAddHost] returns why a host could not be added, or null
 * when it was; null here hides the add button. [onSignIn] null hides the sign-in row (nothing to start it from).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostSheet(
    catalog: HostCatalog,
    connectedHost: HostRef?,
    connection: ConnState?,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onSwitch: (HostRef) -> Unit,
    onDisconnect: () -> Unit,
    onManageHosts: () -> Unit,
    modifier: Modifier = Modifier,
    onAddHost: (suspend (name: String, address: String, token: String) -> String?)? = null,
    onSignIn: (() -> Unit)? = null,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { onRefresh() }
    val others = otherHosts(catalog, connectedHost)
    val card = MaterialTheme.colorScheme.surfaceContainerHigh
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // A tap that ends the sheet slides it away as a swipe or the scrim does, and only then takes it out of the composition.
    val close: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() } }
    // With a host connected and no sign-in to see its tunnel hosts, the sheet says why there are none, rather than leave the person to guess.
    val signInHint = connectedHost != null && catalog.list == HostList.NotSignedIn && onSignIn == null
    val listing = others.isNotEmpty() || signInHint || catalog.list is HostList.Loading || catalog.list is HostList.Failed || catalog.list == HostList.Empty
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        // The handle is itself a control (it expands and collapses the sheet), so it is drawn wide enough to be a 48 dp target.
        dragHandle = { BottomSheetDefaults.DragHandle(width = 48.dp) },
        // The sheet reaches under the navigation bar; the content below keeps clear of it, so nothing sits behind the gesture bar.
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        modifier = modifier.testTag("host_sheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Hosts", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                IconButton(onClick = onRefresh, modifier = Modifier.testTag("refresh_hosts")) { Icon(AppIcons.Refresh, contentDescription = "Refresh hosts") }
            }
            if (connectedHost != null) ConnectedCard(connectedHost, connection, { onDisconnect(); close() }, color = card)
            // The heading is drawn only with something under it; with a host connected and nothing else to show, a plain line says so.
            if (listing) Column(verticalArrangement = Arrangement.spacedBy(Design.Gap)) {
                if (connectedHost != null) SectionHeader("Other hosts")
                when (val list = catalog.list) {
                    HostList.Loading -> Busy("Looking for hosts…", Modifier.padding(8.dp).testTag("hosts_loading"))
                    is HostList.Failed -> ErrorText(list.message, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("hosts_error"))
                    HostList.Empty -> Text(
                        "No VS Code hosts found. In VS Code, turn on Remote Tunnel Access, signed in to the same GitHub account as this app.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("hosts_empty"),
                    )
                    is HostList.Loaded, HostList.NotSignedIn -> {}
                }
                if (signInHint) {
                    Text(
                        "Not signed in to GitHub, so your VS Code hosts are not listed. Disconnect from this host, then sign in from Settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("sign_in_hint"),
                    )
                }
                others.forEachIndexed { index, other ->
                    val shape = connectedShape(index, others.size)
                    when (other) {
                        is OtherHost.Tunnel -> HostRow(other.entry, shape, { onSwitch(HostRef.Tunnel(it)); close() }, color = card)
                        is OtherHost.Direct -> DirectRow(other.entry, shape, { onSwitch(HostRef.Direct(it)); close() }, onRemove = null, color = card)
                    }
                }
            } else if (connectedHost != null) {
                Text("No other hosts.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("no_other_hosts"))
            }
            if (onAddHost != null) {
                OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("add_host")) {
                    Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Add a host by address")
                }
            }
            if (catalog.list == HostList.NotSignedIn && onSignIn != null) {
                TileCard(
                    onClick = onSignIn,
                    onClickLabel = "Sign in with GitHub",
                    color = card,
                    modifier = Modifier.heightIn(min = 56.dp).testTag("sign_in_from_hosts"),
                ) {
                    Text("Sign in with GitHub to see your VS Code hosts", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp))
                }
            }
            TextButton(onClick = { onManageHosts(); close() }, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).testTag("manage_hosts")) { Text("Manage hosts") }
        }
    }
    if (adding && onAddHost != null) {
        AddHostDialog(onDismiss = { adding = false }, onAdd = onAddHost, onAdded = { adding = false })
    }
}

/** The host connected to now, and the way to leave it without picking another. */
@Composable
internal fun ConnectedCard(host: HostRef, connection: ConnState?, onDisconnect: () -> Unit, color: Color = MaterialTheme.colorScheme.surfaceContainerLow) {
    TileCard(color = color, modifier = Modifier.testTag("connected_host")) {
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
            TextButton(onClick = onDisconnect, modifier = Modifier.heightIn(min = 48.dp).testTag("disconnect")) { Text("Disconnect") }
        }
    }
}

@Composable
internal fun HostRow(entry: HostEntry, shape: Shape, onConnect: (TunnelInfo) -> Unit, color: Color = MaterialTheme.colorScheme.surfaceContainerLow) {
    val tunnel = entry.tunnel
    TileCard(
        shape = shape,
        onClick = { onConnect(tunnel) },
        onClickLabel = "Connect to ${tunnel.name}",
        color = color,
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
                        append(" · v").append(tunnel.protocolVersion)
                        if (entry.isLastUsed) append(" · last used")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!tunnel.isOnline) Text("VS Code is not connected. Try anyway, or turn on Remote Tunnel Access there.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * A host added by address: its name and where it is, with a way to connect to it when [onConnect] is given and to take it away when
 * [onRemove] is. [connected] marks the one this phone is connected to now.
 */
@Composable
internal fun DirectRow(
    entry: DirectEntry,
    shape: Shape,
    onConnect: ((DirectHost) -> Unit)?,
    onRemove: (() -> Unit)?,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    connected: Boolean = false,
) {
    val host = entry.host
    TileCard(
        shape = shape,
        onClick = onConnect?.let { connect -> { connect(host) } },
        onClickLabel = "Connect to ${host.name}",
        color = color,
        modifier = Modifier.heightIn(min = 56.dp).testTag("direct_${host.id}"),
    ) {
        Row(modifier = Modifier.padding(start = 16.dp, end = if (onRemove != null) 4.dp else 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(host.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(host.url.substringAfter("://"))
                        if (host.url.startsWith("ws://")) append(" · not encrypted")
                        if (connected) append(" · connected")
                        if (entry.isLastUsed) append(" · last used")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.size(48.dp).testTag("remove_${host.id}")) {
                    Icon(AppIcons.Clear, contentDescription = "Remove ${host.name}")
                }
            }
        }
    }
}

/**
 * Asks for a host's address and token. [onAdd] says why it could not be added, or null when it was; the token is never shown, never kept
 * in the saved state of the screen, and is dropped from here as soon as the dialog closes.
 */
@Composable
internal fun AddHostDialog(onDismiss: () -> Unit, onAdd: suspend (String, String, String) -> String?, onAdded: () -> Unit) {
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
