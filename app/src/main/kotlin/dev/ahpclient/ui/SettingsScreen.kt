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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.TextScale
import dev.ahpclient.state.ThemeMode
import kotlin.math.roundToInt

/**
 * Settings, away from the chat: how the app looks (the size of the text in chats, the colours, the screen staying on), watching in the
 * background, and what the app may do for you. Each switch keeps the words that say what it does, and the two that change what the
 * app can do still ask first in a dialog.
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
    textScale: Float = TextScale.DEFAULT,
    onTextScale: (Float) -> Unit = {},
    theme: ThemeMode = ThemeMode.SYSTEM,
    onTheme: (ThemeMode) -> Unit = {},
    keepAwake: Boolean = false,
    onKeepAwake: (Boolean) -> Unit = {},
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
            SectionHeader("Display")
            TileCard(shape = connectedShape(0, 3)) { TilePadding { TextSizeRow(textScale, onTextScale) } }
            Spacer(Modifier.height(Design.Gap))
            TileCard(shape = connectedShape(1, 3)) { TilePadding { ThemeRow(theme, onTheme) } }
            Spacer(Modifier.height(Design.Gap))
            TileCard(shape = connectedShape(2, 3)) { TilePadding { KeepAwakeToggle(keepAwake, onKeepAwake) } }
            SectionHeader("Background")
            TileCard(shape = connectedShape(0, 1)) {
                TilePadding { MonitorToggle(monitorEnabled, notificationsAllowed, onMonitorChange) }
            }
            SectionHeader("Permissions")
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

/** The size of the text in chats: a slider in even steps, the same size a pinch on a chat sets, with a way back to normal. */
@Composable
private fun TextSizeRow(scale: Float, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("text_size_section")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Text size", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(TextScale.percent(scale), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("text_size_value"))
            TextButton(onClick = { onChange(TextScale.DEFAULT) }, enabled = scale != TextScale.DEFAULT, modifier = Modifier.heightIn(min = 48.dp).testTag("text_size_reset")) { Text("Reset") }
        }
        val steps = ((TextScale.MAX - TextScale.MIN) / TextScale.STEP).roundToInt() - 1
        Slider(
            value = TextScale.clamp(scale),
            onValueChange = { onChange(TextScale.snap(it)) },
            valueRange = TextScale.MIN..TextScale.MAX,
            steps = steps,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Text size" }.testTag("text_size_slider"),
        )
        Text("Pinch a chat to change it too.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                !enabled -> "Off: the list updates only while the app is open."
                notificationsAllowed -> "On: stays connected with the screen off and notifies you when a session needs you."
                else -> "On, but notifications are blocked, so you won't be told. Allow them in Android settings."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled && !notificationsAllowed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("monitor_hint"),
        )
    }
}
