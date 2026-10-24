package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.AgentInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelOptionsTest {
    private fun model(id: String, name: String = id, policy: String? = null, schema: String? = null) =
        """{"id":"$id","provider":"copilotcli","name":"$name"${policy?.let { ""","policyState":"$it"""" }.orEmpty()}${schema?.let { ""","configSchema":$it""" }.orEmpty()}}"""

    private fun agent(vararg models: String) = Ahp.json.decodeFromString(
        AgentInfo.serializer(),
        """{"provider":"copilotcli","displayName":"Copilot","description":"d","models":[${models.joinToString(",")}]}""",
    )

    private fun schema(vararg properties: String) = """{"type":"object","properties":{${properties.joinToString(",")}}}"""

    private fun property(id: String, body: String) = """"$id":$body"""

    private val thinking = property(
        "thinking",
        """{"type":"string","title":"Thinking","enum":["low","high"],"enumLabels":["Quick","Thorough"],"default":"low"}""",
    )

    @Test
    fun `an agent's models are listed in the host's order with their names`() {
        val options = ModelOptions.of(agent(model("auto", "Auto"), model("claude-sonnet-5.5", "Claude Sonnet 5.5")))

        assertEquals(listOf("auto" to "Auto", "claude-sonnet-5.5" to "Claude Sonnet 5.5"), options.map { it.id to it.name })
    }

    @Test
    fun `no agent means no models`() {
        assertEquals(emptyList(), ModelOptions.of(null))
        assertEquals(emptyList(), ModelOptions.of(agent()))
    }

    @Test
    fun `a model the host has disabled or not set up is not offered, and one with no policy is`() {
        val options = ModelOptions.of(agent(model("a", policy = "enabled"), model("b", policy = "disabled"), model("c", policy = "unconfigured"), model("d")))

        assertEquals(listOf("a", "d"), options.map { it.id })
    }

    @Test
    fun `a blank id is skipped, a repeated id is listed once, and a blank name falls back to the id`() {
        val options = ModelOptions.of(agent(model(" "), model("a", "First"), model("a", "Second"), model("b", " ")))

        assertEquals(listOf("a" to "First", "b" to "b"), options.map { it.id to it.name })
    }

    @Test
    fun `the host's words are bounded`() {
        val options = ModelOptions.of(agent(model("a", "n".repeat(200))))

        assertEquals(80, options.single().name.length)
    }

    @Test
    fun `no more models than the cap are listed`() {
        val options = ModelOptions.of(agent(*(1..ModelOptions.MAX_MODELS + 20).map { model("m$it") }.toTypedArray()))

        assertEquals(ModelOptions.MAX_MODELS, options.size)
    }

    @Test
    fun `a setting that is a list of text values is offered with the host's labels`() {
        val option = ModelOptions.of(agent(model("a", schema = schema(thinking)))).single()

        val setting = option.settings.single()
        assertEquals("thinking", setting.id)
        assertEquals("Thinking", setting.title)
        assertEquals(listOf("low" to "Quick", "high" to "Thorough"), setting.values.map { it.value to it.label })
    }

    @Test
    fun `a value without a label is shown as itself`() {
        val bare = property("effort", """{"type":"string","title":"Effort","enum":["a","b"]}""")

        val values = ModelOptions.of(agent(model("m", schema = schema(bare)))).single().settings.single().values

        assertEquals(listOf("a" to "a", "b" to "b"), values.map { it.value to it.label })
    }

    @Test
    fun `settings that are not a list of text values are left out, not shown badly`() {
        val number = property("temperature", """{"type":"number","title":"Temperature"}""")
        val readOnly = property("fixed", """{"type":"string","title":"Fixed","enum":["x"],"readOnly":true}""")
        val free = property("note", """{"type":"string","title":"Note"}""")
        val mixed = property("mixed", """{"type":"string","title":"Mixed","enum":[1,2]}""")
        val bool = property("flag", """{"type":"boolean","title":"Flag"}""")

        val option = ModelOptions.of(agent(model("m", schema = schema(number, readOnly, free, mixed, bool, thinking)))).single()

        assertEquals(listOf("thinking"), option.settings.map { it.id })
    }

    @Test
    fun `a model with no settings has none`() {
        assertEquals(emptyList(), ModelOptions.of(agent(model("a"))).single().settings)
    }

    @Test
    fun `settings and values are capped`() {
        val many = (1..ModelOptions.MAX_SETTINGS + 5).map { property("s$it", """{"type":"string","title":"S$it","enum":["a"]}""") }
        val values = (1..ModelOptions.MAX_VALUES + 5).joinToString(",") { "\"v$it\"" }
        val long = property("long", """{"type":"string","title":"Long","enum":[$values]}""")

        val settings = ModelOptions.of(agent(model("a", schema = schema(*many.toTypedArray())), model("b", schema = schema(long)))).map { it.settings }

        assertEquals(ModelOptions.MAX_SETTINGS, settings[0].size)
        assertEquals(ModelOptions.MAX_VALUES, settings[1].single().values.size)
    }

    @Test
    fun `models come from the agent whose provider the session uses, and from no other`() {
        fun named(provider: String, vararg models: String) = Ahp.json.decodeFromString(
            AgentInfo.serializer(),
            """{"provider":"$provider","displayName":"$provider","description":"d","models":[${models.joinToString(",") { model(it) }}]}""",
        )
        val agents = listOf(named("alpha", "a1"), named("beta", "b1", "b2"))

        assertEquals(listOf("b1", "b2"), ModelOptions.forProvider(agents, "beta").map { it.id })
        assertEquals(listOf("a1"), ModelOptions.forProvider(agents, "alpha").map { it.id })
        assertEquals(emptyList(), ModelOptions.forProvider(agents, "gamma"))
        assertEquals(emptyList(), ModelOptions.forProvider(agents, null))
        assertEquals(emptyList(), ModelOptions.forProvider(emptyList(), "alpha"))
    }

    // ---- what may be sent --------------------------------------------------------------------------------------

    private val options = ModelOptions.of(agent(model("auto"), model("claude", schema = schema(thinking))))

    @Test
    fun `a listed model, with or without its own settings, is offered`() {
        assertTrue(ModelOptions.isOffered(options, ModelChoice("auto")))
        assertTrue(ModelOptions.isOffered(options, ModelChoice("claude")))
        assertTrue(ModelOptions.isOffered(options, ModelChoice("claude", mapOf("thinking" to "high"))))
    }

    @Test
    fun `an unlisted model, an unknown setting, or a value the model does not list is not offered`() {
        assertFalse(ModelOptions.isOffered(options, ModelChoice("gpt-made-up")))
        assertFalse(ModelOptions.isOffered(options, ModelChoice("claude", mapOf("thinking" to "extreme"))))
        assertFalse(ModelOptions.isOffered(options, ModelChoice("claude", mapOf("other" to "high"))))
        assertFalse(ModelOptions.isOffered(options, ModelChoice("auto", mapOf("thinking" to "high"))), "a setting belongs to its own model")
        assertFalse(ModelOptions.isOffered(options, ModelChoice("claude", mapOf("thinking" to "high", "extra" to "x"))))
        assertFalse(ModelOptions.isOffered(emptyList(), ModelChoice("auto")))
        assertFalse(ModelOptions.isOffered(options, ModelChoice("")))
    }
}
