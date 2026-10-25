package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.AgentInfo
import com.microsoft.agenthostprotocol.generated.ChatState
import com.microsoft.agenthostprotocol.generated.ModelSelection
import com.microsoft.agenthostprotocol.generated.PolicyState
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** What a person picks for one message: a model the host offers, and values for its settings. Every value is a string. */
data class ModelChoice(val id: String, val config: Map<String, String> = emptyMap())

data class ModelSettingValue(val value: String, val label: String)

/** One setting of a model (a thinking level, say), as a short list of values to pick from. */
data class ModelSetting(val id: String, val title: String, val values: List<ModelSettingValue>)

data class ModelOption(val id: String, val name: String, val settings: List<ModelSetting>)

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
                        if (property.type != "string" || property.readOnly == true) return@mapNotNull null
                        val values = property.enum.orEmpty().mapIndexedNotNull { i, raw ->
                            val value = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                            ModelSettingValue(value, property.enumLabels?.getOrNull(i)?.takeIf { it.isNotBlank() }?.take(MAX_TEXT) ?: value.take(MAX_TEXT))
                        }.distinctBy { it.value }.take(MAX_VALUES)
                        if (values.isEmpty()) null else ModelSetting(id, property.title.ifBlank { id }.take(MAX_TEXT), values)
                    }.take(MAX_SETTINGS),
                )
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
     * with, else the one that turn reports having used, else the one in the draft. Null when none of these is a model the host
     * offers, which is a chat that has not had a turn: it will use the host's default. A setting the host no longer lists for the
     * model is dropped; the model is kept.
     *
     * A message sent with no model leaves the chat on the model it has (the host changes it only when asked), but the screen shows
     * and sends this one anyway, so what is on it is always what is used.
     */
    fun inUse(chat: ChatState?, options: List<ModelOption>): ModelChoice? {
        chat ?: return null
        for (turn in listOfNotNull(chat.activeTurn?.let { it.message to it.usage }, chat.turns.lastOrNull()?.let { it.message to it.usage })) {
            choiceOf(turn.first.model, options)?.let { return it }
            val reported = turn.second?.model?.takeIf { it.isNotBlank() }
            reported?.let { name -> options.firstOrNull { it.id == name || it.name.equals(name, ignoreCase = true) }?.let { return ModelChoice(it.id) } }
        }
        return choiceOf(chat.draft?.model, options)
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