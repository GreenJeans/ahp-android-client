package dev.ahpclient.state

import dev.ahpclient.ahp.ChatConfigKeys
import com.microsoft.agenthostprotocol.generated.ChatOrigin
import com.microsoft.agenthostprotocol.generated.SessionConfigPropertySchema
import com.microsoft.agenthostprotocol.generated.SessionState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The two per-chat settings this app shows and sets: how the agent works, and how freely its tool calls run. */
enum class ConfigKind { MODE, APPROVALS }

/**
 * One value a setting may take. [label] is this app's own word for it (never the host's, so what a screen or the device check says
 * cannot be changed by what the host calls it); [description] is the host's own line, untrusted, cut and shown as plain text.
 * [available] is false when the host says it is not allowed right now (its policy may withhold some approval levels).
 */
data class ConfigChoice(val value: String, val label: String, val description: String?, val available: Boolean)

/**
 * One setting of one chat, as the host reports it now: what it is [current]ly (the chat's own value over the session's, as the host
 * resolves it), what it may be, and whether this app may change it ([writable]: the host lets the chat set it for itself, and it is
 * not read only). Loosening it (more freedom for the agent) is what the device check stands in front of.
 */
data class ConfigControl(
    val kind: ConfigKind,
    val key: String,
    val choices: List<ConfigChoice>,
    val current: String?,
    val writable: Boolean,
) {
    val title: String get() = if (kind == ConfigKind.MODE) "Mode" else "Approvals"

    val currentLabel: String? get() = current?.let { value -> choices.firstOrNull { it.value == value }?.label ?: ChatConfig.labelOf(kind, key, value) }

    /** True when the setting is at its quiet default (Interactive, Manual), or is not known: nothing to point out. Plan and Autopilot are not. */
    val isDefault: Boolean
        get() = when {
            current == null -> true
            kind == ConfigKind.MODE -> current == "interactive"
            else -> ChatConfig.rank(kind, key, current) == 0
        }

    /** True when approvals are at the top level, where tool calls run without asking: the one state a screen must never leave unremarked. */
    val isAllowAll: Boolean get() = kind == ConfigKind.APPROVALS && ChatConfig.rank(kind, key, current) == 2

    /** True when [value] is one the host offers now and allows. */
    fun offers(value: String): Boolean = choices.any { it.value == value && it.available }

    /** True when moving to [value] gives the agent more freedom than now: Allow all or Assisted over Manual, or Autopilot. An unknown current value counts as the least. */
    fun loosens(value: String): Boolean {
        val to = ChatConfig.rank(kind, key, value) ?: return false
        return to > (ChatConfig.rank(kind, key, current) ?: 0)
    }
}

/** A chat's mode and approvals. A control is null when the host offers none for this chat. */
data class ChatConfigView(val mode: ConfigControl?, val approvals: ConfigControl?) {
    val controls: List<ConfigControl> get() = listOfNotNull(mode, approvals)

    fun control(key: String): ConfigControl? = controls.firstOrNull { it.key == key }

    /** The settings that are not at their quiet default, which the message box points out so an Allow all is never a surprise. */
    val notable: List<ConfigControl> get() = controls.filter { !it.isDefault }

    companion object {
        val NONE = ChatConfigView(null, null)
    }
}

/**
 * Works out a chat's mode and approvals from the session's schema and values, the chat's own values and which properties a chat may set
 * for itself. It follows the host's own rules, so what is shown is what is in force:
 * - the approvals property is `autoApprove` (default, assisted, autoApprove) or, on a host that has no such property, `approvalMode`
 *   (manual, assisted, allow-all); the mode property is `mode` (interactive, plan, autopilot). Any other value is not offered;
 * - a chat's own value hides the session's, but a host-reported `effectiveApprovalMode` of the session does not apply once the chat
 *   chose its own approvals;
 * - a host may report which approval levels are allowed now (`availableApprovalModes`), and the others are shown withheld;
 * - only a chat that is not a tool's subagent may set its own, and only a property the schema marks `chatMutable` and not read only.
 * Pure, so it is tested without a phone.
 */
object ChatConfig {
    private const val MAX_DESCRIPTION = 80
    private val MODE_LABELS = mapOf("interactive" to "Interactive", "plan" to "Plan", "autopilot" to "Autopilot")
    private val APPROVAL_LABELS = listOf("Manual", "Assisted", "Allow all")

    /** The approval level of a value: 0 manual, 1 assisted, 2 allow all. Null when the value is not one this app knows. */
    private fun approvalLevel(key: String, value: String): Int? = when (key) {
        ChatConfigKeys.AUTO_APPROVE -> when (value) { "default" -> 0; "assisted" -> 1; "autoApprove" -> 2; else -> null }
        ChatConfigKeys.APPROVAL_MODE -> when (value) { "manual" -> 0; "assisted" -> 1; "allow-all" -> 2; else -> null }
        else -> null
    }

    /** How much freedom a value gives the agent, for comparing two of the same setting. Null for a value that is not known. */
    internal fun rank(kind: ConfigKind, key: String, value: String?): Int? = when {
        value == null -> null
        kind == ConfigKind.APPROVALS -> approvalLevel(key, value)
        value == "autopilot" -> 2
        value in MODE_LABELS -> 0
        else -> null
    }

    internal fun labelOf(kind: ConfigKind, key: String, value: String): String = when (kind) {
        ConfigKind.MODE -> MODE_LABELS[value] ?: value.take(MAX_DESCRIPTION)
        ConfigKind.APPROVALS -> approvalLevel(key, value)?.let(APPROVAL_LABELS::get) ?: value.take(MAX_DESCRIPTION)
    }

    fun view(state: SessionState?, chatUri: String, chatMutable: Set<String>, own: Map<String, JsonElement>): ChatConfigView {
        val config = state?.config ?: return ChatConfigView.NONE
        val properties = config.schema.properties
        // Only a chat the session lists, and that a person started (or forked), may set its own: a subagent's settings are its parent's,
        // and an origin this app does not know is treated the same way.
        val summary = state.chats.firstOrNull { it.resource == chatUri }
        val origin = summary?.origin
        val mayWrite = summary != null && (origin == null || origin is ChatOrigin.User || origin is ChatOrigin.Fork || origin is ChatOrigin.SideChat)
        // The chat's own values (of the properties it may set) over the session's, as the host layers them: an inner value wins, and the
        // session's report of the approval level in force does not apply once the chat chose its own approvals.
        val values = LinkedHashMap<String, JsonElement>()
        var approvalsChosen = false
        for (layer in listOf(own.filterKeys { it in chatMutable }, config.values)) {
            for ((key, value) in layer) {
                if (key !in values && !(key == ChatConfigKeys.EFFECTIVE_APPROVAL_MODE && approvalsChosen)) values[key] = value
            }
            approvalsChosen = approvalsChosen || layer.keys.any { it == ChatConfigKeys.AUTO_APPROVE || it == ChatConfigKeys.APPROVAL_MODE }
        }

        fun writable(key: String) = mayWrite && key in chatMutable && properties[key]?.readOnly != true

        val modeKey = ChatConfigKeys.MODE
        val mode = properties[modeKey]?.let { property -> modeControl(property, values[modeKey], writable(modeKey)) }

        val approvalKey = when {
            properties.containsKey(ChatConfigKeys.AUTO_APPROVE) -> ChatConfigKeys.AUTO_APPROVE
            else -> ChatConfigKeys.APPROVAL_MODE
        }
        val approvals = properties[approvalKey]?.let { property ->
            approvalsControl(approvalKey, property, properties, values, writable(approvalKey))
        }
        return ChatConfigView(mode, approvals)
    }

    private fun modeControl(property: SessionConfigPropertySchema, held: JsonElement?, writable: Boolean): ConfigControl? {
        val offered = enumValues(property)
        if (property.type != "string" || property.enumDynamic == true || "interactive" !in offered) return null
        val choices = offered.mapIndexedNotNull { i, value ->
            if (value !in MODE_LABELS) null else ConfigChoice(value, MODE_LABELS.getValue(value), describe(property, i), available = true)
        }
        val current = (held.asWord() ?: property.default.asWord())?.takeIf { it in MODE_LABELS }
        return ConfigControl(ConfigKind.MODE, ChatConfigKeys.MODE, choices, current, writable)
    }

    private fun approvalsControl(
        key: String,
        property: SessionConfigPropertySchema,
        properties: Map<String, SessionConfigPropertySchema>,
        values: Map<String, JsonElement>,
        writable: Boolean,
    ): ConfigControl? {
        val offered = enumValues(property)
        val base = if (key == ChatConfigKeys.AUTO_APPROVE) "default" else "manual"
        if (property.type != "string" || property.enumDynamic == true || base !in offered) return null
        // A host that reports which levels are allowed now (a policy may withhold some) is believed: the others are shown, withheld.
        val allowedNow = values[ChatConfigKeys.AVAILABLE_APPROVAL_MODES]
            ?.takeIf { properties[ChatConfigKeys.AVAILABLE_APPROVAL_MODES]?.let { it.type == "array" && it.readOnly == true } == true }
            ?.let { (it as? JsonArray)?.mapNotNull { e -> e.asWord() }?.toSet() }
        val choices = offered.mapIndexedNotNull { i, value ->
            val level = approvalLevel(key, value) ?: return@mapIndexedNotNull null
            ConfigChoice(value, APPROVAL_LABELS[level], describe(property, i), available = allowedNow == null || value in allowedNow)
        }
        // The host's own report of what is in force wins over the property, when it reports one.
        val reported = properties[ChatConfigKeys.EFFECTIVE_APPROVAL_MODE]
            ?.takeIf { it.type == "string" && it.readOnly == true }
            ?.let { values[ChatConfigKeys.EFFECTIVE_APPROVAL_MODE].asWord() }
        val current = (reported ?: values[key].asWord() ?: property.default.asWord())?.takeIf { approvalLevel(key, it) != null }
        return ConfigControl(ConfigKind.APPROVALS, key, choices, current, writable)
    }

    private fun enumValues(property: SessionConfigPropertySchema): List<String> = property.enum.orEmpty().mapNotNull { it.asWord() }

    /** The host's line for the value at [index], plain and short; null when it has none. */
    private fun describe(property: SessionConfigPropertySchema, index: Int): String? =
        property.enumDescriptions?.getOrNull(index)?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_DESCRIPTION)

    private fun JsonElement?.asWord(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
