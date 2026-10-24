package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.AgentInfo
import com.microsoft.agenthostprotocol.generated.PolicyState
import kotlinx.serialization.json.JsonPrimitive

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
}
