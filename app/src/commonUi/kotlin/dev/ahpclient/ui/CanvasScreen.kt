package dev.ahpclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.microsoft.agenthostprotocol.generated.CanvasState
import dev.ahpclient.state.ChannelGoneException
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.LinkTarget
import dev.ahpclient.ui.chat.LinkConfirmDialog

/** Read-only details for a live canvas channel, with untrusted web URLs confirmed before leaving the app. */
@Composable
fun CanvasScreen(
    state: CanvasState?,
    error: Throwable?,
    connection: ConnState,
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
    onShareLink: ((String) -> Unit)? = null,
) {
    val title = state?.title?.takeIf { it.isNotBlank() } ?: state?.extensionName?.takeIf { it.isNotBlank() } ?: "Canvas"
    val safeUrl = remember(state?.url) { state?.url?.takeIf { it.startsWith("https://", ignoreCase = true) && LinkTarget.of(it) is LinkTarget.Web } }
    var pendingUrl by remember(state?.instanceId, state?.url) { mutableStateOf<String?>(null) }
    val target = pendingUrl?.let(LinkTarget::of)
    ScreenScaffold(title = title.take(120), titleTag = "canvas_title", onBack = onBack, backLabel = "Back to chat", large = false) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (connection != ConnState.Online) ConnectionLine(connection)
            when {
                state != null -> {
                    state.extensionName?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }) }
                    Text("Canvas ${state.canvasId.take(120)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("canvas_id"))
                    state.status?.takeIf { it.isNotBlank() }?.let { Text(it.take(300), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("canvas_status")) }
                    if (safeUrl != null) {
                        OutlinedButton(
                            onClick = { pendingUrl = safeUrl },
                            modifier = Modifier.fillMaxWidth().testTag("canvas_open_link"),
                        ) { Text("Open canvas link") }
                    }
                }
                error is ChannelGoneException -> Text("This canvas is no longer available on the host.", modifier = Modifier.testTag("canvas_gone"))
                error != null -> Text("The host's canvas details could not be read.", modifier = Modifier.testTag("canvas_failed"))
                else -> Text("Loading canvas details…", modifier = Modifier.testTag("canvas_loading"))
            }
        }
    }
    target?.let { link ->
        LinkConfirmDialog(
            target = link,
            onOpen = { pendingUrl?.let(onOpenLink); pendingUrl = null },
            onShare = onShareLink?.let { share -> { pendingUrl?.let(share); pendingUrl = null } },
            onDismiss = { pendingUrl = null },
        )
    }
}
