package dev.ahpclient.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.AnswerValue
import dev.ahpclient.state.ChatConfigPrompt
import dev.ahpclient.state.ChatConfigView
import dev.ahpclient.state.ConfigControl
import dev.ahpclient.state.ConfigKind
import dev.ahpclient.state.AgentChoice
import dev.ahpclient.state.AgentPick
import dev.ahpclient.state.CustomAgents
import dev.ahpclient.state.ApprovalChoice
import dev.ahpclient.state.FormCheck
import dev.ahpclient.state.FormQuestion
import dev.ahpclient.state.FormValue
import dev.ahpclient.state.InputForm
import dev.ahpclient.state.InteractMessages
import dev.ahpclient.state.InteractResult
import dev.ahpclient.state.ManageMessages
import dev.ahpclient.state.ManageResult
import dev.ahpclient.state.ModelChoice
import dev.ahpclient.state.ModelOption
import dev.ahpclient.state.ModelOptions
import dev.ahpclient.state.PendingMessageKind
import dev.ahpclient.state.PendingMessageView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import dev.ahpclient.state.ActiveSlashCommand
import dev.ahpclient.state.AgentMentionQuery
import dev.ahpclient.state.CustomizationCategory
import dev.ahpclient.state.CustomizationsView
import dev.ahpclient.state.HostFileTarget
import dev.ahpclient.state.QuestionKind
import dev.ahpclient.state.QuickReplies
import dev.ahpclient.state.QuickReplyChip
import dev.ahpclient.state.QuickReplyContext
import dev.ahpclient.state.QuickReplyMode
import dev.ahpclient.state.QuickReplyProvider
import dev.ahpclient.state.QuickReplySource
import dev.ahpclient.state.QuickReplySuggestion
import dev.ahpclient.state.SlashCommand
import dev.ahpclient.state.SlashCommandKind
import dev.ahpclient.state.SlashCommandMatch
import dev.ahpclient.state.SlashCommands
import dev.ahpclient.ui.SlashCommandPickerSheet
import dev.ahpclient.ui.formatSlashCommandName
import dev.ahpclient.ui.AppIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * What a screen can ask for in a chat the person is allowed to act in. A chat that is read only (the connection cannot
 * interact, the host shows the chat as read only, or consent is off) has none, so none of the controls below exist
 * there: there is nothing to disable and nothing to tap by accident.
 */
@Stable
class ChatActionsUi(
    /** True while the agent is working: a message sent now waits its turn, can steer the turn, and there is something to stop. */
    val running: Boolean,
    val send: suspend (text: String, model: ModelChoice?, agent: AgentPick) -> InteractResult,
    val steer: suspend (text: String, model: ModelChoice?, agent: AgentPick) -> InteractResult,
    val cancel: suspend () -> InteractResult,
    val approve: suspend (toolCallId: String, optionId: String?) -> InteractResult,
    val deny: suspend (toolCallId: String, optionId: String?) -> InteractResult,
    val approveResult: suspend (toolCallId: String) -> InteractResult,
    val denyResult: suspend (toolCallId: String) -> InteractResult,
    val answer: suspend (requestId: String, answers: Map<String, AnswerValue>) -> InteractResult,
    val saveDraft: suspend (requestId: String, questionId: String, answer: AnswerValue?) -> InteractResult,
    val decline: suspend (requestId: String) -> InteractResult,
    val dismiss: suspend (requestId: String) -> InteractResult,
    /** The models the host offers for this chat's agent. None means no picker is shown and messages go with the host's own default. */
    val models: List<ModelOption> = emptyList(),
    /** The model the chat is using now (the one its latest turn was sent with), so the box can show it and send it. Null for a chat with no turn yet. */
    val modelInUse: ModelChoice? = null,
    /** The custom agents the host offers for this chat. None offered and none in use means no agent chip is shown. */
    val agents: List<AgentChoice> = emptyList(),
    /** The custom agent the chat is on now (its latest message's), so the box can show it and a message keeps it. Null is no custom agent. */
    val agentInUse: String? = null,
    /** The chat's mode and approvals as the host reports them. No controls means none are shown. */
    val config: ChatConfigView = ChatConfigView.NONE,
    /** Sets one of them. The device check for giving the agent more freedom is inside it, so a screen only reports the result. */
    val setConfig: suspend (key: String, value: String) -> InteractResult = { _, _ -> InteractResult.NotAllowed },
    /** Restores the chat and workspace to the point just before [beforeTurnId]. */
    val restoreCheckpoint: suspend (beforeTurnId: String) -> InteractResult = { InteractResult.NotAllowed },
    /** Clears all turns from the chat and undoes all changes. */
    val startOver: suspend () -> InteractResult = { InteractResult.NotAllowed },
    /** Truncates to before [turnId] and starts a new turn with [newText]. */
    val editAndResend: suspend (turnId: String, newText: String, model: ModelChoice?, agent: AgentPick) -> InteractResult = { _, _, _, _ -> InteractResult.NotAllowed },
    /** Resumes a resumable failed turn or truncates + re-sends [turnId]. */
    val retryTurn: suspend (turnId: String, model: ModelChoice?, agent: AgentPick) -> InteractResult = { _, _, _ -> InteractResult.NotAllowed },
    /** Cancels the running turn and starts a new turn with [text]. */
    val stopAndSend: suspend (text: String, model: ModelChoice?, agent: AgentPick) -> InteractResult = { _, _, _ -> InteractResult.NotAllowed },
    /** Removes a pending (steering or queued) message by [id]. */
    val removePending: suspend (id: String) -> InteractResult = { InteractResult.NotAllowed },
    /** Reorders the queued messages to match [order]. */
    val reorderQueue: suspend (order: List<String>) -> InteractResult = { InteractResult.NotAllowed },
    /** True when the host and session allow branching this chat into a new forked chat tab. */
    val canFork: Boolean = false,
    /** Branches the conversation at [turnId] into a new chat tab. */
    val forkFromTurn: suspend (turnId: String) -> ManageResult = { ManageResult.NotAllowed },
    /** Branches the conversation at [previousTurnId] (or a fresh chat if null) and sends [newText] as its initial message. */
    val editAsFork: suspend (previousTurnId: String?, newText: String, model: ModelChoice?, agent: AgentPick) -> ManageResult = { _, _, _, _ -> ManageResult.NotAllowed },
    /** True when the host and session allow opening a side chat anchored to a turn. */
    val canSideChat: Boolean = false,
    /** Opens a side chat anchored to [turnId], optionally sending [initialText] right away. */
    val openSideChat: suspend (turnId: String, initialText: String?, model: ModelChoice?, agent: AgentPick) -> ManageResult = { _, _, _, _ -> ManageResult.NotAllowed },
    /** User-invokable slash commands (installed skills, prompts, and built-in commands) for `/` autocomplete in the composer. */
    val slashCommands: List<SlashCommand> = emptyList(),
    /** Full environment customizations summary for the Customizations sheet. */
    val customizations: CustomizationsView = CustomizationsView.EMPTY,
    /** Opens the environment Customizations sheet, optionally filtered to a category or search query. */
    val onOpenCustomizations: ((CustomizationCategory?, String) -> Unit)? = null,
    /** Opens a host file (such as `SKILL.md` or `.agent.md`) in the rich file viewer modal. */
    val onViewHostFile: ((HostFileTarget) -> Unit)? = null,
    /** One-shot command or text prefill from the Customizations sheet (e.g. `"/skill-eval "`). */
    val prefillText: String? = null,
    val onPrefillConsumed: () -> Unit = {},
    /** One-shot agent URI selection from the Customizations sheet. */
    val agentToUse: String? = null,
    val onAgentToUseConsumed: () -> Unit = {},
    /** How quick-reply suggestions are offered after an agent turn finishes. */
    val quickReplyMode: QuickReplyMode = QuickReplyMode.OFF,
    /** Optional AI provider (such as on-device Gemini Nano or an external AI endpoint) to refine quick replies beyond heuristics. */
    val quickReplyProvider: QuickReplyProvider? = null,
    /** Key representing the active AI provider configuration so changing settings re-triggers suggestion generation. */
    val quickReplyCacheKey: String = "",
)

/** The host's own words on a button or a field are untrusted display text: shown as plain text and cut to a sane length. */
private const val MAX_LABEL = 80
private const val MAX_QUESTION_TEXT = 4_000
private const val MAX_OPTION_TEXT = 1_000

/** How long a form waits after the last change before it tells the host what has been entered. */
private const val DRAFT_PAUSE_MS = 700L

/** Keeps a picked model across rotation: its id, then each setting as a key and a value. A null pick is an empty list. */
private val ModelPickSaver: Saver<ModelChoice?, Any> = listSaver(
    save = { choice -> if (choice == null) emptyList<String>() else listOf(choice.id) + choice.config.flatMap { listOf(it.key, it.value) } },
    restore = { saved -> if (saved.isEmpty()) null else ModelChoice(saved[0], saved.drop(1).chunked(2).filter { it.size == 2 }.associate { it[0] to it[1] }) },
)

/** What the model button says: the model's name and the labels of the settings picked for it, or that the host's default is used. */
private fun modelLabel(models: List<ModelOption>, choice: ModelChoice?): String {
    val model = choice?.let { c -> models.firstOrNull { it.id == c.id } } ?: return "Default model"
    val settings = model.settings.mapNotNull { s -> choice.config[s.id]?.let { v -> s.values.firstOrNull { it.value == v }?.label } }
    return (listOf(model.name) + settings).joinToString(" · ")
}

@Composable
private fun PickRow(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Picks the model for the next messages: one the host lists for this agent, and for it, one of each setting's listed values.
 * "Default" (offered only for a chat that has not used a model yet) sends no model at all. The names and labels are the host's words, shown as plain text.
 */
@Composable
private fun ModelPickerDialog(models: List<ModelOption>, current: ModelChoice?, showDefault: Boolean, onDismiss: () -> Unit, onPick: (ModelChoice?) -> Unit) {
    var draft by rememberSaveable(stateSaver = ModelPickSaver) { mutableStateOf(current) }
    val model = models.firstOrNull { it.id == draft?.id }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (models.size > 5) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).testTag("model_dialog"),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (showDefault) PickRow("Default", draft == null, "model_default") { draft = null }
                    models.forEach { m -> PickRow(m.name, draft?.id == m.id, "model_option") { if (draft?.id != m.id) draft = ModelChoice(m.id) } }
                    model?.settings?.forEach { setting ->
                        Text(setting.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp).semantics { heading() })
                        PickRow("Default", draft?.config?.containsKey(setting.id) != true, "model_setting_default") {
                            draft = draft?.let { it.copy(config = it.config - setting.id) }
                        }
                        setting.values.forEach { v ->
                            PickRow(v.label, draft?.config?.get(setting.id) == v.value, "model_setting_value") {
                                draft = draft?.let { it.copy(config = it.config + (setting.id to v.value)) }
                            }
                        }
                    }
                }
                if (models.size > 5) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onPick(draft) }, modifier = Modifier.testTag("model_use")) { Text("Use") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("model_cancel")) { Text("Cancel") } },
    )
}

/** Keeps a picked agent across rotation. */
private val AgentPickSaver: Saver<AgentPick, Any> = listSaver(
    save = { pick ->
        when (pick) {
            AgentPick.Keep -> listOf("keep")
            AgentPick.None -> listOf("none")
            is AgentPick.Use -> listOf("use", pick.uri)
        }
    },
    restore = { saved ->
        when (saved.firstOrNull()) {
            "none" -> AgentPick.None
            "use" -> saved.getOrNull(1)?.let { AgentPick.Use(it) } ?: AgentPick.Keep
            else -> AgentPick.Keep
        }
    },
)

/** What the agent button says: the agent's name, or that none is in use. */
private fun agentLabel(agents: List<AgentChoice>, uri: String?): String = CustomAgents.nameOf(uri, agents) ?: "Default agent"

@Composable
private fun AgentRow(name: String, description: String?, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * One setting of the chat, as a row of choices: its title, then each value the host offers as a chip. A value the host withholds, or any
 * value of a setting this app may not change, is shown but cannot be chosen. Under the choices a line says what a choice that gives the
 * agent more freedom will ask of the person (the device check), or why nothing can be chosen. The words are this app's own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfigSection(control: ConfigControl, selected: String?, onSelect: (String) -> Unit) {
    val kind = if (control.kind == ConfigKind.MODE) "mode" else "approvals"
    Column(modifier = Modifier.fillMaxWidth().testTag("config_section_$kind"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(control.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp).semantics { heading() })
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            control.choices.forEach { choice ->
                FilterChip(
                    selected = selected == choice.value,
                    onClick = { onSelect(choice.value) },
                    enabled = control.writable && choice.available,
                    label = { Text(choice.label) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("config_${kind}_${choice.value}"),
                )
            }
        }
        val hint = when {
            !control.writable -> "Can't be changed from here."
            selected != null && selected != control.current && control.loosens(selected) ->
                "${ChatConfigPrompt.name(control, selected)}. You will be asked for your fingerprint, face or screen lock."
            control.choices.any { !it.available } -> "A greyed-out choice is not allowed by the host right now."
            else -> control.choices.firstOrNull { it.value == selected }?.description
        }
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("config_hint_$kind"))
    }
}

/**
 * The options for the next messages in this chat: how the agent works (mode) and how freely its tool calls run (approvals), when the host
 * offers them, pinned at the top, and under them the custom agent: none (the provider's own behaviour), or one the host lists. The agent the
 * chat is on stays picked unless this is used to change it, so sending never switches it by accident. The mode and approvals are the chat's own
 * on the host, not a pick kept here, so they always show what is in force. The names and descriptions are the host's words, shown as plain text.
 * [onPick] is told the agent and the settings that were changed, as pairs of property and value; the caller sends them.
 */
@Composable
private fun ChatOptionsDialog(
    agents: List<AgentChoice>,
    currentAgent: String?,
    showAgents: Boolean,
    config: ChatConfigView,
    onDismiss: () -> Unit,
    onPick: (agent: String?, changes: List<Pair<String, String>>) -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf(currentAgent) }
    var draftMode by rememberSaveable { mutableStateOf(config.mode?.current) }
    var draftApprovals by rememberSaveable { mutableStateOf(config.approvals?.current) }
    // An agent the chat is on that the host no longer lists is still shown, so the dialog says what is true.
    val unlisted = currentAgent?.takeIf { uri -> agents.none { it.uri == uri } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (config.controls.isEmpty()) "Agent" else "Options") },
        text = {
            Column(modifier = Modifier.testTag("options_dialog"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                config.mode?.let { ConfigSection(it, draftMode) { value -> draftMode = value } }
                config.approvals?.let { ConfigSection(it, draftApprovals) { value -> draftApprovals = value } }
                if (config.controls.isNotEmpty() && showAgents) HorizontalDivider()
                if (showAgents) {
                    if (config.controls.isNotEmpty()) Text("Agent", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                    // A long list opens at the agent the chat is on, so the pop-up shows what is true without a scroll to find it.
                    val position = when {
                        currentAgent == null -> 0
                        unlisted != null -> 1
                        else -> 1 + agents.indexOfFirst { it.uri == currentAgent }.coerceAtLeast(0)
                    }
                    if (agents.size > 3) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    LazyColumn(
                        state = rememberLazyListState(initialFirstVisibleItemIndex = (position - 1).coerceAtLeast(0)),
                        modifier = Modifier.heightIn(max = if (config.controls.isEmpty()) 420.dp else 280.dp).testTag("agent_dialog"),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        item(key = "default") { AgentRow("Default agent", "No custom agent", draft == null, "agent_default") { draft = null } }
                        if (unlisted != null) {
                            item(key = "unlisted") { AgentRow(CustomAgents.nameOf(unlisted, agents).orEmpty(), "Not offered now", draft == unlisted, "agent_option") { draft = unlisted } }
                        }
                        items(agents, key = { it.uri }) { a -> AgentRow(a.name, a.description, draft == a.uri, "agent_option") { draft = a.uri } }
                    }
                    if (agents.size > 3) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val changes = listOfNotNull(
                        config.mode?.takeIf { it.writable }?.let { c -> draftMode?.takeIf { it != c.current }?.let { c.key to it } },
                        config.approvals?.takeIf { it.writable }?.let { c -> draftApprovals?.takeIf { it != c.current }?.let { c.key to it } },
                    )
                    onPick(draft, changes)
                },
                modifier = Modifier.testTag("agent_use"),
            ) { Text("Use") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("agent_cancel")) { Text("Cancel") } },
    )
}

/**
 * What is not at its quiet default, above the message box: Plan, Autopilot, Assisted and, in the warning colour, Allow all. Nothing when
 * mode and approvals are as they start, so the box stays quiet. A tap opens the options. Allow all is the one that must never be a surprise.
 */
@Composable
private fun ConfigStateRow(config: ChatConfigView, onClick: () -> Unit, chipColors: androidx.compose.material3.ChipColors, modifier: Modifier = Modifier) {
    val notable = config.notable
    if (notable.isEmpty()) return
    Row(modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        notable.forEach { control ->
            val label = control.currentLabel.orEmpty()
            val warn = control.isAllowAll
            AssistChip(
                onClick = onClick,
                label = { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                leadingIcon = if (warn) ({ Icon(AppIcons.ErrorCircle, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null,
                colors = if (warn) {
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
                    .semantics { contentDescription = "${control.title}: $label" + if (warn) ". Tool calls run without asking. Opens the options" else ". Opens the options" }
                    .testTag(if (control.kind == ConfigKind.MODE) "config_state_mode" else "config_state_approvals"),
            )
        }
    }
}
@Composable
private fun Notice(text: String?, tag: String, modifier: Modifier = Modifier) {
    if (text == null) return
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * In-flight steering and queued messages waiting on the active turn. Allows removing a pending message, pulling it back into
 * the composer to edit, or reordering queued messages.
 */
@Composable
private fun PendingMessageTray(
    pendingMessages: List<PendingMessageView>,
    busy: Boolean,
    onEdit: (PendingMessageView) -> Unit,
    onRemove: (PendingMessageView) -> Unit,
    onMoveQueue: (fromIndex: Int, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pendingMessages.isEmpty()) return
    val queuedIds = remember(pendingMessages) { pendingMessages.filter { it.kind == PendingMessageKind.QUEUED }.map { it.id } }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp).testTag("pending_tray"),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            pendingMessages.forEach { msg ->
                val qIndex = if (msg.kind == PendingMessageKind.QUEUED) queuedIds.indexOf(msg.id) else -1
                val badge = if (msg.kind == PendingMessageKind.STEERING) {
                    "Steer next"
                } else if (queuedIds.size > 1) {
                    "Queued #${qIndex + 1}"
                } else {
                    "Queued"
                }
                Row(
                    modifier = Modifier.fillMaxWidth().testTag("pending_row_${msg.id}"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = if (msg.kind == PendingMessageKind.STEERING) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (msg.kind == PendingMessageKind.STEERING) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Text(
                        msg.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).testTag("pending_text_${msg.id}"),
                    )
                    if (msg.kind == PendingMessageKind.QUEUED && queuedIds.size > 1) {
                        IconButton(
                            onClick = { onMoveQueue(qIndex, qIndex - 1) },
                            enabled = !busy && qIndex > 0,
                            modifier = Modifier.size(48.dp).testTag("pending_up_${msg.id}"),
                        ) {
                            Icon(AppIcons.ArrowUp, contentDescription = "Move up in queue", modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = { onMoveQueue(qIndex, qIndex + 1) },
                            enabled = !busy && qIndex in 0 until queuedIds.lastIndex,
                            modifier = Modifier.size(48.dp).testTag("pending_down_${msg.id}"),
                        ) {
                            Icon(AppIcons.ArrowDown, contentDescription = "Move down in queue", modifier = Modifier.size(18.dp))
                        }
                    }
                    IconButton(
                        onClick = { onEdit(msg) },
                        enabled = !busy,
                        modifier = Modifier.size(48.dp).testTag("pending_edit_${msg.id}"),
                    ) {
                        Icon(AppIcons.Edit, contentDescription = "Edit pending message", modifier = Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = { onRemove(msg) },
                        enabled = !busy,
                        modifier = Modifier.size(48.dp).testTag("pending_remove_${msg.id}"),
                    ) {
                        Icon(AppIcons.Clear, contentDescription = "Remove pending message", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/**
 * The message box, as the chat apps have it: one rounded card holding the text, and under the text a row with the model on the
 * left and the buttons on the right. The send button is an arrow; while the agent is working a Stop button joins it, and a
 * message typed then can steer the turn, be queued, stop and send, or open a side chat. The text stays until the host has
 * confirmed the message, so a failed send loses nothing, and it survives rotation.
 */
@Composable
internal fun SlashCommandPopup(
    matches: List<SlashCommandMatch>,
    onSelect: (SlashCommand) -> Unit,
    onOpenCustomizations: (() -> Unit)?,
    onShowAll: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (matches.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .testTag("composer_slash_menu"),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Slash commands (${matches.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onShowAll != null) {
                        TextButton(
                            onClick = onShowAll,
                            modifier = Modifier.testTag("composer_show_all_slash_commands"),
                        ) {
                            Text("Show all", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (onOpenCustomizations != null) {
                        TextButton(
                            onClick = onOpenCustomizations,
                            modifier = Modifier.testTag("composer_open_customizations"),
                        ) {
                            Text("Customizations", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .testTag("composer_slash_list"),
            ) {
                items(matches, key = { it.command.name }) { match ->
                    val cmd = match.command
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { onSelect(cmd) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("slash_option_${cmd.name}"),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val primaryColor = MaterialTheme.colorScheme.primary
                        val normalColor = MaterialTheme.colorScheme.onSurface
                        val nameText = formatSlashCommandName(match, primaryColor, normalColor)
                        Text(
                            text = nameText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (cmd.description != null) Modifier.widthIn(max = 210.dp) else Modifier,
                        )
                        val rightText = cmd.description ?: cmd.argumentHint
                        if (rightText != null) {
                            Text(
                                text = rightText,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = if (cmd.description == null) FontFamily.Monospace else FontFamily.Default,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveSlashCommandBar(
    active: ActiveSlashCommand,
    onViewFile: ((HostFileTarget) -> Unit)?,
    onClearCommand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cmd = active.command
    val fileTarget = cmd.uri?.let { HostFileTarget.of(it) }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag("composer_active_skill"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (cmd.kind == SlashCommandKind.SKILL) AppIcons.Lightbulb else AppIcons.Slash,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "/${cmd.name}",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = cmd.argumentHint ?: cmd.description
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Box(Modifier.weight(1f))
            }
            if (fileTarget != null && onViewFile != null) {
                TextButton(
                    onClick = { onViewFile(fileTarget) },
                    modifier = Modifier.testTag("composer_active_skill_view"),
                ) {
                    Text("View file", style = MaterialTheme.typography.labelSmall)
                }
            }
            IconButton(
                onClick = onClearCommand,
                modifier = Modifier.size(32.dp).testTag("composer_active_skill_clear"),
            ) {
                Icon(
                    imageVector = AppIcons.Clear,
                    contentDescription = "Remove slash command",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun AgentMentionPopup(
    mention: AgentMentionQuery,
    onSelect: (AgentChoice) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .testTag("composer_agent_mention_menu"),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .padding(vertical = 4.dp),
        ) {
            items(mention.matches, key = { it.uri }) { agent ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onSelect(agent) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("agent_mention_${agent.name}"),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = AppIcons.AgentCode,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "@${agent.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (agent.description != null) Modifier.widthIn(max = 190.dp) else Modifier,
                    )
                    agent.description?.let { desc ->
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickReplyBar(
    suggestion: QuickReplySuggestion,
    aiLoading: Boolean,
    selectedPrompt: String,
    onSelectChip: (QuickReplyChip) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .testTag("quick_reply_bar"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    val hasError = suggestion.error != null
                    Icon(
                        imageVector = if (hasError) AppIcons.ErrorCircle else AppIcons.Sparkle,
                        contentDescription = null,
                        tint = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    val headerText = when {
                        aiLoading -> "Quick replies · Thinking…"
                        hasError -> "Quick replies · ${suggestion.providerLabel ?: "AI"} error (using smart extract)"
                        !suggestion.providerLabel.isNullOrBlank() -> "Quick replies · ${suggestion.providerLabel}"
                        suggestion.source == QuickReplySource.GEMINI_NANO -> "Quick replies · Gemini Nano"
                        suggestion.source == QuickReplySource.CLOUD_AI -> "Quick replies · External AI"
                        else -> "Quick replies"
                    }
                    Text(
                        text = headerText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("quick_reply_source"),
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp).testTag("quick_reply_dismiss"),
                ) {
                    Icon(
                        imageVector = AppIcons.Clear,
                        contentDescription = "Dismiss quick replies",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            suggestion.error?.let { err ->
                var errorExpanded by rememberSaveable(suggestion.turnKey, err) { mutableStateOf(false) }
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = if (errorExpanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { errorExpanded = !errorExpanded }
                        .padding(end = 8.dp, bottom = 2.dp)
                        .testTag("quick_reply_error"),
                )
            }
            suggestion.summary?.let { summary ->
                var summaryExpanded by rememberSaveable(suggestion.turnKey) { mutableStateOf(false) }
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (summaryExpanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { summaryExpanded = !summaryExpanded }
                        .padding(end = 8.dp, bottom = 2.dp)
                        .testTag("quick_reply_summary"),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                suggestion.chips.forEachIndexed { index, chip ->
                    val selected = selectedPrompt == chip.prompt
                    AssistChip(
                        onClick = { onSelectChip(chip) },
                        label = {
                            Text(
                                text = chip.label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            labelColor = if (selected) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        ),
                        border = null,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("quick_reply_chip_$index"),
                    )
                }
            }
        }
    }
}

@Composable
internal fun Composer(
    actions: ChatActionsUi,
    modifier: Modifier = Modifier,
    pendingMessages: List<PendingMessageView> = emptyList(),
    activeTurnId: String? = null,
    draftToLoad: String? = null,
    onDraftLoaded: () -> Unit = {},
    quickReplyContext: QuickReplyContext? = null,
) {
    var text by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(draftToLoad) {
        if (draftToLoad != null) {
            if (text.isBlank()) text = draftToLoad
            onDraftLoaded()
        }
    }
    var pick by rememberSaveable(stateSaver = ModelPickSaver) { mutableStateOf<ModelChoice?>(null) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var agentPick by rememberSaveable(stateSaver = AgentPickSaver) { mutableStateOf<AgentPick>(AgentPick.Keep) }
    var choosingAgent by rememberSaveable { mutableStateOf(false) }
    var sendMenuOpen by remember { mutableStateOf(false) }
    var showSlashPicker by rememberSaveable { mutableStateOf(false) }
    var slashPickerInitialQuery by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(actions.prefillText) {
        if (actions.prefillText != null) {
            text = actions.prefillText
            actions.onPrefillConsumed()
        }
    }
    LaunchedEffect(actions.agentToUse) {
        val uri = actions.agentToUse
        if (uri != null) {
            agentPick = if (uri == actions.agentInUse) AgentPick.Keep else AgentPick.Use(uri)
            actions.onAgentToUseConsumed()
        }
    }
    // A pick the host no longer offers is dropped, so the box never shows or sends a model that has gone.
    val chosen = pick?.takeIf { ModelOptions.isOffered(actions.models, it) } ?: actions.modelInUse
    // The same for an agent: a pick that is gone becomes "keep what the chat is on". What is shown is what a message is sent with.
    val agentChosen: AgentPick = agentPick.takeIf { it !is AgentPick.Use || actions.agents.any { a -> a.uri == it.uri } } ?: AgentPick.Keep
    val agentUri: String? = when (val p = agentChosen) {
        AgentPick.Keep -> actions.agentInUse
        AgentPick.None -> null
        is AgentPick.Use -> p.uri
    }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val canSend = text.isNotBlank() && !busy
    val chipColors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
    val effectiveCommands = remember(actions.slashCommands, actions.canFork, actions.canSideChat) {
        actions.slashCommands.ifEmpty {
            SlashCommands.builtInCommands(canFork = actions.canFork, canSideChat = actions.canSideChat)
        }
    }
    val slashMatches = remember(text, effectiveCommands) {
        SlashCommands.match(text, effectiveCommands)
    }
    val activeSlashCommand = remember(text, effectiveCommands) {
        SlashCommands.active(text, effectiveCommands)
    }
    val agentMention = remember(text, actions.agents) {
        SlashCommands.matchAgentMention(text, actions.agents)
    }
    val executeBuiltInCommand: (SlashCommand, String) -> Boolean = { cmd, args ->
        when (cmd.name.lowercase()) {
            "customizations" -> {
                actions.onOpenCustomizations?.invoke(null, args)
                text = ""
                true
            }
            "skills", "skills list" -> {
                actions.onOpenCustomizations?.invoke(CustomizationCategory.SKILLS, args)
                text = ""
                true
            }
            "skills info" -> {
                val targetSkill = effectiveCommands.firstOrNull {
                    it.kind == SlashCommandKind.SKILL && it.name.equals(args.trim(), ignoreCase = true)
                }
                val fileTarget = targetSkill?.uri?.let { HostFileTarget.of(it) }
                if (fileTarget != null && actions.onViewHostFile != null) {
                    actions.onViewHostFile.invoke(fileTarget)
                    text = ""
                    true
                } else if (actions.onOpenCustomizations != null) {
                    actions.onOpenCustomizations.invoke(CustomizationCategory.SKILLS, args.trim())
                    text = ""
                    true
                } else {
                    false
                }
            }
            "mcp" -> {
                actions.onOpenCustomizations?.invoke(CustomizationCategory.MCP_SERVERS, args)
                text = ""
                true
            }
            "tools" -> {
                actions.onOpenCustomizations?.invoke(CustomizationCategory.TOOLS, args)
                text = ""
                true
            }
            "clear" -> {
                text = ""
                scope.launch {
                    busy = true
                    notice = InteractMessages.describe(actions.startOver())
                    busy = false
                }
                true
            }
            "agent" -> {
                val arg = args.trim()
                when {
                    arg.equals("default", ignoreCase = true) || arg.equals("none", ignoreCase = true) -> {
                        agentPick = AgentPick.None
                        text = ""
                        true
                    }
                    arg.isNotEmpty() -> {
                        val matched = actions.agents.firstOrNull { it.name.equals(arg, ignoreCase = true) }
                        if (matched != null) {
                            agentPick = if (matched.uri == actions.agentInUse) AgentPick.Keep else AgentPick.Use(matched.uri)
                            text = ""
                            true
                        } else {
                            choosingAgent = true
                            text = ""
                            true
                        }
                    }
                    actions.agents.isNotEmpty() || actions.agentInUse != null || actions.config.controls.isNotEmpty() -> {
                        choosingAgent = true
                        text = ""
                        true
                    }
                    else -> false
                }
            }
            "model" -> {
                val arg = args.trim()
                if (arg.isNotEmpty()) {
                    val matched = actions.models.firstOrNull {
                        it.id.equals(arg, ignoreCase = true) || it.name.equals(arg, ignoreCase = true)
                    }
                    if (matched != null) {
                        pick = ModelChoice(matched.id)
                        text = ""
                        true
                    } else {
                        choosing = true
                        text = ""
                        true
                    }
                } else if (actions.models.isNotEmpty()) {
                    choosing = true
                    text = ""
                    true
                } else {
                    false
                }
            }
            "fork" -> if (actions.canFork) {
                val forkText = args.trim()
                text = ""
                scope.launch {
                    busy = true
                    val res = if (forkText.isEmpty() && activeTurnId != null) {
                        actions.forkFromTurn(activeTurnId)
                    } else {
                        actions.editAsFork(activeTurnId, forkText, chosen, agentChosen)
                    }
                    notice = ManageMessages.describe(res)
                    busy = false
                }
                true
            } else {
                false
            }
            "side" -> if (actions.canSideChat && activeTurnId != null) {
                val sideText = args.trim().ifEmpty { null }
                text = ""
                scope.launch {
                    busy = true
                    val res = actions.openSideChat(activeTurnId, sideText, chosen, agentChosen)
                    notice = ManageMessages.describe(res)
                    busy = false
                }
                true
            } else {
                false
            }
            else -> false
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        modifier = modifier.fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)) {
            ConfigStateRow(actions.config, onClick = { choosingAgent = true }, chipColors = chipColors, modifier = Modifier.padding(top = 4.dp))
            PendingMessageTray(
                pendingMessages = pendingMessages,
                busy = busy,
                onEdit = { msg ->
                    scope.launch {
                        busy = true
                        val result = actions.removePending(msg.id)
                        notice = InteractMessages.describe(result)
                        if (InteractMessages.succeeded(result)) {
                            text = if (text.isBlank()) msg.text else "${msg.text}\n$text"
                        }
                        busy = false
                    }
                },
                onRemove = { msg ->
                    scope.launch {
                        busy = true
                        notice = InteractMessages.describe(actions.removePending(msg.id))
                        busy = false
                    }
                },
                onMoveQueue = { fromIndex, toIndex ->
                    val queuedIds = pendingMessages.filter { it.kind == PendingMessageKind.QUEUED }.map { it.id }.toMutableList()
                    if (fromIndex in queuedIds.indices && toIndex in queuedIds.indices) {
                        val moved = queuedIds.removeAt(fromIndex)
                        queuedIds.add(toIndex, moved)
                        scope.launch {
                            busy = true
                            notice = InteractMessages.describe(actions.reorderQueue(queuedIds))
                            busy = false
                        }
                    }
                },
            )
            Notice(notice, "composer_notice", Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            if (slashMatches.isNotEmpty()) {
                SlashCommandPopup(
                    matches = slashMatches,
                    onSelect = { cmd ->
                        if (cmd.kind == SlashCommandKind.BUILT_IN && cmd.argumentHint == null) {
                            executeBuiltInCommand(cmd, "")
                        } else {
                            text = "/${cmd.name} "
                        }
                    },
                    onOpenCustomizations = actions.onOpenCustomizations?.let { open -> { open(null, "") } },
                    onShowAll = {
                        slashPickerInitialQuery = text.removePrefix("/").trim()
                        showSlashPicker = true
                    },
                )
            } else if (agentMention != null) {
                AgentMentionPopup(
                    mention = agentMention,
                    onSelect = { pickedAgent ->
                        agentPick = if (pickedAgent.uri == actions.agentInUse) AgentPick.Keep else AgentPick.Use(pickedAgent.uri)
                        text = SlashCommands.stripAgentMention(text, agentMention)
                    },
                )
            }
            if (showSlashPicker) {
                SlashCommandPickerSheet(
                    commands = effectiveCommands,
                    initialQuery = slashPickerInitialQuery,
                    onDismiss = { showSlashPicker = false },
                    onSelect = { cmd ->
                        if (cmd.kind == SlashCommandKind.BUILT_IN && cmd.argumentHint == null) {
                            executeBuiltInCommand(cmd, "")
                        } else {
                            text = "/${cmd.name} "
                        }
                    },
                    onViewFile = actions.onViewHostFile,
                    onOpenCustomizations = actions.onOpenCustomizations?.let { open -> { open(null, "") } },
                )
            }
            if (activeSlashCommand != null && slashMatches.isEmpty()) {
                ActiveSlashCommandBar(
                    active = activeSlashCommand,
                    onViewFile = actions.onViewHostFile,
                    onClearCommand = {
                        text = activeSlashCommand.arguments
                    },
                )
            }
            val qrContext = if (!actions.running && actions.quickReplyMode != QuickReplyMode.OFF) quickReplyContext else null
            var qrDismissed by rememberSaveable(qrContext?.turnKey) { mutableStateOf(false) }
            var qrRequested by rememberSaveable(qrContext?.turnKey) { mutableStateOf(false) }
            val showQuickReplyBar = qrContext != null && !qrDismissed &&
                (actions.quickReplyMode == QuickReplyMode.AUTO || qrRequested) &&
                slashMatches.isEmpty() && agentMention == null
            if (showQuickReplyBar && qrContext != null) {
                val heuristicSuggestion = remember(qrContext) { QuickReplies.heuristic(qrContext) }
                val provider = actions.quickReplyProvider
                val cacheKey = actions.quickReplyCacheKey
                var aiSuggestion by remember(qrContext.turnKey, provider, cacheKey) { mutableStateOf<QuickReplySuggestion?>(null) }
                var aiLoading by remember(qrContext.turnKey, provider, cacheKey) { mutableStateOf(false) }
                LaunchedEffect(qrContext, provider, cacheKey) {
                    if (provider != null && aiSuggestion == null) {
                        aiLoading = true
                        val generated = provider.suggest(qrContext)
                        if (generated != null && generated.chips.isNotEmpty()) {
                            aiSuggestion = generated
                        }
                        aiLoading = false
                    }
                }
                QuickReplyBar(
                    suggestion = aiSuggestion ?: heuristicSuggestion,
                    aiLoading = aiLoading,
                    selectedPrompt = text,
                    onSelectChip = { chip -> text = chip.prompt },
                    onDismiss = { qrDismissed = true },
                )
            }
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Message") },
                maxLines = 5,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    errorIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().testTag("composer_input"),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (qrContext != null && !showQuickReplyBar) {
                        AssistChip(
                            onClick = {
                                qrDismissed = false
                                qrRequested = true
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = AppIcons.Sparkle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            label = {
                                Text(
                                    text = "Suggest",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
                            },
                            colors = chipColors,
                            border = null,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .semantics { contentDescription = "Suggest quick replies" }
                                .testTag("quick_reply_suggest_button"),
                        )
                    }
                    if (actions.slashCommands.isNotEmpty() || actions.onOpenCustomizations != null) {
                        AssistChip(
                            onClick = {
                                text = if (text == "/") "" else if (text.startsWith("/")) text else "/$text"
                            },
                            label = {
                                Text(
                                    text = "/",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                )
                            },
                            colors = chipColors,
                            border = null,
                            modifier = Modifier
                                .semantics { contentDescription = "Slash commands" }
                                .testTag("slash_button"),
                        )
                    }
                    if (actions.agents.isNotEmpty() || actions.agentInUse != null || actions.config.controls.isNotEmpty()) {
                        AssistChip(
                            onClick = { choosingAgent = true },
                            label = { Text(agentLabel(actions.agents, agentUri), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = chipColors,
                            border = null,
                            // Capped, so a long name cannot take the model's room; the model button gets the rest.
                            modifier = Modifier.widthIn(max = 150.dp).semantics { contentDescription = "Agent: ${agentLabel(actions.agents, agentUri)}" }.testTag("agent_button"),
                        )
                        if (choosingAgent) {
                            ChatOptionsDialog(
                                actions.agents,
                                agentUri,
                                showAgents = actions.agents.isNotEmpty() || actions.agentInUse != null,
                                config = actions.config,
                                onDismiss = { choosingAgent = false },
                                onPick = { uri, changes ->
                                    // Choosing what the chat is on is "keep", so a change made elsewhere is not undone by the next message.
                                    agentPick = when (uri) {
                                        actions.agentInUse -> AgentPick.Keep
                                        null -> AgentPick.None
                                        else -> AgentPick.Use(uri)
                                    }
                                    choosingAgent = false
                                    if (changes.isNotEmpty()) {
                                        scope.launch {
                                            busy = true
                                            // One after the other: a setting that is refused (or a device check that is not passed) stops the rest.
                                            for ((key, value) in changes) {
                                                val result = actions.setConfig(key, value)
                                                notice = InteractMessages.describe(result)
                                                if (!InteractMessages.succeeded(result)) break
                                            }
                                            busy = false
                                        }
                                    }
                                },
                            )
                        }
                    }
                    if (actions.models.isNotEmpty()) {
                        AssistChip(
                            onClick = { choosing = true },
                            label = { Text(modelLabel(actions.models, chosen), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = chipColors,
                            border = null,
                            modifier = Modifier.weight(1f, fill = false).semantics { contentDescription = "Model: ${modelLabel(actions.models, chosen)}" }.testTag("model_button"),
                        )
                        if (choosing) ModelPickerDialog(actions.models, chosen, showDefault = actions.modelInUse == null, onDismiss = { choosing = false }, onPick = { pick = it; choosing = false })
                    }
                }
                if (actions.running && text.isNotBlank()) {
                    // Steer reaches the turn that is running now; the arrow queues the message to wait for it to end.
                    AssistChip(
                        onClick = {
                            val sending = text
                            scope.launch {
                                busy = true
                                val result = actions.steer(sending, chosen, agentChosen)
                                notice = InteractMessages.describe(result)
                                if (InteractMessages.succeeded(result) && text == sending) text = ""
                                busy = false
                            }
                        },
                        enabled = !busy,
                        label = { Text("Steer now") },
                        colors = chipColors,
                        border = null,
                        modifier = Modifier.testTag("composer_steer"),
                    )
                    Box {
                        IconButton(
                            onClick = { sendMenuOpen = true },
                            enabled = !busy,
                            modifier = Modifier.size(48.dp).testTag("composer_send_options"),
                        ) {
                            Icon(AppIcons.MoreVert, contentDescription = "Send options")
                        }
                        DropdownMenu(
                            expanded = sendMenuOpen,
                            onDismissRequest = { sendMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Steer with Message") },
                                onClick = {
                                    sendMenuOpen = false
                                    val sending = text
                                    scope.launch {
                                        busy = true
                                        val result = actions.steer(sending, chosen, agentChosen)
                                        notice = InteractMessages.describe(result)
                                        if (InteractMessages.succeeded(result) && text == sending) text = ""
                                        busy = false
                                    }
                                },
                                modifier = Modifier.testTag("composer_mode_steer"),
                            )
                            DropdownMenuItem(
                                text = { Text("Add to Queue") },
                                onClick = {
                                    sendMenuOpen = false
                                    val sending = text
                                    scope.launch {
                                        busy = true
                                        val currentActive = activeSlashCommand
                            if (currentActive != null && currentActive.command.kind == SlashCommandKind.BUILT_IN && executeBuiltInCommand(currentActive.command, currentActive.arguments)) {
                                busy = false
                                return@launch
                            }
                            val payload = if (currentActive != null && currentActive.arguments.isEmpty() && sending.endsWith(" ")) sending.trimEnd() else sending
                            val result = actions.send(payload, chosen, agentChosen)
                                        notice = InteractMessages.describe(result)
                                        if (InteractMessages.succeeded(result) && text == sending) text = ""
                                        busy = false
                                    }
                                },
                                modifier = Modifier.testTag("composer_mode_queue"),
                            )
                            DropdownMenuItem(
                                text = { Text("Stop and Send") },
                                onClick = {
                                    sendMenuOpen = false
                                    val sending = text
                                    scope.launch {
                                        busy = true
                                        val result = actions.stopAndSend(sending, chosen, agentChosen)
                                        notice = InteractMessages.describe(result)
                                        if (InteractMessages.succeeded(result) && text == sending) text = ""
                                        busy = false
                                    }
                                },
                                modifier = Modifier.testTag("composer_mode_stop_and_send"),
                            )
                            if (actions.canSideChat && activeTurnId != null) {
                                DropdownMenuItem(
                                    text = { Text("Ask in Side Chat") },
                                    onClick = {
                                        sendMenuOpen = false
                                        val sending = text
                                        scope.launch {
                                            busy = true
                                            val result = actions.openSideChat(activeTurnId, sending, chosen, agentChosen)
                                            notice = ManageMessages.describe(result)
                                            if (result is ManageResult.Done && text == sending) text = ""
                                            busy = false
                                        }
                                    },
                                    modifier = Modifier.testTag("composer_mode_side_chat"),
                                )
                            }
                        }
                    }
                }
                if (actions.running) {
                    FilledTonalIconButton(
                        onClick = { scope.launch { busy = true; notice = InteractMessages.describe(actions.cancel()); busy = false } },
                        enabled = !busy,
                        modifier = Modifier.size(48.dp).testTag("composer_stop"),
                    ) { Icon(AppIcons.Stop, contentDescription = "Stop the agent") }
                }
                FilledIconButton(
                    onClick = {
                        val sending = text
                        scope.launch {
                            busy = true
                            val currentActive = activeSlashCommand
                            if (currentActive != null && currentActive.command.kind == SlashCommandKind.BUILT_IN && executeBuiltInCommand(currentActive.command, currentActive.arguments)) {
                                busy = false
                                return@launch
                            }
                            val payload = if (currentActive != null && currentActive.arguments.isEmpty() && sending.endsWith(" ")) sending.trimEnd() else sending
                            val result = actions.send(payload, chosen, agentChosen)
                            notice = InteractMessages.describe(result)
                            // Only a message the host has taken is cleared, and only if the box still holds what was sent.
                            if (InteractMessages.succeeded(result) && text == sending) text = ""
                            busy = false
                        }
                    },
                    enabled = canSend,
                    modifier = Modifier.size(48.dp).testTag("composer_send"),
                ) { Icon(AppIcons.ArrowUp, contentDescription = if (actions.running) "Queue" else "Send") }
            }
        }
    }
}

/**
 * The buttons on a tool call (or, with [forResult], its output) that is waiting for the person: the host's own options if
 * it offered any, else Approve and Deny. Approving asks for the device check first; the screen cannot skip it, because
 * that lives behind the interactor.
 */
@Composable
internal fun ApprovalButtons(choices: List<ApprovalChoice>, toolCallId: String, forResult: Boolean, actions: ChatActionsUi, modifier: Modifier = Modifier) {
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { choice ->
            val run = {
                scope.launch {
                    busy = true
                    val result = when {
                        forResult && choice.approves -> actions.approveResult(toolCallId)
                        forResult -> actions.denyResult(toolCallId)
                        choice.approves -> actions.approve(toolCallId, choice.optionId)
                        else -> actions.deny(toolCallId, choice.optionId)
                    }
                    notice = InteractMessages.describe(result)
                    busy = false
                }
                Unit
            }
            val tag = (if (choice.approves) "approve_" else "deny_") + (choice.optionId ?: if (forResult) "result" else "plain")
            val label = (if (forResult) (if (choice.approves) "Let the agent see the result" else "Keep the result back") else choice.label).take(MAX_LABEL)
            if (choice.approves) {
                Button(onClick = run, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) { Text(label) }
            } else {
                OutlinedButton(onClick = run, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) { Text(label) }
            }
        }
        Notice(notice, "approval_notice")
    }
}

/**
 * A question the agent is waiting on, as a form. Nothing is submitted until the person taps Send answer and every field
 * passes the checks in [InputForm.check]; the problems are shown against the fields that have them. While it is filled in,
 * what has been entered so far is sent to the host as a draft after a short pause, one question at a time, so another
 * screen showing the same question can follow along. Drafts are best effort and never shown as errors.
 */
@Composable
internal fun QuestionForm(form: InputForm, actions: ChatActionsUi, modifier: Modifier = Modifier) {
    val values = remember(form.requestId) { mutableStateMapOf<String, FormValue>().apply { form.questions.forEach { put(it.id, form.initial(it)) } } }
    var problems by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    // Once an answer, a decline or a dismissal is on its way, a late draft must not follow it.
    var finished by remember(form.requestId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val currentActions by rememberUpdatedState(actions)
    LaunchedEffect(form.requestId) {
        // What the host already holds is not sent back.
        val sent = HashMap<String, AnswerValue?>().apply { form.questions.forEach { put(it.id, form.draftFor(it, form.initial(it))) } }
        snapshotFlow { values.toMap() }.collectLatest { now ->
            delay(DRAFT_PAUSE_MS)
            if (finished) return@collectLatest
            for (question in form.questions) {
                val draft = form.draftFor(question, now[question.id] ?: continue)
                if (draft != sent[question.id]) {
                    sent[question.id] = draft
                    currentActions.saveDraft(form.requestId, question.id, draft)
                }
            }
        }
    }
    fun finish(call: suspend () -> InteractResult) {
        finished = true
        scope.launch {
            busy = true
            val result = call()
            notice = InteractMessages.describe(result)
            // A refusal leaves the question waiting, so drafts may resume.
            if (!InteractMessages.succeeded(result)) finished = false
            busy = false
        }
    }
    val message = form.message?.take(MAX_QUESTION_TEXT)?.takeIf {
        it.isNotBlank() && (form.questions.size > 1 || (it != form.questions.firstOrNull()?.title && it != form.questions.firstOrNull()?.heading))
    }
    Card(modifier = modifier.fillMaxWidth().testTag("question_form")) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Question", style = MaterialTheme.typography.labelLarge)
            if (message != null) {
                Text(message, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("question_message"))
            }
            form.questions.forEachIndexed { index, question ->
                if (index > 0 || message != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                QuestionField(question, values[question.id] ?: form.initial(question), problems[question.id]) {
                    values[question.id] = it
                    problems = problems - question.id
                }
            }
            Notice(notice, "question_notice")
            Button(
                onClick = {
                    when (val check = form.check(values)) {
                        is FormCheck.Problems -> problems = check.byQuestion
                        is FormCheck.Ready -> finish { actions.answer(form.requestId, check.answers) }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("question_submit"),
            ) { Text("Send answer") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { finish { actions.decline(form.requestId) } }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("question_decline")) { Text("Decline") }
                OutlinedButton(onClick = { finish { actions.dismiss(form.requestId) } }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("question_dismiss")) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun QuestionField(question: FormQuestion, value: FormValue, problem: String?, onChange: (FormValue) -> Unit) {
    val title = question.title.take(MAX_QUESTION_TEXT).ifBlank { "Question" }
    val heading = question.heading?.take(MAX_OPTION_TEXT)?.takeIf { it.isNotBlank() }
    when (question.kind) {
        QuestionKind.TEXT, QuestionKind.NUMBER -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            heading?.let { Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
            OutlinedTextField(
                value = (value as? FormValue.Text)?.text.orEmpty(),
                onValueChange = { onChange(FormValue.Text(it)) },
                label = { Text(title) },
                isError = problem != null,
                supportingText = problem?.let { { Text(it) } },
                keyboardOptions = if (question.kind == QuestionKind.NUMBER) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth().testTag("field_${question.id}"),
            )
        }
        QuestionKind.BOOLEAN -> {
            val on = (value as? FormValue.Bool)?.value ?: false
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp)
                    .toggleable(value = on, role = Role.Switch, onValueChange = { onChange(FormValue.Bool(it)) })
                    .testTag("field_${question.id}"),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    heading?.let { Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                }
                Switch(checked = on, onCheckedChange = null)
            }
        }
        QuestionKind.SINGLE -> {
            val choice = value as? FormValue.Choice ?: FormValue.Choice(null)
            Column(modifier = Modifier.fillMaxWidth().testTag("field_${question.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                heading?.let { Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
                Text(title, style = if (heading != null) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleSmall)
                question.options.forEach { option ->
                    val optionLabel = option.label.take(MAX_OPTION_TEXT) + if (option.recommended) " (recommended)" else ""
                    val optionDesc = option.description?.take(MAX_OPTION_TEXT)?.takeIf { it.isNotBlank() }
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp)
                            .selectable(selected = choice.optionId == option.id, role = Role.RadioButton, onClick = { onChange(choice.copy(optionId = option.id)) })
                            .testTag("option_${question.id}_${option.id}"),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice.optionId == option.id, onClick = null)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(optionLabel, style = MaterialTheme.typography.bodyLarge)
                            optionDesc?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
                if (question.allowFreeform) {
                    OutlinedTextField(
                        value = choice.freeform,
                        onValueChange = { onChange(choice.copy(freeform = it)) },
                        label = { Text("Something else") },
                        modifier = Modifier.fillMaxWidth().testTag("freeform_${question.id}"),
                    )
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        QuestionKind.MULTI -> {
            val choices = value as? FormValue.Choices ?: FormValue.Choices(emptySet())
            Column(modifier = Modifier.fillMaxWidth().testTag("field_${question.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                heading?.let { Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
                Text(title, style = if (heading != null) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleSmall)
                question.options.forEach { option ->
                    val checked = option.id in choices.optionIds
                    val optionLabel = option.label.take(MAX_OPTION_TEXT) + if (option.recommended) " (recommended)" else ""
                    val optionDesc = option.description?.take(MAX_OPTION_TEXT)?.takeIf { it.isNotBlank() }
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp)
                            .toggleable(
                                value = checked,
                                role = Role.Checkbox,
                                onValueChange = { on -> onChange(choices.copy(optionIds = if (on) choices.optionIds + option.id else choices.optionIds - option.id)) },
                            )
                            .testTag("option_${question.id}_${option.id}"),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(optionLabel, style = MaterialTheme.typography.bodyLarge)
                            optionDesc?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
                if (question.allowFreeform) {
                    OutlinedTextField(
                        value = choices.freeform,
                        onValueChange = { onChange(choices.copy(freeform = it)) },
                        label = { Text("Something else") },
                        modifier = Modifier.fillMaxWidth().testTag("freeform_${question.id}"),
                    )
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
