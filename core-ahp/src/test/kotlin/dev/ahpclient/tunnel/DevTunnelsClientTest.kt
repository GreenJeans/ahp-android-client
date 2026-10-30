package dev.ahpclient.tunnel

import dev.ahpclient.auth.Secret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class DevTunnelsClientTest {
    private val token = Secret("gh-user-token")

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/$name")) { "missing fixture $name" }.bufferedReader().use { it.readText() }

    private fun withServer(block: suspend (MockWebServer, DevTunnelsClient) -> Unit) = runTest {
        MockWebServer().use { server ->
            server.start()
            block(server, DevTunnelsClient(serviceUrl = server.url("/").toString()))
        }
    }

    private fun MockWebServer.json(body: String, code: Int = 200) =
        enqueue(MockResponse().setResponseCode(code).setBody(body).addHeader("Content-Type", "application/json"))

    private val tunnel = TunnelInfo("test-tunnel-1", "usw2", "dev-box", 6, 1, listOf(31545, 31546))

    @Test
    fun `list sends the same query VS Code sends and authenticates with the github scheme`() = withServer { server, client ->
        server.json(fixture("list-tunnels.json"))

        client.listAgentHostTunnels(token)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("github gh-user-token", request.getHeader("Authorization"))
        val url = request.requestUrl!!
        assertEquals("/tunnels", url.encodedPath)
        assertEquals("true", url.queryParameter("includePorts"))
        assertEquals("connect", url.queryParameter("tokenScopes"))
        assertEquals(TUNNEL_LAUNCHER_LABEL, url.queryParameter("labels"))
        assertEquals("true", url.queryParameter("allLabels"))
        assertEquals("true", url.queryParameter("global"))
        assertEquals("2023-09-27-preview", url.queryParameter("api-version"))
    }

    @Test
    fun `list flattens regions and keeps protocol 5 and newer only`() = withServer { server, client ->
        server.json(fixture("list-tunnels.json"))

        val tunnels = client.listAgentHostTunnels(token)

        // test-tunnel-3 has no protocol tag (defaults to 2) and is dropped
        assertEquals(listOf("test-tunnel-1", "test-tunnel-2"), tunnels.map { it.tunnelId })
        val first = tunnels[0]
        assertEquals("dev-box", first.name)
        assertEquals(6, first.protocolVersion)
        assertTrue(first.isOnline)
        assertTrue(first.usesGateway)
        assertEquals(listOf(31545, 31546), first.ports)
    }

    @Test
    fun `host connection count may be a number`() = withServer { server, client ->
        server.json(fixture("list-tunnels.json"))

        val offline = client.listAgentHostTunnels(token).first { it.tunnelId == "test-tunnel-2" }

        assertFalse(offline.isOnline)
        assertFalse(offline.usesGateway)
    }

    @Test
    fun `an empty listing is not an error`() = withServer { server, client ->
        server.json("""{"value":[]}""")

        assertEquals(emptyList(), client.listAgentHostTunnels(token))
    }

    @Test
    fun `401 maps to AuthExpired and 403 to Forbidden`() = withServer { server, client ->
        server.json("{}", code = 401)
        server.json("{}", code = 403)

        assertFailsWith<DevTunnelsException.AuthExpired> { client.listAgentHostTunnels(token) }
        assertFailsWith<DevTunnelsException.Forbidden> { client.listAgentHostTunnels(token) }
    }

    @Test
    fun `other statuses map to Http with the code`() = withServer { server, client ->
        server.json("{}", code = 503)

        assertEquals(503, assertFailsWith<DevTunnelsException.Http> { client.listAgentHostTunnels(token) }.code)
    }

    @Test
    fun `a body that is not json or has the wrong shape is Malformed, not a crash`() = withServer { server, client ->
        server.json("<html>gateway error</html>")
        server.json("""{"unexpected":true}""")
        server.json("""{"value":["not-an-object"]}""")

        repeat(3) { assertFailsWith<DevTunnelsException.Malformed> { client.listAgentHostTunnels(token) } }
    }

    @Test
    fun `resolve fetches the tunnel by id and returns the web endpoint and connect token`() = withServer { server, client ->
        server.json(fixture("get-tunnel.json"))

        val target = client.resolveConnectTarget(token, tunnel)

        val url = server.takeRequest().requestUrl!!
        assertEquals("/tunnels/test-tunnel-1", url.encodedPath)
        assertEquals("true", url.queryParameter("includePorts"))
        assertEquals("connect", url.queryParameter("tokenScopes"))
        assertEquals("2023-09-27-preview", url.queryParameter("api-version"))
        assertEquals("https://abc123-{port}.usw2.devtunnels.ms/", target.portUriFormat)
        assertEquals("connect-token-value", target.connectToken.reveal())
        assertEquals(6, target.tunnel.protocolVersion)
    }

    @Test
    fun `resolve fails clearly when the service returns no connect token or no web endpoint`() = withServer { server, client ->
        server.json("""{"tunnelId":"test-tunnel-1","clusterId":"usw2","endpoints":[{"portUriFormat":"https://x-{port}.devtunnels.ms/"}]}""")
        server.json("""{"tunnelId":"test-tunnel-1","clusterId":"usw2","accessTokens":{"connect":"t"},"endpoints":[]}""")

        assertTrue(assertFailsWith<DevTunnelsException.Malformed> { client.resolveConnectTarget(token, tunnel) }.message!!.contains("connect-scope token"))
        assertTrue(assertFailsWith<DevTunnelsException.Malformed> { client.resolveConnectTarget(token, tunnel) }.message!!.contains("web endpoint"))
    }

    @Test
    fun `resolve maps 404 to TunnelNotFound`() = withServer { server, client ->
        server.json("{}", code = 404)

        assertFailsWith<DevTunnelsException.TunnelNotFound> { client.resolveConnectTarget(token, tunnel) }
    }

    @Test
    fun `cluster host replaces the global label and leaves other hosts alone`() {
        val global = "https://global.rel.tunnels.api.visualstudio.com/".toHttpUrl()
        val local = "http://127.0.0.1:8080/".toHttpUrl()

        assertEquals("usw2.rel.tunnels.api.visualstudio.com", DevTunnelsClient.clusterUrl(global, "usw2").host)
        assertEquals("127.0.0.1", DevTunnelsClient.clusterUrl(local, "usw2").host)
    }

    @Test
    fun `tokens never appear in string output`() {
        val target = ConnectTarget(tunnel, "https://x-{port}.devtunnels.ms/", Secret("connect-token-value"))

        assertFalse("$target".contains("connect-token-value"))
    }

    @Test
    fun `parseTunnel uses description when label and name are absent or blank`() {
        fun parse(extra: String) = DevTunnelsClient.parseTunnel(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"tunnelId":"re1lw1zrrff4","clusterId":"usw2","labels":["vscode-server-launcher","protocolv6"]$extra}""",
            ) as kotlinx.serialization.json.JsonObject,
        )!!

        assertEquals("work-laptop", parse(""","name":"work-laptop"""").name)
        assertEquals("work-laptop", parse(""","name":"","description":"work-laptop"""").name)
        assertEquals("re1lw1zrrff4", parse(""","name":"  ","description":""""" ).name)
    }
}
