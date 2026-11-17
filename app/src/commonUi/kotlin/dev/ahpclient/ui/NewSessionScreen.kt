package dev.ahpclient.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.AgentPick
import dev.ahpclient.state.Attachments
import dev.ahpclient.state.ComposerAttachment
import dev.ahpclient.state.ConfigCompletions
import dev.ahpclient.state.ConfigField
import dev.ahpclient.state.ConfigOffer
import dev.ahpclient.state.ConfigOption
import dev.ahpclient.state.ConfigPick
import dev.ahpclient.state.ManageMessages
import dev.ahpclient.state.ManageResult
import dev.ahpclient.state.ModelChoice
import dev.ahpclient.state.ModelOptions
import dev.ahpclient.state.NewSessionSetup
import dev.ahpclient.state.RecentFolders
import dev.ahpclient.state.SessionConfigForm
import dev.ahpclient.state.displayPath
import dev.ahpclient.ui.chat.AGENT_LABEL_MAX
import dev.ahpclient.ui.chat.AgentRow
import dev.ahpclient.ui.chat.MODEL_LABEL_MAX
import dev.ahpclient.ui.chat.ModelPickSaver
import dev.ahpclient.ui.chat.ModelPickerDialog
import dev.ahpclient.ui.chat.WholeWordsLabel
import dev.ahpclient.ui.chat.agentLabel
import dev.ahpclient.ui.chat.modelLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the new-session page hands back once the host took the session: where it is, and the first message to send in its chat, with the
 * model, custom agent and files chosen for it. The session page sends it as soon as the chat can take a message.
 */
@Immutable
class FirstMessage(
    val hostId: String?,
    val sessionUri: String,
    val text: String,
    val model: ModelChoice?,
    val agent: AgentPick,
    val attachments: List<ComposerAttachment>,
)

/** One host the page can start a session on: the connected host's own actions, or one of several connected hosts. */
private class StartTarget(
    val hostId: String?,
    val name: String?,
    val agents: List<AgentOption>,
    val folders: List<String>,
    val newSession: suspend (provider: String, folder: String?, picks: Map<String, ConfigPick>) -> ManageResult,
    val sessionSettings: suspend (provider: String, folder: String?, picks: Map<String, ConfigPick>) -> ConfigOffer,
    val settingCompletions: SettingCompletions,
    val models: (provider: String) -> List<dev.ahpclient.state.ModelOption>,
    val customAgents: List<dev.ahpclient.state.AgentChoice>,
)

private fun ManageActionsUi.targets(): List<StartTarget> =
    hosts.map { StartTarget(it.hostId, it.hostName, it.agents, it.folders, it.newSession, it.sessionSettings, it.settingCompletions, it.models, it.customAgents) }
        .ifEmpty { listOf(StartTarget(null, null, agents, folders, newSession, sessionSettings, settingCompletions, models, customAgents)) }

/** Which picker is open over the page: a sheet for a setup chip, or a dialog for the message box's chips. */
private object Pickers {
    const val HOST = "host"
    const val FOLDER = "folder"
    const val HARNESS = "harness"
    const val CHAT = "chat"
    const val AGENT = "agent"
    const val MODEL = "model"
    const val SETTING = "setting:"
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
 * A new session, laid out as the chat it will become. At the top, chips for where the agent works: the host (when more than one is
 * connected), the folder, the agent harness, and the settings the host offers for that pair (isolation and branch first). At the bottom,
 * the message box: the task, files to add, the custom agent, the mode and approvals, and the model. Sending starts the session (after
 * the device check, which names the folder and the settings chosen) and hands the message to the session's chat, so nothing is left
 * half started. Nothing the host did not offer can be picked, and the host is asked again for its settings after each choice, because
 * its answer can change. The host's words are plain text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewSessionScreen(
    manage: ManageActionsUi,
    onBack: () -> Unit,
    onStarted: (FirstMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val targets = remember(manage) { manage.targets() }
    var hostId by rememberSaveable { mutableStateOf(targets.first().hostId) }
    val target = targets.firstOrNull { it.hostId == hostId } ?: targets.first()

    // Opened on the host's first agent and the most recent folder, so a task can be sent at once; each is one tap to change.
    var provider by rememberSaveable { mutableStateOf(NewSessionSetup.defaultProvider(target.agents.map { it.provider })) }
    var folder by rememberSaveable { mutableStateOf(NewSessionSetup.defaultFolder(target.folders)) }
    var picks by rememberSaveable(stateSaver = PicksSaver) { mutableStateOf<Map<String, ConfigPick>>(emptyMap()) }
    var form by remember { mutableStateOf<SessionConfigForm?>(null) }
    var settingsNote by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }

    var text by rememberSaveable { mutableStateOf("") }
    var modelPick by rememberSaveable(stateSaver = ModelPickSaver) { mutableStateOf<ModelChoice?>(null) }
    var agentUri by rememberSaveable { mutableStateOf<String?>(null) }
    // Held in memory only: picked files can be large, so they are not saved across a rotation the way the text is.
    var attached by remember { mutableStateOf<List<ComposerAttachment>>(emptyList()) }
    val scope = rememberCoroutineScope()

    val models = remember(target, provider) { provider?.let(target.models).orEmpty() }
    // A new chat starts on the host's first model, as the message box of an open chat does; a pick the host no longer offers is dropped.
    val model = modelPick?.takeIf { ModelOptions.isOffered(models, it) } ?: models.firstOrNull()?.let { ModelChoice(it.id) }
    val customAgents = target.customAgents
    val agentChosen = agentUri?.takeIf { uri -> customAgents.any { it.uri == uri } }

    fun resetAfterWhere() {
        picks = emptyMap()
        form = null
        settingsNote = null
    }

    // Asked again whenever the host, agent, folder or a choice changes: the host's list of settings can depend on all of them.
    LaunchedEffect(target.hostId, provider, folder, picks) {
        val chosenAgent = provider
        if (chosenAgent == null) {
            form = null
            settingsNote = null
            return@LaunchedEffect
        }
        loading = true
        when (val offer = target.sessionSettings(chosenAgent, folder, picks)) {
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
    val ready = provider != null && !busy && !loading && missing.isEmpty() && (settingsNote == null || picks.isEmpty())
    val canSend = ready && text.isNotBlank()

    val attach = rememberAttachmentPicker(
        onPicked = { files ->
            val all = attached + files
            val problem = Attachments.problem(all, vision = null)
            if (problem == null) attached = all
            notice = problem?.let(Attachments::describe)
        },
        onUnreadable = { notice = it },
    )

    fun start() {
        val chosenAgent = provider ?: return
        val message = text
        val files = attached
        val chosenModel = model
        val pick = agentChosen?.let { AgentPick.Use(it) } ?: AgentPick.Keep
        val chosenFolder = folder
        val chosenPicks = picks
        val startOn = target
        scope.launch {
            busy = true
            notice = null
            val result = startOn.newSession(chosenAgent, chosenFolder, chosenPicks)
            busy = false
            val created = (result as? ManageResult.Done)?.created
            if (created != null) {
                onStarted(FirstMessage(startOn.hostId, created, message, chosenModel, pick, files))
            } else {
                notice = ManageMessages.describe(result) ?: "The host did not say which session it started."
            }
        }
    }

    ScreenScaffold(title = "New session", onBack = onBack, backLabel = "Close", backTag = "new_session_close", modifier = modifier.testTag("new_session_screen")) {
        Column(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.heightIn(min = 24.dp))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TileIcon(AppIcons.AgentCode)
                    Text("What should the agent do?", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                    Text(
                        "Check where it will work, then describe the task below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(Design.OuterRadius), modifier = Modifier.fillMaxWidth()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("new_session_setup"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (targets.size > 1) SetupChip(AppIcons.Host, "Host", target.name.orEmpty(), "pick_host") { picker = Pickers.HOST }
                        SetupChip(AppIcons.Folder, "Folder", folder?.let(::displayPath) ?: "No folder", "pick_folder") { picker = Pickers.FOLDER }
                        val agentName = target.agents.firstOrNull { it.provider == provider }?.let { it.name.ifBlank { it.provider } }
                        SetupChip(AppIcons.AgentCode, "Agent harness", agentName ?: "None offered", "pick_harness") { picker = Pickers.HARNESS }
                        NewSessionSetup.setupFields(form).forEach { field ->
                            val icon = when (field.id) {
                                NewSessionSetup.ISOLATION -> AppIcons.Fork
                                NewSessionSetup.BRANCH -> AppIcons.Branch
                                else -> AppIcons.Wrench
                            }
                            // A setting with no value yet is named by its title, so the chip says what it is; a screen reader hears who decides.
                            val value = NewSessionSetup.valueLabel(field, picks)
                            val required = field in missing
                            SetupChip(
                                icon, field.title, value ?: field.title, "pick_setting_${field.id}", required = required,
                                spoken = value ?: if (required) "choose a value" else "the host's default",
                            ) {
                                picker = Pickers.SETTING + field.id
                            }
                        }
                    }
                }
                if (target.agents.isEmpty()) StatusLine("The host offers no agents, so a session cannot start here.", "no_agents", error = true)
                if (loading) StatusLine("Asking the host for its settings…", "settings_loading")
                if (missing.isNotEmpty()) StatusLine("Choose a value for: " + missing.joinToString { it.title }, "settings_missing", error = true)
                settingsNote?.let { note ->
                    StatusLine("Could not get this agent's settings. $note" + if (picks.isEmpty()) " You can still start; the host will use its own." else "", "settings_note", error = true)
                }
                form?.omitted?.takeIf { it > 0 }?.let { omitted ->
                    StatusLine("$omitted more setting${if (omitted == 1) "" else "s"} this app cannot show; the host keeps its own.", "settings_omitted")
                }
                notice?.let { StatusLine(it, "new_session_notice", error = true) }
                Spacer(Modifier.heightIn(min = 8.dp))
            }
            StartComposer(
                text = text,
                onText = { text = it },
                attached = attached,
                onRemoveAttachment = { file -> attached = attached.filterNot { it === file } },
                onAttach = attach?.let { p -> { p.open() } },
                agentText = if (customAgents.isNotEmpty() || agentChosen != null) agentLabel(customAgents, agentChosen) else null,
                onAgent = { picker = Pickers.AGENT },
                chatText = NewSessionSetup.chatLabel(form, picks),
                allowsAll = NewSessionSetup.allowsAll(form, picks),
                onChat = { picker = Pickers.CHAT },
                modelText = if (models.isNotEmpty()) modelLabel(models, model) else null,
                onModel = { picker = Pickers.MODEL },
                canSend = canSend,
                busy = busy,
                onSend = ::start,
            )
        }
    }

    when (val open = picker) {
        Pickers.HOST -> PickerSheet("Host", "host_sheet", onDismiss = { picker = null }) {
            targets.forEach { t ->
                ChoiceRow(t.name.orEmpty().take(MAX_SHOWN), t.hostId == target.hostId, "host_${t.hostId}") {
                    picker = null
                    if (t.hostId != target.hostId) {
                        hostId = t.hostId
                        provider = NewSessionSetup.defaultProvider(t.agents.map { it.provider })
                        folder = NewSessionSetup.defaultFolder(t.folders)
                        modelPick = null
                        agentUri = null
                        notice = null
                        resetAfterWhere()
                    }
                }
            }
        }
        Pickers.FOLDER -> FolderSheet(target.folders, folder, onDismiss = { picker = null }) { chosen ->
            picker = null
            if (chosen != folder) {
                folder = chosen
                resetAfterWhere()
            }
        }
        Pickers.HARNESS -> PickerSheet("Agent harness", "harness_sheet", onDismiss = { picker = null }) {
            if (target.agents.isEmpty()) Text("The host offers no agents.", modifier = Modifier.padding(vertical = 8.dp))
            target.agents.forEach { agent ->
                ChoiceRow(agent.name.ifBlank { agent.provider }.take(MAX_SHOWN), provider == agent.provider, "agent_${agent.provider}") {
                    picker = null
                    if (provider != agent.provider) {
                        provider = agent.provider
                        modelPick = null
                        resetAfterWhere()
                    }
                }
            }
        }
        Pickers.CHAT -> PickerSheet("Mode and approvals", "chat_sheet", onDismiss = { picker = null }) {
            NewSessionSetup.chatFields(form).forEach { field ->
                SettingChoices(field, picks, target, provider, folder, closeOnPick = null) { id, pick -> picks = picks + (id to pick) }
            }
            if (NewSessionSetup.allowsAll(form, picks)) {
                Text(
                    "Tool calls will run without asking. You will confirm with your fingerprint, face or screen lock when the session starts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp).testTag("allow_all_warning"),
                )
            }
            TextButton(onClick = { picker = null }, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).testTag("chat_sheet_done")) { Text("Done") }
        }
        Pickers.AGENT -> AgentPickerDialog(customAgents, agentChosen, onDismiss = { picker = null }) { uri -> agentUri = uri; picker = null }
        Pickers.MODEL -> ModelPickerDialog(models, model, showDefault = false, onDismiss = { picker = null }, onPick = { modelPick = it; picker = null })
        null -> Unit
        else -> {
            val field = form?.fields?.firstOrNull { Pickers.SETTING + it.id == open }
            if (field == null) {
                // The host stopped offering it, or its settings are still being fetched after a rotation.
                if (!loading) LaunchedEffect(open) { picker = null }
            } else {
                PickerSheet(field.title, "setting_sheet_${field.id}", onDismiss = { picker = null }) {
                    SettingChoices(field, picks, target, provider, folder, closeOnPick = { picker = null }) { id, pick -> picks = picks + (id to pick) }
                }
            }
        }
    }
}

/** A chip for one choice about where the agent works: an icon, the value, and a chevron that says it opens a list. Spoken as "Title: value". */
@Composable
private fun SetupChip(icon: ImageVector, title: String, value: String, tag: String, required: Boolean = false, spoken: String = value, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp)) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
        trailingIcon = { Icon(AppIcons.ChevronDown, contentDescription = null, modifier = Modifier.size(16.dp)) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (required) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
            labelColor = if (required) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        ),
        border = null,
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "$title: $spoken" }.testTag(tag),
    )
}

@Composable
private fun StatusLine(text: String, tag: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag(tag).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * The message box of the new session, styled as the one in a chat: the task, the files added, and under it the custom agent, the mode
 * and approvals (in the warning colour when tool calls will run without asking), the model, and the send arrow, which starts the session.
 */
@Composable
private fun StartComposer(
    text: String,
    onText: (String) -> Unit,
    attached: List<ComposerAttachment>,
    onRemoveAttachment: (ComposerAttachment) -> Unit,
    onAttach: (() -> Unit)?,
    agentText: String?,
    onAgent: () -> Unit,
    chatText: String?,
    allowsAll: Boolean,
    onChat: () -> Unit,
    modelText: String?,
    onModel: () -> Unit,
    canSend: Boolean,
    busy: Boolean,
    onSend: () -> Unit,
) {
    val chipColors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).testTag("new_session_composer"),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 8.dp)) {
            if (attached.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    attached.forEachIndexed { index, file ->
                        AssistChip(
                            onClick = { onRemoveAttachment(file) },
                            label = { Text(file.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp)) },
                            trailingIcon = { Icon(AppIcons.Clear, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            colors = chipColors,
                            border = null,
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Remove attachment ${file.label}" }.testTag("new_session_attachment_$index"),
                        )
                    }
                }
            }
            TextField(
                value = text,
                onValueChange = onText,
                placeholder = { Text("Describe a task for the agent") },
                minLines = 2,
                maxLines = 6,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().testTag("new_session_input"),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The choices scroll sideways when they do not fit; sending, on the right, never scrolls away.
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()).testTag("new_session_chip_row"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onAttach != null) {
                        IconButton(onClick = onAttach, enabled = !busy && attached.size < Attachments.MAX_COUNT, modifier = Modifier.size(48.dp).testTag("new_session_attach")) {
                            Icon(AppIcons.Add, contentDescription = "Add context")
                        }
                    }
                    if (agentText != null) {
                        AssistChip(
                            onClick = onAgent,
                            label = { WholeWordsLabel(agentText, AGENT_LABEL_MAX) },
                            colors = chipColors,
                            border = null,
                            modifier = Modifier.semantics { contentDescription = "Agent: $agentText" }.testTag("new_session_agent"),
                        )
                    }
                    if (chatText != null) {
                        AssistChip(
                            onClick = onChat,
                            label = { Text(chatText, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                            leadingIcon = if (allowsAll) ({ Icon(AppIcons.ErrorCircle, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null,
                            colors = if (allowsAll) {
                                AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    labelColor = MaterialTheme.colorScheme.onErrorContainer,
                                    leadingIconContentColor = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            } else {
                                chipColors
                            },
                            border = null,
                            modifier = Modifier
                                .semantics { contentDescription = "Mode and approvals: $chatText" + if (allowsAll) ". Tool calls run without asking" else "" }
                                .testTag("new_session_mode"),
                        )
                    }
                    if (modelText != null) {
                        AssistChip(
                            onClick = onModel,
                            label = { WholeWordsLabel(modelText, MODEL_LABEL_MAX) },
                            colors = chipColors,
                            border = null,
                            modifier = Modifier.semantics { contentDescription = "Model: $modelText" }.testTag("new_session_model"),
                        )
                    }
                }
                FilledIconButton(onClick = onSend, enabled = canSend, modifier = Modifier.size(48.dp).testTag("new_session_start")) {
                    Icon(AppIcons.ArrowUp, contentDescription = "Start session")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerSheet(title: String, tag: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        dragHandle = { BottomSheetDefaults.DragHandle(width = 48.dp) },
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        modifier = Modifier.testTag(tag),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })
            content()
        }
    }
}

/** The folders: none (the host chooses), the most recent ones, and the rest on request. */
@Composable
private fun FolderSheet(folders: List<String>, chosen: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val shown = if (showAll) folders else folders.take(RecentFolders.SHOWN)
    PickerSheet("Folder", "folder_sheet", onDismiss) {
        if (folders.isEmpty()) {
            Text("The host has no folders to pick yet.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp).testTag("no_folders"))
        }
        ChoiceRow("No folder (the host chooses)", chosen == null, "folder_none") { onPick(null) }
        shown.forEach { path -> ChoiceRow(displayPath(path).take(MAX_SHOWN), chosen == path, "folder_$path") { onPick(path) } }
        if (shown.size < folders.size) {
            TextButton(onClick = { showAll = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("folders_show_all")) {
                Text("Show ${folders.size - shown.size} more folders")
            }
        }
    }
}

/** One of the host's settings as a list: its title, its description, each value, and a search when the host completes it as you type. */
@Composable
private fun SettingChoices(
    field: ConfigField,
    picks: Map<String, ConfigPick>,
    target: StartTarget,
    provider: String?,
    folder: String?,
    closeOnPick: (() -> Unit)?,
    onPick: (String, ConfigPick) -> Unit,
) {
    Text(
        field.title + if (field.required) " (required)" else "",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() }.testTag("setting_title_${field.id}"),
    )
    field.description?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("setting_description_${field.id}"))
    }
    val selected = NewSessionSetup.selected(field, picks)
    val choose = { pick: ConfigPick -> onPick(field.id, pick); closeOnPick?.invoke(); Unit }
    field.options.forEachIndexed { index, option ->
        ChoiceRow(option.label, selected == option.pick, "setting_${field.id}_$index", option.description) { choose(option.pick) }
    }
    if (field.dynamic) {
        DynamicSetting(
            field = field,
            selected = selected,
            complete = { property, query -> if (provider == null) ConfigCompletions.SeedsOnly else target.settingCompletions(provider, folder, picks, property, query) },
            onPick = choose,
        )
    }
}

/**
 * The search for a setting the host wants completed as you type. Only a value the host returned for the typed text can be chosen,
 * and a host that does not implement the query leaves just the seeded values above, with no error shown.
 */
@Composable
private fun DynamicSetting(
    field: ConfigField,
    selected: ConfigPick?,
    complete: suspend (property: String, query: String) -> ConfigCompletions,
    onPick: (ConfigPick) -> Unit,
) {
    var query by rememberSaveable(field.id) { mutableStateOf("") }
    var found by remember(field.id) { mutableStateOf<List<ConfigOption>>(emptyList()) }
    var supported by remember(field.id) { mutableStateOf(true) }
    var problem by remember(field.id) { mutableStateOf<String?>(null) }
    var searching by remember(field.id) { mutableStateOf(false) }
    val shown = found.filter { option -> field.options.none { it.pick == option.pick } }

    // Waits for a pause in typing, so one request is not sent per letter. A new query cancels the one before it.
    LaunchedEffect(field.id, query) {
        if (query.isBlank()) {
            found = emptyList()
            problem = null
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(COMPLETION_DEBOUNCE_MS)
        when (val answer = complete(field.id, query)) {
            is ConfigCompletions.Items -> { found = answer.options; supported = true; problem = null }
            ConfigCompletions.SeedsOnly -> { found = emptyList(); supported = false; problem = null }
            is ConfigCompletions.Unavailable -> { found = emptyList(); problem = answer.reason }
        }
        searching = false
    }

    // The chosen value is shown even when it is neither a seed nor in the latest results, so it never looks as if nothing is chosen.
    val chosen = (selected as? ConfigPick.Text)?.takeIf { pick -> field.options.none { it.pick == pick } && shown.none { it.pick == pick } }
    chosen?.let { ChoiceRow(it.value.take(MAX_SHOWN), true, "setting_${field.id}_chosen") { } }
    if (supported) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(COMPLETION_QUERY_CHARS) },
            label = { Text("Search ${field.title}") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("setting_search_${field.id}"),
        )
        if (searching) Text("Searching…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("setting_searching_${field.id}"))
        problem?.let {
            Text("Could not search. $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("setting_search_problem_${field.id}").semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (!searching && problem == null && query.isNotBlank() && shown.isEmpty()) {
            Text("Nothing matches.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("setting_search_empty_${field.id}"))
        }
        shown.forEachIndexed { index, option ->
            ChoiceRow(option.label, selected == option.pick, "setting_${field.id}_found_$index", option.description) { onPick(option.pick) }
        }
    }
}

/** The custom agents for the first message: none (the harness's own behaviour), or one the host offers. Names are the host's words. */
@Composable
private fun AgentPickerDialog(agents: List<dev.ahpclient.state.AgentChoice>, current: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agent") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp).testTag("agent_dialog"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item(key = "default") { AgentRow("Default agent", "No custom agent", current == null, "agent_default") { onPick(null) } }
                items(agents, key = { it.uri }) { a -> AgentRow(a.name, a.description, current == a.uri, "agent_option") { onPick(a.uri) } }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("agent_cancel")) { Text("Cancel") } },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, tag: String, description: String? = null, onSelect: () -> Unit) {
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
        Column(Modifier.padding(vertical = 6.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private const val COMPLETION_DEBOUNCE_MS = 350L
private const val COMPLETION_QUERY_CHARS = 100

/** The host's own words (agent names, folder paths) are untrusted display text: shown as plain text and cut to a sane length. */
private const val MAX_SHOWN = 120
