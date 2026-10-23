package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.StringOrMarkdown
import com.microsoft.agenthostprotocol.generated.ToolCallCancellationReason
import com.microsoft.agenthostprotocol.generated.ToolCallState
import com.microsoft.agenthostprotocol.generated.ToolCallStateAuthRequired
import com.microsoft.agenthostprotocol.generated.ToolCallStateCancelled
import com.microsoft.agenthostprotocol.generated.ToolCallStateCompleted
import com.microsoft.agenthostprotocol.generated.ToolCallStatePendingConfirmation
import com.microsoft.agenthostprotocol.generated.ToolCallStatePendingResultConfirmation
import com.microsoft.agenthostprotocol.generated.ToolCallStateRunning
import com.microsoft.agenthostprotocol.generated.ToolCallStateStreaming
import com.microsoft.agenthostprotocol.generated.ToolInput
import com.microsoft.agenthostprotocol.generated.ToolResultContent
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Text cut to what a phone can show. [fullLength] says how much there was, so the screen can say it left some out. */
data class Clipped(val text: String, val fullLength: Int) {
    val isClipped: Boolean get() = fullLength > text.length

    companion object {
        fun of(text: String, max: Int): Clipped {
            if (text.length <= max) return Clipped(text, text.length)
            var end = max
            // Never cut a surrogate pair in half: the half left over draws as a broken character.
            if (text[end - 1].isHighSurrogate()) end--
            return Clipped(text.substring(0, end), text.length)
        }
    }
}

data class InputLine(val name: String, val value: Clipped)

/** One thing a tool produced, as the expanded block shows it. Nothing here holds more than a bounded amount of text. */
sealed interface ToolResultView {
    data class Text(val body: Clipped) : ToolResultView

    /** [exitCode] is null while the command has not finished or the host reported none. [clippedByHost] means the host itself cut the output. */
    data class Terminal(val title: String, val output: Clipped?, val exitCode: Long?, val clippedByHost: Boolean) : ToolResultView

    data class FileEdit(val path: String, val added: Long?, val removed: Long?) : ToolResultView

    /** A subagent's own parts are not part of this result: they live in a chat of their own, which this app does not open yet. */
    data class Subagent(val title: String, val agentName: String, val description: String) : ToolResultView

    /** An embedded or referenced resource. Its data is never decoded or copied: one real result was 669 KB of image. */
    data class Attachment(val label: String) : ToolResultView

    data object Unknown : ToolResultView
}

/**
 * What a tool call did, for the expanded block: what it was asked, what it produced and why it failed. Built when a block is
 * opened rather than for every row, and bounded in every part, because a transcript can hold thousands of calls and some
 * results are enormous.
 */
data class ToolDetail(
    /** Set while the call waits on the user: this client only says so, it never approves. */
    val waitingFor: String?,
    val input: List<InputLine>,
    /** How many input fields were left out for being too many. */
    val moreInput: Int,
    val results: List<ToolResultView>,
    val error: String?,
) {
    val isEmpty: Boolean get() = waitingFor == null && input.isEmpty() && results.isEmpty() && error == null

    companion object {
        const val APPROVE_ELSEWHERE = "Approve or deny in VS Code."
        const val MAX_TEXT = 8_000
        const val MAX_INPUT_FIELDS = 12
        const val MAX_INPUT_VALUE = 400
        private const val MAX_NAME = 60

        fun from(call: ToolCallState): ToolDetail = when (call) {
            is ToolCallStateStreaming -> call.value.partialInput?.takeIf { it.isNotBlank() }
                ?.let { build(input = rawInput(it)) } ?: build()
            is ToolCallStatePendingConfirmation -> call.value.let { build(waitingFor = plain(it.confirmationTitle) ?: "Waiting for approval.", toolInput = it.toolInput) }
            is ToolCallStatePendingResultConfirmation -> call.value.let {
                build(waitingFor = "Waiting for approval of the result.", toolInput = it.toolInput, content = it.content, error = it.error)
            }
            is ToolCallStateRunning -> call.value.let { build(toolInput = it.toolInput, content = it.content) }
            is ToolCallStateAuthRequired -> call.value.let { build(waitingFor = "Sign-in required in VS Code.", toolInput = it.toolInput, content = it.content) }
            is ToolCallStateCompleted -> call.value.let { build(toolInput = it.toolInput, content = it.content, error = it.error) }
            is ToolCallStateCancelled -> call.value.let {
                build(toolInput = it.toolInput, cancelled = plain(it.reasonMessage) ?: reasonText(it.reason))
            }
            else -> build()
        }

        private fun build(
            waitingFor: String? = null,
            toolInput: ToolInput? = null,
            input: Pair<List<InputLine>, Int>? = null,
            content: List<ToolResultContent>? = null,
            error: JsonElement? = null,
            cancelled: String? = null,
        ): ToolDetail {
            val (lines, more) = input ?: toolInput?.let(::inputOf) ?: (emptyList<InputLine>() to 0)
            return ToolDetail(waitingFor, lines, more, content.orEmpty().map(::resultOf), cancelled ?: errorText(error))
        }

        private fun inputOf(input: ToolInput): Pair<List<InputLine>, Int> = when (input) {
            is ToolInput.Inline -> rawInput(input.value)
            is ToolInput.ContentReference -> listOf(InputLine("input", Clipped("Stored separately on the host.", 29))) to 0
            else -> emptyList<InputLine>() to 0
        }

        /** A JSON object becomes one line per field; anything else is shown as it came. */
        private fun rawInput(text: String): Pair<List<InputLine>, Int> {
            if (text.isBlank()) return emptyList<InputLine>() to 0
            val parsed = try {
                Json.parseToJsonElement(text)
            } catch (_: Exception) {
                null
            }
            val fields = (parsed as? JsonObject) ?: return listOf(InputLine("input", Clipped.of(text, MAX_INPUT_VALUE))) to 0
            val lines = fields.entries.take(MAX_INPUT_FIELDS).map { (name, value) ->
                InputLine(Clipped.of(name, MAX_NAME).text, Clipped.of(shown(value), MAX_INPUT_VALUE))
            }
            return lines to (fields.size - lines.size).coerceAtLeast(0)
        }

        private fun shown(value: JsonElement): String = (value as? JsonPrimitive)?.let { it.contentOrNull ?: "null" } ?: value.toString()

        private fun resultOf(content: ToolResultContent): ToolResultView = when (content) {
            is ToolResultContent.Text -> ToolResultView.Text(Clipped.of(content.value.text, MAX_TEXT))
            is ToolResultContent.Terminal -> content.value.let { terminal ->
                ToolResultView.Terminal(
                    title = terminal.title.ifBlank { "Terminal" },
                    output = terminal.result?.preview?.takeIf { it.isNotEmpty() }?.let { Clipped.of(it, MAX_TEXT) },
                    exitCode = terminal.result?.exitCode,
                    clippedByHost = terminal.result?.truncated == true,
                )
            }
            is ToolResultContent.FileEdit -> content.value.let { edit ->
                ToolResultView.FileEdit(
                    path = displayPath((edit.after ?: edit.before)?.uri ?: "(unknown file)"),
                    added = edit.diff?.added,
                    removed = edit.diff?.removed,
                )
            }
            is ToolResultContent.Subagent -> content.value.let { ToolResultView.Subagent(it.title.orEmpty().ifBlank { "Subagent" }, it.agentName.orEmpty(), it.description.orEmpty()) }
            is ToolResultContent.EmbeddedResource -> ToolResultView.Attachment("Attachment: ${content.value.contentType}")
            is ToolResultContent.Resource -> ToolResultView.Attachment("Attachment: ${content.value.contentType ?: "unknown type"}")
            else -> ToolResultView.Unknown
        }

        /** The host's error is an object with a message; whatever it is, only a bounded piece of it is shown. */
        private fun errorText(error: JsonElement?): String? {
            if (error == null || error is kotlinx.serialization.json.JsonNull) return null
            val message = (error as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.contentOrNull }
            return Clipped.of(message ?: shown(error), MAX_INPUT_VALUE).text
        }

        private fun reasonText(reason: ToolCallCancellationReason): String = when (reason) {
            ToolCallCancellationReason.DENIED -> "Denied."
            ToolCallCancellationReason.SKIPPED -> "Skipped."
            ToolCallCancellationReason.RESULT_DENIED -> "The result was denied."
            else -> "Cancelled."
        }

        private fun plain(text: StringOrMarkdown?): String? = SessionDetail.plain(text)?.takeIf { it.isNotBlank() }
    }
}

/**
 * Which blocks of a transcript the user has opened, by row key. It outlives the screen on purpose: rotating the phone or
 * leaving the chat and coming back must not fold everything up again. Everything starts collapsed.
 */
class ExpansionState {
    private val _expanded = MutableStateFlow<Set<String>>(emptySet())
    val expanded: StateFlow<Set<String>> = _expanded

    fun toggle(key: String) {
        _expanded.update { if (key in it) it - key else it + key }
    }
}
