package dev.ahpclient.probe

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.InitializeResult
import com.microsoft.agenthostprotocol.generated.ListSessionsResult
import com.microsoft.agenthostprotocol.generated.ReconnectResult
import com.microsoft.agenthostprotocol.generated.ResolveSessionConfigResult
import com.microsoft.agenthostprotocol.generated.ResourceReadResult
import com.microsoft.agenthostprotocol.generated.FetchTurnsResult
import com.microsoft.agenthostprotocol.generated.ActionEnvelope
import com.microsoft.agenthostprotocol.generated.SubscribeResult
import dev.ahpclient.ahp.HostQuirks
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * E0: what a host sends that the pinned library's types do not declare. Fed every frame by [FrameLog] and reports, per kind of frame,
 * (1) every path found under a `_meta` key, which is where hosts put their private extensions, and (2) every other path the library
 * drops, found by decoding the frame with the generated type and writing it out again: a path in the frame that is not in the copy is one
 * the types do not carry.
 *
 * It holds paths, JSON types and counts and never a value. A key that is not a plain identifier (a URI, a UUID, anything long) is shown as
 * `<key>`, because a host can use a title or an address as a map key and that would be a value in disguise.
 */
internal class ExtensionCensus {
    private class Stat(var count: Int = 0, val types: MutableSet<String> = linkedSetOf())

    private val methodById = object : LinkedHashMap<Long, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?): Boolean = size > MAX_OUTSTANDING
    }
    private val frames = sortedMapOf<String, Int>()
    private val meta = sortedMapOf<String, MutableMap<String, Stat>>()
    private val dropped = sortedMapOf<String, MutableMap<String, Stat>>()
    private val undecodable = sortedMapOf<String, Int>()

    fun outbound(text: String) {
        val frame = parse(text) ?: return
        val id = (frame["id"] as? JsonPrimitive)?.longOrNull ?: return
        val method = (frame["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        synchronized(this) { methodById[id] = method }
    }

    fun inbound(text: String) {
        val frame = parse(text) ?: return
        val method = (frame["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val id = (frame["id"] as? JsonPrimitive)?.longOrNull
        when {
            method != null && id == null -> notification(method, frame["params"] as? JsonObject ?: JsonObject(emptyMap()))
            method == null && id != null -> response(id, frame)
        }
    }

    private fun notification(method: String, params: JsonObject) {
        if (method != "action") return count("notification:$method", params, null)
        val type = ((params["action"] as? JsonObject)?.get("type") as? JsonPrimitive)?.takeIf { it.isString }?.content?.split('/')?.joinToString("/", transform = ::safeKey) ?: "?"
        // `origin` is nullable in the generated type, so a decode never shows it missing; count it here.
        tally(if (params["origin"] is JsonObject) "envelope:with-origin" else "envelope:no-origin")
        count("action:$type", params, ActionEnvelope.serializer()) { HostQuirks.action(it as JsonObject) }
    }

    private fun response(id: Long, frame: JsonObject) {
        val method = synchronized(this) { methodById.remove(id) } ?: "?"
        if (frame["error"] != null) return tally("error:$method")
        val serializer: KSerializer<*>? = when (method) {
            "initialize" -> InitializeResult.serializer()
            "listSessions" -> ListSessionsResult.serializer()
            "subscribe" -> SubscribeResult.serializer()
            "reconnect" -> ReconnectResult.serializer()
            "fetchTurns" -> FetchTurnsResult.serializer()
            "resolveSessionConfig" -> ResolveSessionConfigResult.serializer()
            "resourceRead" -> ResourceReadResult.serializer()
            else -> null
        }
        val result = frame["result"] ?: JsonNull
        count("result:$method", result, serializer) { HostQuirks.result(it) ?: it }
    }

    private fun tally(kind: String) {
        synchronized(this) { frames.merge(kind, 1, Int::plus) }
    }

    private fun count(kind: String, payload: JsonElement, serializer: KSerializer<*>?, repair: (JsonElement) -> JsonElement = { it }) {
        val raw = Paths.of(payload)
        val rewritten = serializer?.let { roundTrip(it, repair(payload)) }
        synchronized(this) {
            frames.merge(kind, 1, Int::plus)
            raw.filter { it.underMeta }.forEach { record(meta, kind, it) }
            when {
                serializer == null -> Unit
                rewritten == null -> undecodable.merge(kind, 1, Int::plus)
                else -> {
                    val kept = rewritten.filter { !it.underMeta }.mapTo(HashSet()) { it.path }
                    val lost = raw.filter { !it.underMeta && it.path !in kept }
                    val lostPaths = lost.mapTo(HashSet()) { it.path }
                    // A dropped subtree is one finding, not one per descendant.
                    lost.filter { it.parent !in lostPaths }.forEach { record(dropped, kind, it) }
                }
            }
        }
    }

    private fun <T> roundTrip(serializer: KSerializer<T>, payload: JsonElement): List<Paths.Entry>? = try {
        val decoded = Ahp.json.decodeFromJsonElement(serializer, payload)
        Paths.of(Ahp.json.encodeToJsonElement(serializer, decoded))
    } catch (_: Exception) {
        null
    }

    private fun record(into: MutableMap<String, MutableMap<String, Stat>>, kind: String, entry: Paths.Entry) {
        val stat = into.getOrPut(kind) { sortedMapOf() }.getOrPut(entry.path) { Stat() }
        stat.count++
        stat.types += entry.type
    }

    /** The report: paths, types and counts only. */
    fun report(): String = synchronized(this) {
        buildString {
            appendLine("== frames seen ==")
            frames.forEach { (kind, n) -> appendLine("  $kind  x$n") }
            appendLine("== paths under _meta (no values) ==")
            section(meta)
            appendLine("== paths the generated types drop, outside _meta (no values) ==")
            section(dropped)
            appendLine("== frames the generated types could not decode ==")
            if (undecodable.isEmpty()) appendLine("  none") else undecodable.forEach { (kind, n) -> appendLine("  $kind  x$n") }
        }
    }

    private fun StringBuilder.section(map: Map<String, Map<String, Stat>>) {
        if (map.isEmpty()) return appendLine("  none").let { }
        map.forEach { (kind, paths) ->
            appendLine("  $kind")
            paths.forEach { (path, stat) -> appendLine("    $path  ${stat.types.joinToString("|")}  x${stat.count}") }
        }
    }

    private fun parse(text: String): JsonObject? = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Every path in a JSON value, with the JSON type found there. Array elements share one path (`list[]`). Null leaves are left out, as the library's own encoder leaves them out. */
    internal object Paths {
        data class Entry(val path: String, val type: String, val underMeta: Boolean, val parent: String)

        fun of(root: JsonElement): List<Entry> = buildList { walk(root, "", false, this) }

        private fun walk(element: JsonElement, path: String, underMeta: Boolean, out: MutableList<Entry>) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    if (value is JsonNull) return@forEach
                    val child = if (path.isEmpty()) safeKey(key) else path + "." + safeKey(key)
                    val inMeta = underMeta || key == META
                    out += Entry(child, typeOf(value), inMeta, path)
                    walk(value, child, inMeta, out)
                }
                is JsonArray -> element.forEach { item ->
                    if (item is JsonNull) return@forEach
                    val child = "$path[]"
                    out += Entry(child, typeOf(item), underMeta, path)
                    walk(item, child, underMeta, out)
                }
                else -> Unit
            }
        }

        private fun typeOf(element: JsonElement): String = when (element) {
            is JsonObject -> "object"
            is JsonArray -> "array"
            is JsonNull -> "null"
            is JsonPrimitive -> if (element.isString) "string" else if (element.contentOrNull == "true" || element.contentOrNull == "false") "boolean" else "number"
        }
    }

    companion object {
        const val META = "_meta"
        private const val MAX_OUTSTANDING = 1024
        private val PLAIN_KEY = Regex("[A-Za-z_][A-Za-z0-9_.:-]{0,63}")

        /** A key that is safe to print: a short identifier with few digits. Anything else could be a title, an address or an id. */
        internal fun safeKey(key: String): String =
            if (PLAIN_KEY.matches(key) && key.count { it.isDigit() } < 8) key else "<key>"
    }
}
