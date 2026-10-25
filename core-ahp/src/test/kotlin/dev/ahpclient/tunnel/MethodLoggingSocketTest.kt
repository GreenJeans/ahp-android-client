package dev.ahpclient.tunnel

import dev.ahpclient.ahp.FakeSocket
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class MethodLoggingSocketTest {
    @Test
    fun `a read call is logged by method name only`() {
        assertEquals("initialize", describeOutbound("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"clientId":"secret-id","token":"abc"}}"""))
        listOf("reconnect", "ping", "subscribe", "unsubscribe", "listSessions", "fetchTurns").forEach {
            assertEquals(it, describeOutbound("""{"jsonrpc":"2.0","method":"$it","params":{}}"""))
        }
    }

    @Test
    fun `a method outside the read tier is marked, whatever its params`() {
        assertEquals("NOT-READ dispatchAction", describeOutbound("""{"jsonrpc":"2.0","id":2,"method":"dispatchAction","params":{"action":{"type":"session/titleChanged"}}}"""))
        assertEquals("NOT-READ authenticate", describeOutbound("""{"method":"authenticate"}"""))
    }

    @Test
    fun `the gateway selection is named without its instance id, and odd frames are flagged`() {
        assertEquals("gateway.select", describeOutbound("""{"instanceId":"abc-123"}"""))
        assertEquals("NOT-READ frame without a method", describeOutbound("""{"jsonrpc":"2.0","id":3}"""))
        assertEquals("unparseable frame", describeOutbound("not json"))
        assertEquals("unparseable frame", describeOutbound("[1,2]"))
    }

    @Test
    fun `the wrapper logs every send, still sends it, and never leaks params, url or headers`() = runTest {
        val socket = FakeSocket()
        val lines = mutableListOf<String>()
        val wrapped = MethodLoggingSocketOpener({ _, _ -> socket }, lines::add).open("wss://secret-host.example/path?tkn=abc", mapOf("X-Tunnel-Authorization" to "tunnel secret-token"))

        wrapped.send("""{"jsonrpc":"2.0","id":1,"method":"listSessions","params":{"channel":"ahp-root://","cursor":"private-cursor"}}""")
        wrapped.send("""{"jsonrpc":"2.0","method":"unsubscribe","params":{"channel":"ahp-session:/title-ish"}}""")

        assertEquals(listOf("listSessions", "unsubscribe"), lines)
        assertEquals(listOf("listSessions", "unsubscribe"), socket.sentMethods)
        val everything = lines.joinToString("\n")
        listOf("secret-host", "secret-token", "private-cursor", "title-ish", "params", "channel").forEach { assertFalse(everything.contains(it), it) }
    }

    @Test
    fun `a closed socket still reports false after logging the attempt`() = runTest {
        val socket = FakeSocket()
        val lines = mutableListOf<String>()
        val wrapped = MethodLoggingSocketOpener({ _, _ -> socket }, lines::add).open("wss://x", emptyMap())
        socket.serverCloses()

        val sent = wrapped.send("""{"method":"ping"}""")

        assertEquals(false, sent)
        assertEquals(listOf("ping"), lines)
    }

    @Test
    fun `a big inbound frame is logged by its size alone, and a small one not at all`() = runTest {
        val socket = FakeSocket()
        val lines = mutableListOf<String>()
        val wrapped = MethodLoggingSocketOpener({ _, _ -> socket }, lines::add).open("wss://x", emptyMap())
        val big = """{"secret":"private-text-${"x".repeat(300_000)}"}"""
        socket.serverSends("""{"small":"private-small"}""")
        socket.serverSends(big)

        assertEquals("""{"small":"private-small"}""", wrapped.receive())
        assertEquals(big, wrapped.receive())

        assertEquals(listOf("received a frame of ${big.length} characters"), lines)
        assertFalse(lines.joinToString().contains("private"))
    }

    @Test
    fun `the end of a socket is logged with its close code and never its reason`() = runTest {
        val socket = FakeSocket()
        val lines = mutableListOf<String>()
        val wrapped = MethodLoggingSocketOpener({ _, _ -> socket }, lines::add).open("wss://x", emptyMap())
        socket.serverCloses(1011)

        assertFailsWith<SocketClosedException> { wrapped.receive() }

        assertEquals(1, lines.size)
        assertTrue(lines.single().startsWith("socket ended: SocketClosedException code=1011"), lines.single())
    }

    @Test
    fun `a failure is described by the classes of its causes, a close code, and the words of a timeout only`() {
        val ping = SocketTimeoutException("sent ping but didn't receive pong within 30000ms (after 0 successful ping/pongs)")
        assertEquals(
            "SocketClosedException <- SocketTimeoutException \"sent ping but didn't receive pong within 30000ms (after 0 successful ping/pongs)\"",
            describeFailure(SocketClosedException(null, ping.message, ping)),
        )
        assertEquals("SocketClosedException code=1011", describeFailure(SocketClosedException(1011, "private reason")))
        val other = describeFailure(IllegalStateException("wss://secret-host.example/?tkn=abc", IOException("Authorization: tunnel secret-token")))
        assertEquals("IllegalStateException <- IOException", other)
    }
}
