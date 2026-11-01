package dev.ahpclient.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.AgentPick
import dev.ahpclient.state.InteractMessages
import dev.ahpclient.state.ManageMessages
import dev.ahpclient.state.ManageResult
import dev.ahpclient.state.PendingApproval
import dev.ahpclient.state.SessionDetail
import dev.ahpclient.state.ToolDetail
import dev.ahpclient.state.TurnDuration
import dev.ahpclient.state.TurnOutcome
import dev.ahpclient.state.ToolState
import dev.ahpclient.state.TranscriptItem
import dev.ahpclient.state.TranscriptRowModel
import dev.ahpclient.ui.AppIcons
import dev.ahpclient.ui.InputRequestCard
import kotlinx.coroutines.launch

/**
 * One row of the transcript list: what the person said as a bubble on the right, what the agent wrote as formatted text, and the
 * agent's work in between as one folded card per run of steps, which opens to the steps. [expanded] holds the keys of the rows
 * that are open and [onToggle] flips one. A run with a step waiting on the person is never folded over it.
 */
@Composable
internal fun TranscriptRow(
    row: TranscriptRowModel,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    expanded: Set<String> = emptySet(),
    onToggle: (String) -> Unit = {},
    actions: ChatActionsUi? = null,
    onOpenSubagent: ((String) -> Unit)? = null,
    onOpenTerminal: ((String) -> Unit)? = null,
    allowHostFiles: Boolean = false,
    onOpenRich: ((RichModalRequest) -> Unit)? = null,
    hasOlder: Boolean = false,
    onLoadDraft: (String) -> Unit = {},
) {
    when (row) {
        is TranscriptRowModel.Single -> ItemRow(
            row.item, onOpenLink, modifier, row.item.key in expanded, { onToggle(row.item.key) },
            actions, onOpenSubagent, onOpenTerminal, allowHostFiles, onOpenRich, hasOlder, onLoadDraft,
        )
        is TranscriptRowModel.Steps -> StepsRow(
            row, onOpenLink, modifier, expanded, onToggle,
            actions, onOpenSubagent, onOpenTerminal, allowHostFiles, onOpenRich,
        )
    }
}

@Composable
private fun ItemRow(
    item: TranscriptItem,
    onOpenLink: (String) -> Unit,
    modifier: Modifier,
    expanded: Boolean,
    onToggle: () -> Unit,
    actions: ChatActionsUi?,
    onOpenSubagent: ((String) -> Unit)?,
    onOpenTerminal: ((String) -> Unit)?,
    allowHostFiles: Boolean,
    onOpenRich: ((RichModalRequest) -> Unit)?,
    hasOlder: Boolean = false,
    onLoadDraft: (String) -> Unit = {},
) {
    when (item) {
        is TranscriptItem.User -> UserRow(item, actions, hasOlder, onLoadDraft, modifier)
        is TranscriptItem.Markdown -> AgentBubble(item, onOpenLink, modifier, allowHostFiles, onOpenRich)
        is TranscriptItem.Reasoning -> ReasoningBlock(item, expanded, onToggle, onOpenLink, modifier, allowHostFiles, onOpenRich)
        is TranscriptItem.Tool -> ToolCallBlock(item, expanded, onToggle, actions, onOpenSubagent, onOpenTerminal, onOpenRich, modifier)
        is TranscriptItem.Notice -> NoticeRow(item, modifier)
        is TranscriptItem.TurnEnd -> TurnEndRow(item, actions, modifier)
        is TranscriptItem.Question -> {
            val form = item.form
            if (actions != null && form != null && !item.answered) {
                QuestionForm(form, actions, modifier)
            } else {
                Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InputRequestCard(item.request)
                    Text(
                        if (item.answered) "Answered." else SessionDetail.ANSWER_ELSEWHERE,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("row_question_note"),
                    )
                }
            }
        }
        is TranscriptItem.Other -> Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                item.label,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("row_other"),
            )
            item.resourceRef?.let { ref ->
                InlineResourcePreview(label = item.label, ref = ref, onOpenRich = onOpenRich)
            }
        }
    }
}

/** Confirmation dialog before truncating turns via Restore Checkpoint, Start Over, Edit & Re-send, or non-resumable Retry. */
@Composable
private fun ConfirmTruncateDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, modifier = Modifier.testTag("confirm_truncate_body")) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.heightIn(min = 48.dp).testTag("confirm_truncate_accept"),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp).testTag("confirm_truncate_cancel"),
            ) { Text("Cancel") }
        },
        modifier = Modifier.testTag("confirm_truncate_dialog"),
    )
}

private val LEADING_SLASH_COMMAND = Regex("""^/[A-Za-z0-9_.\-]+(?=\s|$)""")

private fun turnsCountPhrase(count: Int): String = if (count == 1) "1 turn" else "$count turns"

/**
 * The checkpoint bar above a user turn, matching the VS Code Agents Panel:
 * - Before the first turn of the conversation: "Start Over" (clears the chat and undoes all changes).
 * - Between subsequent turns: "Restore Checkpoint" (restores workspace and chat to this point) and a fork icon button
 *   to branch the conversation from the preceding turn.
 */
@Composable
private fun CheckpointDivider(
    item: TranscriptItem.User,
    hasOlder: Boolean,
    actions: ChatActionsUi,
    busy: Boolean,
    onRequestStartOver: () -> Unit,
    onRequestRestore: () -> Unit,
    onRequestFork: (String) -> Unit,
) {
    val isFirstTurn = item.turnIndex == 0 && !hasOlder
    val previousTurnId = item.previousTurnId
    if (!isFirstTurn && previousTurnId == null) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).testTag("checkpoint_row_${item.turnId}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        if (isFirstTurn) {
            TextButton(
                onClick = onRequestStartOver,
                enabled = !busy,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Start Over. Clears the chat and undoes all changes" }
                    .testTag("checkpoint_start_over_${item.turnId}"),
            ) {
                Text("Start Over", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            TextButton(
                onClick = onRequestRestore,
                enabled = !busy,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Restore Checkpoint. Restores workspace and chat to this point" }
                    .testTag("checkpoint_restore_${item.turnId}"),
            ) {
                Text("Restore Checkpoint", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (actions.canFork && previousTurnId != null) {
                Text("\u00b7", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(
                    onClick = { onRequestFork(previousTurnId) },
                    enabled = !busy,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("checkpoint_fork_${item.turnId}"),
                ) {
                    Icon(
                        AppIcons.Fork,
                        contentDescription = "Fork conversation from this point",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private enum class PendingUserConfirm { START_OVER, RESTORE, EDIT_RESEND }

/**
 * What the person said: a rounded bubble on the right, as in the chat apps. Who said it is told to a screen reader, not shown.
 * When interactive, a checkpoint divider sits above the message and tapping the bubble opens an inline editor with Send,
 * Fork & Send, and Cancel.
 */
@Composable
private fun UserRow(
    item: TranscriptItem.User,
    actions: ChatActionsUi?,
    hasOlder: Boolean,
    onLoadDraft: (String) -> Unit,
    modifier: Modifier,
) {
    val interactive = actions != null && item.turnId.isNotBlank()
    var editing by rememberSaveable(item.key) { mutableStateOf(false) }
    var draft by rememberSaveable(item.key) { mutableStateOf(item.text) }
    var confirmAction by remember { mutableStateOf<PendingUserConfirm?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxWidth()) {
        if (interactive && actions != null) {
            CheckpointDivider(
                item = item,
                hasOlder = hasOlder,
                actions = actions,
                busy = busy,
                onRequestStartOver = { confirmAction = PendingUserConfirm.START_OVER },
                onRequestRestore = { confirmAction = PendingUserConfirm.RESTORE },
                onRequestFork = { prevTurnId ->
                    scope.launch {
                        busy = true
                        notice = ManageMessages.describe(actions.forkFromTurn(prevTurnId))
                        busy = false
                    }
                },
            )
        }
        if (editing && interactive && actions != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().testTag("user_edit_card_${item.turnId}"),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("Edit message") },
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth().testTag("user_edit_input_${item.turnId}"),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { editing = false; draft = item.text },
                            enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("user_edit_cancel_${item.turnId}"),
                        ) { Text("Cancel") }
                        if (actions.canFork && (item.previousTurnId != null || !hasOlder)) {
                            OutlinedButton(
                                onClick = {
                                    val editedText = draft.trim()
                                    scope.launch {
                                        busy = true
                                        val res = actions.editAsFork(item.previousTurnId, editedText, actions.modelInUse, AgentPick.Keep)
                                        notice = ManageMessages.describe(res)
                                        if (res is ManageResult.Done) editing = false
                                        busy = false
                                    }
                                },
                                enabled = !busy && draft.isNotBlank(),
                                modifier = Modifier.heightIn(min = 48.dp).testTag("user_edit_fork_${item.turnId}"),
                            ) {
                                Icon(AppIcons.Fork, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Fork & Send")
                            }
                        }
                        Button(
                            onClick = { confirmAction = PendingUserConfirm.EDIT_RESEND },
                            enabled = !busy && draft.isNotBlank(),
                            modifier = Modifier.heightIn(min = 48.dp).testTag("user_edit_send_${item.turnId}"),
                        ) { Text("Send") }
                    }
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Spacer(Modifier.weight(0.1f))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 4.dp, bottomStart = 20.dp),
                    modifier = Modifier
                        .weight(0.9f, fill = false)
                        .then(
                            if (interactive) {
                                Modifier.clickable(onClickLabel = "Click to Edit", role = Role.Button) {
                                    draft = item.text
                                    editing = true
                                }
                            } else {
                                Modifier
                            },
                        )
                        .semantics(mergeDescendants = true) { stateDescription = "You" }
                        .testTag("row_user"),
                ) {
                    SelectionContainer {
                        val styledUserText = remember(item.text) {
                            buildAnnotatedString {
                                val m = LEADING_SLASH_COMMAND.find(item.text)
                                if (m != null) {
                                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)) {
                                        append(item.text.substring(0, m.range.last + 1))
                                    }
                                    append(item.text.substring(m.range.last + 1))
                                } else {
                                    append(item.text)
                                }
                            }
                        }
                        Text(styledUserText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                }
            }
        }
        if (notice != null) {
            Text(
                notice!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.End).padding(top = 4.dp).testTag("user_row_notice_${item.turnId}"),
            )
        }
    }

    val pending = confirmAction
    if (pending != null && actions != null) {
        val countPhrase = turnsCountPhrase(item.turnsFromHereCount)
        when (pending) {
            PendingUserConfirm.START_OVER -> ConfirmTruncateDialog(
                title = "Start Over?",
                body = "Clears the chat and undoes all changes ($countPhrase will be removed).",
                confirmLabel = "Start Over",
                onConfirm = {
                    confirmAction = null
                    scope.launch {
                        busy = true
                        val res = actions.startOver()
                        notice = InteractMessages.describe(res)
                        if (InteractMessages.succeeded(res)) onLoadDraft(item.text)
                        busy = false
                    }
                },
                onDismiss = { confirmAction = null },
            )
            PendingUserConfirm.RESTORE -> ConfirmTruncateDialog(
                title = "Restore Checkpoint?",
                body = "Restores workspace and chat to this point ($countPhrase will be removed).",
                confirmLabel = "Restore",
                onConfirm = {
                    confirmAction = null
                    scope.launch {
                        busy = true
                        val res = actions.restoreCheckpoint(item.turnId)
                        notice = InteractMessages.describe(res)
                        if (InteractMessages.succeeded(res)) onLoadDraft(item.text)
                        busy = false
                    }
                },
                onDismiss = { confirmAction = null },
            )
            PendingUserConfirm.EDIT_RESEND -> ConfirmTruncateDialog(
                title = "Edit and Re-send?",
                body = "Replaces $countPhrase from this point and sends the edited message.",
                confirmLabel = "Send",
                onConfirm = {
                    confirmAction = null
                    val editedText = draft.trim()
                    scope.launch {
                        busy = true
                        val res = actions.editAndResend(item.turnId, editedText, actions.modelInUse, AgentPick.Keep)
                        notice = InteractMessages.describe(res)
                        if (InteractMessages.succeeded(res)) editing = false
                        busy = false
                    }
                },
                onDismiss = { confirmAction = null },
            )
        }
    }
}

/**
 * What the agent wrote, in a bubble of its own, so one message is told from the next and a long turn is plain to see.
 */
@Composable
private fun AgentBubble(
    item: TranscriptItem.Markdown,
    onOpenLink: (String) -> Unit,
    modifier: Modifier,
    allowHostFiles: Boolean = false,
    onOpenRich: ((RichModalRequest) -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp),
        modifier = modifier.fillMaxWidth().testTag("row_markdown"),
    ) {
        SelectionContainer {
            MarkdownPart(
                text = item.text,
                onOpenLink = onOpenLink,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                allowHostFiles = allowHostFiles,
                onOpenRich = onOpenRich,
            )
        }
    }
}

/** The line that ends a turn: that the agent has stopped, how it ended, how long it worked, and actions to retry, fork, or open a side chat. */
@Composable
private fun TurnEndRow(item: TranscriptItem.TurnEnd, actions: ChatActionsUi?, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val (glyph, word) = when (item.outcome) {
        TurnOutcome.DONE -> Glyph.DONE to "Done"
        TurnOutcome.STOPPED -> Glyph.QUIET to "Stopped"
        TurnOutcome.FAILED -> Glyph.FAILED to "Failed"
    }
    val text = listOfNotNull(word, TurnDuration.format(item.durationMs)).joinToString(" \u00b7 ")
    var confirmRetry by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("row_turn_end"),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphIcon(glyph, Modifier.size(14.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = if (item.outcome == TurnOutcome.FAILED) colors.error else colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp).testTag("turn_end_text"),
            )
        }
        if (actions != null && item.turnId.isNotBlank()) {
            val retryLabel = when {
                item.resumable -> "Resume"
                item.outcome == TurnOutcome.DONE -> "Regenerate"
                else -> "Retry"
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        if (item.resumable) {
                            scope.launch {
                                busy = true
                                notice = InteractMessages.describe(actions.retryTurn(item.turnId, actions.modelInUse, AgentPick.Keep))
                                busy = false
                            }
                        } else {
                            confirmRetry = true
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("turn_retry_${item.turnId}"),
                ) {
                    Icon(AppIcons.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(retryLabel, style = MaterialTheme.typography.labelMedium)
                }
                if (actions.canFork) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                notice = ManageMessages.describe(actions.forkFromTurn(item.turnId))
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Fork conversation from this point" }
                            .testTag("turn_fork_${item.turnId}"),
                    ) {
                        Icon(AppIcons.Fork, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Fork", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (actions.canSideChat) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                notice = ManageMessages.describe(actions.openSideChat(item.turnId, null, actions.modelInUse, AgentPick.Keep))
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Open side chat from this turn" }
                            .testTag("turn_side_chat_${item.turnId}"),
                    ) {
                        Icon(AppIcons.SideChat, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Side chat", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        if (notice != null) {
            Text(
                notice!!,
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
                modifier = Modifier.testTag("turn_end_notice_${item.turnId}"),
            )
        }
    }

    if (confirmRetry && actions != null) {
        val actionWord = if (item.outcome == TurnOutcome.DONE) "Regenerate" else "Retry"
        ConfirmTruncateDialog(
            title = "$actionWord Turn?",
            body = "Truncates to before this turn and runs it again (1 turn will be replaced).",
            confirmLabel = actionWord,
            onConfirm = {
                confirmRetry = false
                scope.launch {
                    busy = true
                    notice = InteractMessages.describe(actions.retryTurn(item.turnId, actions.modelInUse, AgentPick.Keep))
                    busy = false
                }
            },
            onDismiss = { confirmRetry = false },
        )
    }
}

/** A line the chat says about itself ("Context was compacted"), quiet and centred between two rules. An error is said in the error colour. */
@Composable
private fun NoticeRow(item: TranscriptItem.Notice, modifier: Modifier) {
    val color = if (item.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        SelectionContainer {
            Text(
                item.text,
                style = MaterialTheme.typography.labelLarge,
                color = color,
                modifier = Modifier.padding(horizontal = 12.dp).testTag("row_notice"),
            )
        }
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * A run of the agent's steps as one card. Folded it is one line: what it is doing now, or how many tools it used and which.
 * Open it lists the steps, each of which opens in turn. While a step waits on the person the card stays open and cannot be folded.
 */
@Composable
private fun StepsRow(
    model: TranscriptRowModel.Steps,
    onOpenLink: (String) -> Unit,
    modifier: Modifier,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    actions: ChatActionsUi?,
    onOpenSubagent: ((String) -> Unit)?,
    onOpenTerminal: ((String) -> Unit)?,
    allowHostFiles: Boolean,
    onOpenRich: ((RichModalRequest) -> Unit)?,
) {
    val open = model.needsYou || model.key in expanded
    val colors = MaterialTheme.colorScheme
    val glyph = when {
        model.needsYou -> Glyph.NEEDS_YOU
        model.running -> Glyph.WORKING
        model.failedCount > 0 -> Glyph.FAILED
        else -> Glyph.DONE
    }
    Surface(color = colors.surfaceContainerLow, shape = RoundedCornerShape(18.dp), modifier = modifier.fillMaxWidth().testTag("row_steps")) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .then(
                        if (model.needsYou) {
                            Modifier
                        } else {
                            Modifier.clickable(onClickLabel = if (open) "Hide the steps" else "Show the steps", role = Role.Button) { onToggle(model.key) }
                        },
                    )
                    .semantics(mergeDescendants = true) { stateDescription = if (open) "Expanded" else "Collapsed" }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag("steps_header"),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphIcon(glyph)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        model.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (model.needsYou) colors.tertiary else colors.onSurface,
                        modifier = Modifier.testTag("steps_title"),
                    )
                    model.detail?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("steps_detail"),
                        )
                    }
                }
                if (!model.needsYou) Chevron(open)
            }
            AnimatedVisibility(visible = open) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp).testTag("steps_body"),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    model.items.forEach { item ->
                        ItemRow(
                            item, onOpenLink, Modifier, item.key in expanded, { onToggle(item.key) },
                            actions, onOpenSubagent, onOpenTerminal, allowHostFiles, onOpenRich,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReasoningBlock(
    item: TranscriptItem.Reasoning,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier,
    allowHostFiles: Boolean = false,
    onOpenRich: ((RichModalRequest) -> Unit)? = null,
) {
    val label = if (item.live) "Thinking\u2026" else "Thought"
    val style = MaterialTheme.typography.bodyMedium
    // Nothing to open when the agent left no text: a plain line, not a button that opens onto nothing.
    if (item.text.isBlank()) {
        Text(label, style = style, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(horizontal = 6.dp, vertical = 8.dp).testTag("row_reasoning"))
        return
    }
    CollapsibleBlock(
        expanded = expanded,
        onToggle = onToggle,
        modifier = modifier.testTag("row_reasoning"),
        header = {
            Text(
                label,
                style = style,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
        },
    ) {
        // The thinking is set off from what the agent said by a thin rule down its side.
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Spacer(Modifier.fillMaxHeight().width(2.dp).background(MaterialTheme.colorScheme.outlineVariant))
            SelectionContainer {
                MarkdownPart(
                    text = item.text,
                    onOpenLink = onOpenLink,
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                    allowHostFiles = allowHostFiles,
                    onOpenRich = onOpenRich,
                )
            }
        }
    }
}

/** A tool call opens to its input and its results. One made without the host's record has nothing to open and stays a plain row. */
@Composable
private fun ToolCallBlock(
    item: TranscriptItem.Tool,
    expanded: Boolean,
    onToggle: () -> Unit,
    actions: ChatActionsUi?,
    onOpenSubagent: ((String) -> Unit)?,
    onOpenTerminal: ((String) -> Unit)?,
    onOpenRich: ((RichModalRequest) -> Unit)?,
    modifier: Modifier,
) {
    val source = item.source
    if (source == null) {
        Row(
            modifier = modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 6.dp).semantics(mergeDescendants = true) {}.testTag("row_tool"),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { ToolHeader(item, Modifier.weight(1f)) }
        return
    }
    // Buttons only for a call that is waiting, and only where the person may act; otherwise the block's own text says where to answer.
    val waiting = if (actions != null) PendingApproval.waitingFor(source) else null
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CollapsibleBlock(
            expanded = expanded,
            onToggle = onToggle,
            modifier = Modifier.testTag("row_tool"),
            header = { ToolHeader(item, Modifier.weight(1f).padding(start = 6.dp), approveHere = waiting != null) },
        ) {
            // Built here, so only a block the user opens pays for parsing its input and clipping its results.
            var showAll by remember(source) { mutableStateOf(false) }
            val detail = remember(source, showAll) { ToolDetail.from(source, if (showAll) ToolDetail.Limits.ALL else ToolDetail.Limits.DEFAULT) }
            ToolDetailBody(
                detail,
                approveHere = waiting != null,
                onShowAll = if (!showAll && detail.hasMore) ({ showAll = true }) else null,
                onOpenSubagent = onOpenSubagent,
                onOpenTerminal = onOpenTerminal,
                onOpenRich = onOpenRich,
            )
        }
        if (actions != null && waiting != null) ApprovalButtons(waiting.choices, waiting.toolCallId, waiting.forResult, actions)
    }
}

/** One step on one line: a glyph for its state, its name in bold and what it did after it, and the state in a word. */
@Composable
private fun RowScope.ToolHeader(item: TranscriptItem.Tool, modifier: Modifier, approveHere: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val glyph = item.state.glyph()
    // A call waiting on the person is read in full: that is what they are being asked to allow.
    val lines = if (item.state == ToolState.WAITING_FOR_APPROVAL) 5 else 2
    GlyphIcon(glyph)
    // A call waiting on the person has a long label of its own, so it sits under the call instead of squeezing it.
    val waiting = item.state == ToolState.WAITING_FOR_APPROVAL
    Column(modifier = modifier) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(item.name) }
                item.summary?.takeIf { it.isNotBlank() }?.let {
                    append("  ")
                    withStyle(SpanStyle(color = colors.onSurfaceVariant)) { append(it) }
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = lines,
            overflow = TextOverflow.Ellipsis,
        )
        if (waiting) ToolBadge(item.state, approveHere, Modifier.padding(top = 2.dp), item.durationText)
    }
    if (!waiting) ToolBadge(item.state, approveHere, Modifier.padding(start = 4.dp), item.durationText)
}

/** The state in words, in a quiet label tinted by role. The word carries the meaning, so colour is never the only cue. */
@Composable
private fun ToolBadge(state: ToolState, approveHere: Boolean, modifier: Modifier, durationText: String? = null) {
    val base = if (approveHere && state == ToolState.WAITING_FOR_APPROVAL) "Waiting for your approval" else state.label
    val label = listOfNotNull(base, durationText?.takeIf { it.isNotBlank() }).joinToString(" \u00b7 ")
    Text(label, style = MaterialTheme.typography.labelSmall, color = state.glyph().tint(), modifier = modifier.testTag("tool_badge"))
}

/** What a step looks like at a glance. */
internal enum class Glyph { WORKING, DONE, FAILED, NEEDS_YOU, QUIET }

private fun ToolState.glyph(): Glyph = when (this) {
    ToolState.STREAMING, ToolState.RUNNING -> Glyph.WORKING
    ToolState.DONE -> Glyph.DONE
    ToolState.FAILED -> Glyph.FAILED
    ToolState.WAITING_FOR_APPROVAL, ToolState.SIGN_IN_REQUIRED -> Glyph.NEEDS_YOU
    ToolState.CANCELLED, ToolState.UNKNOWN -> Glyph.QUIET
}

@Composable
private fun Glyph.tint(): Color = when (this) {
    Glyph.WORKING -> MaterialTheme.colorScheme.primary
    Glyph.DONE -> MaterialTheme.colorScheme.onSurfaceVariant
    Glyph.FAILED -> MaterialTheme.colorScheme.error
    Glyph.NEEDS_YOU -> MaterialTheme.colorScheme.tertiary
    Glyph.QUIET -> MaterialTheme.colorScheme.outline
}

/** The glyph is decorative: the words next to it say the same, so a screen reader reads them and skips this. */
@Composable
internal fun GlyphIcon(glyph: Glyph, modifier: Modifier = Modifier) {
    val tint = glyph.tint()
    val icon: ImageVector? = when (glyph) {
        Glyph.WORKING -> null
        Glyph.DONE -> AppIcons.Check
        Glyph.FAILED -> AppIcons.ErrorCircle
        Glyph.NEEDS_YOU -> AppIcons.Lock
        Glyph.QUIET -> AppIcons.Remove
    }
    if (icon == null) {
        CircularProgressIndicator(modifier = modifier.size(18.dp), strokeWidth = 2.dp, color = tint)
    } else {
        Icon(icon, contentDescription = null, tint = tint, modifier = modifier.size(18.dp))
    }
}
