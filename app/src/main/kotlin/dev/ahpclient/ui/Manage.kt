package dev.ahpclient.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.ConfigField
import dev.ahpclient.state.ConfigOffer
import dev.ahpclient.state.ConfigPick
import dev.ahpclient.state.ChatRow
import dev.ahpclient.state.ManageMessages
import dev.ahpclient.state.ManageResult
import dev.ahpclient.state.RecentFolders
import dev.ahpclient.state.SessionConfigForm
import dev.ahpclient.state.SessionDetail
import dev.ahpclient.state.SessionPhase
import dev.ahpclient.state.displayPath
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
)

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
    /** Connected hosts that can start a new session; when more than one is present, the dialog offers a host picker. */
    val hosts: List<HostManageTarget> = emptyList(),
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

/** The button that starts the new-session dialog: a tonal icon button, spoken "New session". [onCreated] gets the address of the session the host took. */
@Composable
fun NewSessionIconButton(
    manage: ManageActionsUi,
    onCreated: (String) -> Unit,
    modifier: Modifier = Modifier,
    onCreatedOnHost: ((hostId: String?, uri: String) -> Unit)? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    FilledTonalIconButton(onClick = { open = true }, modifier = modifier.testTag("new_session")) {
        Icon(AppIcons.Add, contentDescription = "New session")
    }
    if (open) {
        NewSessionDialog(
            manage = manage,
            onDismiss = { open = false },
            onCreated = { hostId, uri ->
                open = false
                if (onCreatedOnHost != null) onCreatedOnHost(hostId, uri) else onCreated(uri)
            },
        )
    }
}

/** Keeps the settings chosen for a new session across rotation: each setting's id, then its value as `t:text` or `f:true`/`f:false`. */
private val PicksSaver: Saver<Map<String, ConfigPick>, Any> = listSaver<Map<String, ConfigPick>, String>(
    save = { picks ->
        picks.flatMap { (id, pick) -> listOf(id, if (pick is ConfigPick.Flag) "f:${pick.value}" else "t:${(pick as ConfigPick.Text).value}") }
    },
    restore = { saved ->
        saved.chunked(2).filter { it.size == 2 }.mapNotNull { (id, raw) ->
            when {
                raw.startsWith("t:") -> id to ConfigPick.Text(raw.drop(2))
                raw == "f:true" -> id to ConfigPick.Flag(true)
                raw == "f:false" -> id to ConfigPick.Flag(false)
                else -> null
            }
        }.toMap()
    },
)

/**
 * Pick an agent the host offers and a folder it already uses, then the settings the host offers for that pair, then start.
 * Nothing the host did not offer can be picked, and nothing is sent until Start is tapped (which asks for the device check
 * first, naming the settings chosen). The host is asked again after each choice, because its answer can change. The host's
 * words are plain text.
 */
@Composable
private fun NewSessionDialog(manage: ManageActionsUi, onDismiss: () -> Unit, onCreated: (hostId: String?, uri: String) -> Unit) {
    val hostTargets = manage.hosts
    var selectedHostId by rememberSaveable { mutableStateOf(hostTargets.firstOrNull()?.hostId) }
    val activeTarget = hostTargets.firstOrNull { it.hostId == selectedHostId } ?: hostTargets.firstOrNull()
    val activeAgents = activeTarget?.agents ?: manage.agents
    val activeFolders = activeTarget?.folders ?: manage.folders
    val activeNewSession = activeTarget?.newSession ?: manage.newSession
    val activeSessionSettings = activeTarget?.sessionSettings ?: manage.sessionSettings

    var provider by rememberSaveable { mutableStateOf(activeAgents.singleOrNull()?.provider) }
    var folder by rememberSaveable { mutableStateOf(activeFolders.singleOrNull()) }
    // "No folder" is a choice of its own, since a null folder alone would read as nothing chosen yet. With no folders to pick it is the one choice, so it starts chosen.
    var noFolder by rememberSaveable { mutableStateOf(activeFolders.isEmpty()) }
    var picks by rememberSaveable(stateSaver = PicksSaver) { mutableStateOf<Map<String, ConfigPick>>(emptyMap()) }
    var form by remember { mutableStateOf<SessionConfigForm?>(null) }
    var settingsNote by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var showAllFolders by rememberSaveable { mutableStateOf(false) }
    val shownFolders = if (showAllFolders) activeFolders else activeFolders.take(RecentFolders.SHOWN)
    val scope = rememberCoroutineScope()

    // Asked again whenever the host, agent, folder or a choice changes: the host's list of settings can depend on all of them.
    LaunchedEffect(activeTarget?.hostId, provider, folder, noFolder, picks) {
        val chosenAgent = provider
        val chosenFolder = if (noFolder) null else folder
        if (chosenAgent == null || (!noFolder && chosenFolder == null)) {
            form = null
            settingsNote = null
            return@LaunchedEffect
        }
        loading = true
        when (val offer = activeSessionSettings(chosenAgent, chosenFolder, picks)) {
            is ConfigOffer.Form -> {
                form = offer.form
                settingsNote = null
                // A setting the host stopped offering after a choice is dropped, never sent.
                val kept = picks.filterKeys { id -> offer.form.fields.any { it.id == id } }
                if (kept != picks) picks = kept
            }
            ConfigOffer.NoSettings -> {
                form = null
                settingsNote = null
                if (picks.isNotEmpty()) picks = emptyMap()
            }
            is ConfigOffer.Unavailable -> settingsNote = offer.reason
        }
        loading = false
    }

    val missing = form?.missingRequired(picks).orEmpty()
    // With the host's list unknown and nothing chosen, starting with the host's own settings is still fine.
    val canStart = provider != null && (noFolder || folder != null) && !busy && !loading && missing.isEmpty() && (settingsNote == null || picks.isEmpty())
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Start a new session") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hostTargets.size > 1) {
                    Text("Host", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                    hostTargets.forEach { target ->
                        ChoiceRow(target.hostName.take(MAX_SHOWN), activeTarget?.hostId == target.hostId, "host_${target.hostId}") {
                            if (selectedHostId != target.hostId) {
                                selectedHostId = target.hostId
                                provider = target.agents.singleOrNull()?.provider
                                folder = target.folders.singleOrNull()
                                noFolder = target.folders.isEmpty()
                                showAllFolders = false
                                picks = emptyMap()
                                form = null
                                settingsNote = null
                                notice = null
                            }
                        }
                    }
                }
                Text("Agent", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                if (activeAgents.isEmpty()) Text("The host offers no agents.", modifier = Modifier.testTag("no_agents"))
                activeAgents.forEach { agent ->
                    ChoiceRow(agent.name.ifBlank { agent.provider }.take(MAX_SHOWN), provider == agent.provider, "agent_${agent.provider}") {
                        if (provider != agent.provider) {
                            provider = agent.provider
                            picks = emptyMap()
                            form = null
                            settingsNote = null
                        }
                    }
                }
                Text("Folder", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                if (activeFolders.isEmpty()) {
                    Text("The host has no folders to pick yet.", modifier = Modifier.testTag("no_folders"))
                }
                ChoiceRow("No folder (the host chooses)", noFolder, "folder_none") {
                    if (!noFolder) {
                        noFolder = true
                        folder = null
                        picks = emptyMap()
                        form = null
                        settingsNote = null
                    }
                }
                shownFolders.forEach { path ->
                    ChoiceRow(displayPath(path).take(MAX_SHOWN), !noFolder && folder == path, "folder_$path") {
                        if (noFolder || folder != path) {
                            noFolder = false
                            folder = path
                            picks = emptyMap()
                            form = null
                            settingsNote = null
                        }
                    }
                }
                if (shownFolders.size < activeFolders.size) {
                    TextButton(onClick = { showAllFolders = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("folders_show_all")) {
                        Text("Show ${activeFolders.size - shownFolders.size} more folders")
                    }
                }
                form?.let { current -> SettingsSection(current, picks, missing) { id, pick -> picks = picks + (id to pick) } }
                if (loading) Text("Asking the host for its settings…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("settings_loading"))
                settingsNote?.let { note ->
                    Text(
                        "Could not get this agent's settings. $note" + if (picks.isEmpty()) " You can still start; the host will use its own." else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("settings_note").semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                ManageNotice(notice, "new_session_notice")
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val chosenAgent = provider
                    val chosenFolder = if (noFolder) null else folder
                    if (chosenAgent != null && (noFolder || chosenFolder != null)) {
                        scope.launch {
                            busy = true
                            val result = activeNewSession(chosenAgent, chosenFolder, picks)
                            notice = ManageMessages.describe(result)
                            busy = false
                            if (result is ManageResult.Done) result.created?.let { onCreated(activeTarget?.hostId, it) }
                        }
                    }
                },
                enabled = canStart,
                modifier = Modifier.testTag("new_session_start"),
            ) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.testTag("new_session_cancel")) { Text("Cancel") } },
    )
}

/**
 * The settings the host offers for this agent and folder, each as a short list to pick from. What is selected is what the host
 * holds now (its own default until a choice is made). A setting this app cannot show is counted, and the host keeps its own value.
 */
@Composable
private fun SettingsSection(form: SessionConfigForm, picks: Map<String, ConfigPick>, missing: List<ConfigField>, onPick: (String, ConfigPick) -> Unit) {
    if (form.fields.isEmpty() && form.omitted == 0) return
    Text("Settings", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
    form.fields.forEach { field ->
        Text(
            field.title + if (field.required) " (required)" else "",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 4.dp).semantics { heading() }.testTag("setting_title_${field.id}"),
        )
        field.description?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("setting_description_${field.id}"))
        }
        val selected = picks[field.id] ?: field.value
        field.options.forEachIndexed { index, option ->
            ChoiceRow(option.label, selected == option.pick, "setting_${field.id}_$index") { onPick(field.id, option.pick) }
        }
    }
    if (missing.isNotEmpty()) {
        Text("Choose a value for: " + missing.joinToString { it.title }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("settings_missing"))
    }
    if (form.omitted > 0) {
        Text(
            "${form.omitted} more setting${if (form.omitted == 1) "" else "s"} this app cannot show; the host keeps its own.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("settings_omitted"),
        )
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, tag: String, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
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
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    var confirmArchive by rememberSaveable { mutableStateOf(false) }
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
            if (manage != null && manageable) {
                HorizontalDivider()
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
