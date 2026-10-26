package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.Implementation
import dev.ahpclient.ahp.ROOT_CHANNEL
import dev.ahpclient.ahp.result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

/**
 * [HostSession.park] and [HostSession.unpark]: with the app out of sight the chats, changesets and terminals are let go of and the
 * root and sessions stay, a reconnect leaves them out, and coming back subscribes them again. Read from the frames on the socket.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostSessionParkTest {
    private val info = Implementation(name = "ahp-android-client-test", version = "0.0.1")

    private class Rig(val connector: ScriptedConnector, val host: HostSession) {
        val socket get() = connector.sockets.last()

        /** The channels of every [method] frame (`subscribe` or `unsubscribe`) the latest connection sent, in order. */
        fun sent(method: String) = socket.sent.filter { it["method"]?.jsonPrimitive?.content == method }.map { it["params"]!!.jsonObject["channel"]!!.jsonPrimitive.content }

        /** The channels the latest connection named to the host in its `initialize` or `reconnect`. */
        fun named(): List<String> = socket.sent.first()["params"]!!.jsonObject.let { it["subscriptions"] ?: it["initialSubscriptions"] }!!.jsonArray.map { it.jsonPrimitive.content }
    }

    private fun TestScope.rig(vararg scripts: HostScript): Rig {
        val connector = ScriptedConnector(*scripts)
        return Rig(connector, HostSession(connector, backgroundScope, info, Backoff(jitter = 0.0), "client-1"))
    }

    private suspend fun HostSession.awaitState(match: (ConnState) -> Boolean) = withTimeout(300_000) { connection.first(match) }

    private suspend fun Rig.start(): Rig = also {
        host.start()
        host.awaitState { it == ConnState.Online }
    }

    private suspend fun Rig.dropAndReconnect() {
        socket.serverCloses(1011)
        host.awaitState { it is ConnState.Reconnecting }
        host.awaitState { it == ConnState.Online }
    }

    private fun TestScope.settle() {
        advanceTimeBy(100)
        runCurrent()
    }

    /** A chat, a changeset and a terminal open next to a session, all subscribed. */
    private class Open(val session: SessionStore, val chat: ChatStore, val changeset: ChannelStore<*>, val terminal: ChannelStore<*>)

    private fun TestScope.openAll(rig: Rig): Open {
        val open = Open(rig.host.openSession(SESSION_C), rig.host.openChat(CHAT_A), rig.host.openChangeset(CHANGESET_A), rig.host.openTerminal(TERMINAL_A))
        runCurrent()
        return open
    }

    @Test
    fun `park lets go of the chat, the changeset and the terminal, keeps what they showed, and leaves the session subscribed`() = runTest {
        val rig = rig(HostScript()).start()
        val open = openAll(rig)
        val before = rig.socket.sent.size

        rig.host.park()
        runCurrent()

        assertEquals(setOf(CHAT_A, CHANGESET_A, TERMINAL_A), rig.sent("unsubscribe").toSet())
        assertEquals(listOf("unsubscribe", "unsubscribe", "unsubscribe"), rig.socket.sentMethods.drop(before), "nothing else was sent")
        assertNotNull(open.chat.state.value)
        assertNotNull(open.changeset.state.value)
        assertNotNull(open.terminal.state.value)
        assertTrue(open.chat.needsSubscribe && open.changeset.needsSubscribe && open.terminal.needsSubscribe)
        assertEquals(ConnState.Online, rig.host.connection.value)

        rig.socket.serverSends(actionFrame(SESSION_C, titleChanged("Renamed"), SNAPSHOT_SEQ + 1))
        settle()
        assertEquals("Renamed", open.session.state.value!!.title, "the session is still live")
    }

    @Test
    fun `parking twice sends one unsubscribe for each channel`() = runTest {
        val rig = rig(HostScript()).start()
        openAll(rig)

        rig.host.park()
        runCurrent()
        rig.host.park()
        runCurrent()

        assertEquals(3, rig.sent("unsubscribe").size)
        assertEquals(setOf(CHAT_A, CHANGESET_A, TERMINAL_A), rig.sent("unsubscribe").toSet())
    }

    @Test
    fun `a reconnect while parked names only the root and the sessions, and subscribes no chat or channel`() = runTest {
        val rig = rig(HostScript(), HostScript()).start()
        val open = openAll(rig)
        rig.host.park()
        runCurrent()

        rig.dropAndReconnect()
        runCurrent()

        assertEquals(listOf(ROOT_CHANNEL, SESSION_C), rig.named())
        assertEquals(listOf("reconnect", "listSessions"), rig.socket.sentMethods)
        assertNotNull(open.chat.state.value, "what the chat showed is still there")
        assertTrue(open.chat.needsSubscribe && open.changeset.needsSubscribe && open.terminal.needsSubscribe)
    }

    @Test
    fun `a session parked before it first connects names no changeset and subscribes nothing but the session`() = runTest {
        val rig = rig(HostScript())
        rig.host.openSession(SESSION_C)
        val chat = rig.host.openChat(CHAT_A)
        val changeset = rig.host.openChangeset(CHANGESET_A)
        rig.host.park()

        rig.start()
        runCurrent()

        assertEquals(listOf(ROOT_CHANNEL, SESSION_C), rig.named())
        assertEquals(listOf("initialize", "listSessions"), rig.socket.sentMethods)
        assertNull(chat.state.value)
        assertTrue(changeset.needsSubscribe)
    }

    @Test
    fun `unpark subscribes the chat, the changeset and the terminal again, and the chat shows its old state until the new snapshot replaces it`() = runTest {
        var asked = 0
        var pending: Long? = null
        val script = HostScript(
            onChatSubscribe = { id, _ ->
                if (++asked == 1) {
                    result(id, """{"snapshot":${chatSnapshotJson(CHAT_A, SNAPSHOT_SEQ, turns = listOf(turnJson("old")))}}""")
                } else {
                    pending = id
                    null
                }
            },
        )
        val rig = rig(script).start()
        val open = openAll(rig)
        rig.host.park()
        runCurrent()

        rig.host.unpark()
        runCurrent()

        assertEquals(2, rig.sent("subscribe").count { it == CHAT_A })
        assertEquals(2, rig.sent("subscribe").count { it == CHANGESET_A })
        assertEquals(2, rig.sent("subscribe").count { it == TERMINAL_A })
        assertEquals(1, rig.sent("subscribe").count { it == SESSION_C }, "the session was never let go of")
        assertEquals(listOf("old"), open.chat.state.value!!.turns.map { it.id }, "the old transcript stays while the new one is on its way")
        assertNull(open.chat.error.value)

        rig.socket.serverSends(result(pending, """{"snapshot":${chatSnapshotJson(CHAT_A, SNAPSHOT_SEQ + 5, turns = listOf(turnJson("fresh")))}}"""))
        runCurrent()

        assertEquals(listOf("fresh"), open.chat.state.value!!.turns.map { it.id })
        assertFalse(open.chat.needsSubscribe)
        assertFalse(open.changeset.needsSubscribe)
        assertFalse(open.terminal.needsSubscribe)
    }

    @Test
    fun `unpark while there is no connection does nothing, and the next connection subscribes as usual`() = runTest {
        val rig = rig(HostScript(), HostScript()).start()
        val open = openAll(rig)
        rig.host.park()
        runCurrent()
        rig.socket.serverCloses(1011)
        rig.host.awaitState { it is ConnState.Reconnecting }

        rig.host.unpark()
        runCurrent()
        assertTrue(open.chat.needsSubscribe)

        rig.host.awaitState { it == ConnState.Online }
        runCurrent()

        assertEquals(listOf(ROOT_CHANNEL, SESSION_C, CHANGESET_A), rig.named())
        assertEquals(setOf(CHAT_A, CHANGESET_A, TERMINAL_A), rig.sent("subscribe").toSet())
        assertFalse(open.chat.needsSubscribe)
        assertFalse(open.changeset.needsSubscribe)
        assertFalse(open.terminal.needsSubscribe)
    }

    @Test
    fun `a chat, a changeset and a terminal opened while parked are subscribed at once`() = runTest {
        val rig = rig(HostScript()).start()
        rig.host.park()
        runCurrent()

        val chat = rig.host.openChat(CHAT_A)
        val changeset = rig.host.openChangeset(CHANGESET_A)
        val terminal = rig.host.openTerminal(TERMINAL_A)
        runCurrent()

        assertEquals(setOf(CHAT_A, CHANGESET_A, TERMINAL_A), rig.sent("subscribe").toSet())
        assertNotNull(chat.state.value)
        assertNotNull(changeset.state.value)
        assertNotNull(terminal.state.value)
    }

    @Test
    fun `a changeset opened while parked is not forgotten by a reconnect, and is subscribed when the app returns`() = runTest {
        val rig = rig(HostScript(), HostScript()).start()
        rig.host.park()
        runCurrent()
        val changeset = rig.host.openChangeset(CHANGESET_A)
        runCurrent()
        assertFalse(changeset.needsSubscribe)

        rig.dropAndReconnect()
        runCurrent()

        assertEquals(listOf(ROOT_CHANNEL), rig.named(), "the host is not asked to keep it")
        assertTrue(changeset.needsSubscribe, "so it does not claim a subscription it no longer has")

        rig.host.unpark()
        runCurrent()

        assertEquals(listOf(CHANGESET_A), rig.sent("subscribe"))
        assertFalse(changeset.needsSubscribe)
    }

    @Test
    fun `a chat held open in the background survives park, and is let go and invalidated by its background close while a screen still holds it`() = runTest {
        val rig = rig(HostScript()).start()
        val screen = rig.host.openChat(CHAT_A)
        rig.host.openChat(CHAT_A, background = true)
        runCurrent()

        rig.host.park()
        runCurrent()
        assertEquals(emptyList(), rig.sent("unsubscribe"), "something in the background needs it")
        assertFalse(screen.needsSubscribe)

        rig.host.closeChat(CHAT_A, background = true)
        runCurrent()

        assertEquals(listOf(CHAT_A), rig.sent("unsubscribe"))
        assertTrue(screen.needsSubscribe)
        assertNotNull(screen.state.value, "it keeps showing what it has")
    }

    @Test
    fun `a decision-style open while parked subscribes the chat for the job and lets it go again, though a screen holds it`() = runTest {
        val rig = rig(HostScript()).start()
        val screen = rig.host.openChat(CHAT_A)
        runCurrent()
        rig.host.park()
        runCurrent()
        assertEquals(listOf(CHAT_A), rig.sent("unsubscribe"))

        rig.host.openChat(CHAT_A, background = true)
        runCurrent()
        assertEquals(2, rig.sent("subscribe").count { it == CHAT_A }, "the job's open is honoured at once")
        assertFalse(screen.needsSubscribe)

        rig.host.closeChat(CHAT_A, background = true)
        runCurrent()

        assertEquals(listOf(CHAT_A, CHAT_A), rig.sent("unsubscribe"))
        assertTrue(screen.needsSubscribe)
    }

    @Test
    fun `a chat opened and closed in the background with no screen on it is released the ordinary way`() = runTest {
        val rig = rig(HostScript()).start()
        rig.host.park()
        runCurrent()

        rig.host.openChat(CHAT_A, background = true)
        runCurrent()
        rig.host.closeChat(CHAT_A, background = true)
        runCurrent()

        assertEquals(listOf(CHAT_A), rig.sent("subscribe"))
        assertEquals(listOf(CHAT_A), rig.sent("unsubscribe"))
    }

    @Test
    fun `closing a background opener while not parked leaves the chat subscribed`() = runTest {
        val rig = rig(HostScript()).start()
        val screen = rig.host.openChat(CHAT_A)
        rig.host.openChat(CHAT_A, background = true)
        runCurrent()

        rig.host.closeChat(CHAT_A, background = true)
        runCurrent()

        assertEquals(emptyList(), rig.sent("unsubscribe"))
        assertFalse(screen.needsSubscribe)
    }

    @Test
    fun `a chat held in the background is subscribed again by a reconnect while parked, and the others are not`() = runTest {
        val rig = rig(HostScript(), HostScript()).start()
        rig.host.openChat(CHAT_A, background = true)
        rig.host.openChat(CHAT_B)
        runCurrent()
        rig.host.park()
        runCurrent()
        assertEquals(listOf(CHAT_B), rig.sent("unsubscribe"))

        rig.dropAndReconnect()
        runCurrent()

        assertEquals(listOf(CHAT_A), rig.sent("subscribe"))
    }

    @Test
    fun `park while a subscribe is in flight ends parked once the snapshot arrives`() = runTest {
        var pending: Long? = null
        val rig = rig(HostScript(onChatSubscribe = { id, _ -> pending = id; null })).start()
        val chat = rig.host.openChat(CHAT_A)
        runCurrent()
        assertNull(chat.state.value)

        rig.host.park()
        runCurrent()
        assertEquals(emptyList(), rig.sent("unsubscribe"), "it waits behind the subscribe that holds the lock")

        rig.socket.serverSends(result(pending, """{"snapshot":${chatSnapshotJson(CHAT_A, SNAPSHOT_SEQ)}}"""))
        runCurrent()

        assertEquals(listOf(CHAT_A), rig.sent("unsubscribe"))
        assertTrue(chat.needsSubscribe)
        assertNotNull(chat.state.value)
    }

    @Test
    fun `a park that comes while a reconnect is being made lets go of the changeset the reconnect named, once it is live`() = runTest {
        var pending: Long? = null
        val rig = rig(HostScript(), HostScript(onReconnect = { id -> pending = id; null })).start()
        val open = openAll(rig)
        rig.socket.serverCloses(1011)
        rig.host.awaitState { it is ConnState.Reconnecting }
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(ROOT_CHANNEL, SESSION_C, CHANGESET_A), rig.named(), "asked to keep the changeset, and not yet answered")

        rig.host.park()
        runCurrent()
        assertEquals(emptyList(), rig.sent("unsubscribe"), "there is no connection to say it on yet")

        rig.socket.serverSends(result(pending, """{"type":"replay","actions":[],"missing":[]}"""))
        rig.host.awaitState { it == ConnState.Online }
        runCurrent()

        assertEquals(listOf(CHANGESET_A), rig.sent("unsubscribe"))
        assertEquals(emptyList(), rig.sent("subscribe"))
        assertTrue(open.changeset.needsSubscribe)
        assertNotNull(open.changeset.state.value, "it keeps showing what it has")
    }

    @Test
    fun `a park that comes after a connection chose its chats and before they subscribe leaves them unsubscribed`() = runTest {
        val rig = rig(HostScript(), HostScript()).start()
        val chat = rig.host.openChat(CHAT_A)
        runCurrent()
        rig.socket.serverCloses(1011)
        rig.host.awaitState { it is ConnState.Reconnecting }
        rig.host.awaitState { it == ConnState.Online }

        rig.host.park()
        runCurrent()

        assertEquals(emptyList(), rig.sent("subscribe"), "the chat was picked while the app was in sight, but the subscribe finds it parked")
        assertEquals(emptyList(), rig.sent("unsubscribe"))
        assertTrue(chat.needsSubscribe)
    }

    @Test
    fun `two decisions overlapping on a chat while parked leave it subscribed until the last is done`() = runTest {
        val rig = rig(HostScript()).start()
        val screen = rig.host.openChat(CHAT_A)
        runCurrent()
        rig.host.park()
        runCurrent()
        assertEquals(listOf(CHAT_A), rig.sent("unsubscribe"))

        rig.host.openChat(CHAT_A, background = true)
        rig.host.openChat(CHAT_A, background = true)
        runCurrent()
        assertEquals(2, rig.sent("subscribe").count { it == CHAT_A })
        assertFalse(screen.needsSubscribe)

        rig.host.closeChat(CHAT_A, background = true)
        runCurrent()
        assertEquals(listOf(CHAT_A), rig.sent("unsubscribe"), "the other decision is still using it")
        assertFalse(screen.needsSubscribe)

        rig.host.closeChat(CHAT_A, background = true)
        runCurrent()
        assertEquals(listOf(CHAT_A, CHAT_A), rig.sent("unsubscribe"))
        assertTrue(screen.needsSubscribe)
    }

    @Test
    fun `rapid park, unpark and park ends parked, and lets go of each channel once`() = runTest {
        val rig = rig(HostScript()).start()
        openAll(rig)

        rig.host.park()
        rig.host.unpark()
        rig.host.park()
        runCurrent()

        assertTrue(rig.host.parked)
        assertEquals(setOf(CHAT_A, CHANGESET_A, TERMINAL_A), rig.sent("unsubscribe").toSet())
        assertEquals(3, rig.sent("unsubscribe").size)
        assertEquals(1, rig.sent("subscribe").count { it == CHAT_A })
    }

    @Test
    fun `rapid unpark, park and unpark after a park ends unparked, with each channel subscribed again once`() = runTest {
        val rig = rig(HostScript()).start()
        openAll(rig)
        rig.host.park()
        runCurrent()

        rig.host.unpark()
        rig.host.park()
        rig.host.unpark()
        runCurrent()

        assertFalse(rig.host.parked)
        assertEquals(2, rig.sent("subscribe").count { it == CHAT_A })
        assertEquals(2, rig.sent("subscribe").count { it == CHANGESET_A })
        assertEquals(2, rig.sent("subscribe").count { it == TERMINAL_A })
        assertEquals(3, rig.sent("unsubscribe").size, "only the first park let anything go")
    }

    @Test
    fun `park and unpark send only read-tier methods`() = runTest {
        val rig = rig(HostScript()).start()
        openAll(rig)

        rig.host.park()
        runCurrent()
        rig.host.unpark()
        runCurrent()

        val readTier = setOf("initialize", "reconnect", "ping", "subscribe", "unsubscribe", "listSessions", "fetchTurns")
        assertEquals(emptySet(), rig.socket.sentMethods.toSet() - readTier)
    }

    @Test
    fun `a chat the app gave up on stays given up through park and unpark`() = runTest {
        val rig = rig(HostScript(onChatSubscribe = { _, _ -> null })).start()
        val chat = rig.host.openChat(CHAT_A)
        runCurrent()
        advanceTimeBy(6_000)
        runCurrent()
        assertTrue(chat.error.value is ChatNotLoadedException)
        val asked = rig.sent("subscribe").size

        rig.host.park()
        runCurrent()
        rig.host.unpark()
        runCurrent()

        assertEquals(asked, rig.sent("subscribe").size, "nothing asks for it again until the person retries")
        assertTrue(chat.error.value is ChatNotLoadedException)
    }
}
