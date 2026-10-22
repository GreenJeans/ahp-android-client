package dev.ahpclient.tunnel

import dev.ahpclient.auth.Secret
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class TunnelGatewayTest {
    private val ack = """{"ok":true,"selected":{"type":"editor","instanceId":"i1","role":"primary","lifecycle":"external"}}"""

    private fun inventory(endpoints: String, delegated: String? = null) =
        """{"userDataPath":"C:\\Users\\u","endpoints":[$endpoints]${delegated?.let { ""","delegatedInstanceId":"$it"""" } ?: ""}}"""

    private fun endpoint(type: String, id: String) =
        """{"type":"$type","pid":42,"instanceId":"$id","endpointKind":"socket","endpointLabel":"\\\\.\\pipe\\vscode-$id"}"""

    private fun target(server: MockWebServer, protocol: Int = 6) = ConnectTarget(
        TunnelInfo("test-tunnel-1", "usw2", "dev-box", protocol, 1, listOf(31546)),
        "http://127.0.0.1:{port}/",
        Secret("connect-token"),
    )

    private fun gateway(server: MockWebServer, timeoutMs: Long = 5_000) =
        TunnelGateway(stepTimeoutMs = timeoutMs, agentHostPort = server.port)

    /** Scripts one server-side WebSocket and records every frame the client sends. */
    private class Script(
        val received: LinkedBlockingQueue<String> = LinkedBlockingQueue(),
        private val onOpen: (WebSocket) -> Unit = {},
        private val onMessage: (WebSocket, String) -> Unit = { _, _ -> },
    ) {
        // `this@Script` matters: the listener has its own onOpen/onMessage, which would otherwise shadow the handlers.
        fun response(): MockResponse = MockResponse().withWebSocketUpgrade(
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = this@Script.onOpen(webSocket)

                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.add(text)
                    this@Script.onMessage(webSocket, text)
                }

                // Complete the close handshake so MockWebServer can shut down cleanly.
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }
            },
        )
    }

    private fun withServer(block: suspend (MockWebServer) -> Unit) = runBlocking {
        MockWebServer().use { server ->
            server.start()
            block(server)
        }
    }

    @Test
    fun `v6 opens the gateway, selects the delegated instance and leaves the socket speaking AHP`() = withServer { server ->
        val script = Script(
            onOpen = { it.send(inventory(endpoint("editor", "i1"), delegated = "i1")) },
            onMessage = { ws, _ ->
                ws.send(ack)
                ws.send("""{"jsonrpc":"2.0","method":"ping"}""") // arrives right after the ack, before anyone reads
            },
        )
        server.enqueue(script.response())

        val connection = gateway(server).open(target(server))

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/agent-host/select", request.path)
        assertEquals("tunnel connect-token", request.getHeader("X-Tunnel-Authorization"))
        assertEquals("""{"instanceId":"i1"}""", script.received.poll(5, TimeUnit.SECONDS))
        assertEquals(SelectedEndpoint("editor", "i1", "external"), connection.selected)
        assertEquals("""{"jsonrpc":"2.0","method":"ping"}""", connection.socket.receive())
        connection.socket.close()
    }

    private fun selectionFor(inventoryJson: String, instanceId: String? = null): String? = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val script = Script(onOpen = { it.send(inventoryJson) }, onMessage = { ws, text -> ws.send(ack.replace("i1", Regex("\"instanceId\":\"([^\"]+)\"").find(text)!!.groupValues[1])) })
            server.enqueue(script.response())
            gateway(server).open(target(server), instanceId).socket.close()
            script.received.poll(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `instance choice prefers the explicit id, then delegated, then an editor, then the first`() {
        val both = inventory(endpoint("standalone", "s1") + "," + endpoint("editor", "e1"))

        assertEquals("""{"instanceId":"x9"}""", selectionFor(both, instanceId = "x9"))
        assertEquals("""{"instanceId":"s1"}""", selectionFor(inventory(endpoint("standalone", "s1") + "," + endpoint("editor", "e1"), delegated = "s1")))
        assertEquals("""{"instanceId":"e1"}""", selectionFor(both))
        assertEquals("""{"instanceId":"s1"}""", selectionFor(inventory(endpoint("standalone", "s1") + "," + endpoint("standalone", "s2"))))
    }

    @Test
    fun `an empty inventory fails and never asks the gateway to start a host`() = withServer { server ->
        val script = Script(onOpen = { it.send(inventory("")) })
        server.enqueue(script.response())

        assertFailsWith<GatewayException.NoEndpoints> { gateway(server).open(target(server)) }

        assertNull(script.received.poll(300, TimeUnit.MILLISECONDS), "nothing may be sent to the gateway")
    }

    @Test
    fun `a rejected selection surfaces the gateway's error`() = withServer { server ->
        server.enqueue(Script(onOpen = { it.send(inventory(endpoint("editor", "i1"))) }, onMessage = { ws, _ -> ws.send("""{"ok":false,"error":"endpoint gone"}""") }).response())

        val error = assertFailsWith<GatewayException.Rejected> { gateway(server).open(target(server)) }

        assertTrue(error.message!!.contains("endpoint gone"))
    }

    @Test
    fun `a socket closed mid handshake reports the close code`() = withServer { server ->
        server.enqueue(Script(onOpen = { it.close(1011, "host crashed") }).response())

        val error = assertFailsWith<SocketClosedException> { gateway(server).open(target(server)) }

        assertEquals(1011, error.code)
    }

    @Test
    fun `a silent gateway times out`() = withServer { server ->
        server.enqueue(Script().response())

        assertFailsWith<GatewayException.Timeout> { gateway(server, timeoutMs = 300).open(target(server)) }
    }

    @Test
    fun `an invalid inventory or acknowledgement is Malformed`() = withServer { server ->
        server.enqueue(Script(onOpen = { it.send("""{"userDataPath":"p","endpoints":[{"type":"bogus","pid":1,"instanceId":"i","endpointKind":"tcp","endpointLabel":"l"}]}""") }).response())
        server.enqueue(Script(onOpen = { it.send(inventory(endpoint("editor", "i1"))) }, onMessage = { ws, _ -> ws.send("""{"ok":true,"selected":{"type":"editor","instanceId":"i1","role":"secondary","lifecycle":"external"}}""") }).response())
        server.enqueue(Script(onOpen = { it.send("not json") }).response())

        repeat(3) { assertFailsWith<GatewayException.Malformed> { gateway(server).open(target(server)) } }
    }

    @Test
    fun `v5 opens the root route with the derived connection token and no handshake`() = withServer { server ->
        server.enqueue(Script(onOpen = { it.send("""{"jsonrpc":"2.0","method":"hello"}""") }).response())

        val connection = gateway(server).open(target(server, protocol = 5))

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/?tkn=" + ConnectionToken.derive("test-tunnel-1"), request.path)
        assertNull(connection.selected)
        assertEquals("""{"jsonrpc":"2.0","method":"hello"}""", connection.socket.receive())
        connection.socket.close()
    }

    @Test
    fun `a refused upgrade reports the HTTP status so the caller can refresh the token`() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(401))

        val error = assertFailsWith<UpgradeRejectedException> { gateway(server).open(target(server)) }

        assertEquals(401, error.httpCode)
    }

    @Test
    fun `incoming completes on a clean close and throws on an abnormal one`() = withServer { server ->
        server.enqueue(Script(onOpen = { it.send("a"); it.send("b"); it.close(1000, "bye") }).response())
        server.enqueue(Script(onOpen = { it.send("a"); it.close(1011, "boom") }).response())

        val clean = gateway(server).open(target(server, protocol = 5)).socket
        assertEquals(listOf("a", "b"), clean.incoming.toList())

        val abnormal = gateway(server).open(target(server, protocol = 5)).socket
        val error = assertFailsWith<SocketClosedException> { abnormal.incoming.toList() }
        assertEquals(1011, error.code)
    }

    @Test
    fun `url building maps the scheme, fills the port and trims the trailing slash`() {
        assertEquals("wss://abc-31546.usw2.devtunnels.ms/agent-host/select", TunnelGateway.agentHostUrl("https://abc-{port}.usw2.devtunnels.ms/", 31546, "/agent-host/select"))
        assertEquals("ws://127.0.0.1:9/?tkn=t", TunnelGateway.agentHostUrl("http://127.0.0.1:{port}", 9, "/?tkn=t"))
        assertFailsWith<GatewayException.Malformed> { TunnelGateway.agentHostUrl("https://no-placeholder.devtunnels.ms/", 1, "/") }
        assertFailsWith<GatewayException.Malformed> { TunnelGateway.agentHostUrl("ftp://x-{port}/", 1, "/") }
    }

    @Test
    fun `a parsed inventory keeps optional fields`() {
        val parsed = TunnelGateway.parseInventory(
            """{"userDataPath":"p","delegatedInstanceId":"d","endpoints":[{"type":"standalone","pid":7,"instanceId":"i","quality":"stable","tunnelName":"t","endpointKind":"tcp","endpointLabel":"127.0.0.1:1"}]}""",
        )

        assertEquals("d", parsed.delegatedInstanceId)
        assertEquals("stable", parsed.endpoints.single().quality)
        assertNotNull(parsed.endpoints.single().tunnelName)
    }
}
