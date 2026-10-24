package dev.ahpclient.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.ChangeKind
import dev.ahpclient.state.ChangeRow
import dev.ahpclient.state.ChangesetPhase
import dev.ahpclient.state.ChangesetView
import dev.ahpclient.state.ChannelGoneException
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.ManageMessages
import dev.ahpclient.state.ManageResult
import com.microsoft.agenthostprotocol.generated.ChangesetState
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
) {
    ScreenScaffold(title = title, titleTag = "changeset_title", onBack = onBack, backLabel = "Back to session", large = false, modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (connection != ConnState.Online) ConnectionLine(connection, Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            when {
                error is ChannelGoneException -> ErrorText("These changes are no longer on the host.", Modifier.padding(horizontal = 24.dp).testTag("changeset_gone"))
                state != null -> ChangesetBody(remember(state) { ChangesetView.from(state) }, onSetReviewed, onOpenFile)
                error != null -> ErrorText("Could not load these changes (${error.javaClass.simpleName}).", Modifier.padding(horizontal = 24.dp).testTag("changeset_error"))
                else -> Busy("Loading changes…", Modifier.padding(horizontal = 24.dp).testTag("changeset_loading"))
            }
        }
    }
}

@Composable
private fun ChangesetBody(view: ChangesetView, onSetReviewed: (suspend (String, Boolean) -> ManageResult)?, onOpenFile: ((String) -> Unit)?) {
    val scope = rememberCoroutineScope()
    val inFlight = remember { mutableStateListOf<String>() }
    var notice by remember { mutableStateOf<String?>(null) }
    val note = listOfNotNull(
        if (onOpenFile != null) "Tap a file to see what changed in it." else null,
        if (onSetReviewed != null) "Tick “Viewed” to mark a file reviewed on the host." else null,
    ).joinToString(" ").ifEmpty { "Read only: you can look at what changed, not change it." }
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
                HorizontalDivider()
            }
            if (view.omitted > 0) {
                item(key = "omitted") {
                    Text("${view.omitted} more files not shown.", modifier = Modifier.padding(24.dp).testTag("changeset_omitted"))
                }
            }
        }
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
                Text(row.path, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                Text(
                    listOfNotNull(kind, if (row.reviewed) "Reviewed" else null).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            counts?.let { Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("change_counts")) }
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
