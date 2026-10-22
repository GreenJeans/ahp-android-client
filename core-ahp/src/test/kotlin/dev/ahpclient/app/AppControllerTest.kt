package dev.ahpclient.app

import dev.ahpclient.auth.GitHubDeviceAuth
import dev.ahpclient.auth.GitHubToken
import dev.ahpclient.auth.Secret
import dev.ahpclient.security.FakeBox
import dev.ahpclient.security.FakeStore
import dev.ahpclient.security.TokenVault
import dev.ahpclient.security.VaultLoad
import dev.ahpclient.state.Backoff
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.HostConnector
import dev.ahpclient.state.HostScript
import dev.ahpclient.state.HostSession
import dev.ahpclient.state.ScriptedConnector
import dev.ahpclient.state.SESSION_C
import dev.ahpclient.state.fixture
import dev.ahpclient.tunnel.DevTunnelsClient
import dev.ahpclient.tunnel.DevTunnelsException
import dev.ahpclient.tunnel.TunnelInfo
import com.microsoft.agenthostprotocol.generated.Implementation
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

private class FakeLastHost(var id: String? = null) : LastHostStore {
    override suspend fun get(): String? = id

    override suspend fun set(tunnelId: String) {
        id = tunnelId
    }
}

private val deviceCode = """{"device_code":"dc","user_code":"ABCD-1234","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}"""
private val pending = """{"error":"authorization_pending"}"""
private val approved = """{"access_token":"ghu_secret","token_type":"bearer","expires_in":28800,"refresh_token":"ghr_secret"}"""

@OptIn(ExperimentalCoroutinesApi::class)
class AppControllerTest {
    private val clientInfo = Implementation(name = "ahp-android-client-test", version = "0.0.1")
    private val savedToken = GitHubToken(Secret("ghu_saved"), Secret("ghr_saved"), null)

    private class Rig(
        val server: MockWebServer,
        val controller: AppController,
        val vault: TokenVault,
        val box: FakeBox,
        val store: FakeStore,
        val lastHost: FakeLastHost,
        val tokensSeenByHost: MutableList<String>,
        val created: MutableList<HostSession>,
        val connectors: MutableList<ScriptedConnector>,
    ) {
        val state get() = controller.state.value
    }

    private fun MockWebServer.json(body: String, code: Int = 200) =
        enqueue(MockResponse().setResponseCode(code).setBody(body).addHeader("Content-Type", "application/json"))

    private suspend fun TestScope.withRig(
        script: () -> HostScript = { HostScript() },
        now: () -> Instant = { Instant.parse("2026-10-22T12:00:00Z") },
        devTunnelsUrl: ((MockWebServer) -> String)? = null,
        githubUrl: ((MockWebServer) -> String)? = null,
        block: suspend Rig.() -> Unit,
    ) = MockWebServer().use { server ->
        server.start()
        val box = FakeBox()
        val store = FakeStore()
        val vault = TokenVault(box, store)
        val lastHost = FakeLastHost()
        val seen = mutableListOf<String>()
        val created = mutableListOf<HostSession>()
        val connectors = mutableListOf<ScriptedConnector>()
        val factory = HostSessionFactory { tunnel: TunnelInfo, accessToken ->
            val scripted = ScriptedConnector(script()).also { connectors += it }
            val connector = HostConnector { clientId, seq, scope: CoroutineScope ->
                seen += accessToken().reveal()
                scripted.connect(clientId, seq, scope)
            }
            HostSession(connector, backgroundScope, clientInfo, Backoff(), clientId = "client-${tunnel.tunnelId}").also { created += it }
        }
        val controller = AppController(
            auth = GitHubDeviceAuth(baseUrl = githubUrl?.invoke(server) ?: server.url("/").toString(), now = now),
            vault = vault,
            devTunnels = DevTunnelsClient(serviceUrl = devTunnelsUrl?.invoke(server) ?: server.url("/").toString()),
            lastHost = lastHost,
            sessions = factory,
            scope = backgroundScope,
            now = now,
        )
        Rig(server, controller, vault, box, store, lastHost, seen, created, connectors).block()
    }

    private suspend fun Rig.await(match: (AppState) -> Boolean): AppState = controller.state.first(match)

    private suspend fun Rig.awaitHosts(): HostList = (await { it is AppState.Hosts && it.list !is HostList.Loading } as AppState.Hosts).list

    private fun Rig.loaded(): List<HostEntry> = ((state as AppState.Hosts).list as HostList.Loaded).hosts

    /** Channels the app asked the host to stop sending, in order. */
    private fun Rig.unsubscribed(): List<String> = connectors.single().sockets.single().sent
        .filter { it["method"]?.jsonPrimitive?.content == "unsubscribe" }
        .map { it["params"]!!.jsonObject["channel"]!!.jsonPrimitive.content }

    /** Signs in from the vault, connects to the first host and waits until the connection is online. */
    private suspend fun Rig.connectToFirstHost(): AppState.Connected {
        vault.save(savedToken)
        server.json(fixture("list-tunnels.json"))
        controller.start()
        awaitHosts()
        controller.connect(loaded().first().tunnel)
        val connected = assertIs<AppState.Connected>(state)
        connected.session.connection.first { it == ConnState.Online }
        return connected
    }

    @Test
    fun `starts signed out when nothing is saved`() = runTest {
        withRig {
            controller.start()
            runCurrent()

            assertEquals(AppState.SignedOut(SignInStep.Idle, null), state)
        }
    }

    @Test
    fun `a saved sign in goes straight to the host list, online first`() = runTest {
        withRig {
            vault.save(savedToken)
            server.json(fixture("list-tunnels.json"))

            controller.start()
            val hosts = awaitHosts()

            assertEquals("github ghu_saved", server.takeRequest().getHeader("Authorization"))
            assertEquals(listOf("dev-box", "old-laptop"), assertIs<HostList.Loaded>(hosts).hosts.map { it.tunnel.name })
            assertTrue(loaded().none { it.isLastUsed })
        }
    }

    @Test
    fun `the last used host sorts first and is marked`() = runTest {
        withRig {
            vault.save(savedToken)
            lastHost.id = "test-tunnel-2"
            server.json(fixture("list-tunnels.json"))

            controller.start()
            awaitHosts()

            assertEquals(listOf("old-laptop", "dev-box"), loaded().map { it.tunnel.name })
            assertEquals(listOf(true, false), loaded().map { it.isLastUsed })
        }
    }

    @Test
    fun `an empty host list explains itself instead of showing nothing`() = runTest {
        withRig {
            vault.save(savedToken)
            server.json("""{"value":[]}""")

            controller.start()

            assertEquals(HostList.Empty, awaitHosts())
        }
    }

    @Test
    fun `an expired saved token is wiped and sign in is required`() = runTest {
        withRig(now = { Instant.parse("2026-10-23T12:00:00Z") }) {
            vault.save(GitHubToken(Secret("old"), null, Instant.parse("2026-10-22T04:00:00Z")))

            controller.start()
            runCurrent()

            val out = assertIs<AppState.SignedOut>(state)
            assertTrue(out.notice!!.contains("expired"))
            assertEquals(VaultLoad.Absent, vault.load())
            assertEquals(0, server.requestCount, "no request is made with a token known to be expired")
        }
    }

    @Test
    fun `an unreadable saved token is reported and removed`() = runTest {
        withRig {
            vault.save(savedToken)
            box.keyExists = false

            controller.start()
            runCurrent()

            assertTrue(assertIs<AppState.SignedOut>(state).notice!!.contains("removed"))
            assertNull(store.blob)
        }
    }

    @Test
    fun `a storage error while reading is not treated as a lost token`() = runTest {
        withRig {
            vault.save(savedToken)
            store.readFailure = IOException("disk busy")

            controller.start()
            runCurrent()

            assertTrue(assertIs<AppState.SignedOut>(state).notice!!.contains("could not be read"))
            assertEquals(0, store.deletes)
        }
    }

    @Test
    fun `signing in shows the code, waits, saves the token and lists hosts`() = runTest {
        withRig {
            server.json(deviceCode)
            server.json(pending)
            server.json(approved)
            server.json(fixture("list-tunnels.json"))
            controller.start()
            runCurrent()

            controller.signIn()
            val waiting = assertIs<AppState.SignedOut>(await { it is AppState.SignedOut && it.step is SignInStep.AwaitingApproval })
            val step = assertIs<SignInStep.AwaitingApproval>(waiting.step)
            assertEquals("ABCD-1234", step.userCode)
            assertEquals("https://github.com/login/device", step.verificationUri)
            assertEquals(Instant.parse("2026-10-22T12:15:00Z"), step.expiresAt)

            awaitHosts()

            val saved = assertIs<VaultLoad.Present>(vault.load()).token
            assertEquals("ghu_secret", saved.access.reveal())
            assertEquals("ghr_secret", saved.refresh?.reveal())
            assertEquals(Instant.parse("2026-10-22T20:00:00Z"), (state as AppState.Hosts).tokenExpiresAt)
            assertFalse("$state".contains("ghu_secret"))
        }
    }

    @Test
    fun `a denied or expired code shows a message and can be retried`() = runTest {
        withRig {
            server.json(deviceCode)
            server.json("""{"error":"access_denied"}""")
            server.json(deviceCode)
            server.json("""{"error":"expired_token"}""")
            server.json(deviceCode)
            server.json(approved)
            server.json(fixture("list-tunnels.json"))
            controller.start()
            runCurrent()

            controller.signIn()
            val denied = assertIs<SignInStep.Failed>((await { it is AppState.SignedOut && it.step is SignInStep.Failed } as AppState.SignedOut).step)
            assertTrue(denied.message.contains("denied"))

            controller.signIn()
            val expired = assertIs<SignInStep.Failed>((await { it is AppState.SignedOut && (it.step as? SignInStep.Failed)?.message?.contains("expired") == true } as AppState.SignedOut).step)
            assertTrue(expired.message.contains("Start again"))

            controller.signIn()
            assertIs<HostList.Loaded>(awaitHosts())
        }
    }

    @Test
    fun `an unreachable GitHub is reported as a connection problem`() = runTest {
        withRig(githubUrl = { "http://127.0.0.1:1/" }) {
            controller.start()
            runCurrent()

            controller.signIn()
            val failed = assertIs<SignInStep.Failed>((await { it is AppState.SignedOut && it.step is SignInStep.Failed } as AppState.SignedOut).step)

            assertTrue(failed.message.contains("Could not reach GitHub"))
        }
    }

    @Test
    fun `cancelling while waiting returns to the start and stops polling`() = runTest {
        withRig {
            server.json(deviceCode)
            server.json(pending)
            controller.start()
            runCurrent()
            controller.signIn()
            await { it is AppState.SignedOut && it.step is SignInStep.AwaitingApproval }

            controller.cancelSignIn()
            runCurrent()

            assertEquals(AppState.SignedOut(SignInStep.Idle, null), state)
            assertTrue(server.requestCount <= 2, "polling stopped; requests=${server.requestCount}")
        }
    }

    @Test
    fun `asking to sign in again while a code is showing does nothing`() = runTest {
        withRig {
            server.json(deviceCode)
            server.json(pending)
            controller.start()
            runCurrent()
            controller.signIn()
            val before = await { it is AppState.SignedOut && it.step is SignInStep.AwaitingApproval }

            controller.signIn()
            runCurrent()

            assertEquals(before, state)
            controller.cancelSignIn()
        }
    }

    @Test
    fun `a rejected sign in at the host list wipes the token and returns to sign in`() = runTest {
        withRig {
            vault.save(savedToken)
            server.json("{}", code = 401)

            controller.start()
            val out = assertIs<AppState.SignedOut>(await { it is AppState.SignedOut })

            assertTrue(out.notice!!.contains("no longer accepts"))
            assertEquals(VaultLoad.Absent, vault.load())
        }
    }

    @Test
    fun `a forbidden response points at SSO and refreshing recovers`() = runTest {
        withRig {
            vault.save(savedToken)
            server.json("{}", code = 403)
            server.json(fixture("list-tunnels.json"))

            controller.start()
            val failed = assertIs<HostList.Failed>(awaitHosts())
            assertTrue(failed.message.contains("SSO"))

            controller.refreshHosts()
            assertIs<HostList.Loaded>(awaitHosts())
        }
    }

    @Test
    fun `a server error and an unreachable service are shown as messages`() = runTest {
        withRig {
            vault.save(savedToken)
            server.json("{}", code = 503)
            controller.start()

            assertTrue(assertIs<HostList.Failed>(awaitHosts()).message.contains("503"))
        }
        withRig(devTunnelsUrl = { "http://127.0.0.1:1/" }) {
            vault.save(savedToken)
            controller.start()

            assertTrue(assertIs<HostList.Failed>(awaitHosts()).message.contains("Could not reach"))
        }
    }

    @Test
    fun `connecting opens a live session with the signed in token and remembers the host`() = runTest {
        withRig {
            vault.save(savedToken)
            server.json(fixture("list-tunnels.json"))
            controller.start()
            awaitHosts()
            val host = loaded().first().tunnel

            controller.connect(host)
            val connected = assertIs<AppState.Connected>(state)
            connected.session.connection.first { it == ConnState.Online }
            runCurrent()

            assertEquals(host, connected.host)
            assertEquals(listOf("ghu_saved"), tokensSeenByHost)
            assertEquals("test-tunnel-1", lastHost.id)
            assertEquals(6, connected.session.root.sessions.value.size)
        }
    }

    @Test
    fun `connecting is ignored unless the host list is showing`() = runTest {
        withRig {
            controller.start()
            runCurrent()

            controller.connect(TunnelInfo("t", "usw2", "n", 6, 1, emptyList()))

            assertTrue(created.isEmpty())
            assertIs<AppState.SignedOut>(state)
        }
    }

    @Test
    fun `losing the sign in while connected returns to sign in and wipes the token`() = runTest {
        withRig(script = { HostScript(failure = DevTunnelsException.AuthExpired()) }) {
            vault.save(savedToken)
            server.json(fixture("list-tunnels.json"))
            controller.start()
            awaitHosts()

            controller.connect(loaded().first().tunnel)
            val out = assertIs<AppState.SignedOut>(await { it is AppState.SignedOut })

            assertTrue(out.notice!!.contains("no longer accepts"))
            assertEquals(VaultLoad.Absent, vault.load())
            assertEquals(ConnState.Stopped, created.single().connection.value)
        }
    }

    @Test
    fun `opening a session shows its live state, and going back unsubscribes and stays connected`() = runTest {
        withRig {
            val connected = connectToFirstHost()

            controller.openSession(SESSION_C)
            val open = assertIs<AppState.Connected>(state).open!!
            open.store.state.first { it != null }
            runCurrent()

            assertEquals(SESSION_C, open.uri)
            assertEquals("Session C", open.store.state.value!!.title)
            assertEquals(emptyList(), unsubscribed())

            controller.closeSession()
            runCurrent()

            assertNull(assertIs<AppState.Connected>(state).open)
            assertSame(connected.session, assertIs<AppState.Connected>(state).session)
            assertEquals(listOf(SESSION_C), unsubscribed())
        }
    }

    @Test
    fun `a second open while one is showing is ignored, so one back releases the subscription`() = runTest {
        withRig {
            connectToFirstHost()

            controller.openSession(SESSION_C)
            controller.openSession("ahp-session:/other")
            assertEquals(SESSION_C, assertIs<AppState.Connected>(state).open!!.uri)
            controller.closeSession()
            runCurrent()

            assertEquals(listOf(SESSION_C), unsubscribed())
        }
    }

    @Test
    fun `opening and closing a session do nothing unless connected`() = runTest {
        withRig {
            controller.start()
            runCurrent()

            controller.openSession(SESSION_C)
            controller.closeSession()

            assertIs<AppState.SignedOut>(state)
            assertTrue(created.isEmpty())
        }
    }

    @Test
    fun `closing with nothing open does nothing`() = runTest {
        withRig {
            connectToFirstHost()

            controller.closeSession()
            runCurrent()

            assertNull(assertIs<AppState.Connected>(state).open)
            assertEquals(emptyList(), unsubscribed())
        }
    }

    @Test
    fun `leaving the host with a session open returns to the host list`() = runTest {
        withRig {
            server.json(fixture("list-tunnels.json"))
            connectToFirstHost()
            controller.openSession(SESSION_C)

            controller.disconnect()
            val list = awaitHosts()

            assertIs<HostList.Loaded>(list)
            assertEquals(ConnState.Stopped, created.single().connection.value)
        }
    }

    @Test
    fun `losing the sign in with a session open returns to sign in`() = runTest {
        withRig(script = { HostScript(failure = DevTunnelsException.AuthExpired()) }) {
            vault.save(savedToken)
            server.json(fixture("list-tunnels.json"))
            controller.start()
            awaitHosts()

            controller.connect(loaded().first().tunnel)
            controller.openSession(SESSION_C)
            val out = assertIs<AppState.SignedOut>(await { it is AppState.SignedOut })

            assertTrue(out.notice!!.contains("no longer accepts"))
            assertEquals(VaultLoad.Absent, vault.load())
        }
    }

    @Test
    fun `focusing a session opens it, replaces another open one, and ignores unknown or repeated requests`() = runTest {
        withRig {
            connectToFirstHost()
            val other = (state as AppState.Connected).session.root.sessions.value.first { it.resource != SESSION_C }.resource

            controller.focusSession("ahp-session:/not-on-this-host")
            assertNull(assertIs<AppState.Connected>(state).open)

            controller.focusSession(SESSION_C)
            assertEquals(SESSION_C, assertIs<AppState.Connected>(state).open!!.uri)

            controller.focusSession(SESSION_C)
            assertEquals(SESSION_C, assertIs<AppState.Connected>(state).open!!.uri)

            controller.focusSession(other)
            runCurrent()
            assertEquals(other, assertIs<AppState.Connected>(state).open!!.uri)
            assertEquals(listOf(SESSION_C), unsubscribed())
        }
    }

    @Test
    fun `focusing a session does nothing unless connected`() = runTest {
        withRig {
            controller.start()
            runCurrent()

            controller.focusSession(SESSION_C)

            assertIs<AppState.SignedOut>(state)
        }
    }

    @Test
    fun `other connection failures stay on the connected screen so the reason can be shown`() = runTest {
        withRig(script = { HostScript(failure = DevTunnelsException.TunnelNotFound("test-tunnel-1")) }) {
            vault.save(savedToken)
            server.json(fixture("list-tunnels.json"))
            controller.start()
            awaitHosts()

            controller.connect(loaded().first().tunnel)
            val connected = assertIs<AppState.Connected>(state)
            val failed = connected.session.connection.first { it is ConnState.Failed }
            runCurrent()

            assertIs<DevTunnelsException.TunnelNotFound>((failed as ConnState.Failed).cause)
            assertIs<AppState.Connected>(state)
            assertEquals(VaultLoad.Present::class, vault.load()::class, "the token is kept: only the host is gone")
        }
    }

    @Test
    fun `disconnecting stops the session and reloads the host list`() = runTest {
        withRig {
            vault.save(savedToken)
            server.json(fixture("list-tunnels.json"))
            server.json(fixture("list-tunnels.json"))
            controller.start()
            awaitHosts()
            controller.connect(loaded().first().tunnel)
            created.single().connection.first { it == ConnState.Online }

            controller.disconnect()
            val list = awaitHosts()

            assertIs<HostList.Loaded>(list)
            assertEquals(ConnState.Stopped, created.single().connection.value)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `signing out while connected stops the session and wipes the token`() = runTest {
        withRig {
            vault.save(savedToken)
            server.json(fixture("list-tunnels.json"))
            controller.start()
            awaitHosts()
            controller.connect(loaded().first().tunnel)
            created.single().connection.first { it == ConnState.Online }

            controller.signOut()
            await { it is AppState.SignedOut }
            runCurrent()

            assertEquals(AppState.SignedOut(SignInStep.Idle, null), state)
            assertEquals(ConnState.Stopped, created.single().connection.value)
            assertEquals(VaultLoad.Absent, vault.load())
            assertFalse(box.keyExists)
        }
    }
}

class SafeLinksTest {
    @Test
    fun `only https github pages are opened`() {
        assertTrue(SafeLinks.isSafeVerificationUri("https://github.com/login/device"))
        assertTrue(SafeLinks.isSafeVerificationUri("https://github.com/login/device?user_code=ABCD-1234"))
    }

    @Test
    fun `lookalikes, other schemes, credentials and ports are refused`() {
        listOf(
            "http://github.com/login/device",
            "https://github.com.evil.example/login/device",
            "https://evilgithub.com/login/device",
            "https://github.com@evil.example/login/device",
            "https://user:pw@github.com/login/device",
            "https://github.com:8443/login/device",
            "javascript:alert(1)",
            "intent://github.com#Intent;scheme=https;end",
            "file:///sdcard/x",
            "github.com/login/device",
            "",
            "https://",
            "not a uri at all",
        ).forEach { assertFalse(SafeLinks.isSafeVerificationUri(it), it) }
    }
}
