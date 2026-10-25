package dev.ahpclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ahpclient.app.DiffLoad
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.DiffLine
import dev.ahpclient.state.DiffLineKind
import dev.ahpclient.state.DiffResult
import dev.ahpclient.state.FileDiff

/** One row of the list: the label of a block of lines, or one line. [key] is stable so a long list keeps its place. */
private sealed interface DiffRow {
    val key: String

    data class Header(override val key: String, val label: String) : DiffRow

    data class Line(override val key: String, val line: DiffLine) : DiffRow
}

private fun rowsOf(diff: FileDiff): List<DiffRow> =
    diff.hunks.flatMapIndexed { h, hunk ->
        listOf<DiffRow>(DiffRow.Header("h$h", hunk.label)) + hunk.lines.mapIndexed { i, line -> DiffRow.Line("h$h-$i", line) }
    }

/**
 * The line-by-line changes in one file. Everything in it is text the host or the file supplied, so it is shown as plain text
 * only, never as markup or a link. Added and removed lines carry a "+" or "−" and are named for a screen reader, so the
 * colour is never the only sign. [load] says what is going on: loading, the diff, or why there is none.
 */
@Composable
fun FileDiffScreen(path: String, load: DiffLoad, connection: ConnState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    // The bar names the file; the whole path is in the body, because on a long path the bar would cut off the end, which is the file name.
    ScreenScaffold(title = path.substringAfterLast('/').ifEmpty { path }, onBack = onBack, backLabel = "Back to files", large = false, modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (connection != ConnState.Online) ConnectionLine(connection)
                Text(
                    path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { heading() }.testTag("diff_title"),
                )
            }
            val sideMargin = Modifier.padding(horizontal = 24.dp)
            when (load) {
                DiffLoad.Loading -> Busy("Loading the file…", sideMargin.testTag("diff_loading"))
                is DiffLoad.Loaded -> when (val result = load.result) {
                    is DiffResult.Ready -> DiffBody(result.diff)
                    DiffResult.NoSuchFile -> Text("That file is no longer in this changeset.", sideMargin.testTag("diff_gone"))
                    DiffResult.TooLarge -> Text("Too big to compare on the phone.", sideMargin.testTag("diff_too_large"))
                    DiffResult.Binary -> Text("Not a text file, so it is not shown.", sideMargin.testTag("diff_binary"))
                    DiffResult.NoEarlierVersion -> Text(
                        "The host did not keep the earlier version: only the line counts are shown.",
                        sideMargin.testTag("diff_no_earlier"),
                    )
                    is DiffResult.Failed -> ErrorText("Could not load the file. ${result.reason}", sideMargin.testTag("diff_failed"))
                }
            }
        }
    }
}

@Composable
private fun DiffBody(diff: FileDiff) {
    val rows = remember(diff) { rowsOf(diff) }
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (diff.hunks.isEmpty()) {
                Text("No line changes.", modifier = Modifier.testTag("diff_none"))
            } else {
                Text("+${diff.added} −${diff.removed} lines", style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("diff_summary"))
            }
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().testTag("diff_list")) {
            items(rows, key = { it.key }) { row ->
                when (row) {
                    is DiffRow.Header -> Text(
                        row.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() }.testTag("diff_hunk"),
                    )
                    is DiffRow.Line -> DiffLineRow(row.line)
                }
            }
            if (diff.omittedLines > 0) {
                item(key = "omitted") {
                    Text("${diff.omittedLines} more lines not shown.", modifier = Modifier.padding(24.dp).testTag("diff_omitted"))
                }
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: DiffLine) {
    val scheme = MaterialTheme.colorScheme
    val (background, content) = when (line.kind) {
        DiffLineKind.ADDED -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        DiffLineKind.REMOVED -> scheme.errorContainer to scheme.onErrorContainer
        DiffLineKind.CONTEXT -> scheme.surface to scheme.onSurface
    }
    val marker = when (line.kind) {
        DiffLineKind.ADDED -> "+"
        DiffLineKind.REMOVED -> "−"
        DiffLineKind.CONTEXT -> " "
    }
    val number = line.newNumber ?: line.oldNumber
    val kindWord = when (line.kind) {
        DiffLineKind.ADDED -> "Added"
        DiffLineKind.REMOVED -> "Removed"
        DiffLineKind.CONTEXT -> "Unchanged"
    }
    Surface(
        color = background,
        contentColor = content,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "$kindWord, line $number: ${line.text.ifEmpty { "blank" }}" }
            .testTag("diff_line"),
    ) {
        Row(modifier = Modifier.padding(horizontal = 24.dp, vertical = 1.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Text(number?.toString().orEmpty(), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, modifier = Modifier.width(36.dp))
            Text(marker, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text(line.text.ifEmpty { " " }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        }
    }
}
