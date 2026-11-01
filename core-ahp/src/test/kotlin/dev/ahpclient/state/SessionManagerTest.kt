package dev.ahpclient.state

import dev.ahpclient.ahp.AhpException
import dev.ahpclient.ahp.CapabilityDeniedException
import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.AgentInfo
import com.microsoft.agenthostprotocol.generated.ChatSource
import com.microsoft.agenthostprotocol.generated.ChatSourceFork
import com.microsoft.agenthostprotocol.generated.ChatSourceSideChat
import com.microsoft.agenthostprotocol.generated.Message
import com.microsoft.agenthostprotocol.generated.ResolveSessionConfigResult
import com.microsoft.agenthostprotocol.generated.SessionState
import com.microsoft.agenthostprotocol.generated.SessionSummary
import com.microsoft.agenthostprotocol.generated.StateAction
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SessionManagerTest {
    private val s1 = "ahp-session:/s1"
    private val s2 = "ahp-session:/s2"
    private val c1 = "ahp-chat:/c1"
    private val c2 = "ahp-chat:/c2"

    private fun summary(uri: String, title: String, dirs: List<String>) = Ahp.json.decodeFromString(
        SessionSummary.serializer(),
        """{"resource":"$uri","provider":"copilotcli","title":"$title","status":1,"createdAt":"2026-10-20T09:00:00.000Z","modifiedAt":"2026-10-20T10:30:00.000Z",
            "workingDirectories":[${dirs.joinToString(",") { "\"$it\"" }}]}""",
    )

    private fun agent(provider: String, name: String, fork: Boolean = false, sideChat: Boolean = false) =
        Ahp.json.decodeFromString(
            AgentInfo.serializer(),
            """{"provider":"$provider","displayName":"$name","description":"d","models":[],"capabilities":{"multipleChats":{"fork":$fork,"sideChat":$sideChat}}}""",
        )

    private fun state(lifecycle: String, vararg chats: Pair<String, String>, defaultChat: String? = null) = Ahp.json.decodeFromString(
        SessionState.serializer(),
        """{"provider":"copilotcli","title":"T","status":1,"lifecycle":"$lifecycle","activeClients":[]${defaultChat?.let { ""","defaultChat":"$it"""" }.orEmpty()},
            "chats":[${chats.joinToString(",") { """{"resource":"${it.first}","title":"${it.second}","status":1,"modifiedAt":"2026-10-26T10:29:00.000Z"}""" }}]}""",
    )

    private class Commands : ManagementCommands {
        val calls = mutableListOf<String>()
        val configs = mutableListOf<Map<String, JsonElement>?>()
        val resolves = mutableListOf<Triple<String, String?, Map<String, JsonElement>?>>()
        var failure: Throwable? = null
        var resolveFailure: Throwable? = null
        var resolution: (provider: String, directory: String?, config: Map<String, JsonElement>?) -> ResolveSessionConfigResult = { _, _, _ -> empty }

        private fun record(call: String) {
            calls += call
            failure?.let { throw it }
        }

        override suspend fun createSession(uri: String, provider: String?, workingDirectories: List<String>?, config: Map<String, JsonElement>?) {
            configs += config
            record("createSession $uri $provider $workingDirectories")
        }

        override suspend fun resolveSessionConfig(provider: String, workingDirectory: String?, config: Map<String, JsonElement>?): ResolveSessionConfigResult {
            resolves += Triple(provider, workingDirectory, config)
            resolveFailure?.let { throw it }
            return resolution(provider, workingDirectory, config)
        }

        val chatSources = mutableListOf<ChatSource?>()
        val chatMessages = mutableListOf<Message?>()

        override suspend fun createChat(sessionUri: String, chatUri: String, source: ChatSource?, initialMessage: Message?) {
            chatSources += source
            chatMessages += initialMessage
            record("createChat $sessionUri $chatUri")
        }

        override suspend fun disposeSession(uri: String) = record("disposeSession $uri")

        override suspend fun disposeChat(uri: String) = record("disposeChat $uri")

        companion object {
            val empty: ResolveSessionConfigResult = Ahp.json.decodeFromString(ResolveSessionConfigResult.serializer(), """{"schema":{"type":"object","properties":{}},"values":{}}""")
        }
    }

    private inner class Rig {
        var allowed = true
        var sessions = listOf(summary(s1, "Fix the build", listOf("file:///work/a", "file:///work/b")), summary(s2, "Other", listOf("file:///work/a")))
        var agents = listOf(agent("copilotcli", "Copilot CLI"), agent("claude", "Claude"))
        var states = mutableMapOf<String, SessionState>(s1 to state("ready", c1 to "Main", c2 to "Side"))
        val sent = mutableListOf<Pair<String, StateAction>>()
        var outcome: DispatchOutcome = DispatchOutcome.Accepted
        var dispatchFailure: Throwable? = null
        val commands = Commands()
        val asked = mutableListOf<ApprovalRequest>()
        var answer = true
        var gateFailure: Throwable? = null
        var whileAsking: () -> Unit = {}
        private var counter = 0

        val manager = SessionManager(
            allowed = { allowed },
            sessions = { sessions },
            agents = { agents },
            sessionState = { states[it] },
            dispatch = { channel, action ->
                dispatchFailure?.let { throw it }
                sent += channel to action
                outcome
            },
            commands = commands,
            gate = ApprovalGate { request ->
                asked += request
                gateFailure?.let { throw it }
                whileAsking()
                answer
            },
            newId = { "id${++counter}" },
        )
    }

    private fun wire(action: StateAction): JsonObject = Ahp.json.encodeToJsonElement(StateAction.serializer(), action).jsonObject

    // ---- actions ----------------------------------------------------------------------------------------------------

    @Test
    fun `marking read and archiving send exactly their own action on the session channel`() = runTest {
        val rig = Rig()

        assertEquals(ManageResult.Done(), rig.manager.setRead(s1, true))
        assertEquals(ManageResult.Done(), rig.manager.setRead(s1, false))
        assertEquals(ManageResult.Done(), rig.manager.setArchived(s2, true))

        assertEquals(listOf(s1, s1, s2), rig.sent.map { it.first })
        val shapes = rig.sent.map { wire(it.second) }
        assertEquals(listOf("session/isReadChanged", "session/isReadChanged", "session/isArchivedChanged"), shapes.map { it["type"]!!.jsonPrimitive.content })
        assertEquals(listOf(true, false), shapes.take(2).map { it["isRead"]!!.jsonPrimitive.boolean })
        assertEquals(true, shapes[2]["isArchived"]!!.jsonPrimitive.boolean)
        assertTrue(rig.asked.isEmpty(), "reversible, so no device check")
        assertTrue(rig.commands.calls.isEmpty())
    }

    @Test
    fun `an action is refused before anything is sent when the connection cannot manage`() = runTest {
        val rig = Rig().apply { allowed = false }

        assertEquals(ManageResult.NotAllowed, rig.manager.setRead(s1, true))
        assertEquals(ManageResult.NotAllowed, rig.manager.setArchived(s1, true))

        assertTrue(rig.sent.isEmpty())
    }

    @Test
    fun `an action on a session the host does not list finds nothing`() = runTest {
        val rig = Rig()

        assertEquals(ManageResult.NoSuchTarget, rig.manager.setRead("ahp-session:/ghost", true))
        assertEquals(ManageResult.NoSuchTarget, rig.manager.setArchived("ahp-chat:/c1", true))

        assertTrue(rig.sent.isEmpty())
    }

    @Test
    fun `an action the host did not confirm is not reported as done`() = runTest {
        val rig = Rig()
        for (outcome in listOf(DispatchOutcome.Rejected("no"), DispatchOutcome.NoReply, DispatchOutcome.Interrupted)) {
            rig.outcome = outcome

            assertEquals(ManageResult.Unconfirmed(outcome), rig.manager.setArchived(s1, true))
        }
    }

    @Test
    fun `an action that never left the phone says so, and a denied one is not allowed`() = runTest {
        val rig = Rig()

        rig.dispatchFailure = IOException("offline")
        assertIs<ManageResult.NotSent>(rig.manager.setRead(s1, true))
        rig.dispatchFailure = CapabilityDeniedException("dispatchAction", "session/isReadChanged")
        assertEquals(ManageResult.NotAllowed, rig.manager.setRead(s1, true))
    }

    // ---- new chat ---------------------------------------------------------------------------------------------------

    @Test
    fun `a new chat is made in a ready session with a chat address of the app's own and no device check`() = runTest {
        val rig = Rig()

        val result = rig.manager.newChat(s1)

        assertEquals(ManageResult.Done("ahp-chat:/id1"), result)
        assertEquals(listOf("createChat $s1 ahp-chat:/id1"), rig.commands.calls)
        assertTrue(rig.asked.isEmpty())
    }

    @Test
    fun `a chat is not made in a session that is still starting, has failed, is unknown or unlisted`() = runTest {
        val rig = Rig()
        rig.states[s2] = state("creating")

        assertEquals(ManageResult.NoSuchTarget, rig.manager.newChat(s2), "still starting")
        rig.states[s2] = state("failed")
        assertEquals(ManageResult.NoSuchTarget, rig.manager.newChat(s2), "failed")
        rig.states.remove(s2)
        assertEquals(ManageResult.NoSuchTarget, rig.manager.newChat(s2), "not loaded")
        rig.states["ahp-session:/ghost"] = state("ready")
        assertEquals(ManageResult.NoSuchTarget, rig.manager.newChat("ahp-session:/ghost"), "the host does not list it")

        assertTrue(rig.commands.calls.isEmpty())
    }

    @Test
    fun `a chat is not made when the connection cannot manage`() = runTest {
        val rig = Rig().apply { allowed = false }

        assertEquals(ManageResult.NotAllowed, rig.manager.newChat(s1))

        assertTrue(rig.commands.calls.isEmpty())
    }

    // ---- new session ------------------------------------------------------------------------------------------------

    @Test
    fun `the folders offered are the ones the host's sessions already use, each once`() {
        assertEquals(listOf("file:///work/a", "file:///work/b"), Rig().manager.offeredFolders())
    }

    @Test
    fun `a new session asks the device check naming the agent and folder, then is created in that one folder`() = runTest {
        val rig = Rig()

        val result = rig.manager.newSession("claude", "file:///work/b")

        assertEquals(ManageResult.Done("ahp-session:/id1"), result)
        assertEquals(listOf(ApprovalRequest("Claude", "file:///work/b", title = "Start a new session?")), rig.asked)
        assertEquals(listOf("createSession ahp-session:/id1 claude [file:///work/b]"), rig.commands.calls)
    }

    @Test
    fun `a session with no folder names none to the host, and the device check says the host chooses`() = runTest {
        val rig = Rig()

        val result = rig.manager.newSession("claude", null)

        assertEquals(ManageResult.Done("ahp-session:/id1"), result)
        assertEquals(listOf(ApprovalRequest("Claude", SettingsSummary.NO_FOLDER, title = "Start a new session?")), rig.asked)
        assertEquals(listOf("createSession ahp-session:/id1 claude null"), rig.commands.calls)
    }

    @Test
    fun `no folder works on a host with no sessions yet, which is the case that offers no folder at all`() = runTest {
        val rig = Rig().apply { sessions = emptyList() }

        assertEquals(emptyList(), rig.manager.offeredFolders())
        assertIs<ManageResult.Done>(rig.manager.newSession("claude", null))
        assertEquals(listOf("createSession ahp-session:/id1 claude null"), rig.commands.calls)
    }

    @Test
    fun `no folder still needs an agent the host offered, the manage tier and the device check`() = runTest {
        val rig = Rig()

        assertEquals(ManageResult.InvalidChoice, rig.manager.newSession("rogue", null))
        rig.allowed = false
        assertEquals(ManageResult.NotAllowed, rig.manager.newSession("claude", null))
        rig.allowed = true
        rig.answer = false
        assertEquals(ManageResult.NotConfirmed, rig.manager.newSession("claude", null))

        assertTrue(rig.commands.calls.isEmpty())
    }

    @Test
    fun `the host is asked for a new session's settings with no folder named when none is chosen`() = runTest {
        val rig = Rig().apply { hostOffers("$thinking") }

        assertIs<ConfigOffer.Form>(rig.manager.sessionSettings("claude", null))
        rig.manager.newSession("claude", null, thoroughly)

        assertEquals(listOf(null, null), rig.commands.resolves.map { it.second })
        assertEquals(listOf("createSession ahp-session:/id1 claude null"), rig.commands.calls)
    }

    @Test
    fun `a new session that fails the device check sends nothing`() = runTest {
        val rig = Rig().apply { answer = false }

        assertEquals(ManageResult.NotConfirmed, rig.manager.newSession("claude", "file:///work/b"))

        assertTrue(rig.commands.calls.isEmpty())
    }

    @Test
    fun `a device check that breaks counts as not confirmed`() = runTest {
        val rig = Rig().apply { gateFailure = IllegalStateException("prompt crashed") }

        assertEquals(ManageResult.NotConfirmed, rig.manager.newSession("claude", "file:///work/b"))

        assertTrue(rig.commands.calls.isEmpty())
    }

    @Test
    fun `an agent or folder the host did not offer is never sent, and never asks the device check`() = runTest {
        val rig = Rig()

        assertEquals(ManageResult.InvalidChoice, rig.manager.newSession("rogue", "file:///work/a"))
        assertEquals(ManageResult.InvalidChoice, rig.manager.newSession("claude", "file:///etc"))
        assertEquals(ManageResult.InvalidChoice, rig.manager.newSession("claude", "file:///work/a/../../etc"))

        assertTrue(rig.asked.isEmpty())
        assertTrue(rig.commands.calls.isEmpty())
    }

    @Test
    fun `a new session is refused before the device check when the connection cannot manage, and after it if that changed meanwhile`() = runTest {
        val before = Rig().apply { allowed = false }
        assertEquals(ManageResult.NotAllowed, before.manager.newSession("claude", "file:///work/a"))
        assertTrue(before.asked.isEmpty())

        val during = Rig()
        during.whileAsking = { during.allowed = false }
        assertEquals(ManageResult.NotAllowed, during.manager.newSession("claude", "file:///work/a"))
        assertTrue(during.commands.calls.isEmpty())
    }

    @Test
    fun `an agent with no display name is named by its provider`() = runTest {
        val rig = Rig().apply { agents = listOf(agent("claude", "")) }

        rig.manager.newSession("claude", "file:///work/a")

        assertEquals("claude", rig.asked.single().toolName)
    }

    // ---- new session settings -----------------------------------------------------------------------------------------

    private val thinking = """"thinking":{"type":"string","title":"Thinking","enum":["low","high"],"enumLabels":["Quick","Thorough"]}"""
    private val approve = """"autoApprove":{"type":"boolean","title":"Approve tools automatically"}"""

    private fun result(properties: String, values: String = "{}", required: List<String> = emptyList()): ResolveSessionConfigResult {
        val requiredPart = if (required.isEmpty()) "" else ",\"required\":[" + required.joinToString(",") { "\"$it\"" } + "]"
        return Ahp.json.decodeFromString(
            ResolveSessionConfigResult.serializer(),
            "{\"schema\":{\"type\":\"object\",\"properties\":{$properties}$requiredPart},\"values\":$values}",
        )
    }

    /** A host that holds [defaults] and, as the protocol says, echoes back whatever the client already chose. */
    private fun Rig.hostOffers(properties: String, defaults: Map<String, JsonElement> = emptyMap(), required: List<String> = emptyList()) {
        commands.resolution = { _, _, config ->
            val values = JsonObject(defaults + config.orEmpty()).toString()
            result(properties, values, required)
        }
    }

    private val thoroughly = mapOf("thinking" to ConfigPick.Text("high"))

    @Test
    fun `the host is asked about the agent and folder, with no choices yet, and its settings come back as a form`() = runTest {
        val rig = Rig().apply { hostOffers("$thinking,$approve", mapOf("thinking" to JsonPrimitive("low"), "autoApprove" to JsonPrimitive(false))) }

        val offer = assertIs<ConfigOffer.Form>(rig.manager.sessionSettings("claude", "file:///work/b"))

        assertEquals(listOf(Triple<String, String?, Map<String, JsonElement>?>("claude", "file:///work/b", null)), rig.commands.resolves)
        assertEquals(listOf("thinking", "autoApprove"), offer.form.fields.map { it.id })
        assertEquals(ConfigPick.Text("low"), offer.form.fields[0].value)
        assertEquals(ConfigPick.Flag(false), offer.form.fields[1].value)
        assertTrue(rig.asked.isEmpty(), "only asking")
        assertTrue(rig.commands.calls.isEmpty())
    }

    @Test
    fun `choices so far go to the host as typed values, text as text and on-off as a boolean`() = runTest {
        val rig = Rig().apply { hostOffers("$thinking,$approve") }

        rig.manager.sessionSettings("claude", "file:///work/b", mapOf("thinking" to ConfigPick.Text("high"), "autoApprove" to ConfigPick.Flag(true)))

        val sent = rig.commands.resolves.single().third!!
        assertEquals(JsonPrimitive("high"), sent["thinking"])
        assertEquals(JsonPrimitive(true), sent["autoApprove"])
    }

    @Test
    fun `a host that offers nothing, or only what this app cannot show, is told apart`() = runTest {
        val none = Rig()
        assertEquals(ConfigOffer.NoSettings, none.manager.sessionSettings("claude", "file:///work/b"))

        val onlyReadOnly = Rig().apply { hostOffers(""""fixed":{"type":"string","title":"Fixed","enum":["x"],"readOnly":true}""") }
        val form = assertIs<ConfigOffer.Form>(onlyReadOnly.manager.sessionSettings("claude", "file:///work/b")).form
        assertEquals(emptyList(), form.fields)
        assertEquals(1, form.omitted)
    }

    @Test
    fun `nothing is asked of the host for an agent or folder it did not offer, or when the connection cannot manage`() = runTest {
        val rig = Rig()

        assertIs<ConfigOffer.Unavailable>(rig.manager.sessionSettings("rogue", "file:///work/a"))
        assertIs<ConfigOffer.Unavailable>(rig.manager.sessionSettings("claude", "file:///etc"))
        rig.allowed = false
        assertIs<ConfigOffer.Unavailable>(rig.manager.sessionSettings("claude", "file:///work/a"))

        assertTrue(rig.commands.resolves.isEmpty())
    }

    @Test
    fun `a host that refuses or cannot be reached is said in words, and its own words are bounded`() = runTest {
        val rig = Rig()
        rig.commands.resolveFailure = AhpException.Rpc(-32601, "x".repeat(500), null)
        val refused = assertIs<ConfigOffer.Unavailable>(rig.manager.sessionSettings("claude", "file:///work/b")).reason
        assertTrue(refused.startsWith("The host refused it: "))
        assertTrue(refused.length <= "The host refused it: ".length + 200)

        rig.commands.resolveFailure = AhpException.ConnectionClosed(null)
        assertIs<ConfigOffer.Unavailable>(rig.manager.sessionSettings("claude", "file:///work/b"))
        rig.commands.resolveFailure = IOException("offline")
        assertIs<ConfigOffer.Unavailable>(rig.manager.sessionSettings("claude", "file:///work/b"))
        rig.commands.resolveFailure = CapabilityDeniedException("resolveSessionConfig", null)
        assertIs<ConfigOffer.Unavailable>(rig.manager.sessionSettings("claude", "file:///work/b"))
    }

    @Test
    fun `a session with no choices made starts with the host's own settings and does not ask it again`() = runTest {
        val rig = Rig().apply { hostOffers(thinking) }

        assertEquals(ManageResult.Done("ahp-session:/id1"), rig.manager.newSession("claude", "file:///work/b"))

        assertTrue(rig.commands.resolves.isEmpty())
        assertEquals(listOf<Map<String, JsonElement>?>(null), rig.commands.configs)
    }

    @Test
    fun `a session with choices asks the host again, names the settings to the device check, and sends what the host resolved`() = runTest {
        val rig = Rig().apply { hostOffers("$thinking,$approve", mapOf("autoApprove" to JsonPrimitive(false))) }

        val result = rig.manager.newSession("claude", "file:///work/b", thoroughly)

        assertEquals(ManageResult.Done("ahp-session:/id1"), result)
        assertEquals(JsonPrimitive("high"), rig.commands.resolves.single().third!!["thinking"])
        assertEquals(listOf(ApprovalRequest("Claude", "Thinking: Thorough\nfile:///work/b", title = "Start a new session?")), rig.asked)
        assertEquals(listOf<Map<String, JsonElement>?>(mapOf("autoApprove" to JsonPrimitive(false), "thinking" to JsonPrimitive("high"))), rig.commands.configs)
    }

    @Test
    fun `a choice the host does not offer, or does not take, or for a setting it does not list, is never sent and never asks the device check`() = runTest {
        val rig = Rig().apply { hostOffers(thinking) }
        val bad = listOf(
            mapOf("thinking" to ConfigPick.Text("extreme")),
            mapOf("other" to ConfigPick.Text("high")),
            mapOf("thinking" to ConfigPick.Flag(true)),
        )
        for (picks in bad) assertEquals(ManageResult.InvalidChoice, rig.manager.newSession("claude", "file:///work/b", picks), "$picks")

        // A host that answers with a value other than the one chosen has not taken it.
        rig.commands.resolution = { _, _, _ -> result(thinking, """{"thinking":"low"}""") }
        assertEquals(ManageResult.InvalidChoice, rig.manager.newSession("claude", "file:///work/b", thoroughly))

        assertTrue(rig.asked.isEmpty())
        assertTrue(rig.commands.calls.isEmpty())
    }

    @Test
    fun `a required setting with no value and no choice is not started`() = runTest {
        val rig = Rig().apply { hostOffers("$thinking,$approve", required = listOf("autoApprove")) }

        assertEquals(ManageResult.InvalidChoice, rig.manager.newSession("claude", "file:///work/b", thoroughly))
        assertEquals(
            ManageResult.Done("ahp-session:/id1"),
            rig.manager.newSession("claude", "file:///work/b", thoroughly + ("autoApprove" to ConfigPick.Flag(false))),
        )
    }

    @Test
    fun `when the host cannot be asked to check the choices nothing is sent and the device check is not asked`() = runTest {
        val rig = Rig().apply { hostOffers(thinking) }
        rig.commands.resolveFailure = AhpException.Rpc(-32601, "no such method", null)
        assertIs<ManageResult.Refused>(rig.manager.newSession("claude", "file:///work/b", thoroughly))

        rig.commands.resolveFailure = IOException("offline")
        assertIs<ManageResult.NotSent>(rig.manager.newSession("claude", "file:///work/b", thoroughly))

        assertTrue(rig.asked.isEmpty())
        assertTrue(rig.commands.calls.isEmpty())
    }

    @Test
    fun `a session with choices that fails the device check, or loses its tier meanwhile, sends nothing`() = runTest {
        val declined = Rig().apply { hostOffers(thinking); answer = false }
        assertEquals(ManageResult.NotConfirmed, declined.manager.newSession("claude", "file:///work/b", thoroughly))
        assertTrue(declined.commands.calls.isEmpty())

        val during = Rig().apply { hostOffers(thinking) }
        during.whileAsking = { during.allowed = false }
        assertEquals(ManageResult.NotAllowed, during.manager.newSession("claude", "file:///work/b", thoroughly))
        assertTrue(during.commands.calls.isEmpty())
    }

    @Test
    fun `the device check line lists settings first and counts any that do not fit`() {
        assertEquals("file:///work/b", SettingsSummary.of("file:///work/b", emptyList()))
        assertEquals(SettingsSummary.NO_FOLDER, SettingsSummary.of(null, emptyList()))
        assertEquals("Thinking: Thorough\n${SettingsSummary.NO_FOLDER}", SettingsSummary.of(null, listOf("Thinking: Thorough")))
        assertEquals("Thinking: Thorough\nMode: Plan\nfile:///work/b", SettingsSummary.of("file:///work/b", listOf("Thinking: Thorough", "Mode: Plan")))

        val many = (1..9).map { "Setting number $it: a fairly long value that is cut" }
        val line = SettingsSummary.of("file:///work/b", many)
        val settingsPart = line.substringBeforeLast("\nfile:///work/b")
        assertTrue(settingsPart.length <= SettingsSummary.MAX_CHARS, "settings fit before the folder: ${settingsPart.length}")
        assertTrue(line.lines().any { it.matches(Regex("""\+\d+ more settings""")) })
        assertTrue(line.lines().filter { it.startsWith("Setting") }.all { it.length <= 38 })
        assertTrue(line.endsWith("file:///work/b"))
    }

    // ---- remove -----------------------------------------------------------------------------------------------------

    @Test
    fun `removing a session asks the device check by its title, then removes exactly that session`() = runTest {
        val rig = Rig()

        assertEquals(ManageResult.Done(), rig.manager.disposeSession(s1))

        assertEquals(listOf(ApprovalRequest("Fix the build", null, title = "Remove this session?")), rig.asked)
        assertEquals(listOf("disposeSession $s1"), rig.commands.calls)
    }

    @Test
    fun `removing a session that fails the device check, is unlisted, or went away meanwhile sends nothing`() = runTest {
        val refused = Rig().apply { answer = false }
        assertEquals(ManageResult.NotConfirmed, refused.manager.disposeSession(s1))
        assertTrue(refused.commands.calls.isEmpty())

        val unlisted = Rig()
        assertEquals(ManageResult.NoSuchTarget, unlisted.manager.disposeSession("ahp-session:/ghost"))
        assertTrue(unlisted.asked.isEmpty())
        assertTrue(unlisted.commands.calls.isEmpty())

        val vanished = Rig()
        vanished.whileAsking = { vanished.sessions = vanished.sessions.filter { it.resource != s1 } }
        assertEquals(ManageResult.NoSuchTarget, vanished.manager.disposeSession(s1))
        assertTrue(vanished.commands.calls.isEmpty())
    }

    @Test
    fun `removing a session is refused when the connection cannot manage`() = runTest {
        val rig = Rig().apply { allowed = false }

        assertEquals(ManageResult.NotAllowed, rig.manager.disposeSession(s1))

        assertTrue(rig.asked.isEmpty())
        assertTrue(rig.commands.calls.isEmpty())
    }

    @Test
    fun `removing a chat needs the chat to be listed by that session, and asks the device check by its title`() = runTest {
        val rig = Rig()

        assertEquals(ManageResult.Done(), rig.manager.disposeChat(s1, c2))

        assertEquals(listOf(ApprovalRequest("Side", null, title = "Remove this chat?")), rig.asked)
        assertEquals(listOf("disposeChat $c2"), rig.commands.calls)
    }

    @Test
    fun `a chat of another session, or one the session does not list, is never removed`() = runTest {
        val rig = Rig()
        rig.states[s2] = state("ready", "ahp-chat:/other" to "Elsewhere")

        assertEquals(ManageResult.NoSuchTarget, rig.manager.disposeChat(s1, "ahp-chat:/other"))
        assertEquals(ManageResult.NoSuchTarget, rig.manager.disposeChat(s1, "ahp-chat:/ghost"))
        assertEquals(ManageResult.NoSuchTarget, rig.manager.disposeChat("ahp-session:/ghost", c1))

        assertTrue(rig.asked.isEmpty())
        assertTrue(rig.commands.calls.isEmpty())
    }

    @Test
    fun `the session's main chat is never removed, and is not even asked about`() = runTest {
        val rig = Rig()
        rig.states[s1] = state("ready", c1 to "Main", c2 to "Side", defaultChat = c1)

        assertEquals(ManageResult.NotRemovable, rig.manager.disposeChat(s1, c1))

        assertTrue(rig.asked.isEmpty(), "no device check for something that cannot be done")
        assertTrue(rig.commands.calls.isEmpty())
        assertEquals(ManageResult.Done(), rig.manager.disposeChat(s1, c2), "its other chat still goes")
    }

    @Test
    fun `a session's only chat is its main chat, so it is not removed either`() = runTest {
        val rig = Rig()
        rig.states[s1] = state("ready", c1 to "Main")

        assertEquals(ManageResult.NotRemovable, rig.manager.disposeChat(s1, c1))

        assertTrue(rig.asked.isEmpty())
        assertTrue(rig.commands.calls.isEmpty())
    }

    @Test
    fun `the chat list marks the main chat and a lone chat as not removable`() {
        val now = java.time.Instant.parse("2026-10-26T10:30:00Z")

        val two = SessionDetail.from(state("ready", c1 to "Main", c2 to "Side", defaultChat = c1), now)
        val alone = SessionDetail.from(state("ready", c1 to "Main"), now)
        val unknownMain = SessionDetail.from(state("ready", c1 to "Main", c2 to "Side"), now)

        assertEquals(listOf(false, true), two.chats.map { it.removable })
        assertEquals(listOf(false), alone.chats.map { it.removable })
        assertEquals(listOf(true, true), unknownMain.chats.map { it.removable }, "with no main chat named, more than one chat can go")
    }

    @Test
    fun `the main chat is the host's default, else the first listed, and the tabs put it first`() {
        val now = java.time.Instant.parse("2026-10-26T10:30:00Z")

        val sideListedFirst = SessionDetail.from(state("ready", c2 to "Side", c1 to "Main", defaultChat = c1), now)
        val noDefault = SessionDetail.from(state("ready", c2 to "Side", c1 to "Main"), now)
        val none = SessionDetail.from(state("ready"), now)

        assertEquals(c1, sideListedFirst.mainChat!!.uri)
        assertEquals(listOf(c1, c2), sideListedFirst.tabs.map { it.uri }, "the main chat leads, the rest keep the host's order")
        assertEquals(listOf(false, true), sideListedFirst.chats.map { it.isMain })
        assertEquals(c2, noDefault.mainChat!!.uri, "no default named: the first the host lists")
        assertEquals(listOf(c2, c1), noDefault.tabs.map { it.uri })
        assertNull(none.mainChat)
        assertEquals(emptyList(), none.tabs)
    }

    @Test
    fun `removing a chat that fails the device check or went away meanwhile sends nothing`() = runTest {
        val refused = Rig().apply { answer = false }
        assertEquals(ManageResult.NotConfirmed, refused.manager.disposeChat(s1, c1))
        assertTrue(refused.commands.calls.isEmpty())

        val vanished = Rig()
        vanished.whileAsking = { vanished.states[s1] = state("ready", c2 to "Side") }
        assertEquals(ManageResult.NoSuchTarget, vanished.manager.disposeChat(s1, c1))
        assertTrue(vanished.commands.calls.isEmpty())
    }

    @Test
    fun `removing a chat is refused when the connection cannot manage`() = runTest {
        val rig = Rig().apply { allowed = false }

        assertEquals(ManageResult.NotAllowed, rig.manager.disposeChat(s1, c1))

        assertTrue(rig.asked.isEmpty())
    }

    // ---- what the host says -----------------------------------------------------------------------------------------

    @Test
    fun `the host's refusal, a lost connection and a denied command are told apart`() = runTest {
        val rig = Rig()

        rig.commands.failure = AhpException.Rpc(-32003, "already exists", null)
        val refused = assertIs<ManageResult.Refused>(rig.manager.newChat(s1))
        assertEquals(-32003, refused.code)

        rig.commands.failure = AhpException.ConnectionClosed(null)
        assertIs<ManageResult.NotSent>(rig.manager.newChat(s1))
        rig.commands.failure = IOException("Not connected to the host")
        assertIs<ManageResult.NotSent>(rig.manager.newChat(s1))
        rig.commands.failure = CapabilityDeniedException("createChat", null)
        assertEquals(ManageResult.NotAllowed, rig.manager.newChat(s1))
    }

    @Test
    fun `a created address is only reported when the host took the command`() = runTest {
        val rig = Rig()
        rig.commands.failure = AhpException.Rpc(-32003, "already exists", null)

        assertTrue(rig.manager.newChat(s1) !is ManageResult.Done)
        assertTrue(rig.manager.newSession("claude", "file:///work/a") !is ManageResult.Done)
    }

    @Test
    fun `every result has words, the host's are clipped, and only Done says nothing`() {
        assertNull(ManageMessages.describe(ManageResult.Done("x")))
        listOf(
            ManageResult.NotAllowed, ManageResult.NoSuchTarget, ManageResult.NotRemovable, ManageResult.InvalidChoice, ManageResult.NotConfirmed,
            ManageResult.Refused(-32003, "no"), ManageResult.NotSent(IOException()), ManageResult.Unconfirmed(DispatchOutcome.NoReply),
        ).forEach { assertTrue(!ManageMessages.describe(it).isNullOrBlank(), "$it") }
        val text = ManageMessages.describe(ManageResult.Refused(-32003, "x".repeat(5_000)))!!
        assertTrue(text.startsWith("The host refused it: xxx"))
        assertTrue(text.length < 260, "${text.length}")
        assertTrue(ManageMessages.succeeded(ManageResult.Done()))
        assertEquals(false, ManageMessages.succeeded(ManageResult.Unconfirmed(DispatchOutcome.Accepted)))
    }

    // ---- the actions' wire shape ------------------------------------------------------------------------------------

    @Test
    fun `the two session actions are pinned to their type and fields`() {
        assertEquals("""{"type":"session/isReadChanged","isRead":true}""", wire(SessionActions.setRead(true)).toString())
        assertEquals("""{"type":"session/isReadChanged","isRead":false}""", wire(SessionActions.setRead(false)).toString())
        assertEquals("""{"type":"session/isArchivedChanged","isArchived":true}""", wire(SessionActions.setArchived(true)).toString())
        assertEquals("""{"type":"session/isArchivedChanged","isArchived":false}""", wire(SessionActions.setArchived(false)).toString())
        assertEquals("""{"type":"session/titleChanged","title":"New title"}""", wire(SessionActions.setTitle("New title")).toString())
    }

    @Test
    fun `setTitle trims the title, rejects blank or overly long titles, and dispatches on the session channel`() = runTest {
        val rig = Rig()

        assertEquals(ManageResult.Done(), rig.manager.setTitle(s1, "  Renamed session  "))
        assertEquals(listOf(s1), rig.sent.map { it.first })
        assertEquals("""{"type":"session/titleChanged","title":"Renamed session"}""", wire(rig.sent.single().second).toString())

        assertEquals(ManageResult.InvalidChoice, rig.manager.setTitle(s1, "   "))
        assertEquals(ManageResult.InvalidChoice, rig.manager.setTitle(s1, "x".repeat(SessionManager.MAX_TITLE_LENGTH + 1)))
        assertEquals(ManageResult.NoSuchTarget, rig.manager.setTitle("ahp-session:/ghost", "Valid"))

        rig.allowed = false
        assertEquals(ManageResult.NotAllowed, rig.manager.setTitle(s1, "Valid"))
    }

    @Test
    fun `forkChat and createSideChat check agent capabilities and chat membership and pass ChatSource and initialMessage`() = runTest {
        val rig = Rig().apply {
            agents = listOf(agent("copilotcli", "Copilot CLI", fork = true, sideChat = true), agent("claude", "Claude", fork = false, sideChat = false))
        }
        val initial = ChatActions.message("branched prompt")

        assertTrue(rig.manager.canFork(s1))
        assertTrue(rig.manager.canSideChat(s1))

        assertEquals(ManageResult.Done("ahp-chat:/id1"), rig.manager.forkChat(s1, c1, "turn-1", initial))
        val forkSource = assertIs<ChatSourceFork>(rig.commands.chatSources[0])
        assertEquals(c1, forkSource.value.chat)
        assertEquals("turn-1", forkSource.value.turnId)
        assertEquals("branched prompt", rig.commands.chatMessages[0]?.text)

        // Fork & Send on the very first turn (turnId == null) creates a peer chat with initialMessage and no source
        assertEquals(ManageResult.Done("ahp-chat:/id2"), rig.manager.forkChat(s1, c1, null, initial))
        assertNull(rig.commands.chatSources[1])
        assertEquals("branched prompt", rig.commands.chatMessages[1]?.text)
        assertEquals(ManageResult.NoSuchTarget, rig.manager.forkChat(s1, c1, null, null))

        assertEquals(ManageResult.Done("ahp-chat:/id3"), rig.manager.createSideChat(s1, c1, "turn-1", initial))
        val sideSource = assertIs<ChatSourceSideChat>(rig.commands.chatSources[2])
        assertEquals(c1, sideSource.value.chat)
        assertEquals("turn-1", sideSource.value.turnId)
        assertEquals("branched prompt", rig.commands.chatMessages[2]?.text)

        // Unlisted source chat or blank turnId -> NoSuchTarget
        assertEquals(ManageResult.NoSuchTarget, rig.manager.forkChat(s1, "ahp-chat:/other", "turn-1"))
        assertEquals(ManageResult.NoSuchTarget, rig.manager.forkChat(s1, c1, "   "))
        assertEquals(ManageResult.NoSuchTarget, rig.manager.createSideChat(s1, "ahp-chat:/other", "turn-1"))
        assertEquals(ManageResult.NoSuchTarget, rig.manager.createSideChat(s1, c1, "   "))

        // Agent without fork/sideChat capabilities -> NotAllowed
        rig.agents = listOf(agent("copilotcli", "Copilot CLI", fork = false, sideChat = false))
        assertFalse(rig.manager.canFork(s1))
        assertFalse(rig.manager.canSideChat(s1))
        assertEquals(ManageResult.NotAllowed, rig.manager.forkChat(s1, c1, "turn-1"))
        assertEquals(ManageResult.NotAllowed, rig.manager.createSideChat(s1, c1, "turn-1"))
    }
}
