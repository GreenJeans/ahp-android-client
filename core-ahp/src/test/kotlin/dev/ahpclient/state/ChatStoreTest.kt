package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ChatState
import com.microsoft.agenthostprotocol.generated.Snapshot
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ChatStoreTest {
    private fun TestScope.store(fetch: suspend (String) -> Unit = {}) = ChatStore(CHAT_A, backgroundScope, fetch)

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun env(action: String, seq: Long) = chatEnvelope(CHAT_A, action, seq)

    /** Every non-null state the store publishes, with the virtual time it was published at. */
    private fun TestScope.record(store: ChatStore): List<Pair<Long, ChatState>> {
        val seen = mutableListOf<Pair<Long, ChatState>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.state.collect { chat -> if (chat != null) seen += testScheduler.currentTime to chat }
        }
        return seen
    }

    @Test
    fun `actions that arrive before the snapshot are held, then applied in server order`() = runTest {
        val store = store()

        store.apply(env(deltaJson("b"), 14))
        store.apply(env(MARKDOWN_PART, 12))
        store.apply(env(TURN_STARTED, 11))
        store.apply(env(deltaJson("a"), 13))
        assertNull(store.state.value)

        assertTrue(store.seed(chatSnapshot(CHAT_A, 10)))

        assertEquals("ab", store.state.value!!.markdownText())
    }

    @Test
    fun `a snapshot that holds no chat state seeds nothing`() = runTest {
        val store = store()

        assertFalse(store.seed(Ahp.json.decodeFromString(Snapshot.serializer(), sessionSnapshot(SESSION_C, 1))))

        assertNull(store.state.value)
        assertTrue(store.needsSubscribe)
    }

    @Test
    fun `an action at or below the last applied sequence is a duplicate and is dropped`() = runTest {
        val store = store()
        store.seed(chatSnapshot(CHAT_A, 10))

        store.apply(env(TURN_STARTED, 11))
        store.apply(env(MARKDOWN_PART, 12))
        store.apply(env(deltaJson("a"), 13))
        store.apply(env(deltaJson("a"), 13))
        store.apply(env(deltaJson("a"), 5))
        advance(100)

        assertEquals("a", store.state.value!!.markdownText())
    }

    @Test
    fun `the first change shows at once, later ones inside the interval are coalesced, and the last one is never lost`() = runTest {
        val store = store()
        val seen = record(store)
        store.seed(chatSnapshot(CHAT_A, 10))
        advance(1_000)

        store.apply(env(TURN_STARTED, 11))
        advance(10)
        store.apply(env(MARKDOWN_PART, 12))
        advance(10)
        store.apply(env(deltaJson("a"), 13))
        advance(30)
        advance(1_000)

        assertEquals(listOf(0L, 1_000L, 1_050L), seen.map { it.first })
        assertEquals("a", seen.last().second.markdownText())
    }

    @Test
    fun `1000 deltas give the right final text at no more than 20 updates a second`() = runTest {
        val store = store()
        val seen = record(store)
        store.seed(chatSnapshot(CHAT_A, 0))
        store.apply(env(TURN_STARTED, 1))
        store.apply(env(MARKDOWN_PART, 2))
        advance(100)
        val start = testScheduler.currentTime

        repeat(1_000) { i ->
            store.apply(env(deltaJson("x"), 3L + i))
            advance(1)
        }
        advance(100)

        val times = seen.map { it.first }.filter { it >= start }
        assertEquals("x".repeat(1_000), store.state.value!!.markdownText())
        assertEquals("x".repeat(1_000), seen.last().second.markdownText())
        assertTrue(times.zipWithNext().all { (a, b) -> b - a >= 50 }, "updates closer than 50 ms: $times")
        assertTrue(times.count { it - start < 1_000 } <= 20, "updates in the first second: ${times.count { it - start < 1_000 }}")
        assertTrue(times.size < 30, "updates: ${times.size}")
    }

    @Test
    fun `a new snapshot shows at once even inside the interval and resets the sequence`() = runTest {
        val store = store()
        val seen = record(store)
        store.seed(chatSnapshot(CHAT_A, 10))
        store.apply(env(TURN_STARTED, 11))
        store.apply(env(MARKDOWN_PART, 12))

        store.seed(chatSnapshot(CHAT_A, 100, turns = listOf(turnJson("t9"))))
        store.apply(env(deltaJson("late"), 50))
        store.apply(env(TURN_STARTED.replace("\"t1\"", "\"t2\""), 101))
        advance(100)

        assertEquals(listOf(0L, 0L), seen.map { it.first }.take(2))
        assertEquals(listOf("t9"), seen[1].second.turns.map { it.id })
        assertEquals("t2", store.state.value!!.activeTurn!!.id)
        assertEquals(listOf("t9"), store.state.value!!.turns.map { it.id })
    }

    @Test
    fun `invalidate asks for a fresh snapshot, and a gone chat reports it`() = runTest {
        val store = store()
        assertTrue(store.needsSubscribe)

        store.seed(chatSnapshot(CHAT_A, 10))
        assertFalse(store.needsSubscribe)

        store.invalidate()
        assertTrue(store.needsSubscribe)
        assertNotNull(store.state.value)

        store.markGone()
        assertFalse(store.needsSubscribe)
        assertEquals(CHAT_A, assertIs<ChatGoneException>(store.error.value).uri)
    }

    @Test
    fun `an action that arrives after the chat was invalidated is held until the new snapshot and applied on top of it`() = runTest {
        val store = store()
        store.seed(chatSnapshot(CHAT_A, 10))
        store.invalidate()

        store.apply(env(TURN_STARTED, 12))
        advance(100)
        assertNull(store.state.value!!.activeTurn, "the old transcript is left alone while the new one is awaited")

        store.seed(chatSnapshot(CHAT_A, 11))

        assertEquals("t1", store.state.value!!.activeTurn!!.id, "the action is later than the snapshot, so it is not lost")
    }

    @Test
    fun `an action the new snapshot already covers is dropped when it is applied from the held ones`() = runTest {
        val store = store()
        store.seed(chatSnapshot(CHAT_A, 10))
        store.invalidate()

        store.apply(env(TURN_STARTED, 11))
        store.seed(chatSnapshot(CHAT_A, 11))

        assertNull(store.state.value!!.activeTurn)
    }

    @Test
    fun `the flag that says a snapshot is awaited stays up until the snapshot is published`() = runTest {
        val store = store()
        store.seed(chatSnapshot(CHAT_A, 10, turns = listOf(turnJson("old"))))
        store.invalidate()
        var shown: List<String>? = null
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.subscribeNeeded.first { !it }
            shown = store.state.value?.turns?.map { it.id }
        }

        store.apply(env(TURN_STARTED, 12))
        store.seed(chatSnapshot(CHAT_A, 11, turns = listOf(turnJson("fresh"))))

        assertEquals(listOf("fresh"), shown, "a waiter that wakes on the flag already finds the new transcript")
    }

    @Test
    fun `loadOlder does nothing when there is no older page`() = runTest {
        val fetched = mutableListOf<String>()
        val store = store { fetched += it }
        store.seed(chatSnapshot(CHAT_A, 10, turns = listOf(turnJson("t3"))))

        store.loadOlder()
        runCurrent()

        assertTrue(fetched.isEmpty())
        assertFalse(store.loadingOlder.value)
    }

    @Test
    fun `loadOlder asks once, waits for the turns action, then merges them in front`() = runTest {
        val fetched = mutableListOf<String>()
        val store = store { fetched += it }
        store.seed(chatSnapshot(CHAT_A, 10, turns = listOf(turnJson("t3")), cursor = "c2"))

        store.loadOlder()
        runCurrent()
        store.loadOlder()
        runCurrent()
        assertEquals(listOf("c2"), fetched)
        assertTrue(store.loadingOlder.value)

        store.apply(env(turnsLoadedJson(listOf(turnJson("t1"), turnJson("t2")), "c1"), 11))
        advance(100)

        assertFalse(store.loadingOlder.value)
        assertNull(store.olderError.value)
        assertEquals(listOf("t1", "t2", "t3"), store.state.value!!.turns.map { it.id })
        assertEquals("c1", store.state.value!!.turnsNextCursor)

        store.loadOlder()
        runCurrent()
        assertEquals(listOf("c2", "c1"), fetched)
    }

    @Test
    fun `a turns action that beats the reply to the request still ends the wait`() = runTest {
        lateinit var store: ChatStore
        store = store { store.apply(env(turnsLoadedJson(listOf(turnJson("t2")), null), 11)) }
        store.seed(chatSnapshot(CHAT_A, 10, turns = listOf(turnJson("t3")), cursor = "c2"))

        store.loadOlder()
        advance(100)

        assertFalse(store.loadingOlder.value)
        assertNull(store.olderError.value)
        assertEquals(listOf("t2", "t3"), store.state.value!!.turns.map { it.id })
        assertNull(store.state.value!!.turnsNextCursor)
    }

    @Test
    fun `a failed request reports why, stops loading and can be tried again`() = runTest {
        var attempts = 0
        val store = store { attempts++; throw IOException("offline") }
        store.seed(chatSnapshot(CHAT_A, 10, turns = listOf(turnJson("t3")), cursor = "c2"))

        store.loadOlder()
        runCurrent()

        assertIs<IOException>(store.olderError.value)
        assertFalse(store.loadingOlder.value)
        store.loadOlder()
        runCurrent()
        assertEquals(2, attempts)
    }

    @Test
    fun `a request the host never answers with turns times out instead of loading forever`() = runTest {
        val store = store {}
        store.seed(chatSnapshot(CHAT_A, 10, turns = listOf(turnJson("t3")), cursor = "c2"))

        store.loadOlder()
        runCurrent()
        assertTrue(store.loadingOlder.value)
        advance(ChatStore.OLDER_TIMEOUT_MS + 1)

        assertFalse(store.loadingOlder.value)
        assertIs<TimeoutCancellationException>(store.olderError.value)
    }

    @Test
    fun `an empty turnsLoaded batch clears the cursor so paging stops`() = runTest {
        val fetched = mutableListOf<String>()
        val store = store { fetched += it }
        store.seed(chatSnapshot(CHAT_A, 10, turns = listOf(turnJson("t3")), cursor = "c2"))

        store.loadOlder()
        runCurrent()
        store.apply(env(turnsLoadedJson(emptyList(), "c2"), 11))
        advance(100)

        assertFalse(store.loadingOlder.value)
        assertNull(store.state.value!!.turnsNextCursor)
        store.loadOlder()
        runCurrent()
        assertEquals(listOf("c2"), fetched)
    }
}
