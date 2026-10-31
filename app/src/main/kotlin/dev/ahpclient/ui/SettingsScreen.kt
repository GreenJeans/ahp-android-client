package dev.ahpclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ahpclient.app.BillingResult
import dev.ahpclient.app.DirectEntry
import dev.ahpclient.direct.DirectHost
import dev.ahpclient.state.TextScale
import dev.ahpclient.state.ThemeMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val dateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

/**
 * Settings, away from the chat: the hosts (the GitHub sign-in, and the hosts added by address), how the app looks (the size of the text
 * in chats, the colours, the screen staying on), watching in the background, and what the app may do for you. Each switch keeps the
 * words that say what it does, and the two that change what the app can do still ask first in a dialog. Choosing a host is not here:
 * that is the hosts sheet of the side menu.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** False when no GitHub sign-in is held: the section then offers to sign in, and there is nothing to sign out of. */
    signedIn: Boolean = false,
    /** When the GitHub access token runs out; with [renews] the app trades it for a new one on its own, and no end is shown. */
    tokenExpiresAt: Instant? = null,
    renews: Boolean = false,
    /** Null where a sign-in cannot be started from (with a host connected): the section then says so in place of the button. */
    onSignIn: (() -> Unit)? = null,
    onSignOut: () -> Unit = {},
    /** The hosts added by address, with a way to remove each; all of them, whether or not one is connected. */
    direct: List<DirectEntry> = emptyList(),
    /** Adds a host: returns why it could not be added, or null when it was. Null here hides the add button. */
    onAddHost: (suspend (name: String, address: String, token: String) -> String?)? = null,
    onRemoveHost: (DirectHost) -> Unit = {},
    /** The id of the host connected now, of either kind; null with none. Signing out or removing that host ends its connection, and the dialogs say so. */
    connectedId: String? = null,
    monitorEnabled: Boolean = false,
    notificationsAllowed: Boolean = true,
    onMonitorChange: (Boolean) -> Unit = {},
    finishedAll: Boolean = false,
    onFinishedAll: (Boolean) -> Unit = {},
    interactionAllowed: Boolean = false,
    interactionSupported: Boolean = true,
    onInteractionChange: (Boolean) -> Unit = {},
    managementAllowed: Boolean = false,
    /** The connected direct host these permissions are for; null when they are for the signed-in account's tunnel hosts. */
    appliesTo: String? = null,
    onManagementChange: (Boolean) -> Unit = {},
    textScale: Float = TextScale.DEFAULT,
    onTextScale: (Float) -> Unit = {},
    theme: ThemeMode = ThemeMode.SYSTEM,
    onTheme: (ThemeMode) -> Unit = {},
    keepAwake: Boolean = false,
    onKeepAwake: (Boolean) -> Unit = {},
    /** Whether Pro features (background monitoring, acting, managing, and concurrent multi-host) are unlocked. */
    proUnlocked: Boolean = true,
    /** Starts the Pro unlock flow when tapped; when null and [onPurchasePro] or [onRestorePro] is set, opens [ProUpgradeDialog]. */
    onUnlockPro: (() -> Unit)? = null,
    /** Launches the Pro purchase flow from [ProUpgradeDialog]. */
    onPurchasePro: (suspend () -> BillingResult)? = null,
    /** Restores an existing Pro purchase from [ProUpgradeDialog]. */
    onRestorePro: (suspend () -> BillingResult)? = null,
    /** Debug-only callback to toggle between Free and Pro tiers directly from Settings; null in release builds and standard tests. */
    onDebugProChange: ((Boolean) -> Unit)? = null,
) {
    ScreenScaffold(title = "Settings", onBack = onBack, backLabel = "Back", titleTag = "settings_title", modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Design.ScreenPadding)
                .padding(bottom = 16.dp)
                .testTag("settings_screen"),
        ) {
            HostsSection(signedIn, tokenExpiresAt, renews, onSignIn, onSignOut, direct, onAddHost, onRemoveHost, connectedId)
            SectionHeader("Display")
            TileCard(shape = connectedShape(0, 2)) { TilePadding { ThemeRow(theme, onTheme) } }
            Spacer(Modifier.height(Design.Gap))
            TileCard(shape = connectedShape(1, 2)) { TilePadding { KeepAwakeToggle(keepAwake, onKeepAwake) } }
            if (!proUnlocked || onDebugProChange != null) {
                ProSection(proUnlocked, onUnlockPro, onPurchasePro, onRestorePro, onDebugProChange)
            }
            SectionHeader("Background")
            TileCard(shape = connectedShape(0, 2)) {
                TilePadding { MonitorToggle(monitorEnabled, notificationsAllowed, onMonitorChange, proUnlocked = proUnlocked) }
            }
            Spacer(Modifier.height(Design.Gap))
            TileCard(shape = connectedShape(1, 2)) {
                TilePadding { FinishedAllToggle(finishedAll, monitorEnabled, onFinishedAll, proUnlocked = proUnlocked) }
            }
            SectionHeader("Permissions")
            Text(
                if (appliesTo != null) "Applies to “$appliesTo” only." else "Applies to your VS Code tunnel hosts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).testTag("permissions_scope"),
            )
            TileCard(shape = connectedShape(0, 1)) {
                TilePadding {
                    InteractionToggle(
                        allowed = interactionAllowed,
                        onChange = { on ->
                            onInteractionChange(on)
                            onManagementChange(on)
                        },
                        supported = interactionSupported,
                        proUnlocked = proUnlocked,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProSection(
    proUnlocked: Boolean,
    onUnlockPro: (() -> Unit)?,
    onPurchasePro: (suspend () -> BillingResult)?,
    onRestorePro: (suspend () -> BillingResult)?,
    onDebugProChange: ((Boolean) -> Unit)?,
) {
    var showingDialog by rememberSaveable { mutableStateOf(false) }
    val hasDialogFlow = onPurchasePro != null || onRestorePro != null
    val action: (() -> Unit)? = onUnlockPro ?: if (hasDialogFlow) ({ showingDialog = true }) else null
    SectionHeader("Pro")
    if (!proUnlocked) {
        TileCard(
            shape = if (onDebugProChange != null) connectedShape(0, 2) else connectedShape(0, 1),
            modifier = Modifier.testTag("pro_section"),
        ) {
            TilePadding {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Unlock Pro", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Free watches any session on one host. Pro unlocks acting on sessions, background alerts, and connecting to multiple hosts.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("pro_description"),
                    )
                    if (action != null) {
                        Button(
                            onClick = action,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("unlock_pro"),
                        ) {
                            Text("Unlock Pro")
                        }
                    }
                }
            }
        }
        if (onDebugProChange != null) {
            Spacer(Modifier.height(Design.Gap))
        }
    }
    if (onDebugProChange != null) {
        TileCard(
            shape = if (!proUnlocked) connectedShape(1, 2) else connectedShape(0, 1),
        ) {
            TilePadding {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(
                                value = proUnlocked,
                                role = Role.Switch,
                                onValueChange = onDebugProChange,
                            )
                            .testTag("debug_pro_toggle"),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Pro tier (debug preview)", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(checked = proUnlocked, onCheckedChange = null)
                    }
                    Text(
                        "Toggle to test Free vs. Pro.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (showingDialog && hasDialogFlow) {
        ProUpgradeDialog(
            onDismiss = { showingDialog = false },
            onPurchase = onPurchasePro,
            onRestore = onRestorePro,
        )
    }
}

@Composable
private fun ProUpgradeDialog(
    onDismiss: () -> Unit,
    onPurchase: (suspend () -> BillingResult)?,
    onRestore: (suspend () -> BillingResult)?,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }

    fun handle(call: suspend () -> BillingResult) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                when (val res = call()) {
                    BillingResult.Unlocked -> onDismiss()
                    BillingResult.Cancelled -> {}
                    BillingResult.NotFound -> message = "No previous Pro purchase was found."
                    is BillingResult.Unavailable -> message = res.message
                }
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.testTag("pro_upgrade_dialog"),
        title = { Text("AHP Client Pro") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "One-time unlock for acting on sessions, background alerts, and connecting to multiple hosts.",
                )
                val msg = message
                if (msg != null) {
                    Text(
                        msg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("pro_upgrade_message"),
                    )
                }
            }
        },
        confirmButton = {
            if (onPurchase != null) {
                Button(
                    onClick = { handle(onPurchase) },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("pro_purchase"),
                ) {
                    Text("Purchase Pro")
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onRestore != null) {
                    TextButton(
                        onClick = { handle(onRestore) },
                        enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("pro_restore"),
                    ) {
                        Text("Restore purchase")
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("pro_cancel"),
                ) {
                    Text("Close")
                }
            }
        },
    )
}

/**
 * Where hosts are managed: the GitHub sign-in (which lists the VS Code hosts), and the hosts added by address with a remove button
 * each. Both ask first before they forget anything. Which host to use is chosen in the side menu, not here.
 */
@Composable
private fun HostsSection(
    signedIn: Boolean,
    tokenExpiresAt: Instant?,
    renews: Boolean,
    onSignIn: (() -> Unit)?,
    onSignOut: () -> Unit,
    direct: List<DirectEntry>,
    onAddHost: (suspend (name: String, address: String, token: String) -> String?)?,
    onRemoveHost: (DirectHost) -> Unit,
    connectedId: String?,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<DirectHost?>(null) }
    var confirmingSignOut by rememberSaveable { mutableStateOf(false) }
    SectionHeader("Hosts")
    TileCard(modifier = Modifier.testTag("github_section")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("GitHub", style = MaterialTheme.typography.bodyLarge)
                // A sign-in the app renews on its own has no end worth showing: the access token it holds lasts hours, but the sign-in does not.
                val status = when {
                    !signedIn && onSignIn != null -> "Not signed in."
                    !signedIn -> "Not signed in. Disconnect to sign in."
                    renews -> "Signed in. Renews itself."
                    tokenExpiresAt != null -> "Signed in until ${dateTimeFormat.format(tokenExpiresAt)}."
                    else -> "Signed in."
                }
                Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("token_expiry"))
            }
            if (signedIn) {
                TextButton(onClick = { confirmingSignOut = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("sign_out")) { Text("Sign out") }
            } else if (onSignIn != null) {
                TextButton(onClick = onSignIn, modifier = Modifier.heightIn(min = 48.dp).testTag("sign_in")) { Text("Sign in with GitHub") }
            }
        }
    }
    SectionHeader("Hosts you added")
    if (direct.isEmpty()) {
        Text(
            "None added yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).testTag("no_direct_hosts"),
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(Design.Gap)) {
            direct.forEachIndexed { index, entry ->
                DirectRow(entry, connectedShape(index, direct.size), onConnect = null, onRemove = { removing = entry.host }, connected = entry.host.id == connectedId)
            }
        }
    }
    if (onAddHost != null) {
        OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().padding(top = Design.Gap).heightIn(min = 48.dp).testTag("add_host")) {
            Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text("Add a host by address")
        }
    }
    if (adding && onAddHost != null) {
        AddHostDialog(onDismiss = { adding = false }, onAdd = onAddHost, onAdded = { adding = false })
    }
    removing?.let { host ->
        AlertDialog(
            onDismissRequest = { removing = null },
            modifier = Modifier.testTag("remove_host_dialog"),
            title = { Text("Remove “${host.name}”?") },
            text = {
                Text(
                    "Forgets this host, its token, and its permissions." +
                        if (host.id == connectedId) " It is connected now, so the connection ends too." else "",
                )
            },
            confirmButton = { TextButton(onClick = { removing = null; onRemoveHost(host) }, modifier = Modifier.testTag("remove_host_confirm")) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }, modifier = Modifier.testTag("remove_host_cancel")) { Text("Cancel") } },
        )
    }
    if (confirmingSignOut) {
        AlertDialog(
            onDismissRequest = { confirmingSignOut = false },
            modifier = Modifier.testTag("sign_out_dialog"),
            title = { Text("Sign out?") },
            text = {
                Text(
                    "Forgets your GitHub sign-in." +
                        if (connectedId != null) " Leaves the host connected now and returns to the sign-in screen." else "",
                )
            },
            confirmButton = { TextButton(onClick = { confirmingSignOut = false; onSignOut() }, modifier = Modifier.testTag("sign_out_confirm")) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmingSignOut = false }, modifier = Modifier.testTag("sign_out_cancel")) { Text("Cancel") } },
        )
    }
}

/** Light, dark or the system's, one of three. */
@Composable
private fun ThemeRow(theme: ThemeMode, onChange: (ThemeMode) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("theme_section")) {
        Text("Theme", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.heightIn(min = 40.dp).padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = theme == mode,
                    onClick = { onChange(mode) },
                    label = { Text(mode.label) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("theme_${mode.name.lowercase()}"),
                )
            }
        }
    }
}

/** Keeps the screen on while an agent works, so a long turn can be watched without touching the phone. */
@Composable
private fun KeepAwakeToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .testTag("keep_awake_toggle"),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Keep screen on while an agent works", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = null)
    }
}

@Composable
private fun TilePadding(content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { content() }
}

/**
 * The background-monitoring switch. The whole row is the control, so the label and its state are read together and the
 * touch target is the full width. The words under it say what the current setting does, including when notifications
 * are blocked and the user would not be told.
 */
@Composable
internal fun MonitorToggle(enabled: Boolean, notificationsAllowed: Boolean, onChange: (Boolean) -> Unit, proUnlocked: Boolean = true) {
    Column(modifier = Modifier.fillMaxWidth().testTag("monitor_section")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = enabled, enabled = proUnlocked, role = Role.Switch, onValueChange = onChange)
                .testTag("monitor_toggle"),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Monitor in background", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = null, enabled = proUnlocked)
        }
        Text(
            when {
                !proUnlocked -> "Requires Pro."
                !enabled -> "Off: updates only while open."
                notificationsAllowed -> "On: stays connected and notifies you when a session needs you."
                else -> "On, but notifications are blocked in Android settings."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled && !notificationsAllowed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("monitor_hint"),
        )
    }
}

/**
 * Whether to be told when any session finishes, not only the ones picked with the bell in a session's menu. The alerts come only
 * while background monitoring is on, and the words under the switch say so when it is not.
 */
@Composable
internal fun FinishedAllToggle(on: Boolean, monitoring: Boolean, onChange: (Boolean) -> Unit, proUnlocked: Boolean = true) {
    Column(modifier = Modifier.fillMaxWidth().testTag("finished_all_section")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = on, enabled = proUnlocked, role = Role.Switch, onValueChange = onChange)
                .testTag("finished_all_toggle"),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Alert when any session finishes", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = null, enabled = proUnlocked)
        }
        Text(
            when {
                !proUnlocked -> "Requires Pro."
                on && monitoring -> "On: you are told when a session stops."
                on -> "On, but needs Monitor in background."
                else -> "Off: per-session alerts only."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (on && !monitoring) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("finished_all_hint"),
        )
    }
}
