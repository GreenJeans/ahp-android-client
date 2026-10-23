package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.ChatState
import com.microsoft.agenthostprotocol.generated.Message
import com.microsoft.agenthostprotocol.generated.ResponsePart
import com.microsoft.agenthostprotocol.generated.ResponsePartContentRef
import com.microsoft.agenthostprotocol.generated.ResponsePartError
import com.microsoft.agenthostprotocol.generated.ResponsePartInputRequest
import com.microsoft.agenthostprotocol.generated.ResponsePartMarkdown
import com.microsoft.agenthostprotocol.generated.ResponsePartReasoning
import com.microsoft.agenthostprotocol.generated.ResponsePartSystemNotification
import com.microsoft.agenthostprotocol.generated.ResponsePartToolCall
import com.microsoft.agenthostprotocol.generated.StringOrMarkdown
import com.microsoft.agenthostprotocol.generated.ToolCallState
import com.microsoft.agenthostprotocol.generated.ToolCallStateAuthRequired
import com.microsoft.agenthostprotocol.generated.ToolCallStateCancelled
import com.microsoft.agenthostprotocol.generated.ToolCallStateCompleted
import com.microsoft.agenthostprotocol.generated.ToolCallStatePendingConfirmation
import com.microsoft.agenthostprotocol.generated.ToolCallStatePendingResultConfirmation
import com.microsoft.agenthostprotocol.generated.ToolCallStateRunning
import com.microsoft.agenthostprotocol.generated.ToolCallStateStreaming
import com.microsoft.agenthostprotocol.generated.TurnState

/** How far a tool call has got, as the one-line badge says it. */
enum class ToolState(val label: String) {
    STREAMING("Preparing"),
    WAITING_FOR_APPROVAL("Waiting for approval in VS Code"),
    RUNNING("Running"),
    SIGN_IN_REQUIRED("Sign-in required"),
    DONE("Done"),
    FAILED("Failed"),
    CANCELLED("Cancelled"),
    UNKNOWN("Unknown"),
}

/** One row of the transcript. [key] is unique within a transcript and stable while the chat grows, so a list can key on it. */
sealed interface TranscriptItem {
    val key: String

    data class User(override val key: String, val text: String) : TranscriptItem

    data class Markdown(override val key: String, val text: String) : TranscriptItem

    /** [live] is set while the agent is still thinking: this is the last part of the turn that is running. */
    data class Reasoning(override val key: String, val text: String, val live: Boolean) : TranscriptItem

    /** [source] is the host's own record of the call; the detail is built from it only when a block is opened. Null for a row made without one. */
    data class Tool(override val key: String, val name: String, val summary: String?, val state: ToolState, val source: ToolCallState? = null) : TranscriptItem

    data class Notice(override val key: String, val text: String, val isError: Boolean = false) : TranscriptItem

    data class Question(override val key: String, val request: InputRequestView, val answered: Boolean) : TranscriptItem

    /** A part this client does not render yet, named so the reader knows something is there. */
    data class Other(override val key: String, val label: String) : TranscriptItem
}

/**
 * A chat as the transcript screen shows it: the header facts and the rows in reading order, oldest first. Total: a part
 * kind this client does not know becomes an [TranscriptItem.Other] row instead of being dropped or failing.
 */
data class Transcript(
    val title: String,
    val state: ActivityState,
    val activity: String?,
    val items: List<TranscriptItem>,
    /** True when the host holds turns older than the first one loaded, so `ChatStore.loadOlder` can ask for them. */
    val hasOlder: Boolean,
) {
    companion object {
        const val UNTITLED_CHAT = "Chat"

        fun from(chat: ChatState): Transcript {
            val keys = KeyBook()
            val items = ArrayList<TranscriptItem>()
            for (turn in chat.turns) {
                addTurn(items, keys, turn.id, turn.message, turn.responseParts, live = false)
                when (turn.state) {
                    TurnState.CANCELLED -> items += TranscriptItem.Notice(keys.unique("${turn.id}/outcome"), "Stopped before it finished")
                    TurnState.ERROR -> items += TranscriptItem.Notice(keys.unique("${turn.id}/outcome"), "Ended with an error", isError = true)
                    else -> Unit
                }
            }
            chat.activeTurn?.let { addTurn(items, keys, it.id, it.message, it.responseParts, live = true) }
            return Transcript(
                title = chat.title.ifBlank { UNTITLED_CHAT },
                state = chat.status.activityState,
                activity = chat.activity?.takeIf { it.isNotBlank() },
                items = items,
                hasOlder = chat.turnsNextCursor != null,
            )
        }

        private fun addTurn(items: MutableList<TranscriptItem>, keys: KeyBook, turnId: String, message: Message, parts: List<ResponsePart>, live: Boolean) {
            items += TranscriptItem.User(keys.unique("$turnId/user"), message.text)
            parts.forEachIndexed { index, part -> items += item(keys, turnId, index, part, live && index == parts.lastIndex) }
        }

        private fun item(keys: KeyBook, turnId: String, index: Int, part: ResponsePart, isLastLive: Boolean): TranscriptItem {
            fun key(id: String?) = keys.unique("$turnId/${id ?: "#$index"}")
            return when (part) {
                is ResponsePartMarkdown -> TranscriptItem.Markdown(key(part.value.id), part.value.content)
                is ResponsePartReasoning -> TranscriptItem.Reasoning(key(part.value.id), part.value.content, isLastLive)
                is ResponsePartToolCall -> tool(part.value.toolCall).let { facts -> TranscriptItem.Tool(key(facts.id), facts.name, facts.summary, facts.state, part.value.toolCall) }
                is ResponsePartSystemNotification -> TranscriptItem.Notice(key(null), plain(part.value.content).orEmpty())
                is ResponsePartError -> TranscriptItem.Notice(key(null), part.value.error.message.ifBlank { "The agent reported an error." }, isError = true)
                is ResponsePartInputRequest ->
                    TranscriptItem.Question(key(part.value.request.id), SessionDetail.describeChatInput(part.value.request), answered = part.value.response != null)
                is ResponsePartContentRef -> TranscriptItem.Other(key(null), "Attached content (${part.value.contentType ?: "unknown type"})")
                else -> TranscriptItem.Other(key(null), "Content this app cannot show")
            }
        }

        private class ToolFacts(val id: String?, val name: String, val summary: String?, val state: ToolState)

        private fun facts(id: String, displayName: String, toolName: String, intention: String?, message: StringOrMarkdown?, state: ToolState) =
            ToolFacts(id, displayName.ifBlank { toolName }, intention?.takeIf { it.isNotBlank() } ?: plain(message)?.takeIf { it.isNotBlank() }, state)

        private fun tool(call: ToolCallState): ToolFacts = when (call) {
            is ToolCallStateStreaming -> call.value.run { facts(toolCallId, displayName, toolName, intention, invocationMessage, ToolState.STREAMING) }
            is ToolCallStatePendingConfirmation -> call.value.run { facts(toolCallId, displayName, toolName, intention, invocationMessage, ToolState.WAITING_FOR_APPROVAL) }
            is ToolCallStatePendingResultConfirmation -> call.value.run { facts(toolCallId, displayName, toolName, intention, invocationMessage, ToolState.WAITING_FOR_APPROVAL) }
            is ToolCallStateRunning -> call.value.run { facts(toolCallId, displayName, toolName, intention, invocationMessage, ToolState.RUNNING) }
            is ToolCallStateAuthRequired -> call.value.run { facts(toolCallId, displayName, toolName, intention, invocationMessage, ToolState.SIGN_IN_REQUIRED) }
            is ToolCallStateCompleted -> call.value.run {
                facts(toolCallId, displayName, toolName, intention, pastTenseMessage, if (success) ToolState.DONE else ToolState.FAILED)
            }
            is ToolCallStateCancelled -> call.value.run { facts(toolCallId, displayName, toolName, intention, invocationMessage, ToolState.CANCELLED) }
            else -> ToolFacts(null, "Tool call", null, ToolState.UNKNOWN)
        }

        private fun plain(text: StringOrMarkdown?): String? = SessionDetail.plain(text)
    }
}

/** Hands out keys that are never repeated: a host that reuses an id must not be able to crash a list that keys on them. */
private class KeyBook {
    private val used = HashSet<String>()

    fun unique(base: String): String {
        if (used.add(base)) return base
        var n = 2
        while (!used.add("$base~$n")) n++
        return "$base~$n"
    }
}
