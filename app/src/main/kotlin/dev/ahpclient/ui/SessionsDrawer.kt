package dev.ahpclient.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.ActivityState
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.SessionCatalog
import dev.ahpclient.state.SessionListModel
import dev.ahpclient.state.SessionRow
import dev.ahpclient.state.label
import dev.ahpclient.app.HostRef
import com.microsoft.agenthostprotocol.generated.SessionSummary
import java.time.Instant

/**
 * The side menu: the host at the top (a way to the hosts sheet), one row of icon buttons (the inbox, the archived toggle, a button to
 * start a session only when the app may look after sessions, and Settings), then the host's sessions with those needing you
 * first. [host] is null while not connected: the menu then says so, with a button to the hosts sheet.
 * [selected] is the open session. [listState], [showArchived] and [query] are held by the caller so they survive the menu closing.
 * While [stale] the sessions are only what the phone saved last time, and say so.
 */
@Composable
fun DrawerContent(
    host: HostRef?,
    connection: ConnState?,
    sessions: List<SessionSummary>,
    now: Instant,
    selected: String?,
    showArchived: Boolean,
    onToggleArchived: () -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    listState: LazyListState,
    manage: ManageActionsUi?,
    onOpenHost: () -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onSessionCreated: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** The sessions the user chose to be alerted about, each marked with a bell in the list. */
    watched: Set<String> = emptySet(),
    /** How many sessions wait on the user; null when there is no host, and the inbox entry is not shown. */
    inboxCount: Int? = null,
    onOpenInbox: () -> Unit = {},
    stale: Boolean = false,
) {
    val model = remember(sessions, showArchived, now, query) { SessionListModel.from(SessionCatalog.sorted(sessions), showArchived, now, query) }
    ModalDrawerSheet(modifier = modifier.testTag("drawer")) {
        Column(Modifier.fillMaxSize()) {
            HostButton(host, connection, onOpenHost)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (inboxCount != null && host != null) InboxAction(inboxCount, onOpenInbox)
                if (host != null && (model.hiddenArchived > 0 || showArchived)) ArchivedAction(showArchived, model.hiddenArchived, onToggleArchived)
                Spacer(Modifier.weight(1f))
                if (manage != null && host != null) {
                    Tooltip("New session") { NewSessionIconButton(manage, onSessionCreated) }
                }
                Tooltip("Settings") {
                    IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("settings")) {
                        Icon(AppIcons.Settings, contentDescription = "Settings")
                    }
                }
            }
            HorizontalDivider()
            if (host == null) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp).testTag("drawer_no_host"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Connect to a host to see its sessions.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onOpenHost, modifier = Modifier.heightIn(min = 48.dp).testTag("choose_host")) { Text("Choose a host") }
                }
            } else {
                SessionList(model, connection, stale, selected, query, onQueryChange, listState, watched, onOpenSession, Modifier.weight(1f))
            }
        }
    }
}

/** A name shown on long press, for an icon button that has no words of its own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Tooltip(label: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        content = content,
    )
}

/** The way to the inbox, with how many sessions wait on you. Said as one item: "Inbox, 2 need you". */
@Composable
private fun InboxAction(count: Int, onOpenInbox: () -> Unit) {
    val spoken = if (count > 0) "Inbox, $count ${if (count == 1) "needs" else "need"} you" else "Inbox"
    Tooltip("Inbox") {
        IconButton(onClick = onOpenInbox, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = spoken }.testTag("drawer_inbox")) {
            BadgedBox(badge = {
                if (count > 0) {
                    Badge(containerColor = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary) {
                        Text("$count", modifier = Modifier.testTag("inbox_badge"))
                    }
                }
            }) { Icon(AppIcons.Inbox, contentDescription = null) }
        }
    }
}

/**
 * Shows or hides the archived sessions. Its name stays "Show archived" and its state says on or off (with how many are hidden while off),
 * so that the name never says the opposite of the state. While they are hidden it carries the count; while shown it is filled.
 */
@Composable
private fun ArchivedAction(showing: Boolean, hidden: Int, onToggle: () -> Unit) {
    Tooltip("Show archived") {
        IconToggleButton(
            checked = showing,
            onCheckedChange = { onToggle() },
            modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = "Show archived"
                stateDescription = if (showing) "On" else "Off, $hidden hidden"
            }.testTag("archived_toggle"),
            colors = IconButtonDefaults.iconToggleButtonColors(
                checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            BadgedBox(badge = {
                if (!showing) {
                    Badge(containerColor = MaterialTheme.colorScheme.secondary, contentColor = MaterialTheme.colorScheme.onSecondary) {
                        Text("$hidden", modifier = Modifier.testTag("archived_count"))
                    }
                }
            }) { Icon(AppIcons.Archive, contentDescription = null) }
        }
    }
}

/** The host's name and how the connection stands, as one button to the hosts sheet. */
@Composable
private fun HostButton(host: HostRef?, connection: ConnState?, onOpenHost: () -> Unit) {
    val state = when {
        host == null -> "Not connected"
        connection != null -> describe(connection)
        else -> ""
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(onClickLabel = "Show hosts", role = Role.Button, onClick = onOpenHost)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {}
            .testTag("drawer_host"),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Host, contentDescription = null)
        Column(Modifier.weight(1f)) {
            Text(host?.name ?: "Hosts", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("drawer_host_name"))
            Text(state, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("connection_status"))
        }
        Icon(AppIcons.ChevronDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SessionList(
    model: SessionListModel,
    connection: ConnState?,
    stale: Boolean,
    selected: String?,
    query: String,
    onQueryChange: (String) -> Unit,
    listState: LazyListState,
    watched: Set<String>,
    onOpen: (String) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(state = listState, modifier = modifier.fillMaxWidth().testTag("session_list")) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Sessions", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f).semantics { heading() })
                if (stale) UpdatingChip(Modifier.padding(end = 12.dp))
            }
        }
        if (model.summary.isNotEmpty()) {
            item(key = "summary") {
                Text(
                    model.summary,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (model.needsInput > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp).testTag("summary"),
                )
            }
        }
        if (model.total > 0) {
            item(key = "search") { SearchField(query, onQueryChange, Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
        }
        if (model.rows.isEmpty()) {
            item(key = "empty") { EmptyList(model, connection) }
        } else {
            items(model.rows, key = { it.resource }) { row -> SessionRowItem(row, row.resource == selected, row.resource in watched, onOpen) }
        }
        item(key = "end") { Spacer(Modifier.height(8.dp)) }
    }
}

/** A rounded field that narrows the list as you type. Its trailing button clears it, and the keyboard's action just closes the keyboard. */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    val container = MaterialTheme.colorScheme.surfaceContainerHighest
    TextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text("Search") },
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
private fun EmptyList(model: SessionListModel, connection: ConnState?) {
    val tag = Modifier.testTag("empty_list").padding(horizontal = 16.dp, vertical = 12.dp)
    val text = when {
        model.query.isNotEmpty() -> "No sessions match \"${model.query}\"."
        model.total > 0 -> "All ${model.total} are archived."
        connection == ConnState.Online -> "No sessions yet."
        connection is ConnState.Failed -> null
        else -> "Loading sessions\u2026"
    }
    if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = tag)
}

/** One session on one or two lines. Its spoken description joins every part, so TalkBack reads the title, state, changes and age as a single item. */
@Composable
private fun SessionRowItem(row: SessionRow, selected: Boolean, watched: Boolean, onOpen: (String) -> Unit) {
    val description = listOfNotNull(
        row.title,
        row.state.label,
        row.subtitle,
        row.branch?.let { "branch $it" },
        row.changes?.spoken,
        row.whenText.ifEmpty { null },
        if (row.isRead) null else "unread",
        if (row.isArchived) "archived" else null,
        if (watched) "alert when done is on" else null,
    ).joinToString(", ")
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClickLabel = "Open session", role = Role.Button) { onOpen(row.resource) }
            .heightIn(min = 52.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (selected) stateDescription = "Open"
            }
            .testTag("session_row"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StateMark(row.state)
        Column(Modifier.weight(1f)) {
            Text(
                row.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (row.isRead) FontWeight.Normal else FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val second = row.subtitle
            val changes = row.changes
            val branch = row.branch
            if (second != null || changes != null || branch != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    second?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                    // The branch is host text and can be long; it shortens before the change count does.
                    branch?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = if (second != null) 6.dp else 0.dp).weight(1f, fill = false).testTag("row_branch"))
                    }
                    changes?.let {
                        Text(it.text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(start = if (second != null || branch != null) 6.dp else 0.dp).testTag("row_changes"))
                    }
                }
            }
        }
        if (watched) {
            Icon(AppIcons.Bell, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp).testTag("row_alert"))
        }
        if (row.whenText.isNotEmpty()) {
            Text(row.whenText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * What a session is doing at a glance: a spinner while it works, a lock while it waits on you, an alert on an error, a quiet dot at
 * rest. Decorative: the row's spoken description carries the state in words.
 */
@Composable
internal fun StateMark(state: ActivityState, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.size(18.dp).testTag("state_mark").clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        when (state) {
            ActivityState.IN_PROGRESS -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.primary)
            ActivityState.NEEDS_INPUT -> Icon(AppIcons.Lock, contentDescription = null, tint = colors.tertiary, modifier = Modifier.size(16.dp))
            ActivityState.ERROR -> Icon(AppIcons.ErrorCircle, contentDescription = null, tint = colors.error, modifier = Modifier.size(16.dp))
            ActivityState.IDLE -> Box(Modifier.size(8.dp).clip(CircleShape).background(colors.outlineVariant))
        }
    }
}