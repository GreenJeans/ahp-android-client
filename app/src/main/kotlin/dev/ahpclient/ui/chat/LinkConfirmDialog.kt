package dev.ahpclient.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import dev.ahpclient.app.SafeLinks

/**
 * Asks before a link in agent output is opened, and says where it goes. Link text can say one thing and point at another,
 * so the real host is shown on its own line. A link this app would never launch gets an explanation and no Open button.
 */
@Composable
fun LinkConfirmDialog(url: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val safe = SafeLinks.isSafeWebLink(url)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("link_dialog"),
        title = { Text(if (safe) "Open this link?" else "This link can't be opened") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (safe) {
                    Text(hostOf(url), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("link_host"))
                } else {
                    Text("This app only opens https links to a named site.", style = MaterialTheme.typography.bodyMedium)
                }
                Text(shown(url), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("link_url"))
            }
        },
        confirmButton = {
            if (safe) TextButton(onClick = onOpen, modifier = Modifier.testTag("link_open")) { Text("Open") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("link_cancel")) { Text(if (safe) "Cancel" else "OK") }
        },
    )
}

private const val MAX_SHOWN = 400

private fun hostOf(url: String): String = try {
    java.net.URI(url).host.orEmpty()
} catch (_: java.net.URISyntaxException) {
    ""
}

/** The link as written, cut short if it is enormous, so the dialog stays readable. */
private fun shown(url: String): String = if (url.length > MAX_SHOWN) url.take(MAX_SHOWN) + "…" else url
