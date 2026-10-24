package dev.ahpclient.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.ChatGoneException
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.Transcript
import dev.ahpclient.ui.Busy
import dev.ahpclient.ui.ConnectionLine
import dev.ahpclient.ui.ErrorText
import dev.ahpclient.ui.StatusChip
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** How close to the oldest loaded row the user has to scroll before the next page of older turns is asked for. */
private const val LOAD_OLDER_MARGIN = 2

/**
 * One chat, read-only: the conversation with the newest row at the bottom. [transcript] is null until the host's snapshot
 * arrives; [error] says why it may never come. A link in the agent's text is only opened after the user confirms it: [onOpenLink]
 * is called with a link the user has already said yes to.
 */
@Composable
fun ChatScreen(
    transcript: Transcript?,
    error: Throwable?,
    loadingOlder: Boolean,
    olderError: Throwable?,
    connection: ConnState,
    onBack: () -> Unit,
    onLoadOlder: () -> Unit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    expanded: Set<String> = emptySet(),
    onToggle: (String) -> Unit = {},
    actions: ChatActionsUi? = null,
    onOpenSubagent: ((String) -> Unit)? = null,
    onOpenTerminal: ((String) -> Unit)? = null,
    backLabel: String = "Back to session",
) {
    var pendingLink by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.testTag("back")) { Text(backLabel) }
            if (connection != ConnState.Online) ConnectionLine(connection)
            if (transcript != null) {
                Text(transcript.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }.testTag("chat_title"))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(transcript.state)
                    transcript.activity?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("chat_activity")) }
                }
            }
        }
        when {
            error is ChatGoneException -> ErrorText("This chat is no longer on the host.", Modifier.padding(horizontal = 24.dp).testTag("chat_gone"))
            transcript != null -> TranscriptList(transcript, loadingOlder, olderError, onLoadOlder, { pendingLink = it }, expanded, onToggle, actions, onOpenSubagent, onOpenTerminal, Modifier.weight(1f))
            error != null -> ErrorText("Could not load this chat (${error.javaClass.simpleName}).", Modifier.padding(horizontal = 24.dp).testTag("chat_error"))
            else -> Busy("Loading chat…", Modifier.padding(horizontal = 24.dp).testTag("chat_loading"))
        }
        if (actions != null && transcript != null) Composer(actions)
    }
    pendingLink?.let { link ->
        LinkConfirmDialog(url = link, onOpen = { pendingLink = null; onOpenLink(link) }, onDismiss = { pendingLink = null })
    }
}

/**
 * The rows, newest first in a reversed list, so a chat opens at its latest message however long it is, and older turns arriving
 * at the far end never move what is on screen. While the user is at the bottom, new rows keep it there; once they scroll up,
 * their place is held by row key and a button offers the way back.
 */
@Composable
private fun TranscriptList(
    transcript: Transcript,
    loadingOlder: Boolean,
    olderError: Throwable?,
    onLoadOlder: () -> Unit,
    onOpenLink: (String) -> Unit,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    actions: ChatActionsUi?,
    onOpenSubagent: ((String) -> Unit)?,
    onOpenTerminal: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val newestFirst = remember(transcript.items) { transcript.items.asReversed() }

    // Whether the user is at the newest row. It changes only when the position moves while the rows stay the same: a row arriving
    // shifts the position by itself (and changes the row count in the same layout), and must not be mistaken for the user scrolling
    // away. Watching the position, not the scroll gesture, also catches instant scrolls such as TalkBack's scroll actions.
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        var knownCount = -1
        snapshotFlow { listState.atNewest() to listState.layoutInfo.totalItemsCount }.collect { (atNewest, count) ->
            if (count == knownCount) following = atNewest
            knownCount = count
        }
    }
    val newestKey = newestFirst.firstOrNull()?.key
    LaunchedEffect(newestKey) {
        if (following && newestKey != null) listState.scrollToItem(0)
    }

    val currentLoadOlder by rememberUpdatedState(onLoadOlder)
    LaunchedEffect(listState, transcript.hasOlder) {
        if (!transcript.hasOlder) return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 1 - LOAD_OLDER_MARGIN
        }.distinctUntilChanged().filter { it }.collect { currentLoadOlder() }
    }

    val away by remember { derivedStateOf { !listState.atNewest() } }
    Box(modifier = modifier.fillMaxSize()) {
        if (newestFirst.isEmpty() && !transcript.hasOlder) {
            Text("No messages yet.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(24.dp).testTag("chat_empty"))
        }
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize().testTag("transcript"),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
        ) {
            items(newestFirst, key = { it.key }, contentType = { it::class }) { row ->
                TranscriptRow(row, onOpenLink, expanded = row.key in expanded, onToggle = { onToggle(row.key) }, actions = actions, onOpenSubagent = onOpenSubagent, onOpenTerminal = onOpenTerminal)
            }
            if (loadingOlder || olderError != null || transcript.hasOlder) {
                item(key = OLDER_KEY, contentType = OLDER_KEY) { OlderRow(loadingOlder, olderError, onLoadOlder) }
            }
        }
        if (away) {
            FilledTonalButton(
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("jump_latest"),
            ) { Text("Jump to latest") }
        }
    }
}

private const val OLDER_KEY = "older"

private fun LazyListState.atNewest(): Boolean = firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0

/** The top of the transcript: progress while older turns load, the reason and a retry if they could not, else a way to ask. */
@Composable
private fun OlderRow(loading: Boolean, error: Throwable?, onLoadOlder: () -> Unit) {
    when {
        loading -> Busy("Loading earlier messages…", Modifier.testTag("older_loading"))
        error != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ErrorText("Could not load earlier messages.", Modifier.testTag("older_error"))
            OutlinedButton(onClick = onLoadOlder, modifier = Modifier.testTag("older_retry")) { Text("Try again") }
        }
        else -> OutlinedButton(onClick = onLoadOlder, modifier = Modifier.testTag("older_button")) { Text("Load earlier messages") }
    }
}
