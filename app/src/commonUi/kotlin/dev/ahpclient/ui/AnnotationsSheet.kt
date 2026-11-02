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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.microsoft.agenthostprotocol.generated.Annotation
import com.microsoft.agenthostprotocol.generated.AnnotationEntry
import com.microsoft.agenthostprotocol.generated.AnnotationsState
import com.microsoft.agenthostprotocol.generated.StringOrMarkdown

/** Live annotation threads for one session. Comment writes are shown only when the connection has INTERACT capability. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AnnotationsSheet(
    state: AnnotationsState?,
    error: Throwable?,
    canInteract: Boolean,
    onDismiss: () -> Unit,
    onAddEntry: (annotationId: String, text: String) -> Unit,
    onRemoveEntry: (annotationId: String, entryId: String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("annotations_sheet"),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Annotations", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            when {
                state != null && state.annotations.isEmpty() -> Text("No annotations in this session.", modifier = Modifier.testTag("annotations_empty"))
                state != null -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.annotations, key = { it.id }) { annotation ->
                        AnnotationCard(annotation, canInteract, onAddEntry, onRemoveEntry)
                    }
                }
                error != null -> Text("Could not load annotations from the host.", modifier = Modifier.testTag("annotations_failed"))
                else -> Text("Loading annotations…", modifier = Modifier.testTag("annotations_loading"))
            }
        }
    }
}

@Composable
private fun AnnotationCard(
    annotation: Annotation,
    canInteract: Boolean,
    onAddEntry: (String, String) -> Unit,
    onRemoveEntry: (String, String) -> Unit,
) {
    var draft by rememberSaveable(annotation.id) { mutableStateOf("") }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().testTag("annotation_${annotation.id}"),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(annotation.resource.take(240), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(if (annotation.resolved) "Resolved" else "Open", style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("annotation_status_${annotation.id}"))
            }
            val origin = listOfNotNull(annotation.origin.chat?.let { "Chat $it" }, annotation.origin.turnId?.let { "Turn $it" }).joinToString(" · ")
            if (origin.isNotEmpty()) Text(origin.take(240), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            annotation.entries.forEach { entry ->
                AnnotationEntryRow(annotation.id, entry, canInteract, onRemoveEntry)
            }
            if (canInteract) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(MAX_COMMENT_CHARS) },
                    label = { Text("Add a comment") },
                    modifier = Modifier.fillMaxWidth().testTag("annotation_draft_${annotation.id}"),
                    minLines = 2,
                )
                OutlinedButton(
                    onClick = { val text = draft.trim(); if (text.isNotEmpty()) { onAddEntry(annotation.id, text); draft = "" } },
                    enabled = draft.isNotBlank(),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("annotation_add_${annotation.id}"),
                ) { Text("Add comment") }
            }
        }
    }
}

@Composable
private fun AnnotationEntryRow(
    annotationId: String,
    entry: AnnotationEntry,
    canInteract: Boolean,
    onRemove: (String, String) -> Unit,
) {
    val body = when (val text = entry.text) {
        is StringOrMarkdown.Plain -> text.value
        is StringOrMarkdown.Markdown -> text.value
    }.take(MAX_COMMENT_CHARS)
    Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp).testTag("annotation_entry_${annotationId}_${entry.id}")) {
        Text(body, style = MaterialTheme.typography.bodyMedium)
        if (canInteract) TextButton(onClick = { onRemove(annotationId, entry.id) }, modifier = Modifier.align(Alignment.End).testTag("annotation_remove_${entry.id}")) {
            Text("Remove comment")
        }
    }
}

private const val MAX_COMMENT_CHARS = 4_000
