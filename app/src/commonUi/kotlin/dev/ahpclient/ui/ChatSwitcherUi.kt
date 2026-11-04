package dev.ahpclient.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.ChatSwitcher
import dev.ahpclient.state.SwitcherGroup
import dev.ahpclient.state.SwitcherRow

/**
 * The way between a session's chats: the main chat, one tap away, and a single control for every other chat ("2 other
 * chats") that opens [ChatSwitcherSheet]. When one of the others is on screen the control carries its title, so it is clear
 * which chat is shown. A session with only its main chat needs neither, so nothing is drawn; one whose other chats are all
 * hidden still gets the control ("1 hidden chat"), so they can be reached. The main chat's chip carries a dot when it is
 * waiting on the person or unread; the other-chats control carries one only when some other chat is waiting on the person
 * (unread shows on the rows of the sheet). Each spoken description says what its dot means, so colour is never the only cue.
 */
@Composable
internal fun ChatSwitcherBar(switcher: ChatSwitcher, onSelect: (String) -> Unit, onOpen: () -> Unit) {
    val main = switcher.main ?: return
    if (!switcher.hasOthers) return
    val shownOther = switcher.others.firstOrNull { it.shown }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("chat_switcher"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = main.shown,
            onClick = { onSelect(main.uri) },
            label = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (main.marked) Dot(main.needsYou, "chat_switcher_main_dot")
                    Text(main.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
                }
            },
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = main.spoken }.testTag("chat_switcher_main"),
        )
        FilterChip(
            selected = shownOther != null,
            onClick = onOpen,
            label = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (switcher.marked) Dot(needsYou = true, "chat_switcher_dot")
                    if (shownOther != null) {
                        Text(shownOther.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Text("· ${switcher.label}", maxLines = 1)
                    } else {
                        Text(switcher.label, maxLines = 1)
                    }
                }
            },
            trailingIcon = { Icon(AppIcons.ChevronDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
            modifier = Modifier
                .weight(1f, fill = false)
                .heightIn(min = 48.dp)
                .semantics { contentDescription = listOfNotNull(switcher.spoken, shownOther?.let { "showing ${it.title}" }).joinToString(", ") }
                .testTag("chat_switcher_open"),
        )
    }
}

/**
 * Every chat of the session, from the bottom of the screen: the main chat first, then the groups of [ChatSwitcher]. A chat
 * sits under the one it came from; the agent's own chats start folded, and their heading still says how many there are and
 * how many are unread. Tapping a chat shows it, the same as the tabs did. When the host marks some chats hidden, a toggle at
 * the end ("Show hidden chats (2)") lists them in a group of their own; [showHidden] is held by the caller, which builds
 * [switcher] with it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatSwitcherSheet(
    switcher: ChatSwitcher,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    showHidden: Boolean = false,
    onToggleHidden: () -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        // The handle is itself a control (it expands and collapses the sheet), so it is drawn wide enough to be a 48 dp target.
        dragHandle = { BottomSheetDefaults.DragHandle(width = 48.dp) },
        // The sheet reaches under the navigation bar; the content below keeps clear of it, so nothing sits behind the gesture bar.
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        modifier = Modifier.testTag("chat_switcher_sheet"),
    ) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            Text("Chats", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp).semantics { heading() })
            switcher.main?.let { SwitcherRowItem(it, onSelect) }
            switcher.groups.forEach { group -> SwitcherGroupItems(group, onSelect) }
            if (switcher.hiddenCount > 0) ShowHiddenToggle(switcher.hiddenCount, showHidden, onToggleHidden)
        }
    }
}

/** Lists or leaves out the chats the host marks hidden. Its name says how many are left out while off; its state says on or off. */
@Composable
private fun ShowHiddenToggle(count: Int, on: Boolean, onToggle: () -> Unit) {
    FilterChip(
        selected = on,
        onClick = onToggle,
        label = { Text(if (on) "Show hidden chats" else "Show hidden chats ($count)") },
        leadingIcon = if (on) ({ Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }) else null,
        modifier = Modifier
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .heightIn(min = 48.dp)
            .semantics { stateDescription = if (on) "On" else "Off" }
            .testTag("chat_switcher_show_hidden"),
    )
}

@Composable
private fun SwitcherGroupItems(group: SwitcherGroup, onSelect: (String) -> Unit) {
    var expanded by rememberSaveable(group.kind) { mutableStateOf(!group.collapsedByDefault) }
    val headingModifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    if (group.collapsedByDefault) {
        Row(
            modifier = headingModifier
                .clickable(onClickLabel = if (expanded) "Fold" else "Show") { expanded = !expanded }
                .semantics {
                    heading()
                    contentDescription = "${group.title}, ${group.summary}"
                    stateDescription = if (expanded) "Shown" else "Folded"
                }
                .padding(horizontal = 24.dp)
                .testTag("chat_switcher_group_toggle"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(group.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("chat_switcher_group"))
                Text(group.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(AppIcons.ChevronDown, contentDescription = null, modifier = if (expanded) Modifier.rotate(180f) else Modifier)
        }
    } else {
        Row(modifier = headingModifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                group.title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() }.testTag("chat_switcher_group"),
            )
        }
    }
    if (expanded) group.rows.forEach { SwitcherRowItem(it, onSelect) }
}

@Composable
private fun SwitcherRowItem(row: SwitcherRow, onSelect: (String) -> Unit) {
    // Deeper levels stop indenting past a few, so a long chain of forks still leaves room for the title on a phone.
    val indent = 24.dp + 16.dp * row.depth.coerceAtMost(MAX_INDENT_LEVELS)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClickLabel = "Show this chat") { onSelect(row.uri) }
            .semantics {
                contentDescription = row.spoken
                selected = row.shown
            }
            .padding(start = indent, end = 24.dp, top = 6.dp, bottom = 6.dp)
            .testTag("chat_switcher_row"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.marked) Dot(row.needsYou, "chat_switcher_row_dot") else Spacer(Modifier.size(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (row.archived) "${row.title} (archived)" else row.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.detail.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (row.shown) Icon(AppIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

/** Waiting on the person is the stronger colour; unread is the quieter one. What it means is always said in words beside it. */
@Composable
private fun Dot(needsYou: Boolean, tag: String) {
    Surface(
        color = if (needsYou) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
        shape = CircleShape,
        modifier = Modifier.size(8.dp).testTag(tag).clearAndSetSemantics {},
    ) {}
}

private const val MAX_INDENT_LEVELS = 4
