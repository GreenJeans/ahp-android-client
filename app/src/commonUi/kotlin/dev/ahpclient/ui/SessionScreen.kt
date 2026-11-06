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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.ChangeStats
import dev.ahpclient.state.ChangesetEntry
import dev.ahpclient.state.ChatSwitcher
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
import dev.ahpclient.state.DispatchOutcome
import dev.ahpclient.state.ManageMessages
import dev.ahpclient.state.ManageResult
import dev.ahpclient.state.RecentFolders
import dev.ahpclient.state.displayPath
import kotlinx.coroutines.launch
import com.microsoft.agenthostprotocol.generated.RootState
import com.microsoft.agenthostprotocol.generated.SessionState
import com.microsoft.agenthostprotocol.generated.SessionSummary
import com.microsoft.agenthostprotocol.generated.AnnotationsState
import dev.ahpclient.platform.Instant

/**
 * One session: the way to its chats under a top bar (the main chat and one control for the others, see [ChatSwitcherBar]),
 * and the chat that is selected filling the rest of the screen. What used to
 * be a page of buttons is behind the bar's menu (looking after the session) and its Details item (the facts, what waits on
 * the user, and the changes). [state] is null until the host's snapshot arrives; [error] says why it may never come.
 *
 * The chat itself is [chat], supplied by the caller, which owns what is open; [shownChat] says which one that is. This
 * screen asks for the session's main chat, through [onSelectChat], whenever none is shown or the one shown has left the
 * session, and for a chat the user picks or has just started.
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
    chatChangesets: List<ChangesetEntry> = emptyList(),
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
    onRequestedChatConsumed: () -> Unit = {},
    rootState: RootState? = null,
    onUseSlashCommand: ((String) -> Unit)? = null,
    onUseAgent: ((String) -> Unit)? = null,
    onViewHostFile: ((HostFileTarget) -> Unit)? = null,
    openCustomizationsRequest: Pair<CustomizationCategory?, String>? = null,
    onCustomizationsRequestConsumed: () -> Unit = {},
    chatUsage: TurnUsage? = null,
    backgroundWork: List<BackgroundWorkView> = emptyList(),
    canvases: List<CanvasView> = emptyList(),
    chatWorkingDirectories: List<String> = emptyList(),
    onOpenCanvas: ((String) -> Unit)? = null,
    annotationsOpen: Boolean = false,
    annotations: AnnotationsState? = null,
    annotationsError: Throwable? = null,
    canInteract: Boolean = false,
    onOpenAnnotations: (() -> Unit)? = null,
    onCloseAnnotations: (() -> Unit)? = null,
    onAddAnnotationEntry: suspend (annotationId: String, text: String) -> ManageResult = { _, _ -> ManageResult.NotAllowed },
    onRemoveAnnotationEntry: suspend (annotationId: String, entryId: String) -> ManageResult = { _, _ -> ManageResult.NotAllowed },
    onOpenSubagent: ((String) -> Unit)? = null,
    onOpenTerminal: ((String) -> Unit)? = null,
    /** Which turn of a chat a turn id is, counting from 1, when the app holds enough of that chat to tell; names a fork's turn in the switcher. */
    chatTurnNumber: (chatUri: String, turnId: String) -> Int? = { _, _ -> null },
    chat: @Composable () -> Unit = {},
) {
    val detail = remember(state, now, summary) { state?.let { SessionDetail.from(it, now, summary) } }
    val changesets = remember(detail, chatChangesets) { ChangesetEntry.merge(detail?.changesets.orEmpty(), chatChangesets) }
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
    // A chat the user has just started or chosen from the drawer is brought forward as soon as it is in the session's reachable chats.
    var pendingChat by remember { mutableStateOf(requestedChat) }
    LaunchedEffect(requestedChat) {
        if (requestedChat != null) pendingChat = requestedChat
    }

    var showArchivedChats by rememberSaveable { mutableStateOf(false) }
    var showHiddenChats by rememberSaveable { mutableStateOf(false) }
    // The other chats are reached through one control and a sheet (U5), not a tab each: most of them do not need the person.
    val switcher = remember(detail, shownChat, showArchivedChats, showHiddenChats, chatTurnNumber) {
        detail?.let { ChatSwitcher.of(it.chats, shownChat, showArchived = showArchivedChats, showHidden = showHiddenChats, turnNumber = chatTurnNumber) }
            ?: ChatSwitcher.EMPTY
    }
    // A chat can be on screen only while the switcher lists it: an archived or hidden one goes back to the main chat when left out.
    val tabUris = switcher.reachable
    var showSwitcher by rememberSaveable { mutableStateOf(false) }
    val canShowChat = detail != null && detail.phase != SessionPhase.FAILED
    LaunchedEffect(tabUris, shownChat, canShowChat, pendingChat) {
        val wanted = pendingChat
        if (wanted != null && wanted in tabUris) {
            onSelectChat(wanted)
            pendingChat = null
            onRequestedChatConsumed()
        } else if (canShowChat && (shownChat == null || shownChat !in tabUris)) {
            detail.mainChat?.let { onSelectChat(it.uri) }
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
                    onOpenCanvas = onOpenCanvas,
                )
                ChangesChip(changes, changesets, onOpenChangeset)
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
                    ChatSwitcherBar(switcher, onSelectChat, onOpen = { showSwitcher = true })
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
    if (showSwitcher && switcher.hasOthers) {
        ChatSwitcherSheet(
            switcher = switcher,
            onSelect = { uri ->
                showSwitcher = false
                onSelectChat(uri)
            },
            onDismiss = { showSwitcher = false },
            showHidden = showHiddenChats,
            onToggleHidden = { showHiddenChats = !showHiddenChats },
        )
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
            chatWorkingDirectories = chatWorkingDirectories,
            manage = manage,
            sessionUri = sessionUri,
            onOpenCanvas = onOpenCanvas?.let { open -> { uri -> showDetails = false; open(uri) } },
            onOpenAnnotations = onOpenAnnotations?.let { open -> { showDetails = false; open() } },
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
            notice = notice,
            onToggleCustomization = if (manage != null && sessionUri.isNotEmpty()) {
                { id, enabled ->
                    scope.launch {
                        notice = ManageMessages.describe(manage.toggleCustomization(sessionUri, id, enabled))
                    }
                }
            } else {
                null
            },
        )
    }
    if (annotationsOpen) {
        AnnotationsSheet(
            state = annotations,
            error = annotationsError,
            canInteract = canInteract,
            onDismiss = { onCloseAnnotations?.invoke() },
            onAddEntry = onAddAnnotationEntry,
            onRemoveEntry = onRemoveAnnotationEntry,
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
    chatWorkingDirectories: List<String> = emptyList(),
    manage: ManageActionsUi? = null,
    sessionUri: String? = null,
    onOpenCanvas: ((String) -> Unit)? = null,
    onOpenAnnotations: (() -> Unit)? = null,
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

            Facts(detail, chatUsage, canvases, onOpenCanvas, onOpenAnnotations, chatWorkingDirectories, manage, sessionUri)
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
private fun Facts(
    detail: SessionDetail,
    chatUsage: TurnUsage? = null,
    canvases: List<CanvasView> = emptyList(),
    onOpenCanvas: ((String) -> Unit)? = null,
    onOpenAnnotations: (() -> Unit)? = null,
    chatWorkingDirectories: List<String> = emptyList(),
    manage: ManageActionsUi? = null,
    sessionUri: String? = null,
) {
    var chooseFolder by remember { mutableStateOf(false) }
    var folderNotice by remember { mutableStateOf<String?>(null) }
    // A refusal seen here, in case the host's refusal is not yet (or never) reported back through [ManageActionsUi.addFolderRefusal].
    var refusedHere by remember { mutableStateOf<String?>(null) }
    val addRefusal = manage?.addFolderRefusal ?: refusedHere
    // The same for removing, kept apart: a refused add leaves "Remove" offered until the host refuses a remove itself.
    var removeRefusedHere by remember { mutableStateOf<String?>(null) }
    val removeRefusal = manage?.removeFolderRefusal ?: removeRefusedHere
    val folderScope = rememberCoroutineScope()
    val addableFolders = manage?.folders.orEmpty().filterNot { offered -> detail.workingDirectories.any { it == displayPath(offered) } }
    val facts = buildList {
        add("Agent" to detail.provider)
        detail.model?.let { add("Model" to it) }
        detail.project?.let { add("Project" to it) }
        detail.extras.branchLabel?.let { add("Branch" to it) }
        detail.extras.countsText?.let { add("Git status" to it) }
        chatUsage?.detailText?.let { add("Token usage" to it) }
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
        if (chatWorkingDirectories.isNotEmpty() && chatWorkingDirectories != detail.workingDirectories) {
            Text("Chat folders", style = MaterialTheme.typography.labelLarge)
            chatWorkingDirectories.distinct().forEachIndexed { index, folder ->
                Text(folder.take(500), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("chat_working_directory_$index"))
            }
        }
        if (detail.workingDirectories.isNotEmpty()) Text("Session folders", style = MaterialTheme.typography.labelLarge)
        detail.workingDirectories.forEachIndexed { index, folder ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(folder.take(500), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).testTag("session_working_directory_$index"))
                if (manage != null && sessionUri != null && index > 0 && removeRefusal == null) TextButton(
                    onClick = {
                        folderScope.launch {
                            val result = manage.removeWorkingDirectory(sessionUri, folder)
                            val refused = ((result as? ManageResult.Unconfirmed)?.outcome as? DispatchOutcome.Rejected)?.reason
                            // The host's refusal replaces the buttons rather than showing twice.
                            if (refused != null) removeRefusedHere = refused
                            folderNotice = if (refused != null) null else ManageMessages.describe(result)
                        }
                    },
                    modifier = Modifier.testTag("folder_remove_$index"),
                ) { Text("Remove") }
            }
        }
        // Once the host refuses a remove, its reason stands once under the folders in place of their "Remove" buttons, for as long as this connection lasts.
        if (manage != null && sessionUri != null && removeRefusal != null && detail.workingDirectories.size > 1) {
            Text(ManageMessages.removeFolderRefused(removeRefusal), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("folder_remove_refused"))
        }
        if (manage != null && sessionUri != null) {
            // Offered until the host refuses it once; after that its reason stands where the button was, for as long as this connection lasts.
            if (addableFolders.isNotEmpty()) {
                if (addRefusal != null) {
                    Text(ManageMessages.addFolderRefused(addRefusal), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("folder_add_refused"))
                } else {
                    OutlinedButton(
                        onClick = { chooseFolder = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("folder_add"),
                    ) { Text("Add folder") }
                }
            }
            folderNotice?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("folder_notice")) }
            if (chooseFolder && addRefusal == null) AlertDialog(
                onDismissRequest = { chooseFolder = false },
                title = { Text("Add a recent folder") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        addableFolders.take(RecentFolders.SHOWN).forEachIndexed { index, folder ->
                            TextButton(
                                onClick = {
                                    chooseFolder = false
                                    folderScope.launch {
                                        val result = manage.addWorkingDirectory(sessionUri, folder)
                                        val refused = ((result as? ManageResult.Unconfirmed)?.outcome as? DispatchOutcome.Rejected)?.reason
                                        // The host's refusal replaces the button rather than showing twice.
                                        if (refused != null) refusedHere = refused
                                        folderNotice = if (refused != null) null else ManageMessages.describe(result)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().testTag("folder_choice_$index"),
                            ) { Text(displayPath(folder).take(500), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { chooseFolder = false }) { Text("Close") } },
            )
        }
        detail.annotations?.let { annotations ->
            if (onOpenAnnotations == null) {
                Text("Annotations · ${annotations.summaryText}", modifier = Modifier.testTag("details_annotations"))
            } else {
                OutlinedButton(
                    onClick = onOpenAnnotations,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("details_open_annotations"),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("Annotations")
                        Text(annotations.summaryText, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        canvases.forEach { canvas ->
            if (onOpenCanvas == null) {
                Text("Canvas · ${canvas.label}")
            } else {
                OutlinedButton(
                    onClick = { onOpenCanvas(canvas.resource) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("canvas_open_${canvas.label}"),
                ) { Text("Canvas · ${canvas.label}") }
            }
        }
    }
}
