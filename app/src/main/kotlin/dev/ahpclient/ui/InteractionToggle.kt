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
fun InteractionToggle(allowed: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    var asking by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth().testTag("interaction_section")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = allowed, role = Role.Switch, onValueChange = { on -> if (on) asking = true else onChange(false) })
                .testTag("interaction_toggle"),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Let this app act for me", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = allowed, onCheckedChange = null)
        }
        Text(
            if (allowed) {
                "On: this app can send messages, answer questions, approve tool calls and stop the agent, as you. Approving asks for your fingerprint, face or screen lock first."
            } else {
                "Off: this app only watches. Nothing you do here changes a session."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("interaction_hint"),
        )
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Let this app act for you?") },
            text = {
                Text(
                    "With this on, this app can send messages to your agents, answer their questions, approve or deny their tool calls, and stop them, as you. " +
                        "Approving a tool call asks for your fingerprint, face or screen lock first, but anyone who can unlock this phone could do the rest while it is on. " +
                        "Signing out turns it off.",
                    modifier = Modifier.testTag("interaction_dialog_text"),
                )
            },
            confirmButton = { TextButton(onClick = { asking = false; onChange(true) }, modifier = Modifier.testTag("interaction_confirm")) { Text("Turn on") } },
            dismissButton = { TextButton(onClick = { asking = false }, modifier = Modifier.testTag("interaction_cancel")) { Text("Cancel") } },
        )
    }
}
