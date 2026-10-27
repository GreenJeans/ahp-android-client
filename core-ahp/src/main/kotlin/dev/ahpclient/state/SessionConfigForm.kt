package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.ResolveSessionConfigResult
import com.microsoft.agenthostprotocol.generated.SessionConfigPropertySchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** One value a person can choose for a setting: a piece of text from the host's own list, or on/off. */
sealed interface ConfigPick {
    fun toJson(): JsonPrimitive

    data class Text(val value: String) : ConfigPick {
        override fun toJson() = JsonPrimitive(value)
    }

    data class Flag(val value: Boolean) : ConfigPick {
        override fun toJson() = JsonPrimitive(value)
    }
}

data class ConfigOption(val pick: ConfigPick, val label: String)

/** One setting of a new session, as a short list of values to pick from. [value] is what the host holds for it now, if it is one of the options. */
data class ConfigField(
    val id: String,
    val title: String,
    val description: String?,
    val options: List<ConfigOption>,
    val required: Boolean,
    val value: ConfigPick?,
)

/**
 * The settings a host offers for a new session, reduced to what a phone can show and send back: a setting that is a list of
 * text values, or on/off. Anything else (free text, a number, a read-only value, a list too long to list that the host wants
 * completed as you type) is left out and counted in [omitted], and the host keeps its own value for it. Everything the host says
 * is untrusted display text, so it is bounded; and a choice is accepted only when it is exactly one of the offered options and
 * the host's own answer shows it took.
 */
data class SessionConfigForm(val fields: List<ConfigField>, val omitted: Int, private val resolved: Map<String, JsonElement>) {
    /** The required settings that have no value yet, where the person has not chosen one either. */
    fun missingRequired(picks: Map<String, ConfigPick>): List<ConfigField> = fields.filter { it.required && it.value == null && it.id !in picks }

    /**
     * True when every pick is for a setting listed here, is one of its options, and is what the host answered it holds, so a
     * choice the host did not take, or that is out of date, is never sent.
     */
    fun accepts(picks: Map<String, ConfigPick>): Boolean =
        picks.all { (id, pick) -> fields.firstOrNull { it.id == id }?.let { field -> field.options.any { it.pick == pick } && field.value == pick } == true }

    /** The picks as "Setting: Value" lines, in the order of the settings, for the device check to show. */
    fun describe(picks: Map<String, ConfigPick>): List<String> =
        fields.mapNotNull { field ->
            val pick = picks[field.id] ?: return@mapNotNull null
            "${field.title}: ${field.options.firstOrNull { it.pick == pick }?.label.orEmpty()}"
        }

    /** What is sent to the host: the values it resolved itself for this agent and folder, with the person's choices already in them. */
    fun wireConfig(): Map<String, JsonElement> = resolved

    companion object {
        const val MAX_FIELDS = 20
        const val MAX_OPTIONS = 30
        const val MAX_VALUES = 50
        private const val MAX_ID = 100
        private const val MAX_TEXT = 80
        private const val MAX_DESCRIPTION = 200
        private const val MAX_VALUE_CHARS = 4_000

        fun from(result: ResolveSessionConfigResult): SessionConfigForm {
            val required = result.schema.required.orEmpty().toSet()
            val fields = ArrayList<ConfigField>()
            var omitted = 0
            for ((id, property) in result.schema.properties) {
                val field = field(id, property, id in required, result.values[id])
                if (field != null && fields.size < MAX_FIELDS) fields += field else omitted++
            }
            val resolved = result.values.entries
                .filter { it.key in result.schema.properties && it.value.toString().length <= MAX_VALUE_CHARS }
                .take(MAX_VALUES)
                .associate { it.key to it.value }
            return SessionConfigForm(fields, omitted, resolved)
        }

        private fun field(id: String, property: SessionConfigPropertySchema, required: Boolean, held: JsonElement?): ConfigField? {
            if (id.isBlank() || id.length > MAX_ID) return null
            if (property.readOnly == true || property.enumDynamic == true) return null
            val options = when (property.type) {
                "string" -> choices(property)
                "boolean" -> listOf(ConfigOption(ConfigPick.Flag(true), "On"), ConfigOption(ConfigPick.Flag(false), "Off"))
                else -> return null
            }
            if (options.isEmpty()) return null
            val value = (held as? JsonPrimitive)?.let { primitive ->
                if (property.type == "boolean") primitive.booleanOrNull?.let { ConfigPick.Flag(it) } else primitive.takeIf { it.isString }?.contentOrNull?.let { ConfigPick.Text(it) }
            }?.takeIf { v -> options.any { it.pick == v } }
            return ConfigField(
                id = id,
                title = property.title.ifBlank { id }.take(MAX_TEXT),
                description = property.description?.takeIf { it.isNotBlank() }?.take(MAX_DESCRIPTION),
                options = options,
                required = required,
                value = value,
            )
        }

        private fun choices(property: SessionConfigPropertySchema): List<ConfigOption> =
            property.enum.orEmpty().mapIndexedNotNull { i, raw ->
                val text = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                ConfigOption(ConfigPick.Text(text), property.enumLabels?.getOrNull(i)?.takeIf { it.isNotBlank() }?.take(MAX_TEXT) ?: text.take(MAX_TEXT))
            }.distinctBy { it.pick }.take(MAX_OPTIONS)
    }
}

/** What asking the host for a new session's settings came to. */
sealed interface ConfigOffer {
    data class Form(val form: SessionConfigForm) : ConfigOffer

    /** The host offers nothing to choose for this agent here, so the session starts with the host's own settings. */
    data object NoSettings : ConfigOffer

    /** The host could not be asked, or refused. [reason] is plain words, bounded. */
    data class Unavailable(val reason: String) : ConfigOffer
}

/**
 * The line the device check shows when starting a session with settings the person chose, so what they confirm with their
 * fingerprint is what will be sent. The check shows at most 200 characters, so the settings come first and the folder last, and
 * settings that do not fit are counted ("+2 more settings") rather than silently cut off.
 */
object SettingsSummary {
    const val MAX_CHARS = 190
    private const val MAX_LINE = 38

    /** Said at the device check when no folder was named, so the person knows the host will pick where the agent works. */
    const val NO_FOLDER = "No folder: the host chooses"

    /** [folder] null names none; the line then says the host chooses. */
    fun of(folder: String?, chosen: List<String>): String {
        val place = folder ?: NO_FOLDER
        if (chosen.isEmpty()) return place
        val lines = chosen.map { it.take(MAX_LINE) }
        fun settings(shown: Int): String =
            (lines.take(shown) + if (shown < lines.size) listOf("+${lines.size - shown} more settings") else emptyList()).joinToString("\n")
        var shown = lines.size
        while (shown > 0 && settings(shown).length > MAX_CHARS) shown--
        return settings(shown) + "\n" + place
    }
}
