package dev.ahpclient.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.ActivityState
import dev.ahpclient.state.Inbox
import dev.ahpclient.state.InboxItem
import dev.ahpclient.state.InteractMessages
import dev.ahpclient.state.InteractResult
import dev.ahpclient.state.ManageMessages
import dev.ahpclient.state.ManageResult
import dev.ahpclient.state.RequestRead
import kotlinx.coroutines.launch

/**
 * Where you are needed, in one place: the sessions waiting on you (what they ask, and for a single tool call the host's own buttons to
 * decide it, an approval behind the phone's device check as everywhere else), and the ones that finished on their own and that you have
 * not read. Tapping a session opens it. [details] holds what each waiting session was read to be waiting for; a session with no words yet,
 * or none, still has a way to open it. The buttons to decide exist only when [canDecide] (the app may act for you); the buttons to mark
 * sessions read only when [manage] is given. While [stale], the list is only what the phone saved last time: it says so, and nothing in it
 * can be decided or marked read, since the host may no longer have those sessions.
 */
@Composable
fun InboxScreen(
    inbox: Inbox,
    details: Map<String, RequestRead?>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    canDecide: Boolean,
    onDecide: suspend (chat: String, toolCallId: String, approve: Boolean, optionId: String?) -> InteractResult,
    manage: ManageActionsUi?,
    modifier: Modifier = Modifier,
    stale: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    ScreenScaffold(
        title = "Inbox",
        onBack = onBack,
        titleTag = "inbox_title",
        modifier = modifier,
        actions = { if (stale) UpdatingChip(Modifier.padding(end = 8.dp)) },
    ) {
        if (inbox.isEmpty) {
            EmptyState(
                AppIcons.Inbox,
                "Nothing needs you",
                modifier = Modifier.testTag("inbox_empty"),
                body = "Sessions that wait for you, and recent ones you have not read, show up here.",
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("inbox_list"),
                contentPadding = PaddingValues(horizontal = Design.ScreenPadding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (notice != null) item(key = "notice") { ManageNotice(notice, "inbox_notice") }
                if (inbox.waiting.isNotEmpty()) {
                    item(key = "waiting_header") { SectionHeader("Waiting for you", Modifier.testTag("inbox_waiting_header")) }
                    items(inbox.waiting, key = { "w:" + it.row.resource }) { item ->
                        WaitingCard(item, details[item.row.resource], loaded = details.containsKey(item.row.resource), canDecide, stale, onOpen, onDecide)
                    }
                }
                if (inbox.finished.isNotEmpty()) {
                    item(key = "finished_header") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionHeader("Finished, not read", Modifier.weight(1f).testTag("inbox_finished_header"))
                            if (manage != null && inbox.finished.size > 1) {
                                TextButton(
                                    enabled = !busy && !stale,
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            notice = null
                                            // One after the other: a refusal stops the rest and is said, so nothing is half done unseen.
                                            for (finished in inbox.finished) {
                                                val result = manage.setRead(finished.row.resource, true)
                                                if (result !is ManageResult.Done) {
                                                    notice = ManageMessages.describe(result)
                                                    break
                                                }
                                            }
                                            busy = false
                                        }
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp).testTag("inbox_mark_all"),
                                ) { Text("Mark all read") }
                            }
                        }
                    }
                    items(inbox.finished, key = { "f:" + it.row.resource }) { item -> FinishedCard(item, manage, stale, onOpen) }
                }
            }
        }
    }
}

/** The part of a card that opens the session: the state, the title and when, and what it is waiting for or doing. One item for a screen reader. */
@Composable
private fun CardHeader(item: InboxItem, detail: String?, second: String?, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val row = item.row
    val spoken = listOfNotNull(row.title, detail, second, row.whenText.ifEmpty { null }).joinToString(". ")
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open session", role = Role.Button) { onOpen(row.resource) }
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken }.testTag("inbox_header"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            StateMark(row.state)
            Text(row.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (row.whenText.isNotEmpty()) Text(row.whenText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("inbox_detail"))
        if (second != null) Text(second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WaitingCard(
    item: InboxItem,
    read: RequestRead?,
    loaded: Boolean,
    canDecide: Boolean,
    stale: Boolean,
    onOpen: (String) -> Unit,
    onDecide: suspend (chat: String, toolCallId: String, approve: Boolean, optionId: String?) -> InteractResult,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var said by remember(item.stamp) { mutableStateOf<String?>(null) }
    val target = read?.approval?.takeIf { canDecide }
    TileCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.testTag("inbox_waiting")) {
        CardHeader(item, detail = read?.text, second = item.row.subtitle, onOpen = onOpen)
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (target != null) {
                // With no options of the host's own the call is a plain Deny and Approve; with options, they are the buttons, as in the notification.
                data class Choice(val label: String, val approves: Boolean, val optionId: String?)
                val choices = if (target.options.isEmpty()) {
                    listOf(Choice("Deny", false, null), Choice("Approve", true, null))
                } else {
                    target.options.map { Choice(it.label, it.approves, it.id) }
                }
                choices.forEach { choice ->
                    val decide = {
                        scope.launch {
                            busy = true
                            said = InteractMessages.describeDecision(choice.approves, onDecide(target.chat, target.toolCallId, choice.approves, choice.optionId))
                            busy = false
                        }
                        Unit
                    }
                    val tag = if (target.options.isEmpty()) (if (choice.approves) "inbox_approve" else "inbox_deny") else "inbox_option"
                    if (choice.approves) {
                        Button(onClick = decide, enabled = !busy && !stale, modifier = Modifier.heightIn(min = 48.dp).testTag(tag)) { Text(choice.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    } else {
                        OutlinedButton(onClick = decide, enabled = !busy && !stale, modifier = Modifier.heightIn(min = 48.dp).testTag(tag)) { Text(choice.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            } else if (loaded || !canDecide) {
                // A question, or several things at once, or a phone that may not act: decided in the session, where it can all be seen.
                OutlinedButton(onClick = { onOpen(item.row.resource) }, modifier = Modifier.heightIn(min = 48.dp).testTag("inbox_open")) { Text("Open") }
            }
        }
        said?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).testTag("inbox_said").semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun FinishedCard(item: InboxItem, manage: ManageActionsUi?, stale: Boolean, onOpen: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var problem by remember(item.stamp) { mutableStateOf<String?>(null) }
    val row = item.row
    val result = if (row.state == ActivityState.ERROR) "Failed" else "Finished"
    val second = listOfNotNull(result, row.subtitle, row.changes?.text).joinToString(" · ")
    TileCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.testTag("inbox_finished")) {
        CardHeader(item, detail = null, second = second, onOpen = onOpen)
        if (manage != null) {
            TextButton(
                enabled = !busy && !stale,
                onClick = {
                    scope.launch {
                        busy = true
                        val done = manage.setRead(row.resource, true)
                        problem = if (done is ManageResult.Done) null else ManageMessages.describe(done)
                        busy = false
                    }
                },
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp).heightIn(min = 48.dp).testTag("inbox_mark_read"),
            ) { Text("Mark read") }
        }
        problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).testTag("inbox_problem")) }
    }
}
