package dev.ahpclient.ahp

import com.microsoft.agenthostprotocol.generated.Implementation
import com.microsoft.agenthostprotocol.generated.SnapshotState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Hosts that leave out a field the protocol requires: seen from `ahpd` 0.14.0 on 2026-10-27, and repaired narrowly, never changing what is sent. */
@OptIn(ExperimentalCoroutinesApi::class)
class HostQuirksTest {
    private val clientInfo = Implementation(name = "ahp-android-client", version = "0.1.0")

    // An agent as ahpd sends its copilot agent: no description, no customizations.
    private val bareAgent = """{"provider":"copilot","displayName":"Copilot","models":[{"id":"m","name":"M","provider":"copilot"}],"capabilities":{}}"""
    private val fullAgent = """{"provider":"claude","displayName":"Claude","description":"Claude Code","models":[]}"""

    private fun root(agents: String) = """{"resource":"ahp-root://","fromSeq":7,"state":{"agents":[$agents],"activeSessions":0}}"""

    private fun parse(text: String) = Json.parseToJsonElement(text)

    @Test
    fun `an agent with no description reads as an empty one, in every place agents appear`() = runTest {
        val socket = FakeSocket { method, id, _ ->
            when (method) {
                "initialize" -> result(id, """{"protocolVersion":"1.0.0","serverSeq":7,"snapshots":[${root("$bareAgent,$fullAgent")}]}""")
                "subscribe" -> result(id, """{"snapshot":${root(bareAgent)}}""")
                else -> null
            }
        }
        val connection = AhpConnection(socket, backgroundScope, CapabilityPolicy.READ, clientId = "c")

        val init = connection.initialize(clientInfo)
        val agents = (init.snapshots.single().state as SnapshotState.Root).value.agents
        assertEquals(listOf("", "Claude Code"), agents.map { it.description })
        assertEquals(listOf("copilot", "claude"), agents.map { it.provider })

        val subscribed = (connection.subscribe("ahp-root://").snapshot!!.state as SnapshotState.Root).value.agents
        assertEquals(listOf(""), subscribed.map { it.description })
    }

    @Test
    fun `root agentsChanged is repaired too, and nothing else about an action is touched`() = runTest {
        val socket = FakeSocket { _, _, _ -> null }
        val connection = AhpConnection(socket, backgroundScope, CapabilityPolicy.READ, clientId = "c")
        socket.serverSends("""{"jsonrpc":"2.0","method":"action","params":{"channel":"ahp-root://","action":{"type":"root/agentsChanged","agents":[$bareAgent]},"serverSeq":9}}""")
        runCurrent()

        val note = connection.notifications.first()

        val envelope = assertIs<ServerNotification.Action>(note).envelope
        assertEquals(9L, envelope.serverSeq)
        assertEquals(9L, connection.lastServerSeq)
    }

    @Test
    fun `a reply that needs nothing is returned as it came`() {
        val plain = parse("""{"protocolVersion":"1.0.0","serverSeq":7,"snapshots":[${root(fullAgent)}]}""")
        assertSame(plain, HostQuirks.result(plain))
        val chat = parse("""{"snapshot":{"resource":"ahp-chat:/x","fromSeq":1,"state":{"turns":[]}}}""")
        assertSame(chat, HostQuirks.result(chat))
        assertSame(null, HostQuirks.result(null))
        val array = JsonArray(emptyList())
        assertSame(array, HostQuirks.result(array))
    }

    @Test
    fun `only a missing description is filled, and a present one is never changed`() {
        val fixed = HostQuirks.result(parse("""{"snapshots":[${root("$bareAgent,$fullAgent")}]}""")) as JsonObject

        val agents = fixed["snapshots"]!!.jsonArray.single().jsonObject["state"]!!.jsonObject["agents"]!!.jsonArray
        assertEquals("", agents[0].jsonObject["description"]!!.jsonPrimitive.content)
        assertEquals("Claude Code", agents[1].jsonObject["description"]!!.jsonPrimitive.content)
        assertEquals(setOf("provider", "displayName", "models", "capabilities", "description"), agents[0].jsonObject.keys)
    }

    @Test
    fun `something that is not an agent is left for the decoder to refuse`() {
        val odd = parse("""{"snapshots":[{"resource":"ahp-root://","fromSeq":1,"state":{"agents":[5,{"x":1},{"provider":"p"}]}}]}""")
        assertSame(odd, HostQuirks.result(odd))
    }

    @Test
    fun `an actions list from a reconnect is repaired and other actions are left alone`() {
        val text = """{"actions":[
            {"channel":"ahp-root://","action":{"type":"root/agentsChanged","agents":[$bareAgent]},"serverSeq":1},
            {"channel":"ahp-session:/a","action":{"type":"session/titleChanged","title":"t"},"serverSeq":2}]}"""
        val fixed = HostQuirks.result(parse(text)) as JsonObject

        val actions = fixed["actions"]!!.jsonArray
        assertEquals("", actions[0].jsonObject["action"]!!.jsonObject["agents"]!!.jsonArray[0].jsonObject["description"]!!.jsonPrimitive.content)
        assertEquals(parse(text).jsonObject["actions"]!!.jsonArray[1], actions[1])
    }

    @Test
    fun `a reply that is really malformed still fails as it did`() = runTest {
        val socket = FakeSocket { method, id, _ -> if (method == "initialize") result(id, """{"protocolVersion":"1.0.0","snapshots":[]}""") else null }
        val connection = AhpConnection(socket, backgroundScope, CapabilityPolicy.READ, clientId = "c")

        assertTrue(assertFailsWith<AhpException.Protocol> { connection.initialize(clientInfo) }.message!!.contains("initialize"))
        assertEquals(JsonPrimitive("x"), HostQuirks.result(JsonPrimitive("x")))
    }
}
