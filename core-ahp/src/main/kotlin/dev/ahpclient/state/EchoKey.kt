package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.StateAction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What an echo that carries no `origin` must match to answer a sent action. A turn is named by its `turnId`, which this client
 * made up and nobody else uses, and the host may rewrite the rest of it (a message's attachments, the chat it is answered on).
 * Anything else must come back exactly as it was sent, compared as the JSON it encodes to. A [text] of null (an action that
 * could not be encoded) matches nothing.
 */
internal data class EchoKey(val text: String?, val anyChannel: Boolean) {
    fun matches(other: EchoKey): Boolean = text != null && this == other

    companion object {
        private const val TURN_STARTED = "chat/turnStarted"

        fun of(action: StateAction): EchoKey {
            val json = try {
                Ahp.json.encodeToJsonElement(StateAction.serializer(), action) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return EchoKey(null, anyChannel = false)
            val type = (json["type"] as? JsonPrimitive)?.contentOrNull
            val turnId = (json["turnId"] as? JsonPrimitive)?.contentOrNull
            return if (type == TURN_STARTED && turnId != null) EchoKey("$type:$turnId", anyChannel = true) else EchoKey(json.toString(), anyChannel = false)
        }
    }
}
