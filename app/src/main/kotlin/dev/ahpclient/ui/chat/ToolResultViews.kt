package dev.ahpclient.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.Clipped
import dev.ahpclient.state.InputLine
import dev.ahpclient.state.ToolDetail
import dev.ahpclient.state.ToolResultView
import java.text.NumberFormat

/** What a tool call was asked and what it produced, as the body of its expanded block. [approveHere] is set when the buttons to decide it are on screen, so the block does not send the person elsewhere. */
@Composable
internal fun ToolDetailBody(detail: ToolDetail, approveHere: Boolean = false) {
    detail.waitingFor?.let { waiting ->
        Column(modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("tool_waiting"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(waiting, style = MaterialTheme.typography.titleSmall)
            if (!approveHere) Text(ToolDetail.APPROVE_ELSEWHERE, style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (detail.input.isNotEmpty()) {
        Column(modifier = Modifier.testTag("tool_input"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Input", style = MaterialTheme.typography.labelLarge)
            detail.input.forEach { line -> InputLineView(line) }
            if (detail.moreInput > 0) {
                Text("${detail.moreInput} more fields not shown.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    detail.results.forEach { result -> ToolResultRow(result) }
    detail.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("tool_error")) }
    if (detail.isEmpty) Text("No further details.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("tool_nothing"))
}

@Composable
private fun InputLineView(line: InputLine) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(line.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MonoBlock(line.value)
    }
}

@Composable
internal fun ToolResultRow(result: ToolResultView) {
    when (result) {
        is ToolResultView.Text -> MonoBlock(result.body, Modifier.testTag("result_text"))
        is ToolResultView.Terminal -> TerminalResult(result)
        is ToolResultView.FileEdit -> FileEditResult(result)
        is ToolResultView.Subagent -> Column(modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("result_subagent"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(result.title, style = MaterialTheme.typography.titleSmall)
            if (result.agentName.isNotBlank()) Text(result.agentName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (result.description.isNotBlank()) Text(result.description, style = MaterialTheme.typography.bodyMedium)
            Text("Its own transcript is not shown yet.", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is ToolResultView.Attachment -> Text(result.label, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("result_attachment"))
        ToolResultView.Unknown -> Text("A result this app cannot show.", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("result_unknown"))
    }
}

@Composable
private fun TerminalResult(result: ToolResultView.Terminal) {
    Column(modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("result_terminal"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(result.title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            ExitBadge(result.exitCode)
        }
        val output = result.output
        if (output != null) {
            MonoBlock(output)
        } else {
            Text(if (result.exitCode == null) "No output yet." else "No output.", style = MaterialTheme.typography.bodyMedium)
        }
        if (result.clippedByHost) Text("The host cut this output short.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The exit code in words, tinted by role. A command that has not finished says so instead of showing nothing. */
@Composable
private fun ExitBadge(exitCode: Long?) {
    val colors = MaterialTheme.colorScheme
    val (label, container, content) = when {
        exitCode == null -> Triple("running", colors.primaryContainer, colors.onPrimaryContainer)
        exitCode == 0L -> Triple("exit 0", colors.surfaceVariant, colors.onSurfaceVariant)
        else -> Triple("exit $exitCode", colors.errorContainer, colors.onErrorContainer)
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small, modifier = Modifier.testTag("exit_badge")) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun FileEditResult(result: ToolResultView.FileEdit) {
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("result_file_edit"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(result.path, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        if (result.added != null || result.removed != null) {
            val added = result.added ?: 0
            val removed = result.removed ?: 0
            Text(
                "+$added \u2212$removed",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.semantics { contentDescription = "$added lines added, $removed removed" },
            )
        }
    }
}

/**
 * Text as the tool printed it: monospace, not wrapped, scrolling sideways, because output is data and its layout matters.
 * When part of it was left out, the block says how much, so nobody mistakes a cut for the whole.
 */
@Composable
internal fun MonoBlock(text: Clipped, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant, shape = MaterialTheme.shapes.small) {
            Text(
                text.text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
            )
        }
        if (text.isClipped) {
            val format = NumberFormat.getIntegerInstance()
            Text(
                "Showing the first ${format.format(text.text.length)} of ${format.format(text.fullLength)} characters.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("clipped_note"),
            )
        }
    }
}
