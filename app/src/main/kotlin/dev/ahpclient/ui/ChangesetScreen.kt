package dev.ahpclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
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
import com.microsoft.agenthostprotocol.generated.ChangesetState

/**
 * The files one changeset changed, with their line counts. Read only: the files' contents and a line-by-line diff are not
 * shown, and nothing can be marked reviewed. [state] is null until the host's snapshot arrives; [error] says why it may never
 * come. The host's own words (paths, errors) are plain text.
 */
@Composable
fun ChangesetScreen(
    title: String,
    state: ChangesetState?,
    error: Throwable?,
    connection: ConnState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.testTag("back")) { Text("Back to session") }
            if (connection != ConnState.Online) ConnectionLine(connection)
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }.testTag("changeset_title"))
        }
        when {
            error is ChannelGoneException -> ErrorText("These changes are no longer on the host.", Modifier.padding(horizontal = 24.dp).testTag("changeset_gone"))
            state != null -> ChangesetBody(remember(state) { ChangesetView.from(state) })
            error != null -> ErrorText("Could not load these changes (${error.javaClass.simpleName}).", Modifier.padding(horizontal = 24.dp).testTag("changeset_error"))
            else -> Busy("Loading changes…", Modifier.padding(horizontal = 24.dp).testTag("changeset_loading"))
        }
    }
}

@Composable
private fun ChangesetBody(view: ChangesetView) {
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
                "Read only: the files' contents and the line-by-line changes are not shown yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("changeset_note"),
            )
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().testTag("changeset_list")) {
            items(view.rows, key = { it.id }) { row ->
                ChangeRowItem(row)
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
private fun ChangeRowItem(row: ChangeRow) {
    val kind = when (row.kind) {
        ChangeKind.ADDED -> "Added"
        ChangeKind.MODIFIED -> "Modified"
        ChangeKind.DELETED -> "Deleted"
    }
    val counts = if (row.added != null && row.removed != null) "+${row.added} −${row.removed}" else null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(row.path, kind, counts?.replace("−", "minus ")?.replace("+", "plus "), if (row.reviewed) "reviewed" else null).joinToString(", ")
            }
            .testTag("change_row"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.path, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            Text(
                listOfNotNull(kind, if (row.reviewed) "Reviewed" else null).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        counts?.let { Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("change_counts")) }
    }
}
