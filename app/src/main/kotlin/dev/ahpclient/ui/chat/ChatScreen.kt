package dev.ahpclient.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.ChatGoneException
import dev.ahpclient.state.ChatNotLoadedException
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.Transcript
import dev.ahpclient.state.TranscriptItem
import dev.ahpclient.state.TranscriptRowModel
import dev.ahpclient.state.label
import dev.ahpclient.ui.AppIcons
import dev.ahpclient.ui.Busy
import dev.ahpclient.ui.ConnectionLine
import dev.ahpclient.ui.ErrorText
import dev.ahpclient.ui.ScreenScaffold
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** How close to the oldest loaded row the user has to scroll before the next page of older turns is asked for. */
private const val LOAD_OLDER_MARGIN = 2

/** How long "Loading chat…" runs before it says that a long chat takes a while. */
private const val SLOW_LOAD_MS = 6_000L

/**
 * One chat as a screen of its own, read-only: a top bar with the way back, the chat's title and its state, and the
 * conversation under it. This is how a subagent's transcript is shown; a session's own chats are the [ChatPane] inside the
 * session screen. [transcript] is null until the host's snapshot arrives; [error] says why it may never come.
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
    onRetry: (() -> Unit)? = null,
) {
    ScreenScaffold(
        title = transcript?.title ?: "Chat",
        subtitle = transcript?.state?.label,
        onBack = onBack,
        backLabel = backLabel,
        titleTag = "chat_title",
        large = false,
        modifier = modifier,
    ) {
        ChatPane(transcript, error, loadingOlder, olderError, connection, onLoadOlder, onOpenLink, Modifier, expanded, onToggle, actions, onOpenSubagent, onOpenTerminal, onRetry)
    }
}

/**
 * The conversation itself, with no bar of its own, for a screen that supplies one: the newest row at the bottom, the agent's
 * current activity above it, and the composer when the app may act. A link in the agent's text is only opened after the user
 * confirms it: [onOpenLink] is called with a link the user has already said yes to.
 */
@Composable
fun ChatPane(
    transcript: Transcript?,
    error: Throwable?,
    loadingOlder: Boolean,
    olderError: Throwable?,
    connection: ConnState,
    onLoadOlder: () -> Unit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    expanded: Set<String> = emptySet(),
    onToggle: (String) -> Unit = {},
    actions: ChatActionsUi? = null,
    onOpenSubagent: ((String) -> Unit)? = null,
    onOpenTerminal: ((String) -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    var pendingLink by rememberSaveable { mutableStateOf<String?>(null) }
    val notLoaded = error as? ChatNotLoadedException
    Column(modifier = modifier.fillMaxSize()) {
        if (connection != ConnState.Online || transcript?.activity != null) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (connection != ConnState.Online) ConnectionLine(connection)
                transcript?.activity?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("chat_activity")) }
            }
        }
        when {
            error is ChatGoneException -> ErrorText("This chat is no longer on the host.", Modifier.padding(horizontal = 24.dp).testTag("chat_gone"))
            notLoaded != null && transcript == null ->
                NotLoaded(notLoaded, onRetry, stale = false, Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            transcript != null -> {
                if (notLoaded != null) NotLoaded(notLoaded, onRetry, stale = true, Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                TranscriptList(transcript, loadingOlder, olderError, onLoadOlder, { pendingLink = it }, expanded, onToggle, actions, onOpenSubagent, onOpenTerminal, Modifier.weight(1f))
            }
            error != null -> ErrorText("Could not load this chat (${error.javaClass.simpleName}).", Modifier.padding(horizontal = 24.dp).testTag("chat_error"))
            else -> LoadingChat(Modifier.padding(horizontal = 24.dp))
        }
        if (actions != null && transcript != null) Composer(actions)
    }
    pendingLink?.let { link ->
        LinkConfirmDialog(url = link, onOpen = { pendingLink = null; onOpenLink(link) }, onDismiss = { pendingLink = null })
    }
}

/** Why a chat is not there (or not up to date) and the way to ask for it again. A long chat is the usual reason. */
@Composable
private fun NotLoaded(failure: ChatNotLoadedException, onRetry: (() -> Unit)?, stale: Boolean, modifier: Modifier = Modifier) {
    val why = if (failure.interrupted) "The connection kept dropping while this chat loaded." else "The host took too long to send this chat."
    Column(modifier = modifier.fillMaxWidth().testTag("chat_not_loaded"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ErrorText(if (stale) "$why What is shown is not being kept up to date." else "$why It may be too big for the tunnel, which carries at most 16 MB in one message: the host sends a chat whole.", Modifier.testTag("chat_not_loaded_text"))
        if (onRetry != null) FilledTonalButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag("chat_retry")) { Text("Try again") }
    }
}

/** A spinner with its words, and after a few seconds a line saying why it is taking a while: the wait is never silent. */
@Composable
private fun LoadingChat(modifier: Modifier = Modifier) {
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SLOW_LOAD_MS)
        slow = true
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Busy("Loading chat…", Modifier.testTag("chat_loading"))
        if (slow) {
            Text(
                "This chat is long, so it can take a while.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("chat_loading_slow"),
            )
        }
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
    val newestFirst = remember(transcript.items) { TranscriptRowModel.from(transcript.items).asReversed() }

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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            itemsIndexed(newestFirst, key = { _, row -> row.key }, contentType = { _, row -> row::class }) { index, row ->
                TranscriptRow(
                    row,
                    onOpenLink,
                    Modifier.padding(top = rowGap(row, newestFirst.getOrNull(index + 1))),
                    expanded,
                    onToggle,
                    actions,
                    onOpenSubagent,
                    onOpenTerminal,
                )
            }
            if (loadingOlder || olderError != null || transcript.hasOlder) {
                item(key = OLDER_KEY, contentType = OLDER_KEY) { OlderRow(loadingOlder, olderError, onLoadOlder) }
            }
        }
        if (away) {
            FilledTonalIconButton(
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).size(48.dp).testTag("jump_latest"),
            ) { Icon(AppIcons.ArrowDown, contentDescription = "Jump to latest") }
        }
    }
}

/**
 * The air above a row. What a person says starts a new exchange and gets the most; the first row after it sits a little apart; a
 * run of rows by the agent sits close together. The first row of all has none: the list's own padding is enough there.
 */
private fun rowGap(row: TranscriptRowModel, before: TranscriptRowModel?): Dp = when {
    before == null -> 0.dp
    row.isUser() -> 28.dp
    before.isUser() -> 16.dp
    else -> 10.dp
}

private fun TranscriptRowModel.isUser(): Boolean = this is TranscriptRowModel.Single && item is TranscriptItem.User

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
