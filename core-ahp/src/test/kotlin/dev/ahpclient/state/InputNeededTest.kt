package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ListSessionsResult
import com.microsoft.agenthostprotocol.generated.SessionStatus
import com.microsoft.agenthostprotocol.generated.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private val fixtureSessions = Ahp.json.decodeFromString(ListSessionsResult.serializer(), fixture("live/list-sessions.json")).items

private fun idle(n: Int) = fixtureSessions.first { it.title == "Session B" }.copy(resource = "ahp-session:/s$n", title = "Idle $n", status = SessionStatus.IDLE)

private fun waiting(n: Int) = idle(n).copy(title = "Waiting $n", status = SessionStatus.INPUT_NEEDED or SessionStatus.IS_READ)

class InputNeededDetectorTest {
    private val detector = InputNeededDetector()

    @Test
    fun `the first list only records who is already waiting`() {
        assertEquals(emptyList(), detector.onSessions(listOf(waiting(1), waiting(2), idle(3))))
    }

    @Test
    fun `a session that starts waiting is reported once, with its title`() {
        detector.onSessions(listOf(idle(1), idle(2)))

        assertEquals(listOf(InputNeeded("ahp-session:/s1", "Waiting 1")), detector.onSessions(listOf(waiting(1), idle(2))))
        assertEquals(emptyList(), detector.onSessions(listOf(waiting(1), idle(2))))
        assertEquals(emptyList(), detector.onSessions(listOf(waiting(1).copy(activity = "still waiting"), idle(2))))
    }

    @Test
    fun `a session that leaves and re-enters the state is reported again`() {
        detector.onSessions(listOf(idle(1)))
        detector.onSessions(listOf(waiting(1)))
        detector.onSessions(listOf(idle(1)))

        assertEquals(1, detector.onSessions(listOf(waiting(1))).size)
    }

    @Test
    fun `a session that appears already waiting is reported, and a vanished one is forgotten`() {
        detector.onSessions(listOf(idle(1)))

        assertEquals(listOf("ahp-session:/s2"), detector.onSessions(listOf(idle(1), waiting(2))).map { it.resource })
        detector.onSessions(listOf(idle(1)))
        assertEquals(listOf("ahp-session:/s2"), detector.onSessions(listOf(idle(1), waiting(2))).map { it.resource })
    }

    @Test
    fun `a blank title gets the placeholder`() {
        detector.onSessions(listOf(idle(1)))

        assertEquals(SessionListModel.UNTITLED, detector.onSessions(listOf(waiting(1).copy(title = " "))).single().title)
    }

    @Test
    fun `a status that is only in progress is not waiting`() {
        detector.onSessions(listOf(idle(1)))

        assertEquals(emptyList(), detector.onSessions(listOf(idle(1).copy(status = SessionStatus.IN_PROGRESS))))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class InputNeededNotifierTest {
    private val notified = mutableListOf<InputNeeded>()
    private val resolved = mutableListOf<String>()

    private fun TestScopeRig(list: MutableStateFlow<List<SessionSummary>>, scope: kotlinx.coroutines.CoroutineScope) {
        val notifier = InputNeededNotifier(list, { notified += it }, { resolved += it }, debounceMs = 3_000)
        scope.launch { notifier.run() }
    }

    @Test
    fun `a notification fires only after the debounce`() = runTest {
        val list = MutableStateFlow(listOf(idle(1)))
        TestScopeRig(list, backgroundScope)
        runCurrent()

        list.value = listOf(waiting(1))
        runCurrent()
        advanceTimeBy(2_900)
        assertEquals(emptyList(), notified)

        advanceTimeBy(200)
        assertEquals(listOf("ahp-session:/s1"), notified.map { it.resource })
    }

    @Test
    fun `a question withdrawn inside the debounce never notifies`() = runTest {
        val list = MutableStateFlow(listOf(idle(1)))
        TestScopeRig(list, backgroundScope)
        runCurrent()

        list.value = listOf(waiting(1))
        runCurrent()
        advanceTimeBy(1_000)
        list.value = listOf(idle(1))
        runCurrent()
        advanceTimeBy(10_000)

        assertEquals(emptyList(), notified)
        assertEquals(emptyList(), resolved)
    }

    @Test
    fun `one notification per session until it leaves the state`() = runTest {
        val list = MutableStateFlow(listOf(idle(1)))
        TestScopeRig(list, backgroundScope)
        runCurrent()

        list.value = listOf(waiting(1))
        runCurrent()
        advanceTimeBy(4_000)
        list.value = listOf(waiting(1).copy(activity = "update"))
        runCurrent()
        advanceTimeBy(10_000)

        assertEquals(1, notified.size)
    }

    @Test
    fun `resolved is called when a notified session stops waiting, and only then`() = runTest {
        val list = MutableStateFlow(listOf(idle(1), idle(2)))
        TestScopeRig(list, backgroundScope)
        runCurrent()

        list.value = listOf(waiting(1), idle(2))
        runCurrent()
        advanceTimeBy(4_000)
        list.value = listOf(idle(1), idle(2))
        runCurrent()

        assertEquals(listOf("ahp-session:/s1"), resolved)
    }

    @Test
    fun `a host that already has many open questions does not storm, even when the list is replaced`() = runTest {
        val open = (1..10).map { waiting(it) }
        val list = MutableStateFlow(open)
        TestScopeRig(list, backgroundScope)
        runCurrent()

        list.value = open.map { it.copy(activity = "after reconnect") }
        runCurrent()
        advanceTimeBy(10_000)

        assertEquals(emptyList(), notified)
    }

    @Test
    fun `a question that arrived while the connection was down notifies once the list is refetched`() = runTest {
        val list = MutableStateFlow(listOf(idle(1), waiting(2)))
        TestScopeRig(list, backgroundScope)
        runCurrent()

        list.value = listOf(idle(1), waiting(2), waiting(3))
        runCurrent()
        advanceTimeBy(4_000)

        assertEquals(listOf("ahp-session:/s3"), notified.map { it.resource })
    }
}

class MonitorStatusTest {
    private val sessions = listOf(idle(1), waiting(2), idle(3).copy(status = SessionStatus.IDLE or SessionStatus.IS_ARCHIVED))

    @Test
    fun `online shows active sessions and how many wait, ignoring archived ones`() {
        val status = MonitorStatus.from("dev-box", sessions, ConnState.Online)

        assertEquals("Monitoring dev-box", status.title)
        assertEquals("2 active sessions · 1 needs input", status.text)
    }

    @Test
    fun `the words are singular and plural, and empty hosts say so`() {
        assertEquals("1 active session", MonitorStatus.from("h", listOf(idle(1)), ConnState.Online).text)
        assertEquals("2 need input", MonitorStatus.from("h", listOf(waiting(1), waiting(2)), ConnState.Online).text.substringAfter("· "))
        assertEquals("No active sessions", MonitorStatus.from("h", emptyList(), ConnState.Online).text)
    }

    @Test
    fun `connection trouble is stated without leaking a cause`() {
        assertEquals("Connecting…", MonitorStatus.from("h", sessions, ConnState.Connecting).text)
        assertTrue(MonitorStatus.from("h", sessions, ConnState.Reconnecting(2, IllegalStateException("secret-ish"))).text.startsWith("Connection lost"))
        val failed = MonitorStatus.from("h", sessions, ConnState.Failed(IllegalStateException("secret-ish"))).text
        assertEquals("Disconnected. Open the app for details.", failed)
    }
}
