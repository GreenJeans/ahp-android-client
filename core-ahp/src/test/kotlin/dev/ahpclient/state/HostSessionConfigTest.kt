package dev.ahpclient.state

import dev.ahpclient.ahp.CapabilityPolicy
import com.microsoft.agenthostprotocol.generated.Implementation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A chat's mode and approvals through a whole connection: read from the host's own replies (which the 1.0.0 library cannot decode),
 * changed with `chat/configChanged`, and shown only once the host has echoed the change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostSessionConfigTest {
    private val info = Implementation(name = "ahp-android-client-test", version = "0.0.1")

    private val sessionConfig = """{"schema":{"type":"object","properties":{${properties("autoApprove" to AUTO_APPROVE_PROPERTY, "mode" to MODE_PROPERTY)}}},
        "values":{"autoApprove":"default","mode":"interactive"}}"""

    private val chats = """[{"resource":"$CHAT_A","title":"Main","status":1,"modifiedAt":"2026-10-22T10:00:00.000Z"}]"""

    private class Rig(val connector: ScriptedConnector, val host: HostSession) {
        fun dispatched() = connector.sockets.last().sent.filter { it["method"]?.jsonPrimitive?.content == "dispatchAction" }
    }

    private fun TestScope.rig(script: HostScript, policy: CapabilityPolicy = CapabilityPolicy.INTERACT): Rig {
        val connector = ScriptedConnector(script, policy = policy)
        return Rig(connector, HostSession(connector, backgroundScope, info, Backoff(), "client-1"))
    }

    private suspend fun Rig.start(): Rig = also {
        host.start()
        withTimeout(300_000) { host.connection.first { it == ConnState.Online } }
    }

    private fun TestScope.settle() {
        advanceTimeBy(100)
        runCurrent()
    }

    private fun HostScript(chatConfig: String? = null, onDispatch: (kotlinx.serialization.json.JsonObject) -> String? = { null }) =
        dev.ahpclient.state.HostScript(sessionChats = chats, sessionConfig = sessionConfig, chatConfig = chatConfig, onDispatch = onDispatch)

    private fun ChatStore.own() = ownConfig.value.mapValues { (it.value as JsonPrimitive).content }

    @Test
    fun `an open session reports which settings a chat may set, and an open chat its own values`() = runTest {
        val rig = rig(HostScript(chatConfig = """{"values":{"mode":"plan"}}""")).start()
        val session = rig.host.openSession(SESSION_C)
        val chat = rig.host.openChat(CHAT_A)
        runCurrent()
        settle()

        assertEquals(setOf("mode", "autoApprove"), session.chatMutableKeys.value)
        assertEquals(mapOf("mode" to "plan"), chat.own())
        val view = ChatConfig.view(session.state.value, CHAT_A, session.chatMutableKeys.value, chat.ownConfig.value)
        assertEquals("plan", view.mode!!.current)
        assertEquals("default", view.approvals!!.current)
        assertTrue(view.mode!!.writable && view.approvals!!.writable)
    }

    @Test
    fun `a chat with none of its own settings has none, and shows the session's`() = runTest {
        val rig = rig(HostScript()).start()
        val session = rig.host.openSession(SESSION_C)
        val chat = rig.host.openChat(CHAT_A)
        runCurrent()
        settle()

        assertEquals(emptyMap(), chat.own())
        assertEquals("interactive", ChatConfig.view(session.state.value, CHAT_A, session.chatMutableKeys.value, chat.ownConfig.value).mode!!.current)
    }

    @Test
    fun `a change is shown only once the host echoes it as ours`() = runTest {
        val rig = rig(HostScript(onDispatch = { echoOf(it, SNAPSHOT_SEQ + 1) })).start()
        val session = rig.host.openSession(SESSION_C)
        val chat = rig.host.openChat(CHAT_A)
        runCurrent()
        settle()

        val outcome = rig.host.dispatch(CHAT_A, ChatConfigActions.set("autoApprove", "assisted"))
        settle()

        assertEquals(DispatchOutcome.Accepted, outcome)
        assertEquals(mapOf("autoApprove" to "assisted"), chat.own())
        assertEquals("assisted", ChatConfig.view(session.state.value, CHAT_A, session.chatMutableKeys.value, chat.ownConfig.value).approvals!!.current)
        val sent = rig.dispatched().single()["params"]!!.jsonObject
        assertEquals(CHAT_A, sent["channel"]!!.jsonPrimitive.content)
        assertEquals("""{"type":"chat/configChanged","config":{"autoApprove":"assisted"}}""", sent["action"]!!.jsonObject.toString())
    }

    @Test
    fun `a change the host refuses changes nothing`() = runTest {
        val rig = rig(HostScript(onDispatch = { echoOf(it, SNAPSHOT_SEQ + 1, rejection = "Allow all is turned off by policy") })).start()
        val chat = rig.host.openChat(CHAT_A)
        runCurrent()
        settle()

        val outcome = rig.host.dispatch(CHAT_A, ChatConfigActions.set("autoApprove", "autoApprove"))
        settle()

        assertEquals(DispatchOutcome.Rejected("Allow all is turned off by policy"), outcome)
        assertEquals(emptyMap(), chat.own())
    }

    @Test
    fun `a change made by someone else in VS Code reaches an open chat`() = runTest {
        val rig = rig(HostScript()).start()
        val chat = rig.host.openChat(CHAT_A)
        runCurrent()
        settle()

        rig.connector.sockets.last().serverSends(actionFrame(CHAT_A, """{"type":"chat/configChanged","config":{"mode":"autopilot"}}""", SNAPSHOT_SEQ + 1))
        runCurrent()
        settle()

        assertEquals(mapOf("mode" to "autopilot"), chat.own())
    }

    @Test
    fun `a read only connection cannot change a chat's settings and sends nothing`() = runTest {
        val rig = rig(HostScript(), policy = CapabilityPolicy.READ).start()

        val failed = runCatching { rig.host.dispatch(CHAT_A, ChatConfigActions.set("mode", "plan")) }

        assertTrue(failed.isFailure)
        assertTrue(rig.dispatched().isEmpty())
    }

    @Test
    fun `settings read on the first connection are still known after a reconnect re-subscribes the chat`() = runTest {
        val first = HostScript(chatConfig = """{"values":{"mode":"plan"}}""")
        val second = HostScript(chatConfig = """{"values":{"mode":"autopilot"}}""")
        val connector = ScriptedConnector(first, second, policy = CapabilityPolicy.INTERACT)
        val host = HostSession(connector, backgroundScope, info, Backoff(), "client-1")
        host.start()
        withTimeout(300_000) { host.connection.first { it == ConnState.Online } }
        val chat = host.openChat(CHAT_A)
        runCurrent()
        settle()
        assertEquals(mapOf("mode" to "plan"), chat.own())

        connector.sockets.last().serverCloses(1011)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(mapOf("mode" to "autopilot"), chat.own(), "the new connection's snapshot is the truth")
    }
}
