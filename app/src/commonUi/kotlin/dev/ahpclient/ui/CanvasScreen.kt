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
import dev.ahpclient.state.CanvasDetail
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
    val detail = remember(state) { state?.let(CanvasDetail::from) }
    var pendingUrl by remember(detail?.url) { mutableStateOf<String?>(null) }
    val target = pendingUrl?.let(LinkTarget::of)
    ScreenScaffold(title = detail?.title ?: "Canvas", titleTag = "canvas_title", onBack = onBack, backLabel = "Back to chat", large = false) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (connection != ConnState.Online) ConnectionLine(connection)
            when {
                detail != null -> {
                    detail.extension?.let { Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }) }
                    detail.canvasId?.let { Text("Canvas $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("canvas_id")) }
                    detail.status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("canvas_status")) }
                    detail.url?.let { url ->
                        OutlinedButton(
                            onClick = { pendingUrl = url },
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
