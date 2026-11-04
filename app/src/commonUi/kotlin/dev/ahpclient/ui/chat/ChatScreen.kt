package dev.ahpclient.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.microsoft.agenthostprotocol.generated.ResourceReadResult
import dev.ahpclient.state.BackgroundWorkView
import dev.ahpclient.state.CanvasView
import dev.ahpclient.state.ChatGoneException
import dev.ahpclient.state.ChatNotLoadedException
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.HostFileTarget
import dev.ahpclient.state.LinkTarget
import dev.ahpclient.state.QuickReplies
import dev.ahpclient.state.QuickReplyMode
import dev.ahpclient.state.Transcript
import dev.ahpclient.state.TranscriptItem
import dev.ahpclient.state.TranscriptLandmarks
import kotlin.math.abs
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
    onShareLink: ((String) -> Unit)? = null,
    expanded: Set<String> = emptySet(),
    onToggle: (String) -> Unit = {},
    actions: ChatActionsUi? = null,
    onOpenSubagent: ((String) -> Unit)? = null,
    onOpenTerminal: ((String) -> Unit)? = null,
    onOpenCanvas: ((String) -> Unit)? = null,
    backLabel: String = "Back to session",
    externalRichRequest: RichModalRequest? = null,
    onExternalRichConsumed: () -> Unit = {},
    onRetry: (() -> Unit)? = null,
    textScale: Float = 1f,
    onTextScale: ((Float) -> Unit)? = null,
    onReadResource: (suspend (String) -> ResourceReadResult)? = null,
    workingDirectories: List<String> = emptyList(),
    fromCache: Boolean = false,
) {
    ScreenScaffold(
        title = transcript?.title ?: "Chat",
        subtitle = transcript?.state?.label,
        onBack = onBack,
        backLabel = backLabel,
        titleTag = "chat_title",
        actions = {
            BackgroundWorkChip(
                items = transcript?.backgroundWork.orEmpty(),
                canvases = transcript?.canvases.orEmpty(),
                onOpenSubagent = onOpenSubagent,
                onOpenTerminal = onOpenTerminal,
                onOpenCanvas = onOpenCanvas,
            )
        },
        large = false,
        modifier = modifier,
    ) {
        ChatPane(
            transcript = transcript,
            error = error,
            loadingOlder = loadingOlder,
            olderError = olderError,
            connection = connection,
            onLoadOlder = onLoadOlder,
            onOpenLink = onOpenLink,
            modifier = Modifier,
            onShareLink = onShareLink,
            expanded = expanded,
            onToggle = onToggle,
            actions = actions,
            onOpenSubagent = onOpenSubagent,
            onOpenTerminal = onOpenTerminal,
            onRetry = onRetry,
            textScale = textScale,
            onTextScale = onTextScale,
            externalRichRequest = externalRichRequest,
            onExternalRichConsumed = onExternalRichConsumed,
            onReadResource = onReadResource,
            workingDirectories = workingDirectories,
            fromCache = fromCache,
        )
    }
}

/**
 * The conversation itself, with no bar of its own, for a screen that supplies one: the newest row at the bottom, the agent's
 * current activity above it, and the composer when the app may act. A link in the agent's text is only opened after the user
 * confirms it: [onOpenLink] is called with a link the user has already said yes to, and [onShareLink] with a web link the user chose to
 * share instead. Without an [onShareLink] the dialog has no Share button.
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
    onShareLink: ((String) -> Unit)? = null,
    expanded: Set<String> = emptySet(),
    onToggle: (String) -> Unit = {},
    actions: ChatActionsUi? = null,
    onOpenSubagent: ((String) -> Unit)? = null,
    onOpenTerminal: ((String) -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    /** The size of the text, as a multiple of the system's; [onTextScale], when given, is asked to change it as the person pinches the chat. */
    externalRichRequest: RichModalRequest? = null,
    onExternalRichConsumed: () -> Unit = {},
    textScale: Float = 1f,
    onTextScale: ((Float) -> Unit)? = null,
    onReadResource: (suspend (String) -> ResourceReadResult)? = null,
    workingDirectories: List<String> = emptyList(),
    /** What is shown is the copy kept from the last visit, not yet replaced by the host's snapshot (U1). */
    fromCache: Boolean = false,
) {
    // Kept as the text it came from: that survives the app being recreated, and a target is made from it again.
    var pendingRaw by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingLink = pendingRaw?.let(LinkTarget::of)
    var activeRichRequest by remember { mutableStateOf<RichModalRequest?>(null) }
    LaunchedEffect(externalRichRequest) {
        if (externalRichRequest != null) {
            activeRichRequest = externalRichRequest
            onExternalRichConsumed()
        }
    }
    val notLoaded = error as? ChatNotLoadedException
    // The text of the chat, and the box under it, follow the person's size; the bars around them keep the system's.
    val base = LocalDensity.current
    // While the fingers are down the size follows them here, and is only handed on (and kept) when they lift.
    var live by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(textScale) { live = null }
    val shown = live ?: textScale
    val scaled = remember(base, shown) { Density(base.density, base.fontScale * shown) }
    // The pinch sits outside the density it changes: a change of density would restart the gesture after its first step.
    Box(modifier = modifier.fillMaxSize().let { if (onTextScale != null) it.pinchToScale(shown, { s -> live = s }, { s -> live = s; onTextScale(s) }) else it }) {
        CompositionLocalProvider(LocalDensity provides scaled) {
            val openRich = activeRichRequest
            val openLink: (String) -> Unit = { link ->
                val hostTarget = HostFileTarget.fromInternalUri(link)
                if (hostTarget != null && onReadResource != null) {
                    activeRichRequest = RichModalRequest.HostFile(hostTarget)
                } else if (LinkTarget.of(link) != null) {
                    // The confirmation is shown over the chat, so a link from a full-screen view closes that view first.
                    activeRichRequest = null
                    pendingRaw = link
                }
            }
            // The chat stays composed under a full-screen view, so closing the view finds it exactly where it was (rows laid out
            // afresh grow a frame later and would move it) and the message box keeps its draft. While covered it is hidden from
            // screen readers and tests, and the view's surface takes every touch.
            Column(modifier = Modifier.fillMaxSize().then(if (openRich != null) Modifier.clearAndSetSemantics {} else Modifier)) {
                if (connection != ConnState.Online) ConnectionLine(connection, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                // Shown until the host's snapshot replaces the copy, and said plainly, so nobody takes an old reply for a new one.
                if (fromCache && transcript != null && error == null) {
                    Text(
                        "Showing this chat as you last saw it. Updating…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("chat_cached"),
                    )
                }
                if (transcript?.running == true && !transcript.waitingOnYou) WorkingBar()
                when {
                    error is ChatGoneException -> ErrorText("This chat is no longer on the host.", Modifier.padding(horizontal = 16.dp).testTag("chat_gone"))
                    notLoaded != null && transcript == null ->
                        NotLoaded(notLoaded, onRetry, stale = false, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    transcript != null -> {
                        if (notLoaded != null) NotLoaded(notLoaded, onRetry, stale = true, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        TranscriptList(
                            transcript = transcript,
                            loadingOlder = loadingOlder,
                            olderError = olderError,
                            onLoadOlder = onLoadOlder,
                            onOpenLink = openLink,
                            expanded = expanded,
                            onToggle = onToggle,
                            actions = actions,
                            onOpenSubagent = onOpenSubagent,
                            onOpenTerminal = onOpenTerminal,
                            allowHostFiles = onReadResource != null,
                            onOpenRich = { req -> activeRichRequest = req },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    error != null -> ErrorText("Could not load this chat (${error::class.simpleName}).", Modifier.padding(horizontal = 16.dp).testTag("chat_error"))
                    else -> LoadingChat(Modifier.padding(horizontal = 16.dp))
                }
            }
            if (openRich != null) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RichViewerModal(
                        request = openRich,
                        connection = connection,
                        onDismiss = { activeRichRequest = null },
                        onReadResource = onReadResource,
                        workingDirectories = workingDirectories,
                        onOpenLink = openLink,
                    )
                }
            } else {
                pendingLink?.let { target ->
                    LinkConfirmDialog(
                        target = target,
                        onOpen = { pendingRaw = null; onOpenLink(rawOf(target)) },
                        onShare = onShareLink?.let { share -> { pendingRaw = null; share(rawOf(target)) } },
                        onDismiss = { pendingRaw = null },
                    )
                }
            }
        }
    }
}

/** What to hand the phone to open: the address a target was made from. */
private fun rawOf(target: LinkTarget): String = when (target) {
    is LinkTarget.Web -> target.url
    is LinkTarget.Mail -> "mailto:${target.address}"
    is LinkTarget.Phone -> "tel:${target.number}"
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
        Busy("Loading chat\u2026", Modifier.testTag("chat_loading"))
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
    allowHostFiles: Boolean,
    onOpenRich: ((RichModalRequest) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var composerDraft by rememberSaveable { mutableStateOf<String?>(null) }
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
    // The person's latest message and the start of the reply to it, as list positions (the working row, when shown, comes first).
    val landmarks = remember(newestFirst) { TranscriptLandmarks.of(newestFirst) }
    val readableTurns = remember(transcript.items) { TranscriptLandmarks.readableTurns(transcript.items) }
    val rowOffset = if (transcript.running) 1 else 0
    val lastUserTopShown by remember(landmarks, rowOffset) { derivedStateOf { landmarks.lastUser?.let { listState.topShown(it + rowOffset) } ?: true } }
    // Shown only once there is a reply: a message still waiting for one is the newest row, and needs no reminder.
    val barShown = landmarks.lastUserText != null && landmarks.replyStart != null && !lastUserTopShown
    var barHeightPx by remember { mutableStateOf(0) }
    // The bar covers the top of the list, so a reply's first line counts as shown only below it, and a jump stops below it.
    val barInset by rememberUpdatedState(if (barShown) barHeightPx else 0)
    val replyTopShown by remember(landmarks, rowOffset) { derivedStateOf { landmarks.replyStart?.let { listState.topShown(it + rowOffset, barInset) } ?: true } }
    var composerHeightPx by remember { mutableStateOf(0) }
    val composerPadding = if (actions != null) with(LocalDensity.current) { composerHeightPx.toDp() } else 0.dp
    val saveableHolder = rememberSaveableStateHolder()
    Box(modifier = modifier.fillMaxSize()) {
        if (newestFirst.isEmpty() && !transcript.hasOlder) {
            Text("No messages yet.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(24.dp).testTag("chat_empty"))
        }
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize().testTag("transcript"),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp + composerPadding),
        ) {
            // The newest row, at the bottom: for as long as the turn runs, the sign that it does (or that it is held up on the person).
            if (transcript.running) {
                item(key = WORKING_KEY, contentType = WORKING_KEY) { WorkingRow(transcript.activity, transcript.waitingOnYou, Modifier.padding(top = 8.dp)) }
            }
            itemsIndexed(newestFirst, key = { _, row -> row.key }, contentType = { _, row -> row::class }) { index, row ->
                TranscriptRow(
                    row = row,
                    onOpenLink = onOpenLink,
                    modifier = Modifier.padding(top = rowGap(row, newestFirst.getOrNull(index + 1))),
                    expanded = expanded,
                    onToggle = onToggle,
                    actions = actions,
                    onOpenSubagent = onOpenSubagent,
                    onOpenTerminal = onOpenTerminal,
                    allowHostFiles = allowHostFiles,
                    onOpenRich = onOpenRich,
                    hasOlder = transcript.hasOlder,
                    onRead = onOpenRich?.let { open ->
                        { turnId: String -> TranscriptLandmarks.replyParts(transcript.items, turnId)?.let { open(RichModalRequest.Reply("Reply", it.answer, it.notes)) } }
                    },
                    readableTurns = readableTurns,
                    onLoadDraft = { draft ->
                        composerDraft = draft
                        scope.launch { listState.scrollToItem(0) }
                    },
                )
            }
            if (loadingOlder || olderError != null || transcript.hasOlder) {
                item(key = OLDER_KEY, contentType = OLDER_KEY) { OlderRow(loadingOlder, olderError, onLoadOlder) }
            }
        }
        if (away) {
            FilledTonalIconButton(
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .shadow(6.dp, CircleShape)
                    .size(48.dp)
                    .testTag("jump_latest"),
            ) { Icon(AppIcons.ArrowDown, contentDescription = "Jump to latest") }
        }
        // A reply can be longer than the screen: this goes to its first line, not its end, and works while it is still arriving.
        val replyStart = landmarks.replyStart
        if (replyStart != null && !replyTopShown) {
            FilledTonalIconButton(
                onClick = { scope.launch { listState.showTopOf(replyStart + rowOffset, barInset) } },
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 12.dp + if (away) 0.dp else composerPadding)
                    .shadow(6.dp, CircleShape)
                    .size(48.dp)
                    .testTag("jump_reply_start"),
            ) { Icon(AppIcons.ArrowUp, contentDescription = "Jump to the start of the reply") }
        }
        // The person's latest message, kept in view once it has scrolled away, so the reply below is never read without its question.
        val pinned = landmarks.lastUserText
        val lastUser = landmarks.lastUser
        if (barShown && pinned != null && lastUser != null) {
            Surface(
                onClick = { scope.launch { listState.showTopOf(lastUser + rowOffset) } },
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.medium,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .onSizeChanged { barHeightPx = it.height }
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Your last message: $pinned. Tap to go to it." }
                    .testTag("pinned_user_message"),
            ) {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = pinned,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (actions != null) {
            val quickReplyContext = remember(transcript, actions.quickReplyMode) {
                if (actions.quickReplyMode != QuickReplyMode.OFF) QuickReplies.extractContext(transcript) else null
            }
            AnimatedVisibility(
                visible = !away,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { if (it.height > 0) composerHeightPx = it.height },
            ) {
                saveableHolder.SaveableStateProvider("composer") {
                    Composer(
                        actions = actions,
                        pendingMessages = transcript.pendingMessages,
                        activeTurnId = transcript.activeTurnId ?: transcript.lastTurnId,
                        draftToLoad = composerDraft,
                        onDraftLoaded = { composerDraft = null },
                        quickReplyContext = quickReplyContext,
                    )
                }
            }
        }
    }
}

/**
 * The air above a row. What a person says starts a new exchange and gets the most; the first row after it sits a little apart; a
 * run of rows by the agent sits close together. The first row of all has none: the list's own padding is enough there.
 */
private fun rowGap(row: TranscriptRowModel, before: TranscriptRowModel?): Dp = when {
    before == null -> 0.dp
    row.isUser() -> 20.dp
    before.isUser() -> 10.dp
    else -> 8.dp
}

private fun TranscriptRowModel.isUser(): Boolean = this is TranscriptRowModel.Single && item is TranscriptItem.User

private const val OLDER_KEY = "older"

private const val WORKING_KEY = "working"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundWorkChip(
    items: List<BackgroundWorkView>,
    canvases: List<CanvasView> = emptyList(),
    onOpenSubagent: ((String) -> Unit)? = null,
    onOpenTerminal: ((String) -> Unit)? = null,
    onOpenCanvas: ((String) -> Unit)? = null,
) {
    if (items.isEmpty() && canvases.isEmpty()) return
    var showSheet by rememberSaveable { mutableStateOf(false) }
    val count = if (items.isNotEmpty()) items.size else canvases.size
    val spoken = when {
        items.size == 1 -> "1 background task. Show background work"
        items.size > 1 -> "${items.size} background tasks. Show background work"
        canvases.size == 1 -> "1 canvas. Show canvases"
        else -> "${canvases.size} canvases. Show canvases"
    }
    TextButton(
        onClick = { showSheet = true },
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics { contentDescription = spoken }
            .testTag("background_work_chip"),
    ) {
        Icon(AppIcons.AgentCode, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("$count", style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            dragHandle = { BottomSheetDefaults.DragHandle(width = 48.dp) },
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
            modifier = Modifier.testTag("background_work_sheet"),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (items.isNotEmpty()) "Background work" else "Canvases",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                if (items.isNotEmpty()) {
                    BackgroundWorkBar(
                        items = items,
                        onOpenSubagent = onOpenSubagent?.let { open -> { uri -> showSheet = false; open(uri) } },
                        onOpenTerminal = onOpenTerminal?.let { open -> { uri -> showSheet = false; open(uri) } },
                    )
                }
                if (canvases.isNotEmpty()) {
                    CanvasesBar(
                        canvases = canvases,
                        onOpenCanvas = onOpenCanvas?.let { open -> { uri -> showSheet = false; open(uri) } },
                    )
                }
            }
        }
    }
}

@Composable
fun BackgroundWorkBar(
    items: List<BackgroundWorkView>,
    onOpenSubagent: ((String) -> Unit)?,
    onOpenTerminal: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().testTag("background_work_bar"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().testTag("background_work_${item.id}"),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(item.kind.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(item.label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        item.command?.let { cmd ->
                            Text(
                                cmd,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    val terminalUri = item.terminalUri
                    val chatUri = item.chatUri
                    if (terminalUri != null && onOpenTerminal != null) {
                        TextButton(
                            onClick = { onOpenTerminal(terminalUri) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("background_work_open_${item.id}"),
                        ) { Text("Open terminal") }
                    } else if (chatUri != null && onOpenSubagent != null) {
                        TextButton(
                            onClick = { onOpenSubagent(chatUri) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("background_work_open_${item.id}"),
                        ) { Text("Open subagent") }
                    }
                }
            }
        }
    }
}

@Composable
fun CanvasesBar(canvases: List<CanvasView>, modifier: Modifier = Modifier, onOpenCanvas: ((String) -> Unit)? = null) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().testTag("canvases_bar"),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (canvases.size == 1) "Canvas" else "Canvases (${canvases.size})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onOpenCanvas == null) {
                Text(canvases.joinToString(" · ") { it.label }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("canvases_text"))
            } else {
                canvases.forEach { canvas ->
                    TextButton(
                        onClick = { onOpenCanvas(canvas.resource) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("canvas_open_${canvas.label}"),
                    ) { Text(canvas.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

private fun LazyListState.atNewest(): Boolean = firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0

/**
 * Whether the top edge of the item at [index] is on screen, below [topInset] pixels (something drawn over the top of the list).
 * The list is reversed, so offsets count up from the bottom.
 */
private fun LazyListState.topShown(index: Int, topInset: Int = 0): Boolean {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
    return item.offset + item.size <= info.viewportEndOffset - info.afterContentPadding - topInset
}

/**
 * Scrolls so the item at [index] starts at the top of the screen, just below [topInset]. The item is brought on screen first, then
 * moved by the distance from where its top edge actually landed: a scroll to an item puts its bottom at the bottom of a reversed
 * list, except near the oldest end, where the list cannot go that far and the item stops higher up. Rows that have just come on
 * screen can grow a frame later (their text finishes laying out), which pushes the item up again in a list held at its bottom, so
 * the distance is measured and closed again after each frame until the item stays put.
 */
private suspend fun LazyListState.showTopOf(index: Int, topInset: Int = 0) {
    scrollToItem(index)
    var settled = 0
    repeat(SHOW_TOP_PASSES) {
        // A frame first, so the rows that just came on screen have their real height before anything is measured.
        withFrameNanos { }
        val info = layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        if (item == null) {
            settled = 0
            scrollToItem(index)
        } else {
            val gap = item.offset + item.size - (info.viewportEndOffset - info.afterContentPadding - topInset)
            if (abs(gap) <= 1) {
                // Rows can keep growing for a frame or two more, so the place counts only once it has held for two frames.
                if (++settled >= 2) return
            } else {
                settled = 0
                scrollBy(gap.toFloat())
            }
        }
    }
}

/** At most this many frames for [showTopOf] to settle while rows that came on screen grow to their real height. */
private const val SHOW_TOP_PASSES = 10

/** The top of the transcript: progress while older turns load, the reason and a retry if they could not, else a way to ask. */
@Composable
private fun OlderRow(loading: Boolean, error: Throwable?, onLoadOlder: () -> Unit) {
    when {
        loading -> Busy("Loading earlier messages\u2026", Modifier.testTag("older_loading"))
        error != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ErrorText("Could not load earlier messages.", Modifier.testTag("older_error"))
            OutlinedButton(onClick = onLoadOlder, modifier = Modifier.testTag("older_retry")) { Text("Try again") }
        }
        else -> OutlinedButton(onClick = onLoadOlder, modifier = Modifier.testTag("older_button")) { Text("Load earlier messages") }
    }
}
