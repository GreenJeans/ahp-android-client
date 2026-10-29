package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.AgentInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    // ---- the model a chat is using -------------------------------------------------------------------------------

    private val sonnetJson = model("sonnet", "Claude Sonnet", schema = schema(thinking))
    private val autoJson = model("auto", "Auto")
    private val offered get() = ModelOptions.of(agent(autoJson, sonnetJson))

    private fun chatWith(turns: String = "", active: String? = null, draft: String? = null) = Ahp.json.decodeFromString(
        com.microsoft.agenthostprotocol.generated.ChatState.serializer(),
        """{"resource":"ahp-chat:/c","title":"t","status":1,"modifiedAt":"2026-10-22T10:00:00.000Z","turns":[$turns]
            ${active?.let { ""","activeTurn":$it""" }.orEmpty()}${draft?.let { ""","draft":$it""" }.orEmpty()}}""",
    )

    private fun turn(id: String, model: String? = null, usage: String? = null) =
        """{"id":"$id","message":{"text":"x","origin":{"kind":"user"}${model?.let { ""","model":$it""" }.orEmpty()}},"responseParts":[],"state":"complete"${usage?.let { ""","usage":{"model":"$it"}""" }.orEmpty()}}"""

    @Test
    fun `a chat is using the model its latest turn was sent with, settings and all`() {
        val chat = chatWith(turn("t1", """{"id":"auto"}""") + "," + turn("t2", """{"id":"sonnet","config":{"thinking":"high"}}"""))

        assertEquals(ModelChoice("sonnet", mapOf("thinking" to "high")), ModelOptions.inUse(chat, offered))
    }

    @Test
    fun `the running turn counts before the finished ones`() {
        val chat = chatWith(turn("t1", """{"id":"auto"}"""), active = """{"id":"t2","startedAt":"2026-10-22T10:05:00.000Z","message":{"text":"x","origin":{"kind":"user"},"model":{"id":"sonnet"}},"responseParts":[]}""")

        assertEquals(ModelChoice("sonnet"), ModelOptions.inUse(chat, offered))
    }

    @Test
    fun `with no model on the message, the model the turn reports using is found by its id or its name`() {
        assertEquals(ModelChoice("sonnet"), ModelOptions.inUse(chatWith(turn("t1", usage = "sonnet")), offered))
        assertEquals(ModelChoice("sonnet"), ModelOptions.inUse(chatWith(turn("t1", usage = "claude sonnet")), offered))
        assertNull(ModelOptions.inUse(chatWith(turn("t1", usage = "something-else")), offered))
    }

    @Test
    fun `a model the host no longer offers is not used, and one setting it no longer lists is dropped`() {
        assertNull(ModelOptions.inUse(chatWith(turn("t1", """{"id":"retired"}""")), offered))
        assertEquals(ModelChoice("sonnet", mapOf("thinking" to "high")), ModelOptions.inUse(chatWith(turn("t1", """{"id":"sonnet","config":{"thinking":"high","old":"x"}}""")), offered))
        assertEquals(ModelChoice("sonnet"), ModelOptions.inUse(chatWith(turn("t1", """{"id":"sonnet","config":{"thinking":"ludicrous"}}""")), offered))
    }

    @Test
    fun `a chat with no turns uses the model in its draft, and with no draft none is known`() {
        assertEquals(ModelChoice("auto"), ModelOptions.inUse(chatWith(draft = """{"text":"","origin":{"kind":"user"},"model":{"id":"auto"}}"""), offered))
        assertNull(ModelOptions.inUse(chatWith(), offered))
        assertNull(ModelOptions.inUse(null, offered))
    }

    @Test
    fun `the latest turn wins over the draft`() {
        val chat = chatWith(turn("t1", """{"id":"sonnet"}"""), draft = """{"text":"","origin":{"kind":"user"},"model":{"id":"auto"}}""")

        assertEquals(ModelChoice("sonnet"), ModelOptions.inUse(chat, offered))
    }

    @Test
    fun `whatever is found is always something the host offers`() {
        val chat = chatWith(turn("t1", """{"id":"sonnet","config":{"thinking":"low"}}"""))

        assertTrue(ModelOptions.isOffered(offered, ModelOptions.inUse(chat, offered)!!))
    }

    @Test
    fun `a chat with no turn or draft model falls back to the session's model when offered`() {
        assertEquals(ModelChoice("sonnet"), ModelOptions.inUse(chatWith(), offered, sessionModel = "sonnet"))
        assertEquals(ModelChoice("sonnet"), ModelOptions.inUse(null, offered, sessionModel = "Claude Sonnet"))
        assertNull(ModelOptions.inUse(chatWith(), offered, sessionModel = "unlisted"))
        assertEquals(
            ModelChoice("auto"),
            ModelOptions.inUse(chatWith(draft = """{"text":"","origin":{"kind":"user"},"model":{"id":"auto"}}"""), offered, sessionModel = "sonnet"),
            "the chat's draft wins over the session's model",
        )
        assertEquals(
            ModelChoice("auto"),
            ModelOptions.inUse(chatWith(turn("t1", """{"id":"auto"}""")), offered, sessionModel = "sonnet"),
            "the chat's turn wins over the session's model",
        )
    }

    @Test
    fun `a chat interactor reports the model in use from the chat or the session fallback`() {
        var currentChat: com.microsoft.agenthostprotocol.generated.ChatState? = chatWith()
        var currentSessionModel: String? = "sonnet"
        val interactor = ChatInteractor(
            chatUri = "ahp-chat:/c",
            chat = { currentChat },
            canInteract = { true },
            dispatch = { _, _ -> DispatchOutcome.Accepted },
            gate = ApprovalGate { false },
            models = { offered },
            sessionModel = { currentSessionModel },
        )

        assertEquals(ModelChoice("sonnet"), interactor.modelInUse())

        currentChat = chatWith(turn("t1", """{"id":"auto"}"""))
        assertEquals(ModelChoice("auto"), interactor.modelInUse())

        currentChat = chatWith()
        currentSessionModel = null
        assertNull(interactor.modelInUse())
    }
}