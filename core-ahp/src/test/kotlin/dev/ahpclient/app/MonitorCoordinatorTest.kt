package dev.ahpclient.app

import dev.ahpclient.state.ApprovalTarget
import dev.ahpclient.state.Backoff
import dev.ahpclient.state.CHAT_A
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.HostScript
import dev.ahpclient.state.HostSession
import dev.ahpclient.state.InputNeeded
import dev.ahpclient.state.SESSION_C
import dev.ahpclient.state.ScriptedConnector
import dev.ahpclient.state.chatSummaryJson
import dev.ahpclient.tunnel.TunnelInfo
import com.microsoft.agenthostprotocol.generated.Implementation
import com.microsoft.agenthostprotocol.generated.SessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private class FakeSettings(initial: Boolean) : MonitorSettings {
    val flow = MutableStateFlow(initial)
    override val enabled = flow

    override suspend fun setEnabled(on: Boolean) {
        flow.value = on
    }
}

private class FakeService : MonitorService {
    var starts = 0
    var stops = 0

    override fun start() {
        starts++
    }

    override fun stop() {
        stops++
    }
}

private class FakeSink : InputNeededSink {
    val posted = mutableListOf<InputNeeded>()
    val cleared = mutableListOf<String>()

    override fun post(request: InputNeeded) {
        posted += request
    }

    override fun clear(resource: String) {
        cleared += resource
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MonitorCoordinatorTest {
    private val tunnel = TunnelInfo("t", "usw2", "dev-box", 6, 1, emptyList())
    private val clientInfo = Implementation(name = "test", version = "0")

    private class Rig(
        val state: MutableStateFlow<AppState>,
        val settings: FakeSettings,
        val service: FakeService,
        val sink: FakeSink,
        val coordinator: MonitorCoordinator,
    ) {
        /** The scripted connection of the last [connect], so a test can read what was sent on it. */
        var connector: ScriptedConnector? = null
    }

    private fun TestScope.rig(enabled: Boolean = true): Rig {
        val state = MutableStateFlow<AppState>(AppState.Hosts(HostList.Loading, null))
        val settings = FakeSettings(enabled)
        val service = FakeService()
        val sink = FakeSink()
        val coordinator = MonitorCoordinator(state, settings, service, sink, backgroundScope, debounceMs = 3_000)
        coordinator.start()
        runCurrent()
        return Rig(state, settings, service, sink, coordinator)
    }

    private suspend fun TestScope.connect(rig: Rig, script: HostScript = HostScript()): HostSession {
        val connector = ScriptedConnector(script).also { rig.connector = it }
        val host = HostSession(connector, backgroundScope, clientInfo, Backoff(), clientId = "c")
        host.start()
        host.connection.first { it == ConnState.Online }
        rig.state.value = AppState.Connected(tunnel, host)
        runCurrent()
        return host
    }

    /** Channels the app subscribed to or left on its single connection, by method. */
    private fun Rig.channels(method: String): List<String> = connector!!.sockets.single().sent
        .filter { it["method"]?.jsonPrimitive?.content == method }
        .map { it["params"]!!.jsonObject["channel"]!!.jsonPrimitive.content }

    private fun waitingOn(host: HostSession, title: String) {
        host.root.replaceCatalog(host.root.sessions.value.map { if (it.title == title) it.copy(status = SessionStatus.INPUT_NEEDED) else it })
    }

    @Test
    fun `nothing runs while the user is not connected to a host`() = runTest {
        val rig = rig()

        assertEquals(0, rig.service.starts)
        assertNull(rig.coordinator.status.value)
    }

    @Test
    fun `connecting with monitoring on starts the service and shows the status`() = runTest {
        val rig = rig()

        connect(rig)

        assertEquals(1, rig.service.starts)
        val status = assertNotNull(rig.coordinator.status.value)
        assertEquals("Monitoring dev-box", status.title)
        assertTrue(status.text.contains("active session"), status.text)
    }

    @Test
    fun `connecting with monitoring off starts nothing, and turning it on then starts it`() = runTest {
        val rig = rig(enabled = false)
        connect(rig)

        assertEquals(0, rig.service.starts)
        assertNull(rig.coordinator.status.value)

        rig.coordinator.setEnabled(true)
        runCurrent()

        assertEquals(1, rig.service.starts)
        assertNotNull(rig.coordinator.status.value)
    }

    @Test
    fun `turning it off stops the service and clears the status`() = runTest {
        val rig = rig()
        connect(rig)
        val stopsBefore = rig.service.stops

        rig.coordinator.setEnabled(false)
        runCurrent()

        assertEquals(stopsBefore + 1, rig.service.stops)
        assertNull(rig.coordinator.status.value)
    }

    @Test
    fun `leaving the host or signing out stops it`() = runTest {
        val rig = rig()
        connect(rig)
        val stopsBefore = rig.service.stops

        rig.state.value = AppState.Hosts(HostList.Loading, null)
        runCurrent()
        assertEquals(stopsBefore + 1, rig.service.stops)
        assertNull(rig.coordinator.status.value)

        connect(rig)
        rig.state.value = AppState.SignedOut(SignInStep.Idle, null)
        runCurrent()
        assertNull(rig.coordinator.status.value)
        assertEquals(stopsBefore + 2, rig.service.stops)
    }

    @Test
    fun `opening a session detail does not restart the service`() = runTest {
        val rig = rig()
        val host = connect(rig)
        val stopsBefore = rig.service.stops

        val connected = rig.state.value as AppState.Connected
        rig.state.value = connected.copy(open = OpenSession("ahp-session:/x", host.openSession("ahp-session:/x")))
        runCurrent()

        assertEquals(1, rig.service.starts)
        assertEquals(stopsBefore, rig.service.stops)
    }

    @Test
    fun `a session that starts waiting notifies once after the debounce, not the ones already waiting`() = runTest {
        val rig = rig()
        val host = connect(rig)
        advanceTimeBy(10_000)
        assertEquals(emptyList(), rig.sink.posted, "the session that was already waiting is the baseline")

        host.root.replaceCatalog(host.root.sessions.value.map { if (it.title == "Session D") it.copy(status = SessionStatus.INPUT_NEEDED) else it })
        runCurrent()
        advanceTimeBy(2_900)
        assertEquals(emptyList(), rig.sink.posted)
        advanceTimeBy(200)

        assertEquals(listOf("Session D"), rig.sink.posted.map { it.title })
    }

    @Test
    fun `the alert says what the session is waiting for, read with one subscribe and one unsubscribe`() = runTest {
        val question = """[{"kind":"chatInput","id":"r1","chat":"c","request":{"id":"q","message":"Which season do you prefer?"}}]"""
        val rig = rig()
        val host = connect(rig, HostScript(inputNeeded = question))
        advanceTimeBy(10_000)

        waitingOn(host, "Session C")
        runCurrent()
        advanceTimeBy(3_500)

        val alert = rig.sink.posted.single()
        assertEquals("Session C", alert.title)
        assertEquals("Which season do you prefer?", alert.detail)
        assertEquals(listOf(SESSION_C), rig.channels("subscribe").filter { it == SESSION_C })
        assertEquals(listOf(SESSION_C), rig.channels("unsubscribe"))
    }

    @Test
    fun `an approval request is described by the tool and what it will do`() = runTest {
        val approval = """[{"kind":"toolConfirmation","id":"r2","chat":"c","turnId":"t","toolCall":{"status":"pending-confirmation",
            "toolCallId":"tc","toolName":"run_in_terminal","displayName":"Run command","intention":"Run the tests","invocationMessage":"Run npm test"}}]"""
        val rig = rig()
        val host = connect(rig, HostScript(inputNeeded = approval))
        advanceTimeBy(10_000)

        waitingOn(host, "Session C")
        runCurrent()
        advanceTimeBy(3_500)

        assertEquals("Run command: Run the tests", rig.sink.posted.single().detail)
        assertNull(rig.sink.posted.single().approval, "its chat is not one the session lists, so there are no buttons")
    }

    private fun approvalIn(chat: String, options: String = "") =
        """[{"kind":"toolConfirmation","id":"r2","chat":"$chat","turnId":"t","toolCall":{"status":"pending-confirmation",
            "toolCallId":"tc","toolName":"run_in_terminal","displayName":"Run command","intention":"Run the tests","invocationMessage":"Run npm test"$options}}]"""

    @Test
    fun `one plain tool call in a listed chat carries the call its notification buttons act on`() = runTest {
        val rig = rig()
        val host = connect(rig, HostScript(sessionChats = "[${chatSummaryJson(CHAT_A)}]", inputNeeded = approvalIn(CHAT_A)))
        advanceTimeBy(10_000)

        waitingOn(host, "Session C")
        runCurrent()
        advanceTimeBy(3_500)

        val alert = rig.sink.posted.single()
        assertEquals(ApprovalTarget(CHAT_A, "tc"), alert.approval)
        assertEquals("Run command: Run the tests", alert.detail)
        assertEquals(listOf(SESSION_C), rig.channels("unsubscribe"), "reading it is still one subscribe and one unsubscribe, in the read tier")
    }

    @Test
    fun `a tool call with options of the hosts own carries no buttons`() = runTest {
        val options = ""","options":[{"id":"o1","label":"Allow once","kind":"approve"},{"id":"o2","label":"Not now","kind":"deny"}]"""
        val rig = rig()
        val host = connect(rig, HostScript(sessionChats = "[${chatSummaryJson(CHAT_A)}]", inputNeeded = approvalIn(CHAT_A, options)))
        advanceTimeBy(10_000)

        waitingOn(host, "Session C")
        runCurrent()
        advanceTimeBy(3_500)

        assertNull(rig.sink.posted.single().approval)
    }

    @Test
    fun `a session with nothing readable still alerts, with its title alone`() = runTest {
        val rig = rig()
        val host = connect(rig, HostScript())
        advanceTimeBy(10_000)

        waitingOn(host, "Session C")
        runCurrent()
        advanceTimeBy(3_500)

        val alert = rig.sink.posted.single()
        assertEquals("Session C", alert.title)
        assertNull(alert.detail)
    }

    @Test
    fun `a session the host has just dropped still alerts, and is left again`() = runTest {
        val rig = rig()
        val host = connect(rig, HostScript(missingSessions = setOf(SESSION_C)))
        advanceTimeBy(10_000)

        waitingOn(host, "Session C")
        runCurrent()
        advanceTimeBy(3_500)

        assertNull(rig.sink.posted.single().detail)
        assertEquals(listOf(SESSION_C), rig.channels("unsubscribe"))
    }

    @Test
    fun `a session that starts waiting is alerted once and only reads the session once`() = runTest {
        val question = """[{"kind":"chatInput","id":"r1","chat":"c","request":{"id":"q","message":"Which season do you prefer?"}}]"""
        val rig = rig()
        val host = connect(rig, HostScript(inputNeeded = question))
        advanceTimeBy(10_000)

        waitingOn(host, "Session C")
        runCurrent()
        advanceTimeBy(3_500)
        host.root.replaceCatalog(host.root.sessions.value.map { if (it.title == "Session C") it.copy(activity = "still waiting") else it })
        runCurrent()
        advanceTimeBy(10_000)

        assertEquals(1, rig.sink.posted.size)
        assertEquals(1, rig.channels("subscribe").count { it == SESSION_C })
    }

    @Test
    fun `the status follows the list`() = runTest {
        val rig = rig()
        val host = connect(rig)
        val before = rig.coordinator.status.value!!.text

        host.root.replaceCatalog(host.root.sessions.value.map { it.copy(status = SessionStatus.IDLE) })
        runCurrent()

        assertTrue(before.contains("need"), before)
        assertEquals("6 active sessions", rig.coordinator.status.value!!.text)
    }
}
