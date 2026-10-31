package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.FileEditSide
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

    /**
     * [exitCode] is null while the command has not finished or the host reported none. [clippedByHost] means the host itself cut the output.
     * [terminalUri] is set only when the host named a terminal address this app may subscribe to, so the live output can be opened.
     */
    data class Terminal(val title: String, val output: Clipped?, val exitCode: Long?, val clippedByHost: Boolean, val terminalUri: String? = null) : ToolResultView

    data class FileEdit(val path: String, val added: Long?, val removed: Long?) : ToolResultView {
        var before: FileEditSide? = null
        var after: FileEditSide? = null
        val canOpenDiff: Boolean get() = before?.content != null || after?.content != null
    }

    /** The id of the subagent's own chat, set only when it is an AHP chat address: the transcript of what the subagent did lives there. */
    data class Subagent(val title: String, val agentName: String, val description: String, val chatUri: String? = null) : ToolResultView

    /** An embedded or referenced resource. Its data is only decoded on demand within safe bounds: one real result was 669 KB of image. */
    data class Attachment(val label: String) : ToolResultView {
        var resourceRef: ResourceRef? = null
    }

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
    /** How long a finished call took, formatted as `"840ms"` / `"1.2s"` / `"2m 5s"` (E5), or null while running or unreported. */
    val durationText: String? = null,
    /** The MCP server name the host reported for this tool call (E5), or null when unreported. */
    val mcpServerName: String? = null,
) {
    val isEmpty: Boolean get() = waitingFor == null && input.isEmpty() && results.isEmpty() && error == null && mcpServerName == null && durationText == null

    /** True when something was left out for length, so the block can offer to show more. */
    val hasMore: Boolean
        get() = moreInput > 0 || input.any { it.value.isClipped } || results.any {
            when (it) {
                is ToolResultView.Text -> it.body.isClipped
                is ToolResultView.Terminal -> it.output?.isClipped == true
                else -> false
            }
        }

    /** How much of a long text, a long value and a long list of fields is kept. [ALL] is the "Show all" setting and is still bounded. */
    data class Limits(val text: Int, val inputFields: Int, val inputValue: Int) {
        companion object {
            val DEFAULT = Limits(text = 8_000, inputFields = 12, inputValue = 400)

            /** Every real result seen was far under this; it only stops a freak one (a 669 KB image's data is never decoded at all) from freezing the screen. */
            val ALL = Limits(text = 100_000, inputFields = 200, inputValue = 100_000)
        }
    }

    companion object {
        const val APPROVE_ELSEWHERE = "Approve or deny in VS Code."
        const val MAX_TEXT = 8_000
        const val MAX_INPUT_FIELDS = 12
        const val MAX_INPUT_VALUE = 400
        private const val MAX_NAME = 60
        private const val CHAT_SCHEME = "ahp-chat:/"
        private const val MAX_CHAT_URI = 512

        fun from(call: ToolCallState, limits: Limits = Limits.DEFAULT): ToolDetail = when (call) {
            is ToolCallStateStreaming -> call.value.partialInput?.takeIf { it.isNotBlank() }
                ?.let { build(limits, input = rawInput(it, limits), extras = call.extras) } ?: build(limits, extras = call.extras)
            is ToolCallStatePendingConfirmation -> call.value.let {
                build(limits, waitingFor = plain(it.confirmationTitle) ?: "Waiting for approval.", toolInput = it.toolInput, extras = call.extras)
            }
            is ToolCallStatePendingResultConfirmation -> call.value.let {
                build(limits, waitingFor = "Waiting for approval of the result.", toolInput = it.toolInput, content = it.content, error = it.error, extras = call.extras)
            }
            is ToolCallStateRunning -> call.value.let { build(limits, toolInput = it.toolInput, content = it.content, extras = call.extras) }
            is ToolCallStateAuthRequired -> call.value.let {
                build(limits, waitingFor = "Sign-in required in VS Code.", toolInput = it.toolInput, content = it.content, extras = call.extras)
            }
            is ToolCallStateCompleted -> call.value.let { build(limits, toolInput = it.toolInput, content = it.content, error = it.error, extras = call.extras) }
            is ToolCallStateCancelled -> call.value.let {
                build(limits, toolInput = it.toolInput, cancelled = plain(it.reasonMessage) ?: reasonText(it.reason), extras = call.extras)
            }
            else -> build(limits)
        }

        private fun build(
            limits: Limits,
            waitingFor: String? = null,
            toolInput: ToolInput? = null,
            input: Pair<List<InputLine>, Int>? = null,
            content: List<ToolResultContent>? = null,
            error: JsonElement? = null,
            cancelled: String? = null,
            extras: StepExtras = StepExtras.NONE,
        ): ToolDetail {
            val (lines, more) = input ?: toolInput?.let { inputOf(it, limits) } ?: (emptyList<InputLine>() to 0)
            return ToolDetail(
                waitingFor = waitingFor,
                input = lines,
                moreInput = more,
                results = content.orEmpty().map { resultOf(it, limits, extras) },
                error = cancelled ?: errorText(error, limits),
                durationText = extras.durationText,
                mcpServerName = extras.mcpServerName,
            )
        }

        private fun inputOf(input: ToolInput, limits: Limits): Pair<List<InputLine>, Int> = when (input) {
            is ToolInput.Inline -> rawInput(input.value, limits)
            is ToolInput.ContentReference -> listOf(InputLine("input", Clipped("Stored separately on the host.", 29))) to 0
        }

        /** A JSON object becomes one line per field; anything else is shown as it came. */
        private fun rawInput(text: String, limits: Limits): Pair<List<InputLine>, Int> {
            if (text.isBlank()) return emptyList<InputLine>() to 0
            val parsed = try {
                Json.parseToJsonElement(text)
            } catch (_: Exception) {
                null
            }
            val fields = (parsed as? JsonObject) ?: return listOf(InputLine("input", Clipped.of(text, limits.inputValue))) to 0
            val lines = fields.entries.take(limits.inputFields).map { (name, value) ->
                InputLine(Clipped.of(name, MAX_NAME).text, Clipped.of(shown(value), limits.inputValue))
            }
            return lines to (fields.size - lines.size).coerceAtLeast(0)
        }

        private fun shown(value: JsonElement): String = (value as? JsonPrimitive)?.let { it.contentOrNull ?: "null" } ?: value.toString()

        private fun resultOf(content: ToolResultContent, limits: Limits, extras: StepExtras = StepExtras.NONE): ToolResultView = when (content) {
            is ToolResultContent.Text -> ToolResultView.Text(Clipped.of(content.value.text, limits.text))
            is ToolResultContent.Terminal -> content.value.let { terminal ->
                ToolResultView.Terminal(
                    title = terminal.title.ifBlank { "Terminal" },
                    output = terminal.result?.preview?.takeIf { it.isNotEmpty() }?.let { Clipped.of(it, limits.text) },
                    exitCode = terminal.result?.exitCode,
                    clippedByHost = terminal.result?.truncated == true,
                    terminalUri = terminal.resource.takeIf(TerminalLinks::isTerminalUri),
                )
            }
            is ToolResultContent.FileEdit -> content.value.let { edit ->
                ToolResultView.FileEdit(
                    path = displayPath((edit.after ?: edit.before)?.uri ?: "(unknown file)"),
                    added = edit.diff?.added,
                    removed = edit.diff?.removed,
                ).apply {
                    before = edit.before
                    after = edit.after
                }
            }
            is ToolResultContent.Subagent -> content.value.let {
                ToolResultView.Subagent(
                    it.title.orEmpty().ifBlank { "Subagent" },
                    it.agentName.orEmpty().ifBlank { extras.subagentAgentName.orEmpty() },
                    it.description.orEmpty().ifBlank { extras.subagentDescription.orEmpty() },
                    chatUriOf(it.resource),
                )
            }
            is ToolResultContent.EmbeddedResource -> ToolResultView.Attachment("Attachment: ${content.value.contentType}").apply {
                resourceRef = ResourceRef.from(content.value)
            }
            is ToolResultContent.Resource -> ToolResultView.Attachment("Attachment: ${content.value.contentType ?: "unknown type"}").apply {
                resourceRef = ResourceRef.from(content.value)
            }
            else -> ToolResultView.Unknown
        }

        /** Only an AHP chat address is ever opened from a result: anything else the host puts there is not a transcript. */
        private fun chatUriOf(resource: String?): String? = resource?.takeIf { it.length <= MAX_CHAT_URI && it.startsWith(CHAT_SCHEME) && it.length > CHAT_SCHEME.length }

        /** The host's error is an object with a message; whatever it is, only a bounded piece of it is shown. */
        private fun errorText(error: JsonElement?, limits: Limits): String? {
            if (error == null || error is kotlinx.serialization.json.JsonNull) return null
            val message = (error as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.contentOrNull }
            return Clipped.of(message ?: shown(error), limits.inputValue).text
        }

        private fun reasonText(reason: ToolCallCancellationReason): String = when (reason) {
            ToolCallCancellationReason.DENIED -> "Denied."
            ToolCallCancellationReason.SKIPPED -> "Skipped."
            ToolCallCancellationReason.RESULT_DENIED -> "The result was denied."
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
