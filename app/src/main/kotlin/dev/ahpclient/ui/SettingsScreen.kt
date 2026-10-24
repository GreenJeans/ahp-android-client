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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * What this app may do, in one place, away from the session list: watching in the background, acting for the user, and
 * looking after sessions. Each switch keeps the words that say what it does, and the two that change what the app can do
 * still ask first in a dialog. The switches are the same ones the list used to fold behind a row.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    monitorEnabled: Boolean = false,
    notificationsAllowed: Boolean = true,
    onMonitorChange: (Boolean) -> Unit = {},
    interactionAllowed: Boolean = false,
    interactionSupported: Boolean = true,
    onInteractionChange: (Boolean) -> Unit = {},
    managementAllowed: Boolean = false,
    onManagementChange: (Boolean) -> Unit = {},
) {
    ScreenScaffold(title = "Settings", onBack = onBack, backLabel = "Back to sessions", titleTag = "settings_title", modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Design.ScreenPadding)
                .padding(bottom = 24.dp)
                .testTag("settings_screen"),
        ) {
            SectionHeader("Background")
            TileCard(shape = connectedShape(0, 1)) {
                TilePadding { MonitorToggle(monitorEnabled, notificationsAllowed, onMonitorChange) }
            }
            SectionHeader("What this app may do")
            TileCard(shape = connectedShape(0, 2)) {
                TilePadding { InteractionToggle(interactionAllowed, onInteractionChange, supported = interactionSupported) }
            }
            Spacer(Modifier.height(Design.Gap))
            TileCard(shape = connectedShape(1, 2)) {
                TilePadding { ManagementToggle(managementAllowed, available = interactionAllowed && interactionSupported, onChange = onManagementChange) }
            }
        }
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
internal fun MonitorToggle(enabled: Boolean, notificationsAllowed: Boolean, onChange: (Boolean) -> Unit) {
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
