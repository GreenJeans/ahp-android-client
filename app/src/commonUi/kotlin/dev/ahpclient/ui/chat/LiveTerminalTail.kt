package dev.ahpclient.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.Clipped
import dev.ahpclient.state.LiveTerminal
import dev.ahpclient.state.TerminalLinks

/**
 * The end of the terminal a running command prints to, inline in its tool call's block, as the host streams it (U7). A tool
 * result only names its terminal and carries the output once the command ends, so without this the block said "No output yet."
 * until then and showed it all at once. The terminal is watched only while this is on screen: a folded block never composes
 * its body, so nothing is subscribed for it. [watch] is asked once per address, and only for an address of a terminal; what it
 * returns is closed when this leaves. It only ever reads: nothing is typed into the terminal from here.
 */
@Composable
internal fun LiveTerminalTail(uri: String, watch: (String) -> LiveTerminal?, modifier: Modifier = Modifier) {
    // The provider may be a new lambda on every pass; only a new address is a reason to watch again.
    val currentWatch by rememberUpdatedState(watch)
    var live by remember(uri) { mutableStateOf<LiveTerminal?>(null) }
    DisposableEffect(uri) {
        val watched = if (TerminalLinks.isTerminalUri(uri)) currentWatch(uri) else null
        live = watched
        onDispose { watched?.close() }
    }
    val state = live?.state?.collectAsState()?.value
    val lines = remember(state) { state?.let { LiveTerminal.tail(it) }.orEmpty() }
    Column(modifier = modifier.testTag("terminal_live"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Said only once the terminal's state is here: an address that was refused, or a host not yet answering, is not live.
        if (state != null) {
            Text(
                "Live",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("terminal_live_label"),
            )
        }
        if (lines.isEmpty()) {
            Text("No output yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            val text = lines.joinToString("\n")
            MonoBlock(Clipped(text, text.length), Modifier.testTag("terminal_live_tail"))
        }
    }
}
