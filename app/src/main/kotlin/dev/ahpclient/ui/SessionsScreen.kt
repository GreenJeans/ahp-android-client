package dev.ahpclient.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.SessionCatalog
import dev.ahpclient.state.SessionListModel
import dev.ahpclient.state.SessionRow
import dev.ahpclient.state.label
import dev.ahpclient.tunnel.TunnelInfo
import com.microsoft.agenthostprotocol.generated.SessionSummary
import java.time.Instant

/**
 * The host's sessions, those needing the user first. Read-only: a tap opens the detail, and nothing here changes a session.
 * [showArchived] and [listState] are held by the caller so they survive a trip into a session and back.
 */
@Composable
fun SessionsScreen(
    host: TunnelInfo,
    connection: ConnState,
    sessions: List<SessionSummary>,
    now: Instant,
    showArchived: Boolean,
    listState: LazyListState,
    onToggleArchived: () -> Unit,
    onOpen: (String) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
    monitorEnabled: Boolean = false,
    notificationsAllowed: Boolean = true,
    onMonitorChange: (Boolean) -> Unit = {},
    interactionAllowed: Boolean = false,
    interactionSupported: Boolean = true,
    onInteractionChange: (Boolean) -> Unit = {},
    managementAllowed: Boolean = false,
    onManagementChange: (Boolean) -> Unit = {},
    manage: ManageActionsUi? = null,
    settingsExpanded: Boolean = false,
) {
    val model = remember(sessions, showArchived, now) { SessionListModel.from(SessionCatalog.sorted(sessions), showArchived, now) }
    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(host.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            ConnectionLine(connection)
            if (model.summary.isNotEmpty()) {
                Text(model.summary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("summary"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.testTag("disconnect")) { Text("Back to hosts") }
                if (model.hiddenArchived > 0 || showArchived) {
                    FilterChip(
                        selected = showArchived,
                        onClick = onToggleArchived,
                        label = { Text(if (model.hiddenArchived > 0) "Show archived (${model.hiddenArchived})" else "Show archived") },
                        modifier = Modifier.testTag("archived_toggle"),
                    )
                }
                if (manage != null) NewSessionButton(manage, onCreated = onOpen)
            }
            SettingsSection(
                summary = listOf(
                    if (monitorEnabled) "Monitoring on" else "Monitoring off",
                    if (interactionAllowed) "Acting on" else "Acting off",
                    if (managementAllowed) "Managing on" else "Managing off",
                ).joinToString(" · "),
                initiallyExpanded = settingsExpanded,
            ) {
                MonitorToggle(monitorEnabled, notificationsAllowed, onMonitorChange)
                InteractionToggle(interactionAllowed, onInteractionChange, supported = interactionSupported)
                ManagementToggle(managementAllowed, available = interactionAllowed && interactionSupported, onChange = onManagementChange)
            }
        }
        if (model.rows.isEmpty()) {
            EmptyList(model, connection)
        } else {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().testTag("session_list")) {
                items(model.rows, key = { it.resource }) { row ->
                    SessionRowItem(row, onOpen)
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * The switches (background monitoring, acting for the user, looking after sessions) folded behind one row, so they do not
 * take the screen the session list needs. Collapsed by default; the row says in a line what is on. It is one control, so
 * its words and its state are read together.
 */
@Composable
private fun SettingsSection(summary: String, initiallyExpanded: Boolean, content: @Composable () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Column(modifier = Modifier.fillMaxWidth().testTag("settings_section")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = if (expanded) "Hide settings" else "Show settings", role = Role.Button) { expanded = !expanded }
                .semantics(mergeDescendants = true) { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .testTag("settings_toggle"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Settings", style = MaterialTheme.typography.titleSmall)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("settings_summary"))
            }
            Text(if (expanded) "▴" else "▾", style = MaterialTheme.typography.titleMedium)
        }
        if (expanded) content()
    }
}

/**
 * The background-monitoring switch. The whole row is the control, so the label and its state are read together and the
 * touch target is the full width. The words under it say what the current setting does, including when notifications
 * are blocked and the user would not be told.
 */
@Composable
private fun MonitorToggle(enabled: Boolean, notificationsAllowed: Boolean, onChange: (Boolean) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().testTag("monitor_section")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = enabled, role = Role.Switch, onValueChange = onChange)
                .testTag("monitor_toggle"),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Monitor in background", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = null)
        }
        Text(
            when {
                !enabled -> "Off: the list updates only while this app is open."
                notificationsAllowed -> "On: stays connected with the screen off and notifies you when a session needs input."
                else -> "On, but notifications are blocked for this app, so you will not be told when a session needs input. Allow them in Android settings."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled && !notificationsAllowed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("monitor_hint"),
        )
    }
}

@Composable
private fun EmptyList(model: SessionListModel, connection: ConnState) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp).testTag("empty_list")) {
        when {
            model.total > 0 -> Text("All ${model.total} sessions are archived. Use Show archived to see them.", style = MaterialTheme.typography.bodyLarge)
            connection == ConnState.Online -> Text("No sessions on this host yet.", style = MaterialTheme.typography.bodyLarge)
            connection is ConnState.Failed -> Unit
            else -> Busy("Loading sessions…")
        }
    }
}

/** One row. Its spoken description joins every part, so TalkBack reads the title, state and age as a single item. */
@Composable
private fun SessionRowItem(row: SessionRow, onOpen: (String) -> Unit) {
    val description = listOfNotNull(
        row.title,
        row.state.label,
        row.subtitle,
        row.whenText.ifEmpty { null },
        if (row.isRead) null else "unread",
        if (row.isArchived) "archived" else null,
    ).joinToString(", ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .clickable(onClickLabel = "Open session", role = Role.Button) { onOpen(row.resource) }
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .testTag("session_row"),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                row.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (row.isRead) FontWeight.Normal else FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            row.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusChip(row.state)
            if (row.whenText.isNotEmpty()) {
                Text(row.whenText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
