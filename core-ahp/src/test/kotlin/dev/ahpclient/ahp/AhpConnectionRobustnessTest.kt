package dev.ahpclient.ahp

import com.microsoft.agenthostprotocol.generated.Implementation
import dev.ahpclient.tunnel.ClientOverloadedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** What a host can do wrong on the wire, and that none of it takes the connection down. */
@OptIn(ExperimentalCoroutinesApi::class)
class AhpConnectionRobustnessTest {
    private val clientInfo = Implementation(name = "ahp-android-client", version = "0.1.0")

    private fun TestScope.connection(socket: FakeSocket) =
        AhpConnection(socket, backgroundScope, CapabilityPolicy.READ, clientId = "client-1", requestTimeoutMs = 5_000)

    private fun TestScope.smallInbox(socket: FakeSocket, capacity: Int = 3) =
        AhpConnection(socket, backgroundScope, CapabilityPolicy.READ, clientId = "client-1", requestTimeoutMs = 5_000, maxQueuedNotifications = capacity)

    /** The overload somewhere in the chain of causes: an exception that crosses a coroutine boundary under test is copied, with the original as its cause. */
    private fun Throwable.overload(): ClientOverloadedException? = generateSequence(this) { it.cause }.filterIsInstance<ClientOverloadedException>().firstOrNull()

    private fun named(n: Int) = """{"jsonrpc":"2.0","method":"root/sessionRemoved","params":{"session":"ahp-session:/$n"}}"""

    private fun action(seq: Long) =
        """{"jsonrpc":"2.0","method":"action","params":{"channel":"ahp-session:/abc","action":{"type":"session/titleChanged","title":"t"},"serverSeq":$seq}}"""

    @Test
    fun `a full inbox ends the connection with ClientOverloadedException as the cause`() = runTest {
        val socket = FakeSocket()
        val connection = smallInbox(socket)
        repeat(4) { socket.serverSends(named(it)) }
        runCurrent()

        val closed = assertFailsWith<AhpException.ConnectionClosed> { connection.ping() }

        assertIs<ClientOverloadedException>(closed.cause)
        assertTrue(closed.cause!!.message!!.contains("3"), "the message names the limit")
    }

    @Test
    fun `a request waiting when the inbox fills fails with ConnectionClosed`() = runTest {
        val socket = FakeSocket()
        val connection = smallInbox(socket)
        val waiting = async { runCatching { connection.ping() } }
        runCurrent()

        repeat(4) { socket.serverSends(named(it)) }
        runCurrent()

        val closed = assertIs<AhpException.ConnectionClosed>(waiting.await().exceptionOrNull())
        assertNotNull(closed.overload())
    }

    @Test
    fun `what was queued is still delivered before the collector sees the overload`() = runTest {
        val socket = FakeSocket()
        val connection = smallInbox(socket)
        repeat(5) { socket.serverSends(named(it)) }
        runCurrent()

        val seen = ArrayList<ServerNotification>()
        val failure = runCatching { connection.notifications.collect { seen += it } }.exceptionOrNull()

        assertEquals(3, seen.size)
        assertNotNull(assertIs<AhpException.ConnectionClosed>(failure).overload())
    }

    @Test
    fun `an action lost to a full inbox does not move the resume point`() = runTest {
        val socket = FakeSocket()
        val connection = smallInbox(socket, capacity = 2)
        socket.serverSends(action(1))
        socket.serverSends(action(2))
        socket.serverSends(action(3))
        runCurrent()

        assertEquals(2L, connection.lastServerSeq)
    }

    @Test
    fun `an undecodable frame is refused too when the inbox is full`() = runTest {
        val socket = FakeSocket()
        val connection = smallInbox(socket, capacity = 1)
        socket.serverSends("""{"jsonrpc":"2.0"}""")
        socket.serverSends("not json")
        runCurrent()

        assertIs<ClientOverloadedException>(assertFailsWith<AhpException.ConnectionClosed> { connection.ping() }.cause)
    }

    @Test
    fun `a burst that fits the inbox is not an overload`() = runTest {
        val socket = FakeSocket()
        val connection = smallInbox(socket, capacity = 4)
        repeat(4) { socket.serverSends(named(it)) }
        runCurrent()

        assertEquals(4, connection.notifications.take(4).toList().size)
    }

    @Test
    fun `a frame with neither a method nor an id is reported, and the next frame still arrives`() = runTest {
        val socket = FakeSocket()
        val connection = connection(socket)
        socket.serverSends("""{"jsonrpc":"2.0"}""")
        socket.serverSends("""{"jsonrpc":"2.0","method":"root/sessionRemoved","params":{"session":"ahp-session:/a"}}""")

        val first = connection.notifications.first()

        val undecodable = assertIs<ServerNotification.Undecodable>(first)
        assertNull(undecodable.method)
        assertTrue(undecodable.cause.message!!.contains("neither a method nor an id"))
    }

    @Test
    fun `an unsupported-version error with unreadable data still says what was offered`() = runTest {
        val socket = FakeSocket { method, id, _ ->
            if (method == "initialize") error(id, -32005, "unsupported", "\"not an object\"") else null
        }

        val failure = assertFailsWith<AhpException.UnsupportedProtocolVersion> { connection(socket).initialize(clientInfo) }

        assertEquals(listOf("1.0.0", "0.9.0"), failure.offered)
        assertEquals(emptyList(), failure.supported)
    }

    @Test
    fun `a result of the wrong shape is a protocol error naming the method`() = runTest {
        val socket = FakeSocket { method, id, _ -> if (method == "listSessions") result(id, """{"items":"not a list"}""") else null }

        val failure = assertFailsWith<AhpException.Protocol> { connection(socket).listSessions() }

        assertTrue(failure.message!!.contains("listSessions"))
    }

    @Test
    fun `a request the host never answers is not left registered after it times out`() = runTest {
        val socket = FakeSocket()
        val connection = connection(socket)

        assertFailsWith<AhpException.Timeout> { connection.ping() }
        assertFailsWith<AhpException.Timeout> { connection.ping() }

        assertEquals(2, socket.sentMethods.count { it == "ping" })
    }
}
