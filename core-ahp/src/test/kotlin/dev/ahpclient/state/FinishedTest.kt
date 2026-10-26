package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ListSessionsResult
import com.microsoft.agenthostprotocol.generated.SessionStatus
import com.microsoft.agenthostprotocol.generated.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private val listed = Ahp.json.decodeFromString(ListSessionsResult.serializer(), fixture("live/list-sessions.json")).items

private fun at(n: Int, status: SessionStatus, title: String = "Session $n") =
    listed.first { it.title == "Session B" }.copy(resource = "ahp-session:/s$n", title = title, status = status)

private fun quiet(n: Int) = at(n, SessionStatus.IDLE)

private fun busy(n: Int) = at(n, SessionStatus.IN_PROGRESS)

private fun asking(n: Int) = at(n, SessionStatus.INPUT_NEEDED)

private fun broken(n: Int) = at(n, SessionStatus.ERROR)

class FinishedDetectorTest {
    private var now = 1_000_000L
    private val detector = FinishedDetector { now }

    @Test
    fun `the first list only records who is working, and one of them reported later has no length`() {
        assertEquals(emptyList(), detector.onSessions(listOf(busy(1), quiet(2))))

        val finished = detector.onSessions(listOf(quiet(1), quiet(2))).single()

        assertEquals(FinishedSession("ahp-session:/s1", "Session 1", failed = false, tookMs = null), finished)
    }

    @Test
    fun `a session that starts after the first list is reported once when it stops, with how long it worked`() {
        detector.onSessions(listOf(quiet(1)))
        detector.onSessions(listOf(busy(1)))
        now += 125_000

        val finished = detector.onSessions(listOf(quiet(1))).single()

        assertEquals(125_000L, finished.tookMs)
        assertEquals(emptyList(), detector.onSessions(listOf(quiet(1))), "still stopped: not reported again")
    }

    @Test
    fun `waiting on the user is part of the run, so a session that asks and is then answered is reported once at the end`() {
        detector.onSessions(listOf(quiet(1)))
        detector.onSessions(listOf(busy(1)))
        now += 10_000
        assertEquals(emptyList(), detector.onSessions(listOf(asking(1))))
        now += 20_000
        assertEquals(emptyList(), detector.onSessions(listOf(busy(1))))
        now += 30_000

        assertEquals(60_000L, detector.onSessions(listOf(quiet(1))).single().tookMs)
    }

    @Test
    fun `a session that stops with an error is reported as failed`() {
        detector.onSessions(listOf(quiet(1)))
        detector.onSessions(listOf(busy(1)))

        assertEquals(true, detector.onSessions(listOf(broken(1))).single().failed)
    }

    @Test
    fun `a session that was already in error, or idle, is not a stop`() {
        detector.onSessions(listOf(broken(1), quiet(2)))

        assertEquals(emptyList(), detector.onSessions(listOf(broken(1), quiet(2))))
    }

    @Test
    fun `a session that works again is followed from its first start, and reported again when it stops again`() {
        detector.onSessions(listOf(quiet(1)))
        detector.onSessions(listOf(busy(1)))
        now += 5_000
        assertEquals(5_000L, detector.onSessions(listOf(quiet(1))).single().tookMs)
        now += 1_000
        detector.onSessions(listOf(busy(1)))
        now += 14_000

        assertEquals(20_000L, detector.onSessions(listOf(quiet(1))).single().tookMs)
    }

    @Test
    fun `once settled, a run is over and the next one starts afresh`() {
        detector.onSessions(listOf(quiet(1)))
        detector.onSessions(listOf(busy(1)))
        now += 5_000
        detector.onSessions(listOf(quiet(1)))
        detector.settled("ahp-session:/s1")
        now += 100_000

        assertEquals(emptyList(), detector.onSessions(listOf(quiet(1))))
        detector.onSessions(listOf(busy(1)))
        now += 7_000
        assertEquals(7_000L, detector.onSessions(listOf(quiet(1))).single().tookMs)
    }

    @Test
    fun `an archived session is not followed, and a session that leaves the list is forgotten`() {
        detector.onSessions(listOf(quiet(1), quiet(2)))
        detector.onSessions(listOf(busy(1), busy(2)))

        assertEquals(emptyList(), detector.onSessions(listOf(at(1, SessionStatus.IDLE or SessionStatus.IS_ARCHIVED))))
        assertEquals(emptyList(), detector.onSessions(listOf(quiet(1), quiet(2))), "gone from the list, so its run was dropped")
    }

    @Test
    fun `a blank title gets the placeholder`() {
        detector.onSessions(listOf(quiet(1)))
        detector.onSessions(listOf(busy(1)))

        assertEquals(SessionListModel.UNTITLED, detector.onSessions(listOf(at(1, SessionStatus.IDLE, title = " "))).single().title)
    }
}

class FinishedWatchTest {
    @Test
    fun `nothing is covered by default, and everything when all is on`() {
        assertEquals(false, FinishedWatch().covers("ahp-session:/s1"))
        assertEquals(true, FinishedWatch(all = true).covers("ahp-session:/s1"))
    }

    @Test
    fun `only the chosen sessions are covered when all is off`() {
        val watch = FinishedWatch(sessions = setOf("ahp-session:/s1"))

        assertEquals(true, watch.covers("ahp-session:/s1"))
        assertEquals(false, watch.covers("ahp-session:/s2"))
    }
}

class FinishedTextTest {
    @Test
    fun `the words say the result and how long it took when that is known`() {
        assertEquals("Done · 8m 16s", FinishedText.detail(FinishedSession("r", "t", failed = false, tookMs = 496_000)))
        assertEquals("Failed · 4s", FinishedText.detail(FinishedSession("r", "t", failed = true, tookMs = 4_000)))
        assertEquals("Done", FinishedText.detail(FinishedSession("r", "t", failed = false, tookMs = null)))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FinishedNotifierTest {
    private val posted = mutableListOf<FinishedSession>()
    private val cleared = mutableListOf<String>()
    private val list = MutableStateFlow(listOf(quiet(1), quiet(2)))
    private val watch = MutableStateFlow(FinishedWatch(all = true))
    private var viewing: String? = null

    private fun TestScope.start(initial: List<SessionSummary> = list.value, scope: CoroutineScope = backgroundScope) {
        list.value = initial
        val notifier = FinishedNotifier(list, watch, { posted += it }, { cleared += it }, { viewing }, debounceMs = 5_000, clock = { testScheduler.currentTime })
        scope.launch { notifier.run() }
        runCurrent()
    }

    private fun TestScope.change(vararg sessions: SessionSummary) {
        list.value = sessions.toList()
        runCurrent()
    }

    @Test
    fun `an alert goes out only after the session has stayed stopped for the debounce`() = runTest {
        start()
        change(busy(1), quiet(2))

        change(quiet(1), quiet(2))
        advanceTimeBy(4_900)
        assertEquals(emptyList(), posted)

        advanceTimeBy(200)
        assertEquals(listOf("ahp-session:/s1"), posted.map { it.resource })
    }

    @Test
    fun `a pause between two steps never alerts, and the alert after the real end says how long the whole run was`() = runTest {
        start()
        change(busy(1), quiet(2))
        advanceTimeBy(60_000)

        change(quiet(1), quiet(2))
        advanceTimeBy(2_000)
        change(busy(1), quiet(2))
        advanceTimeBy(30_000)
        assertEquals(emptyList(), posted)

        change(quiet(1), quiet(2))
        advanceTimeBy(6_000)

        assertEquals(1, posted.size)
        assertEquals(92_000L, posted.single().tookMs)
    }

    @Test
    fun `a session already working when the list first arrives alerts when it stops`() = runTest {
        start(listOf(busy(1), quiet(2)))
        advanceTimeBy(10_000)
        assertEquals(emptyList(), posted, "working at the start is the baseline")

        change(quiet(1), quiet(2))
        advanceTimeBy(6_000)

        assertEquals(FinishedSession("ahp-session:/s1", "Session 1", failed = false, tookMs = null), posted.single())
    }

    @Test
    fun `with nothing chosen there is no alert`() = runTest {
        watch.value = FinishedWatch()
        start()
        change(busy(1), quiet(2))
        change(quiet(1), quiet(2))
        advanceTimeBy(10_000)

        assertEquals(emptyList(), posted)
    }

    @Test
    fun `only the chosen sessions alert when all is off`() = runTest {
        watch.value = FinishedWatch(sessions = setOf("ahp-session:/s2"))
        start()
        change(busy(1), busy(2))
        change(quiet(1), quiet(2))
        advanceTimeBy(10_000)

        assertEquals(listOf("ahp-session:/s2"), posted.map { it.resource })
    }

    @Test
    fun `the choice is read when the time is up, not when the session stopped`() = runTest {
        watch.value = FinishedWatch()
        start()
        change(busy(1), quiet(2))
        change(quiet(1), quiet(2))
        advanceTimeBy(2_000)

        watch.value = FinishedWatch(sessions = setOf("ahp-session:/s1"))
        runCurrent()
        advanceTimeBy(4_000)
        assertEquals(listOf("ahp-session:/s1"), posted.map { it.resource })

        change(busy(1), quiet(2))
        change(quiet(1), quiet(2))
        advanceTimeBy(2_000)
        watch.value = FinishedWatch()
        runCurrent()
        advanceTimeBy(4_000)
        assertEquals(1, posted.size, "switched off before the alert was due")
    }

    @Test
    fun `the session on screen gets no alert, and the others do`() = runTest {
        viewing = "ahp-session:/s1"
        start()
        change(busy(1), busy(2))
        change(quiet(1), quiet(2))
        advanceTimeBy(10_000)

        assertEquals(listOf("ahp-session:/s2"), posted.map { it.resource })
    }

    @Test
    fun `a session that fails is reported as failed`() = runTest {
        start()
        change(busy(1), quiet(2))
        change(broken(1), quiet(2))
        advanceTimeBy(6_000)

        assertEquals(true, posted.single().failed)
    }

    @Test
    fun `an alert that was shown is taken away when the session starts working again`() = runTest {
        start()
        change(busy(1), quiet(2))
        change(quiet(1), quiet(2))
        advanceTimeBy(6_000)
        assertEquals(emptyList(), cleared)

        change(busy(1), quiet(2))

        assertEquals(listOf("ahp-session:/s1"), cleared)
    }

    @Test
    fun `nothing is taken away for a session that was never alerted`() = runTest {
        start()
        change(busy(1), quiet(2))
        change(quiet(1), quiet(2))
        advanceTimeBy(1_000)
        change(busy(1), quiet(2))

        assertEquals(emptyList(), cleared)
    }

    @Test
    fun `an archived session does not alert`() = runTest {
        start()
        change(busy(1), quiet(2))
        change(at(1, SessionStatus.IDLE or SessionStatus.IS_ARCHIVED), quiet(2))
        advanceTimeBy(10_000)

        assertEquals(emptyList(), posted)
    }

    @Test
    fun `a list that is sent again with nothing new does not alert twice`() = runTest {
        start()
        change(busy(1), quiet(2))
        change(quiet(1), quiet(2))
        advanceTimeBy(6_000)
        change(quiet(1).copy(activity = "again"), quiet(2))
        advanceTimeBy(10_000)

        assertEquals(1, posted.size)
    }

    @Test
    fun `a stop is alerted again the next time the session works and stops, each with its own length`() = runTest {
        start()
        change(busy(1), quiet(2))
        advanceTimeBy(10_000)
        change(quiet(1), quiet(2))
        advanceTimeBy(6_000)
        change(busy(1), quiet(2))
        advanceTimeBy(20_000)
        change(quiet(1), quiet(2))
        advanceTimeBy(6_000)

        assertEquals(listOf(10_000L, 20_000L), posted.map { it.tookMs })
    }
}
