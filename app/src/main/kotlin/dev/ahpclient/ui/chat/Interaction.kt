package dev.ahpclient.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.AnswerValue
import dev.ahpclient.state.ApprovalChoice
import dev.ahpclient.state.FormCheck
import dev.ahpclient.state.FormQuestion
import dev.ahpclient.state.FormValue
import dev.ahpclient.state.InputForm
import dev.ahpclient.state.InteractMessages
import dev.ahpclient.state.InteractResult
import dev.ahpclient.state.ModelChoice
import dev.ahpclient.state.ModelOption
import dev.ahpclient.state.ModelOptions
import dev.ahpclient.state.QuestionKind
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
    val send: suspend (text: String, model: ModelChoice?) -> InteractResult,
    val steer: suspend (text: String, model: ModelChoice?) -> InteractResult,
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
)

/** The host's own words on a button or a field are untrusted display text: shown as plain text and cut to a sane length. */
private const val MAX_LABEL = 80

/** How long a form waits after the last change before it tells the host what has been entered. */
private const val DRAFT_PAUSE_MS = 700L

/** Keeps a picked model across rotation: its id, then each setting as a key and a value. A null pick is an empty list. */
private val ModelPickSaver: Saver<ModelChoice?, Any> = listSaver(
    save = { choice -> if (choice == null) emptyList<String>() else listOf(choice.id) + choice.config.flatMap { listOf(it.key, it.value) } },
    restore = { saved -> if (saved.isEmpty()) null else ModelChoice(saved[0], saved.drop(1).chunked(2).filter { it.size == 2 }.associate { it[0] to it[1] }) },
)

/** What the model button says: the model's name and the labels of the settings picked for it, or that the host chooses. */
private fun modelLabel(models: List<ModelOption>, choice: ModelChoice?): String {
    val model = choice?.let { c -> models.firstOrNull { it.id == c.id } } ?: return "host default"
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
 * "Host default" sends no model at all. The names and labels are the host's words, shown as plain text.
 */
@Composable
private fun ModelPickerDialog(models: List<ModelOption>, current: ModelChoice?, onDismiss: () -> Unit, onPick: (ModelChoice?) -> Unit) {
    var draft by rememberSaveable(stateSaver = ModelPickSaver) { mutableStateOf(current) }
    val model = models.firstOrNull { it.id == draft?.id }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Model for your messages") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).testTag("model_dialog"),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                PickRow("Host default", draft == null, "model_default") { draft = null }
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
        },
        confirmButton = { TextButton(onClick = { onPick(draft) }, modifier = Modifier.testTag("model_use")) { Text("Use") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("model_cancel")) { Text("Cancel") } },
    )
}

@Composable
private fun Notice(text: String?, tag: String) {
    if (text == null) return
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * The message box, with Stop while the agent is working. The text stays until the host has confirmed the message, so a
 * failed send loses nothing, and it survives rotation.
 */
@Composable
internal fun Composer(actions: ChatActionsUi, modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf("") }
    var pick by rememberSaveable(stateSaver = ModelPickSaver) { mutableStateOf<ModelChoice?>(null) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    // A pick the host no longer offers is dropped, so the box never shows or sends a model that has gone.
    val chosen = pick?.takeIf { ModelOptions.isOffered(actions.models, it) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(
        modifier = modifier.fillMaxWidth().imePadding().padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Notice(notice, "composer_notice")
        if (actions.models.isNotEmpty()) {
            OutlinedButton(onClick = { choosing = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("model_button")) {
                Text("Model: ${modelLabel(actions.models, chosen)}")
            }
            if (choosing) ModelPickerDialog(actions.models, chosen, onDismiss = { choosing = false }, onPick = { pick = it; choosing = false })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Message") },
                maxLines = 5,
                modifier = Modifier.weight(1f).testTag("composer_input"),
            )
            Button(
                onClick = {
                    val sending = text
                    scope.launch {
                        busy = true
                        val result = actions.send(sending, chosen)
                        notice = InteractMessages.describe(result)
                        // Only a message the host has taken is cleared, and only if the box still holds what was sent.
                        if (InteractMessages.succeeded(result) && text == sending) text = ""
                        busy = false
                    }
                },
                enabled = text.isNotBlank() && !busy,
                modifier = Modifier.heightIn(min = 56.dp).testTag("composer_send"),
            ) { Text(if (actions.running) "Queue" else "Send") }
        }
        if (actions.running) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Steer reaches the turn that is running now; Queue (the main button) waits for it to end.
                OutlinedButton(
                    onClick = {
                        val sending = text
                        scope.launch {
                            busy = true
                            val result = actions.steer(sending, chosen)
                            notice = InteractMessages.describe(result)
                            if (InteractMessages.succeeded(result) && text == sending) text = ""
                            busy = false
                        }
                    },
                    enabled = text.isNotBlank() && !busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("composer_steer"),
                ) { Text("Steer now") }
                OutlinedButton(
                    onClick = { scope.launch { busy = true; notice = InteractMessages.describe(actions.cancel()); busy = false } },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("composer_stop"),
                ) { Text("Stop the agent") }
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
    Column(modifier = modifier.fillMaxWidth().testTag("question_form"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        form.questions.forEach { question ->
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

@Composable
private fun QuestionField(question: FormQuestion, value: FormValue, problem: String?, onChange: (FormValue) -> Unit) {
    val title = question.title.take(MAX_LABEL * 3).ifBlank { "Question" }
    when (question.kind) {
        QuestionKind.TEXT, QuestionKind.NUMBER -> OutlinedTextField(
            value = (value as? FormValue.Text)?.text.orEmpty(),
            onValueChange = { onChange(FormValue.Text(it)) },
            label = { Text(title) },
            isError = problem != null,
            supportingText = problem?.let { { Text(it) } },
            keyboardOptions = if (question.kind == QuestionKind.NUMBER) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth().testTag("field_${question.id}"),
        )
        QuestionKind.BOOLEAN -> {
            val on = (value as? FormValue.Bool)?.value ?: false
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = on, role = Role.Switch, onValueChange = { onChange(FormValue.Bool(it)) })
                    .testTag("field_${question.id}"),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = on, onCheckedChange = null)
            }
        }
        QuestionKind.SINGLE -> {
            val choice = value as? FormValue.Choice ?: FormValue.Choice(null)
            Column(modifier = Modifier.fillMaxWidth().testTag("field_${question.id}")) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                question.options.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = choice.optionId == option.id, role = Role.RadioButton, onClick = { onChange(choice.copy(optionId = option.id)) })
                            .testTag("option_${question.id}_${option.id}"),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice.optionId == option.id, onClick = null)
                        Text(option.label.take(MAX_LABEL), style = MaterialTheme.typography.bodyLarge)
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
            Column(modifier = Modifier.fillMaxWidth().testTag("field_${question.id}")) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                question.options.forEach { option ->
                    val checked = option.id in choices.optionIds
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
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
                        Text(option.label.take(MAX_LABEL), style = MaterialTheme.typography.bodyLarge)
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
