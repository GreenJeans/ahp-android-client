package dev.ahpclient.ahp

import com.microsoft.agenthostprotocol.generated.Implementation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/** What a host can do wrong on the wire, and that none of it takes the connection down. */
@OptIn(ExperimentalCoroutinesApi::class)
class AhpConnectionRobustnessTest {
    private val clientInfo = Implementation(name = "ahp-android-client", version = "0.1.0")

    private fun TestScope.connection(socket: FakeSocket) =
        AhpConnection(socket, backgroundScope, CapabilityPolicy.READ, clientId = "client-1", requestTimeoutMs = 5_000)

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
