package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.SessionState
import com.microsoft.agenthostprotocol.generated.SessionSummary
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * What a host says about a session beyond the protocol's own fields, read from `_meta` (E1). Only what a real host has been seen to send
 * is read (`docs/verification/extensions.md`); the names are the host's own, so a count is not called "ahead" until a host says so.
 */
data class SessionExtras(
    val branch: String? = null,
    /** `outgoingChanges`: changes not yet pushed. */
    val outgoing: Int? = null,
    /** `incomingChanges`: changes upstream that are not yet here. */
    val incoming: Int? = null,
    val uncommitted: Int? = null,
    val detachedHead: Boolean = false,
) {
    val isEmpty: Boolean get() = this == NONE

    /** The branch as a person reads it: its name, or "Detached HEAD" when the host says there is none. Null when the host said nothing about it. */
    val branchLabel: String? get() = branch ?: if (detachedHead) DETACHED else null

    /** The non-zero counts in the host's own words ("2 outgoing, 3 uncommitted"), or null when there are none. */
    val countsText: String?
        get() = listOfNotNull(
            outgoing?.takeIf { it > 0 }?.let { "$it outgoing" },
            incoming?.takeIf { it > 0 }?.let { "$it incoming" },
            uncommitted?.takeIf { it > 0 }?.let { "$it uncommitted" },
        ).joinToString(", ").ifEmpty { null }

    companion object {
        val NONE = SessionExtras()
        const val DETACHED = "Detached HEAD"
    }
}

/**
 * Reads host extensions out of a decoded `_meta`. Every field is optional: a missing key, a value of the wrong type, an oversized or
 * hostile value gives `null` or the default, never an exception, so a host that sends something new cannot break a screen.
 */
object HostExtras {
    const val MAX_TEXT = 200
    private const val MAX_ROOTS = 16
    private const val MAX_COUNT = 1_000_000

    fun session(meta: Map<String, JsonElement>?): SessionExtras {
        if (meta.isNullOrEmpty()) return SessionExtras.NONE
        val root = JsonObject(meta)
        // `git` for a single folder; `gitData.<folder>` for a multi-root workspace, one object per folder.
        val folders = (root.at("gitData") as? JsonObject)?.values.orEmpty().asSequence().take(MAX_ROOTS).mapNotNull { it as? JsonObject }.map { it to "" }
        val candidates = (if (root.at("git") is JsonObject || root.keys.any { it.startsWith("git.") }) sequenceOf(root to "git.") else emptySequence()) + folders
        val (source, prefix) = candidates.firstOrNull { (from, where) -> from.text("${where}branchName") != null } ?: candidates.firstOrNull() ?: return SessionExtras.NONE
        return SessionExtras(
            branch = source.text("${prefix}branchName"),
            outgoing = source.count("${prefix}outgoingChanges"),
            incoming = source.count("${prefix}incomingChanges"),
            uncommitted = source.count("${prefix}uncommittedChanges"),
            detachedHead = (source.at("${prefix}isDetachedHead") as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: false,
        )
    }

    /** Looks up a dotted path. A host may send `{"a.b": 1}` or `{"a": {"b": 1}}` (both are seen live), so every split of the path is tried. */
    internal fun JsonObject.at(path: String): JsonElement? {
        this[path]?.let { return it }
        var dot = path.indexOf('.')
        while (dot >= 0) {
            ((this[path.substring(0, dot)] as? JsonObject)?.at(path.substring(dot + 1)))?.let { return it }
            dot = path.indexOf('.', dot + 1)
        }
        return null
    }

    private fun JsonObject.text(key: String): String? {
        val primitive = at(key) as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.content.take(MAX_TEXT * 4).filterNot { it.isISOControl() }.trim().take(MAX_TEXT).trim().ifEmpty { null }
    }

    private fun JsonObject.count(key: String): Int? {
        val primitive = at(key) as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.content.toIntOrNull()?.takeIf { it in 0..MAX_COUNT }
    }
}

val SessionSummary.extras: SessionExtras get() = HostExtras.session(meta)

val SessionState.extras: SessionExtras get() = HostExtras.session(meta)
