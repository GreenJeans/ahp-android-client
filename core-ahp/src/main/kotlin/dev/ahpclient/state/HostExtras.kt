package dev.ahpclient.state

import dev.ahpclient.app.SafeLinks
import com.microsoft.agenthostprotocol.generated.Message
import com.microsoft.agenthostprotocol.generated.SessionState
import com.microsoft.agenthostprotocol.generated.SessionSummary
import com.microsoft.agenthostprotocol.generated.ToolCallState
import com.microsoft.agenthostprotocol.generated.ToolCallStateRunning
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * One pull request the host reports for a session (E1, E2). [state] is the host's word (open, merged, closed, or an unknown state kept as
 * text). [openableUrl] is [url] only when it is a plain `https://github.com/...` address that [SafeLinks] allows; anything else is shown as
 * text, never made a link.
 */
data class PullRequest(
    val url: String,
    val state: String,
    val branch: String? = null,
) {
    /** The pull-request number when [url] has `/pull/<n>` or `/pulls/<n>`, or null. */
    val number: Int?
        get() = PR_NUMBER.find(url)?.groupValues?.get(1)?.toIntOrNull()

    /** A short chip label carrying the state in words so colour is never the only cue ("PR #42 · open", or "PR · merged"). */
    val label: String
        get() = if (number != null) "PR #$number · $state" else "PR · $state"

    /** The address to open on tap, or null when [url] is not a safe `https://github.com/...` link with a path. */
    val openableUrl: String?
        get() = url.takeIf { SafeLinks.isSafeVerificationUri(it) && java.net.URI(it).path.orEmpty().length > 1 }

    private companion object {
        val PR_NUMBER = Regex("""/pulls?/(\d+)(?:[/?#]|$)""")
    }
}

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
    val pullRequests: List<PullRequest> = emptyList(),
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
 * What a host says about a running tool call beyond the protocol's own fields (E1, E3). [progressMessage] is read only while the call is
 * running; once it finishes, the normal result view takes over. Plain one-line text, never put in a notification or a log.
 */
data class StepExtras(
    val progressMessage: String? = null,
) {
    val isEmpty: Boolean get() = this == NONE

    companion object {
        val NONE = StepExtras()
    }
}

/**
 * What a host says about a message beyond the protocol's own fields (E1, E4). When [hiddenFromTranscript] is true, the message is a
 * synthetic or system prompt that VS Code hides from the transcript.
 */
data class MessageExtras(
    val hiddenFromTranscript: Boolean = false,
) {
    val isEmpty: Boolean get() = this == NONE

    companion object {
        val NONE = MessageExtras()
    }
}

/**
 * Reads host extensions out of a decoded `_meta`. Every field is optional: a missing key, a value of the wrong type, an oversized or
 * hostile value gives `null` or the default, never an exception, so a host that sends something new cannot break a screen.
 */
object HostExtras {
    const val MAX_TEXT = 200
    const val MAX_URL = 500
    const val MAX_STATE = 40
    const val MAX_PULL_REQUESTS = 10
    private const val MAX_ROOTS = 16
    private const val MAX_COUNT = 1_000_000

    private val PROGRESS_KEYS = listOf("progressMessage", "vscode.toolCall.progressMessage", "ahpd.progressMessage")

    private val HIDDEN_KEYS = listOf("vscode.chat.hiddenFromTranscript", "requestHiddenFromTranscript", "hiddenFromTranscript")
    private val WHITESPACE_RUN = Regex("""[\r\n\t ]+""")


    fun session(meta: Map<String, JsonElement>?): SessionExtras {
        if (meta.isNullOrEmpty()) return SessionExtras.NONE
        val root = JsonObject(meta)
        // `git` for a single folder; `gitData.<folder>` for a multi-root workspace, one object per folder.
        val folders = (root.at("gitData") as? JsonObject)?.values.orEmpty().asSequence().take(MAX_ROOTS).mapNotNull { it as? JsonObject }.map { it to "" }
        val candidates = (if (root.at("git") is JsonObject || root.keys.any { it.startsWith("git.") }) sequenceOf(root to "git.") else emptySequence()) + folders
        val picked = candidates.firstOrNull { (from, where) -> from.text("${where}branchName") != null } ?: candidates.firstOrNull()
        val prs = picked?.let { (from, where) -> from.pullRequests(where) }.orEmpty().ifEmpty { root.pullRequests("") }
        if (picked == null) return if (prs.isEmpty()) SessionExtras.NONE else SessionExtras(pullRequests = prs)
        val (source, prefix) = picked
        return SessionExtras(
            branch = source.text("${prefix}branchName"),
            outgoing = source.count("${prefix}outgoingChanges"),
            incoming = source.count("${prefix}incomingChanges"),
            uncommitted = source.count("${prefix}uncommittedChanges"),
            detachedHead = source.flag("${prefix}isDetachedHead"),
            pullRequests = prs,
        )
    }

    /**
     * Reads [StepExtras] for [call]. Only a [ToolCallStateRunning] call has a live progress message (E3); any other state returns
     * [StepExtras.NONE] so a finished or cancelled call shows its final result.
     */
    fun step(call: ToolCallState): StepExtras = when (call) {
        is ToolCallStateRunning -> stepRunning(call.value.meta)
        else -> StepExtras.NONE
    }

    /** Reads [StepExtras] from a running tool call's `_meta`. */
    fun stepRunning(meta: Map<String, JsonElement>?): StepExtras {
        if (meta.isNullOrEmpty()) return StepExtras.NONE
        val root = JsonObject(meta)
        val raw = PROGRESS_KEYS.firstNotNullOfOrNull { root.oneLine(it) } ?: return StepExtras.NONE
        val plain = MarkdownPlain.of(raw).take(MAX_TEXT).trim().ifEmpty { null } ?: return StepExtras.NONE
        return StepExtras(progressMessage = plain)
    }

    /** Reads [MessageExtras] from a message's `_meta` (E4). */
    fun message(meta: Map<String, JsonElement>?): MessageExtras {
        if (meta.isNullOrEmpty()) return MessageExtras.NONE
        val root = JsonObject(meta)
        return MessageExtras(hiddenFromTranscript = HIDDEN_KEYS.any { root.flag(it) })
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

    private fun JsonObject.text(key: String, max: Int = MAX_TEXT): String? {
        val primitive = at(key) as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.content.take(max * 4).filterNot { it.isISOControl() }.trim().take(max).trim().ifEmpty { null }
    }

    private fun JsonObject.oneLine(key: String, max: Int = MAX_TEXT): String? {
        val primitive = at(key) as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.content.take(max * 4).replace(WHITESPACE_RUN, " ").filterNot { it.isISOControl() }.trim().ifEmpty { null }
    }

    private fun JsonObject.count(key: String): Int? {
        val primitive = at(key) as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.content.toIntOrNull()?.takeIf { it in 0..MAX_COUNT }
    }

    private fun JsonObject.flag(key: String): Boolean =
        (at(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: false

    private fun JsonObject.pullRequests(prefix: String): List<PullRequest> {
        val array = (at("${prefix}pullRequests") as? JsonArray)?.asSequence()
            ?: (at("${prefix}pullRequest") as? JsonObject)?.let { sequenceOf(it) }
            ?: return emptyList()
        return array.take(MAX_PULL_REQUESTS).mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val url = obj.text("url", MAX_URL) ?: obj.text("link", MAX_URL) ?: obj.text("uri", MAX_URL) ?: return@mapNotNull null
            val state = obj.text("state", MAX_STATE) ?: obj.text("status", MAX_STATE) ?: "open"
            val branch = obj.text("branchName", MAX_TEXT) ?: obj.text("branch", MAX_TEXT)
            PullRequest(url = url, state = state, branch = branch)
        }.toList()
    }
}

val SessionSummary.extras: SessionExtras get() = HostExtras.session(meta)

val SessionState.extras: SessionExtras get() = HostExtras.session(meta)

val ToolCallState.extras: StepExtras get() = HostExtras.step(this)

val Message.extras: MessageExtras get() = HostExtras.message(meta)
