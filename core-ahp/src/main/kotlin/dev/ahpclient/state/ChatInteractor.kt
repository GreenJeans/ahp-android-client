package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.ChatInputRequest
import com.microsoft.agenthostprotocol.generated.ChatInteractivity
import com.microsoft.agenthostprotocol.generated.ChatState
import com.microsoft.agenthostprotocol.generated.ConfirmationOptionKind
import com.microsoft.agenthostprotocol.generated.ResponsePartInputRequest
import com.microsoft.agenthostprotocol.generated.ResponsePartToolCall
import com.microsoft.agenthostprotocol.generated.StateAction
import com.microsoft.agenthostprotocol.generated.ToolCallPendingConfirmationState
import com.microsoft.agenthostprotocol.generated.ToolCallState
import com.microsoft.agenthostprotocol.generated.ToolCallStatePendingConfirmation
import com.microsoft.agenthostprotocol.generated.ToolCallStatePendingResultConfirmation
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * What the person is about to allow. Shown by the device check; it is display text and never sent anywhere. [forResult] is
 * set when it is the agent seeing a tool's output rather than running the tool. [title] is the question the prompt asks, for
 * the few things that are not a tool call (starting or removing a session). [choice] is the label of the host's own option the
 * person picked ("Allow in this session"), so the prompt says how much is being allowed, not only which tool.
 */
data class ApprovalRequest(
    val toolName: String,
    val summary: String?,
    val forResult: Boolean = false,
    val title: String = TOOL_TITLE,
    val choice: String? = null,
) {
    /** What the prompt's description says: the picked option first, so a cut never hides it, then the tool's summary. */
    val description: String?
        get() = listOfNotNull(choice?.takeIf { it.isNotBlank() }?.let { "Your choice: ${it.take(MAX_CHOICE)}" }, summary).joinToString("\n").ifBlank { null }

    companion object {
        const val TOOL_TITLE = "Approve this tool call?"
        private const val MAX_CHOICE = 60
    }
}

/**
 * The device check that stands in front of every tool approval: a fingerprint, face or screen-lock prompt on the phone.
 * It answers true only when the person has just proved they are the one holding the phone. Anything else (cancelled, no
 * screen lock set, an error) is false, and a false answer means nothing is sent.
 */
fun interface ApprovalGate {
    suspend fun confirm(request: ApprovalRequest): Boolean
}

/** One button on a tool call that is waiting for the person. [optionId] is the host's own id for it, or null for a plain approve or deny. */
data class ApprovalChoice(val optionId: String?, val label: String, val approves: Boolean)

/** A tool call waiting for the person, as a screen needs it: which call, and the buttons to offer. [forResult] means it is the tool's output that is waiting to be approved, not the call. */
data class WaitingCall(val toolCallId: String, val choices: List<ApprovalChoice>, val forResult: Boolean = false)

/** A tool call (or, with [forResult], its output) the host is holding until the person decides. */
data class PendingApproval(
    val turnId: String,
    val toolCallId: String,
    val toolName: String,
    val summary: String?,
    val choices: List<ApprovalChoice>,
    val forResult: Boolean = false,
) {
    companion object {
        private val PLAIN_CHOICES = listOf(ApprovalChoice(null, "Approve", approves = true), ApprovalChoice(null, "Deny", approves = false))

        /** The id and the buttons for a call (or its result) that is waiting to be decided, or null for a call in any other state. */
        fun waitingFor(call: ToolCallState?): WaitingCall? = when (call) {
            is ToolCallStatePendingConfirmation -> WaitingCall(call.value.toolCallId, from("", call.value).choices)
            is ToolCallStatePendingResultConfirmation -> WaitingCall(call.value.toolCallId, PLAIN_CHOICES, forResult = true)
            else -> null
        }

        /** Finds the call in the running turn: only the running turn can hold one, and a stale id finds nothing. */
        fun find(chat: ChatState, toolCallId: String): PendingApproval? {
            val turn = chat.activeTurn ?: return null
            val call = turn.responseParts
                .filterIsInstance<ResponsePartToolCall>()
                .map { it.value.toolCall }
                .filterIsInstance<ToolCallStatePendingConfirmation>()
                .map { it.value }
                .firstOrNull { it.toolCallId == toolCallId } ?: return null
            return from(turn.id, call)
        }

        /** Finds a call whose output is waiting to be approved, in the running turn. */
        fun findResult(chat: ChatState, toolCallId: String): PendingApproval? {
            val turn = chat.activeTurn ?: return null
            val call = turn.responseParts
                .filterIsInstance<ResponsePartToolCall>()
                .map { it.value.toolCall }
                .filterIsInstance<ToolCallStatePendingResultConfirmation>()
                .map { it.value }
                .firstOrNull { it.toolCallId == toolCallId } ?: return null
            return PendingApproval(
                turnId = turn.id,
                toolCallId = call.toolCallId,
                toolName = call.displayName.ifBlank { call.toolName },
                summary = call.intention?.takeIf { it.isNotBlank() } ?: SessionDetail.plain(call.invocationMessage)?.takeIf { it.isNotBlank() },
                choices = PLAIN_CHOICES,
                forResult = true,
            )
        }

        fun from(turnId: String, call: ToolCallPendingConfirmationState): PendingApproval {
            val offered = call.options.orEmpty().map { ApprovalChoice(it.id, it.label, approves = it.kind == ConfirmationOptionKind.APPROVE) }
            val choices = offered.ifEmpty { PLAIN_CHOICES }
            return PendingApproval(
                turnId = turnId,
                toolCallId = call.toolCallId,
                toolName = call.displayName.ifBlank { call.toolName },
                summary = call.intention?.takeIf { it.isNotBlank() } ?: SessionDetail.plain(call.invocationMessage)?.takeIf { it.isNotBlank() },
                choices = choices,
            )
        }
    }
}

/** What happened to one thing the person tried to do. Everything but [Sent] means nothing reached the host. */
sealed interface InteractResult {
    /** The host answered, or did not: see [DispatchOutcome] for what that means. */
    data class Sent(val outcome: DispatchOutcome) : InteractResult

    /** This connection cannot act, or the host shows this chat as read only or hidden. */
    data object NotAllowed : InteractResult

    /** There was nothing to send. */
    data object Blank : InteractResult

    /** There is no running turn to stop. */
    data object NoActiveTurn : InteractResult

    /** The tool call or option is not (or no longer) waiting for a decision. */
    data object NoSuchRequest : InteractResult

    /** The person did not pass the device check, so the approval was not sent. */
    data object NotConfirmed : InteractResult

    /** The model the person picked is not one the host offers (any more), so nothing was sent. */
    data object InvalidModel : InteractResult

    /** The custom agent the person picked is not one the host offers (any more), so nothing was sent. */
    data object InvalidAgent : InteractResult

    /** The mode or approvals the person picked is not one the host offers or lets this chat change (any more), so nothing was sent. */
    data object InvalidConfig : InteractResult

    /** Sending failed before anything left the phone (offline, or the connection was just lost). Safe to try again. */
    data class NotSent(val cause: Throwable) : InteractResult

    /** A notification button was pressed with no live connection, and none could be made in time. Nothing was sent; the person has to open the app. */
    data object NotConnected : InteractResult
}

/**
 * What the person can do in one open chat: send a message, stop the running turn, answer the agent's question, and
 * approve or deny a tool call. It chooses the action, checks the preconditions, and hands the action to [dispatch].
 * The screen shows nothing as done until the host has echoed it, so there is no local state to undo.
 *
 * Approving is the only thing that lets the agent do something on the user's machine, so it is the only one behind the
 * [gate]. Denying never needs it: refusing is always safe.
 */
class ChatInteractor(
    private val chatUri: String,
    private val chat: () -> ChatState?,
    private val canInteract: () -> Boolean,
    private val dispatch: suspend (channel: String, action: StateAction) -> DispatchOutcome,
    private val gate: ApprovalGate,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Instant = Instant::now,
    /** The models the host offers for this chat's agent. A message names a model only if it is one of these. */
    private val models: () -> List<ModelOption> = { emptyList() },
    /** The custom agents the host offers for this chat's session. A message names an agent only if it is one of these, or the one the chat is already on. */
    private val agents: () -> List<AgentChoice> = { emptyList() },
    /** This chat's mode and approvals as the host reports them now, and which of them this app may change. */
    private val config: () -> ChatConfigView = { ChatConfigView.NONE },
    /** The model id the host reports for this chat's session (E6), used when the chat itself has not named a model yet. */
    private val sessionModel: () -> String? = { null },
) {
    /** The models a message in this chat may be sent with, as the host offers them now. */
    fun modelOptions(): List<ModelOption> = models()

    /** The model in use in this chat now: from the running or latest turn, the chat's draft, or the session's model (E6). */
    fun modelInUse(): ModelChoice? = ModelOptions.inUse(chat(), models(), sessionModel())

    /** The custom agents a message in this chat may be sent with, as the host offers them now. */
    fun agentChoices(): List<AgentChoice> = agents()

    /** This chat's mode and approvals now. */
    fun configView(): ChatConfigView = config()

    /**
     * Sets this chat's mode or approvals. Only a setting the host shows for this chat, lets the chat change for itself and offers that value
     * for (and allows now) is sent, and only when it differs from what is in force. Giving the agent more freedom (Allow all, Assisted,
     * Autopilot) goes through the device check first, in this app's own words, and is looked up again after it; taking freedom away never
     * does, like denying a tool call. Nothing counts as done until the host echoes it, so what the screen shows afterwards is what the host holds.
     */
    suspend fun setConfig(key: String, value: String): InteractResult {
        usable() ?: return InteractResult.NotAllowed
        val control = config().control(key) ?: return InteractResult.InvalidConfig
        if (!control.writable || !control.offers(value)) return InteractResult.InvalidConfig
        if (control.current == value) return InteractResult.Blank
        if (control.loosens(value)) {
            if (!confirmed(ApprovalRequest(toolName = ChatConfigPrompt.name(control, value), summary = ChatConfigPrompt.SUMMARY, title = ChatConfigPrompt.TITLE))) {
                return InteractResult.NotConfirmed
            }
            // The person took their time: look again, so a setting the host withdrew meanwhile is not sent blind.
            if (usable() == null) return InteractResult.NotAllowed
            val now = config().control(key) ?: return InteractResult.InvalidConfig
            if (!now.writable || !now.offers(value)) return InteractResult.InvalidConfig
        }
        return deliver(ChatConfigActions.set(key, value))
    }

    suspend fun send(text: String, model: ModelChoice? = null, agent: AgentPick = AgentPick.Keep): InteractResult {
        val state = usable() ?: return InteractResult.NotAllowed
        if (text.isBlank()) return InteractResult.Blank
        if (model != null && !ModelOptions.isOffered(models(), model)) return InteractResult.InvalidModel
        val resolved = resolveAgent(agent, state) ?: return InteractResult.InvalidAgent
        // A turn already running cannot take a second one: the message waits in the queue and starts when it ends.
        val action = if (state.activeTurn == null) {
            ChatActions.startTurn(newId(), now(), text, model = model, agent = resolved.uri)
        } else {
            ChatActions.queueMessage(newId(), text, model, resolved.uri)
        }
        return deliver(action)
    }

    /** The agent address a message carries; [uri] is null for none. */
    private class ResolvedAgent(val uri: String?)

    /** [AgentPick.Keep] is the chat's own agent, so sending never changes it by accident; a pick must be one the host offers, else null. */
    private fun resolveAgent(pick: AgentPick, state: ChatState): ResolvedAgent? = when (pick) {
        AgentPick.Keep -> ResolvedAgent(CustomAgents.inUse(state))
        AgentPick.None -> ResolvedAgent(null)
        is AgentPick.Use -> if (agents().any { it.uri == pick.uri }) ResolvedAgent(pick.uri) else null
    }

    suspend fun cancel(): InteractResult {
        val state = usable() ?: return InteractResult.NotAllowed
        val turn = state.activeTurn ?: return InteractResult.NoActiveTurn
        return deliver(ChatActions.cancelTurn(turn.id))
    }

    suspend fun answer(requestId: String, answers: Map<String, AnswerValue>): InteractResult =
        usable()?.let { deliver(ChatActions.completeInput(requestId, InputResponse.ACCEPT, answers)) } ?: InteractResult.NotAllowed

    /**
     * Answers a single free-text question by [requestId], checking that it is still waiting in the running turn and still a
     * single free-text question whose bounds [text] meets. Used when answering from a notification, where the chat was not
     * already open on screen.
     */
    suspend fun answerText(requestId: String, text: String): InteractResult {
        val state = usable() ?: return InteractResult.NotAllowed
        if (text.isBlank()) return InteractResult.Blank
        val request = InputRequests.find(state, requestId) ?: return InteractResult.NoSuchRequest
        val form = InputForm.from(request) ?: return InteractResult.NoSuchRequest
        val question = form.questions.singleOrNull()?.takeIf { it.kind == QuestionKind.TEXT } ?: return InteractResult.NoSuchRequest
        val checked = form.check(mapOf(question.id to FormValue.Text(text))) as? FormCheck.Ready ?: return InteractResult.Blank
        return deliver(ChatActions.completeInput(requestId, InputResponse.ACCEPT, checked.answers))
    }

    suspend fun declineInput(requestId: String): InteractResult =
        usable()?.let { deliver(ChatActions.completeInput(requestId, InputResponse.DECLINE)) } ?: InteractResult.NotAllowed

    suspend fun dismissInput(requestId: String): InteractResult =
        usable()?.let { deliver(ChatActions.completeInput(requestId, InputResponse.CANCEL)) } ?: InteractResult.NotAllowed

    /** A message that steers the turn running now. Needs a running turn: with none, a new message is [send], not a steer. */
    suspend fun steer(text: String, model: ModelChoice? = null, agent: AgentPick = AgentPick.Keep): InteractResult {
        val state = usable() ?: return InteractResult.NotAllowed
        if (text.isBlank()) return InteractResult.Blank
        if (state.activeTurn == null) return InteractResult.NoActiveTurn
        if (model != null && !ModelOptions.isOffered(models(), model)) return InteractResult.InvalidModel
        val resolved = resolveAgent(agent, state) ?: return InteractResult.InvalidAgent
        return deliver(ChatActions.steerTurn(newId(), text, model, resolved.uri))
    }

    /**
     * Tells the host (and so any other screen showing the same question) what has been entered so far for one question.
     * A null [answer] clears it. Nothing is sent for a question that is not waiting any more.
     */
    suspend fun saveDraft(requestId: String, questionId: String, answer: AnswerValue?): InteractResult {
        val state = usable() ?: return InteractResult.NotAllowed
        if (!InputRequests.isWaiting(state, requestId)) return InteractResult.NoSuchRequest
        return deliver(ChatActions.draftAnswer(requestId, questionId, answer))
    }

    /** Lets the agent see the output of a tool call that asked to be reviewed first, after the device check. */
    suspend fun approveResult(toolCallId: String): InteractResult {
        val pending = lookupResult(toolCallId) ?: return refusal()
        if (!confirmed(ApprovalRequest(pending.toolName, pending.summary, forResult = true))) return InteractResult.NotConfirmed
        val current = lookupResult(toolCallId) ?: return refusal()
        return deliver(ChatActions.confirmToolResult(current.turnId, toolCallId, approved = true))
    }

    /** Keeps the output of a tool call from the agent. No device check: saying no is always safe. */
    suspend fun denyResult(toolCallId: String): InteractResult {
        val pending = lookupResult(toolCallId) ?: return refusal()
        return deliver(ChatActions.confirmToolResult(pending.turnId, toolCallId, approved = false))
    }

    /** Allows a waiting tool call, after the device check. [optionId] is one of the host's own options, if it offered any. */
    suspend fun approve(toolCallId: String, optionId: String? = null): InteractResult {
        val pending = lookup(toolCallId, optionId, approves = true) ?: return refusal()
        val picked = optionId?.let { id -> pending.choices.firstOrNull { it.optionId == id }?.label }
        if (!confirmed(ApprovalRequest(pending.toolName, pending.summary, choice = picked))) return InteractResult.NotConfirmed
        // The person took their time: look again, so a call that finished or was answered elsewhere is not approved blind.
        val current = lookup(toolCallId, optionId, approves = true) ?: return refusal()
        return deliver(ChatActions.approveTool(current.turnId, toolCallId, optionId))
    }

    /** Refuses a waiting tool call. No device check: saying no is always safe. */
    suspend fun deny(toolCallId: String, optionId: String? = null): InteractResult {
        val pending = lookup(toolCallId, optionId, approves = false) ?: return refusal()
        return deliver(ChatActions.denyTool(pending.turnId, toolCallId, optionId))
    }

    private fun usable(): ChatState? = chat()?.takeIf { available(it, canInteract()) }

    private fun lookup(toolCallId: String, optionId: String?, approves: Boolean): PendingApproval? {
        val pending = usable()?.let { PendingApproval.find(it, toolCallId) } ?: return null
        if (optionId == null) return pending
        // An option the host did not offer, or one that means the opposite, is never sent.
        return pending.takeIf { p -> p.choices.any { it.optionId == optionId && it.approves == approves } }
    }

    private fun lookupResult(toolCallId: String): PendingApproval? = usable()?.let { PendingApproval.findResult(it, toolCallId) }

    private fun refusal(): InteractResult = if (usable() == null) InteractResult.NotAllowed else InteractResult.NoSuchRequest

    private suspend fun confirmed(request: ApprovalRequest): Boolean =
        try {
            gate.confirm(request)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    private suspend fun deliver(action: StateAction): InteractResult =
        try {
            InteractResult.Sent(dispatch(chatUri, action))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            InteractResult.NotSent(e)
        }

    companion object {
        /** True when the person may act in [chat] right now: the connection allows it and the host shows the chat as interactive (absent means full). */
        fun available(chat: ChatState?, canInteract: Boolean): Boolean =
            canInteract && chat != null && (chat.interactivity ?: ChatInteractivity.FULL) == ChatInteractivity.FULL
    }
}

/** Questions the agent has asked in the running turn. */
internal object InputRequests {
    /** True when [requestId] is a question still waiting for an answer. */
    fun isWaiting(chat: ChatState, requestId: String): Boolean =
        find(chat, requestId) != null

    /** The unanswered request with [requestId] in the running turn, or null if it has been answered or the turn has ended. */
    fun find(chat: ChatState, requestId: String): ChatInputRequest? =
        chat.activeTurn?.responseParts.orEmpty()
            .filterIsInstance<ResponsePartInputRequest>()
            .firstOrNull { it.value.request.id == requestId && it.value.response == null }
            ?.value?.request
}
