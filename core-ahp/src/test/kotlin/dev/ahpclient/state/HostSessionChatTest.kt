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

/** Chats through [HostSession]: opening, subscribing, routing, closing and surviving a reconnect, like sessions do. */
@OptIn(ExperimentalCoroutinesApi::class)
class HostSessionChatTest {
    private val info = Implementation(name = "ahp-android-client-test", version = "0.0.1")

    private class Rig(val connector: ScriptedConnector, val host: HostSession) {
        val socket get() = connector.sockets.last()
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
    fun `a chat opened before connecting is asked for in the first initialize and subscribed right after`() = runTest {
        val rig = rig(HostScript())
        val store = rig.host.openChat(CHAT_A)

        rig.start()
        runCurrent()

        val initialize = rig.socket.sent.first { it["method"]?.jsonPrimitive?.content == "initialize" }
        assertEquals(listOf("ahp-root://", CHAT_A), initialize["params"]!!.jsonObject["initialSubscriptions"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Chat", store.state.value!!.title)
        assertEquals(listOf("initialize", "listSessions", "subscribe"), rig.socket.sentMethods)
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
    fun `reconnect resumes the chat, replays its actions and subscribes to it by name`() = runTest {
        val replay = """{"type":"replay","actions":[{"channel":"$CHAT_A","action":$TURN_STARTED,"serverSeq":126570}],"missing":[]}"""
        val rig = rig(HostScript(), HostScript(onReconnect = { id -> result(id, replay) })).start()
        val store = rig.host.openChat(CHAT_A)
        runCurrent()
        assertNull(store.state.value!!.activeTurn)

        rig.dropAndReconnect()
        settle()

        val params = rig.socket.sent.first()["params"]!!.jsonObject
        assertEquals(listOf("ahp-root://", CHAT_A), params["subscriptions"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("t1", store.state.value!!.activeTurn!!.id)
        assertEquals(listOf("reconnect", "listSessions"), rig.socket.sentMethods)
    }

    @Test
    fun `a snapshot reconnect result replaces chat state and a skipped chat subscribes again`() = runTest {
        val covered = """{"type":"snapshot","snapshots":[${chatSnapshotJson(CHAT_A, 126_600, turns = listOf(turnJson("fresh")))}]}"""
        val skipped = """{"type":"snapshot","snapshots":[]}"""
        for ((reply, turns, methods) in listOf(
            Triple(covered, listOf("fresh"), listOf("reconnect", "listSessions")),
            Triple(skipped, emptyList(), listOf("reconnect", "listSessions", "subscribe")),
        )) {
            val rig = rig(HostScript(), HostScript(onReconnect = { id -> result(id, reply) })).start()
            val store = rig.host.openChat(CHAT_A)
            runCurrent()

            rig.dropAndReconnect()

            assertEquals(turns, store.state.value!!.turns.map { it.id })
            assertEquals(methods, rig.socket.sentMethods)
            rig.host.stop()
        }
    }

    @Test
    fun `a chat the host lost during the gap is marked gone and not subscribed again`() = runTest {
        val replay = """{"type":"replay","actions":[],"missing":["$CHAT_A"]}"""
        val rig = rig(HostScript(), HostScript(onReconnect = { id -> result(id, replay) })).start()
        val store = rig.host.openChat(CHAT_A)
        runCurrent()

        rig.dropAndReconnect()

        assertIs<ChatGoneException>(store.error.value)
        assertEquals(listOf("reconnect", "listSessions"), rig.socket.sentMethods)
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
