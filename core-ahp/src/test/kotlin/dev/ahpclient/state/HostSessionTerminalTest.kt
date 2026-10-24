package dev.ahpclient.state

import dev.ahpclient.ahp.CapabilityDeniedException
import dev.ahpclient.ahp.result
import com.microsoft.agenthostprotocol.generated.Implementation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Terminals through [HostSession]: the library cannot decode a terminal's snapshot, so a terminal is subscribed with a
 * hand-read reply, never named in `initialize` or `reconnect` (whose replies would carry the snapshot), and subscribed afresh on
 * every connection. Everything is read only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostSessionTerminalTest {
    private val info = Implementation(name = "ahp-android-client-test", version = "0.0.1")

    private class Rig(val connector: ScriptedConnector, val host: HostSession) {
        val socket get() = connector.sockets.last()

        fun sent(method: String) = socket.sent.filter { it["method"]?.jsonPrimitive?.content == method }.map { it["params"]!!.jsonObject["channel"]!!.jsonPrimitive.content }
    }

    private fun TestScope.rig(vararg scripts: HostScript): Rig {
        val connector = ScriptedConnector(*scripts)
        return Rig(connector, HostSession(connector, backgroundScope, info, Backoff(), "client-1"))
    }

    private suspend fun HostSession.awaitState(match: (ConnState) -> Boolean) = withTimeout(300_000) { connection.first(match) }

    private suspend fun Rig.start(): Rig = also {
        host.start()
        host.awaitState { it == ConnState.Online }
    }

    private fun TestScope.settle() {
        advanceTimeBy(100)
        runCurrent()
    }

    private fun data(text: String) = """{"type":"terminal/data","data":"$text"}"""

    private fun view(store: ChannelStore<com.microsoft.agenthostprotocol.generated.TerminalState>) = TerminalView.from(store.state.value!!)

    @Test
    fun `an open terminal is seeded from its hand-read snapshot and follows its output`() = runTest {
        val rig = rig(HostScript()).start()

        val store = rig.host.openTerminal(TERMINAL_A)
        runCurrent()
        assertEquals(listOf("hello"), view(store).lines)
        assertEquals(listOf(TERMINAL_A), rig.sent("subscribe").filter { it == TERMINAL_A })

        rig.socket.serverSends(actionFrame(TERMINAL_A, data("more\\r\\n"), SNAPSHOT_SEQ + 1))
        settle()

        assertEquals(listOf("hello", "more"), view(store).lines)
    }

    @Test
    fun `a real-shaped terminal address works the same`() = runTest {
        val rig = rig(HostScript()).start()

        val store = rig.host.openTerminal(TERMINAL_REAL)
        runCurrent()

        assertEquals(listOf("hello"), view(store).lines)
        assertEquals(listOf(TERMINAL_REAL), rig.sent("subscribe").filter { it == TERMINAL_REAL })
    }

    @Test
    fun `output at or before the snapshot is not applied twice`() = runTest {
        val rig = rig(HostScript()).start()
        val store = rig.host.openTerminal(TERMINAL_A)
        runCurrent()

        rig.socket.serverSends(actionFrame(TERMINAL_A, data("old\\r\\n"), SNAPSHOT_SEQ))
        settle()

        assertEquals(listOf("hello"), view(store).lines)
    }

    @Test
    fun `only a terminal address is ever opened`() = runTest {
        val rig = rig(HostScript()).start()

        listOf("ahp-session:/x", "ahp-changeset:/c1", "https://example.com/", "ahp-terminal:/", "agenthost-terminal://", "", "ahp-terminal:/a\nb", "ahp-terminal:/" + "x".repeat(TerminalLinks.MAX_URI))
            .forEach { assertFailsWith<IllegalArgumentException>(it) { rig.host.openTerminal(it) } }

        assertEquals(emptyList(), rig.sent("subscribe").filter { it.contains("terminal") })
    }

    @Test
    fun `a terminal is never named to the host when connecting, so its snapshot cannot break the connection`() = runTest {
        val rig = rig(HostScript(), HostScript()).start()
        rig.host.openTerminal(TERMINAL_A)
        runCurrent()

        rig.socket.serverCloses(1011)
        rig.host.awaitState { it is ConnState.Reconnecting }
        rig.host.awaitState { it == ConnState.Online }
        runCurrent()

        val second = rig.connector.sockets.last().sent
        val named = second.filter { it["method"]?.jsonPrimitive?.content in setOf("initialize", "reconnect") }
            .flatMap { frame -> frame["params"]!!.jsonObject.let { (it["initialSubscriptions"] ?: it["subscriptions"])?.jsonArray.orEmpty() } }
            .map { it.jsonPrimitive.content }
        assertFalse(named.any { it.contains("terminal") }, "named: $named")
    }

    @Test
    fun `an open terminal is subscribed afresh after the connection drops`() = runTest {
        val rig = rig(HostScript(), HostScript(terminalState = terminalStateJson(content = """{"type":"unclassified","value":"after\r\n"}"""))).start()
        val store = rig.host.openTerminal(TERMINAL_A)
        runCurrent()
        assertEquals(listOf("hello"), view(store).lines)

        rig.socket.serverCloses(1011)
        rig.host.awaitState { it is ConnState.Reconnecting }
        rig.host.awaitState { it == ConnState.Online }
        runCurrent()

        assertEquals(listOf(TERMINAL_A), rig.connector.sockets.last().sent.filter { it["method"]?.jsonPrimitive?.content == "subscribe" }
            .map { it["params"]!!.jsonObject["channel"]!!.jsonPrimitive.content }.filter { it == TERMINAL_A })
        assertEquals(listOf("after"), view(store).lines, "the fresh snapshot replaces what was shown")
    }

    @Test
    fun `a terminal the host no longer has is reported gone, not left loading`() = runTest {
        val rig = rig(HostScript(missingSessions = setOf(TERMINAL_A))).start()

        val store = rig.host.openTerminal(TERMINAL_A)
        runCurrent()

        assertNull(store.state.value)
        assertIs<ChannelGoneException>(store.error.value)
    }

    @Test
    fun `a host that answers a terminal in a shape that cannot be read is reported, and the connection carries on`() = runTest {
        val bad = HostScript(onTerminalSubscribe = { id, channel -> result(id, """{"snapshot":{"resource":"$channel","fromSeq":5,"state":{"content":"not a list"}}}""") })
        val rig = rig(bad).start()

        val store = rig.host.openTerminal(TERMINAL_A)
        runCurrent()

        assertNull(store.state.value)
        assertNotNull(store.error.value)
        assertFalse(store.needsSubscribe, "it is not retried in a loop")
        assertEquals(ConnState.Online, rig.host.connection.value)
    }

    @Test
    fun `a reply for another channel, or with no terminal in it, is refused`() = runTest {
        val wrongChannel = HostScript(onTerminalSubscribe = { id, _ -> result(id, """{"snapshot":${terminalSnapshotJson("ahp-terminal:/other", 5)}}""") })
        val noContent = HostScript(onTerminalSubscribe = { id, channel -> result(id, """{"snapshot":{"resource":"$channel","fromSeq":5,"state":{"title":"x"}}}""") })
        val noSnapshot = HostScript(onTerminalSubscribe = { id, _ -> result(id, "{}") })

        for (script in listOf(wrongChannel, noContent, noSnapshot)) {
            val rig = rig(script).start()
            val store = rig.host.openTerminal(TERMINAL_A)
            runCurrent()
            assertNull(store.state.value)
            assertNotNull(store.error.value)
        }
    }

    @Test
    fun `closing a terminal unsubscribes only when the last opener leaves`() = runTest {
        val rig = rig(HostScript()).start()
        rig.host.openTerminal(TERMINAL_A)
        rig.host.openTerminal(TERMINAL_A)
        runCurrent()

        rig.host.closeChannel(TERMINAL_A)
        assertEquals(emptyList(), rig.sent("unsubscribe"))

        rig.host.closeChannel(TERMINAL_A)
        assertEquals(listOf(TERMINAL_A), rig.sent("unsubscribe"))
    }

    @Test
    fun `watching a terminal sends nothing outside the read tier, and typing into one is refused`() = runTest {
        val rig = rig(HostScript()).start()
        rig.host.openTerminal(TERMINAL_A)
        runCurrent()
        rig.socket.serverSends(actionFrame(TERMINAL_A, data("x\\r\\n"), SNAPSHOT_SEQ + 1))
        settle()
        rig.host.closeChannel(TERMINAL_A)

        val allowed = setOf("initialize", "reconnect", "ping", "subscribe", "unsubscribe", "listSessions", "fetchTurns")
        assertEquals(emptySet(), rig.socket.sentMethods.toSet() - allowed)
        assertFalse(rig.host.canInteract.value)
        assertTrue(runCatching { rig.host.dispatch(TERMINAL_A, terminalInput()) }.exceptionOrNull() is CapabilityDeniedException)
    }

    private fun terminalInput() = com.microsoft.agenthostprotocol.Ahp.json.decodeFromString(
        com.microsoft.agenthostprotocol.generated.StateAction.serializer(),
        """{"type":"terminal/input","data":"rm -rf /"}""",
    )
}
