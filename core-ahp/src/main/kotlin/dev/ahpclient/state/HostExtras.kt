package dev.ahpclient.state
import dev.ahpclient.platform.*

import dev.ahpclient.app.SafeLinks
import com.microsoft.agenthostprotocol.generated.ChatOrigin
import com.microsoft.agenthostprotocol.generated.ChatState
import com.microsoft.agenthostprotocol.generated.Message
import com.microsoft.agenthostprotocol.generated.SessionOriginAutomation
import com.microsoft.agenthostprotocol.generated.SessionOriginUnknown
import com.microsoft.agenthostprotocol.generated.SessionState
import com.microsoft.agenthostprotocol.generated.SessionSummary
import com.microsoft.agenthostprotocol.generated.ToolCallState
import com.microsoft.agenthostprotocol.generated.ToolCallStateAuthRequired
import com.microsoft.agenthostprotocol.generated.ToolCallStateCancelled
import com.microsoft.agenthostprotocol.generated.ToolCallStateCompleted
import com.microsoft.agenthostprotocol.generated.ToolCallStatePendingConfirmation
import com.microsoft.agenthostprotocol.generated.ToolCallStatePendingResultConfirmation
import com.microsoft.agenthostprotocol.generated.ToolCallStateRunning
import com.microsoft.agenthostprotocol.generated.ToolCallStateStreaming
import com.microsoft.agenthostprotocol.generated.UsageInfo
import kotlin.math.roundToLong
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
        get() = url.takeIf { SafeLinks.isSafeVerificationUri(it) && parseUri(it)?.path.orEmpty().length > 1 }

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
    val model: String? = null,
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
    val toolKind: String? = null,
    val durationMs: Long? = null,
    val mcpServerName: String? = null,
    val subagentAgentName: String? = null,
    val subagentDescription: String? = null,
) {
    val isEmpty: Boolean get() = this == NONE

    /** How long a finished tool call took ("4s", "1m 4s"), or null when the host did not say. */
    val durationText: String? get() = TurnDuration.format(durationMs)

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
/**
 * Token counts and optional cost reported for a turn or aggregated across a chat (`UsageInfo` and its `_meta`, E1).
 * Every count and cost is positive and bounded; when no positive count or cost is present, [HostExtras.usage] returns `null`.
 */
data class TurnUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cacheReadTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val costAmount: Double? = null,
    val costCurrency: String? = null,
    val model: String? = null,
) {
    /** Total input + output tokens (or cache tokens when input/output are omitted), or null when none are positive. */
    val totalTokens: Long?
        get() {
            val inOut = (inputTokens ?: 0L) + (outputTokens ?: 0L)
            if (inOut > 0L) return inOut
            val cache = (cacheReadTokens ?: 0L) + (cacheWriteTokens ?: 0L)
            return cache.takeIf { it > 0L }
        }

    /** Cost formatted for display (`"$0.04"`, `"$0.0025"`, `"0.04 EUR"`), or null when unreported or zero. */
    val costText: String?
        get() {
            val amount = costAmount?.takeIf { it > 0.0 && it.isFinite() } ?: return null
            val digits = if (amount < 0.01) {
                val scaled = (amount * 10_000.0).roundToLong()
                val intPart = scaled / 10_000L
                val fracPart = (scaled % 10_000L).toString().padStart(4, '0')
                "$intPart.$fracPart".trimEnd('0').trimEnd('.')
            } else {
                val scaled = (amount * 100.0).roundToLong()
                val intPart = scaled / 100L
                val fracPart = (scaled % 100L).toString().padStart(2, '0')
                "$intPart.$fracPart"
            }
            val cur = costCurrency?.trim()?.takeIf { it.isNotEmpty() }
            return if (cur == null || cur.equals("USD", ignoreCase = true)) "$$digits" else "$digits $cur"
        }

    /** Compact one-line summary for a `TurnEnd` row (`"71.2k tok"`, `"71.2k tok · $0.04"`), or null when empty. */
    val summaryText: String?
        get() = listOfNotNull(
            totalTokens?.let { "${formatTokens(it)} tok" },
            costText,
        ).joinToString(" \u00b7 ").ifEmpty { null }

    /** Full breakdown for the session `DetailsSheet` (`"68k in, 3.2k out · 58.8k cached · $0.04"`), or null when empty. */
    val detailText: String?
        get() {
            val inOut = listOfNotNull(
                inputTokens?.takeIf { it > 0L }?.let { "${formatTokens(it)} in" },
                outputTokens?.takeIf { it > 0L }?.let { "${formatTokens(it)} out" },
            ).joinToString(", ").ifEmpty { null }
            return listOfNotNull(
                inOut,
                cacheReadTokens?.takeIf { it > 0L }?.let { "${formatTokens(it)} cached" },
                cacheWriteTokens?.takeIf { it > 0L }?.let { "${formatTokens(it)} cache write" },
                costText,
            ).joinToString(" \u00b7 ").ifEmpty { null }
        }

    companion object {
        fun formatTokens(tokens: Long): String {
            if (tokens < 1_000L) return tokens.toString()
            if (tokens < 999_950L) {
                return formatOneDecimal(tokens / 1_000.0) + "k"
            }
            return formatOneDecimal(tokens / 1_000_000.0) + "M"
        }

        private fun formatOneDecimal(value: Double): String {
            val tenths = (value * 10.0).roundToLong()
            val intPart = tenths / 10L
            val fracPart = tenths % 10L
            return if (fracPart == 0L) intPart.toString() else "$intPart.$fracPart"
        }
    }
}

object HostExtras {
    const val MAX_TEXT = 200
    const val MAX_URL = 500
    const val MAX_STATE = 40
    const val MAX_PULL_REQUESTS = 10
    private const val MAX_ROOTS = 16
    private const val MAX_COUNT = 1_000_000
    private const val MAX_DURATION_MS = 86_400_000L
    private const val MAX_TOKENS = 1_000_000_000L
    private const val MAX_COST = 1_000_000.0

    private val CACHE_WRITE_KEYS = listOf("ahpd.cacheWriteTokens", "cacheWriteTokens", "vscode.cacheWriteTokens")
    private val COST_AMOUNT_KEYS = listOf("cost", "ahpd.cost.amount", "cost.amount", "vscode.cost")
    private val COST_CURRENCY_KEYS = listOf("ahpd.cost.currency", "cost.currency", "currency")

    private val PROGRESS_KEYS = listOf("progressMessage", "vscode.toolCall.progressMessage", "ahpd.progressMessage")
    private val TOOL_KIND_KEYS = listOf("toolKind", "vscode.toolCall.toolKind", "ahpd.toolKind")
    private val DURATION_KEYS = listOf("ahpd.durationMs", "durationMs")
    private val MCP_SERVER_KEYS = listOf("mcpServerName", "vscode.toolCall.mcpServerName")
    private val SUBAGENT_NAME_KEYS = listOf("subagentAgentName", "vscode.toolCall.subagentAgentName")
    private val SUBAGENT_DESC_KEYS = listOf("subagentDescription", "vscode.toolCall.subagentDescription")

    private val MODEL_KEYS = listOf("model", "ahpd.model", "vscode.model")
    private val HIDDEN_KEYS = listOf("vscode.chat.hiddenFromTranscript", "requestHiddenFromTranscript", "hiddenFromTranscript")
    private val EXTERNAL_KEYS = listOf("vscode.external", "external", "ahpd.external")
    private val INITIATOR_KEYS = listOf("vscode.session.initiator.name", "initiator.name", "initiator", "ahpd.initiator")
    private val ARGUMENT_HINT_KEYS = listOf("vscode.skill.argumentHint", "ahpd.argumentHint", "argumentHint")
    private val SPAWNED_ORIGINS = setOf("automation", "subagent", "tool", "external")
    private val WHITESPACE_RUN = Regex("""[\r\n\t ]+""")

    /** Whether `_meta` marks the session as created outside the primary agent panel (`vscode.external`). */
    fun isExternal(meta: Map<String, JsonElement>?): Boolean {
        if (meta.isNullOrEmpty()) return false
        val root = JsonObject(meta)
        return EXTERNAL_KEYS.any { root.flag(it) }
    }

    /** Which client or surface started the session (`vscode.session.initiator.name`), or null when the host did not say. */
    fun initiator(meta: Map<String, JsonElement>?): String? {
        if (meta.isNullOrEmpty()) return null
        val root = JsonObject(meta)
        return INITIATOR_KEYS.firstNotNullOfOrNull { root.text(it, MAX_STATE) }
    }

    internal fun isSpawnedOriginKind(kind: String?): Boolean = kind?.lowercase() in SPAWNED_ORIGINS

    /** An argument hint the host attaches to a skill or prompt customization (`vscode.skill.argumentHint`, `ahpd.argumentHint`), or null. */
    fun argumentHint(meta: Map<String, JsonElement>?): String? {
        if (meta.isNullOrEmpty()) return null
        val root = JsonObject(meta)
        return ARGUMENT_HINT_KEYS.firstNotNullOfOrNull { root.oneLine(it, MAX_TEXT) }
    }

    fun session(meta: Map<String, JsonElement>?): SessionExtras {
        if (meta.isNullOrEmpty()) return SessionExtras.NONE
        val root = JsonObject(meta)
        // `git` for a single folder; `gitData.<folder>` for a multi-root workspace, one object per folder.
        val folders = (root.at("gitData") as? JsonObject)?.values.orEmpty().asSequence().take(MAX_ROOTS).mapNotNull { it as? JsonObject }.map { it to "" }
        val candidates = (if (root.at("git") is JsonObject || root.keys.any { it.startsWith("git.") }) sequenceOf(root to "git.") else emptySequence()) + folders
        val picked = candidates.firstOrNull { (from, where) -> (from.text("${where}branchName") ?: from.text("${where}branch")) != null } ?: candidates.firstOrNull()
        val prs = picked?.let { (from, where) -> from.pullRequests(where) }.orEmpty().ifEmpty { root.pullRequests("") }
        val model = MODEL_KEYS.firstNotNullOfOrNull { root.text(it) }
        val (source, prefix) = picked ?: (root to "")
        return SessionExtras(
            branch = source.text("${prefix}branchName") ?: source.text("${prefix}branch") ?: root.text("branchName") ?: root.text("branch"),
            outgoing = source.count("${prefix}outgoingChanges") ?: source.count("${prefix}ahead") ?: root.count("outgoingChanges") ?: root.count("ahead"),
            incoming = source.count("${prefix}incomingChanges") ?: source.count("${prefix}behind") ?: root.count("incomingChanges") ?: root.count("behind"),
            uncommitted = source.count("${prefix}uncommittedChanges") ?: source.count("${prefix}uncommitted") ?: root.count("uncommittedChanges") ?: root.count("uncommitted"),
            detachedHead = source.flag("${prefix}isDetachedHead") || root.flag("isDetachedHead"),
            pullRequests = prs,
            model = model,
        )
    }

    /**
     * Reads [StepExtras] for [call]. Only a [ToolCallStateRunning] call has a live progress message (E3); any other state returns
     * [StepExtras.NONE] so a finished or cancelled call shows its final result.
     */
    fun step(call: ToolCallState): StepExtras = when (call) {
        is ToolCallStateStreaming -> stepMeta(call.value.meta, running = false, completed = false)
        is ToolCallStatePendingConfirmation -> stepMeta(call.value.meta, running = false, completed = false)
        is ToolCallStatePendingResultConfirmation -> stepMeta(call.value.meta, running = false, completed = false)
        is ToolCallStateRunning -> stepMeta(call.value.meta, running = true, completed = false)
        is ToolCallStateAuthRequired -> stepMeta(call.value.meta, running = false, completed = false)
        is ToolCallStateCompleted -> stepMeta(call.value.meta, running = false, completed = true)
        is ToolCallStateCancelled -> stepMeta(call.value.meta, running = false, completed = false)
        else -> StepExtras.NONE
    }

    /** Reads [StepExtras] from a running tool call's `_meta`. */
    fun stepRunning(meta: Map<String, JsonElement>?): StepExtras = stepMeta(meta, running = true, completed = false)

    internal fun stepMeta(meta: Map<String, JsonElement>?, running: Boolean, completed: Boolean): StepExtras {
        if (meta.isNullOrEmpty()) return StepExtras.NONE
        val root = JsonObject(meta)
        val progress = if (running) {
            PROGRESS_KEYS.firstNotNullOfOrNull { root.oneLine(it) }?.let { MarkdownPlain.of(it).take(MAX_TEXT).trim().ifEmpty { null } }
        } else {
            null
        }
        val duration = if (completed) DURATION_KEYS.firstNotNullOfOrNull { root.duration(it) } else null
        return StepExtras(
            progressMessage = progress,
            toolKind = TOOL_KIND_KEYS.firstNotNullOfOrNull { root.text(it, MAX_STATE) },
            durationMs = duration,
            mcpServerName = MCP_SERVER_KEYS.firstNotNullOfOrNull { root.text(it) },
            subagentAgentName = SUBAGENT_NAME_KEYS.firstNotNullOfOrNull { root.text(it) },
            subagentDescription = SUBAGENT_DESC_KEYS.firstNotNullOfOrNull { root.oneLine(it)?.take(MAX_TEXT)?.trim()?.ifEmpty { null } },
        )
    }

    /** Reads [MessageExtras] from a message's `_meta` (E4). */
    /** Reads [TurnUsage] from a turn's [UsageInfo] and its `_meta`, or returns `null` when no positive token count or cost was reported. */
    fun usage(info: UsageInfo?): TurnUsage? {
        info ?: return null
        val root = info.meta?.takeIf { it.isNotEmpty() }?.let(::JsonObject)
        val input = info.inputTokens?.takeIf { it in 1L..MAX_TOKENS }
        val output = info.outputTokens?.takeIf { it in 1L..MAX_TOKENS }
        val cacheRead = info.cacheReadTokens?.takeIf { it in 1L..MAX_TOKENS }
        val cacheWrite = root?.let { r -> CACHE_WRITE_KEYS.firstNotNullOfOrNull { r.tokens(it) } }
        val costAmount = root?.let { r -> COST_AMOUNT_KEYS.firstNotNullOfOrNull { r.amount(it) } }
        val costCurrency = if (costAmount != null) root.let { r -> COST_CURRENCY_KEYS.firstNotNullOfOrNull { r.text(it, MAX_STATE) } } else null
        val model = info.model?.filterNot { it.isISOControl() }?.trim()?.take(MAX_TEXT)?.trim()?.ifEmpty { null }
        if (input == null && output == null && cacheRead == null && cacheWrite == null && costAmount == null) return null
        return TurnUsage(
            inputTokens = input,
            outputTokens = output,
            cacheReadTokens = cacheRead,
            cacheWriteTokens = cacheWrite,
            costAmount = costAmount,
            costCurrency = costCurrency,
            model = model,
        )
    }

    /** Aggregates [TurnUsage] across all completed turns and the active turn of [chat], or returns `null` when none report usage. */
    fun chatUsage(chat: ChatState?): TurnUsage? {
        chat ?: return null
        val turns = (chat.turns.map { it.usage } + listOfNotNull(chat.activeTurn?.usage)).mapNotNull(::usage)
        if (turns.isEmpty()) return null
        fun sumTokens(selector: (TurnUsage) -> Long?): Long? {
            val values = turns.mapNotNull(selector)
            if (values.isEmpty()) return null
            return values.sumOf { it }.coerceAtMost(MAX_TOKENS).takeIf { it > 0L }
        }
        val costTurns = turns.filter { it.costAmount != null }
        val currencies = costTurns.mapNotNull { it.costCurrency?.uppercase() }.distinct()
        val compatibleCost = currencies.size <= 1 && (currencies.isEmpty() || costTurns.all { it.costCurrency == null || it.costCurrency.equals(currencies.single(), ignoreCase = true) })
        val totalCost = if (costTurns.isNotEmpty() && compatibleCost) {
            costTurns.sumOf { it.costAmount!! }.coerceAtMost(MAX_COST).takeIf { it > 0.0 }
        } else {
            null
        }
        val currency = if (totalCost != null) costTurns.firstNotNullOfOrNull { it.costCurrency } else null
        return TurnUsage(
            inputTokens = sumTokens { it.inputTokens },
            outputTokens = sumTokens { it.outputTokens },
            cacheReadTokens = sumTokens { it.cacheReadTokens },
            cacheWriteTokens = sumTokens { it.cacheWriteTokens },
            costAmount = totalCost,
            costCurrency = currency,
            model = turns.lastOrNull { it.model != null }?.model,
        )
    }

    private fun JsonObject.tokens(key: String): Long? {
        val primitive = at(key) as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.content.toLongOrNull()?.takeIf { it in 1L..MAX_TOKENS }
    }

    private fun JsonObject.amount(key: String): Double? {
        val primitive = at(key) as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.content.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 && it <= MAX_COST }
    }

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

    private fun JsonObject.duration(key: String): Long? {
        val primitive = at(key) as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.content.toLongOrNull()?.takeIf { it in 0..MAX_DURATION_MS }
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

/** Whether the host marks this session as created externally (`vscode.external`). */
val SessionSummary.isExternal: Boolean get() = HostExtras.isExternal(meta)

/** Whether this session was started by an automation or an unknown spawned origin. */
val SessionSummary.isAutomation: Boolean
    get() = when (val o = origin) {
        is SessionOriginAutomation -> true
        is SessionOriginUnknown -> HostExtras.isSpawnedOriginKind((o.raw["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
        else -> false
    }

/** Whether the session's main chat (or only chat) was spawned by a tool call (`ChatOrigin.Tool`). */
val SessionSummary.isToolSpawned: Boolean
    get() {
        val list = chats.orEmpty()
        if (list.isEmpty()) return false
        val main = list.firstOrNull { it.resource == defaultChat } ?: list.first()
        return main.origin is ChatOrigin.Tool
    }

/** Whether this session was created externally, spawned by a tool/subagent, or started by an automation. */
val SessionSummary.isSpawnedOrExternal: Boolean
    get() = isExternal || isAutomation || isToolSpawned

/** Where the session was created ("Created In"): the explicit initiator name, or a fallback derived from its origin/external flag. */
val SessionSummary.initiatorLabel: String?
    get() {
        HostExtras.initiator(meta)?.let { return it }
        if (isAutomation) return "Automation"
        if (isToolSpawned) return "Subagent"
        val m = meta ?: return null
        if ("vscode.external" in m || "external" in m || "ahpd.external" in m || (m["vscode"] as? JsonObject)?.containsKey("external") == true) {
            return if (isExternal) "External" else "VS Code"
        }
        return null
    }

/** The workspace or repository name for grouping and filtering: `project.displayName`, or the folder basename of the first working directory. */
val SessionSummary.workspaceLabel: String?
    get() {
        project?.displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val firstDir = workingDirectories?.firstOrNull { it.isNotBlank() } ?: return null
        val path = displayPath(firstDir).trimEnd('/', '\\')
        val name = path.substringAfterLast('/').substringAfterLast('\\').trim()
        return name.ifEmpty { path.takeIf { it.isNotEmpty() } }
    }

val SessionState.extras: SessionExtras get() = HostExtras.session(meta)

val ToolCallState.extras: StepExtras get() = HostExtras.step(this)

val Message.extras: MessageExtras get() = HostExtras.message(meta)

val UsageInfo.extras: TurnUsage? get() = HostExtras.usage(this)
