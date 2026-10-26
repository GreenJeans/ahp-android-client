package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.Implementation
import dev.ahpclient.ahp.AhpException
import dev.ahpclient.tunnel.ClientOverloadedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

/** A host that sends faster than the app applies: the connection is dropped and resumed, and a chat that causes it three times in a row is given up on. */
@OptIn(ExperimentalCoroutinesApi::class)
class HostSessionOverloadTest {
    private val info = Implementation(name = "ahp-android-client-test", version = "0.0.1")

    private class Rig(val connector: ScriptedConnector, val host: HostSession) {
        val socket get() = connector.sockets.last()
    }

    private fun TestScope.rig(vararg scripts: HostScript, maxQueued: Int = QUEUE): Rig {
        val connector = ScriptedConnector(*scripts, maxQueuedNotifications = maxQueued)
        return Rig(connector, HostSession(connector, backgroundScope, info, Backoff(), "client-1"))
    }

    private suspend fun HostSession.awaitState(match: (ConnState) -> Boolean) = withTimeout(300_000) { connection.first(match) }

    private suspend fun Rig.start(): Rig = also {
        host.start()
        host.awaitState { it == ConnState.Online }
    }

    /** Sends more notifications than the connection holds, all at once, so the reader fills the buffer before anything applies them. */
    private fun Rig.flood() {
        repeat(QUEUE * 3) { socket.serverSends(notification("root/sessionRemoved", """{"session":"ahp-session:/gone-$it"}""")) }
    }

    private suspend fun Rig.overloadAndReconnect() {
        flood()
        host.awaitState { it is ConnState.Reconnecting }
        host.awaitState { it == ConnState.Online }
    }

    @Test
    fun `an overloaded connection moves to Reconnecting, not Failed, and the next one resumes`() = runTest {
        val rig = rig(HostScript(), HostScript()).start()

        rig.flood()
        val state = assertIs<ConnState.Reconnecting>(rig.host.awaitState { it is ConnState.Reconnecting })

        val closed = assertIs<AhpException.ConnectionClosed>(state.cause)
        assertNotNull(generateSequence<Throwable>(closed) { it.cause }.filterIsInstance<ClientOverloadedException>().firstOrNull(), "the overload is the cause")
        rig.host.awaitState { it == ConnState.Online }
        runCurrent()
        assertEquals(2, rig.connector.connects.size)
        assertEquals(listOf("reconnect", "listSessions"), rig.socket.sentMethods)
    }

    @Test
    fun `a burst that fits the buffer leaves the connection alone`() = runTest {
        val rig = rig(HostScript(), maxQueued = 2_000).start()

        repeat(1_000) { rig.socket.serverSends(notification("root/sessionRemoved", """{"session":"ahp-session:/gone-$it"}""")) }
        runCurrent()

        assertEquals(ConnState.Online, rig.host.connection.value)
        assertEquals(1, rig.connector.connects.size)
    }

    @Test
    fun `a chat that overloads the connection three times in a row ends as not loaded and is not asked for again`() = runTest {
        val hang = { HostScript(onChatSubscribe = { _, _ -> null }) }
        val rig = rig(hang(), hang(), hang(), HostScript()).start()
        val store = rig.host.openChat(CHAT_A)
        runCurrent()

        repeat(2) {
            rig.overloadAndReconnect()
            runCurrent()
            assertNull(store.error.value, "after ${it + 1} overload(s) it is still tried again")
        }
        rig.overloadAndReconnect()
        runCurrent()

        assertTrue(assertIs<ChatNotLoadedException>(store.error.value).interrupted)
        assertEquals(4, rig.connector.connects.size)
        assertFalse("subscribe" in rig.socket.sentMethods, "the connection that follows does not ask for it: ${rig.socket.sentMethods}")
    }

    @Test
    fun `a chat given up on after overloads is subscribed again when the person retries`() = runTest {
        val hang = { HostScript(onChatSubscribe = { _, _ -> null }) }
        val rig = rig(hang(), hang(), hang(), HostScript()).start()
        val store = rig.host.openChat(CHAT_A)
        runCurrent()
        repeat(3) {
            rig.overloadAndReconnect()
            runCurrent()
        }
        assertIs<ChatNotLoadedException>(store.error.value)

        store.retry()
        runCurrent()

        assertNull(store.error.value)
        assertEquals("Chat", store.state.value!!.title)
        assertEquals(1, rig.socket.sentMethods.count { it == "subscribe" })
    }

    @Test
    fun `an overload while no chat is loading does not count against the chat that arrived`() = runTest {
        val rig = rig(HostScript(), HostScript(), HostScript(), HostScript()).start()
        val store = rig.host.openChat(CHAT_A)
        runCurrent()
        assertEquals("Chat", store.state.value!!.title)

        repeat(3) {
            rig.overloadAndReconnect()
            runCurrent()
        }

        assertNull(store.error.value)
        assertEquals("Chat", store.state.value!!.title)
        assertEquals(1, rig.socket.sentMethods.count { it == "subscribe" }, "each new connection asks for it afresh")
    }

    @Test
    fun `actions queued while attaching are asked for again when the overload ends the connection before they are applied`() = runTest {
        lateinit var rig: Rig
        val floodsReconnect = HostScript(onReconnect = {
            repeat(QUEUE * 3) { rig.socket.serverSends(actionFrame(SESSION_A, titleChanged("t$it"), FLOODED_SEQ + it)) }
            null
        })
        rig = rig(HostScript(), floodsReconnect, HostScript()).start()

        rig.overloadAndReconnect()
        runCurrent()

        assertEquals(3, rig.connector.connects.size)
        val held = rig.connector.connects[1].second
        assertTrue(held in 1 until FLOODED_SEQ, "the first connection was online and holds a real sequence: $held")
        assertEquals(held, rig.connector.connects[2].second, "the actions that were queued and never applied are not skipped")
    }

    private companion object {
        /** Small, so a test can fill it with a few frames. */
        const val QUEUE = 4

        /** Far above any sequence the fixtures use, so an action from it can only come from the flood. */
        const val FLOODED_SEQ = 1_000_000_000L
    }
}
