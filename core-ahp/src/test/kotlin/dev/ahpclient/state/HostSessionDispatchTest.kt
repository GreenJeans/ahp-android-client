package dev.ahpclient.state

import dev.ahpclient.ahp.CapabilityDeniedException
import dev.ahpclient.ahp.CapabilityPolicy
import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.Implementation
import com.microsoft.agenthostprotocol.generated.StateAction
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Sending actions through [HostSession]: matching the host's echo to the call, and never applying what the host refused. */
@OptIn(ExperimentalCoroutinesApi::class)
class HostSessionDispatchTest {
    private val info = Implementation(name = "ahp-android-client-test", version = "0.0.1")

    private val startTurn: StateAction get() = Ahp.json.decodeFromString(StateAction.serializer(), TURN_STARTED)

    private class Rig(val connector: ScriptedConnector, val host: HostSession) {
        val socket get() = connector.sockets.last()

        fun dispatched() = socket.sent.filter { it["method"]?.jsonPrimitive?.content == "dispatchAction" }
    }

    private fun TestScope.rig(vararg scripts: HostScript, policy: CapabilityPolicy = CapabilityPolicy.INTERACT): Rig {
        val connector = ScriptedConnector(*scripts, policy = policy)
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

    @Test
    fun `an accepted action resolves once the host echoes it as ours and the chat shows its effect`() = runTest {
        val rig = rig(HostScript(onDispatch = { echoOf(it, SNAPSHOT_SEQ + 1) })).start()
        val store = rig.host.openChat(CHAT_A)
        runCurrent()
        assertNull(store.state.value!!.activeTurn)

        val outcome = rig.host.dispatch(CHAT_A, startTurn)
        settle()

        assertEquals(DispatchOutcome.Accepted, outcome)
        assertNotNull(store.state.value!!.activeTurn, "the chat changes only when the host's echo arrives")
        val frame = rig.dispatched().single()
        assertEquals(CHAT_A, frame["params"]!!.jsonObject["channel"]!!.jsonPrimitive.content)
        assertEquals("chat/turnStarted", frame["params"]!!.jsonObject["action"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nothing is applied before the host answers`() = runTest {
        val rig = rig(HostScript()).start()
        val store = rig.host.openChat(CHAT_A)
        runCurrent()

        val outcome = async { rig.host.dispatch(CHAT_A, startTurn) }
        runCurrent()
        settle()

        assertTrue(outcome.isActive)
        assertNull(store.state.value!!.activeTurn, "no optimistic apply")
        outcome.cancel()
    }

    @Test
    fun `a rejected action reports the reason, changes nothing and does not use up its sequence number`() = runTest {
        val rig = rig(HostScript(onDispatch = { echoOf(it, SNAPSHOT_SEQ + 1, rejection = "a turn is already running") })).start()
        val store = rig.host.openChat(CHAT_A)
        runCurrent()

        val outcome = rig.host.dispatch(CHAT_A, startTurn)
        settle()

        assertEquals(DispatchOutcome.Rejected("a turn is already running"), outcome)
        assertNull(store.state.value!!.activeTurn, "a refused action must not be reduced")

        // The host may reuse the number a refusal carried; the real action that follows must still apply.
        rig.socket.serverSends(actionFrame(CHAT_A, TURN_STARTED, SNAPSHOT_SEQ + 1))
        runCurrent()
        settle()
        assertNotNull(store.state.value!!.activeTurn)
    }

    @Test
    fun `an echo of someone else's action does not answer ours`() = runTest {
        val rig = rig(HostScript(onDispatch = { echoOf(it, SNAPSHOT_SEQ + 1, clientId = "someone-else") })).start()

        val outcome = rig.host.dispatch(CHAT_A, startTurn, timeoutMs = 1_000)

        assertEquals(DispatchOutcome.NoReply, outcome)
    }

    @Test
    fun `a turn echoed with no origin, as ahpd does when it wakes a session, is accepted and shown once`() = runTest {
        // ahpd answers on the chat it woke, with no origin: the turn id is what names it.
        val rig = rig(HostScript(onDispatch = { notification("action", """{"channel":"$CHAT_A","action":$TURN_STARTED,"serverSeq":${SNAPSHOT_SEQ + 1}}""") })).start()
        val store = rig.host.openChat(CHAT_A)
        runCurrent()

        val outcome = rig.host.dispatch(CHAT_A, startTurn, timeoutMs = 1_000)
        settle()

        assertEquals(DispatchOutcome.Accepted, outcome)
        assertEquals("t1", store.state.value!!.activeTurn?.id)
    }

    @Test
    fun `an echo with no origin that matches nothing sent answers nothing`() = runTest {
        val other = TURN_STARTED.replace("\"t1\"", "\"t9\"")
        val rig = rig(HostScript(onDispatch = { notification("action", """{"channel":"$CHAT_A","action":$other,"serverSeq":${SNAPSHOT_SEQ + 1}}""") })).start()
        val store = rig.host.openChat(CHAT_A)
        runCurrent()

        val outcome = rig.host.dispatch(CHAT_A, startTurn, timeoutMs = 1_000)

        assertEquals(DispatchOutcome.NoReply, outcome)
        assertEquals("t9", store.state.value!!.activeTurn?.id, "it is still applied, as someone else's action")
    }

    @Test
    fun `identical actions in flight are answered by origin-less echoes in the order they were sent`() = runTest {
        val cancel = """{"type":"chat/turnCancelled","turnId":"t1","duration":0}"""
        val rig = rig(HostScript()).start()
        val action = Ahp.json.decodeFromString(StateAction.serializer(), cancel)
        val first = async { rig.host.dispatch(CHAT_A, action, timeoutMs = 5_000) }
        val second = async { rig.host.dispatch(CHAT_A, action, timeoutMs = 5_000) }
        runCurrent()

        rig.socket.serverSends(notification("action", """{"channel":"$CHAT_A","action":$cancel,"serverSeq":${SNAPSHOT_SEQ + 1},"rejectionReason":"no turn"}"""))
        runCurrent()
        assertEquals(DispatchOutcome.Rejected("no turn"), first.await())
        assertTrue(second.isActive, "one echo answers one dispatch")

        rig.socket.serverSends(notification("action", """{"channel":"$CHAT_B","action":$cancel,"serverSeq":${SNAPSHOT_SEQ + 1}}"""))
        runCurrent()
        assertTrue(second.isActive, "anything but a turn must come back on the channel it was sent on")
        assertEquals(DispatchOutcome.NoReply, second.await())
    }

    @Test
    fun `a silent host ends in NoReply after the timeout, not an error`() = runTest {
        val rig = rig(HostScript()).start()

        val outcome = rig.host.dispatch(CHAT_A, startTurn, timeoutMs = 1_000)

        assertEquals(DispatchOutcome.NoReply, outcome)
        assertEquals(1, rig.dispatched().size, "it was sent exactly once")
    }

    @Test
    fun `a connection that drops while waiting is Interrupted, not a failure and not a retry`() = runTest {
        val rig = rig(HostScript()).start()
        val outcome = async { rig.host.dispatch(CHAT_A, startTurn) }
        runCurrent()

        rig.socket.serverCloses(1011)

        assertEquals(DispatchOutcome.Interrupted, outcome.await())
        assertEquals(1, rig.dispatched().size)
    }

    @Test
    fun `without a connection nothing is sent and the call fails`() = runTest {
        val rig = rig(HostScript())

        assertFailsWith<IOException> { rig.host.dispatch(CHAT_A, startTurn) }

        assertTrue(rig.connector.sockets.isEmpty())
    }

    @Test
    fun `a read only connection refuses to dispatch and sends nothing`() = runTest {
        val rig = rig(HostScript(), policy = CapabilityPolicy.READ).start()

        assertFailsWith<CapabilityDeniedException> { rig.host.dispatch(CHAT_A, startTurn) }

        assertTrue(rig.dispatched().isEmpty())
    }

    @Test
    fun `canInteract follows the tier of the live connection`() = runTest {
        val interact = rig(HostScript(), policy = CapabilityPolicy.INTERACT).start()
        val readOnly = rig(HostScript(), policy = CapabilityPolicy.READ).start()

        assertTrue(interact.host.canInteract.value)
        assertFalse(readOnly.host.canInteract.value)

        interact.host.stop()
        assertFalse(interact.host.canInteract.value, "a stopped session offers nothing")
    }

    @Test
    fun `canInteract is false while reconnecting`() = runTest {
        val rig = rig(HostScript(), HostScript(), policy = CapabilityPolicy.INTERACT).start()

        rig.socket.serverCloses(1011)
        rig.host.awaitState { it is ConnState.Reconnecting }

        assertFalse(rig.host.canInteract.value)
        rig.host.awaitState { it == ConnState.Online }
        assertTrue(rig.host.canInteract.value)
    }

    @Test
    fun `restart ends the live connection at once and the next one uses the tier then in force`() = runTest {
        val rig = rig(HostScript(), HostScript(), policy = CapabilityPolicy.INTERACT).start()
        val firstSocket = rig.socket
        assertTrue(rig.host.canInteract.value)

        rig.connector.policy = CapabilityPolicy.READ
        rig.host.restart()
        rig.host.awaitState { it is ConnState.Connecting }
        rig.host.awaitState { it == ConnState.Online }

        assertEquals(2, rig.connector.sockets.size)
        assertTrue(rig.socket !== firstSocket)
        assertFalse(rig.host.canInteract.value, "the permission was taken away, so the connection that held it is gone")
        assertFailsWith<CapabilityDeniedException> { rig.host.dispatch(CHAT_A, startTurn) }
        assertTrue(testScheduler.currentTime < 1_000, "a deliberate restart does not wait out the reconnect backoff")
    }

    @Test
    fun `a restart asked for while connecting discards the connection made under the old tier`() = runTest {
        val inner = ScriptedConnector(HostScript(), HostScript(), policy = CapabilityPolicy.INTERACT)
        lateinit var host: HostSession
        var first = true
        val connector = HostConnector { clientId, serverSeq, scope ->
            inner.connect(clientId, serverSeq, scope).also {
                if (first) {
                    first = false
                    inner.policy = CapabilityPolicy.READ
                    host.restart()
                }
            }
        }
        host = HostSession(connector, backgroundScope, info, Backoff(), "client-1")

        host.start()
        host.awaitState { it == ConnState.Online }
        runCurrent()

        assertEquals(2, inner.sockets.size)
        assertFalse(host.canInteract.value)
        assertFailsWith<CapabilityDeniedException> { host.dispatch(CHAT_A, startTurn) }
    }

    @Test
    fun `the client sequence keeps increasing across a reconnect`() = runTest {
        val echo = HostScript(onDispatch = { echoOf(it, SNAPSHOT_SEQ + 1) })
        val rig = rig(echo, HostScript(onDispatch = { echoOf(it, SNAPSHOT_SEQ + 2) })).start()
        rig.host.dispatch(CHAT_A, startTurn)
        val first = rig.dispatched().single()["params"]!!.jsonObject["clientSeq"]!!.jsonPrimitive.long

        rig.socket.serverCloses(1011)
        rig.host.awaitState { it is ConnState.Reconnecting }
        rig.host.awaitState { it == ConnState.Online }
        rig.host.dispatch(CHAT_A, startTurn)
        val second = rig.dispatched().single()["params"]!!.jsonObject["clientSeq"]!!.jsonPrimitive.long

        assertEquals(1L, first)
        assertEquals(2L, second, "a reconnect reuses the client id, so the sequence must not start over")
    }
}
