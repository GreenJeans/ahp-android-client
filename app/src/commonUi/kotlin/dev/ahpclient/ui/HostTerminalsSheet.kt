package dev.ahpclient.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.HostTerminalEntry

/**
 * The host's own terminals, from its root state: each a row to watch it read only. Nothing here can start, stop or type into one.
 * The host's list carries no folder for a terminal, so the row says its title and whether it runs; the folder is on the terminal's screen.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun HostTerminalsSheet(terminals: List<HostTerminalEntry>, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("host_terminals_sheet"),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("Terminals", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp).semantics { heading() })
            Text(
                "Open on the host. Watch only: nothing typed here reaches them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).testTag("host_terminal_list")) {
                items(terminals, key = { it.uri }) { terminal -> TerminalRow(terminal) { onOpen(terminal.uri) } }
            }
        }
    }
}

/** One terminal as one item for a screen reader: "dev server, Running". */
@Composable
private fun TerminalRow(terminal: HostTerminalEntry, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Watch terminal", role = Role.Button, onClick = onOpen)
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = "${terminal.title}, ${terminal.status}" }
            .testTag("host_terminal_row"),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(terminal.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                terminal.status,
                style = MaterialTheme.typography.bodySmall,
                color = if (terminal.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("host_terminal_status"),
            )
        }
    }
}
