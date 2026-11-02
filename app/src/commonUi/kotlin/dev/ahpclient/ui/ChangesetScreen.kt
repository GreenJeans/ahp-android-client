package dev.ahpclient.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.microsoft.agenthostprotocol.generated.ChangesetState
import dev.ahpclient.state.ChangeKind
import dev.ahpclient.state.ChangeRow
import dev.ahpclient.state.ChangesetOperationItem
import dev.ahpclient.state.ChangesetOperationPhase
import dev.ahpclient.state.ChangesetPhase
import dev.ahpclient.state.ChangesetView
import dev.ahpclient.state.ChannelGoneException
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.ManageMessages
import dev.ahpclient.state.ManageResult
import kotlinx.coroutines.launch

/**
 * The files one changeset changed, with their line counts. [state] is null until the host's snapshot arrives; [error] says
 * why it may never come. The host's own words (paths, errors) are plain text.
 *
 * [onSetReviewed] is given only when the connection may look after sessions and the host says this changeset can be reviewed;
 * it adds a "Viewed" box to each file. Nothing is shown as ticked until the host has echoed it, so a box can be disabled
 * for a moment while a tap is in flight, and a refusal is said in words.
 */
@Composable
fun ChangesetScreen(
    title: String,
    state: ChangesetState?,
    error: Throwable?,
    connection: ConnState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onSetReviewed: (suspend (fileId: String, reviewed: Boolean) -> ManageResult)? = null,
    onOpenFile: ((fileId: String) -> Unit)? = null,
    onInvokeOperation: (suspend (operationId: String) -> ManageResult)? = null,
    onInvokeFileOperation: (suspend (fileId: String, operationId: String) -> ManageResult)? = null,
) {
    ScreenScaffold(title = title, titleTag = "changeset_title", onBack = onBack, backLabel = "Back to session", large = false, modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (connection != ConnState.Online) ConnectionLine(connection, Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            when {
                error is ChannelGoneException -> ErrorText("These changes are no longer on the host.", Modifier.padding(horizontal = 24.dp).testTag("changeset_gone"))
                state != null -> ChangesetBody(remember(state) { ChangesetView.from(state) }, onSetReviewed, onOpenFile, onInvokeOperation, onInvokeFileOperation)
                error != null -> ErrorText("Could not load these changes (${error.javaClass.simpleName}).", Modifier.padding(horizontal = 24.dp).testTag("changeset_error"))
                else -> Busy("Loading changes…", Modifier.padding(horizontal = 24.dp).testTag("changeset_loading"))
            }
        }
    }
}

private data class PendingOperation(val op: ChangesetOperationItem, val file: ChangeRow? = null)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChangesetBody(
    view: ChangesetView,
    onSetReviewed: (suspend (String, Boolean) -> ManageResult)?,
    onOpenFile: ((String) -> Unit)?,
    onInvokeOperation: (suspend (String) -> ManageResult)?,
    onInvokeFileOperation: (suspend (String, String) -> ManageResult)?,
) {
    val scope = rememberCoroutineScope()
    val inFlight = remember { mutableStateListOf<String>() }
    val opsInFlight = remember { mutableStateListOf<String>() }
    var confirmingOp by remember { mutableStateOf<PendingOperation?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var opMessage by remember { mutableStateOf<String?>(null) }
    val note = listOfNotNull(
        if (onOpenFile != null) "Tap a file to see what changed in it." else null,
        if (onSetReviewed != null) "Tick “Viewed” to mark a file reviewed on the host." else null,
    ).joinToString(" ").ifEmpty { "Read only: you can look at what changed, not change it." }
    fun runOperation(opId: String, fileId: String? = null) {
        val flightKey = if (fileId != null) "$fileId:$opId" else opId
        if (flightKey in opsInFlight) return
        val invoke: suspend () -> ManageResult = if (fileId != null) {
            val fileInvoke = onInvokeFileOperation ?: return
            suspend { fileInvoke(fileId, opId) }
        } else {
            val csInvoke = onInvokeOperation ?: return
            suspend { csInvoke(opId) }
        }
        opsInFlight += flightKey
        notice = null
        opMessage = null
        scope.launch {
            try {
                val res = invoke()
                notice = ManageMessages.describe(res)
                if (res is ManageResult.Done) opMessage = res.created
            } finally {
                opsInFlight -= flightKey
            }
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (view.phase) {
                ChangesetPhase.COMPUTING -> Text("The host is working out what changed…", modifier = Modifier.testTag("changeset_computing").semantics { liveRegion = LiveRegionMode.Polite })
                ChangesetPhase.RECOMPUTING -> Text("Refreshing. What is shown may be out of date.", modifier = Modifier.testTag("changeset_recomputing"))
                ChangesetPhase.ERROR -> ErrorText("The host could not work out the changes." + (view.error?.let { " $it" }.orEmpty()), Modifier.testTag("changeset_failed"))
                ChangesetPhase.OTHER -> Text("The host reports a state this app does not know.", modifier = Modifier.testTag("changeset_unknown"))
                ChangesetPhase.READY -> Unit
            }
            if (view.rows.isNotEmpty()) {
                Text(
                    "${view.rows.size + view.omitted} ${if (view.rows.size + view.omitted == 1) "file" else "files"} · +${view.totalAdded} −${view.totalRemoved}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("changeset_summary"),
                )
            } else if (view.phase == ChangesetPhase.READY) {
                Text("No files changed.", modifier = Modifier.testTag("changeset_empty"))
            }
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("changeset_note"),
            )
            if (onInvokeOperation != null && view.operations.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().testTag("changeset_operations"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    view.operations.forEach { op ->
                        val running = op.phase == ChangesetOperationPhase.RUNNING || op.id in opsInFlight
                        OutlinedButton(
                            onClick = { if (op.confirmation != null) confirmingOp = PendingOperation(op) else runOperation(op.id) },
                            enabled = op.phase.canInvoke && !running,
                            modifier = Modifier.testTag("changeset_op_${op.id}"),
                        ) {
                            Text(if (running) "${op.label}…" else op.label)
                        }
                    }
                }
                view.operations.forEach { op ->
                    op.error?.let { err ->
                        ErrorText("${op.label}: $err", Modifier.testTag("changeset_op_error_${op.id}"))
                    }
                }
            }
            opMessage?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("operation_message").semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            ManageNotice(notice, "review_notice")
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().testTag("changeset_list")) {
            items(view.rows, key = { it.id }) { row ->
                ChangeRowItem(
                    row = row,
                    onOpen = onOpenFile?.let { open -> { open(row.id) } },
                    reviewEnabled = row.id !in inFlight,
                    onReviewChange = onSetReviewed?.let { set ->
                        { reviewed ->
                            if (row.id !in inFlight) {
                                inFlight += row.id
                                notice = null
                                scope.launch {
                                    try {
                                        notice = ManageMessages.describe(set(row.id, reviewed))
                                    } finally {
                                        inFlight -= row.id
                                    }
                                }
                            }
                        }
                    },
                )
                if (onInvokeFileOperation != null && view.resourceOperations.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp).testTag("changeset_file_operations_${row.id}"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        view.resourceOperations.forEach { op ->
                            val running = op.phase == ChangesetOperationPhase.RUNNING || "${row.id}:${op.id}" in opsInFlight
                            OutlinedButton(
                                onClick = { if (op.confirmation != null) confirmingOp = PendingOperation(op, row) else runOperation(op.id, row.id) },
                                enabled = op.phase.canInvoke && !running,
                                modifier = Modifier.testTag("changeset_file_op_${row.id}_${op.id}"),
                            ) {
                                Text(if (running) "${op.label}…" else op.label)
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
            if (view.omitted > 0) {
                item(key = "omitted") {
                    Text("${view.omitted} more files not shown.", modifier = Modifier.padding(24.dp).testTag("changeset_omitted"))
                }
            }
        }
    }
    val pendingOp = confirmingOp
    if (pendingOp != null && (if (pendingOp.file != null) onInvokeFileOperation != null else onInvokeOperation != null)) {
        AlertDialog(
            onDismissRequest = { confirmingOp = null },
            title = { Text(if (pendingOp.file != null) "${pendingOp.op.label} (${pendingOp.file.fileName})?" else "${pendingOp.op.label}?") },
            text = {
                Text(
                    listOfNotNull(
                        pendingOp.file?.path,
                        pendingOp.op.confirmation,
                        "You will be asked for your fingerprint, face or screen lock.",
                    ).joinToString(" "),
                    modifier = Modifier.testTag("changeset_op_confirm_text"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingOp = null
                        runOperation(pendingOp.op.id, pendingOp.file?.id)
                    },
                    modifier = Modifier.testTag("changeset_op_confirm"),
                ) { Text("Run") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingOp = null }, modifier = Modifier.testTag("changeset_op_cancel")) { Text("Cancel") }
            },
            modifier = Modifier.testTag("changeset_op_confirm_dialog"),
        )
    }
}

@Composable
private fun ChangeRowItem(row: ChangeRow, onOpen: (() -> Unit)?, reviewEnabled: Boolean, onReviewChange: ((Boolean) -> Unit)?) {
    val kind = when (row.kind) {
        ChangeKind.ADDED -> "Added"
        ChangeKind.MODIFIED -> "Modified"
        ChangeKind.DELETED -> "Deleted"
    }
    val counts = if (row.added != null && row.removed != null) "+${row.added} −${row.removed}" else null
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .then(if (onOpen != null) Modifier.heightIn(min = 48.dp).clickable(onClickLabel = "Show the changes in this file", onClick = onOpen) else Modifier)
                .semantics(mergeDescendants = true) {
                    contentDescription = listOfNotNull(row.path, kind, counts?.replace("−", "minus ")?.replace("+", "plus "), if (row.reviewed) "reviewed" else null).joinToString(", ")
                }
                .testTag("change_row"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(
                    row.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                row.directory?.let { dir ->
                    Text(
                        dir,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
                Text(
                    listOfNotNull(kind, if (row.reviewed) "Reviewed" else null).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val added = row.added
            val removed = row.removed
            if (added != null && removed != null) {
                val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
                val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
                val addedColor = if (added > 0) (if (isDark) Color(0xFF81C784) else Color(0xFF1B5E20)) else mutedColor
                val removedColor = if (removed > 0) (if (isDark) Color(0xFFE57373) else Color(0xFFB71C1C)) else mutedColor
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = addedColor, fontWeight = FontWeight.Medium)) { append("+$added") }
                        withStyle(SpanStyle(color = mutedColor)) { append(" ") }
                        withStyle(SpanStyle(color = removedColor, fontWeight = FontWeight.Medium)) { append("−$removed") }
                    },
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.testTag("change_counts"),
                )
            }
        }
        if (onReviewChange != null) ViewedBox(row.path, row.reviewed, reviewEnabled, onReviewChange)
    }
}

/** The "Viewed" box of one file. It shows what the host holds; a tap asks the host to flip it. */
@Composable
private fun ViewedBox(path: String, reviewed: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .toggleable(value = reviewed, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .semantics { contentDescription = "Viewed: $path" }
            .testTag("viewed_toggle"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = reviewed, onCheckedChange = null, enabled = enabled)
        Text("Viewed", style = MaterialTheme.typography.labelLarge)
    }
}
