package dev.ahpclient.state

import dev.ahpclient.ahp.result
import com.microsoft.agenthostprotocol.generated.Implementation
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
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
import kotlinx.serialization.json.longOrNull

/** How many latest turns the app asks the host for when it subscribes to a chat. */
private const val CHAT_TURNS_ASKED = 10L

/** Chats through [HostSession]: opening, subscribing, routing, closing and surviving a reconnect, like sessions do. */
@OptIn(ExperimentalCoroutinesApi::class)
class HostSessionChatTest {
    private val info = Implementation(name = "ahp-android-client-test", version = "0.0.1")

    private class Rig(val connector: ScriptedConnector, val host: HostSession) {
        val socket get() = connector.sockets.last()

        /** The `view.turns` of the `subscribe` for [channel] on the latest connection; null when none was asked for. */
        fun askedTurns(channel: String): Long? = socket.sent
            .filter { it["method"]?.jsonPrimitive?.content == "subscribe" && it["params"]!!.jsonObject["channel"]?.jsonPrimitive?.content == channel }
            .map { it["params"]!!.jsonObject["view"]?.jsonObject?.get("turns")?.jsonPrimitive?.longOrNull }
            .lastOrNull()
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

    private suspend fun Rig.dropAndReconnect() {
        socket.serverCloses(1011)
        host.awaitState { it is ConnState.Reconnecting }
        host.awaitState { it == ConnState.Online }
    }

    @Test
    fun `an open chat is seeded from its snapshot and follows later actions`() = runTest {
        val rig = rig(HostScript()).start()

        val store = rig.host.openChat(CHAT_A)
        runCurrent()
        assertEquals("Chat", store.state.value!!.title)
        assertEquals(listOf("initialize", "listSessions", "subscribe"), rig.socket.sentMethods)

        rig.socket.serverSends(actionFrame(CHAT_A, TURN_STARTED, SNAPSHOT_SEQ + 1))
        rig.socket.serverSends(actionFrame(CHAT_A, MARKDOWN_PART, SNAPSHOT_SEQ + 2))
        rig.socket.serverSends(actionFrame(CHAT_A, deltaJson("hi"), SNAPSHOT_SEQ + 3))
        runCurrent()
        settle()
        assertEquals("hi", store.state.value!!.markdownText())

        rig.socket.serverSends(actionFrame(CHAT_A, deltaJson("stale"), SNAPSHOT_SEQ))
        runCurrent()
        settle()
        assertEquals("hi", store.state.value!!.markdownText(), "an action at or below the last applied sequence is a duplicate")
    }

    @Test
    fun `chat and session actions each reach only their own store`() = runTest {
        val rig = rig(HostScript()).start()
        val session = rig.host.openSession(SESSION_C)
        val chat = rig.host.openChat(CHAT_A)
        runCurrent()

        rig.socket.serverSends(actionFrame(SESSION_C, titleChanged("Renamed"), SNAPSHOT_SEQ + 1))
        rig.socket.serverSends(actionFrame(CHAT_A, TURN_STARTED, SNAPSHOT_SEQ + 2))
        runCurrent()
        settle()

        assertEquals("Renamed", session.state.value!!.title)
        assertEquals("Chat", chat.state.value!!.title)
        assertEquals("t1", chat.state.value!!.activeTurn!!.id)
    }

    @Test
    fun `a chat opened before connecting is not named in initialize, and is subscribed once online for its latest turns only`() = runTest {
        val rig = rig(HostScript())
        val store = rig.host.openChat(CHAT_A)

        rig.start()
        runCurrent()

        val initialize = rig.socket.sent.first { it["method"]?.jsonPrimitive?.content == "initialize" }
        assertEquals(listOf("ahp-root://"), initialize["params"]!!.jsonObject["initialSubscriptions"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Chat", store.state.value!!.title)
        assertEquals(listOf("initialize", "listSessions", "subscribe"), rig.socket.sentMethods)
        assertEquals(CHAT_TURNS_ASKED, rig.askedTurns(CHAT_A))
    }

    @Test
    fun `an open chat is asked for only its latest turns, not its whole history`() = runTest {
        val rig = rig(HostScript()).start()

        rig.host.openChat(CHAT_A)
        runCurrent()

        assertEquals(CHAT_TURNS_ASKED, rig.askedTurns(CHAT_A))
    }

    @Test
    fun `a chat the host does not know is marked gone`() = runTest {
        val rig = rig(HostScript(missingSessions = setOf(CHAT_A))).start()

        val store = rig.host.openChat(CHAT_A)
        runCurrent()

        assertEquals(CHAT_A, assertIs<ChatGoneException>(store.error.value).uri)
        assertNull(store.state.value)
    }

    @Test
    fun `closing a chat drops the subscription only when the last opener leaves`() = runTest {
        val rig = rig(HostScript()).start()
        val first = rig.host.openChat(CHAT_A)
        assertSame(first, rig.host.openChat(CHAT_A))
        runCurrent()

        rig.host.closeChat(CHAT_A)
        runCurrent()
        assertFalse("unsubscribe" in rig.socket.sentMethods)

        rig.host.closeChat(CHAT_A)
        runCurrent()
        val unsubscribe = rig.socket.sent.first { it["method"]?.jsonPrimitive?.content == "unsubscribe" }
        assertEquals(CHAT_A, unsubscribe["params"]!!.jsonObject["channel"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a reconnect does not name the chat, which is subscribed afresh for its latest turns`() = runTest {
        val rig = rig(HostScript(chatTurns = listOf(turnJson("old"))), HostScript(chatTurns = listOf(turnJson("fresh")))).start()
        val store = rig.host.openChat(CHAT_A)
        runCurrent()
        assertEquals(listOf("old"), store.state.value!!.turns.map { it.id })

        rig.dropAndReconnect()
        runCurrent()

        val params = rig.socket.sent.first()["params"]!!.jsonObject
        assertEquals(listOf("ahp-root://"), params["subscriptions"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("fresh"), store.state.value!!.turns.map { it.id })
        assertEquals(listOf("reconnect", "listSessions", "subscribe"), rig.socket.sentMethods)
        assertEquals(CHAT_TURNS_ASKED, rig.askedTurns(CHAT_A))
    }

    @Test
    fun `what a chat showed stays on screen while its new snapshot is on its way, and the connection is online meanwhile`() = runTest {
        val rig = rig(HostScript(chatTurns = listOf(turnJson("old"))), HostScript(onChatSubscribe = { _, _ -> null })).start()
        val store = rig.host.openChat(CHAT_A)
        runCurrent()

        rig.dropAndReconnect()
        runCurrent()

        assertEquals(ConnState.Online, rig.host.connection.value, "a chat that is slow to arrive does not keep the connection from being online")
        assertEquals(listOf("old"), store.state.value!!.turns.map { it.id })
        assertNull(store.error.value)
    }

    @Test
    fun `a chat the host no longer has after a reconnect is marked gone`() = runTest {
        val rig = rig(HostScript(), HostScript(missingSessions = setOf(CHAT_A))).start()
        val store = rig.host.openChat(CHAT_A)
        runCurrent()

        rig.dropAndReconnect()
        runCurrent()

        assertIs<ChatGoneException>(store.error.value)
        assertEquals(listOf("reconnect", "listSessions", "subscribe"), rig.socket.sentMethods)
    }

    @Test
    fun `a chat that is slow to arrive does not keep the connection from being online`() = runTest {
        val rig = rig(HostScript(onChatSubscribe = { _, _ -> null })).start()

        val store = rig.host.openChat(CHAT_A)
        runCurrent()

        assertEquals(ConnState.Online, rig.host.connection.value)
        assertNull(store.state.value)
        assertNull(store.error.value)
    }

    @Test
    fun `a chat the host never sends is given up on, the connection is kept, and nothing asks again until retry`() = runTest {
        var calls = 0
        val rig = rig(
            HostScript(onChatSubscribe = { id, _ -> if (++calls == 1) null else result(id, """{"snapshot":${chatSnapshotJson(CHAT_A, SNAPSHOT_SEQ)}}""") }),
        ).start()
        val store = rig.host.openChat(CHAT_A)
        runCurrent()

        advanceTimeBy(6_000)
        runCurrent()

        val failure = assertIs<ChatNotLoadedException>(store.error.value)
        assertFalse(failure.interrupted)
        assertEquals(ConnState.Online, rig.host.connection.value)
        assertEquals(1, rig.connector.connects.size, "the connection was not torn down to ask again")
        assertEquals(1, rig.socket.sentMethods.count { it == "subscribe" })

        store.retry()
        runCurrent()

        assertNull(store.error.value)
        assertEquals("Chat", store.state.value!!.title)
        assertEquals(2, rig.socket.sentMethods.count { it == "subscribe" })
    }

    @Test
    fun `a connection lost three times in a row while a chat loads stops the loop, and the chat is left for retry`() = runTest {
        val hang = { HostScript(onChatSubscribe = { _, _ -> null }) }
        val rig = rig(hang(), hang(), hang(), HostScript()).start()
        val store = rig.host.openChat(CHAT_A)
        runCurrent()

        repeat(2) {
            rig.dropAndReconnect()
            runCurrent()
            assertNull(store.error.value, "after ${it + 1} lost connection(s) it is still tried again")
        }
        rig.dropAndReconnect()
        runCurrent()

        assertTrue(assertIs<ChatNotLoadedException>(store.error.value).interrupted)
        assertEquals(4, rig.connector.connects.size)
        assertFalse("subscribe" in rig.socket.sentMethods, "the connection that follows does not ask for it: ${rig.socket.sentMethods}")
    }

    @Test
    fun `a chat that did arrive resets the count, so a flaky link never gives it up`() = runTest {
        val hang = { HostScript(onChatSubscribe = { _, _ -> null }) }
        val rig = rig(hang(), hang(), HostScript(), hang(), hang(), HostScript()).start()
        val store = rig.host.openChat(CHAT_A)
        runCurrent()

        repeat(5) {
            rig.dropAndReconnect()
            runCurrent()
        }

        assertNull(store.error.value)
        assertEquals("Chat", store.state.value!!.title)
    }

    @Test
    fun `retry does nothing for a chat the host says is gone`() = runTest {
        val rig = rig(HostScript(missingSessions = setOf(CHAT_A))).start()
        val store = rig.host.openChat(CHAT_A)
        runCurrent()

        store.retry()
        runCurrent()

        assertIs<ChatGoneException>(store.error.value)
        assertEquals(1, rig.socket.sentMethods.count { it == "subscribe" })
    }

    @Test
    fun `loadOlder asks the host for the page, and the turns that follow are merged in`() = runTest {
        val rig = rig(HostScript(chatCursor = "c1")).start()
        val store = rig.host.openChat(CHAT_A)
        runCurrent()
        assertEquals("c1", store.state.value!!.turnsNextCursor)

        store.loadOlder()
        runCurrent()
        val fetch = rig.socket.sent.first { it["method"]?.jsonPrimitive?.content == "fetchTurns" }["params"]!!.jsonObject
        assertEquals(CHAT_A, fetch["channel"]!!.jsonPrimitive.content)
        assertEquals("c1", fetch["cursor"]!!.jsonPrimitive.content)

        rig.socket.serverSends(actionFrame(CHAT_A, turnsLoadedJson(listOf(turnJson("old")), null), SNAPSHOT_SEQ + 1))
        runCurrent()
        settle()

        assertEquals(listOf("old"), store.state.value!!.turns.map { it.id })
        assertNull(store.state.value!!.turnsNextCursor)
        assertFalse(store.loadingOlder.value)
    }

    @Test
    fun `opening, paging and closing a chat sends only read-tier methods`() = runTest {
        val rig = rig(HostScript(chatCursor = "c1")).start()
        val store = rig.host.openChat(CHAT_A)
        runCurrent()
        store.loadOlder()
        runCurrent()
        rig.socket.serverSends(actionFrame(CHAT_A, turnsLoadedJson(listOf(turnJson("old")), null), SNAPSHOT_SEQ + 1))
        runCurrent()
        settle()
        rig.host.closeChat(CHAT_A)
        runCurrent()

        val readTier = setOf("initialize", "reconnect", "ping", "subscribe", "unsubscribe", "listSessions", "fetchTurns")
        assertEquals(setOf("initialize", "listSessions", "subscribe", "fetchTurns", "unsubscribe"), rig.socket.sentMethods.toSet())
        assertTrue(rig.socket.sentMethods.all { it in readTier }, "sent: ${rig.socket.sentMethods}")
    }

    @Test
    fun `loadOlder while the connection is down reports it and keeps what is loaded`() = runTest {
        val rig = rig(HostScript(chatCursor = "c1"), HostScript(chatCursor = "c1")).start()
        val store = rig.host.openChat(CHAT_A)
        runCurrent()

        rig.socket.serverCloses(1011)
        rig.host.awaitState { it is ConnState.Reconnecting }
        store.loadOlder()
        runCurrent()

        assertIs<IOException>(store.olderError.value)
        assertFalse(store.loadingOlder.value)
        assertEquals("c1", store.state.value!!.turnsNextCursor)
        assertTrue("fetchTurns" !in rig.socket.sentMethods)
    }
}
