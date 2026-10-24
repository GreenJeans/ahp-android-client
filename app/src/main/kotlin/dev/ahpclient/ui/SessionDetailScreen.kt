package dev.ahpclient.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.SessionDetail
import dev.ahpclient.state.SessionGoneException
import dev.ahpclient.state.SessionPhase
import com.microsoft.agenthostprotocol.generated.SessionState
import java.time.Instant

/**
 * One session, read-only. [state] is null until the host's snapshot arrives; [error] says why it may never come. When the
 * agent is waiting on the user the request is shown in words and the user is told to answer in VS Code: this client never answers.
 */
@Composable
fun SessionDetailScreen(
    state: SessionState?,
    error: Throwable?,
    connection: ConnState,
    now: Instant,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenChat: (String) -> Unit = {},
    manage: ManageActionsUi? = null,
    sessionUri: String = "",
    onRemoved: () -> Unit = {},
    onOpenChangeset: (uri: String, label: String) -> Unit = { _, _ -> },
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenColumn {
            OutlinedButton(onClick = onBack, modifier = Modifier.testTag("back")) { Text("Back to sessions") }
            if (connection != ConnState.Online) ConnectionLine(connection)
            when {
                error is SessionGoneException -> ErrorText("This session is no longer on the host.", Modifier.testTag("session_gone"))
                state != null -> DetailBody(remember(state, now) { SessionDetail.from(state, now) }, onOpenChat, manage, sessionUri, onRemoved, onOpenChangeset)
                error != null -> ErrorText("Could not load this session (${error.javaClass.simpleName}).", Modifier.testTag("detail_error"))
                else -> Busy("Loading session…", Modifier.testTag("detail_loading"))
            }
        }
    }
}

@Composable
private fun DetailBody(
    detail: SessionDetail,
    onOpenChat: (String) -> Unit,
    manage: ManageActionsUi?,
    sessionUri: String,
    onRemoved: () -> Unit,
    onOpenChangeset: (uri: String, label: String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(detail.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() }.testTag("detail_title"))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusChip(detail.state)
            if (detail.isArchived) Text("Archived", style = MaterialTheme.typography.labelLarge)
        }
        when (detail.phase) {
            // A new session's chat is held back by the host until the first message is sent, so this can stay for as long as it takes to write one.
            SessionPhase.CREATING -> Text(
                "Starting on the host. A new session may wait for your first message: open its chat below and send one.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("session_starting").semantics { liveRegion = LiveRegionMode.Polite },
            )
            SessionPhase.FAILED -> ErrorText(
                "The host could not start this session." + (detail.creationError?.let { " $it" }.orEmpty()),
                Modifier.testTag("session_failed"),
            )
            SessionPhase.READY, SessionPhase.OTHER -> Unit
        }
        detail.activity?.let { Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("detail_activity")) }

        if (detail.inputNeeded.isNotEmpty()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag("input_needed").semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text("Waiting on you", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                detail.inputNeeded.forEach { request -> InputRequestCard(request) }
                Text(SessionDetail.ANSWER_ELSEWHERE, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("answer_hint"))
            }
        }

        Facts(detail)

        if (detail.changesets.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("changesets")) {
                Text("Changes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                detail.changesets.forEach { entry ->
                    val uri = entry.uri
                    if (uri != null) {
                        OutlinedButton(onClick = { onOpenChangeset(uri, entry.label) }, modifier = Modifier.heightIn(min = 48.dp).testTag("open_changeset")) {
                            Text(entry.label)
                        }
                    } else {
                        Text("${entry.label}: not shown here yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("changeset_unsupported"))
                    }
                }
            }
        }

        if (manage != null && sessionUri.isNotEmpty()) SessionManageBar(detail, sessionUri, manage, onRemoved)

        if (detail.chats.isNotEmpty()) {
            Text("Chats", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            detail.chats.forEach { chat ->
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClickLabel = "Open chat", role = Role.Button) { onOpenChat(chat.uri) }
                            .semantics(mergeDescendants = true) {}
                            .padding(vertical = 8.dp)
                            .testTag("chat_row"),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(chat.title, style = MaterialTheme.typography.bodyLarge)
                            val line = listOfNotNull(chat.activity, chat.whenText.ifEmpty { null }).joinToString(" · ")
                            if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatusChip(chat.state)
                    }
                    if (manage != null && sessionUri.isNotEmpty() && chat.removable) RemoveChatButton(chat.title, sessionUri, chat.uri, manage)
                }
            }
        }
    }
}

@Composable
private fun Facts(detail: SessionDetail) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Fact("Agent", detail.provider)
        detail.project?.let { Fact("Project", it) }
        detail.workingDirectories.forEach { Fact("Folder", it) }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
