package dev.ahpclient.tunnel

import dev.ahpclient.ahp.FakeSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
