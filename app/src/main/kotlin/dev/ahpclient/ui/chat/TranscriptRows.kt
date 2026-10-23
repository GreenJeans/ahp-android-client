package dev.ahpclient.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.SessionDetail
import dev.ahpclient.state.ToolDetail
import dev.ahpclient.state.ToolState
import dev.ahpclient.state.TranscriptItem
import dev.ahpclient.ui.InputRequestCard

/**
 * One row of the transcript. Reasoning and tool calls fold up to one line and open on tap; [expanded] says whether this row is
 * open and [onToggle] flips it. The rest are always shown as they are.
 */
@Composable
internal fun TranscriptRow(
    item: TranscriptItem,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
    onToggle: () -> Unit = {},
) {
    when (item) {
        is TranscriptItem.User -> UserRow(item, modifier)
        is TranscriptItem.Markdown -> MarkdownPart(item.text, onOpenLink, modifier.fillMaxWidth().testTag("row_markdown"))
        is TranscriptItem.Reasoning -> ReasoningBlock(item, expanded, onToggle, onOpenLink, modifier)
        is TranscriptItem.Tool -> ToolCallBlock(item, expanded, onToggle, modifier)
        is TranscriptItem.Notice -> Text(
            item.text,
            style = MaterialTheme.typography.labelLarge,
            color = if (item.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.testTag("row_notice"),
        )
        is TranscriptItem.Question -> Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            InputRequestCard(item.request)
            Text(
                if (item.answered) "Answered." else SessionDetail.ANSWER_ELSEWHERE,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("row_question_note"),
            )
        }
        is TranscriptItem.Other -> Text(
            item.label,
            style = MaterialTheme.typography.bodyMedium,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.testTag("row_other"),
        )
    }
}

@Composable
private fun UserRow(item: TranscriptItem.User, modifier: Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("row_user"),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("You", style = MaterialTheme.typography.labelMedium)
            Text(item.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ReasoningBlock(item: TranscriptItem.Reasoning, expanded: Boolean, onToggle: () -> Unit, onOpenLink: (String) -> Unit, modifier: Modifier) {
    val label = if (item.live) "Thinking…" else "Thought"
    val style = MaterialTheme.typography.bodyMedium
    // Nothing to open when the agent left no text: a plain line, not a button that opens onto nothing.
    if (item.text.isBlank()) {
        Text(label, style = style, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.testTag("row_reasoning"))
        return
    }
    CollapsibleBlock(
        expanded = expanded,
        onToggle = onToggle,
        modifier = modifier.testTag("row_reasoning"),
        header = { Text(label, style = style, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)) },
    ) {
        MarkdownPart(item.text, onOpenLink, Modifier.fillMaxWidth())
    }
}

/** A tool call opens to its input and its results. One made without the host's record has nothing to open and stays a plain row. */
@Composable
private fun ToolCallBlock(item: TranscriptItem.Tool, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    val source = item.source
    if (source == null) {
        Row(
            modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("row_tool"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { ToolHeader(item, Modifier.weight(1f)) }
        return
    }
    CollapsibleBlock(
        expanded = expanded,
        onToggle = onToggle,
        modifier = modifier.testTag("row_tool"),
        header = { ToolHeader(item, Modifier.weight(1f)) },
    ) {
        // Built here, so only a block the user opens pays for parsing its input and clipping its results.
        val detail = remember(source) { ToolDetail.from(source) }
        ToolDetailBody(detail)
    }
}

@Composable
private fun RowScope.ToolHeader(item: TranscriptItem.Tool, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        item.summary?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    ToolBadge(item.state)
}

/** The state in words, tinted by role. The label carries the meaning, so colour is never the only cue. */
@Composable
private fun ToolBadge(state: ToolState) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (state) {
        ToolState.WAITING_FOR_APPROVAL, ToolState.SIGN_IN_REQUIRED -> colors.tertiaryContainer to colors.onTertiaryContainer
        ToolState.RUNNING, ToolState.STREAMING -> colors.primaryContainer to colors.onPrimaryContainer
        ToolState.FAILED -> colors.errorContainer to colors.onErrorContainer
        ToolState.DONE, ToolState.CANCELLED, ToolState.UNKNOWN -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small, modifier = Modifier.testTag("tool_badge")) {
        Text(state.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}
