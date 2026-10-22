package dev.ahpclient.app

import dev.ahpclient.state.Backoff
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.HostScript
import dev.ahpclient.state.HostSession
import dev.ahpclient.state.InputNeeded
import dev.ahpclient.state.ScriptedConnector
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
    )

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

    private suspend fun TestScope.connect(rig: Rig): HostSession {
        val host = HostSession(ScriptedConnector(HostScript()), backgroundScope, clientInfo, Backoff(), clientId = "c")
        host.start()
        host.connection.first { it == ConnState.Online }
        rig.state.value = AppState.Connected(tunnel, host)
        runCurrent()
        return host
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
