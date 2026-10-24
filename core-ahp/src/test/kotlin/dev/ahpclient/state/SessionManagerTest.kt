package dev.ahpclient.state

import dev.ahpclient.ahp.AhpException
import dev.ahpclient.ahp.CapabilityDeniedException
import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.AgentInfo
import com.microsoft.agenthostprotocol.generated.SessionState
import com.microsoft.agenthostprotocol.generated.SessionSummary
import com.microsoft.agenthostprotocol.generated.StateAction
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
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

    private fun agent(provider: String, name: String) =
        Ahp.json.decodeFromString(AgentInfo.serializer(), """{"provider":"$provider","displayName":"$name","description":"d","models":[]}""")

    private fun state(lifecycle: String, vararg chats: Pair<String, String>) = Ahp.json.decodeFromString(
        SessionState.serializer(),
        """{"provider":"copilotcli","title":"T","status":1,"lifecycle":"$lifecycle","activeClients":[],
            "chats":[${chats.joinToString(",") { """{"resource":"${it.first}","title":"${it.second}","status":1,"modifiedAt":"2026-10-26T10:29:00.000Z"}""" }}]}""",
    )

    private class Commands : ManagementCommands {
        val calls = mutableListOf<String>()
        var failure: Throwable? = null

        private fun record(call: String) {
            calls += call
            failure?.let { throw it }
        }

        override suspend fun createSession(uri: String, provider: String?, workingDirectories: List<String>?) = record("createSession $uri $provider $workingDirectories")

        override suspend fun createChat(sessionUri: String, chatUri: String) = record("createChat $sessionUri $chatUri")

        override suspend fun disposeSession(uri: String) = record("disposeSession $uri")

        override suspend fun disposeChat(uri: String) = record("disposeChat $uri")
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
            ManageResult.NotAllowed, ManageResult.NoSuchTarget, ManageResult.InvalidChoice, ManageResult.NotConfirmed,
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
    }
}
