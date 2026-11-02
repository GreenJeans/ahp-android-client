package dev.ahpclient.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.LinkTarget

/**
 * Asks before a link in agent output is opened, and says where it goes. Link text can say one thing and point at another, so the
 * real site, address or number is shown on its own line. Only a [LinkTarget] gets here: a link that goes nowhere on a phone is never
 * made tappable, so there is no dialog for one. A web link can also be shared; a mail address or a number is not something a person
 * shares, so those have no Share; nor has a web link when the caller gives no way to share it ([onShare] null).
 */
@Composable
fun LinkConfirmDialog(target: LinkTarget, onOpen: () -> Unit, onShare: (() -> Unit)?, onDismiss: () -> Unit) {
    val (title, full) = when (target) {
        is LinkTarget.Web -> "Open link?" to target.url
        is LinkTarget.Mail -> "Write to?" to null
        is LinkTarget.Phone -> "Dial?" to null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("link_dialog"),
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(target.shown, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("link_host"))
                full?.let { Text(shown(it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("link_url")) }
            }
        },
        confirmButton = { TextButton(onClick = onOpen, modifier = Modifier.testTag("link_open")) { Text("Open") } },
        dismissButton = {
            Row {
                if (target is LinkTarget.Web && onShare != null) TextButton(onClick = onShare, modifier = Modifier.testTag("link_share")) { Text("Share") }
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("link_cancel")) { Text("Cancel") }
            }
        },
    )
}

private const val MAX_SHOWN = 400

/** The link as written, cut short if it is enormous, so the dialog stays readable. */
private fun shown(url: String): String = if (url.length > MAX_SHOWN) url.take(MAX_SHOWN) + "…" else url