package dev.ahpclient.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.ConfigField
import dev.ahpclient.state.ConfigOffer
import dev.ahpclient.state.ConfigPick
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
    val newSession: suspend (provider: String, folder: String, picks: Map<String, ConfigPick>) -> ManageResult,
    val disposeSession: suspend (sessionUri: String) -> ManageResult,
    val disposeChat: suspend (sessionUri: String, chatUri: String) -> ManageResult,
    /** Asks the host which settings it offers for a new session of this agent in this folder, given the choices so far. */
    val sessionSettings: suspend (provider: String, folder: String, picks: Map<String, ConfigPick>) -> ConfigOffer = { _, _, _ -> ConfigOffer.NoSettings },
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
            Text("Let this app look after sessions", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = allowed, onCheckedChange = null, enabled = available)
        }
        Text(
            when {
                !available -> "Not available: turn on \"Let this app act for me\" first."
                allowed -> "On: this app can mark sessions read, archive them, start chats and sessions, and remove them. Starting and removing ask for your fingerprint, face or screen lock first."
                else -> "Off: this app cannot start, archive or remove anything."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("management_hint"),
        )
    }
    if (asking && available) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("Let this app look after sessions?") },
            text = {
                Text(
                    "With this on, this app can mark your sessions read or unread, archive and restore them, start new chats, start new sessions " +
                        "(which runs an agent on your computer, in a folder you pick) and remove sessions and chats, which cannot be undone. " +
                        "Starting and removing ask for your fingerprint, face or screen lock first. Turning off \"act for me\", or signing out, turns this off too.",
                    modifier = Modifier.testTag("management_dialog_text"),
                )
            },
            confirmButton = { TextButton(onClick = { asking = false; onChange(true) }, modifier = Modifier.testTag("management_confirm")) { Text("Turn on") } },
            dismissButton = { TextButton(onClick = { asking = false }, modifier = Modifier.testTag("management_cancel")) { Text("Cancel") } },
        )
    }
}

/** The button on the session list that starts the new-session dialog. [onCreated] gets the address of the session the host took. */
@Composable
fun NewSessionButton(manage: ManageActionsUi, onCreated: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    Button(onClick = { open = true }, modifier = modifier.heightIn(min = 48.dp).testTag("new_session")) { Text("New session") }
    if (open) NewSessionDialog(manage, onDismiss = { open = false }, onCreated = { open = false; onCreated(it) })
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
private fun NewSessionDialog(manage: ManageActionsUi, onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    var provider by rememberSaveable { mutableStateOf(manage.agents.singleOrNull()?.provider) }
    var folder by rememberSaveable { mutableStateOf(manage.folders.singleOrNull()) }
    var picks by rememberSaveable(stateSaver = PicksSaver) { mutableStateOf<Map<String, ConfigPick>>(emptyMap()) }
    var form by remember { mutableStateOf<SessionConfigForm?>(null) }
    var settingsNote by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var showAllFolders by rememberSaveable { mutableStateOf(false) }
    val shownFolders = if (showAllFolders) manage.folders else manage.folders.take(RecentFolders.SHOWN)
    val scope = rememberCoroutineScope()

    // Asked again whenever the agent, folder or a choice changes: the host's list of settings can depend on all three.
    LaunchedEffect(provider, folder, picks) {
        val chosenAgent = provider
        val chosenFolder = folder
        if (chosenAgent == null || chosenFolder == null) {
            form = null
            settingsNote = null
            return@LaunchedEffect
        }
        loading = true
        when (val offer = manage.sessionSettings(chosenAgent, chosenFolder, picks)) {
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
    val canStart = provider != null && folder != null && !busy && !loading && missing.isEmpty() && (settingsNote == null || picks.isEmpty())
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Start a new session") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Agent", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                if (manage.agents.isEmpty()) Text("The host offers no agents.", modifier = Modifier.testTag("no_agents"))
                manage.agents.forEach { agent ->
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
                if (manage.folders.isEmpty()) {
                    Text("No folders yet. Start a session in VS Code first; this app only offers folders your sessions already use.", modifier = Modifier.testTag("no_folders"))
                }
                shownFolders.forEach { path ->
                    ChoiceRow(displayPath(path).take(MAX_SHOWN), folder == path, "folder_$path") {
                        if (folder != path) {
                            folder = path
                            picks = emptyMap()
                            form = null
                            settingsNote = null
                        }
                    }
                }
                if (shownFolders.size < manage.folders.size) {
                    TextButton(onClick = { showAllFolders = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("folders_show_all")) {
                        Text("Show ${manage.folders.size - shownFolders.size} more folders")
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
                    val chosenFolder = folder
                    if (chosenAgent != null && chosenFolder != null) {
                        scope.launch {
                            busy = true
                            val result = manage.newSession(chosenAgent, chosenFolder, picks)
                            notice = ManageMessages.describe(result)
                            busy = false
                            if (result is ManageResult.Done) result.created?.let(onCreated)
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
 * The buttons that look after one open session: mark it read or unread, archive or restore it, start a chat (once it is
 * ready) and remove it. Archiving and removing ask first (archiving may take the host's working copy of the project with it),
 * and removing then asks for the device check. The words are what happened, in plain terms.
 */
@Composable
fun SessionManageBar(detail: SessionDetail, sessionUri: String, manage: ManageActionsUi, onRemoved: () -> Unit, modifier: Modifier = Modifier) {
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    var confirmArchive by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun run(call: suspend () -> ManageResult, then: (ManageResult) -> Unit = {}) {
        scope.launch {
            busy = true
            val result = call()
            notice = ManageMessages.describe(result)
            busy = false
            then(result)
        }
    }
    Column(modifier = modifier.fillMaxWidth().testTag("manage_bar"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Manage", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { run({ manage.setRead(sessionUri, !detail.isRead) }) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("toggle_read")) {
                Text(if (detail.isRead) "Mark unread" else "Mark read")
            }
            OutlinedButton(
                onClick = { if (detail.isArchived) run({ manage.setArchived(sessionUri, false) }) else confirmArchive = true },
                enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp).testTag("toggle_archive"),
            ) {
                Text(if (detail.isArchived) "Restore" else "Archive")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { run({ manage.newChat(sessionUri) }) },
                enabled = !busy && detail.phase == SessionPhase.READY,
                modifier = Modifier.heightIn(min = 48.dp).testTag("new_chat"),
            ) { Text("New chat") }
            OutlinedButton(onClick = { confirmRemove = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("remove_session")) { Text("Remove session…") }
        }
        ManageNotice(notice, "manage_notice")
    }
    if (confirmArchive) {
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
    if (confirmRemove) {
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
}

/** The Remove button beside a chat in the list, with the same ask-first-then-check path as removing a session. */
@Composable
fun RemoveChatButton(chatTitle: String, sessionUri: String, chatUri: String, manage: ManageActionsUi, modifier: Modifier = Modifier) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(modifier = modifier) {
        TextButton(onClick = { confirm = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("remove_chat")) { Text("Remove chat…") }
        ManageNotice(notice, "remove_chat_notice")
    }
    if (confirm) {
        ConfirmRemoveDialog(
            title = "Remove this chat?",
            text = "\"${chatTitle.take(MAX_SHOWN)}\" will be removed from the session. This cannot be undone. You will be asked for your fingerprint, face or screen lock.",
            onConfirm = {
                confirm = false
                scope.launch {
                    busy = true
                    notice = ManageMessages.describe(manage.disposeChat(sessionUri, chatUri))
                    busy = false
                }
            },
            onDismiss = { confirm = false },
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
