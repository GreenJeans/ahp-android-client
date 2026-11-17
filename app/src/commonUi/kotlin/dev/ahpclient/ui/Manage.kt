package dev.ahpclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.AgentChoice
import dev.ahpclient.state.ConfigCompletions
import dev.ahpclient.state.ConfigOffer
import dev.ahpclient.state.ConfigPick
import dev.ahpclient.state.ChatRow
import dev.ahpclient.state.ManageMessages
import dev.ahpclient.state.ManageResult
import dev.ahpclient.state.McpServerControlAction
import dev.ahpclient.state.ModelOption
import dev.ahpclient.state.SessionDetail
import dev.ahpclient.state.SessionManager
import dev.ahpclient.state.SessionPhase
import kotlinx.coroutines.launch

/** An agent the host offers for a new session: its id and the name to show. Both come from the host and are shown as plain text. */
@Immutable
data class AgentOption(val provider: String, val name: String)

/** One connected host that can start a new session when multiple hosts are connected. */
@Stable
class HostManageTarget(
    val hostId: String,
    val hostName: String,
    val agents: List<AgentOption>,
    val folders: List<String>,
    val newSession: suspend (provider: String, folder: String?, picks: Map<String, ConfigPick>) -> ManageResult,
    val sessionSettings: suspend (provider: String, folder: String?, picks: Map<String, ConfigPick>) -> ConfigOffer = { _, _, _ -> ConfigOffer.NoSettings },
    val settingCompletions: SettingCompletions = { _, _, _, _, _ -> ConfigCompletions.SeedsOnly },
    /** The models the host offers for an agent harness, for the new session's first message. */
    val models: (provider: String) -> List<ModelOption> = { emptyList() },
    /** The custom agents the host offers before a session exists (its own customizations), for the first message. */
    val customAgents: List<AgentChoice> = emptyList(),
)

/** Asks the host for values matching a query for one setting of a new session: agent, folder, choices so far, setting id, query. */
typealias SettingCompletions = suspend (provider: String, folder: String?, picks: Map<String, ConfigPick>, property: String, query: String) -> ConfigCompletions

/**
 * What a screen can ask for to look after sessions, once the person has allowed it and the connection is on the manage
 * tier. A screen with none of this shows none of the controls below: nothing to disable, nothing to tap by accident.
 * Starting and removing go through the phone's device check behind these calls; the screen cannot skip it.
 */
@Stable
class ManageActionsUi(
    val agents: List<AgentOption>,
    val folders: List<String>,
    val setRead: suspend (sessionUri: String, read: Boolean) -> ManageResult,
    val setArchived: suspend (sessionUri: String, archived: Boolean) -> ManageResult,
    val newChat: suspend (sessionUri: String) -> ManageResult,
    /** A null folder starts a session with no working folder named; the host chooses where the agent works. */
    val newSession: suspend (provider: String, folder: String?, picks: Map<String, ConfigPick>) -> ManageResult,
    val disposeSession: suspend (sessionUri: String) -> ManageResult,
    val disposeChat: suspend (sessionUri: String, chatUri: String) -> ManageResult,
    /** Asks the host which settings it offers for a new session of this agent in this folder, given the choices so far. */
    val sessionSettings: suspend (provider: String, folder: String?, picks: Map<String, ConfigPick>) -> ConfigOffer = { _, _, _ -> ConfigOffer.NoSettings },
    /** Asks the host for values for a setting it wants completed as you type. */
    val settingCompletions: SettingCompletions = { _, _, _, _, _ -> ConfigCompletions.SeedsOnly },
    /** The models the host offers for an agent harness, for the new session's first message. */
    val models: (provider: String) -> List<ModelOption> = { emptyList() },
    /** The custom agents the host offers before a session exists (its own customizations), for the first message. */
    val customAgents: List<AgentChoice> = emptyList(),
    /** Connected hosts that can start a new session; when more than one is present, the new-session page offers a host picker. */
    val hosts: List<HostManageTarget> = emptyList(),
    val setTitle: suspend (sessionUri: String, title: String) -> ManageResult = { _, _ -> ManageResult.NotAllowed },
    val setChatRead: suspend (sessionUri: String, chatUri: String, read: Boolean) -> ManageResult = { _, _, _ -> ManageResult.NotAllowed },
    val setChatArchived: suspend (sessionUri: String, chatUri: String, archived: Boolean) -> ManageResult = { _, _, _ -> ManageResult.NotAllowed },
    val controlMcpServer: suspend (sessionUri: String, serverId: String, control: McpServerControlAction) -> ManageResult = { _, _, _ -> ManageResult.NotAllowed },
    val toggleCustomization: suspend (sessionUri: String, customizationId: String, enabled: Boolean) -> ManageResult = { _, _, _ -> ManageResult.NotAllowed },
    val addWorkingDirectory: suspend (sessionUri: String, directory: String) -> ManageResult = { _, _ -> ManageResult.NotAllowed },
    val removeWorkingDirectory: suspend (sessionUri: String, directory: String) -> ManageResult = { _, _ -> ManageResult.NotAllowed },
    /** The host's words once it has refused to add a folder on this connection: "Add folder" is then hidden and this is shown instead. */
    val addFolderRefusal: String? = null,
    /** The host's words once it has refused to remove a folder on this connection: the "Remove" buttons are then hidden and this is shown instead. */
    val removeFolderRefusal: String? = null,
)

@Composable
internal fun ManageNotice(text: String?, tag: String) {
    if (text == null) return
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * The second switch: let this app also look after sessions. It needs the first (acting for the user) and is disabled, with
 * the reason, while that is off. Turning it on is a decision in a dialog that says what it allows; turning it off needs none.
 */
@Composable
fun ManagementToggle(allowed: Boolean, available: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    var asking by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth().testTag("management_section")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(
                    value = allowed,
                    enabled = available,
                    role = Role.Switch,
                    onValueChange = { on -> if (on) asking = true else onChange(false) },
                )
                .testTag("management_toggle"),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Look after sessions", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = allowed, onCheckedChange = null, enabled = available)
        }
        Text(
            when {
                !available -> "Turn on \"Act for me\" first."
                allowed -> "On: mark read, archive, start and remove. Starting and removing ask for your fingerprint, face or lock."
                else -> "Off: no starting, archiving or removing."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("management_hint"),
        )
    }
    if (asking && available) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Look after sessions?") },
            text = {
                Text(
                    "This app can mark sessions read or unread, archive and restore them, start chats and sessions " +
                        "(which run an agent on your computer, in a folder you pick) and remove them, which cannot be undone. " +
                        "Starting and removing ask for your fingerprint, face or screen lock first. Turning off \"Act for me\", or signing out, turns this off too.",
                    modifier = Modifier.testTag("management_dialog_text"),
                )
            },
            confirmButton = { TextButton(onClick = { asking = false; onChange(true) }, modifier = Modifier.testTag("management_confirm")) { Text("Turn on") } },
            dismissButton = { TextButton(onClick = { asking = false }, modifier = Modifier.testTag("management_cancel")) { Text("Cancel") } },
        )
    }
}

/** The button that opens the new-session page: a tonal icon button, spoken "New session". */
@Composable
fun NewSessionIconButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalIconButton(onClick = onClick, modifier = modifier.testTag("new_session")) {
        Icon(AppIcons.Add, contentDescription = "New session")
    }
}

/**
 * The session's menu. Its first item shows the session's details, and the next, when [alerts] is given and the session is not archived,
 * says whether to be alerted when it finishes ("Alert when done"; checked when it is, and always when every session is). When the app
 * has been allowed to look after sessions it
 * also marks the session read or unread, archives or restores it, starts a chat (once it is ready) and removes the chat on
 * screen or the whole session. Archiving and removing ask first (archiving may take the host's working copy of the project
 * with it), and removing then asks for the device check. What happened is passed to [onNotice] in plain terms, for the
 * screen to show; a chat that was started is passed to [onChatCreated] so the screen can bring it forward.
 */
@Composable
fun SessionMenu(
    detail: SessionDetail,
    sessionUri: String,
    manage: ManageActionsUi?,
    shownChat: ChatRow?,
    onShowDetails: () -> Unit,
    onNotice: (String?) -> Unit,
    onChatCreated: (String) -> Unit,
    onRemoved: () -> Unit,
    modifier: Modifier = Modifier,
    alerts: FinishedAlertsUi? = null,
    customizationsCount: Int = 0,
    onShowCustomizations: (() -> Unit)? = null,
    showArchivedChats: Boolean = false,
    onToggleArchivedChats: (() -> Unit)? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    var confirmArchive by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var removingChat by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun run(call: suspend () -> ManageResult, then: (ManageResult) -> Unit = {}) {
        scope.launch {
            busy = true
            val result = call()
            onNotice(ManageMessages.describe(result))
            busy = false
            then(result)
        }
    }
    val manageable = manage != null && sessionUri.isNotEmpty()
    Box(modifier = modifier) {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("session_menu")) {
            Icon(AppIcons.MoreVert, contentDescription = "More options")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.testTag("session_menu_items")) {
            DropdownMenuItem(
                text = { Text("Details") },
                leadingIcon = { Icon(AppIcons.Info, contentDescription = null) },
                onClick = { open = false; onShowDetails() },
                modifier = Modifier.testTag("menu_details"),
            )
            if (onShowCustomizations != null) {
                DropdownMenuItem(
                    text = { Text(if (customizationsCount > 0) "Customizations ($customizationsCount)" else "Customizations") },
                    leadingIcon = { Icon(AppIcons.Plug, contentDescription = null) },
                    onClick = { open = false; onShowCustomizations() },
                    modifier = Modifier.testTag("menu_customizations"),
                )
            }
            if (alerts != null && sessionUri.isNotEmpty() && !detail.isArchived) {
                val everySession = alerts.watch.all
                val on = everySession || sessionUri in alerts.watch.sessions
                DropdownMenuItem(
                    text = { Text("Alert when done") },
                    leadingIcon = { Icon(AppIcons.Bell, contentDescription = null) },
                    trailingIcon = if (on) ({ Icon(AppIcons.Check, contentDescription = null) }) else null,
                    enabled = !everySession,
                    onClick = {
                        open = false
                        alerts.onSession(sessionUri, !on)
                        // The alert comes from the background service, so with it off a choice made here would never be heard.
                        if (!on && !alerts.monitoring) onNotice("Alerts need Monitor in background. Turn it on in Settings.")
                    },
                    modifier = Modifier.semantics { stateDescription = if (everySession) "On for every session" else if (on) "On" else "Off" }.testTag("toggle_alert"),
                )
            }
            if (onToggleArchivedChats != null && (detail.archivedChatCount > 0 || showArchivedChats)) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (detail.archivedChatCount > 0 && !showArchivedChats) {
                                "Show archived chats (${detail.archivedChatCount})"
                            } else {
                                "Show archived chats"
                            },
                        )
                    },
                    leadingIcon = { Icon(AppIcons.Archive, contentDescription = null) },
                    trailingIcon = if (showArchivedChats) ({ Icon(AppIcons.Check, contentDescription = null) }) else null,
                    onClick = { open = false; onToggleArchivedChats() },
                    modifier = Modifier.semantics { stateDescription = if (showArchivedChats) "On" else "Off" }.testTag("toggle_show_archived_chats"),
                )
            }
            if (manage != null && manageable) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Rename session…") },
                    leadingIcon = { Icon(AppIcons.Edit, contentDescription = null) },
                    enabled = !busy,
                    onClick = { open = false; renaming = true },
                    modifier = Modifier.testTag("rename_session"),
                )
                DropdownMenuItem(
                    text = { Text(if (detail.isRead) "Mark unread" else "Mark read") },
                    leadingIcon = { Icon(AppIcons.Mail, contentDescription = null) },
                    enabled = !busy,
                    onClick = { open = false; run({ manage.setRead(sessionUri, !detail.isRead) }) },
                    modifier = Modifier.testTag("toggle_read"),
                )
                DropdownMenuItem(
                    text = { Text(if (detail.isArchived) "Restore" else "Archive") },
                    leadingIcon = { Icon(if (detail.isArchived) AppIcons.Refresh else AppIcons.Archive, contentDescription = null) },
                    enabled = !busy,
                    onClick = { open = false; if (detail.isArchived) run({ manage.setArchived(sessionUri, false) }) else confirmArchive = true },
                    modifier = Modifier.testTag("toggle_archive"),
                )
                DropdownMenuItem(
                    text = { Text("New chat") },
                    leadingIcon = { Icon(AppIcons.Add, contentDescription = null) },
                    enabled = !busy && detail.phase == SessionPhase.READY,
                    onClick = { open = false; run({ manage.newChat(sessionUri) }) { result -> (result as? ManageResult.Done)?.created?.let(onChatCreated) } },
                    modifier = Modifier.testTag("new_chat"),
                )
                if (detail.chats.size > 1 && shownChat != null) {
                    DropdownMenuItem(
                        text = { Text(if (shownChat.isRead) "Mark chat unread" else "Mark chat read") },
                        leadingIcon = { Icon(AppIcons.Mail, contentDescription = null) },
                        enabled = !busy,
                        onClick = { open = false; run({ manage.setChatRead(sessionUri, shownChat.uri, !shownChat.isRead) }) },
                        modifier = Modifier.testTag("toggle_chat_read"),
                    )
                    if (shownChat.removable) {
                        DropdownMenuItem(
                            text = { Text(if (shownChat.isArchived) "Restore chat" else "Archive chat") },
                            leadingIcon = { Icon(if (shownChat.isArchived) AppIcons.Refresh else AppIcons.Archive, contentDescription = null) },
                            enabled = !busy,
                            onClick = { open = false; run({ manage.setChatArchived(sessionUri, shownChat.uri, !shownChat.isArchived) }) },
                            modifier = Modifier.testTag("toggle_chat_archive"),
                        )
                    }
                }
                val destructiveColors = MenuDefaults.itemColors(
                    textColor = MaterialTheme.colorScheme.error,
                    leadingIconColor = MaterialTheme.colorScheme.error,
                )
                if (shownChat != null && shownChat.removable) {
                    DropdownMenuItem(
                        text = { Text("Remove this chat…") },
                        leadingIcon = { Icon(AppIcons.Trash, contentDescription = null) },
                        colors = destructiveColors,
                        enabled = !busy,
                        onClick = { open = false; removingChat = shownChat.uri },
                        modifier = Modifier.testTag("remove_chat"),
                    )
                }
                DropdownMenuItem(
                    text = { Text("Remove session…") },
                    leadingIcon = { Icon(AppIcons.Trash, contentDescription = null) },
                    colors = destructiveColors,
                    enabled = !busy,
                    onClick = { open = false; confirmRemove = true },
                    modifier = Modifier.testTag("remove_session"),
                )
            }
        }
    }
    if (renaming && manage != null) {
        RenameSessionDialog(
            initialTitle = detail.title,
            onDismiss = { renaming = false },
            onSave = { newTitle ->
                renaming = false
                run({ manage.setTitle(sessionUri, newTitle) })
            },
        )
    }
    if (confirmArchive && manage != null) {
        AlertDialog(
            onDismissRequest = { confirmArchive = false },
            title = { Text("Archive this session?") },
            text = {
                Text(
                    "\"${detail.title.take(MAX_SHOWN)}\" moves to the archive. If it has its own working copy on your computer (a git worktree), " +
                        "the host may remove that folder and keep any unsaved changes as a commit on its branch. Restoring the session later does " +
                        "not bring the folder back.",
                    modifier = Modifier.testTag("archive_dialog_text"),
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmArchive = false; run({ manage.setArchived(sessionUri, true) }) }, modifier = Modifier.testTag("archive_confirm")) { Text("Archive") }
            },
            dismissButton = { TextButton(onClick = { confirmArchive = false }, modifier = Modifier.testTag("archive_cancel")) { Text("Keep it") } },
        )
    }
    if (confirmRemove && manage != null) {
        ConfirmRemoveDialog(
            title = "Remove this session?",
            text = "\"${detail.title.take(MAX_SHOWN)}\" and all its chats will be removed from the host. This cannot be undone. Archive it instead if you may want it back. You will be asked for your fingerprint, face or screen lock.",
            onConfirm = {
                confirmRemove = false
                run({ manage.disposeSession(sessionUri) }) { if (it is ManageResult.Done) onRemoved() }
            },
            onDismiss = { confirmRemove = false },
        )
    }
    val chatToRemove = removingChat?.let { uri -> detail.chats.firstOrNull { it.uri == uri } }
    if (chatToRemove != null && manage != null) {
        ConfirmRemoveDialog(
            title = "Remove this chat?",
            text = "\"${chatToRemove.title.take(MAX_SHOWN)}\" will be removed from the session. This cannot be undone. You will be asked for your fingerprint, face or screen lock.",
            onConfirm = {
                removingChat = null
                run({ manage.disposeChat(sessionUri, chatToRemove.uri) })
            },
            onDismiss = { removingChat = null },
        )
    }
}

@Composable
private fun ConfirmRemoveDialog(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text, modifier = Modifier.testTag("remove_dialog_text")) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("remove_confirm")) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("remove_cancel")) { Text("Keep it") } },
    )
}

/** The host's own words (agent names, folder paths, titles) are untrusted display text: shown as plain text and cut to a sane length. */
private const val MAX_SHOWN = 120

@Composable
private fun RenameSessionDialog(initialTitle: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var title by rememberSaveable { mutableStateOf(initialTitle.take(SessionManager.MAX_TITLE_LENGTH)) }
    val trimmed = title.trim()
    val valid = trimmed.isNotEmpty() && trimmed.length <= SessionManager.MAX_TITLE_LENGTH
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename session") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { if (it.length <= SessionManager.MAX_TITLE_LENGTH) title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("rename_session_input"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (valid) onSave(trimmed) },
                enabled = valid,
                modifier = Modifier.testTag("rename_session_confirm"),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("rename_session_cancel")) { Text("Cancel") }
        },
        modifier = Modifier.testTag("rename_session_dialog"),
    )
}
