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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ahpclient.app.AppController
import dev.ahpclient.state.AutomationRow
import dev.ahpclient.state.AutomationRunRow
import dev.ahpclient.state.Automations
import dev.ahpclient.state.HostSession
import dev.ahpclient.state.ManageMessages
import kotlinx.coroutines.launch

/**
 * [AutomationsSheet] for the focused host's [session]: holds the catalogue open while shown, and turns the switch and the cancel
 * button into the controller's checked requests. A cancellation is asked about first, since a run cannot be started again from here.
 */
@Composable
internal fun AutomationsHost(session: HostSession, controller: AppController, onOpenSession: (String) -> Unit, onDismiss: () -> Unit) {
    val store = remember(session) { session.openAutomations() }
    DisposableEffect(session) { onDispose { session.closeChannel(Automations.CHANNEL) } }
    val state by store.state.collectAsStateWithLifecycle()
    val capabilities by session.automations.collectAsStateWithLifecycle()
    val canManage by session.canManage.collectAsStateWithLifecycle()
    val now by rememberNow()
    val rows = remember(state, now, capabilities) { state?.let { Automations.rows(it, now, capabilities) } }
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmCancel by remember { mutableStateOf<String?>(null) }
    AutomationsSheet(
        rows = rows,
        canManage = canManage,
        onToggle = { resource, enabled -> scope.launch { notice = ManageMessages.describe(controller.setAutomationEnabled(resource, enabled)) } },
        onCancelRun = { confirmCancel = it },
        onOpenSession = onOpenSession,
        onDismiss = onDismiss,
        notice = notice,
    )
    confirmCancel?.let { run ->
        AlertDialog(
            onDismissRequest = { confirmCancel = null },
            title = { Text("Cancel this run?") },
            text = { Text("The host stops the agent's work for this run. It cannot be resumed from here.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = null
                    scope.launch { notice = ManageMessages.describe(controller.cancelAutomationRun(run)) }
                }, modifier = Modifier.testTag("automation_cancel_confirm")) { Text("Cancel run") }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = null }) { Text("Keep it") } },
        )
    }
}

/**
 * The host's automations (P7): each with what sets it off, whether it is on, its next run and its latest runs. On the manage tier
 * an automation the host lets be changed has a switch for its triggers, and a waiting or running run can be cancelled. A run with a
 * session opens it. Nothing here creates, edits or removes an automation. [rows] is null while the catalogue is on its way.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AutomationsSheet(
    rows: List<AutomationRow>?,
    canManage: Boolean,
    onToggle: (resource: String, enabled: Boolean) -> Unit,
    onCancelRun: (run: String) -> Unit,
    onOpenSession: (session: String) -> Unit,
    onDismiss: () -> Unit,
    notice: String? = null,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // The handle is itself a control (it expands and collapses the sheet), so it is drawn wide enough to be a 48 dp target.
        dragHandle = { BottomSheetDefaults.DragHandle(width = 48.dp) },
        modifier = Modifier.testTag("automations_sheet"),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("Automations", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp).semantics { heading() })
            Text(
                if (canManage) "On the host. Turning one off stops its schedule; it can still be run by hand there." else "On the host. Turn on \"Act for me\" to switch them on or off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp).testTag("automations_notice")) }
            when {
                rows == null -> Text("Loading automations…", modifier = Modifier.padding(20.dp).testTag("automations_loading"))
                rows.isEmpty() -> Text("This host has no automations.", modifier = Modifier.padding(20.dp).testTag("automations_empty"))
                else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp).testTag("automation_list")) {
                    items(rows, key = { it.resource }) { row ->
                        AutomationItem(row, canManage, onToggle, onCancelRun, onOpenSession)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun AutomationItem(
    row: AutomationRow,
    canManage: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onCancelRun: (String) -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val switchable = canManage && row.canToggle
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("automation_row")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (switchable) Modifier.toggleable(value = row.enabled, role = Role.Switch, onValueChange = { onToggle(row.resource, it) }) else Modifier)
                .padding(horizontal = 20.dp)
                .semantics(mergeDescendants = true) { contentDescription = row.spoken }
                .testTag("automation_header"),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(row.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(row.triggers, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    listOfNotNull(if (row.enabled) "On" else "Off", row.nextRun).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (switchable) Switch(checked = row.enabled, onCheckedChange = null, modifier = Modifier.testTag("automation_switch"))
        }
        row.runs.forEach { run -> RunItem(run, canManage, onCancelRun, onOpenSession) }
    }
}

/** One run: what became of it and when, opening its session when it has one, with a way to cancel it while it is still going. */
@Composable
private fun RunItem(run: AutomationRunRow, canManage: Boolean, onCancelRun: (String) -> Unit, onOpenSession: (String) -> Unit) {
    val session = run.primarySession
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (session != null) Modifier.clickable(onClickLabel = "Open its session", role = Role.Button) { onOpenSession(session) } else Modifier)
            .padding(start = 36.dp, end = 12.dp)
            .testTag("automation_run"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            run.spoken,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).semantics { contentDescription = listOfNotNull("Run", run.spoken, "opens its session".takeIf { session != null }).joinToString(", ") },
        )
        if (canManage && run.canCancel) {
            TextButton(onClick = { onCancelRun(run.resource) }, modifier = Modifier.heightIn(min = 48.dp).testTag("automation_run_cancel")) { Text("Cancel run") }
        }
    }
}
