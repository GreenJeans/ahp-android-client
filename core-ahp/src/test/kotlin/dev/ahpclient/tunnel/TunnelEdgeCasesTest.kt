package dev.ahpclient.tunnel

import dev.ahpclient.auth.Secret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString.Companion.encodeUtf8

class TunnelEdgeCasesTest {
    private val token = Secret("gh-user-token")

    private fun MockWebServer.json(body: String, code: Int = 200) =
        enqueue(MockResponse().setResponseCode(code).setBody(body).addHeader("Content-Type", "application/json"))

    @Test
    fun `a tunnel list of the wrong shape is Malformed, not a crash`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.json("""{"value":"not a list"}""")

            assertFailsWith<DevTunnelsException.Malformed> { DevTunnelsClient(serviceUrl = server.url("/").toString()).listAgentHostTunnels(token) }
        }
    }

    @Test
    fun `an acknowledgement that says ok but names no endpoint is Malformed`() {
        assertFailsWith<GatewayException.Malformed> { TunnelGateway.parseAck("""{"ok":true}""") }
        assertFailsWith<GatewayException.Malformed> { TunnelGateway.parseAck("""{"ok":true,"selected":{"type":"editor"}}""") }
        assertFailsWith<GatewayException.Malformed> { TunnelGateway.parseAck("""{"ok":"yes"}""") }
    }

    @Test
    fun `a binary frame that carries UTF-8 is delivered as text`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send("""{"hello":"binary"}""".encodeUtf8())
                        }

                        // Without answering the client's close, the server's queue never drains and closing the server times out.
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, null)
                        }
                    },
                ),
            )
            server.start()

            val socket = OkHttpSocketOpener().open(server.url("/").toString().replaceFirst("http", "ws"), emptyMap())
            val frame = withTimeout(10_000) { socket.receive() }
            socket.close()

            assertEquals("""{"hello":"binary"}""", frame)
            assertTrue(frame.startsWith("{"))
        }
    }
}
