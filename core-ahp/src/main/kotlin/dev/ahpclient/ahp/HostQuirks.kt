package dev.ahpclient.ahp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Small repairs to what a real host sends, made before the library decodes it, for hosts that leave out a field the protocol marks
 * required. Each is a place where a whole reply (and so the whole connection) would otherwise be refused over a detail the app never uses.
 * They are narrow on purpose: only the named path is touched, a value that is there is never changed, and a reply that needs nothing is
 * returned as it came, without a copy. The walk goes only a few levels down fixed paths, so a very large chat snapshot costs nothing.
 *
 * Known so far:
 * - **`AgentInfo.description`** is required, and `ahpd` 0.14.0 leaves it out for an agent that has none (its `copilot` agent, seen live
 *   2026-10-27, protocol 1.0.0). Read as an empty description. It appears in `initialize`, `reconnect` and `subscribe` snapshots of the
 *   root, and in the `root/agentsChanged` action.
 */
internal object HostQuirks {
    private const val AGENTS_CHANGED = "root/agentsChanged"

    /** [result] of a request, with the repairs applied. */
    fun result(result: JsonElement?): JsonElement? {
        val obj = result as? JsonObject ?: return result
        var changed = false
        val out = obj.toMutableMap()

        (obj["snapshots"] as? JsonArray)?.let { snapshots ->
            val fixed = snapshots.map { snapshot(it) }
            if (fixed.indices.any { fixed[it] !== snapshots[it] }) {
                out["snapshots"] = JsonArray(fixed)
                changed = true
            }
        }
        obj["snapshot"]?.let { single ->
            val fixed = snapshot(single)
            if (fixed !== single) {
                out["snapshot"] = fixed
                changed = true
            }
        }
        (obj["actions"] as? JsonArray)?.let { actions ->
            val fixed = actions.map { envelope(it) }
            if (fixed.indices.any { fixed[it] !== actions[it] }) {
                out["actions"] = JsonArray(fixed)
                changed = true
            }
        }
        return if (changed) JsonObject(out) else result
    }

    /** The `params` of an `action` notification, with the repairs applied. */
    fun action(params: JsonObject): JsonObject = envelope(params) as JsonObject

    private fun snapshot(element: JsonElement): JsonElement {
        val snapshot = element as? JsonObject ?: return element
        val state = snapshot["state"] as? JsonObject ?: return element
        val agents = state["agents"] ?: return element
        val fixed = agents(agents)
        return if (fixed === agents) element else JsonObject(snapshot + ("state" to JsonObject(state + ("agents" to fixed))))
    }

    private fun envelope(element: JsonElement): JsonElement {
        val envelope = element as? JsonObject ?: return element
        val action = envelope["action"] as? JsonObject ?: return element
        if ((action["type"] as? JsonPrimitive)?.contentOrNull != AGENTS_CHANGED) return element
        val agents = action["agents"] ?: return element
        val fixed = agents(agents)
        return if (fixed === agents) element else JsonObject(envelope + ("action" to JsonObject(action + ("agents" to fixed))))
    }

    private fun agents(element: JsonElement): JsonElement {
        val list = element as? JsonArray ?: return element
        if (list.none { needsDescription(it) }) return element
        return JsonArray(list.map { agent -> if (needsDescription(agent)) JsonObject((agent as JsonObject) + ("description" to JsonPrimitive(""))) else agent })
    }

    private fun needsDescription(agent: JsonElement): Boolean {
        val o = agent as? JsonObject ?: return false
        val d = o["description"]
        // Only an agent that has the other required parts: something that is not an agent is left for the decoder to refuse.
        return (d == null || d is JsonNull) && o["provider"] is JsonPrimitive && o["displayName"] is JsonPrimitive
    }
}
