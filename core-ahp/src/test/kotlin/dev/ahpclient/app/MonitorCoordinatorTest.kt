package dev.ahpclient.app

import dev.ahpclient.security.FakeBox
import dev.ahpclient.security.FakeStore
import dev.ahpclient.security.SessionListCache
import dev.ahpclient.state.ApprovalTarget
import dev.ahpclient.state.Backoff
import dev.ahpclient.state.CHAT_A
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.FinishedSession
import dev.ahpclient.state.FinishedWatch
import dev.ahpclient.state.HostConnector
import dev.ahpclient.state.HostScript
import dev.ahpclient.state.HostSession
import dev.ahpclient.state.InputNeeded
import dev.ahpclient.state.SESSION_C
import dev.ahpclient.state.ScriptedConnector
import dev.ahpclient.state.chatSummaryJson
import dev.ahpclient.state.fixture
import dev.ahpclient.tunnel.TunnelInfo
import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.Implementation
import com.microsoft.agenthostprotocol.generated.ListSessionsResult
import com.microsoft.agenthostprotocol.generated.SessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
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

    /** Whether the next start works, the way Android may refuse a foreground service started from the background. */
    var startWorks = true

    override fun start(): Boolean {
        starts++
        return startWorks
    }

    override fun stop() {
        stops++
    }
}

private class FakeProblems : MonitorProblemSink {
    val posted = mutableListOf<String>()
    var clears = 0

    override fun post(text: String) {
        posted += text
    }

    override fun clear() {
        clears++
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

private class FakeWatch(initial: FinishedWatch = FinishedWatch()) : FinishedWatchSettings {
    val flow = MutableStateFlow(initial)
    override val watch = flow

    override suspend fun setAll(on: Boolean) {
        flow.value = flow.value.copy(all = on)
    }

    override suspend fun setSession(resource: String, on: Boolean) {
        flow.value = flow.value.copy(sessions = if (on) flow.value.sessions + resource else flow.value.sessions - resource)
    }
}

private class FakeFinishedSink : FinishedSink {
    val posted = mutableListOf<FinishedSession>()
    val cleared = mutableListOf<String>()

    override fun post(finished: FinishedSession) {
        posted += finished
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

    private fun TestScope.rig(
        enabled: Boolean = true,
        finished: FinishedAlerts? = null,
        startWorks: Boolean = true,
        problems: MonitorProblemSink? = null,
    ): Rig {
        val state = MutableStateFlow<AppState>(AppState.Hosts(HostList.Loading, null))
        val settings = FakeSettings(enabled)
        val service = FakeService().also { it.startWorks = startWorks }
        val sink = FakeSink()
        val coordinator = MonitorCoordinator(state, settings, service, sink, backgroundScope, debounceMs = 3_000, finished = finished, finishedDebounceMs = 5_000, problems = problems)
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
    fun `a service that cannot start is said so in the status and to the problem sink`() = runTest {
        val problems = FakeProblems()
        val rig = rig(startWorks = false, problems = problems)

        connect(rig)

        assertEquals(1, rig.service.starts)
        val status = assertNotNull(rig.coordinator.status.value)
        assertEquals("Monitoring dev-box", status.title)
        assertEquals("Monitoring could not start. Open the app.", status.text)
        assertEquals(listOf("Monitoring could not start. Open the app."), problems.posted)
        assertEquals(0, problems.clears, "nothing was monitored before, so there was no warning of this coordinator's to take away")
    }

    @Test
    fun `the first look in a process with nothing connected leaves a warning that was already posted`() = runTest {
        val problems = FakeProblems()

        val rig = rig(problems = problems)

        assertEquals(0, problems.clears)
        assertEquals(1, rig.service.stops, "the service is still told to stop")
    }

    @Test
    fun `the failure stays in the status while the connection changes, and alerts still run`() = runTest {
        val rig = rig(startWorks = false, problems = FakeProblems())
        val host = connect(rig)
        advanceTimeBy(10_000)

        waitingOn(host, "Session D")
        runCurrent()
        advanceTimeBy(3_500)

        assertEquals("Monitoring could not start. Open the app.", rig.coordinator.status.value!!.text)
        assertEquals(listOf("Session D"), rig.sink.posted.map { it.title })
    }

    @Test
    fun `a start that works later clears the problem and brings the status back`() = runTest {
        val problems = FakeProblems()
        val rig = rig(startWorks = false, problems = problems)
        connect(rig)
        val clearsBefore = problems.clears

        rig.service.startWorks = true
        rig.coordinator.refreshService()
        runCurrent()

        assertEquals(2, rig.service.starts)
        assertEquals(clearsBefore + 1, problems.clears)
        assertTrue(rig.coordinator.status.value!!.text.contains("active session"), rig.coordinator.status.value!!.text)
    }

    @Test
    fun `a start that works never touches the problem sink, and ending monitoring clears it`() = runTest {
        val problems = FakeProblems()
        val rig = rig(problems = problems)
        val clearsBefore = problems.clears
        connect(rig)

        assertEquals(emptyList(), problems.posted)
        assertEquals(clearsBefore + 1, problems.clears, "a start that works takes any warning away")
        rig.coordinator.setEnabled(false)
        runCurrent()
        assertEquals(clearsBefore + 2, problems.clears, "turning monitoring off takes it away too")
    }

    @Test
    fun `a failed start is cleared when the user leaves the host`() = runTest {
        val problems = FakeProblems()
        val rig = rig(startWorks = false, problems = problems)
        connect(rig)
        val clearsBefore = problems.clears

        rig.state.value = AppState.Hosts(HostList.Loading, null)
        runCurrent()

        assertNull(rig.coordinator.status.value)
        assertEquals(clearsBefore + 1, problems.clears)
    }

    @Test
    fun `refreshing the service does nothing while monitoring is off`() = runTest {
        val rig = rig(enabled = false)
        connect(rig)

        rig.coordinator.refreshService()

        assertEquals(0, rig.service.starts)
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
    fun `a session waiting on several tool calls says how many and has no buttons`() = runTest {
        val two = """[{"kind":"toolConfirmation","id":"r1","chat":"$CHAT_A","turnId":"t","toolCall":{"status":"pending-confirmation","toolCallId":"a","toolName":"p","displayName":"Run command","intention":"Run the first","invocationMessage":"x"}},
            {"kind":"toolConfirmation","id":"r2","chat":"$CHAT_A","turnId":"t","toolCall":{"status":"pending-confirmation","toolCallId":"b","toolName":"p","displayName":"Run command","intention":"Run the second","invocationMessage":"y"}}]"""
        val rig = rig()
        val host = connect(rig, HostScript(sessionChats = "[${chatSummaryJson(CHAT_A)}]", inputNeeded = two))
        advanceTimeBy(10_000)

        waitingOn(host, "Session C")
        runCurrent()
        advanceTimeBy(3_500)

        val alert = rig.sink.posted.single()
        assertEquals("2 approvals waiting. First: Run command: Run the first. Open the app to decide.", alert.detail)
        assertNull(alert.approval, "which call a button would decide is not clear, so there are none")
    }

    @Test
    fun `a tool call with options of the hosts own carries them as buttons`() = runTest {
        val options = ""","options":[{"id":"o1","label":"Allow once","kind":"approve"},{"id":"o2","label":"Not now","kind":"deny"}]"""
        val rig = rig()
        val host = connect(rig, HostScript(sessionChats = "[${chatSummaryJson(CHAT_A)}]", inputNeeded = approvalIn(CHAT_A, options)))
        advanceTimeBy(10_000)

        waitingOn(host, "Session C")
        runCurrent()
        advanceTimeBy(3_500)

        assertEquals(listOf("o2", "o1"), rig.sink.posted.single().approval?.options?.map { it.id })
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
    private fun working(host: HostSession, title: String, status: SessionStatus) {
        host.root.replaceCatalog(host.root.sessions.value.map { if (it.title == title) it.copy(status = status) else it })
    }

    @Test
    fun `a watched session that stops working alerts once after the debounce`() = runTest {
        val watch = FakeWatch(FinishedWatch(all = true))
        val sink = FakeFinishedSink()
        val rig = rig(finished = FinishedAlerts(watch, sink))
        val host = connect(rig)
        advanceTimeBy(10_000)
        assertEquals(emptyList(), sink.posted, "nothing has stopped yet")

        working(host, "Session D", SessionStatus.IN_PROGRESS)
        runCurrent()
        working(host, "Session D", SessionStatus.IDLE)
        runCurrent()
        advanceTimeBy(4_900)
        assertEquals(emptyList(), sink.posted)
        advanceTimeBy(200)

        assertEquals(listOf("Session D"), sink.posted.map { it.title })
        assertEquals(false, sink.posted.single().failed)
    }

    @Test
    fun `a session left running when the app connected alerts when it stops`() = runTest {
        val sink = FakeFinishedSink()
        val rig = rig(finished = FinishedAlerts(FakeWatch(FinishedWatch(all = true)), sink))
        val host = connect(rig)
        advanceTimeBy(10_000)
        assertEquals(emptyList(), sink.posted, "Session C was working when the list arrived: that is the baseline, not a stop")
        working(host, "Session C", SessionStatus.IDLE)
        runCurrent()
        advanceTimeBy(6_000)

        assertEquals(listOf("Session C"), sink.posted.map { it.title })
        assertNull(sink.posted.single().tookMs, "when it started is not known")
    }

    @Test
    fun `a saved list that differs from the hosts own raises no alert for the difference`() = runTest {
        val live = Ahp.json.decodeFromString(ListSessionsResult.serializer(), fixture("live/list-sessions.json")).items
        // Saved while every session was working: against the host's list, the waiting ones look newly waiting and the idle ones look finished.
        val cache = SessionListCache(FakeBox(), FakeStore())
        cache.save("t", live.map { it.copy(status = SessionStatus.IN_PROGRESS) })
        val finished = FakeFinishedSink()
        val rig = rig(finished = FinishedAlerts(FakeWatch(FinishedWatch(all = true)), finished))
        val release = CompletableDeferred<Unit>()
        val scripted = ScriptedConnector(HostScript())
        val host = HostSession(
            HostConnector { clientId, seq, scope -> release.await(); scripted.connect(clientId, seq, scope) },
            backgroundScope, clientInfo, Backoff(), clientId = "c", cache = cache, cacheKey = "t",
        )
        host.start()
        rig.state.value = AppState.Connected(tunnel, host)
        runCurrent()
        assertTrue(host.root.stale.value, "the saved list is on show while the connection is made")
        assertEquals(live.size, host.root.sessions.value.size)
        advanceTimeBy(10_000)

        release.complete(Unit)
        host.connection.first { it == ConnState.Online }
        advanceTimeBy(60_000)

        assertFalse(host.root.stale.value)
        assertEquals(emptyList(), rig.sink.posted, "who was waiting differs between the two lists: that is no news")
        assertEquals(emptyList(), finished.posted, "who was working differs between the two lists: that is no news")
        waitingOn(host, "Session D")
        runCurrent()
        advanceTimeBy(3_500)
        assertEquals(listOf("Session D"), rig.sink.posted.map { it.title }, "the alerts do run on what the host says next")
    }

    @Test
    fun `nothing alerts for a session that is not watched, and the choice can be changed through the coordinator`() = runTest {
        val watch = FakeWatch()
        val sink = FakeFinishedSink()
        val rig = rig(finished = FinishedAlerts(watch, sink))
        val host = connect(rig)
        working(host, "Session D", SessionStatus.IN_PROGRESS)
        runCurrent()
        working(host, "Session D", SessionStatus.IDLE)
        runCurrent()
        advanceTimeBy(10_000)
        assertEquals(emptyList(), sink.posted)

        val uri = host.root.sessions.value.first { it.title == "Session D" }.resource
        rig.coordinator.setWatched(uri, true)
        assertEquals(FinishedWatch(sessions = setOf(uri)), rig.coordinator.finishedWatch.first())
        working(host, "Session D", SessionStatus.IN_PROGRESS)
        runCurrent()
        working(host, "Session D", SessionStatus.IDLE)
        runCurrent()
        advanceTimeBy(6_000)
        assertEquals(listOf("Session D"), sink.posted.map { it.title })

        rig.coordinator.setWatched(uri, false)
        rig.coordinator.setWatchAll(true)
        assertEquals(FinishedWatch(all = true), rig.coordinator.finishedWatch.first())
    }

    @Test
    fun `with monitoring off nothing alerts, but the choice is still kept`() = runTest {
        val watch = FakeWatch(FinishedWatch(all = true))
        val sink = FakeFinishedSink()
        val rig = rig(enabled = false, finished = FinishedAlerts(watch, sink))
        val host = connect(rig)
        working(host, "Session D", SessionStatus.IN_PROGRESS)
        runCurrent()
        working(host, "Session D", SessionStatus.IDLE)
        runCurrent()
        advanceTimeBy(10_000)
        assertEquals(emptyList(), sink.posted)

        rig.coordinator.setWatchAll(false)
        assertEquals(FinishedWatch(), rig.coordinator.finishedWatch.first())
    }

    @Test
    fun `the session on screen is not alerted, and turning monitoring off stops the alerts`() = runTest {
        val sink = FakeFinishedSink()
        var onScreen: String? = null
        val rig = rig(finished = FinishedAlerts(FakeWatch(FinishedWatch(all = true)), sink, viewing = { onScreen }))
        val host = connect(rig)
        onScreen = host.root.sessions.value.first { it.title == "Session D" }.resource
        working(host, "Session D", SessionStatus.IN_PROGRESS)
        runCurrent()
        working(host, "Session D", SessionStatus.IDLE)
        runCurrent()
        advanceTimeBy(10_000)
        assertEquals(emptyList(), sink.posted)

        onScreen = null
        working(host, "Session D", SessionStatus.IN_PROGRESS)
        runCurrent()
        working(host, "Session D", SessionStatus.IDLE)
        runCurrent()
        rig.settings.setEnabled(false)
        runCurrent()
        advanceTimeBy(10_000)
        assertEquals(emptyList(), sink.posted, "monitoring was turned off before the alert was due")
    }

    @Test
    fun `without finished alerts configured the choice is off and setting it does nothing`() = runTest {
        val rig = rig()

        rig.coordinator.setWatchAll(true)
        rig.coordinator.setWatched("ahp-session:/x", true)

        assertEquals(FinishedWatch(), rig.coordinator.finishedWatch.first())
    }

    @Test
    fun `the session on screen is the open one, and only while the app is in front`() = runTest {
        val rig = rig()
        val host = connect(rig)
        val connected = rig.state.value as AppState.Connected
        val open = connected.copy(open = OpenSession("ahp-session:/x", host.openSession("ahp-session:/x")))

        assertEquals("ahp-session:/x", OnScreen.session(inForeground = true, state = open))
        assertNull(OnScreen.session(inForeground = false, state = open), "pressing Home with a session open is not looking at it")
        assertNull(OnScreen.session(inForeground = true, state = connected), "no session is open")
        assertNull(OnScreen.session(inForeground = true, state = AppState.Hosts(HostList.Loading, null)))
    }
}
