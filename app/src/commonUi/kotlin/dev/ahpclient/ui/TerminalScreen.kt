package dev.ahpclient.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import dev.ahpclient.state.WrapView
import com.microsoft.agenthostprotocol.generated.TerminalState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The live output of one terminal a tool call ran a command in. Read only: there is no way to type into it, and nothing here
 * sends anything. The output is the host's own text, shown as plain text only (see [TerminalView]); the end of it follows new
 * output until the person scrolls away, and a button brings them back. [state] is null until the host has sent the terminal,
 * and [error] says why it never will: the terminal is gone, or the host's answer could not be read. [fromHost] is set for one of the
 * host's own terminals, opened from the side menu: Back then goes to where the person was, and a gone one has no tool result to point at.
 */
@Composable
fun TerminalScreen(state: TerminalState?, error: Throwable?, connection: ConnState, onBack: () -> Unit, modifier: Modifier = Modifier, fromHost: Boolean = false) {
    val view = remember(state) { state?.let(TerminalView::from) }
    val wrap = rememberLineWrap(WrapView.TERMINAL)
    ScreenScaffold(
        title = view?.title ?: "Terminal",
        titleTag = "terminal_title",
        onBack = onBack,
        backLabel = if (fromHost) "Back" else "Back to chat",
        large = false,
        modifier = modifier,
        actions = { if (view != null && view.lines.isNotEmpty()) WrapToggle(wrap, WrapView.TERMINAL) },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (connection != ConnState.Online) ConnectionLine(connection)
                view?.let {
                    // Said, not only coloured, and announced when it changes: a command that finishes while the screen is open.
                    Text(
                        listOfNotNull(it.status, it.cwd).joinToString(" · "),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("terminal_status"),
                    )
                }
            }
            val sideMargin = Modifier.padding(horizontal = 24.dp)
            when {
                view != null -> TerminalBody(view, wrap.on)
                error is ChannelGoneException -> Text(
                    if (fromHost) "This terminal is no longer on the host." else "This terminal is no longer on the host. Its output is in the tool's result.",
                    sideMargin.testTag("terminal_gone"),
                )
                error != null -> ErrorText("The host's answer for this terminal could not be read.", sideMargin.testTag("terminal_failed"))
                else -> Busy("Loading the terminal…", sideMargin.testTag("terminal_loading"))
            }
        }
    }
}

/** [wrap] off keeps every line whole: the list is then as wide as the widest line and scrolls sideways as one block, so the rows stay lined up. */
@Composable
private fun TerminalBody(view: TerminalView, wrap: Boolean) {
    val listState = rememberLazyListState()
    val sideways = rememberScrollState()
    val lineStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val commandStyle = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace)
    // The command row also holds its exit code; the side margins are 24 dp each.
    val wholeWidth = if (wrap) {
        null
    } else {
        maxOf(widestLine(view.lines, lineStyle), widestLine(view.commands.map { "$ ${it.commandLine}" }, commandStyle) + COMMAND_END) + 48.dp
    }
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
    // A change in the room the list has (the system bars arriving after the first layout, a rotation) moves the end out from
    // under a list that was following it, with no new output to bring it back. Not a scroll by the person, so it leaves `following` alone.
    val lastLine by rememberUpdatedState(last)
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.viewportSize.height }.distinctUntilChanged().collect {
            if (following && lastLine >= 0) listState.scrollToItem(lastLine)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                "Read only, as plain text.",
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
                    // The screen's width is passed on as the least the list may be, so a terminal of short lines still fills it.
                    val size = if (wholeWidth == null) Modifier.fillMaxSize() else Modifier.fillMaxSize().horizontalScroll(sideways).width(wholeWidth)
                    LazyColumn(state = listState, modifier = size.testTag("terminal_list")) {
                        itemsIndexed(view.lines, key = { index, _ -> index }) { index, line ->
                            val command = view.commands.firstOrNull { it.lineIndex == index }
                            if (command != null) {
                                // One thing to a screen reader: the command and how it ended.
                                Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("terminal_command_$index")) {
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp, vertical = 3.dp))
                                    androidx.compose.foundation.layout.Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            "$ ${command.commandLine}",
                                            style = commandStyle,
                                            softWrap = wrap,
                                            modifier = Modifier.weight(1f),
                                        )
                                        val exit = command.exitCode
                                        if (command.isComplete && exit != null) {
                                            Text("Exit $exit", style = MaterialTheme.typography.labelSmall)
                                        } else if (!command.isComplete) {
                                            Text("Running", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    line.ifEmpty { " " },
                                    style = lineStyle,
                                    softWrap = wrap,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag("terminal_line"),
                                )
                            }
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

/** Room left after a command for its exit code ("Exit 127" or "Running") when lines are kept whole. */
private val COMMAND_END = 72.dp
