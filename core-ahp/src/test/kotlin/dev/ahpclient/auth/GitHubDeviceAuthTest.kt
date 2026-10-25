package dev.ahpclient.auth

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

@OptIn(ExperimentalCoroutinesApi::class)
class GitHubDeviceAuthTest {
    private val fixedNow = Instant.parse("2026-10-22T12:00:00Z")

    private fun withServer(block: suspend TestScope.(MockWebServer, GitHubDeviceAuth) -> Unit) = runTest {
        MockWebServer().use { server ->
            server.start()
            val auth = GitHubDeviceAuth(baseUrl = server.url("/").toString(), now = { fixedNow })
            block(server, auth)
        }
    }

    private fun MockWebServer.json(body: String) = enqueue(MockResponse().setBody(body).addHeader("Content-Type", "application/json"))

    private val code = DeviceCode(Secret("dev-code"), "ABCD-1234", "https://github.com/login/device", 900, 5)

    @Test
    fun `start posts the client id and parses the device code`() = withServer { server, auth ->
        server.json("""{"device_code":"dc","user_code":"9D05-B2C1","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}""")

        val result = auth.start()

        val request = server.takeRequest()
        assertEquals("/login/device/code", request.path)
        assertEquals("application/json", request.getHeader("Accept"))
        assertTrue(request.body.readUtf8().contains("client_id=$DEV_TUNNELS_GITHUB_APP_CLIENT_ID"))
        assertEquals("9D05-B2C1", result.userCode)
        assertEquals(900, result.expiresInSeconds)
        assertEquals(5, result.intervalSeconds)
    }

    @Test
    fun `start fails clearly when GitHub refuses the flow`() = withServer { server, auth ->
        server.json("""{"error":"device_flow_disabled","error_description":"Device Flow must be explicitly enabled"}""")

        val error = assertFailsWith<GitHubAuthException> { auth.start() }

        assertTrue(error.message!!.contains("device_flow_disabled"))
    }

    @Test
    fun `poll returns the token after pending then slow down`() = withServer { server, auth ->
        server.json("""{"error":"authorization_pending"}""")
        server.json("""{"error":"slow_down","interval":10}""")
        server.json("""{"access_token":"ghu_secret","token_type":"bearer","refresh_token":"ghr_secret","expires_in":28800}""")

        val result = auth.poll(code)

        val success = assertIs<DeviceFlowResult.Success>(result)
        assertEquals("ghu_secret", success.token.access.reveal())
        assertEquals("ghr_secret", success.token.refresh?.reveal())
        assertEquals(fixedNow.plusSeconds(28800), success.token.expiresAt)
        // 5 s, then 5 s again (pending), then the 10 s the server asked for after slow_down
        assertEquals(20_000L, currentTime)
        val request = server.takeRequest()
        assertTrue(request.body.readUtf8().contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"))
    }

    @Test
    fun `slow down without an interval adds five seconds`() = withServer { server, auth ->
        server.json("""{"error":"slow_down"}""")
        server.json("""{"access_token":"ghu_secret","token_type":"bearer"}""")

        val result = auth.poll(code)

        val success = assertIs<DeviceFlowResult.Success>(result)
        assertNull(success.token.refresh)
        assertNull(success.token.expiresAt)
        assertEquals(15_000L, currentTime)
    }

    @Test
    fun `poll reports an expired code`() = withServer { server, auth ->
        server.json("""{"error":"expired_token"}""")

        assertEquals(DeviceFlowResult.Expired, auth.poll(code))
    }

    @Test
    fun `poll reports a denied request`() = withServer { server, auth ->
        server.json("""{"error":"access_denied"}""")

        assertEquals(DeviceFlowResult.Denied, auth.poll(code))
    }

    @Test
    fun `poll surfaces an unexpected error`() = withServer { server, auth ->
        server.json("""{"error":"incorrect_client_credentials","error_description":"bad client"}""")

        val result = assertIs<DeviceFlowResult.Failed>(auth.poll(code))

        assertEquals("incorrect_client_credentials", result.error)
        assertEquals("bad client", result.description)
    }

    @Test
    fun `poll gives up when the code lifetime runs out`() = withServer { server, auth ->
        repeat(3) { server.json("""{"error":"authorization_pending"}""") }

        val result = auth.poll(code.copy(expiresInSeconds = 15))

        assertEquals(DeviceFlowResult.Expired, result)
        assertEquals(15_000L, currentTime)
    }

    @Test
    fun `refresh trades the refresh token for a new pair and sends no client secret`() = withServer { server, auth ->
        server.json("""{"access_token":"ghu_new","token_type":"bearer","refresh_token":"ghr_new","expires_in":28800,"refresh_token_expires_in":15897600}""")

        val result = auth.refresh(Secret("ghr_old"))

        val renewed = assertIs<RefreshResult.Renewed>(result)
        assertEquals("ghu_new", renewed.token.access.reveal())
        assertEquals("ghr_new", renewed.token.refresh?.reveal())
        assertEquals(fixedNow.plusSeconds(28800), renewed.token.expiresAt)
        val request = server.takeRequest()
        assertEquals("/login/oauth/access_token", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("client_id=$DEV_TUNNELS_GITHUB_APP_CLIENT_ID"))
        assertTrue(body.contains("grant_type=refresh_token"))
        assertTrue(body.contains("refresh_token=ghr_old"))
        assertFalse(body.contains("client_secret"), "a device-flow token is refreshed without the app's secret: this app has none")
    }

    @Test
    fun `refresh reports a refusal with GitHub's reason`() = withServer { server, auth ->
        server.json("""{"error":"bad_refresh_token","error_description":"The refresh token passed is incorrect or expired."}""")

        val result = auth.refresh(Secret("ghr_old"))

        assertEquals(RefreshResult.Rejected("bad_refresh_token"), result)
    }

    @Test
    fun `refresh does not mistake a refusal with no reason for success`() = withServer { server, auth ->
        server.json("""{}""")

        assertEquals(RefreshResult.Rejected("unexpected response"), auth.refresh(Secret("ghr_old")))
    }

    @Test
    fun `secrets never appear in string output`() {
        val token = GitHubToken(Secret("ghu_secret"), Secret("ghr_secret"), null)

        val rendered = "$token $code"

        assertFalse(rendered.contains("ghu_secret"))
        assertFalse(rendered.contains("ghr_secret"))
        assertFalse(rendered.contains("dev-code"))
    }
}
