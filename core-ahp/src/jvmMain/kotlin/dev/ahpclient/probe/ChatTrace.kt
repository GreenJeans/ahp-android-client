package dev.ahpclient.probe

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.chatReducer
import com.microsoft.agenthostprotocol.generated.ChatState
import com.microsoft.agenthostprotocol.generated.ResponsePart
import com.microsoft.agenthostprotocol.generated.StateAction
import com.microsoft.agenthostprotocol.generated.StateActionUnknown
import java.util.TreeMap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * What a chat channel does to this client's copy of a chat: a snapshot, then each action folded with the library's
 * `chatReducer`. It records only counts and type names, never content, so its report is safe to read out of a log.
 * The point is to catch the library meeting traffic it does not know: an action it decodes as unknown, or a reduction
 * that throws.
 */
internal class ChatTrace(initial: ChatState) {
    var state: ChatState = initial
        private set

    /** How many times each action type was applied, in name order. */
    val actionTypes = TreeMap<String, Int>()

    /** The `type` of every action the library could not decode into a known one. */
    val unknownTypes = sortedSetOf<String>()

    var reduceFailures = 0
        private set

    val unknownActions: Int get() = unknownCount

    private var unknownCount = 0

    fun apply(action: StateAction) {
        val type = typeOf(action)
        actionTypes.merge(type, 1, Int::plus)
        if (action is StateActionUnknown) {
            unknownCount++
            unknownTypes += (action.raw["type"] as? JsonPrimitive)?.contentOrNull ?: "(no type)"
        }
        try {
            state = chatReducer(state, action)
        } catch (_: Exception) {
            reduceFailures++
        }
    }

    /** How many response parts of each kind the transcript holds, finished turns and the one still running. */
    fun partKinds(): Map<String, Int> {
        val parts = state.turns.flatMap { it.responseParts } + state.activeTurn?.responseParts.orEmpty()
        return parts.groupingBy { kindOf(it) }.eachCount().toSortedMap()
    }

    val ok: Boolean get() = unknownCount == 0 && reduceFailures == 0

    companion object {
        fun typeOf(action: StateAction): String = try {
            (Ahp.json.encodeToJsonElement(StateAction.serializer(), action) as? JsonObject)?.get("type")?.let { (it as? JsonPrimitive)?.contentOrNull } ?: "?"
        } catch (_: Exception) {
            "?"
        }

        fun kindOf(part: ResponsePart): String = try {
            (Ahp.json.encodeToJsonElement(ResponsePart.serializer(), part).jsonObject["kind"] as? JsonPrimitive)?.contentOrNull ?: "?"
        } catch (_: Exception) {
            "?"
        }
    }
}
