package dev.ahpclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * The switch that lets this app act for the user: send messages, answer questions, approve tools and stop the agent. Off
 * until turned on, and turning it on is a decision made in a dialog that says what it allows; turning it off needs no
 * question. The whole row is the control, so the label and its state are read together.
 */
@Composable
fun InteractionToggle(
    allowed: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supported: Boolean = true,
    proUnlocked: Boolean = true,
) {
    var asking by rememberSaveable { mutableStateOf(false) }
    val enabled = supported && proUnlocked
    Column(modifier = modifier.fillMaxWidth().testTag("interaction_section")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(
                    value = allowed,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = { on -> if (on) asking = true else onChange(false) },
                )
                .testTag("interaction_toggle"),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Act for me", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = allowed, onCheckedChange = null, enabled = enabled)
        }
        Text(
            when {
                !supported -> "Needs Android 11 or newer, for the device-lock check on approvals. Watch only."
                !proUnlocked -> "Requires Pro unlock. Watch only."
                allowed -> "On: send, answer, approve and stop, as you. Approvals ask for your fingerprint, face or lock."
                else -> "Off: watch only."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("interaction_hint"),
        )
    }
    if (asking && enabled) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Act for you?") },
            text = {
                Text(
                    "This app can send messages to your agents, answer their questions, approve or deny their tool calls and stop them, as you. " +
                        "Approvals ask for your fingerprint, face or screen lock first, but anyone who can unlock this phone could do the rest while it is on. " +
                        "Signing out turns it off.",
                    modifier = Modifier.testTag("interaction_dialog_text"),
                )
            },
            confirmButton = { TextButton(onClick = { asking = false; onChange(true) }, modifier = Modifier.testTag("interaction_confirm")) { Text("Turn on") } },
            dismissButton = { TextButton(onClick = { asking = false }, modifier = Modifier.testTag("interaction_cancel")) { Text("Cancel") } },
        )
    }
}
