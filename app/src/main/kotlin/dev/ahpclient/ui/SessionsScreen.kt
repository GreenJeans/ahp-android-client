package dev.ahpclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.SessionCatalog
import dev.ahpclient.state.SessionListModel
import dev.ahpclient.state.SessionRow
import dev.ahpclient.state.label
import dev.ahpclient.tunnel.TunnelInfo
import com.microsoft.agenthostprotocol.generated.SessionSummary
import java.time.Instant

/**
 * The host's sessions, those needing the user first. A tap opens one. The settings (what this app may do, background
 * monitoring) are one tap away behind the gear, and starting a session is the button at the bottom, present only when the
 * app has been allowed to look after sessions. [showArchived], [query] and [listState] are held by the caller so they
 * survive a trip into a session and back.
 */
@Composable
fun SessionsScreen(
    host: TunnelInfo,
    connection: ConnState,
    sessions: List<SessionSummary>,
    now: Instant,
    showArchived: Boolean,
    listState: LazyListState,
    onToggleArchived: () -> Unit,
    onOpen: (String) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    manage: ManageActionsUi? = null,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
) {
    val model = remember(sessions, showArchived, now, query) { SessionListModel.from(SessionCatalog.sorted(sessions), showArchived, now, query) }
    ScreenScaffold(
        title = "Sessions",
        onBack = onDisconnect,
        backLabel = "Back to hosts",
        backTag = "disconnect",
        modifier = modifier,
        actions = {
            IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("settings")) {
                Icon(AppIcons.Settings, contentDescription = "Settings")
            }
        },
        floatingActionButton = { if (manage != null) NewSessionButton(manage, onCreated = onOpen) },
    ) {
        // Room under the last card for the button, so it never hides a row.
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = Design.ScreenPadding, end = Design.ScreenPadding, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(Design.Gap),
            modifier = Modifier.fillMaxSize().testTag("session_list"),
        ) {
            item(key = "status") {
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(host.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ConnectionLine(connection)
                }
            }
            if (model.summary.isNotEmpty()) {
                item(key = "summary") { SummaryBanner(model) }
            }
            if (model.total > 0) {
                item(key = "search") { SearchField(query, onQueryChange, modifier = Modifier.padding(top = 8.dp)) }
            }
            if (model.hiddenArchived > 0 || showArchived) {
                item(key = "filters") {
                    FilterChip(
                        selected = showArchived,
                        onClick = onToggleArchived,
                        label = { Text(if (model.hiddenArchived > 0) "Show archived (${model.hiddenArchived})" else "Show archived") },
                        modifier = Modifier.padding(top = 4.dp).testTag("archived_toggle"),
                    )
                }
            }
            if (model.rows.isEmpty()) {
                item(key = "empty") { EmptyList(model, connection) }
            } else {
                itemsIndexed(model.rows, key = { _, row -> row.resource }) { index, row ->
                    SessionRowCard(row, connectedShape(index, model.rows.size), onOpen)
                }
            }
        }
    }
}

/** What is going on across the sessions, in a tinted card: warm when something waits on the user, calm otherwise. */
@Composable
private fun SummaryBanner(model: SessionListModel) {
    val colors = MaterialTheme.colorScheme
    val waiting = model.needsInput > 0
    Surface(
        color = if (waiting) colors.tertiaryContainer else colors.primaryContainer,
        contentColor = if (waiting) colors.onTertiaryContainer else colors.onPrimaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(
            model.summary,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp).testTag("summary"),
        )
    }
}

/** A rounded field that narrows the list as you type. Its trailing button clears it, and the keyboard's action just closes the keyboard. */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    TextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text("Search sessions") },
        leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.testTag("search_clear")) {
                    Icon(AppIcons.Clear, contentDescription = "Clear search")
                }
            }
        },
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        modifier = modifier.fillMaxWidth().testTag("search"),
    )
}

@Composable
private fun EmptyList(model: SessionListModel, connection: ConnState) {
    val tag = Modifier.testTag("empty_list")
    when {
        model.query.isNotEmpty() -> EmptyState(AppIcons.Search, "No sessions match \"${model.query}\"", tag, body = "Try another word, or clear the search.")
        model.total > 0 -> EmptyState(AppIcons.Info, "All ${model.total} sessions are archived", tag, body = "Use Show archived to see them.")
        connection == ConnState.Online -> EmptyState(AppIcons.Info, "No sessions on this host yet.", tag)
        connection is ConnState.Failed -> Unit
        else -> Box(tag.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) { Busy("Loading sessions…") }
    }
}

/** One session as a card. Its spoken description joins every part, so TalkBack reads the title, state and age as a single item. */
@Composable
private fun SessionRowCard(row: SessionRow, shape: Shape, onOpen: (String) -> Unit) {
    val description = listOfNotNull(
        row.title,
        row.state.label,
        row.subtitle,
        row.whenText.ifEmpty { null },
        if (row.isRead) null else "unread",
        if (row.isArchived) "archived" else null,
    ).joinToString(", ")
    TileCard(
        shape = shape,
        onClick = { onOpen(row.resource) },
        onClickLabel = "Open session",
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description }.testTag("session_row"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    row.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (row.isRead) FontWeight.Normal else FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                row.subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusChip(row.state)
                if (row.whenText.isNotEmpty()) {
                    Text(row.whenText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
