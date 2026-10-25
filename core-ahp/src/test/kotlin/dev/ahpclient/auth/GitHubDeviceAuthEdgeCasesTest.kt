package dev.ahpclient.auth

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy

@OptIn(ExperimentalCoroutinesApi::class)
class GitHubDeviceAuthEdgeCasesTest {
    private val code = DeviceCode(Secret("dev-code"), "ABCD-1234", "https://github.com/login/device", 900, 5)

    @Test
    fun `a dropped connection while waiting does not abort a sign-in the user is still completing`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.enqueue(MockResponse().setBody("""{"access_token":"ghu_secret","token_type":"bearer"}""").addHeader("Content-Type", "application/json"))
            val auth = GitHubDeviceAuth(baseUrl = server.url("/").toString(), now = { Instant.parse("2026-10-22T12:00:00Z") })

            val result = auth.poll(code)

            assertIs<DeviceFlowResult.Success>(result)
            assertTrue(currentTime >= 10_000, "it kept polling after the failed attempts, waiting between them")
        }
    }

    @Test
    fun `a refresh that cannot reach GitHub fails as an IO error, not as a refusal of the token`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val auth = GitHubDeviceAuth(baseUrl = server.url("/").toString())

            assertFailsWith<java.io.IOException> { auth.refresh(Secret("ghr_old")) }
        }
    }

    @Test
    fun `a refresh answered with a page that is not JSON is not a refusal either`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>"))
            val auth = GitHubDeviceAuth(baseUrl = server.url("/").toString())

            assertFailsWith<GitHubAuthException> { auth.refresh(Secret("ghr_old")) }
        }
    }

    @Test
    fun `a page that is not JSON is reported as such, with the HTTP status`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>"))
            val auth = GitHubDeviceAuth(baseUrl = server.url("/").toString())

            val failure = assertFailsWith<GitHubAuthException> { auth.start() }

            assertTrue(failure.message!!.contains("non-JSON"), failure.message)
            assertTrue(failure.message!!.contains("502"), failure.message)
        }
    }
}
