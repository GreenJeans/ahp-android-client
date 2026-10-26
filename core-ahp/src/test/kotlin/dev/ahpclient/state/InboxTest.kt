package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ListSessionsResult
import com.microsoft.agenthostprotocol.generated.SessionStatus
import com.microsoft.agenthostprotocol.generated.SessionSummary
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private val inboxFixture = Ahp.json.decodeFromString(ListSessionsResult.serializer(), fixture("live/list-sessions.json")).items

private val NOW: Instant = Instant.parse("2026-10-26T12:00:00Z")

private fun session(n: Int, status: SessionStatus, modifiedAt: String, title: String = "S$n") =
    inboxFixture.first { it.title == "Session B" }.copy(resource = "ahp-session:/i$n", title = title, status = status, modifiedAt = modifiedAt, activity = null)

private fun hoursAgo(h: Long) = NOW.minusSeconds(h * 3600).toString()

class InboxTest {
    private fun inbox(vararg sessions: SessionSummary) = Inbox.from(sessions.toList(), NOW)

    private fun Inbox.waitingTitles() = waiting.map { it.row.title }

    private fun Inbox.finishedTitles() = finished.map { it.row.title }

    @Test
    fun `a session that needs the person is waiting`() {
        val inbox = inbox(session(1, SessionStatus.INPUT_NEEDED or SessionStatus.IS_READ, hoursAgo(1)))

        assertEquals(listOf("S1"), inbox.waitingTitles())
        assertEquals(emptyList(), inbox.finished)
        assertEquals(1, inbox.total)
    }

    @Test
    fun `an archived session that needs the person is still waiting`() {
        val inbox = inbox(session(1, SessionStatus.INPUT_NEEDED or SessionStatus.IS_ARCHIVED, hoursAgo(1)))

        assertEquals(listOf("S1"), inbox.waitingTitles())
    }

    @Test
    fun `a session that is working is not in the inbox`() {
        assertTrue(inbox(session(1, SessionStatus.IN_PROGRESS, hoursAgo(0))).isEmpty)
    }

    @Test
    fun `an idle session that has not been read and is recent is finished`() {
        val inbox = inbox(session(1, SessionStatus.IDLE, hoursAgo(5)))

        assertEquals(listOf("S1"), inbox.finishedTitles())
        assertEquals(emptyList(), inbox.waiting)
    }

    @Test
    fun `a session that has been read is not in the inbox`() {
        assertTrue(inbox(session(1, SessionStatus.IDLE or SessionStatus.IS_READ, hoursAgo(5))).isEmpty)
    }

    @Test
    fun `an archived session that finished is not in the inbox`() {
        assertTrue(inbox(session(1, SessionStatus.IDLE or SessionStatus.IS_ARCHIVED, hoursAgo(5))).isEmpty)
    }

    @Test
    fun `a session that finished longer ago than the window is not news`() {
        val edge = NOW.minusSeconds(Inbox.RECENT_DAYS * 24 * 3600).toString()
        val past = NOW.minusSeconds(Inbox.RECENT_DAYS * 24 * 3600 + 1).toString()

        assertEquals(listOf("S1"), inbox(session(1, SessionStatus.IDLE, edge)).finishedTitles())
        assertTrue(inbox(session(2, SessionStatus.IDLE, past)).isEmpty)
    }

    @Test
    fun `a session with a timestamp that cannot be read is not shown as finished, but a waiting one still is`() {
        val inbox = inbox(session(1, SessionStatus.IDLE, "yesterday"), session(2, SessionStatus.INPUT_NEEDED, "yesterday"))

        assertEquals(emptyList(), inbox.finished)
        assertEquals(listOf("S2"), inbox.waitingTitles())
    }

    @Test
    fun `a failed session is in the inbox, and comes before one that finished well`() {
        val inbox = inbox(
            session(1, SessionStatus.IDLE, hoursAgo(1)),
            session(2, SessionStatus.ERROR, hoursAgo(10)),
            session(3, SessionStatus.IDLE, hoursAgo(2)),
        )

        assertEquals(listOf("S2", "S1", "S3"), inbox.finishedTitles())
        assertEquals(ActivityState.ERROR, inbox.finished.first().row.state)
    }

    @Test
    fun `waiting sessions are ordered like the side menu, most recent first`() {
        val inbox = inbox(
            session(1, SessionStatus.INPUT_NEEDED, hoursAgo(9)),
            session(2, SessionStatus.INPUT_NEEDED, hoursAgo(1)),
            session(3, SessionStatus.INPUT_NEEDED, hoursAgo(4)),
        )

        assertEquals(listOf("S2", "S3", "S1"), inbox.waitingTitles())
    }

    @Test
    fun `an item carries the row the side menu shows, and a stamp that changes when the session does`() {
        val first = inbox(session(1, SessionStatus.INPUT_NEEDED, hoursAgo(3))).waiting.single()
        val later = inbox(session(1, SessionStatus.INPUT_NEEDED, hoursAgo(1))).waiting.single()

        assertEquals("ahp-session:/i1", first.row.resource)
        assertEquals(ActivityState.NEEDS_INPUT, first.row.state)
        assertEquals("3h", first.row.whenText)
        assertTrue(first.stamp != later.stamp)
    }

    @Test
    fun `an empty host has an empty inbox`() {
        assertTrue(Inbox.from(emptyList(), NOW).isEmpty)
        assertEquals(0, Inbox.from(emptyList(), NOW).total)
    }

    @Test
    fun `a mixed list is split by what each session needs`() {
        val inbox = inbox(
            session(1, SessionStatus.IN_PROGRESS, hoursAgo(0)),
            session(2, SessionStatus.INPUT_NEEDED, hoursAgo(2)),
            session(3, SessionStatus.IDLE or SessionStatus.IS_READ, hoursAgo(1)),
            session(4, SessionStatus.IDLE, hoursAgo(3)),
            session(5, SessionStatus.IDLE, hoursAgo(24 * 10)),
        )

        assertEquals(listOf("S2"), inbox.waitingTitles())
        assertEquals(listOf("S4"), inbox.finishedTitles())
        assertEquals(2, inbox.total)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class InboxReaderTest {
    private fun item(n: Int, stamp: String = "t$n") = InboxItem(SessionListModel.row(session(n, SessionStatus.INPUT_NEEDED, stamp), NOW), stamp)

    private fun words(text: String) = RequestRead(text)

    @Test
    fun `each waiting session is read once, and what was read is shown`() = runTest {
        val asked = mutableListOf<String>()
        val reader = InboxReader({ asked += it; words("about $it") })

        reader.refresh(listOf(item(1), item(2)))

        assertEquals(listOf("ahp-session:/i1", "ahp-session:/i2"), asked)
        assertEquals(mapOf("ahp-session:/i1" to words("about ahp-session:/i1"), "ahp-session:/i2" to words("about ahp-session:/i2")), reader.details.value)
    }

    @Test
    fun `a session that has not changed is not read again`() = runTest {
        var asked = 0
        val reader = InboxReader({ asked++; words("x") })

        reader.refresh(listOf(item(1)))
        reader.refresh(listOf(item(1)))

        assertEquals(1, asked)
    }

    @Test
    fun `a session that changed is read again, and the new words replace the old`() = runTest {
        var round = 0
        val reader = InboxReader({ words("round ${++round}") })

        reader.refresh(listOf(item(1, "a")))
        reader.refresh(listOf(item(1, "b")))

        assertEquals(words("round 2"), reader.details.value["ahp-session:/i1"])
    }

    @Test
    fun `a session that left the inbox is forgotten`() = runTest {
        val reader = InboxReader({ words("x") })
        reader.refresh(listOf(item(1), item(2)))

        reader.refresh(listOf(item(2)))

        assertEquals(setOf("ahp-session:/i2"), reader.details.value.keys)
    }

    @Test
    fun `only the first few are read`() = runTest {
        val asked = mutableListOf<String>()
        val reader = InboxReader({ asked += it; words("x") }, max = 3)

        reader.refresh((1..8).map { item(it) })

        assertEquals(listOf("ahp-session:/i1", "ahp-session:/i2", "ahp-session:/i3"), asked)
        assertEquals(3, reader.details.value.size)
    }

    @Test
    fun `a session with nothing to show is kept with a null, so it is not asked again`() = runTest {
        var asked = 0
        val reader = InboxReader({ asked++; null })

        reader.refresh(listOf(item(1)))
        reader.refresh(listOf(item(1)))

        assertEquals(1, asked)
        assertTrue(reader.details.value.containsKey("ahp-session:/i1"))
        assertNull(reader.details.value.getValue("ahp-session:/i1"))
    }

    @Test
    fun `a read that fails leaves that row without words and does not stop the others`() = runTest {
        val reader = InboxReader({ if (it.endsWith("i1")) throw IllegalStateException("gone") else words("ok") })

        reader.refresh(listOf(item(1), item(2)))

        assertNull(reader.details.value.getValue("ahp-session:/i1"))
        assertEquals(words("ok"), reader.details.value.getValue("ahp-session:/i2"))
    }

    @Test
    fun `a read that is too slow is given up on, and the next is read`() = runTest {
        val reader = InboxReader({ if (it.endsWith("i1")) { delay(60_000); words("late") } else words("ok") }, timeoutMs = 5_000)

        val job = async { reader.refresh(listOf(item(1), item(2))) }
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()
        job.await()

        assertNull(reader.details.value.getValue("ahp-session:/i1"))
        assertEquals(words("ok"), reader.details.value.getValue("ahp-session:/i2"))
    }

    @Test
    fun `leaving the screen cancels the reading`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var cancelled = false
        val reader = InboxReader({ try { gate.await(); words("x") } catch (e: CancellationException) { cancelled = true; throw e } })

        val job = async { reader.refresh(listOf(item(1))) }
        runCurrent()
        job.cancel()
        runCurrent()

        assertTrue(cancelled)
        assertEquals(emptyMap(), reader.details.value)
    }

    @Test
    fun `an empty inbox clears everything read`() = runTest {
        val reader = InboxReader({ words("x") })
        reader.refresh(listOf(item(1)))

        reader.refresh(emptyList())

        assertEquals(emptyMap(), reader.details.value)
    }
}
