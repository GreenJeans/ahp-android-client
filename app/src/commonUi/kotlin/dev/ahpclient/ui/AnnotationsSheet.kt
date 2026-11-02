package dev.ahpclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.microsoft.agenthostprotocol.generated.AnnotationsState
import dev.ahpclient.state.AnnotationEntryRow
import dev.ahpclient.state.AnnotationRow
import dev.ahpclient.state.AnnotationsView
import dev.ahpclient.state.ManageMessages
import dev.ahpclient.state.ManageResult
import kotlinx.coroutines.launch

/**
 * Live annotation threads for one session. Comment writes are shown only when the connection has INTERACT capability, and what
 * goes wrong with one is said here, because the sheet covers the session's own banner.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AnnotationsSheet(
    state: AnnotationsState?,
    error: Throwable?,
    canInteract: Boolean,
    onDismiss: () -> Unit,
    onAddEntry: suspend (annotationId: String, text: String) -> ManageResult,
    onRemoveEntry: suspend (annotationId: String, entryId: String) -> ManageResult,
) {
    val view = remember(state) { state?.let(AnnotationsView::from) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("annotations_sheet"),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Annotations", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            ManageNotice(notice, "annotations_notice")
            Spacer(Modifier.height(8.dp))
            when {
                view != null && view.rows.isEmpty() -> Text("No annotations in this session.", modifier = Modifier.testTag("annotations_empty"))
                view != null -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(view.rows, key = { it.id }) { annotation ->
                        AnnotationCard(
                            annotation = annotation,
                            canInteract = canInteract,
                            onAdd = { text, done ->
                                scope.launch {
                                    val result = onAddEntry(annotation.id, text)
                                    notice = ManageMessages.describe(result)
                                    done(result)
                                }
                            },
                            onRemove = { entryId -> scope.launch { notice = ManageMessages.describe(onRemoveEntry(annotation.id, entryId)) } },
                        )
                    }
                    if (view.omitted > 0) item(key = "omitted") { Text("${view.omitted} more not shown.", style = MaterialTheme.typography.bodySmall) }
                }
                error != null -> Text("Could not load annotations from the host.", modifier = Modifier.testTag("annotations_failed"))
                else -> Text("Loading annotations…", modifier = Modifier.testTag("annotations_loading"))
            }
        }
    }
}

@Composable
private fun AnnotationCard(
    annotation: AnnotationRow,
    canInteract: Boolean,
    onAdd: (text: String, done: (ManageResult) -> Unit) -> Unit,
    onRemove: (entryId: String) -> Unit,
) {
    var draft by rememberSaveable(annotation.id) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().testTag("annotation_${annotation.id}"),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(annotation.target, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(if (annotation.resolved) "Resolved" else "Open", style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("annotation_status_${annotation.id}"))
            }
            annotation.entries.forEach { entry ->
                // An annotation must keep at least one entry.
                AnnotationEntryLine(annotation.id, entry, canInteract && annotation.entries.size > 1, onRemove)
            }
            if (annotation.omittedEntries > 0) Text("${annotation.omittedEntries} more comments not shown.", style = MaterialTheme.typography.bodySmall)
            if (canInteract) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(AnnotationsView.MAX_COMMENT_CHARS) },
                    label = { Text("Add a comment") },
                    modifier = Modifier.fillMaxWidth().testTag("annotation_draft_${annotation.id}"),
                    minLines = 2,
                )
                OutlinedButton(
                    onClick = {
                        val text = draft.trim()
                        if (text.isNotEmpty()) {
                            sending = true
                            onAdd(text) { result ->
                                sending = false
                                // The words are kept until the host has them, so a failed send can be tried again.
                                if (result is ManageResult.Done) draft = ""
                            }
                        }
                    },
                    enabled = draft.isNotBlank() && !sending,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("annotation_add_${annotation.id}"),
                ) { Text("Add comment") }
            }
        }
    }
}

@Composable
private fun AnnotationEntryLine(
    annotationId: String,
    entry: AnnotationEntryRow,
    canRemove: Boolean,
    onRemove: (entryId: String) -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp).testTag("annotation_entry_${annotationId}_${entry.id}")) {
        Text(entry.text, style = MaterialTheme.typography.bodyMedium)
        if (canRemove) TextButton(onClick = { confirming = true }, modifier = Modifier.align(Alignment.End).testTag("annotation_remove_${entry.id}")) {
            Text("Remove comment")
        }
    }
    // A comment may be someone else's, and nothing here can bring one back.
    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text("Remove this comment?") },
        text = { Text("It is removed from the session on the host. This app cannot bring it back.") },
        confirmButton = {
            TextButton(onClick = { confirming = false; onRemove(entry.id) }, modifier = Modifier.testTag("annotation_remove_confirm")) { Text("Remove") }
        },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
    )
}
