package dev.ahpclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.ActivityState
import dev.ahpclient.state.ChangeStats
import dev.ahpclient.state.ChangesetEntry
import dev.ahpclient.state.ChatRow
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.LinkTarget
import dev.ahpclient.state.PullRequest
import dev.ahpclient.state.SessionDetail
import dev.ahpclient.state.SessionGoneException
import dev.ahpclient.state.SessionPhase
import dev.ahpclient.state.label
import dev.ahpclient.ui.chat.LinkConfirmDialog
import dev.ahpclient.state.CustomizationCategory
import dev.ahpclient.state.HostFileTarget
import dev.ahpclient.state.SlashCommands
import dev.ahpclient.state.TurnUsage
import dev.ahpclient.state.BackgroundWorkView
import dev.ahpclient.state.CanvasView
import dev.ahpclient.ui.chat.BackgroundWorkBar
import dev.ahpclient.ui.chat.BackgroundWorkChip
import dev.ahpclient.state.ManageMessages
import kotlinx.coroutines.launch
import com.microsoft.agenthostprotocol.generated.RootState
import com.microsoft.agenthostprotocol.generated.SessionState
import com.microsoft.agenthostprotocol.generated.SessionSummary
import dev.ahpclient.platform.Instant

/**
 * One session: its chats as tabs under a top bar, and the chat that is selected filling the rest of the screen. What used to
 * be a page of buttons is behind the bar's menu (looking after the session) and its Details item (the facts, what waits on
 * the user, and the changes). [state] is null until the host's snapshot arrives; [error] says why it may never come.
 *
 * The chat itself is [chat], supplied by the caller, which owns what is open; [shownChat] says which one that is. This
 * screen asks for the session's main chat, through [onSelectChat], whenever none is shown or the one shown has left the
 * session, and for a tab the user taps or a chat they have just started.
 */
@Composable
fun SessionScreen(
    state: SessionState?,
    error: Throwable?,
    connection: ConnState,
    now: Instant,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the side menu; the bar starts with its button when given. */
    onMenu: (() -> Unit)? = null,
    /** The totals of what the session has changed, for the bar. */
    changes: ChangeStats? = null,
    shownChat: String? = null,
    onSelectChat: (String) -> Unit = {},
    manage: ManageActionsUi? = null,
    sessionUri: String = "",
    onRemoved: () -> Unit = {},
    onOpenChangeset: (uri: String, label: String) -> Unit = { _, _ -> },
    /** The choice of sessions to be alerted about when they finish; the menu has no such item without it. */
    alerts: FinishedAlertsUi? = null,
    summary: SessionSummary? = null,
    onOpenLink: (String) -> Unit = {},
    onShareLink: ((String) -> Unit)? = null,
    requestedChat: String? = null,
    rootState: RootState? = null,
    onUseSlashCommand: ((String) -> Unit)? = null,
    onUseAgent: ((String) -> Unit)? = null,
    onViewHostFile: ((HostFileTarget) -> Unit)? = null,
    openCustomizationsRequest: Pair<CustomizationCategory?, String>? = null,
    onCustomizationsRequestConsumed: () -> Unit = {},
    chatUsage: TurnUsage? = null,
    backgroundWork: List<BackgroundWorkView> = emptyList(),
    canvases: List<CanvasView> = emptyList(),
    onOpenSubagent: ((String) -> Unit)? = null,
    onOpenTerminal: ((String) -> Unit)? = null,
    chat: @Composable () -> Unit = {},
) {
    val detail = remember(state, now, summary) { state?.let { SessionDetail.from(it, now, summary) } }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val customizations = remember(state, rootState) { SlashCommands.customizationsOf(state, rootState) }
    var showCustomizations by rememberSaveable { mutableStateOf(false) }
    var customizationsCategory by rememberSaveable { mutableStateOf<CustomizationCategory?>(null) }
    var customizationsQuery by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(openCustomizationsRequest) {
        if (openCustomizationsRequest != null) {
            customizationsCategory = openCustomizationsRequest.first
            customizationsQuery = openCustomizationsRequest.second
            showCustomizations = true
            onCustomizationsRequestConsumed()
        }
    }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // A chat the user has just started is not in the host's list until the host echoes it, so the tab is brought forward then.
    var pendingChat by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(requestedChat) {
        if (requestedChat != null) pendingChat = requestedChat
    }

    var showArchivedChats by rememberSaveable { mutableStateOf(false) }
    val visibleTabs = remember(detail, showArchivedChats) { detail?.visibleTabs(showArchivedChats).orEmpty() }
    val tabUris = visibleTabs.map { it.uri }
    val canShowChat = detail != null && detail.phase != SessionPhase.FAILED
    LaunchedEffect(tabUris, shownChat, canShowChat) {
        if (canShowChat && (shownChat == null || shownChat !in tabUris)) detail.mainChat?.let { onSelectChat(it.uri) }
    }
    LaunchedEffect(tabUris, pendingChat) {
        val wanted = pendingChat ?: return@LaunchedEffect
        if (wanted in tabUris) {
            onSelectChat(wanted)
            pendingChat = null
        }
    }

    ScreenScaffold(
        title = detail?.title ?: "Session",
        subtitle = detail?.let { listOfNotNull(it.state.label, it.project).joinToString(" · ") },
        onBack = onBack,
        onMenu = onMenu,
        backLabel = "Back",
        titleTag = "detail_title",
        large = false,
        modifier = modifier,
        actions = {
            if (detail != null) {
                BackgroundWorkChip(
                    items = backgroundWork,
                    canvases = canvases,
                    onOpenSubagent = onOpenSubagent,
                    onOpenTerminal = onOpenTerminal,
                )
                ChangesChip(changes, detail.changesets, onOpenChangeset)
                SessionMenu(
                    detail = detail,
                    sessionUri = sessionUri,
                    manage = manage,
                    shownChat = detail.chats.firstOrNull { it.uri == shownChat },
                    onShowDetails = { showDetails = true },
                    onNotice = { notice = it },
                    onChatCreated = { pendingChat = it },
                    onRemoved = onRemoved,
                    alerts = alerts,
                    showArchivedChats = showArchivedChats,
                    onToggleArchivedChats = { showArchivedChats = !showArchivedChats },
                    customizationsCount = customizations.totalCount,
                    onShowCustomizations = {
                        customizationsCategory = null
                        customizationsQuery = ""
                        showCustomizations = true
                    },
                )
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (connection != ConnState.Online) ConnectionLine(connection, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            when {
                error is SessionGoneException -> ErrorText("This session is no longer on the host.", Modifier.padding(24.dp).testTag("session_gone"))
                detail != null -> {
                    SessionBanners(detail, notice)
                    ChatTabs(visibleTabs, shownChat, onSelectChat)
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            detail.phase == SessionPhase.FAILED -> Unit
                            shownChat != null && shownChat in tabUris -> chat()
                            detail.tabs.isEmpty() -> EmptyState(AppIcons.InfoDuo, "No chats in this session yet")
                            else -> Busy("Loading chat…", Modifier.padding(24.dp).testTag("chat_pending"))
                        }
                    }
                }
                error != null -> ErrorText("Could not load this session (${error::class.simpleName}).", Modifier.padding(24.dp).testTag("detail_error"))
                else -> Busy("Loading session…", Modifier.padding(24.dp).testTag("detail_loading"))
            }
        }
    }
    if (showDetails && detail != null) {
        DetailsSheet(
            detail = detail,
            customizationsCount = customizations.totalCount,
            onDismiss = { showDetails = false },
            onOpenCustomizations = {
                showDetails = false
                customizationsCategory = null
                customizationsQuery = ""
                showCustomizations = true
            },
            onOpenLink = onOpenLink,
            onShareLink = onShareLink,
            chatUsage = chatUsage,
            backgroundWork = backgroundWork,
            canvases = canvases,
            onOpenSubagent = onOpenSubagent,
            onOpenTerminal = onOpenTerminal,
        )
    }
    if (showCustomizations && detail != null) {
        CustomizationsSheet(
            customizations = customizations,
            initialCategory = customizationsCategory,
            initialQuery = customizationsQuery,
            onDismiss = { showCustomizations = false },
            onUseSlashCommand = onUseSlashCommand,
            onUseAgent = onUseAgent,
            onViewFile = onViewHostFile,
            onControlMcpServer = if (manage != null && sessionUri.isNotEmpty()) {
                { serverId, control ->
                    scope.launch {
                        notice = ManageMessages.describe(manage.controlMcpServer(sessionUri, serverId, control))
                    }
                }
            } else {
                null
            },
        )
    }
}

/**
 * What the session has changed, as the host totals it, "(+1072, −8)": a button to the files when there is one set of changes to open, a
 * menu of them when there are several, and plain text when the host lists none this app can show.
 */
@Composable
private fun ChangesChip(stats: ChangeStats?, changesets: List<ChangesetEntry>, onOpenChangeset: (uri: String, label: String) -> Unit) {
    val openable = changesets.filter { it.uri != null }
    val text = stats?.text ?: if (openable.isNotEmpty()) "Changes" else return
    val spoken = stats?.spoken ?: "Changes"
    var menu by remember { mutableStateOf(false) }
    when {
        openable.isEmpty() -> Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp).semantics { contentDescription = spoken }.testTag("changes_chip"),
        )
        else -> Box {
            TextButton(
                onClick = { if (openable.size == 1) onOpenChangeset(openable[0].uri!!, openable[0].label) else menu = true },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "$spoken. Show the files" }.testTag("changes_chip"),
            ) { Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                openable.forEach { entry ->
                    DropdownMenuItem(text = { Text(entry.label) }, onClick = { menu = false; onOpenChangeset(entry.uri!!, entry.label) }, modifier = Modifier.testTag("open_changeset"))
                }
            }
        }
    }
}

/** What the person needs to know before the chat: the session is archived, is still starting, failed to start, or an action went wrong. */
@Composable
private fun SessionBanners(detail: SessionDetail, notice: String?) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (detail.isArchived) Text("Archived", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp).testTag("detail_archived"))
        when (detail.phase) {
            // A new session's chat is held back by the host until the first message is sent, so this can stay for as long as it takes to write one.
            SessionPhase.CREATING -> Text(
                "Starting. The host may wait for your first message: send one below.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 8.dp).testTag("session_starting").semantics { liveRegion = LiveRegionMode.Polite },
            )
            SessionPhase.FAILED -> ErrorText(
                "Could not start this session." + (detail.creationError?.let { " $it" }.orEmpty()),
                Modifier.padding(vertical = 8.dp).testTag("session_failed"),
            )
            SessionPhase.READY, SessionPhase.OTHER -> Unit
        }
        ManageNotice(notice, "manage_notice")
    }
}

/**
 * One tab per chat, the main chat first. A single chat needs no tabs, so none are drawn. A chat that is waiting on the user
 * carries a dot, and its tab's spoken description says so, so colour is never the only cue.
 */
@Composable
private fun ChatTabs(chats: List<ChatRow>, shownChat: String?, onSelect: (String) -> Unit) {
    if (chats.size < 2) return
    val selected = chats.indexOfFirst { it.uri == shownChat }.coerceAtLeast(0)
    PrimaryScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp, modifier = Modifier.testTag("chat_tabs")) {
        chats.forEachIndexed { index, chat ->
            Tab(
                selected = index == selected,
                onClick = { onSelect(chat.uri) },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics {
                        contentDescription = buildList {
                            add(chat.title)
                            if (chat.state == ActivityState.NEEDS_INPUT) add(chat.state.label)
                            if (chat.isArchived) add("Archived")
                        }.joinToString(", ")
                    }
                    .testTag("chat_tab"),
                text = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (chat.state == ActivityState.NEEDS_INPUT) {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiary,
                                shape = CircleShape,
                                modifier = Modifier.size(8.dp).testTag("chat_tab_dot").clearAndSetSemantics {},
                            ) {}
                        }
                        Text(if (chat.isArchived) "${chat.title} (archived)" else chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
                    }
                },
            )
        }
    }
}

/**
 * The facts of the session in a sheet from the bottom: what it is doing, anything the agent is waiting on (in words; answering
 * is in the chat), where it runs, and the changes it advertises, each a way into the files it changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsSheet(
    detail: SessionDetail,
    customizationsCount: Int = 0,
    onDismiss: () -> Unit,
    onOpenCustomizations: (() -> Unit)? = null,
    chatUsage: TurnUsage? = null,
    backgroundWork: List<BackgroundWorkView> = emptyList(),
    canvases: List<CanvasView> = emptyList(),
    onOpenSubagent: ((String) -> Unit)? = null,
    onOpenTerminal: ((String) -> Unit)? = null,
    onOpenLink: (String) -> Unit = {},
    onShareLink: ((String) -> Unit)? = null,
) {
    var pendingPrUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingTarget = pendingPrUrl?.let(LinkTarget::of)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        // The handle is itself a control (it expands and collapses the sheet), so it is drawn wide enough to be a 48 dp target.
        dragHandle = { BottomSheetDefaults.DragHandle(width = 48.dp) },
        // The sheet reaches under the navigation bar; the content below keeps clear of it, so nothing sits behind the gesture bar.
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        modifier = Modifier.testTag("details_sheet"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Details", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusChip(detail.state)
                if (detail.isArchived) Text("Archived", style = MaterialTheme.typography.labelLarge)
            }
            detail.activity?.let { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("detail_activity")) }

            if (detail.inputNeeded.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag("input_needed").semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    Text("Waiting on you", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    detail.inputNeeded.forEach { request -> InputRequestCard(request) }
                    Text(SessionDetail.ANSWER_ELSEWHERE, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("answer_hint"))
                }
            }

            if (detail.pullRequests.isNotEmpty()) {
                PullRequestsSection(detail.pullRequests, onSelectUrl = { pendingPrUrl = it })
            }

            if (backgroundWork.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Background work", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    BackgroundWorkBar(
                        items = backgroundWork,
                        onOpenSubagent = onOpenSubagent?.let { open -> { uri -> onDismiss(); open(uri) } },
                        onOpenTerminal = onOpenTerminal?.let { open -> { uri -> onDismiss(); open(uri) } },
                    )
                }
            }

            Facts(detail, chatUsage, canvases)
            if (onOpenCustomizations != null) {
                OutlinedButton(
                    onClick = onOpenCustomizations,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("details_open_customizations"),
                ) {
                    Text(if (customizationsCount > 0) "Customizations ($customizationsCount)" else "Customizations")
                }
            }
        }
    }
    pendingTarget?.let { target ->
        LinkConfirmDialog(
            target = target,
            onOpen = {
                val url = pendingPrUrl
                pendingPrUrl = null
                if (url != null) onOpenLink(url)
            },
            onShare = onShareLink?.let { share ->
                {
                    val url = pendingPrUrl
                    pendingPrUrl = null
                    if (url != null) share(url)
                }
            },
            onDismiss = { pendingPrUrl = null },
        )
    }
}

@Composable
private fun PullRequestsSection(pullRequests: List<PullRequest>, onSelectUrl: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("pull_requests")) {
        Text("Pull requests", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        pullRequests.forEach { pr ->
            val openable = pr.openableUrl
            val text = listOfNotNull(pr.label, pr.branch).joinToString(" · ")
            if (openable != null) {
                OutlinedButton(
                    onClick = { onSelectUrl(openable) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("pr_chip"),
                ) {
                    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("pr_chip"),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(text, style = MaterialTheme.typography.labelLarge)
                        Text(pr.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun Facts(detail: SessionDetail, chatUsage: TurnUsage? = null, canvases: List<CanvasView> = emptyList()) {
    val facts = buildList {
        add("Agent" to detail.provider)
        detail.model?.let { add("Model" to it) }
        detail.project?.let { add("Project" to it) }
        detail.workingDirectories.forEach { add("Folder" to it) }
        detail.extras.branchLabel?.let { add("Branch" to it) }
        detail.extras.countsText?.let { add("Git status" to it) }
        chatUsage?.detailText?.let { add("Token usage" to it) }
        detail.annotations?.summaryText?.let { add("Annotations" to it) }
        if (canvases.isNotEmpty()) {
            add("Canvases" to canvases.joinToString(" · ") { it.label })
        }
    }
    Column {
        facts.forEachIndexed { index, (label, value) ->
            if (index > 0) Spacer(Modifier.height(Design.Gap))
            TileCard(shape = connectedShape(index, facts.size), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
