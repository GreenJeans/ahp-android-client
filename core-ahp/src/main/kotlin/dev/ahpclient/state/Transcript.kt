package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.BackgroundWork
import com.microsoft.agenthostprotocol.generated.BackgroundWorkShell
import com.microsoft.agenthostprotocol.generated.BackgroundWorkSubagent
import com.microsoft.agenthostprotocol.generated.BackgroundWorkUnknown
import com.microsoft.agenthostprotocol.generated.CanvasReference
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

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

/** How a finished turn ended. */
enum class TurnOutcome { DONE, STOPPED, FAILED }

/** Kind of pending message waiting in the chat. */
/** Kind of pending message waiting in the chat. */
enum class PendingMessageKind { STEERING, QUEUED }

/** Kind of active background work reported on a chat. */
enum class BackgroundWorkKindView(val label: String) {
    SHELL("Background shell"),
    SUBAGENT("Background subagent"),
    OTHER("Background task"),
}

/** An active background task (shell command or subagent) reported on the chat by the host. */
data class BackgroundWorkView(
    val id: String,
    val kind: BackgroundWorkKindView,
    val label: String,
    val command: String? = null,
    val terminalUri: String? = null,
    val chatUri: String? = null,
)

/** A canvas referenced by the chat. */
data class CanvasView(
    val resource: String,
    val label: String,
)

/** A steering or queued message waiting to be consumed by the agent. */
data class PendingMessageView(
    val id: String,
    val text: String,
    val kind: PendingMessageKind,
)

/** One row of the transcript. [key] is unique within a transcript and stable while the chat grows, so a list can key on it. */
sealed interface TranscriptItem {
    val key: String

    data class User(
        override val key: String,
        val text: String,
        val turnId: String = key.removeSuffix("/user"),
        val turnIndex: Int = 0,
        val previousTurnId: String? = null,
        val turnsFromHereCount: Int = 1,
    ) : TranscriptItem

    data class Markdown(override val key: String, val text: String) : TranscriptItem

    /** [live] is set while the agent is still thinking: this is the last part of the turn that is running. */
    data class Reasoning(override val key: String, val text: String, val live: Boolean) : TranscriptItem

    /**
     * [source] is the host's own record of the call; the detail is built from it only when a block is opened. Null for a row made without one.
     * [progress] is the host's live "what it is doing now" line while the call is [ToolState.RUNNING] (E3); null once it finishes.
     * [durationText] is how long a finished call took, formatted as `"840ms"` / `"1.2s"` / `"2m 5s"` (E5); null while running or unreported.
     * [mcpServerName] is the MCP server the host attributes this call to (E5), or null when unreported.
     */
    data class Tool(
        override val key: String,
        val name: String,
        val summary: String?,
        val state: ToolState,
        val source: ToolCallState? = null,
        val progress: String? = null,
        val durationText: String? = source?.extras?.durationText,
        val mcpServerName: String? = source?.extras?.mcpServerName,
    ) : TranscriptItem

    data class Notice(override val key: String, val text: String, val isError: Boolean = false) : TranscriptItem

    /** The end of a finished turn, so it is plain when the agent has stopped, and for how long it worked. */
    data class TurnEnd(
        override val key: String,
        val outcome: TurnOutcome,
        val durationMs: Long?,
        val turnId: String = key.removeSuffix("/end"),
        val resumable: Boolean = false,
        val usage: TurnUsage? = null,
    ) : TranscriptItem

    data class Question(override val key: String, val request: InputRequestView, val answered: Boolean, val form: InputForm? = null) : TranscriptItem

    /** A part this client does not render as plain markdown or a tool row, named so the reader knows something is there. */
    data class Other(override val key: String, val label: String) : TranscriptItem {
        var resourceRef: ResourceRef? = null
    }
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
    /** A turn is in progress: the agent is working, or waiting on the person. */
    val running: Boolean = false,
    /** Pending steering and queued messages currently waiting in the chat. */
    val pendingMessages: List<PendingMessageView> = emptyList(),
    /** Identifier of the currently running turn, when [running] is true. */
    val activeTurnId: String? = null,
    /** Identifier of the most recent completed turn, if any. */
    val lastTurnId: String? = null,
    /** Aggregated token and cost telemetry across the turns in this chat, or null when the host reported none. */
    val usage: TurnUsage? = null,
    /** Active background tasks (shell processes or subagents) reported by the host on this chat. */
    val backgroundWork: List<BackgroundWorkView> = emptyList(),
    /** Canvases referenced by this chat. */
    val canvases: List<CanvasView> = emptyList(),
) {
    /** The turn that is running has stopped for the person: a call waiting to be approved or signed in, or a question to answer. Nothing is being worked on then. */
    val waitingOnYou: Boolean
        get() = running && items.takeLastWhile { it !is TranscriptItem.User && it !is TranscriptItem.TurnEnd }.any {
            (it is TranscriptItem.Tool && (it.state == ToolState.WAITING_FOR_APPROVAL || it.state == ToolState.SIGN_IN_REQUIRED)) ||
                (it is TranscriptItem.Question && !it.answered)
        }

    companion object {
        const val UNTITLED_CHAT = "Chat"

        fun from(chat: ChatState): Transcript {
            val keys = KeyBook()
            val items = ArrayList<TranscriptItem>()
            val totalTurns = chat.turns.size + if (chat.activeTurn != null) 1 else 0
            chat.turns.forEachIndexed { index, turn ->
                val prevId = if (index == 0) null else chat.turns[index - 1].id
                val turnsFromHere = totalTurns - index
                if (addTurn(items, keys, turn.id, turn.message, turn.responseParts, live = false, turnIndex = index, previousTurnId = prevId, turnsFromHereCount = turnsFromHere)) {
                    val outcome = when (turn.state) {
                        TurnState.CANCELLED -> TurnOutcome.STOPPED
                        TurnState.ERROR -> TurnOutcome.FAILED
                        else -> TurnOutcome.DONE
                    }
                    val resumable = index == chat.turns.lastIndex &&
                        chat.activeTurn == null &&
                        turn.state == TurnState.ERROR &&
                        (turn.responseParts.lastOrNull() as? ResponsePartError)?.value?.resumable == true
                    items += TranscriptItem.TurnEnd(
                        key = keys.unique("${turn.id}/end"),
                        outcome = outcome,
                        durationMs = turn.duration?.takeIf { it >= 0 },
                        turnId = turn.id,
                        resumable = resumable,
                        usage = turn.usage?.extras,
                    )
                }
            }
            chat.activeTurn?.let { active ->
                val prevId = chat.turns.lastOrNull()?.id
                addTurn(items, keys, active.id, active.message, active.responseParts, live = true, turnIndex = chat.turns.size, previousTurnId = prevId, turnsFromHereCount = 1)
            }
            val pending = buildList {
                chat.steeringMessage?.let { add(PendingMessageView(it.id, it.message.text, PendingMessageKind.STEERING)) }
                chat.queuedMessages.orEmpty().forEach { add(PendingMessageView(it.id, it.message.text, PendingMessageKind.QUEUED)) }
            }
            return Transcript(
                title = chat.title.ifBlank { UNTITLED_CHAT },
                state = chat.status.activityState,
                activity = chat.activity?.takeIf { it.isNotBlank() },
                items = items,
                hasOlder = chat.turnsNextCursor != null,
                running = chat.activeTurn != null,
                pendingMessages = pending,
                activeTurnId = chat.activeTurn?.id,
                lastTurnId = chat.turns.lastOrNull()?.id,
                usage = HostExtras.chatUsage(chat),
                backgroundWork = chat.backgroundWork.orEmpty().mapIndexed(::backgroundWorkOf),
                canvases = chat.canvases.orEmpty().mapNotNull(::canvasOf),
            )
        }

        /** Adds a turn's user message (unless hidden from the transcript, E4) and its response parts. Returns false when the turn left no rows. */
        private fun addTurn(
            items: MutableList<TranscriptItem>,
            keys: KeyBook,
            turnId: String,
            message: Message,
            parts: List<ResponsePart>,
            live: Boolean,
            turnIndex: Int = 0,
            previousTurnId: String? = null,
            turnsFromHereCount: Int = 1,
        ): Boolean {
            val hidden = message.extras.hiddenFromTranscript
            if (!hidden) {
                items += TranscriptItem.User(
                    key = keys.unique("$turnId/user"),
                    text = message.text,
                    turnId = turnId,
                    turnIndex = turnIndex,
                    previousTurnId = previousTurnId,
                    turnsFromHereCount = turnsFromHereCount,
                )
            }
            parts.forEachIndexed { index, part -> items += item(keys, turnId, index, part, live && index == parts.lastIndex) }
            return !hidden || parts.isNotEmpty()
        }

        private fun item(keys: KeyBook, turnId: String, index: Int, part: ResponsePart, isLastLive: Boolean): TranscriptItem {
            fun key(id: String?) = keys.unique("$turnId/${id ?: "#$index"}")
            return when (part) {
                is ResponsePartMarkdown -> TranscriptItem.Markdown(key(part.value.id), part.value.content)
                is ResponsePartReasoning -> TranscriptItem.Reasoning(key(part.value.id), part.value.content, isLastLive)
                is ResponsePartToolCall -> tool(part.value.toolCall).let { facts ->
                    val extras = part.value.toolCall.extras
                    TranscriptItem.Tool(
                        key = key(facts.id),
                        name = facts.name,
                        summary = facts.summary,
                        state = facts.state,
                        source = part.value.toolCall,
                        progress = facts.progress,
                        durationText = extras.durationText,
                        mcpServerName = extras.mcpServerName,
                    )
                }
                is ResponsePartSystemNotification -> TranscriptItem.Notice(key(null), plain(part.value.content).orEmpty())
                is ResponsePartError -> TranscriptItem.Notice(key(null), part.value.error.message.ifBlank { "The agent reported an error." }, isError = true)
                is ResponsePartInputRequest -> {
                    val answered = part.value.response != null
                    // A form only for a question still waiting; an answered one is history.
                    TranscriptItem.Question(
                        key(part.value.request.id),
                        SessionDetail.describeChatInput(part.value.request),
                        answered,
                        form = if (answered) null else InputForm.from(part.value.request),
                    )
                }
                is ResponsePartContentRef -> TranscriptItem.Other(key(null), "Attached content (${part.value.contentType ?: "unknown type"})").apply {
                    resourceRef = ResourceRef.from(part.value)
                }
                else -> TranscriptItem.Other(key(null), "Content this app cannot show")
            }
        }

        private class ToolFacts(val id: String?, val name: String, val summary: String?, val state: ToolState, val progress: String? = null)

        private fun facts(id: String, displayName: String, toolName: String, intention: String?, message: StringOrMarkdown?, state: ToolState, progress: String? = null) =
            ToolFacts(
                id,
                displayName.ifBlank { toolName },
                progress ?: intention?.takeIf { it.isNotBlank() } ?: plain(message)?.takeIf { it.isNotBlank() },
                state,
                progress,
            )

        private fun tool(call: ToolCallState): ToolFacts = when (call) {
            is ToolCallStateStreaming -> call.value.run { facts(toolCallId, displayName, toolName, intention, invocationMessage, ToolState.STREAMING) }
            is ToolCallStatePendingConfirmation -> call.value.run { facts(toolCallId, displayName, toolName, intention, invocationMessage, ToolState.WAITING_FOR_APPROVAL) }
            is ToolCallStatePendingResultConfirmation -> call.value.run { facts(toolCallId, displayName, toolName, intention, invocationMessage, ToolState.WAITING_FOR_APPROVAL) }
            is ToolCallStateRunning -> call.value.run { facts(toolCallId, displayName, toolName, intention, invocationMessage, ToolState.RUNNING, call.extras.progressMessage) }
            is ToolCallStateAuthRequired -> call.value.run { facts(toolCallId, displayName, toolName, intention, invocationMessage, ToolState.SIGN_IN_REQUIRED) }
            is ToolCallStateCompleted -> call.value.run {
                facts(toolCallId, displayName, toolName, intention, pastTenseMessage, if (success) ToolState.DONE else ToolState.FAILED)
            }
            is ToolCallStateCancelled -> call.value.run { facts(toolCallId, displayName, toolName, intention, invocationMessage, ToolState.CANCELLED) }
            else -> ToolFacts(null, "Tool call", null, ToolState.UNKNOWN)
        }

        private fun plain(text: StringOrMarkdown?): String? = SessionDetail.plain(text)

        private const val MAX_CANVAS_LABEL_CHARS = 80

        private fun backgroundWorkOf(index: Int, work: BackgroundWork): BackgroundWorkView = when (work) {
            is BackgroundWorkShell -> {
                val v = work.value
                val id = v.id.ifBlank { "shell-$index" }
                val cmd = v.command.trim().takeIf { it.isNotEmpty() }
                val label = v.label.trim().ifEmpty { cmd ?: id }
                BackgroundWorkView(
                    id = id,
                    kind = BackgroundWorkKindView.SHELL,
                    label = label,
                    command = cmd?.takeIf { it != label },
                    terminalUri = v.terminal?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
            is BackgroundWorkSubagent -> {
                val v = work.value
                val id = v.id.ifBlank { "subagent-$index" }
                val label = v.label.trim().ifEmpty { id }
                BackgroundWorkView(
                    id = id,
                    kind = BackgroundWorkKindView.SUBAGENT,
                    label = label,
                    chatUri = v.chat.trim().takeIf { it.isNotEmpty() },
                )
            }
            is BackgroundWorkUnknown -> {
                val raw = work.raw
                val id = raw["id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: "work-$index"
                val label = raw["label"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: id
                BackgroundWorkView(id = id, kind = BackgroundWorkKindView.OTHER, label = label)
            }
        }

        private fun canvasOf(ref: CanvasReference): CanvasView? {
            val resource = ref.resource.trim()
            if (resource.isEmpty()) return null
            val tail = resource.substringAfterLast('/').substringAfterLast(':').trim()
            val label = (if (tail.isNotEmpty()) tail else resource).take(MAX_CANVAS_LABEL_CHARS)
            return CanvasView(resource = resource, label = label)
        }
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
