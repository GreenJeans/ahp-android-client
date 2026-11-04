package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.AgentInfo
import com.microsoft.agenthostprotocol.generated.ChatState
import com.microsoft.agenthostprotocol.generated.ModelSelection
import com.microsoft.agenthostprotocol.generated.PolicyState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.abs
import kotlin.math.round

/**
 * What a person picks for one message: a model the host offers, and values for its settings. Every value is held as a string;
 * [numeric] names the settings the host declares as numbers, which go on the wire as numbers. It is filled in when a message is
 * built (see [ModelOptions.prepare]), so a pick never has to carry it.
 */
data class ModelChoice(val id: String, val config: Map<String, String> = emptyMap(), val numeric: Set<String> = emptySet())

data class ModelSettingValue(val value: String, val label: String)

/**
 * One setting of a model (a thinking level, a context size), as a short list of values to pick from. [numeric] is true when the
 * host declares the setting as a number. [default] is the host's own default, when it is one of [values].
 */
data class ModelSetting(
    val id: String,
    val title: String,
    val values: List<ModelSettingValue>,
    val numeric: Boolean = false,
    val default: String? = null,
)

data class ModelOption(val id: String, val name: String, val settings: List<ModelSetting>, val supportsVision: Boolean? = null)

/**
 * The models a host offers for an agent, reduced to what a phone can show and send back: a model the host has not disabled,
 * and the settings of it that are a plain list of text values to choose from. Anything else a host describes (a number box,
 * a read-only field, a nested form) is left out rather than shown badly. Everything the host says is untrusted display
 * text, so it is bounded; and a choice is sent only if it is exactly one of these options.
 */
object ModelOptions {
    const val MAX_MODELS = 100
    const val MAX_SETTINGS = 10
    const val MAX_VALUES = 30
    private const val MAX_TEXT = 80
    private const val MAX_WHOLE = 1e15

    fun of(agent: AgentInfo?): List<ModelOption> =
        agent?.models.orEmpty()
            .filter { it.id.isNotBlank() && (it.policyState == null || it.policyState == PolicyState.ENABLED) }
            .distinctBy { it.id }
            .take(MAX_MODELS)
            .map { model ->
                ModelOption(
                    id = model.id,
                    name = model.name.ifBlank { model.id }.take(MAX_TEXT),
                    settings = model.configSchema?.properties.orEmpty().mapNotNull { (id, property) ->
                        val numeric = property.type == "number" || property.type == "integer"
                        if ((property.type != "string" && !numeric) || property.readOnly == true) return@mapNotNull null
                        val values = property.enum.orEmpty().mapIndexedNotNull { i, raw ->
                            val value = plainValue(raw, numeric) ?: return@mapIndexedNotNull null
                            ModelSettingValue(value, property.enumLabels?.getOrNull(i)?.takeIf { it.isNotBlank() }?.take(MAX_TEXT) ?: (if (numeric) grouped(value) else value).take(MAX_TEXT))
                        }.distinctBy { it.value }.take(MAX_VALUES)
                        val default = plainValue(property.default, numeric)?.takeIf { d -> values.any { it.value == d } }
                        if (values.isEmpty()) null else ModelSetting(id, property.title.ifBlank { id }.take(MAX_TEXT), values, numeric, default)
                    }.take(MAX_SETTINGS),
                    supportsVision = model.supportsVision,
                )
            }

    /** "936000" as "936,000", so a size reads at a glance. */
    private fun grouped(whole: String): String {
        val digits = whole.removePrefix("-")
        val spaced = digits.reversed().chunked(3).joinToString(",").reversed()
        return if (whole.startsWith("-")) "-$spaced" else spaced
    }

    /** A setting's value as text: a text value for a text setting, a whole number for a number setting. Anything else is left out. */
    private fun plainValue(raw: JsonElement?, numeric: Boolean): String? {
        val primitive = raw as? JsonPrimitive ?: return null
        if (!numeric) return primitive.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        if (primitive.isString) return null
        return primitive.content.toDoubleOrNull()?.takeIf { it.isFinite() && it == round(it) && abs(it) < MAX_WHOLE }?.toLong()?.toString()
    }

    /**
     * What goes on the wire for [choice]: each setting the host declares a default for, and the person has not set, gets that
     * default (the host's own value, never one made up here), and the settings the host declares as numbers are marked so they
     * are sent as numbers. VS Code sends a model's settings this way; a message that leaves them out can run the model at a smaller
     * context size than the host's default, and fail on a chat whose fixed context does not fit.
     */
    fun prepare(options: List<ModelOption>, choice: ModelChoice): ModelChoice {
        val settings = options.firstOrNull { it.id == choice.id }?.settings ?: return choice
        val config = choice.config + settings.filter { it.default != null && it.id !in choice.config }.associate { it.id to it.default!! }
        return choice.copy(config = config, numeric = settings.filter { it.numeric && it.id in config }.mapTo(HashSet()) { it.id })
    }

    /** The models of the agent whose provider is [provider], or none when there is no such agent (or no provider). */
    fun forProvider(agents: List<AgentInfo>, provider: String?): List<ModelOption> =
        if (provider == null) emptyList() else of(agents.firstOrNull { it.provider == provider })

    /** True when [choice] is one of [options] exactly: a listed model, with only settings and values that model lists. */
    fun isOffered(options: List<ModelOption>, choice: ModelChoice): Boolean {
        val model = options.firstOrNull { it.id == choice.id } ?: return false
        return choice.config.all { (key, value) -> model.settings.firstOrNull { it.id == key }?.values?.any { it.value == value } == true }
    }

    /**
     * The model a chat is using now, so the message box can show it and send it: the one its running or latest turn was sent
     * with, else the one that turn reports having used, else the one in the draft, else the session's model. A chat that has had
     * no turn, on a session the host names no model for, starts on the host's first listed model with that model's default
     * settings (an empty [ModelChoice.config]; [prepare] adds the host's defaults when the message is built), so its first message
     * always names a model: a message with none leaves the host to pick, and the VS Code host's own pick can fail on a large
     * context (`compaction_static_context_blocked`). Null when the host offers no models, when [chat] is not known yet, and when a
     * turn or the session names a model the host does not offer: that chat is on a model the host knows, and sending none keeps
     * it. A setting the host no longer lists for the model is dropped; the model is kept.
     *
     * A message sent with no model leaves the chat on the model it has (the host changes it only when asked), but the screen shows
     * and sends this one anyway, so what is on it is always what is used.
     */
    fun inUse(chat: ChatState?, options: List<ModelOption>, sessionModel: String? = null): ModelChoice? {
        chat ?: return resolveByName(sessionModel, options)
        for (turn in listOfNotNull(chat.activeTurn?.let { it.message to it.usage }, chat.turns.lastOrNull()?.let { it.message to it.usage })) {
            choiceOf(turn.first.model, options)?.let { return it }
            val reported = turn.second?.model?.takeIf { it.isNotBlank() }
            resolveByName(reported, options)?.let { return it }
        }
        return choiceOf(chat.draft?.model, options) ?: resolveByName(sessionModel, options) ?: firstForNewChat(chat, options, sessionModel)
    }

    /** The host's first listed model, for a chat that has had no turn (none running, none kept, none older to load) on a session that names no model. */
    private fun firstForNewChat(chat: ChatState, options: List<ModelOption>, sessionModel: String?): ModelChoice? {
        if (chat.activeTurn != null || chat.turns.isNotEmpty() || chat.turnsNextCursor != null || !sessionModel.isNullOrBlank()) return null
        return options.firstOrNull()?.let { ModelChoice(it.id) }
    }

    private fun resolveByName(raw: String?, options: List<ModelOption>): ModelChoice? {
        val name = raw?.takeIf { it.isNotBlank() } ?: return null
        return options.firstOrNull { it.id == name || it.name.equals(name, ignoreCase = true) }?.let { ModelChoice(it.id) }
    }

    private fun choiceOf(selection: ModelSelection?, options: List<ModelOption>): ModelChoice? {
        selection ?: return null
        val model = options.firstOrNull { it.id == selection.id } ?: return null
        val config = selection.config.orEmpty().mapNotNull { (key, raw) ->
            val value = (raw as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if (model.settings.firstOrNull { it.id == key }?.values?.any { it.value == value } == true) key to value else null
        }.toMap()
        return ModelChoice(model.id, config)
    }
}