package dev.ahpclient.state

import dev.ahpclient.ahp.FakeSocket
import dev.ahpclient.ahp.result
import dev.ahpclient.auth.Secret
import dev.ahpclient.tunnel.DevTunnelsClient
import dev.ahpclient.tunnel.DevTunnelsException
import dev.ahpclient.tunnel.SocketOpener
import dev.ahpclient.tunnel.TextSocket
import dev.ahpclient.tunnel.TunnelGateway
import dev.ahpclient.tunnel.TunnelInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/** The production path from "a host was chosen" to "an AHP connection that can only read". */
@OptIn(ExperimentalCoroutinesApi::class)
class TunnelHostConnectorTest {
    private val tunnel = TunnelInfo("test-tunnel-1", "usw2", "dev-box", 6, 1, listOf(31545, 31546))
    private val inventory =
        """{"userDataPath":"/u","endpoints":[{"type":"editor","pid":1,"instanceId":"i1","endpointKind":"socket","endpointLabel":"x"}],"delegatedInstanceId":"i1"}"""
    private val ack = """{"ok":true,"selected":{"type":"editor","instanceId":"i1","role":"primary","lifecycle":"external"}}"""

    private class RecordingOpener(private val socket: FakeSocket) : SocketOpener {
        val opened = mutableListOf<Pair<String, Map<String, String>>>()

        override suspend fun open(url: String, headers: Map<String, String>): TextSocket {
            opened += url to headers
            return socket
        }
    }

    private fun tunnelJson(connectToken: String) =
        """{"tunnelId":"test-tunnel-1","clusterId":"usw2","labels":["dev-box","protocolv6","vscode-server-launcher"],"status":{"hostConnectionCount":{"current":1}},
           "endpoints":[{"portUriFormat":"https://abc123-{port}.usw2.devtunnels.ms/"}],"accessTokens":{"connect":"$connectToken"}}"""

    private fun MockWebServer.json(body: String, code: Int = 200) =
        enqueue(MockResponse().setResponseCode(code).setBody(body).addHeader("Content-Type", "application/json"))

    private fun withConnector(
        socket: FakeSocket = FakeSocket { method, id, _ -> if (method == "listSessions") result(id, """{"items":[]}""") else null },
        tokens: MutableList<String> = mutableListOf(),
        block: suspend TestScope.(TunnelHostConnector, RecordingOpener, MockWebServer, List<String>) -> Unit,
    ) = runTest {
        MockWebServer().use { server ->
            server.start()
            val opener = RecordingOpener(socket)
            val connector = TunnelHostConnector(
                DevTunnelsClient(serviceUrl = server.url("/").toString()),
                TunnelGateway(opener),
                tunnel,
                { Secret("gh-token-${tokens.size + 1}").also { tokens += it.reveal() } },
            )
            block(connector, opener, server, tokens)
        }
    }

    @Test
    fun `connecting fetches a fresh connect token, opens the gateway with it and returns a read-only connection`() {
        val socket = FakeSocket { method, id, _ -> if (method == "listSessions") result(id, """{"items":[]}""") else null }
        socket.serverSends(inventory)
        socket.serverSends(ack)
        withConnector(socket) { connector, opener, server, tokens ->
            server.json(tunnelJson("connect-token-1"))

            val connection = connector.connect("client-1", 0, backgroundScope)
            connection.listSessions()

            assertEquals("github gh-token-1", server.takeRequest().getHeader("Authorization"))
            assertEquals(listOf("gh-token-1"), tokens)
            val (url, headers) = opener.opened.single()
            assertTrue(url.startsWith("wss://abc123-31546.usw2.devtunnels.ms"), url)
            assertTrue(url.endsWith("/agent-host/select"), url)
            assertEquals("tunnel connect-token-1", headers["X-Tunnel-Authorization"])
            assertEquals("client-1", connection.clientId)
            assertEquals(listOf("listSessions"), socket.sentMethods)
        }
    }

    @Test
    fun `the sign-in token is asked for on every attempt, so a refreshed one is picked up`() {
        withConnector { connector, _, server, tokens ->
            server.json(tunnelJson("t1"))
            server.json(tunnelJson("t2"))
            val scope = backgroundScope
            runCatching { connector.connect("c", 0, scope) }
            runCatching { connector.connect("c", 0, scope) }

            assertEquals(listOf("gh-token-1", "gh-token-2"), tokens)
            assertEquals("github gh-token-2", server.let { it.takeRequest(); it.takeRequest().getHeader("Authorization") })
        }
    }

    @Test
    fun `a rejected sign-in surfaces as AuthExpired before any socket is opened`() {
        withConnector { connector, opener, server, _ ->
            server.json("{}", code = 401)

            assertFailsWith<DevTunnelsException.AuthExpired> { connector.connect("c", 0, backgroundScope) }

            assertEquals(emptyList(), opener.opened)
        }
    }

    @Test
    fun `a host the account no longer has surfaces as TunnelNotFound`() {
        withConnector { connector, _, server, _ ->
            server.json("{}", code = 404)

            assertFailsWith<DevTunnelsException.TunnelNotFound> { connector.connect("c", 0, backgroundScope) }
        }
    }
}
