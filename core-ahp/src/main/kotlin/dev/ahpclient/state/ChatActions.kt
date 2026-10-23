package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.StateAction
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One answer to one question of an input request, in the shapes the protocol defines. */
sealed interface AnswerValue {
    data class Text(val value: String) : AnswerValue

    data class Number(val value: Double) : AnswerValue

    data class Bool(val value: Boolean) : AnswerValue

    data class Selected(val value: String, val freeform: List<String> = emptyList()) : AnswerValue

    data class SelectedMany(val values: List<String>, val freeform: List<String> = emptyList()) : AnswerValue
}

/** How the user closed an input request. */
enum class InputResponse(val wire: String) {
    ACCEPT("accept"),
    DECLINE("decline"),
    CANCEL("cancel"),
}

/**
 * Builds the actions the INTERACT tier may send, and only those. Each is assembled as structured JSON (so user text is
 * never spliced into a string) and decoded through the protocol library's own serializer, so a shape the host would not
 * accept fails here instead of on the wire. The library decodes an unrecognised `type` as a harmless no-op, so a typo
 * would otherwise go unnoticed: every builder is pinned to its exact type and fields by tests.
 *
 * Nothing here talks to the host. [HostSession.dispatch] sends what these return.
 */
object ChatActions {
    /** A new user message that starts a turn. A client may only send user messages. */
    fun startTurn(turnId: String, startedAt: Instant, text: String, queuedMessageId: String? = null): StateAction =
        action("chat/turnStarted") {
            put("turnId", turnId)
            put("startedAt", startedAt.toString())
            put("message", userMessage(text))
            queuedMessageId?.let { put("queuedMessageId", it) }
        }

    /** A user message to run after the current turn. If the chat is idle the host starts a turn from it at once. */
    fun queueMessage(messageId: String, text: String): StateAction =
        action("chat/pendingMessageSet") {
            put("kind", "queued")
            put("id", messageId)
            put("message", userMessage(text))
        }

    /** Takes back a queued message that has not been consumed yet. */
    fun removeQueuedMessage(messageId: String): StateAction =
        action("chat/pendingMessageRemoved") {
            put("kind", "queued")
            put("id", messageId)
        }

    /**
     * Stops the running turn. `duration` is the producer's own measure of the turn's length and a phone has no such
     * clock for a turn the host runs, so it is sent as 0 and the host's own figure is the one that counts.
     */
    fun cancelTurn(turnId: String): StateAction =
        action("chat/turnCancelled") {
            put("turnId", turnId)
            put("duration", 0)
        }

    /** Closes an input request. [answers] (keyed by question id) replace the host's drafts and matter only when accepting. */
    fun completeInput(requestId: String, response: InputResponse, answers: Map<String, AnswerValue> = emptyMap()): StateAction =
        action("chat/inputCompleted") {
            put("requestId", requestId)
            put("response", response.wire)
            if (response == InputResponse.ACCEPT && answers.isNotEmpty()) {
                put("answers", JsonObject(answers.mapValues { (_, value) -> submitted(value) }))
            }
        }

    /** Allows a tool call that is waiting for confirmation. [selectedOptionId] is one of the options the host offered, if it offered any. */
    fun approveTool(turnId: String, toolCallId: String, selectedOptionId: String? = null): StateAction =
        action("chat/toolCallConfirmed") {
            put("turnId", turnId)
            put("toolCallId", toolCallId)
            put("approved", true)
            put("confirmed", "user-action")
            selectedOptionId?.let { put("selectedOptionId", it) }
        }

    /** Refuses a tool call that is waiting for confirmation. */
    fun denyTool(turnId: String, toolCallId: String, selectedOptionId: String? = null): StateAction =
        action("chat/toolCallConfirmed") {
            put("turnId", turnId)
            put("toolCallId", toolCallId)
            put("approved", false)
            put("reason", "denied")
            selectedOptionId?.let { put("selectedOptionId", it) }
        }

    /** Approves or refuses the result of a tool call that asked to be reviewed. */
    fun confirmToolResult(turnId: String, toolCallId: String, approved: Boolean): StateAction =
        action("chat/toolCallResultConfirmed") {
            put("turnId", turnId)
            put("toolCallId", toolCallId)
            put("approved", approved)
        }

    private fun userMessage(text: String): JsonObject = buildJsonObject {
        put("text", text)
        put("origin", buildJsonObject { put("kind", "user") })
    }

    private fun submitted(value: AnswerValue): JsonElement = buildJsonObject {
        put("state", "submitted")
        put("value", answerValue(value))
    }

    private fun answerValue(value: AnswerValue): JsonObject = buildJsonObject {
        when (value) {
            is AnswerValue.Text -> {
                put("kind", "text")
                put("value", value.value)
            }
            is AnswerValue.Number -> {
                put("kind", "number")
                put("value", value.value)
            }
            is AnswerValue.Bool -> {
                put("kind", "boolean")
                put("value", value.value)
            }
            is AnswerValue.Selected -> {
                put("kind", "selected")
                put("value", value.value)
                freeform(value.freeform)
            }
            is AnswerValue.SelectedMany -> {
                put("kind", "selected-many")
                put("value", JsonArray(value.values.map(::JsonPrimitive)))
                freeform(value.freeform)
            }
        }
    }

    private fun JsonObjectBuilder.freeform(values: List<String>) {
        if (values.isNotEmpty()) put("freeformValues", JsonArray(values.map(::JsonPrimitive)))
    }

    private fun action(type: String, fields: JsonObjectBuilder.() -> Unit): StateAction =
        Ahp.json.decodeFromJsonElement(
            StateAction.serializer(),
            buildJsonObject {
                put("type", type)
                fields()
            },
        )
}
