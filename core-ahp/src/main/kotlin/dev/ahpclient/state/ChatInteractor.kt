package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.ChatInteractivity
import com.microsoft.agenthostprotocol.generated.ChatState
import com.microsoft.agenthostprotocol.generated.ConfirmationOptionKind
import com.microsoft.agenthostprotocol.generated.ResponsePartToolCall
import com.microsoft.agenthostprotocol.generated.StateAction
import com.microsoft.agenthostprotocol.generated.ToolCallPendingConfirmationState
import com.microsoft.agenthostprotocol.generated.ToolCallStatePendingConfirmation
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** What the person is about to allow. Shown by the device check; it is display text and never sent anywhere. */
data class ApprovalRequest(val toolName: String, val summary: String?)

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

/** A tool call the host is holding until the person decides. */
data class PendingApproval(
    val turnId: String,
    val toolCallId: String,
    val toolName: String,
    val summary: String?,
    val choices: List<ApprovalChoice>,
) {
    companion object {
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

        fun from(turnId: String, call: ToolCallPendingConfirmationState): PendingApproval {
            val offered = call.options.orEmpty().map { ApprovalChoice(it.id, it.label, approves = it.kind == ConfirmationOptionKind.APPROVE) }
            val choices = offered.ifEmpty { listOf(ApprovalChoice(null, "Approve", approves = true), ApprovalChoice(null, "Deny", approves = false)) }
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

    /** Sending failed before anything left the phone (offline, or the connection was just lost). Safe to try again. */
    data class NotSent(val cause: Throwable) : InteractResult
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
) {
    suspend fun send(text: String): InteractResult {
        val state = usable() ?: return InteractResult.NotAllowed
        if (text.isBlank()) return InteractResult.Blank
        // A turn already running cannot take a second one: the message waits in the queue and starts when it ends.
        val action = if (state.activeTurn == null) ChatActions.startTurn(newId(), now(), text) else ChatActions.queueMessage(newId(), text)
        return deliver(action)
    }

    suspend fun cancel(): InteractResult {
        val state = usable() ?: return InteractResult.NotAllowed
        val turn = state.activeTurn ?: return InteractResult.NoActiveTurn
        return deliver(ChatActions.cancelTurn(turn.id))
    }

    suspend fun answer(requestId: String, answers: Map<String, AnswerValue>): InteractResult =
        usable()?.let { deliver(ChatActions.completeInput(requestId, InputResponse.ACCEPT, answers)) } ?: InteractResult.NotAllowed

    suspend fun declineInput(requestId: String): InteractResult =
        usable()?.let { deliver(ChatActions.completeInput(requestId, InputResponse.DECLINE)) } ?: InteractResult.NotAllowed

    suspend fun dismissInput(requestId: String): InteractResult =
        usable()?.let { deliver(ChatActions.completeInput(requestId, InputResponse.CANCEL)) } ?: InteractResult.NotAllowed

    /** Allows a waiting tool call, after the device check. [optionId] is one of the host's own options, if it offered any. */
    suspend fun approve(toolCallId: String, optionId: String? = null): InteractResult {
        val pending = lookup(toolCallId, optionId, approves = true) ?: return refusal()
        if (!confirmed(ApprovalRequest(pending.toolName, pending.summary))) return InteractResult.NotConfirmed
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
