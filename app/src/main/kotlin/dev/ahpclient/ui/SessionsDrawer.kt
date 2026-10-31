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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.microsoft.agenthostprotocol.generated.SessionSummary
import dev.ahpclient.app.HostRef
import dev.ahpclient.state.ActivityState
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.HostSessionEntry
import dev.ahpclient.state.SessionCatalog
import dev.ahpclient.state.SessionFilter
import dev.ahpclient.state.SessionListModel
import dev.ahpclient.state.SessionRow
import dev.ahpclient.state.label
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
    /** Multi-host session entries when connected to one or more hosts; falls back to [sessions] when empty. */
    entries: List<HostSessionEntry> = emptyList(),
    /** All currently connected hosts, so the header can show how many hosts are connected when more than one is active. */
    connectedHosts: List<HostRef> = emptyList(),
    /** Active filter, ordering and grouping choices for the sessions list. */
    filter: SessionFilter = SessionFilter.DEFAULT,
    onFilterChange: (SessionFilter) -> Unit = {},
    onOpenSessionOnHost: (hostId: String?, uri: String) -> Unit = { _, uri -> onOpenSession(uri) },
    onSessionCreatedOnHost: (hostId: String?, uri: String) -> Unit = { _, uri -> onSessionCreated(uri) },
) {
    val effectiveFilter = remember(filter, showArchived) { filter.copy(showArchived = showArchived) }
    val model = remember(entries, sessions, effectiveFilter, now, query) {
        if (entries.isNotEmpty()) {
            SessionListModel.fromEntries(SessionCatalog.sortedEntries(entries), effectiveFilter, now, query)
        } else {
            SessionListModel.from(SessionCatalog.sorted(sessions), showArchived, now, query, filter = effectiveFilter)
        }
    }
    var filterSheetOpen by rememberSaveable { mutableStateOf(false) }
    val applyFilter: (SessionFilter) -> Unit = { updated ->
        if (updated.showArchived != showArchived) onToggleArchived()
        onFilterChange(updated)
    }
    val resetFilter: () -> Unit = {
        if (showArchived) onToggleArchived()
        onFilterChange(SessionFilter.DEFAULT)
    }

    ModalDrawerSheet(modifier = modifier.testTag("drawer")) {
        Column(Modifier.fillMaxSize()) {
            HostButton(host, connectedHosts, connection, onOpenHost)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (inboxCount != null && host != null) InboxAction(inboxCount, onOpenInbox)
                if (host != null && (model.hiddenArchived > 0 || showArchived)) ArchivedAction(showArchived, model.hiddenArchived, onToggleArchived)
                Spacer(Modifier.weight(1f))
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
                SessionList(
                    model = model,
                    connection = connection,
                    stale = stale,
                    selected = selected,
                    query = query,
                    onQueryChange = onQueryChange,
                    onOpenFilter = { filterSheetOpen = true },
                    onResetFilter = resetFilter,
                    listState = listState,
                    manage = manage,
                    onSessionCreated = onSessionCreated,
                    onSessionCreatedOnHost = onSessionCreatedOnHost,
                    watched = watched,
                    onOpen = onOpenSessionOnHost,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    if (filterSheetOpen && host != null) {
        SessionFilterSheet(
            model = model,
            filter = effectiveFilter,
            onFilterChange = applyFilter,
            onReset = resetFilter,
            onDismiss = { filterSheetOpen = false },
        )
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
        CountOver(
            count = count.takeIf { it > 0 },
            tag = "inbox_badge",
            containerColor = MaterialTheme.colorScheme.tertiary,
            contentColor = MaterialTheme.colorScheme.onTertiary,
        ) {
            IconButton(onClick = onOpenInbox, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = spoken }.testTag("drawer_inbox")) {
                Icon(AppIcons.Inbox, contentDescription = null)
            }
        }
    }
}

/**
 * An icon button with a count on the corner of its icon. The badge is drawn over the button, not inside it: an icon button clips what
 * it holds to its round shape, which cut a three-digit count to two ("30" for 305). The count is not read out on its own, since the
 * button's own words already say it.
 */
@Composable
private fun CountOver(count: Int?, tag: String, containerColor: Color, contentColor: Color, button: @Composable () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        button()
        if (count != null) {
            // Stands where the button's 24 dp icon is, so the badge sits where it did inside the button.
            BadgedBox(badge = {
                Badge(containerColor = containerColor, contentColor = contentColor) {
                    Text("$count", modifier = Modifier.testTag(tag).semantics { hideFromAccessibility() })
                }
            }) { Spacer(Modifier.size(30.dp)) }
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
        CountOver(
            count = hidden.takeIf { !showing },
            tag = "archived_count",
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary,
        ) {
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
            ) { Icon(AppIcons.Archive, contentDescription = null) }
        }
    }
}

/** Opens the session filter sheet, carrying a badge when any non-default filter, ordering or grouping is active. */
@Composable
private fun FilterButton(activeCount: Int, onOpenFilter: () -> Unit) {
    val active = activeCount > 0
    val spoken = if (active) "Filter sessions, $activeCount active" else "Filter sessions"
    Tooltip("Filter sessions") {
        CountOver(
            count = activeCount.takeIf { it > 0 },
            tag = "session_filter_badge",
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            IconToggleButton(
                checked = active,
                onCheckedChange = { onOpenFilter() },
                modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = spoken
                    stateDescription = if (active) "$activeCount active" else "Default"
                }.testTag("session_filter"),
                colors = IconButtonDefaults.iconToggleButtonColors(
                    checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Icon(AppIcons.Filter, contentDescription = null)
            }
        }
    }
}

/** The host's name and how the connection stands, as one button to the hosts sheet. */
@Composable
private fun HostButton(host: HostRef?, connectedHosts: List<HostRef>, connection: ConnState?, onOpenHost: () -> Unit) {
    val extraHosts = (connectedHosts.size - 1).coerceAtLeast(0)
    val title = when {
        host == null -> "Hosts"
        extraHosts > 0 -> "${host.name} + $extraHosts more"
        else -> host.name
    }
    val state = when {
        host == null -> "Not connected"
        extraHosts > 0 && connection != null -> "${describe(connection)} · ${connectedHosts.size} hosts connected"
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
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("drawer_host_name"))
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
    onOpenFilter: () -> Unit,
    onResetFilter: () -> Unit,
    listState: LazyListState,
    manage: ManageActionsUi?,
    onSessionCreated: (String) -> Unit,
    onSessionCreatedOnHost: (hostId: String?, uri: String) -> Unit,
    watched: Set<String>,
    onOpen: (hostId: String?, uri: String) -> Unit,
    modifier: Modifier,
) {
    val showSearch = model.total > 0 || !model.filter.isDefault
    LazyColumn(state = listState, modifier = modifier.fillMaxWidth().testTag("session_list")) {
        if (stale) {
            item(key = "stale") {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UpdatingChip()
                }
            }
        }
        if (showSearch || manage != null) {
            item(key = "search") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showSearch) {
                        SearchField(query, onQueryChange, Modifier.weight(1f))
                        FilterButton(model.filter.activeCount, onOpenFilter)
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (manage != null) {
                        Tooltip("New session") {
                            NewSessionIconButton(manage, onSessionCreated, onCreatedOnHost = onSessionCreatedOnHost)
                        }
                    }
                }
            }
        }
        if (model.rows.isEmpty()) {
            item(key = "empty") { EmptyList(model, connection, onResetFilter) }
        } else {
            model.groups.forEach { group ->
                val groupTitle = group.title
                if (groupTitle != null) {
                    item(key = "group_header_${group.key}") {
                        Text(
                            groupTitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)
                                .semantics { heading() }
                                .testTag("group_header"),
                        )
                    }
                }
                items(group.rows, key = { "${group.key}:${it.key}" }) { row ->
                    SessionRowItem(
                        row = row,
                        selected = row.resource == selected,
                        watched = row.resource in watched,
                        onOpen = { onOpen(row.hostId, row.resource) },
                    )
                }
            }
        }
        item(key = "end") { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
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
private fun EmptyList(model: SessionListModel, connection: ConnState?, onResetFilter: () -> Unit) {
    val tag = Modifier.testTag("empty_list").padding(horizontal = 16.dp, vertical = 12.dp)
    if (model.query.isEmpty() && model.hiddenByFilter > 0) {
        Column(modifier = tag, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "No sessions match the current filters (${model.hiddenByFilter} hidden).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = onResetFilter,
                modifier = Modifier.heightIn(min = 48.dp).testTag("reset_filters_empty"),
            ) {
                Text("Reset filters")
            }
        }
        return
    }
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
private fun SessionRowItem(row: SessionRow, selected: Boolean, watched: Boolean, onOpen: () -> Unit) {
    val description = listOfNotNull(
        row.title,
        row.state.label,
        row.hostName?.let { "host $it" },
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
            .clickable(onClickLabel = "Open session", role = Role.Button, onClick = onOpen)
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
        StateMark(row.state, unread = !row.isRead)
        Column(Modifier.weight(1f)) {
            Text(
                row.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (row.isRead) FontWeight.Normal else FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val hostName = row.hostName
            val second = row.subtitle
            val changes = row.changes
            val branch = row.branch
            val inlineBranch = branch.takeIf { second == null }
            val separateBranch = branch.takeIf { second != null }
            hostName?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.testTag("row_host"),
                )
            }
            if (second != null || changes != null || inlineBranch != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    second?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    inlineBranch?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                            modifier = Modifier.weight(1f, fill = false).testTag("row_branch"),
                        )
                    }
                    changes?.let {
                        Text(
                            it.text,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.padding(start = if (second != null || inlineBranch != null) 6.dp else 0.dp).testTag("row_changes"),
                        )
                    }
                }
            }
            separateBranch?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.testTag("row_branch"),
                )
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
 * rest (or an accent dot when unread). Decorative: the row's spoken description carries the state in words.
 */
@Composable
internal fun StateMark(state: ActivityState, modifier: Modifier = Modifier, unread: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.size(18.dp).testTag("state_mark").clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        when (state) {
            ActivityState.IN_PROGRESS -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.primary)
            ActivityState.NEEDS_INPUT -> Icon(AppIcons.Lock, contentDescription = null, tint = colors.tertiary, modifier = Modifier.size(16.dp))
            ActivityState.ERROR -> Icon(AppIcons.ErrorCircle, contentDescription = null, tint = colors.error, modifier = Modifier.size(16.dp))
            ActivityState.IDLE -> Box(
                Modifier
                    .size(if (unread) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(if (unread) colors.primary else colors.outlineVariant),
            )
        }
    }
}
