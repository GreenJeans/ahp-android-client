package dev.ahpclient.ahp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The per-chat settings this app reads and may write, and the words each may be set to. The one list that both the screens and
 * [CapabilityPolicy] use, so a value the policy would refuse is never offered, and nothing outside it can be sent: the host has
 * other per-chat properties (the terminal rules a tool may run without asking, the sandbox) that are never touched from here.
 */
object ChatConfigKeys {
    const val MODE = "mode"
    const val AUTO_APPROVE = "autoApprove"

    /** The Copilot agent's name for the same choice as [AUTO_APPROVE]: manual, assisted, allow-all. Used only when the host has no `autoApprove`. */
    const val APPROVAL_MODE = "approvalMode"

    /** Host-reported, read-only: the approval level actually in force, and the levels the host allows now (its policy may withhold some). */
    const val EFFECTIVE_APPROVAL_MODE = "effectiveApprovalMode"
    const val AVAILABLE_APPROVAL_MODES = "availableApprovalModes"

    /** What may be written, and to what. Every value is a short fixed word, so nothing the host or a screen supplies can be sent as one. */
    val WRITABLE: Map<String, Set<String>> = mapOf(
        MODE to setOf("interactive", "plan", "autopilot"),
        AUTO_APPROVE to setOf("default", "assisted", "autoApprove"),
        APPROVAL_MODE to setOf("manual", "assisted", "allow-all"),
    )

    /** Every property of a chat this app reads from the host. */
    val READ: Set<String> = WRITABLE.keys + EFFECTIVE_APPROVAL_MODE + AVAILABLE_APPROVAL_MODES
}

/**
 * What the host sends in a snapshot that the 1.0.0 protocol library has no field for, read from the raw reply: a chat's own
 * values for [ChatConfigKeys.READ], and which of a session's properties a chat may set for itself (`chatMutable`). Both arrived
 * in protocol 1.1.0, which the host still speaks to this app; without them a chat's mode and approvals cannot be shown truly,
 * because a chat's own value hides the session's. Pure: nothing here talks to the host.
 */
data class ConfigFacts(
    /** For a chat: its own values. Empty when the chat has none of its own. Null when this is not a chat. */
    val chatValues: Map<String, JsonElement>?,
    /** For a session: the keys among [ChatConfigKeys.READ] its schema lets a chat set. Null when this is not a session. */
    val chatMutableKeys: Set<String>?,
) {
    companion object {
        private const val MAX_SNAPSHOTS = 64
        private const val MAX_RESOURCE = 512

        /** Facts for each snapshot a reply holds (`subscribe` has one, `initialize` and a full `reconnect` have several), by channel. */
        fun fromResult(result: JsonElement?): Map<String, ConfigFacts> {
            val obj = result as? JsonObject ?: return emptyMap()
            val snapshots = buildList {
                (obj["snapshot"] as? JsonObject)?.let(::add)
                (obj["snapshots"] as? JsonArray)?.take(MAX_SNAPSHOTS)?.forEach { (it as? JsonObject)?.let(::add) }
            }
            val found = LinkedHashMap<String, ConfigFacts>()
            for (snapshot in snapshots) {
                val resource = (snapshot["resource"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: continue
                if (resource.isEmpty() || resource.length > MAX_RESOURCE) continue
                val state = snapshot["state"] as? JsonObject ?: continue
                found[resource] = factsOf(state) ?: continue
            }
            return found
        }

        private fun factsOf(state: JsonObject): ConfigFacts? {
            val config = state["config"] as? JsonObject
            return when {
                // A session has a `lifecycle` and a chat has `turns`; a root or a terminal has neither, and holds nothing to read here.
                state.containsKey("lifecycle") -> ConfigFacts(chatValues = null, chatMutableKeys = chatMutableKeys(config))
                state.containsKey("turns") -> ConfigFacts(chatValues = chatValues(config), chatMutableKeys = null)
                else -> null
            }
        }

        private fun chatMutableKeys(config: JsonObject?): Set<String> {
            val properties = ((config?.get("schema") as? JsonObject)?.get("properties") as? JsonObject) ?: return emptySet()
            return ChatConfigKeys.READ.filterTo(HashSet()) { key ->
                (((properties[key] as? JsonObject)?.get("chatMutable") as? JsonPrimitive)?.takeIf { !it.isString })?.booleanOrNull == true
            }
        }

        private fun chatValues(config: JsonObject?): Map<String, JsonElement> {
            val values = config?.get("values") as? JsonObject ?: return emptyMap()
            return values.filterKeys { it in ChatConfigKeys.READ }
        }
    }
}
