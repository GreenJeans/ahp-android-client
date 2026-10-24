package dev.ahpclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.ChannelGoneException
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.TerminalView
import com.microsoft.agenthostprotocol.generated.TerminalState
import kotlinx.coroutines.launch

/**
 * The live output of one terminal a tool call ran a command in. Read only: there is no way to type into it, and nothing here
 * sends anything. The output is the host's own text, shown as plain text only (see [TerminalView]); the end of it follows new
 * output until the person scrolls away, and a button brings them back. [state] is null until the host has sent the terminal,
 * and [error] says why it never will: the terminal is gone, or the host's answer could not be read.
 */
@Composable
fun TerminalScreen(state: TerminalState?, error: Throwable?, connection: ConnState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val view = remember(state) { state?.let(TerminalView::from) }
    ScreenScaffold(title = view?.title ?: "Terminal", titleTag = "terminal_title", onBack = onBack, backLabel = "Back to chat", large = false, modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (connection != ConnState.Online) ConnectionLine(connection)
                view?.let {
                    // Said, not only coloured, and announced when it changes: a command that finishes while the screen is open.
                    Text(it.status, style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("terminal_status"))
                    it.cwd?.let { cwd ->
                        Text(cwd, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("terminal_cwd"))
                    }
                }
            }
            val sideMargin = Modifier.padding(horizontal = 24.dp)
            when {
                view != null -> TerminalBody(view)
                error is ChannelGoneException -> Text(
                    "This terminal is no longer on the host. What it printed is in the tool's result.",
                    sideMargin.testTag("terminal_gone"),
                )
                error != null -> ErrorText("The host's answer for this terminal could not be read.", sideMargin.testTag("terminal_failed"))
                else -> Busy("Loading the terminal…", sideMargin.testTag("terminal_loading"))
            }
        }
    }
}

@Composable
private fun TerminalBody(view: TerminalView) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var following by remember { mutableStateOf(true) }
    val last = view.lines.size - 1

    // Only a scroll by the person changes whether the end is followed: new output arriving must not look like leaving it.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to !listState.canScrollForward }.collect { (scrolling, atEnd) -> if (scrolling) following = atEnd }
    }
    LaunchedEffect(view.lines.size, view.lines.lastOrNull()) {
        if (following && last >= 0) listState.scrollToItem(last)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                "Read only. Shown as plain text: colours and cursor movement are left out.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).testTag("terminal_note"),
            )
            if (view.omittedLines > 0) {
                Text(
                    "The first ${view.omittedLines} lines are not shown.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 24.dp).testTag("terminal_omitted"),
                )
            }
            if (view.lines.isEmpty()) {
                Text("No output yet.", modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).testTag("terminal_empty"))
            } else {
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("terminal_list")) {
                        itemsIndexed(view.lines, key = { index, _ -> index }) { _, line ->
                            Text(
                                line.ifEmpty { " " },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag("terminal_line"),
                            )
                        }
                    }
                }
            }
        }
        if (!following && last >= 0) {
            Button(
                onClick = {
                    following = true
                    scope.launch { listState.scrollToItem(last) }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).heightIn(min = 48.dp).testTag("terminal_latest"),
            ) { Text("Latest") }
        }
    }
}
