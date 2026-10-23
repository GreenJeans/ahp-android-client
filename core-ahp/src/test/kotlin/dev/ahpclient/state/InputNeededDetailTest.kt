package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ListSessionsResult
import com.microsoft.agenthostprotocol.generated.SessionStatus
import com.microsoft.agenthostprotocol.generated.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private val baseSession: SessionSummary =
    Ahp.json.decodeFromString(ListSessionsResult.serializer(), fixture("live/list-sessions.json")).items.first { it.title == "Session B" }

private fun quiet(n: Int) = baseSession.copy(resource = "ahp-session:/q$n", title = "Quiet $n", status = SessionStatus.IDLE)

private fun asking(n: Int) = quiet(n).copy(title = "Asking $n", status = SessionStatus.INPUT_NEEDED or SessionStatus.IS_READ)

/** What the alert says it is waiting for, and that a slow or broken read never costs the user the alert. */
@OptIn(ExperimentalCoroutinesApi::class)
class InputNeededDetailTest {
    private val notified = mutableListOf<InputNeeded>()
    private val resolved = mutableListOf<String>()

    private fun start(
        list: MutableStateFlow<List<SessionSummary>>,
        scope: CoroutineScope,
        readDetail: suspend (String) -> String?,
    ) {
        val notifier = InputNeededNotifier(list, { notified += it }, { resolved += it }, debounceMs = 3_000, readDetail = readDetail, readTimeoutMs = 4_000)
        scope.launch { notifier.run() }
    }

    @Test
    fun `the alert carries what the session is waiting for`() = runTest {
        val list = MutableStateFlow(listOf(quiet(1)))
        start(list, backgroundScope) { "Which season do you prefer?" }
        runCurrent()

        list.value = listOf(asking(1))
        runCurrent()
        advanceTimeBy(3_100)

        assertEquals(listOf(InputNeeded("ahp-session:/q1", "Asking 1", "Which season do you prefer?")), notified)
    }

    @Test
    fun `the question is read once, and only after the debounce`() = runTest {
        var reads = 0
        val list = MutableStateFlow(listOf(quiet(1)))
        start(list, backgroundScope) {
            reads++
            "q"
        }
        runCurrent()

        list.value = listOf(asking(1))
        runCurrent()
        advanceTimeBy(2_900)
        assertEquals(0, reads, "nothing is read for a question that may yet be withdrawn")

        advanceTimeBy(200)
        list.value = listOf(asking(1).copy(activity = "still waiting"))
        runCurrent()
        advanceTimeBy(10_000)

        assertEquals(1, reads)
        assertEquals(1, notified.size)
    }

    @Test
    fun `nothing readable still alerts, with the title alone`() = runTest {
        val list = MutableStateFlow(listOf(quiet(1)))
        start(list, backgroundScope) { null }
        runCurrent()

        list.value = listOf(asking(1))
        runCurrent()
        advanceTimeBy(3_100)

        assertNull(notified.single().detail)
        assertEquals("Asking 1", notified.single().title)
    }

    @Test
    fun `a read that never finishes is cut off after the timeout and the alert goes out`() = runTest {
        val list = MutableStateFlow(listOf(quiet(1)))
        start(list, backgroundScope) { awaitCancellation() }
        runCurrent()

        list.value = listOf(asking(1))
        runCurrent()
        advanceTimeBy(3_100)
        assertEquals(emptyList(), notified, "still waiting on the read")

        advanceTimeBy(4_100)

        assertNull(notified.single().detail)
    }

    @Test
    fun `a read that fails still alerts`() = runTest {
        val list = MutableStateFlow(listOf(quiet(1)))
        start(list, backgroundScope) { error("the host dropped the connection") }
        runCurrent()

        list.value = listOf(asking(1))
        runCurrent()
        advanceTimeBy(3_100)

        assertNull(notified.single().detail)
    }

    @Test
    fun `a question withdrawn while it is being read never alerts`() = runTest {
        val list = MutableStateFlow(listOf(quiet(1)))
        start(list, backgroundScope) {
            delay(2_000)
            "too late"
        }
        runCurrent()

        list.value = listOf(asking(1))
        runCurrent()
        advanceTimeBy(4_000)
        list.value = listOf(quiet(1))
        runCurrent()
        advanceTimeBy(10_000)

        assertEquals(emptyList(), notified)
        assertEquals(emptyList(), resolved)
    }

    @Test
    fun `a long multi-line question is shown as one short line`() = runTest {
        val list = MutableStateFlow(listOf(quiet(1)))
        start(list, backgroundScope) { "First line\n\n   second   line " + "x".repeat(500) }
        runCurrent()

        list.value = listOf(asking(1))
        runCurrent()
        advanceTimeBy(3_100)

        val detail = notified.single().detail!!
        assertEquals(InputNeededNotifier.MAX_DETAIL_CHARS, detail.length)
        assertEquals(true, detail.startsWith("First line second line xxx"))
        assertEquals(true, detail.endsWith("…"))
    }

    @Test
    fun `tidy collapses whitespace, leaves short text alone and drops blanks`() {
        assertEquals("a b c", InputNeededNotifier.tidy("  a\n b\t\tc  "))
        assertEquals("Which season?", InputNeededNotifier.tidy("Which season?"))
        assertNull(InputNeededNotifier.tidy("   \n\t "))
        assertNull(InputNeededNotifier.tidy(""))
        val exact = "y".repeat(InputNeededNotifier.MAX_DETAIL_CHARS)
        assertEquals(exact, InputNeededNotifier.tidy(exact))
    }
}
