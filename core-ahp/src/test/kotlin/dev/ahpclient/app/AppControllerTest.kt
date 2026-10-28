@file:OptIn(ExperimentalForInheritanceCoroutinesApi::class)

package dev.ahpclient.app

import dev.ahpclient.auth.GitHubDeviceAuth
import dev.ahpclient.auth.GitHubToken
import dev.ahpclient.auth.Secret
import dev.ahpclient.ahp.error
import dev.ahpclient.ahp.result
import dev.ahpclient.security.BlobStore
import dev.ahpclient.security.FakeBox
import dev.ahpclient.security.FakeStore
import dev.ahpclient.security.SessionListCache
import dev.ahpclient.security.TokenVault
import dev.ahpclient.security.VaultLoad
import dev.ahpclient.state.Backoff
import dev.ahpclient.state.CHAT_A
import dev.ahpclient.state.CHANGESET_A
import dev.ahpclient.state.ChangesetView
import dev.ahpclient.state.DiffResult
import dev.ahpclient.state.changesetFileJson
import dev.ahpclient.state.changesetStateJson
import dev.ahpclient.state.CHAT_B
import dev.ahpclient.state.actionFrame
import dev.ahpclient.state.CHAT_C
import dev.ahpclient.state.ApprovalGate
import dev.ahpclient.state.ApprovalRequest
import dev.ahpclient.state.ApprovalTarget
import dev.ahpclient.state.QuestionTarget
import dev.ahpclient.state.ChatInteractor
import dev.ahpclient.state.DispatchOutcome
import dev.ahpclient.state.InteractResult
import dev.ahpclient.state.ModelChoice
import dev.ahpclient.state.ManageResult
import dev.ahpclient.state.ConfigOffer
import dev.ahpclient.state.ConfigPick
import dev.ahpclient.state.SNAPSHOT_SEQ
import dev.ahpclient.state.echoOf
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.HostConnector
import dev.ahpclient.state.HostScript
import dev.ahpclient.state.HostSession
import dev.ahpclient.state.ScriptedConnector
import dev.ahpclient.state.SESSION_C
import dev.ahpclient.state.TERMINAL_REAL
import dev.ahpclient.state.TerminalView
import dev.ahpclient.state.terminalStateJson
import dev.ahpclient.state.chatSnapshotJson
import dev.ahpclient.state.chatSummaryJson
import dev.ahpclient.state.turnJson
import dev.ahpclient.state.fixture
import dev.ahpclient.tunnel.DevTunnelsClient
import dev.ahpclient.tunnel.DevTunnelsException
import dev.ahpclient.tunnel.TunnelInfo
import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.Implementation
import com.microsoft.agenthostprotocol.generated.ListSessionsResult
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy

private class FakeLastHost(var id: String? = null) : LastHostStore {
    var failReads = false
    var failWrites = false

    override suspend fun get(): String? {
        if (failReads) throw IOException("disk unavailable")
        return id
    }

    override suspend fun set(tunnelId: String) {
        if (failWrites) throw IOException("disk full")
        id = tunnelId
    }
}

/**
 * The controller's state with one thing added: the first swap attempt is preceded by [beforeFirstSwap], which changes the state the
 * way a host list loading on another thread would. That moment sits between `connect()` reading the state and writing to it, where no
 * ordinary test can reach, so [raced] also tells a test the seam was in the path.
 */
private class RacingState(
    private val inner: MutableStateFlow<AppState>,
    private val beforeFirstSwap: () -> Unit,
) : MutableStateFlow<AppState> by inner {
    var raced = false
        private set

    override fun compareAndSet(expect: AppState, update: AppState): Boolean {
        if (!raced) {
            raced = true
            beforeFirstSwap()
        }
        return inner.compareAndSet(expect, update)
    }
}

/** A blob store that counts what is written to it, to tell a save made at once from one made after a delay. */
private class RecordingStore : BlobStore {
    var blob: ByteArray? = null
    var writes = 0

    override suspend fun read(): ByteArray? = blob

    override suspend fun write(blob: ByteArray) {
        this.blob = blob
        writes++
    }

    override suspend fun delete() {
        blob = null
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
        val interaction: InteractionConsent,
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
        interaction: InteractionConsent = InteractionConsent(),
        approvalGate: ApprovalGate? = null,
        /** Runs inside the factory, before a session is returned: for changing the controller's state at the point a connect is half done. */
        onCreate: Rig.() -> Unit = {},
        /** Whether the controller may connect by itself (the Monitoring switch, in the app). Read each time it is asked. */
        autoConnect: suspend () -> Boolean = { false },
        /** Runs after the user's own sign-out has taken effect (Monitoring off, in the app). */
        afterSignOut: suspend () -> Unit = {},
        /** How long each connection to the host takes to come up, in the test's virtual time. */
        connectDelayMs: Long = 0,
        /** Where the sessions keep the host list between runs; the controller wipes it at sign-out. Each session reads and writes it under its own host. */
        sessionCache: SessionListCache? = null,
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
        var rig: Rig? = null
        val factory = HostSessionFactory { tunnel: TunnelInfo, accessToken ->
            // Enough scripts for a reconnect or two, which tests of the interaction setting cause on purpose.
            val scripted = ScriptedConnector(script(), script(), script()).also { connectors += it }
            val connector = HostConnector { clientId, seq, scope: CoroutineScope ->
                seen += accessToken().reveal()
                delay(connectDelayMs)
                scripted.policy = interaction.policy()
                scripted.connect(clientId, seq, scope)
            }
            HostSession(connector, backgroundScope, clientInfo, Backoff(), clientId = "client-${tunnel.tunnelId}", cache = sessionCache, cacheKey = tunnel.tunnelId).also {
                created += it
                rig?.onCreate()
            }
        }
        val auth = GitHubDeviceAuth(baseUrl = githubUrl?.invoke(server) ?: server.url("/").toString(), now = now)
        val tunnels = DevTunnelsClient(serviceUrl = devTunnelsUrl?.invoke(server) ?: server.url("/").toString())
        // With no gate given the controller's own default is used, which is the one a test of the default must see.
        val controller = if (approvalGate == null) {
            AppController(auth, vault, tunnels, lastHost, factory, backgroundScope, now, interaction, autoConnect = autoConnect, afterSignOut = afterSignOut, sessionCache = sessionCache)
        } else {
            AppController(auth, vault, tunnels, lastHost, factory, backgroundScope, now, interaction, approvalGate, autoConnect = autoConnect, afterSignOut = afterSignOut, sessionCache = sessionCache)
        }
        val built = Rig(server, controller, vault, box, store, lastHost, seen, created, connectors, interaction)
        rig = built
        built.block()
    }

    private suspend fun Rig.await(match: (AppState) -> Boolean): AppState = controller.state.first(match)

    private suspend fun Rig.awaitHosts(): HostList {
        await { it is AppState.Hosts }
        return controller.hosts.first { it.list !is HostList.Loading }.list
    }

    private fun Rig.loaded(): List<HostEntry> = (controller.hosts.value.list as HostList.Loaded).hosts

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
    fun `an expired saved token also removes the saved session list`() = runTest {
        val listStore = FakeStore()
        val cache = SessionListCache(FakeBox(), listStore)
        cache.save("test-tunnel-1", listedSessions)
        withRig(now = { Instant.parse("2026-10-23T12:00:00Z") }, sessionCache = cache) {
            vault.save(GitHubToken(Secret("old"), null, Instant.parse("2026-10-22T04:00:00Z")))

            controller.start()
            runCurrent()

            assertIs<AppState.SignedOut>(state)
            assertNull(listStore.blob, "no session title stays on the phone once the sign-in is gone")
        }
    }

    @Test
    fun `an unreadable saved token also removes the saved session list`() = runTest {
        val listStore = FakeStore()
        val cache = SessionListCache(FakeBox(), listStore)
        cache.save("test-tunnel-1", listedSessions)
        withRig(sessionCache = cache) {
            vault.save(savedToken)
            box.keyExists = false

            controller.start()
            runCurrent()

            assertIs<AppState.SignedOut>(state)
            assertNull(listStore.blob)
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
            assertEquals(Instant.parse("2026-10-22T20:00:00Z"), controller.hosts.value.tokenExpiresAt)
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

    private val renewed = """{"access_token":"ghu_new","token_type":"bearer","refresh_token":"ghr_new","expires_in":28800}"""
    private val refreshRefused = """{"error":"bad_refresh_token","error_description":"The refresh token passed is incorrect or expired."}"""
    private val renewalNow = Instant.parse("2026-10-23T12:00:00Z")

    /** The paths the app asked for, in order: tells a renewal from a host list. */
    private fun Rig.paths(): List<String> = (1..server.requestCount).map { server.takeRequest().path!! }

    @Test
    fun `an expired saved token that can be renewed is renewed, saved, and used to list the hosts`() = runTest {
        withRig(now = { renewalNow }) {
            vault.save(GitHubToken(Secret("ghu_old"), Secret("ghr_old"), Instant.parse("2026-10-22T04:00:00Z")))
            server.json(renewed)
            server.json(fixture("list-tunnels.json"))

            controller.start()
            awaitHosts()

            val renewal = server.takeRequest()
            assertEquals("/login/oauth/access_token", renewal.path)
            assertTrue(renewal.body.readUtf8().contains("refresh_token=ghr_old"))
            assertEquals("github ghu_new", server.takeRequest().getHeader("Authorization"))
            val saved = assertIs<VaultLoad.Present>(vault.load()).token
            assertEquals("ghu_new", saved.access.reveal())
            assertEquals("ghr_new", saved.refresh?.reveal(), "the refresh token was spent, so the new one is what is kept")
            val hosts = controller.hosts.value
            assertEquals(renewalNow.plusSeconds(28800), hosts.tokenExpiresAt)
            assertTrue(hosts.renews)
        }
    }

    @Test
    fun `a token about to run out is renewed when a host is connected to, and the new one is used for the connection`() = runTest {
        var clock = renewalNow
        withRig(now = { clock }) {
            vault.save(GitHubToken(Secret("ghu_saved"), Secret("ghr_saved"), renewalNow.plusSeconds(3600)))
            server.json(fixture("list-tunnels.json"))
            controller.start()
            awaitHosts()
            assertTrue(tokensSeenByHost.isEmpty())
            clock = renewalNow.plusSeconds(3600 - 120)
            server.json(renewed)

            controller.connect(loaded().first().tunnel)
            val connected = assertIs<AppState.Connected>(state)
            connected.session.connection.first { it == ConnState.Online }

            assertEquals(listOf("ghu_new"), tokensSeenByHost)
            assertEquals("ghr_new", assertIs<VaultLoad.Present>(vault.load()).token.refresh?.reveal())
        }
    }

    @Test
    fun `a token far from running out is not renewed`() = runTest {
        withRig(now = { renewalNow }) {
            vault.save(GitHubToken(Secret("ghu_saved"), Secret("ghr_saved"), renewalNow.plusSeconds(3600)))
            server.json(fixture("list-tunnels.json"))

            controller.start()
            awaitHosts()

            assertEquals(listOf("/tunnels"), paths().map { it.substringBefore('?') })
        }
    }

    @Test
    fun `when GitHub refuses the renewal a token that is still valid is used, and the refused refresh token is not sent again`() = runTest {
        withRig(now = { renewalNow }) {
            vault.save(GitHubToken(Secret("ghu_saved"), Secret("ghr_saved"), renewalNow.plusSeconds(120)))
            server.json(refreshRefused)
            server.json(fixture("list-tunnels.json"))
            // A different list, because the one on show stays while a refresh loads, so only a different one tells that it came.
            server.json("""{"value":[]}""")

            controller.start()
            awaitHosts()
            controller.refreshHosts()
            controller.hosts.first { it.list == HostList.Empty }

            assertEquals(
                listOf("/login/oauth/access_token", "/tunnels", "/tunnels"),
                paths().map { it.substringBefore('?') },
                "one attempt at renewing, then the token that still works",
            )
            assertIs<AppState.Hosts>(state)
        }
    }

    @Test
    fun `when GitHub refuses the renewal of an expired token the user is asked to sign in again`() = runTest {
        withRig(now = { renewalNow }) {
            vault.save(GitHubToken(Secret("ghu_old"), Secret("ghr_old"), Instant.parse("2026-10-22T04:00:00Z")))
            server.json(refreshRefused)

            controller.start()
            val out = assertIs<AppState.SignedOut>(await { it is AppState.SignedOut })

            assertTrue(out.notice!!.contains("no longer accepts"))
            assertEquals(VaultLoad.Absent, vault.load())
            assertEquals(1, server.requestCount, "no host request is made with a token that is known to be dead")
        }
    }

    @Test
    fun `a renewal that cannot reach GitHub keeps the sign-in and succeeds on the next try`() = runTest {
        withRig(now = { renewalNow }) {
            vault.save(GitHubToken(Secret("ghu_old"), Secret("ghr_old"), Instant.parse("2026-10-22T04:00:00Z")))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

            controller.start()
            val failed = awaitHosts()

            assertIs<HostList.Failed>(failed)
            assertEquals("ghr_old", assertIs<VaultLoad.Present>(vault.load()).token.refresh?.reveal(), "a network error does not cost the user their sign-in")

            server.json(renewed)
            server.json(fixture("list-tunnels.json"))
            controller.refreshHosts()

            assertIs<HostList.Loaded>(awaitHosts())
            assertEquals("ghr_new", assertIs<VaultLoad.Present>(vault.load()).token.refresh?.reveal())
        }
    }

    @Test
    fun `two requests for a token at the same moment renew it once, because a refresh token can be used once`() = runTest {
        withRig(now = { renewalNow }) {
            vault.save(GitHubToken(Secret("ghu_old"), Secret("ghr_old"), Instant.parse("2026-10-22T04:00:00Z")))
            server.json(renewed)
            server.json(fixture("list-tunnels.json"))

            controller.start()
            runCurrent()
            controller.refreshHosts()
            awaitHosts()

            val renewals = paths().count { it == "/login/oauth/access_token" }
            assertEquals(1, renewals)
        }
    }

    @Test
    fun `a saved sign-in that cannot be renewed shows its expiry, one that can says it renews itself`() = runTest {
        withRig(now = { renewalNow }) {
            vault.save(GitHubToken(Secret("ghu_saved"), null, renewalNow.plusSeconds(3600)))
            server.json(fixture("list-tunnels.json"))

            controller.start()
            awaitHosts()

            val hosts = controller.hosts.value
            assertEquals(renewalNow.plusSeconds(3600), hosts.tokenExpiresAt)
            assertFalse(hosts.renews)
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
    fun `a rejected sign in at the host list returns to sign in even when wiping the token has to wait on the disk`() = runTest {
        withRig {
            store.suspendOnDelete = true
            vault.save(savedToken)
            server.json("{}", code = 401)

            controller.start()
            val out = assertIs<AppState.SignedOut>(await { it is AppState.SignedOut })

            assertTrue(out.notice!!.contains("no longer accepts"))
            assertEquals(VaultLoad.Absent, vault.load())
        }
    }

    @Test
    fun `losing the sign in while connected returns to sign in even when wiping the token has to wait on the disk`() = runTest {
        withRig(script = { HostScript(failure = DevTunnelsException.AuthExpired()) }) {
            vault.save(savedToken)
            server.json(fixture("list-tunnels.json"))
            controller.start()
            awaitHosts()
            store.suspendOnDelete = true

            controller.connect(loaded().first().tunnel)
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

            assertEquals(HostRef.Tunnel(host), connected.host)
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
    fun `a sign-out that lands while a connection is being made wins, and the session made for it is stopped`() = runTest {
        withRig(onCreate = {
            controller.signOut()
            runCurrent()
        }) {
            vault.save(savedToken)
            server.json(fixture("list-tunnels.json"))
            controller.start()
            awaitHosts()

            controller.connect(loaded().first().tunnel)
            runCurrent()

            assertEquals(AppState.SignedOut(SignInStep.Idle, null, offerRevoke = true), state)
            assertEquals(VaultLoad.Absent, vault.load())
            assertEquals(ConnState.Stopped, created.single().connection.value)
            assertTrue(tokensSeenByHost.isEmpty(), "the session never reached the host")
            assertNull(lastHost.id)
        }
    }

    @Test
    fun `a connection made while a sign-out is wiping the token is stopped when the sign-out lands`() = runTest {
        withRig {
            vault.save(savedToken)
            server.json(fixture("list-tunnels.json"))
            controller.start()
            awaitHosts()
            val host = loaded().first().tunnel
            val wipe = CompletableDeferred<Unit>()
            store.deleteGate = wipe

            controller.signOut()
            runCurrent()
            // The wipe is released first but resumes only at the next runCurrent, by which time the connection has been made.
            wipe.complete(Unit)
            controller.connect(host)
            assertIs<AppState.Connected>(state)
            runCurrent()

            assertIs<AppState.SignedOut>(state)
            assertEquals(VaultLoad.Absent, vault.load())
            assertEquals(ConnState.Stopped, created.single().connection.value)
        }
    }

    @Test
    fun `the host list being replaced while a connection is being made does not stop the connection`() = runTest {
        withRig(onCreate = { controller.refreshHosts() }) {
            vault.save(savedToken)
            server.json(fixture("list-tunnels.json"))
            server.json(fixture("list-tunnels.json"))
            controller.start()
            awaitHosts()
            val host = loaded().first().tunnel

            controller.connect(host)
            val connected = assertIs<AppState.Connected>(state)
            connected.session.connection.first { it == ConnState.Online }
            runCurrent()

            assertEquals(HostRef.Tunnel(host), connected.host)
            assertSame(created.single(), connected.session)
            assertEquals("test-tunnel-1", lastHost.id)
        }
    }

    @Test
    fun `a host list replaced between reading the state and the swap to the live session is retried, not taken as a refusal`() = runTest {
        withRig {
            vault.save(savedToken)
            server.json(fixture("list-tunnels.json"))
            server.json(fixture("list-tunnels.json"))
            controller.start()
            awaitHosts()
            val host = loaded().first().tunnel
            // The state is private and nothing else runs between the read and the swap, so the field is replaced by a wrapper that races it.
            val field = AppController::class.java.getDeclaredField("_state").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val live = field.get(controller) as MutableStateFlow<AppState>
            val racing = RacingState(live) { controller.refreshHosts() }
            field.set(controller, racing)

            controller.connect(host)
            val connected = assertIs<AppState.Connected>(state)
            connected.session.connection.first { it == ConnState.Online }
            runCurrent()

            assertTrue(racing.raced, "the list was replaced under the first swap")
            assertSame(created.single(), connected.session)
            assertEquals("test-tunnel-1", lastHost.id)
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
    fun `a chat opens over its session, going back leaves the session open, and each release unsubscribes exactly its own channel`() = runTest {
        withRig(script = { HostScript(sessionChats = "[${chatSummaryJson(CHAT_A)}]") }) {
            connectToFirstHost()
            controller.openSession(SESSION_C)
            val session = assertIs<AppState.Connected>(state).open!!
            session.store.state.first { it != null }
            runCurrent()

            controller.openChat(CHAT_A)
            val chat = assertIs<AppState.Connected>(state).open!!.chat!!
            chat.store.state.first { it != null }
            runCurrent()

            assertEquals(CHAT_A, chat.uri)
            assertEquals("Chat", chat.store.state.value!!.title)
            assertEquals(emptyList(), unsubscribed())

            controller.closeChat()
            runCurrent()

            val after = assertIs<AppState.Connected>(state).open!!
            assertEquals(SESSION_C, after.uri)
            assertNull(after.chat)
            assertEquals(listOf(CHAT_A), unsubscribed())
        }
    }

    @Test
    fun `a chat the session does not list is ignored, and so is a second open while one is showing`() = runTest {
        withRig(script = { HostScript(sessionChats = "[${chatSummaryJson(CHAT_A)}, ${chatSummaryJson(CHAT_B, "Second")}]") }) {
            connectToFirstHost()
            controller.openSession(SESSION_C)
            controller.openChat(CHAT_A)
            assertNull(assertIs<AppState.Connected>(state).open!!.chat, "the session snapshot had not arrived, so no chat could be checked against it")
            assertEquals(emptyList(), unsubscribed())

            assertIs<AppState.Connected>(state).open!!.store.state.first { it != null }
            runCurrent()
            controller.openChat("ahp-chat:/not-in-this-session")
            assertNull(assertIs<AppState.Connected>(state).open!!.chat)

            controller.openChat(CHAT_A)
            controller.openChat(CHAT_B)
            assertEquals(CHAT_A, assertIs<AppState.Connected>(state).open!!.chat!!.uri)
            controller.closeChat()
            runCurrent()

            assertEquals(listOf(CHAT_A), unsubscribed())
        }
    }

    @Test
    fun `leaving the session with a chat open releases the chat and the session`() = runTest {
        withRig(script = { HostScript(sessionChats = "[${chatSummaryJson(CHAT_A)}]") }) {
            connectToFirstHost()
            controller.openSession(SESSION_C)
            assertIs<AppState.Connected>(state).open!!.store.state.first { it != null }
            runCurrent()
            controller.openChat(CHAT_A)
            runCurrent()

            controller.closeSession()
            runCurrent()

            assertNull(assertIs<AppState.Connected>(state).open)
            assertEquals(setOf(CHAT_A, SESSION_C), unsubscribed().toSet())
            assertEquals(2, unsubscribed().size)
        }
    }

    @Test
    fun `a chat cannot be opened or closed without an open session, or when not connected`() = runTest {
        withRig {
            controller.start()
            runCurrent()
            controller.openChat(CHAT_A)
            controller.closeChat()
            assertIs<AppState.SignedOut>(state)
            assertTrue(created.isEmpty())
        }
        withRig {
            connectToFirstHost()

            controller.openChat(CHAT_A)
            controller.closeChat()
            runCurrent()

            assertNull(assertIs<AppState.Connected>(state).open)
            assertEquals(emptyList(), unsubscribed())
        }
    }

    @Test
    fun `selecting a chat opens it when none is open and swaps it for the one that is, which stays subscribed`() = runTest {
        withRig(script = { HostScript(sessionChats = "[${chatSummaryJson(CHAT_A)}, ${chatSummaryJson(CHAT_B, "Second")}]") }) {
            connectToFirstHost()
            controller.openSession(SESSION_C)
            assertIs<AppState.Connected>(state).open!!.store.state.first { it != null }
            runCurrent()

            controller.selectChat(CHAT_A)
            assertEquals(CHAT_A, assertIs<AppState.Connected>(state).open!!.chat!!.uri)
            runCurrent()
            assertEquals(emptyList(), unsubscribed())

            controller.selectChat(CHAT_B)
            runCurrent()

            val open = assertIs<AppState.Connected>(state).open!!
            assertEquals(CHAT_B, open.chat!!.uri)
            assertNull(open.chat!!.parent)
            assertEquals(SESSION_C, open.uri)
            assertEquals(listOf(CHAT_A), open.warm.map { it.uri }, "the chat left is kept, so its tab is instant when the person comes back")
            assertEquals(emptyList(), unsubscribed())
        }
    }

    private fun Rig.subscribesTo(channel: String): Int = connectors.single().sockets.flatMap { it.sent }
        .count { it["method"]?.jsonPrimitive?.content == "subscribe" && it["params"]!!.jsonObject["channel"]?.jsonPrimitive?.content == channel }

    @Test
    fun `going back to a chat looked at before shows the same live chat with no new subscription`() = runTest {
        withRig(script = { HostScript(sessionChats = "[${chatSummaryJson(CHAT_A)}, ${chatSummaryJson(CHAT_B, "Second")}]") }) {
            connectToFirstHost()
            controller.openSession(SESSION_C)
            assertIs<AppState.Connected>(state).open!!.store.state.first { it != null }
            runCurrent()
            controller.selectChat(CHAT_A)
            val first = assertIs<AppState.Connected>(state).open!!.chat!!
            first.store.state.first { it != null }
            controller.selectChat(CHAT_B)
            runCurrent()

            controller.selectChat(CHAT_A)
            runCurrent()

            val open = assertIs<AppState.Connected>(state).open!!
            assertSame(first, open.chat, "the very chat that was on screen before, already loaded")
            assertEquals(listOf(CHAT_B), open.warm.map { it.uri })
            assertEquals(1, subscribesTo(CHAT_A), "it was asked for once, not again on the way back")
            assertEquals(emptyList(), unsubscribed())
        }
    }

    @Test
    fun `a session keeps only a few chats warm, and lets the oldest go`() = runTest {
        val chats = listOf(CHAT_A, CHAT_B, CHAT_C, "ahp-chat:/d4", "ahp-chat:/d5")
        withRig(script = { HostScript(sessionChats = chats.joinToString(prefix = "[", postfix = "]") { chatSummaryJson(it, "Chat $it") }) }) {
            connectToFirstHost()
            controller.openSession(SESSION_C)
            assertIs<AppState.Connected>(state).open!!.store.state.first { it != null }
            runCurrent()

            chats.forEach { controller.selectChat(it); runCurrent() }

            val open = assertIs<AppState.Connected>(state).open!!
            assertEquals(chats.last(), open.chat!!.uri)
            assertEquals(chats.subList(1, 4), open.warm.map { it.uri }, "the three before it, the oldest of all let go")
            assertEquals(listOf(chats.first()), unsubscribed())
        }
    }

    @Test
    fun `a chat that has left the session is not kept warm`() = runTest {
        withRig(script = { HostScript(sessionChats = "[${chatSummaryJson(CHAT_A)}, ${chatSummaryJson(CHAT_B, "Second")}, ${chatSummaryJson(CHAT_C, "Third")}]") }) {
            connectToFirstHost()
            controller.openSession(SESSION_C)
            assertIs<AppState.Connected>(state).open!!.store.state.first { it != null }
            runCurrent()
            controller.selectChat(CHAT_A)
            runCurrent()
            controller.selectChat(CHAT_B)
            runCurrent()
            connectors.single().sockets.single().serverSends(actionFrame(SESSION_C, """{"type":"session/chatRemoved","chat":"$CHAT_A"}""", SNAPSHOT_SEQ + 1))
            runCurrent()

            controller.selectChat(CHAT_C)
            runCurrent()

            val open = assertIs<AppState.Connected>(state).open!!
            assertEquals(listOf(CHAT_B), open.warm.map { it.uri })
            assertEquals(listOf(CHAT_A), unsubscribed(), "the removed chat was let go")
        }
    }

    @Test
    fun `selecting the chat already shown, or one the session does not list, changes nothing`() = runTest {
        withRig(script = { HostScript(sessionChats = "[${chatSummaryJson(CHAT_A)}]") }) {
            connectToFirstHost()
            controller.openSession(SESSION_C)
            assertIs<AppState.Connected>(state).open!!.store.state.first { it != null }
            runCurrent()
            controller.selectChat(CHAT_A)
            val first = assertIs<AppState.Connected>(state).open!!.chat!!

            controller.selectChat(CHAT_A)
            controller.selectChat("ahp-chat:/not-in-this-session")
            runCurrent()

            assertSame(first, assertIs<AppState.Connected>(state).open!!.chat)
            assertEquals(emptyList(), unsubscribed())
        }
    }

    @Test
    fun `selecting a chat before the session has loaded opens nothing, and with no open session does nothing`() = runTest {
        withRig(script = { HostScript(sessionChats = "[${chatSummaryJson(CHAT_A)}]") }) {
            connectToFirstHost()
            controller.selectChat(CHAT_A)
            assertNull(assertIs<AppState.Connected>(state).open)

            controller.openSession(SESSION_C)
            controller.selectChat(CHAT_A)

            assertNull(assertIs<AppState.Connected>(state).open!!.chat, "the session snapshot had not arrived, so the chat could not be checked against it")
        }
    }

    @Test
    fun `showing a session leaves the one that is open for it, and does not need the host to list it`() = runTest {
        withRig(script = { HostScript(sessionChats = "[${chatSummaryJson(CHAT_A)}]") }) {
            connectToFirstHost()
            controller.openSession(SESSION_C)
            assertIs<AppState.Connected>(state).open!!.store.state.first { it != null }
            runCurrent()

            controller.showSession("ahp-session:/just-started")
            runCurrent()

            val open = assertIs<AppState.Connected>(state).open!!
            assertEquals("ahp-session:/just-started", open.uri)
            assertEquals(listOf(SESSION_C), unsubscribed(), "the session left is released, and only that one")
        }
    }

    @Test
    fun `showing the session that is already open changes nothing, and with no host connected does nothing`() = runTest {
        withRig(script = { HostScript(sessionChats = "[${chatSummaryJson(CHAT_A)}]") }) {
            controller.showSession(SESSION_C)
            assertIs<AppState.Restoring>(state)

            connectToFirstHost()
            controller.openSession(SESSION_C)
            val first = assertIs<AppState.Connected>(state).open!!
            runCurrent()

            controller.showSession(SESSION_C)
            runCurrent()

            assertSame(first, assertIs<AppState.Connected>(state).open)
            assertEquals(emptyList(), unsubscribed())
        }
    }

    @Test
    fun `a chat is never swapped away from under a subagent transcript`() = runTest {
        withRig(script = { subagentScript(listOf(CHAT_A, CHAT_C)) }) {
            connectToFirstHost()
            controller.openSession(SESSION_C)
            assertIs<AppState.Connected>(state).open!!.store.state.first { it != null }
            runCurrent()
            controller.selectChat(CHAT_A)
            assertIs<AppState.Connected>(state).open!!.chat!!.store.state.first { it != null }
            controller.openSubagentChat(CHAT_B)
            runCurrent()

            controller.selectChat(CHAT_C)
            runCurrent()

            val open = assertIs<AppState.Connected>(state).open!!
            assertEquals(CHAT_B, open.chat!!.uri)
            assertEquals(CHAT_A, open.chat!!.parent!!.uri)
            assertEquals(emptyList(), unsubscribed())
        }
    }

    @Test
    fun `a chat is never swapped away from under a terminal`() = runTest {
        withRig(script = { terminalScript(listOf(CHAT_A, CHAT_C)) }) {
            connectToFirstHost()
            openTerminalChat(this)
            controller.openTerminal(TERMINAL_REAL)
            runCurrent()

            controller.selectChat(CHAT_C)
            runCurrent()

            val open = assertIs<AppState.Connected>(state).open!!
            assertEquals(CHAT_A, open.chat!!.uri)
            assertEquals(TERMINAL_REAL, open.terminal!!.uri)
            assertEquals(emptyList(), unsubscribed())
        }
    }

    @Test
    fun `leaving the session after swapping chats releases the chat on screen and the session, and nothing twice`() = runTest {
        withRig(script = { HostScript(sessionChats = "[${chatSummaryJson(CHAT_A)}, ${chatSummaryJson(CHAT_B, "Second")}]") }) {
            connectToFirstHost()
            controller.openSession(SESSION_C)
            assertIs<AppState.Connected>(state).open!!.store.state.first { it != null }
            runCurrent()
            controller.selectChat(CHAT_A)
            runCurrent()
            controller.selectChat(CHAT_B)
            runCurrent()

            controller.closeSession()
            runCurrent()

            assertNull(assertIs<AppState.Connected>(state).open)
            assertEquals(listOf(CHAT_A, CHAT_B, SESSION_C).sorted(), unsubscribed().sorted())
        }
    }

    @Test
    fun `leaving the host with a chat open returns to the host list`() = runTest {
        withRig(script = { HostScript(sessionChats = "[${chatSummaryJson(CHAT_A)}]") }) {
            server.json(fixture("list-tunnels.json"))
            connectToFirstHost()
            controller.openSession(SESSION_C)
            assertIs<AppState.Connected>(state).open!!.store.state.first { it != null }
            runCurrent()
            controller.openChat(CHAT_A)

            controller.disconnect()
            val list = awaitHosts()

            assertIs<HostList.Loaded>(list)
            assertEquals(ConnState.Stopped, created.single().connection.value)
        }
    }

    @Test
    fun `the blocks a user opened in a chat are still open when they leave it and come back, and each chat keeps its own`() = runTest {
        withRig(script = { HostScript(sessionChats = "[${chatSummaryJson(CHAT_A)}, ${chatSummaryJson(CHAT_B, "Second")}]") }) {
            connectToFirstHost()
            controller.openSession(SESSION_C)
            assertIs<AppState.Connected>(state).open!!.store.state.first { it != null }
            runCurrent()

            controller.openChat(CHAT_A)
            val first = assertIs<AppState.Connected>(state).open!!.chat!!.expansion
            assertEquals(emptySet(), first.expanded.value)
            first.toggle("t1/tool-1")
            controller.closeChat()

            controller.openChat(CHAT_B)
            val second = assertIs<AppState.Connected>(state).open!!.chat!!.expansion
            assertEquals(emptySet(), second.expanded.value, "a different chat starts folded")
            controller.closeChat()

            controller.openChat(CHAT_A)
            val again = assertIs<AppState.Connected>(state).open!!.chat!!.expansion
            assertSame(first, again)
            assertEquals(setOf("t1/tool-1"), again.expanded.value)
        }
    }

    @Test
    fun `leaving the host forgets what was open`() = runTest {
        withRig(script = { HostScript(sessionChats = "[${chatSummaryJson(CHAT_A)}]") }) {
            server.json(fixture("list-tunnels.json"))
            connectToFirstHost()
            controller.openSession(SESSION_C)
            assertIs<AppState.Connected>(state).open!!.store.state.first { it != null }
            runCurrent()
            controller.openChat(CHAT_A)
            val before = assertIs<AppState.Connected>(state).open!!.chat!!.expansion
            before.toggle("t1/tool-1")

            controller.disconnect()
            awaitHosts()
            controller.connect(loaded().first().tunnel)
            val connected = assertIs<AppState.Connected>(state)
            connected.session.connection.first { it == ConnState.Online }
            controller.openSession(SESSION_C)
            assertIs<AppState.Connected>(state).open!!.store.state.first { it != null }
            runCurrent()
            controller.openChat(CHAT_A)

            val after = assertIs<AppState.Connected>(state).open!!.chat!!.expansion
            assertNotSame(before, after)
            assertEquals(emptySet(), after.expanded.value)
        }
    }

    @Test
    fun `signing out forgets what was open too`() = runTest {
        withRig(script = { HostScript(sessionChats = "[${chatSummaryJson(CHAT_A)}]") }) {
            connectToFirstHost()
            controller.openSession(SESSION_C)
            assertIs<AppState.Connected>(state).open!!.store.state.first { it != null }
            runCurrent()
            controller.openChat(CHAT_A)
            val before = assertIs<AppState.Connected>(state).open!!.chat!!.expansion
            before.toggle("t1/tool-1")

            controller.signOut()
            await { it is AppState.SignedOut }
            vault.save(savedToken)
            server.json(fixture("list-tunnels.json"))
            controller.start()
            awaitHosts()
            controller.connect(loaded().first().tunnel)
            assertIs<AppState.Connected>(state).session.connection.first { it == ConnState.Online }
            controller.openSession(SESSION_C)
            assertIs<AppState.Connected>(state).open!!.store.state.first { it != null }
            runCurrent()
            controller.openChat(CHAT_A)

            val after = assertIs<AppState.Connected>(state).open!!.chat!!.expansion
            assertNotSame(before, after)
            assertEquals(emptySet(), after.expanded.value)
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
    fun `a sign in GitHub refuses, or answers with garbage, says so and can be retried`() = runTest {
        withRig {
            server.json(deviceCode)
            server.json("""{"error":"unsupported_grant_type"}""")
            controller.start()
            runCurrent()

            controller.signIn()
            val refused = assertIs<SignInStep.Failed>((await { it is AppState.SignedOut && it.step is SignInStep.Failed } as AppState.SignedOut).step)
            assertTrue(refused.message.contains("unsupported_grant_type"), refused.message)
        }
        withRig {
            server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>"))
            controller.start()
            runCurrent()

            controller.signIn()
            val garbled = assertIs<SignInStep.Failed>((await { it is AppState.SignedOut && it.step is SignInStep.Failed } as AppState.SignedOut).step)
            assertTrue(garbled.message.contains("non-JSON"), garbled.message)
        }
    }

    @Test
    fun `a token that cannot be stored securely is never kept, and the user is told`() = runTest {
        withRig {
            box.failNextSeals = 5 // the vault retries once with a fresh key; a persistent failure must still end in an honest message
            server.json(deviceCode)
            server.json(approved)
            controller.start()
            runCurrent()

            controller.signIn()
            val failed = assertIs<SignInStep.Failed>((await { it is AppState.SignedOut && it.step is SignInStep.Failed } as AppState.SignedOut).step)

            assertTrue(failed.message.contains("stored securely"), failed.message)
            assertEquals(VaultLoad.Absent, vault.load())
            assertEquals(0, server.requestCount - 2, "no host list was requested with a token that was not saved")
        }
    }

    @Test
    fun `a failing last-host store never blocks the host list or a connection`() = runTest {
        withRig {
            lastHost.failReads = true
            lastHost.failWrites = true
            vault.save(savedToken)
            server.json(fixture("list-tunnels.json"))
            controller.start()
            awaitHosts()
            assertTrue(loaded().none { it.isLastUsed })

            controller.connect(loaded().first().tunnel)
            created.single().connection.first { it == ConnState.Online }
            runCurrent()

            assertIs<AppState.Connected>(state)
            assertNull(lastHost.id)
        }
    }

    @Test
    fun `signing out still lands on the sign-in screen when the saved token cannot be deleted`() = runTest {
        withRig {
            vault.save(savedToken)
            server.json(fixture("list-tunnels.json"))
            controller.start()
            awaitHosts()
            store.deleteFailure = IOException("disk error")

            controller.signOut()
            await { it is AppState.SignedOut }

            assertEquals(AppState.SignedOut(SignInStep.Idle, null, offerRevoke = true), state)
        }
    }

    @Test
    fun `the revoke offer appears only after a deliberate sign-out, and goes when signing in again`() = runTest {
        withRig {
            vault.save(savedToken)
            server.json(fixture("list-tunnels.json"))
            server.json(deviceCode)
            controller.start()
            awaitHosts()

            controller.signOut()
            val out = assertIs<AppState.SignedOut>(await { it is AppState.SignedOut })
            assertTrue(out.offerRevoke)

            controller.signIn()
            val next = assertIs<AppState.SignedOut>(await { it is AppState.SignedOut && it.step !is SignInStep.Idle })
            assertFalse(next.offerRevoke)
        }
        withRig {
            vault.save(savedToken)
            server.json("{}", code = 401)
            controller.start()

            val expired = assertIs<AppState.SignedOut>(await { it is AppState.SignedOut })

            assertFalse(expired.offerRevoke, "an expired token is not a sign-out the user chose")
        }
    }

    @Test
    fun `the revoke link is an https GitHub page the app is willing to open`() {
        assertTrue(SafeLinks.isSafeVerificationUri(GITHUB_AUTHORIZED_APPS_URL))
        assertEquals("https://github.com/settings/applications", GITHUB_AUTHORIZED_APPS_URL)
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

    // ---- the host catalog, and switching hosts ---------------------------------------------------------------------

    private fun Rig.tunnel(id: String): TunnelInfo = loaded().first { it.tunnel.tunnelId == id }.tunnel

    /** Signs in from the vault, lists the hosts and connects to the first, online. */
    private suspend fun Rig.connectedToFirstOfTwo(): AppState.Connected {
        vault.save(savedToken)
        server.json(fixture("list-tunnels.json"))
        controller.start()
        awaitHosts()
        controller.connect(tunnel("test-tunnel-1"))
        return assertIs<AppState.Connected>(state).also { it.session.connection.first { c -> c == ConnState.Online } }
    }

    /** Every state the controller takes from now on, recorded as it is set: a collector that runs later could be handed only the last of two quick ones. */
    private fun TestScope.recordStates(controller: AppController): List<AppState> {
        val seen = mutableListOf<AppState>()
        backgroundScope.launch(Dispatchers.Unconfined) { controller.state.collect { seen += it } }
        return seen
    }

    @Test
    fun `refreshing while connected loads the tunnel hosts into the catalog and leaves the state as it is`() = runTest {
        withRig {
            val connected = connectedToFirstOfTwo()
            val seen = recordStates(controller)
            server.json("""{"value":[]}""")

            controller.refreshHosts()
            controller.hosts.first { it.list == HostList.Empty }

            assertEquals(listOf<AppState>(connected), seen, "no state was set")
            assertSame(connected, state)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `a refresh keeps the list on show while the new one loads, but not a failed one`() = runTest {
        withRig {
            vault.save(savedToken)
            server.json("{}", code = 503)
            server.json(fixture("list-tunnels.json"))
            server.json(fixture("list-tunnels.json"))
            controller.start()
            assertIs<HostList.Failed>(awaitHosts())

            controller.refreshHosts()
            assertEquals(HostList.Loading, controller.hosts.value.list, "a failure is not a list to keep")
            val loaded = assertIs<HostList.Loaded>(awaitHosts())

            controller.refreshHosts()
            assertEquals(loaded, controller.hosts.value.list, "the list on show stays while the next one loads")
            runCurrent()
        }
    }

    @Test
    fun `a sign-in that the host list refuses while connected ends the sign-in and empties the catalog`() = runTest {
        withRig {
            val connected = connectedToFirstOfTwo()
            server.json("{}", code = 401)

            controller.refreshHosts()
            val out = assertIs<AppState.SignedOut>(await { it is AppState.SignedOut })
            runCurrent()

            assertTrue(out.notice!!.contains("no longer accepts"))
            assertEquals(ConnState.Stopped, connected.session.connection.value)
            assertEquals(VaultLoad.Absent, vault.load())
            assertEquals(HostList.NotSignedIn, controller.hosts.value.list)
        }
    }

    @Test
    fun `signing out empties the tunnel hosts of the catalog`() = runTest {
        withRig {
            connectedToFirstOfTwo()
            assertIs<HostList.Loaded>(controller.hosts.value.list)

            controller.signOut()
            await { it is AppState.SignedOut }
            runCurrent()

            assertEquals(HostCatalog(HostList.NotSignedIn), controller.hosts.value)
        }
    }

    @Test
    fun `switching from one connected host to another goes straight across, with no host list between, and the old session is stopped`() = runTest {
        withRig {
            val a = connectedToFirstOfTwo()
            controller.openSession(SESSION_C)
            val beforeSwitch = state
            val seen = recordStates(controller)
            val target = tunnel("test-tunnel-2")

            controller.switchHost(HostRef.Tunnel(target))
            val b = assertIs<AppState.Connected>(state)
            b.session.connection.first { it == ConnState.Online }
            runCurrent()

            assertEquals(listOf(beforeSwitch, b), seen, "Connected a, then Connected b: never the host list")
            assertEquals(HostRef.Tunnel(target), b.host)
            assertNotSame(a.session, b.session)
            assertNull(b.open, "what was open on the old host is not carried over")
            assertEquals(ConnState.Stopped, a.session.connection.value)
            assertEquals(listOf("ghu_saved", "ghu_saved"), tokensSeenByHost)
            assertEquals("test-tunnel-2", lastHost.id)
            assertIs<HostList.Loaded>(controller.hosts.value.list)
        }
    }

    @Test
    fun `the session a switch leaves saves its list as it stops`() = runTest {
        val listStore = RecordingStore()
        withRig(sessionCache = SessionListCache(FakeBox(), listStore)) {
            val a = connectedToFirstOfTwo()
            assertTrue(a.session.root.hasLiveCatalog)
            assertEquals(0, listStore.writes, "the debounce has not run yet, so only stopping can save it")

            controller.switchHost(HostRef.Tunnel(tunnel("test-tunnel-2")))
            runCurrent()

            assertEquals(1, listStore.writes)
        }
    }

    @Test
    fun `switching to the host that is already connected does nothing`() = runTest {
        withRig {
            val a = connectedToFirstOfTwo()

            controller.switchHost(HostRef.Tunnel(tunnel("test-tunnel-1")))
            runCurrent()

            assertSame(a, state)
            assertEquals(1, created.size)
            assertEquals(ConnState.Online, a.session.connection.value)
        }
    }

    @Test
    fun `switching is ignored unless the host list or a host is showing`() = runTest {
        withRig {
            controller.start()
            runCurrent()

            controller.switchHost(HostRef.Tunnel(TunnelInfo("t", "usw2", "n", 6, 1, emptyList())))

            assertTrue(created.isEmpty())
            assertIs<AppState.SignedOut>(state)
        }
    }

    @Test
    fun `switching from the host list connects as connecting does`() = runTest {
        withRig {
            vault.save(savedToken)
            server.json(fixture("list-tunnels.json"))
            controller.start()
            awaitHosts()
            val target = tunnel("test-tunnel-1")

            controller.switchHost(HostRef.Tunnel(target))

            assertEquals(HostRef.Tunnel(target), assertIs<AppState.Connected>(state).host)
        }
    }

    @Test
    fun `a sign-out that lands while a switch is making its session wins, and the session made for it is stopped`() = runTest {
        var signOutInCreate = false
        withRig(onCreate = {
            if (signOutInCreate) {
                controller.signOut()
                runCurrent()
            }
        }) {
            val a = connectedToFirstOfTwo()
            signOutInCreate = true

            controller.switchHost(HostRef.Tunnel(tunnel("test-tunnel-2")))
            runCurrent()

            assertEquals(AppState.SignedOut(SignInStep.Idle, null, offerRevoke = true), state)
            assertEquals(VaultLoad.Absent, vault.load())
            assertEquals(2, created.size)
            assertEquals(listOf(ConnState.Stopped, ConnState.Stopped), created.map { it.connection.value })
            assertSame(a.session, created.first())
            assertEquals(listOf("ghu_saved"), tokensSeenByHost, "the session made for the switch never reached the host")
            assertEquals("test-tunnel-1", lastHost.id)
        }
    }

    @Test
    fun `a switch made while a sign-out is wiping the token is stopped when the sign-out lands`() = runTest {
        withRig {
            connectedToFirstOfTwo()
            val wipe = CompletableDeferred<Unit>()
            store.deleteGate = wipe

            controller.signOut()
            runCurrent()
            // The wipe is released first but resumes only at the next runCurrent, by which time the switch has been made.
            wipe.complete(Unit)
            controller.switchHost(HostRef.Tunnel(tunnel("test-tunnel-2")))
            assertIs<AppState.Connected>(state)
            runCurrent()

            assertIs<AppState.SignedOut>(state)
            assertEquals(VaultLoad.Absent, vault.load())
            assertEquals(listOf(ConnState.Stopped, ConnState.Stopped), created.map { it.connection.value })
        }
    }

    @Test
    fun `a session switched to while the app is out of sight starts parked, and returning unparks it`() = runTest {
        withRig {
            val a = connectedToFirstOfTwo()
            controller.setAppVisible(false)
            advanceTimeBy(30_000)
            runCurrent()
            assertTrue(a.session.parked)

            controller.switchHost(HostRef.Tunnel(tunnel("test-tunnel-2")))
            val b = assertIs<AppState.Connected>(state)
            assertTrue(b.session.parked, "the app is still away")

            controller.setAppVisible(true)
            runCurrent()
            assertFalse(b.session.parked)
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

            assertEquals(AppState.SignedOut(SignInStep.Idle, null, offerRevoke = true), state)
            assertEquals(ConnState.Stopped, created.single().connection.value)
            assertEquals(VaultLoad.Absent, vault.load())
            assertFalse(box.keyExists)
        }
    }

    private val listedSessions = Ahp.json.decodeFromString(ListSessionsResult.serializer(), fixture("live/list-sessions.json")).items

    @Test
    fun `signing out wipes the saved session list, and the session it stops does not write it back`() = runTest {
        val listStore = FakeStore()
        val cache = SessionListCache(FakeBox(), listStore)
        cache.save("test-tunnel-1", listedSessions.map { it.copy(title = "Secret plan") })
        withRig(sessionCache = cache) {
            val session = connectToFirstHost().session
            assertTrue(session.root.hasLiveCatalog)
            assertTrue(listStore.blob != null)

            controller.signOut()
            await { it is AppState.SignedOut }
            runCurrent()
            advanceTimeBy(60_000)

            assertEquals(ConnState.Stopped, session.connection.value)
            assertNull(listStore.blob, "no session title stays on the phone after a sign-out")
            assertNull(cache.load("test-tunnel-1"))
        }
    }

    @Test
    fun `a host never shows the list that was saved for another`() = runTest {
        val listStore = FakeStore()
        val cache = SessionListCache(FakeBox(), listStore)
        cache.save("test-tunnel-1", listedSessions)
        withRig(sessionCache = cache, connectDelayMs = 10_000) {
            vault.save(savedToken)
            server.json(fixture("list-tunnels.json"))
            server.json(fixture("list-tunnels.json"))
            controller.start()
            awaitHosts()
            val (first, second) = loaded().map { it.tunnel }.take(2)
            assertEquals(listOf("test-tunnel-1", "test-tunnel-2"), listOf(first.tunnelId, second.tunnelId))

            controller.connect(first)
            runCurrent()
            val own = created.last()
            assertTrue(own.root.stale.value, "its own list is shown at once, marked")
            assertEquals(listedSessions.size, own.root.sessions.value.size)
            controller.disconnect()
            awaitHosts()

            controller.connect(second)
            runCurrent()
            val other = created.last()
            assertFalse(other.root.stale.value)
            assertEquals(emptyList(), other.root.sessions.value, "nothing of the first host is shown for the second")
            assertNull(listStore.blob, "the list of the other host is dropped")
        }
    }

    @Test
    fun `a notification button does nothing while the list shown is only the saved one, and works once the host's has come`() = runTest {
        val listStore = FakeStore()
        val cache = SessionListCache(FakeBox(), listStore)
        cache.save("test-tunnel-1", listedSessions)
        var clientId = ""
        val script = decideScript { clientId }
        withRig(script = script, interaction = allowed, approvalGate = ApprovalGate { true }, sessionCache = cache, connectDelayMs = 20_000) {
            vault.save(savedToken)
            server.json(fixture("list-tunnels.json"))
            controller.start()
            awaitHosts()
            val tunnel = loaded().first().tunnel
            clientId = "client-${tunnel.tunnelId}"
            controller.connect(tunnel)
            runCurrent()
            val session = created.last()
            assertTrue(session.root.stale.value, "the saved list is on show")

            assertEquals(InteractResult.NotAllowed, controller.decideToolCall(CHAT_A, "c1", approve = false))
            assertTrue(connectors.single().sockets.isEmpty(), "nothing was asked of a host that may no longer have the session")

            advanceTimeBy(20_000)
            session.connection.first { it == ConnState.Online }
            session.canInteract.first { it }

            assertFalse(session.root.stale.value)
            assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), controller.decideToolCall(CHAT_A, "c1", approve = false))
        }
    }

    @Test
    fun `turning interaction on reconnects the live session under the interact tier, and off drops that connection`() = runTest {
        withRig {
            val session = connectToFirstHost().session
            assertFalse(session.canInteract.value, "read only until the user allows more")
            assertFalse(controller.interactionAllowed.value)

            assertTrue(controller.setInteraction(true))
            session.canInteract.first { it }
            assertTrue(controller.interactionAllowed.value)

            assertTrue(controller.setInteraction(false))
            assertFalse(session.canInteract.value, "the controls go the moment the permission does")
            assertFalse(controller.interactionAllowed.value)
            runCurrent()
            session.connection.first { it == ConnState.Online }
            assertFalse(session.canInteract.value)
            assertEquals(3, connectors.single().sockets.size, "one connection for each tier change")
        }
    }

    @Test
    fun `on an unsupported phone the session stays read only and the switch cannot be turned on`() = runTest {
        val settings = FakeInteractionSettings(saved = true)
        withRig(interaction = InteractionConsent(settings, supported = false)) {
            val session = connectToFirstHost().session
            runCurrent()

            assertFalse(controller.interactionSupported)
            assertFalse(controller.interactionAllowed.value, "a saved yes is ignored")
            assertFalse(session.canInteract.value)
            assertFalse(controller.setInteraction(true))
            runCurrent()

            assertFalse(session.canInteract.value)
            assertEquals(1, connectors.single().sockets.size, "nothing reconnected")
        }
    }

    @Test
    fun `a subagent's transcript opens on top of its parent, and back returns to the parent first`() = runTest {
        withRig(script = { subagentScript() }) {
            connectToFirstHost()
            controller.openSession(SESSION_C)
            assertIs<AppState.Connected>(state).open!!.store.state.first { it != null }
            runCurrent()
            controller.openChat(CHAT_A)
            assertIs<AppState.Connected>(state).open!!.chat!!.store.state.first { it != null }

            controller.openSubagentChat(CHAT_B)
            runCurrent()

            val child = assertIs<AppState.Connected>(state).open!!.chat!!
            assertEquals(CHAT_B, child.uri)
            assertEquals(CHAT_A, child.parent!!.uri)
            controller.closeChat()
            runCurrent()
            assertEquals(CHAT_A, assertIs<AppState.Connected>(state).open!!.chat!!.uri, "back goes to the chat it was opened from")
            assertEquals(listOf(CHAT_B), unsubscribed(), "only the subagent's chat was dropped")
            controller.closeChat()
            runCurrent()
            assertNull(assertIs<AppState.Connected>(state).open!!.chat)
            assertEquals(listOf(CHAT_B, CHAT_A), unsubscribed())
        }
    }

    @Test
    fun `an address no subagent result names, a chat already open above, and a non chat address are never opened`() = runTest {
        withRig(script = { subagentScript() }) {
            connectToFirstHost()
            controller.openSession(SESSION_C)
            assertIs<AppState.Connected>(state).open!!.store.state.first { it != null }
            runCurrent()
            controller.openChat(CHAT_A)
            assertIs<AppState.Connected>(state).open!!.chat!!.store.state.first { it != null }

            listOf("ahp-chat:/00000000-0000-4000-8000-0000000000ff", "ahp-terminal:/t", "https://evil.example/x", "", CHAT_A).forEach {
                controller.openSubagentChat(it)
                assertEquals(CHAT_A, assertIs<AppState.Connected>(state).open!!.chat!!.uri, it)
            }

            controller.openSubagentChat(CHAT_B)
            runCurrent()
            assertEquals(CHAT_B, assertIs<AppState.Connected>(state).open!!.chat!!.uri)
            controller.openSubagentChat(CHAT_B)
            assertEquals(CHAT_B, assertIs<AppState.Connected>(state).open!!.chat!!.uri)
            assertEquals(CHAT_A, assertIs<AppState.Connected>(state).open!!.chat!!.parent!!.uri, "no loop")
        }
    }

    @Test
    fun `without an open chat there is nothing to open a subagent from, and leaving the session drops the whole stack`() = runTest {
        withRig(script = { subagentScript() }) {
            connectToFirstHost()
            controller.openSubagentChat(CHAT_B)
            assertNull(assertIs<AppState.Connected>(state).open)

            controller.openSession(SESSION_C)
            assertIs<AppState.Connected>(state).open!!.store.state.first { it != null }
            runCurrent()
            controller.openSubagentChat(CHAT_B)
            assertNull(assertIs<AppState.Connected>(state).open!!.chat, "no chat is open yet")

            controller.openChat(CHAT_A)
            assertIs<AppState.Connected>(state).open!!.chat!!.store.state.first { it != null }
            controller.openSubagentChat(CHAT_B)
            runCurrent()
            controller.closeSession()
            runCurrent()

            assertEquals(setOf(CHAT_A, CHAT_B, SESSION_C), unsubscribed().toSet(), "every subscription of the stack is dropped")
        }
    }

    private fun chatsJson(uris: List<String>) = uris.joinToString(prefix = "[", postfix = "]") { chatSummaryJson(it) }

    private fun subagentScript(chats: List<String> = listOf(CHAT_A)) = HostScript(
        sessionChats = chatsJson(chats),
        chatTurns = listOf(
            turnJson(
                "t1",
                parts = """{"kind":"toolCall","toolCall":{"status":"completed","toolCallId":"c1","toolName":"runSubagent","displayName":"Subagent",
                    "invocationMessage":"Go","toolInput":"{}","confirmed":"not-needed","success":true,"pastTenseMessage":"Done",
                    "content":[{"type":"subagent","resource":"$CHAT_B","title":"Explore","agentName":"explore","description":"Look"}]}}""",
            ),
        ),
    )

    private fun terminalScript(chats: List<String> = listOf(CHAT_A), running: Boolean = true) = HostScript(
        sessionChats = chatsJson(chats),
        chatTurns = listOf(
            turnJson(
                "t1",
                parts = """{"kind":"toolCall","toolCall":{"status":"completed","toolCallId":"c1","toolName":"powershell","displayName":"Shell",
                    "invocationMessage":"Run","toolInput":"{}","confirmed":"not-needed","success":true,"pastTenseMessage":"Ran",
                    "content":[{"type":"terminal","resource":"$TERMINAL_REAL","title":"Run Shell Command","isPty":false}]}}""",
            ),
        ),
        terminalState = terminalStateJson(lifecycle = if (running) """{"status":"running"}""" else """{"status":"exited","exitCode":0}"""),
    )

    private suspend fun TestScope.openTerminalChat(rig: Rig) {
        rig.controller.openSession(SESSION_C)
        assertIs<AppState.Connected>(rig.state).open!!.store.state.first { it != null }
        runCurrent()
        rig.controller.openChat(CHAT_A)
        assertIs<AppState.Connected>(rig.state).open!!.chat!!.store.state.first { it != null }
    }

    @Test
    fun `a terminal a tool result names opens over the chat, and back returns to the chat`() = runTest {
        withRig(script = { terminalScript() }) {
            connectToFirstHost()
            openTerminalChat(this)

            controller.openTerminal(TERMINAL_REAL)
            runCurrent()

            val open = assertIs<AppState.Connected>(state).open!!
            assertEquals(TERMINAL_REAL, open.terminal!!.uri)
            assertEquals(listOf("hello"), TerminalView.from(open.terminal!!.store.state.value!!).lines)
            assertEquals(CHAT_A, open.chat!!.uri, "the chat is still there underneath")

            controller.closeTerminal()
            runCurrent()

            assertNull(assertIs<AppState.Connected>(state).open!!.terminal)
            assertEquals(CHAT_A, assertIs<AppState.Connected>(state).open!!.chat!!.uri)
            assertEquals(listOf(TERMINAL_REAL), unsubscribed())
        }
    }

    @Test
    fun `an address no tool result names, a second terminal over an open one, and anything that is not a terminal are never opened`() = runTest {
        withRig(script = { terminalScript() }) {
            connectToFirstHost()
            openTerminalChat(this)

            listOf("ahp-terminal:/not-named", "agenthost-terminal://shell/other", "https://evil.example/x", CHAT_B, "ahp-session:/x", "").forEach {
                controller.openTerminal(it)
                assertNull(assertIs<AppState.Connected>(state).open!!.terminal, it)
            }
            assertEquals(emptyList(), channelsSent("subscribe").filter { it.contains("terminal") })

            controller.openTerminal(TERMINAL_REAL)
            controller.openTerminal(TERMINAL_REAL)
            runCurrent()
            assertEquals(1, channelsSent("subscribe").count { it == TERMINAL_REAL }, "a second tap does not subscribe again")
        }
    }

    @Test
    fun `there is no terminal to open without an open chat`() = runTest {
        withRig(script = { terminalScript() }) {
            connectToFirstHost()
            controller.openTerminal(TERMINAL_REAL)
            assertNull(assertIs<AppState.Connected>(state).open)

            controller.openSession(SESSION_C)
            assertIs<AppState.Connected>(state).open!!.store.state.first { it != null }
            runCurrent()
            controller.openTerminal(TERMINAL_REAL)

            assertNull(assertIs<AppState.Connected>(state).open!!.terminal, "no chat is open yet")
        }
    }

    @Test
    fun `leaving the chat or the session drops the terminal with it`() = runTest {
        withRig(script = { terminalScript() }) {
            connectToFirstHost()
            openTerminalChat(this)
            controller.openTerminal(TERMINAL_REAL)
            runCurrent()

            controller.closeChat()
            runCurrent()
            assertNull(assertIs<AppState.Connected>(state).open!!.terminal)
            assertEquals(setOf(TERMINAL_REAL, CHAT_A), unsubscribed().toSet())

            openChatAgain()
            controller.openTerminal(TERMINAL_REAL)
            runCurrent()
            controller.closeSession()
            runCurrent()
            assertEquals(2, unsubscribed().count { it == TERMINAL_REAL }, "dropped again when the whole session is left")
        }
    }

    private suspend fun Rig.openChatAgain() {
        controller.openChat(CHAT_A)
        assertIs<AppState.Connected>(state).open!!.chat!!.store.state.first { it != null }
    }

    @Test
    fun `watching a terminal from the app sends nothing outside the read tier`() = runTest {
        withRig(script = { terminalScript() }, interaction = InteractionConsent(FakeInteractionSettings(saved = true))) {
            connectToFirstHost()
            openTerminalChat(this)
            controller.openTerminal(TERMINAL_REAL)
            runCurrent()
            controller.closeTerminal()
            runCurrent()

            val methods = connectors.single().sockets.flatMap { it.sent }.mapNotNull { it["method"]?.jsonPrimitive?.content }.toSet()
            assertEquals(emptySet(), methods - setOf("initialize", "reconnect", "ping", "subscribe", "unsubscribe", "listSessions", "fetchTurns"))
        }
    }

    @Test
    fun `a saved yes is read before the first connection, which is then made under the interact tier`() = runTest {
        withRig(interaction = InteractionConsent(FakeInteractionSettings(saved = true))) {
            val session = connectToFirstHost().session
            runCurrent()

            assertTrue(session.canInteract.value)
            assertEquals(1, connectors.single().sockets.size, "no reconnect was needed")
        }
    }

    @Test
    fun `a yes that cannot be saved is not granted and does not disturb the session`() = runTest {
        val settings = FakeInteractionSettings().apply { failWrites = true }
        withRig(interaction = InteractionConsent(settings)) {
            val session = connectToFirstHost().session

            assertFalse(controller.setInteraction(true))
            runCurrent()

            assertFalse(controller.interactionAllowed.value)
            assertFalse(session.canInteract.value)
            assertEquals(1, connectors.single().sockets.size, "nothing changed, so nothing reconnected")
        }
    }

    @Test
    fun `signing out takes the permission back`() = runTest {
        val settings = FakeInteractionSettings()
        withRig(interaction = InteractionConsent(settings)) {
            connectToFirstHost()
            assertTrue(controller.setInteraction(true))
            assertTrue(controller.interactionAllowed.value)

            controller.signOut()
            await { it is AppState.SignedOut }
            runCurrent()

            assertFalse(controller.interactionAllowed.value)
            assertFalse(settings.saved, "it is off on disk too, so the next sign-in starts read only")
        }
    }

    private val pendingTool =
        """{"id":"t9","message":{"text":"go","origin":{"kind":"user"}},"startedAt":"2026-10-22T10:05:00.000Z","responseParts":[
            {"kind":"toolCall","toolCall":{"status":"pending-confirmation","toolCallId":"c1","toolName":"run_in_terminal","displayName":"Run in Terminal",
            "invocationMessage":"Run it","toolInput":"{}","confirmationTitle":"Run?","options":[]}}]}"""

    private suspend fun TestScope.openChatInteractor(rig: Rig): ChatInteractor {
        rig.controller.openSession(SESSION_C)
        assertIs<AppState.Connected>(rig.state).open!!.store.state.first { it != null }
        runCurrent()
        rig.controller.openChat(CHAT_A)
        val open = assertIs<AppState.Connected>(rig.state).open!!.chat!!
        // Until the chat has loaded nothing can be done in it, whatever the tier: wait, so a test of the tier tests the tier.
        open.store.state.first { it != null }
        return open.interactor
    }

    private fun Rig.dispatches() = connectors.single().sockets.flatMap { it.sent }.filter { it["method"]?.jsonPrimitive?.content == "dispatchAction" }

    @Test
    fun `an open chat sends a message only once interaction is allowed, and it reaches the host as one dispatch`() = runTest {
        var clientId = ""
        val script = { HostScript(sessionChats = "[${chatSummaryJson(CHAT_A)}]", onDispatch = { echoOf(it, SNAPSHOT_SEQ + 1, clientId = clientId) }) }
        withRig(script = script) {
            val connected = connectToFirstHost()
            clientId = "client-${connected.host.id}"
            val interactor = openChatInteractor(this)

            assertEquals(InteractResult.NotAllowed, interactor.send("hello"), "read only until the user allows more")
            assertTrue(dispatches().isEmpty())

            assertTrue(controller.setInteraction(true))
            connected.session.canInteract.first { it }
            runCurrent()

            assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), interactor.send("hello"))
            assertEquals(1, dispatches().size)
        }
    }

    @Test
    fun `with the default gate no approval is ever sent, and a gate that says yes lets one through`() = runTest {
        var clientId = ""
        val script = {
            HostScript(
                sessionChats = "[${chatSummaryJson(CHAT_A)}]",
                chatActiveTurn = pendingTool,
                onDispatch = { echoOf(it, SNAPSHOT_SEQ + 1, clientId = clientId) },
            )
        }
        withRig(script = script, interaction = InteractionConsent(FakeInteractionSettings(saved = true))) {
            val connected = connectToFirstHost()
            clientId = "client-${connected.host.id}"
            val interactor = openChatInteractor(this)
            runCurrent()

            assertEquals(InteractResult.NotConfirmed, interactor.approve("c1"), "a build with no device check cannot approve")
            assertTrue(dispatches().isEmpty())
        }
        withRig(script = script, interaction = InteractionConsent(FakeInteractionSettings(saved = true)), approvalGate = ApprovalGate { true }) {
            val connected = connectToFirstHost()
            clientId = "client-${connected.host.id}"
            val interactor = openChatInteractor(this)
            runCurrent()

            assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), interactor.approve("c1"))
            assertEquals(1, dispatches().size)
        }
    }

    // ---- deciding a tool call from a notification button ----------------------------------------------------------

    private fun Rig.channelsSent(method: String): List<String> = connectors.single().sockets.flatMap { it.sent }
        .filter { it["method"]?.jsonPrimitive?.content == method }
        .map { it["params"]!!.jsonObject["channel"]!!.jsonPrimitive.content }

    /** What each dispatch decided: the action it carried, the call it named, and whether it approved. */
    private fun Rig.decisions() = dispatches().map { request ->
        val action = request["params"]!!.jsonObject["action"]!!.jsonObject
        Triple(action["type"]!!.jsonPrimitive.content, action["toolCallId"]!!.jsonPrimitive.content, action["approved"]!!.jsonPrimitive.boolean)
    }

    private fun decideScript(missing: Set<String> = emptySet(), clientId: () -> String) = {
        HostScript(
            sessionChats = "[${chatSummaryJson(CHAT_A)}]",
            chatActiveTurn = pendingTool,
            missingSessions = missing,
            onDispatch = { echoOf(it, SNAPSHOT_SEQ + 1, clientId = clientId()) },
        )
    }

    private val allowed get() = InteractionConsent(FakeInteractionSettings(saved = true))

    private val pendingTextQuestion =
        """{"id":"t9","message":{"text":"go","origin":{"kind":"user"}},"startedAt":"2026-10-22T10:05:00.000Z","responseParts":[
            {"kind":"inputRequest","request":{"id":"req-1","questions":[{"kind":"text","id":"q1","title":"Season","message":"Which season do you prefer?"}]}}]}"""

    private fun answerQuestionScript(clientId: () -> String) = {
        HostScript(
            sessionChats = "[${chatSummaryJson(CHAT_A)}]",
            chatActiveTurn = pendingTextQuestion,
            inputNeeded = """[{"kind":"chatInput","id":"i1","chat":"$CHAT_A","request":{"id":"req-1","questions":[{"kind":"text","id":"q1","title":"Season","message":"Which season do you prefer?"}]}}]""",
            onDispatch = { echoOf(it, SNAPSHOT_SEQ + 1, clientId = clientId()) },
        )
    }

    @Test
    fun `answering a question from a notification opens the chat, sends the answer without a device check, and closes the chat`() = runTest {
        var clientId = ""
        withRig(script = answerQuestionScript { clientId }, interaction = allowed, approvalGate = ApprovalGate { false }) {
            val connected = connectToFirstHost()
            clientId = "client-${connected.host.id}"
            connected.session.canInteract.first { it }

            val read = controller.readWaiting(SESSION_C)
            assertEquals(QuestionTarget(CHAT_A, "req-1"), read?.question)

            assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), controller.answerQuestion(CHAT_A, "req-1", "autumn"))

            val action = dispatchedActions().single()
            assertEquals("chat/inputCompleted", action["type"]!!.jsonPrimitive.content)
            assertEquals("req-1", action["requestId"]!!.jsonPrimitive.content)
            assertEquals("autumn", action["answers"]!!.jsonObject["q1"]!!.jsonObject["value"]!!.jsonObject["value"]!!.jsonPrimitive.content)
            assertEquals(listOf(CHAT_A), channelsSent("subscribe").filter { it == CHAT_A })
            assertEquals(listOf(CHAT_A), channelsSent("unsubscribe").filter { it == CHAT_A })
        }
    }

    @Test
    fun `answering a question from a notification refuses invalid inputs, read-only connections, and already-answered requests`() = runTest {
        var clientId = ""
        withRig(script = answerQuestionScript { clientId }, interaction = allowed) {
            val connected = connectToFirstHost()
            clientId = "client-${connected.host.id}"
            connected.session.canInteract.first { it }

            assertEquals(InteractResult.Blank, controller.answerQuestion(CHAT_A, "req-1", "  "))
            assertEquals(InteractResult.NotAllowed, controller.answerQuestion("", "req-1", "autumn"))
            assertEquals(InteractResult.NotAllowed, controller.answerQuestion(CHAT_A, "", "autumn"))
            assertEquals(InteractResult.NotAllowed, controller.answerQuestion(CHAT_A, "req-1", "x".repeat(QuestionTarget.MAX_ANSWER_CHARS + 1)))
            assertEquals(InteractResult.NoSuchRequest, controller.answerQuestion(CHAT_A, "already-gone", "autumn"))
            assertTrue(dispatches().isEmpty())
        }
        withRig(script = answerQuestionScript { "" }) {
            connectToFirstHost()
            assertEquals(InteractResult.NotAllowed, controller.answerQuestion(CHAT_A, "req-1", "autumn"))
            assertTrue(dispatches().isEmpty())
        }
    }

    @Test
    fun `an approval from a notification goes through the device check, reaches the host once, and the chat is left again`() = runTest {
        var clientId = ""
        withRig(script = decideScript { clientId }, interaction = allowed, approvalGate = ApprovalGate { true }) {
            val connected = connectToFirstHost()
            clientId = "client-${connected.host.id}"
            connected.session.canInteract.first { it }

            assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), controller.decideToolCall(CHAT_A, "c1", approve = true))

            assertEquals(listOf(Triple("chat/toolCallConfirmed", "c1", true)), decisions(), "one dispatch, and it approves that call")
            assertEquals(listOf(CHAT_A), channelsSent("subscribe").filter { it == CHAT_A })
            assertEquals(listOf(CHAT_A), channelsSent("unsubscribe").filter { it == CHAT_A }, "opened for the decision and closed after it")
        }
    }

    @Test
    fun `with no device check an approval from a notification is refused, and a denial still goes through`() = runTest {
        var clientId = ""
        withRig(script = decideScript { clientId }, interaction = allowed) {
            val connected = connectToFirstHost()
            clientId = "client-${connected.host.id}"
            connected.session.canInteract.first { it }

            assertEquals(InteractResult.NotConfirmed, controller.decideToolCall(CHAT_A, "c1", approve = true))
            assertTrue(dispatches().isEmpty())
            assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), controller.decideToolCall(CHAT_A, "c1", approve = false))
            assertEquals(listOf(Triple("chat/toolCallConfirmed", "c1", false)), decisions(), "one dispatch, and it denies that call")
        }
    }

    @Test
    fun `a notification button does nothing while the write tier is off`() = runTest {
        withRig(script = decideScript { "" }, approvalGate = ApprovalGate { true }) {
            connectToFirstHost()

            assertEquals(InteractResult.NotAllowed, controller.decideToolCall(CHAT_A, "c1", approve = true))
            assertEquals(InteractResult.NotAllowed, controller.decideToolCall(CHAT_A, "c1", approve = false))
            assertTrue(dispatches().isEmpty())
        }
    }

    @Test
    fun `a notification button does nothing when no host is connected`() = runTest {
        withRig(approvalGate = ApprovalGate { true }) {
            assertEquals(InteractResult.NotAllowed, controller.decideToolCall(CHAT_A, "c1", approve = true))
            assertEquals(InteractResult.NotAllowed, controller.decideToolCall(CHAT_A, "c1", approve = false))
            assertTrue(connectors.isEmpty())
        }
    }

    // ---- reading what a waiting session waits for, for the inbox -----------------------------------------------------

    private val question = """[{"kind":"chatInput","id":"r1","chat":"c","request":{"id":"q","message":"Which season do you prefer?"}}]"""

    @Test
    fun `what a waiting session waits for is read with one subscribe and one unsubscribe, and nothing is sent`() = runTest {
        withRig(script = { HostScript(inputNeeded = question) }) {
            connectToFirstHost()

            val read = controller.readWaiting(SESSION_C)

            assertEquals("Which season do you prefer?", read?.text)
            assertEquals(1, channelsSent("subscribe").count { it == SESSION_C })
            assertEquals(1, channelsSent("unsubscribe").count { it == SESSION_C })
            assertTrue(dispatches().isEmpty(), "reading changes nothing")
        }
    }

    @Test
    fun `nothing is read when no host is connected`() = runTest {
        withRig {
            assertNull(controller.readWaiting(SESSION_C))
            assertTrue(connectors.isEmpty())
        }
    }

    @Test
    fun `an address that is blank or absurdly long is never subscribed to`() = runTest {
        withRig(script = { HostScript(inputNeeded = question) }) {
            connectToFirstHost()
            val before = channelsSent("subscribe").size

            assertNull(controller.readWaiting(""))
            assertNull(controller.readWaiting("  "))
            assertNull(controller.readWaiting("ahp-session:/" + "x".repeat(600)))

            assertEquals(before, channelsSent("subscribe").size)
        }
    }

    @Test
    fun `a session that is not waiting has nothing to read`() = runTest {
        withRig {
            connectToFirstHost()

            assertNull(controller.readWaiting(SESSION_C)?.text)
        }
    }

    @Test
    fun `a call that is no longer waiting is not decided, whatever the notification said`() = runTest {
        var clientId = ""
        withRig(script = decideScript { clientId }, interaction = allowed, approvalGate = ApprovalGate { true }) {
            val connected = connectToFirstHost()
            clientId = "client-${connected.host.id}"
            connected.session.canInteract.first { it }

            assertEquals(InteractResult.NoSuchRequest, controller.decideToolCall(CHAT_A, "some-other-call", approve = true))
            assertEquals(InteractResult.NoSuchRequest, controller.decideToolCall(CHAT_A, "some-other-call", approve = false))
            assertTrue(dispatches().isEmpty())
        }
    }

    // A host that offers options of its own on the waiting call (as VS Code's Copilot agent does).
    private val pendingToolWithOptions =
        """{"id":"t9","message":{"text":"go","origin":{"kind":"user"}},"startedAt":"2026-10-22T10:05:00.000Z","responseParts":[
            {"kind":"toolCall","toolCall":{"status":"pending-confirmation","toolCallId":"c1","toolName":"run_in_terminal","displayName":"Run in Terminal",
            "invocationMessage":"Run it","toolInput":"{}","confirmationTitle":"Run?",
            "options":[{"id":"o-allow","label":"Allow once","kind":"approve"},{"id":"o-no","label":"Skip","kind":"deny"}]}}]}"""

    private fun decideWithOptionsScript(clientId: () -> String) = {
        HostScript(
            sessionChats = "[${chatSummaryJson(CHAT_A)}]",
            chatActiveTurn = pendingToolWithOptions,
            onDispatch = { echoOf(it, SNAPSHOT_SEQ + 1, clientId = clientId()) },
        )
    }

    private fun Rig.dispatchedActions() = dispatches().map { it["params"]!!.jsonObject["action"]!!.jsonObject }

    @Test
    fun `a notification button for one of the hosts options sends that option, and the device check names it`() = runTest {
        var clientId = ""
        val asked = mutableListOf<ApprovalRequest>()
        withRig(script = decideWithOptionsScript { clientId }, interaction = allowed, approvalGate = ApprovalGate { asked += it; true }) {
            val connected = connectToFirstHost()
            clientId = "client-${connected.host.id}"
            connected.session.canInteract.first { it }

            assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), controller.decideToolCall(CHAT_A, "c1", approve = true, optionId = "o-allow"))

            assertEquals("Allow once", asked.single().choice)
            val action = dispatchedActions().single()
            assertEquals("o-allow", action["selectedOptionId"]!!.jsonPrimitive.content)
            assertEquals(true, action["approved"]!!.jsonPrimitive.boolean)
        }
    }

    @Test
    fun `a refusing option from a notification needs no device check`() = runTest {
        var clientId = ""
        withRig(script = decideWithOptionsScript { clientId }, interaction = allowed, approvalGate = ApprovalGate { false }) {
            val connected = connectToFirstHost()
            clientId = "client-${connected.host.id}"
            connected.session.canInteract.first { it }

            assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), controller.decideToolCall(CHAT_A, "c1", approve = false, optionId = "o-no"))

            val action = dispatchedActions().single()
            assertEquals("o-no", action["selectedOptionId"]!!.jsonPrimitive.content)
            assertEquals(false, action["approved"]!!.jsonPrimitive.boolean)
        }
    }

    @Test
    fun `a notification button cannot send an option the host did not offer, or one that means the opposite`() = runTest {
        var clientId = ""
        withRig(script = decideWithOptionsScript { clientId }, interaction = allowed, approvalGate = ApprovalGate { true }) {
            val connected = connectToFirstHost()
            clientId = "client-${connected.host.id}"
            connected.session.canInteract.first { it }

            assertEquals(InteractResult.NoSuchRequest, controller.decideToolCall(CHAT_A, "c1", approve = true, optionId = "o-no"), "a refusal cannot approve")
            assertEquals(InteractResult.NoSuchRequest, controller.decideToolCall(CHAT_A, "c1", approve = false, optionId = "o-allow"), "an approval cannot refuse")
            assertEquals(InteractResult.NoSuchRequest, controller.decideToolCall(CHAT_A, "c1", approve = true, optionId = "made-up"))
            assertEquals(InteractResult.NotAllowed, controller.decideToolCall(CHAT_A, "c1", approve = true, optionId = ""))
            assertEquals(InteractResult.NotAllowed, controller.decideToolCall(CHAT_A, "c1", approve = true, optionId = "o".repeat(ApprovalTarget.MAX_OPTION_ID + 1)))
            assertTrue(dispatches().isEmpty())
        }
    }

    @Test
    fun `an option sent for a call that offers none is refused`() = runTest {
        var clientId = ""
        withRig(script = decideScript { clientId }, interaction = allowed, approvalGate = ApprovalGate { true }) {
            val connected = connectToFirstHost()
            clientId = "client-${connected.host.id}"
            connected.session.canInteract.first { it }

            assertEquals(InteractResult.NoSuchRequest, controller.decideToolCall(CHAT_A, "c1", approve = true, optionId = "o-allow"))
            assertTrue(dispatches().isEmpty())
        }
    }

    // ---- looking after sessions ----------------------------------------------------------------------------------

    private val managing get() = InteractionConsent(FakeInteractionSettings(saved = true), supported = true, managementSettings = FakeInteractionSettings(saved = true))

    private fun Rig.requests(method: String) = connectors.single().sockets.flatMap { it.sent }
        .filter { it["method"]?.jsonPrimitive?.content == method }
        .map { it["params"]!!.jsonObject }

    @Test
    fun `looking after sessions is its own switch, refused until acting for the user is on, and reconnects under its own tier`() = runTest {
        withRig {
            val session = connectToFirstHost().session
            assertFalse(session.canManage.value)
            assertFalse(controller.setManagement(true), "refused while the app may not act for the user at all")
            assertFalse(controller.managementAllowed.value)
            assertEquals(1, connectors.single().sockets.size, "a refused change reconnects nothing")

            assertTrue(controller.setInteraction(true))
            session.canInteract.first { it }
            assertFalse(session.canManage.value, "acting for the user alone is the interact tier")

            assertTrue(controller.setManagement(true))
            session.canManage.first { it }
            assertTrue(controller.managementAllowed.value)
            assertEquals(3, connectors.single().sockets.size, "one connection for each tier change")
        }
    }

    @Test
    fun `turning looking after sessions off, and acting for the user off, each drop the tier at once`() = runTest {
        withRig(interaction = managing) {
            val session = connectToFirstHost().session
            session.canManage.first { it }

            assertTrue(controller.setManagement(false))
            assertFalse(session.canManage.value, "the controls go the moment the permission does")
            runCurrent()
            session.connection.first { it == ConnState.Online }
            session.canInteract.first { it }
            assertFalse(session.canManage.value)
            assertTrue(session.canInteract.value, "acting for the user is still on")

            assertTrue(controller.setInteraction(false))
            assertFalse(controller.managementAllowed.value)
            assertFalse(session.canInteract.value)
        }
    }

    @Test
    fun `signing out turns looking after sessions off with the rest`() = runTest {
        val interaction = managing
        withRig(interaction = interaction) {
            val session = connectToFirstHost().session
            session.canManage.first { it }

            controller.signOut()
            await { it is AppState.SignedOut }
            runCurrent()

            assertFalse(controller.managementAllowed.value)
            assertFalse(controller.interactionAllowed.value)
        }
    }

    private fun managementScript(clientId: () -> String) = {
        HostScript(
            sessionChats = "[${chatSummaryJson(CHAT_A)}]",
            onDispatch = { echoOf(it, SNAPSHOT_SEQ + 1, clientId = clientId()) },
        )
    }

    @Test
    fun `the manager reaches the host through the live connection with the commands and actions the protocol defines`() = runTest {
        var clientId = ""
        withRig(script = managementScript { clientId }, interaction = managing, approvalGate = ApprovalGate { true }) {
            val connected = connectToFirstHost()
            clientId = "client-${connected.host.id}"
            connected.session.canManage.first { it }
            controller.openSession(SESSION_C)
            assertIs<AppState.Connected>(state).open!!.store.state.first { it != null }
            runCurrent()

            assertEquals(ManageResult.Done(), controller.manager.setRead(SESSION_C, true))
            val action = dispatches().single()["params"]!!.jsonObject
            assertEquals(SESSION_C, action["channel"]!!.jsonPrimitive.content)
            assertEquals("session/isReadChanged", action["action"]!!.jsonObject["type"]!!.jsonPrimitive.content)

            val chat = assertIs<ManageResult.Done>(controller.manager.newChat(SESSION_C)).created!!
            assertEquals(listOf(SESSION_C to chat), requests("createChat").map { it["channel"]!!.jsonPrimitive.content to it["chat"]!!.jsonPrimitive.content })

            val folder = controller.manager.offeredFolders().first()
            val session = assertIs<ManageResult.Done>(controller.manager.newSession("copilotcli", folder)).created!!
            val created = requests("createSession").single()
            assertEquals(session, created["channel"]!!.jsonPrimitive.content)
            assertEquals("copilotcli", created["provider"]!!.jsonPrimitive.content)
            assertEquals(listOf(folder), created["workingDirectories"]!!.jsonArray.map { it.jsonPrimitive.content })

            assertEquals(ManageResult.Done(), controller.manager.disposeSession(SESSION_C))
            assertEquals(SESSION_C, requests("disposeSession").single()["channel"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `the manager sends nothing, and asks no device check, on a connection that is not on the manage tier`() = runTest {
        var asked = 0
        withRig(script = managementScript { "" }, interaction = InteractionConsent(FakeInteractionSettings(saved = true)), approvalGate = ApprovalGate { asked++; true }) {
            val connected = connectToFirstHost()
            connected.session.canInteract.first { it }
            controller.openSession(SESSION_C)
            assertIs<AppState.Connected>(state).open!!.store.state.first { it != null }
            runCurrent()

            assertEquals(ManageResult.NotAllowed, controller.manager.setRead(SESSION_C, true))
            assertEquals(ManageResult.NotAllowed, controller.manager.newChat(SESSION_C))
            assertEquals(ManageResult.NotAllowed, controller.manager.newSession("copilotcli", "file:///work/repo-a"))
            assertEquals(ManageResult.NotAllowed, controller.manager.disposeSession(SESSION_C))
            assertTrue(dispatches().isEmpty())
            assertTrue(requests("createChat").isEmpty() && requests("createSession").isEmpty() && requests("disposeSession").isEmpty())
            assertEquals(0, asked, "nobody is prompted for something that cannot be done")
        }
    }

    @Test
    fun `with the default gate nothing that starts or removes is ever sent`() = runTest {
        withRig(script = managementScript { "" }, interaction = managing) {
            val connected = connectToFirstHost()
            connected.session.canManage.first { it }
            controller.openSession(SESSION_C)
            assertIs<AppState.Connected>(state).open!!.store.state.first { it != null }
            runCurrent()

            val folder = controller.manager.offeredFolders().first()
            assertEquals(ManageResult.NotConfirmed, controller.manager.newSession("copilotcli", folder))
            assertEquals(ManageResult.NotConfirmed, controller.manager.disposeSession(SESSION_C))
            assertTrue(requests("createSession").isEmpty() && requests("disposeSession").isEmpty())
        }
    }

    @Test
    fun `a host that refuses a command is reported with its own code`() = runTest {
        val script = { HostScript(onCommand = { _, id, _ -> error(id, -32003, "already exists") }) }
        withRig(script = script, interaction = managing, approvalGate = ApprovalGate { true }) {
            val connected = connectToFirstHost()
            connected.session.canManage.first { it }
            val folder = controller.manager.offeredFolders().first()

            val refused = assertIs<ManageResult.Refused>(controller.manager.newSession("copilotcli", folder))

            assertEquals(-32003, refused.code)
        }
    }

    // ---- watching a changeset ------------------------------------------------------------------------------------

    private val plainChangeset = """{"label":"Uncommitted","uriTemplate":"$CHANGESET_A","changeKind":"uncommitted"}"""
    private val perTurnChangeset = """{"label":"Turn","uriTemplate":"ahp-changeset:/turn/{turnId}","changeKind":"turn"}"""

    private fun changesetScript() = {
        HostScript(
            sessionChats = "[${chatSummaryJson(CHAT_A)}]",
            sessionChangesets = "[$plainChangeset,$perTurnChangeset]",
            changesetState = changesetStateJson("ready", changesetFileJson("work/a.kt")),
        )
    }

    private suspend fun TestScope.openLoadedSession(rig: Rig) {
        rig.controller.openSession(SESSION_C)
        assertIs<AppState.Connected>(rig.state).open!!.store.state.first { it != null }
        runCurrent()
    }

    @Test
    fun `a changeset the open session advertises opens read only and its files load`() = runTest {
        withRig(script = changesetScript()) {
            connectToFirstHost()
            openLoadedSession(this)

            controller.openChangeset(CHANGESET_A)
            val open = assertIs<AppState.Connected>(state).open!!.changeset!!
            assertEquals(CHANGESET_A, open.uri)
            open.store.state.first { it != null }

            assertEquals(listOf("/work/a.kt"), ChangesetView.from(open.store.state.value!!).rows.map { it.path })
            assertTrue(dispatches().isEmpty())
        }
    }

    @Test
    fun `an address the session did not advertise, one that needs values filled in, and an over long one are never opened`() = runTest {
        withRig(script = changesetScript()) {
            connectToFirstHost()
            openLoadedSession(this)

            controller.openChangeset("ahp-changeset:/somewhere-else")
            controller.openChangeset("ahp-changeset:/turn/{turnId}")
            controller.openChangeset("ahp-changeset:/" + "x".repeat(ApprovalTarget.MAX_CHAT_URI))
            controller.openChangeset("")

            assertNull(assertIs<AppState.Connected>(state).open!!.changeset)
            assertTrue(connectors.single().sockets.flatMap { it.sent }.none { it["method"]?.jsonPrimitive?.content == "subscribe" && it["params"]!!.jsonObject["channel"]!!.jsonPrimitive.content.startsWith("ahp-changeset:") })
        }
    }

    @Test
    fun `a changeset opens over the session's own chat, but not without a session, and not twice`() = runTest {
        withRig(script = changesetScript()) {
            connectToFirstHost()

            controller.openChangeset(CHANGESET_A)
            assertNull(assertIs<AppState.Connected>(state).open, "no session is open")

            openLoadedSession(this)
            controller.openChat(CHAT_A)
            assertIs<AppState.Connected>(state).open!!.chat!!.store.state.first { it != null }
            controller.openChangeset(CHANGESET_A)
            controller.openChangeset(CHANGESET_A)
            runCurrent()

            val open = assertIs<AppState.Connected>(state).open!!
            assertEquals(CHANGESET_A, open.changeset!!.uri)
            assertEquals(CHAT_A, open.chat!!.uri, "the chat is still there underneath")
            assertEquals(1, connectors.single().sockets.flatMap { it.sent }.count { it["method"]?.jsonPrimitive?.content == "subscribe" && it["params"]!!.jsonObject["channel"]!!.jsonPrimitive.content == CHANGESET_A })
        }
    }

    @Test
    fun `going back from a changeset, or leaving the session, drops its subscription`() = runTest {
        withRig(script = changesetScript()) {
            connectToFirstHost()
            openLoadedSession(this)

            controller.openChangeset(CHANGESET_A)
            assertIs<AppState.Connected>(state).open!!.changeset!!.store.state.first { it != null }
            controller.closeChangeset()
            runCurrent()
            assertNull(assertIs<AppState.Connected>(state).open!!.changeset)
            assertEquals(listOf(CHANGESET_A), unsubscribed().filter { it == CHANGESET_A })

            controller.openChangeset(CHANGESET_A)
            assertIs<AppState.Connected>(state).open!!.changeset!!.store.state.first { it != null }
            controller.closeSession()
            runCurrent()
            assertEquals(listOf(CHANGESET_A, CHANGESET_A), unsubscribed().filter { it == CHANGESET_A })
            assertTrue(SESSION_C in unsubscribed())
        }
    }

    @Test
    fun `a file of a reviewable changeset is marked reviewed on the host and shows once the host echoes it`() = runTest {
        var clientId = ""
        withRig(script = reviewScript { clientId }, interaction = managing) {
            val connected = connectToFirstHost()
            clientId = "client-${connected.host.id}"
            connected.session.canManage.first { it }
            openLoadedSession(this)
            controller.openChangeset(CHANGESET_A)
            val open = assertIs<AppState.Connected>(state).open!!.changeset!!
            open.store.state.first { it != null }
            assertTrue(open.reviewable)

            assertEquals(ManageResult.Done(), controller.setFileReviewed("file:///work/a.kt", true))

            val sent = dispatches().single()["params"]!!.jsonObject
            assertEquals(CHANGESET_A, sent["channel"]!!.jsonPrimitive.content)
            assertEquals("changeset/filesReviewChanged", sent["action"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals(listOf(true), ChangesetView.from(open.store.state.value!!).rows.map { it.reviewed })
        }
    }

    @Test
    fun `a changeset the host does not call reviewable, and a file it does not list, are never sent`() = runTest {
        var clientId = ""
        withRig(script = reviewScript(plainChangeset) { clientId }, interaction = managing) {
            val connected = connectToFirstHost()
            clientId = "client-${connected.host.id}"
            connected.session.canManage.first { it }
            openLoadedSession(this)
            controller.openChangeset(CHANGESET_A)
            val open = assertIs<AppState.Connected>(state).open!!.changeset!!
            open.store.state.first { it != null }

            assertFalse(open.reviewable)
            assertEquals(ManageResult.NoSuchTarget, controller.setFileReviewed("file:///work/a.kt", true))
            assertTrue(dispatches().isEmpty())
        }
    }

    @Test
    fun `an unlisted file, no open changeset, and a connection that may not manage send nothing`() = runTest {
        var clientId = ""
        withRig(script = reviewScript { clientId }, interaction = managing) {
            val connected = connectToFirstHost()
            clientId = "client-${connected.host.id}"
            connected.session.canManage.first { it }
            openLoadedSession(this)
            assertEquals(ManageResult.NoSuchTarget, controller.setFileReviewed("file:///work/a.kt", true), "no changeset is open")
            controller.openChangeset(CHANGESET_A)
            assertIs<AppState.Connected>(state).open!!.changeset!!.store.state.first { it != null }

            assertEquals(ManageResult.NoSuchTarget, controller.setFileReviewed("file:///elsewhere.kt", true))
            assertTrue(dispatches().isEmpty())
        }
        withRig(script = reviewScript { "" }, interaction = allowed) {
            connectToFirstHost()
            openLoadedSession(this)
            controller.openChangeset(CHANGESET_A)
            assertIs<AppState.Connected>(state).open!!.changeset!!.store.state.first { it != null }

            assertEquals(ManageResult.NotAllowed, controller.setFileReviewed("file:///work/a.kt", true))
            assertTrue(dispatches().isEmpty())
        }
    }

    // ---- a new session's settings ---------------------------------------------------------------------------------------

    @Test
    fun `a new session's settings are asked of the host over the live connection, and the chosen ones are sent with the session`() = runTest {
        var clientId = ""
        val script = {
            HostScript(
                sessionChats = "[${chatSummaryJson(CHAT_A)}]",
                onDispatch = { echoOf(it, SNAPSHOT_SEQ + 1, clientId = clientId) },
                onResolveConfig = { id, params ->
                    val chosen = params["config"]?.jsonObject ?: JsonObject(emptyMap())
                    val values = JsonObject(mapOf<String, JsonElement>("thinking" to JsonPrimitive("low")) + chosen)
                    result(id, """{"schema":{"type":"object","properties":{"thinking":{"type":"string","title":"Thinking","enum":["low","high"]}}},"values":$values}""")
                },
            )
        }
        withRig(script = script, interaction = managing, approvalGate = ApprovalGate { true }) {
            val connected = connectToFirstHost()
            clientId = "client-${connected.host.id}"
            connected.session.canManage.first { it }
            val folder = controller.manager.offeredFolders().first()

            val form = assertIs<ConfigOffer.Form>(controller.manager.sessionSettings("copilotcli", folder)).form
            assertEquals(listOf("thinking"), form.fields.map { it.id })

            val picks = mapOf("thinking" to ConfigPick.Text("high"))
            assertIs<ManageResult.Done>(controller.manager.newSession("copilotcli", folder, picks))

            val asked = requests("resolveSessionConfig")
            assertEquals(2, asked.size, "once to show the form, once to check the choice before sending")
            assertEquals("high", asked[1]["config"]!!.jsonObject["thinking"]!!.jsonPrimitive.content)
            assertEquals("copilotcli", asked[1]["provider"]!!.jsonPrimitive.content)
            assertEquals(folder, asked[1]["workingDirectory"]!!.jsonPrimitive.content)
            assertEquals("high", requests("createSession").single()["config"]!!.jsonObject["thinking"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `a connection that is not on the manage tier never asks the host for a new session's settings`() = runTest {
        withRig(script = { HostScript(sessionChats = "[${chatSummaryJson(CHAT_A)}]") }) {
            connectToFirstHost()

            assertIs<ConfigOffer.Unavailable>(controller.manager.sessionSettings("copilotcli", "file:///work/repo-a"))
            assertEquals(ManageResult.NotAllowed, controller.manager.newSession("copilotcli", "file:///work/repo-a", mapOf("thinking" to ConfigPick.Text("high"))))

            assertTrue(requests("resolveSessionConfig").isEmpty())
        }
    }

    // ---- picking a model ---------------------------------------------------------------------------------------

    @Test
    fun `an open chat offers its agent's models, and a picked one reaches the host with the message`() = runTest {
        var clientId = ""
        val script = { HostScript(sessionChats = "[${chatSummaryJson(CHAT_A)}]", onDispatch = { echoOf(it, SNAPSHOT_SEQ + 1, clientId = clientId) }) }
        withRig(script = script, interaction = allowed) {
            val connected = connectToFirstHost()
            clientId = "client-${connected.host.id}"
            connected.session.canInteract.first { it }
            val interactor = openChatInteractor(this)

            assertEquals(listOf("auto", "claude-sonnet-5.5"), interactor.modelOptions().map { it.id })

            assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), interactor.send("hello", ModelChoice("claude-sonnet-5.5")))
            val message = dispatches().single()["params"]!!.jsonObject["action"]!!.jsonObject["message"]!!.jsonObject
            assertEquals("""{"id":"claude-sonnet-5.5"}""", message["model"].toString())

            assertEquals(InteractResult.InvalidModel, interactor.send("hello", ModelChoice("not-a-model")))
            assertEquals(1, dispatches().size)
        }
    }

    // ---- a file's line-by-line changes ------------------------------------------------------------------------------

    private fun diffScript(reads: MutableList<String>, answer: Boolean = true) = {
        val content = mapOf("ahp-content:/b-work/a.kt" to "one\ntwo\n", "ahp-content:/a-work/a.kt" to "one\nTWO\n")
        HostScript(
            sessionChats = "[${chatSummaryJson(CHAT_A)}]",
            sessionChangesets = "[$plainChangeset]",
            changesetState = changesetStateJson("ready", changesetFileJson("work/a.kt")),
            onResourceRead = { id, params ->
                val uri = params["uri"]!!.jsonPrimitive.content
                reads += uri
                if (!answer) {
                    null
                } else {
                    content[uri]?.let { result(id, """{"data":${JsonPrimitive(it)},"encoding":"utf-8"}""") } ?: error(id, -32008, "no such content")
                }
            },
        )
    }

    private suspend fun TestScope.openChangesetOf(rig: Rig) {
        rig.connectToFirstHost()
        openLoadedSession(rig)
        rig.controller.openChangeset(CHANGESET_A)
        assertIs<AppState.Connected>(rig.state).open!!.changeset!!.store.state.first { it != null }
    }

    private val Rig.openedChangeset get() = assertIs<AppState.Connected>(state).open!!.changeset

    @Test
    fun `a file of the open changeset opens as a line diff, read through the read only connection`() = runTest {
        val reads = mutableListOf<String>()
        withRig(script = diffScript(reads)) {
            openChangesetOf(this)

            controller.openDiff("file:///work/a.kt")

            val diff = openedChangeset!!.diff!!
            assertEquals("/work/a.kt", diff.path)
            val ready = assertIs<DiffResult.Ready>((diff.load.first { it is DiffLoad.Loaded } as DiffLoad.Loaded).result)
            assertEquals(1, ready.diff.added)
            assertEquals(1, ready.diff.removed)
            assertEquals(listOf("ahp-content:/b-work/a.kt", "ahp-content:/a-work/a.kt"), reads)
            assertEquals(listOf("resourceRead", "resourceRead"), connectors.single().sockets.flatMap { it.sent }.mapNotNull { it["method"]?.jsonPrimitive?.content }.filter { it == "resourceRead" })
            assertTrue(dispatches().isEmpty(), "reading changes nothing on the host")
        }
    }

    @Test
    fun `a file the changeset does not list, no open changeset, and a second tap read nothing more`() = runTest {
        val reads = mutableListOf<String>()
        withRig(script = diffScript(reads)) {
            connectToFirstHost()
            openLoadedSession(this)
            controller.openDiff("file:///work/a.kt")
            assertNull(assertIs<AppState.Connected>(state).open!!.changeset, "no changeset is open, so there is nothing to open a file of")

            controller.openChangeset(CHANGESET_A)
            assertIs<AppState.Connected>(state).open!!.changeset!!.store.state.first { it != null }
            controller.openDiff("file:///elsewhere.kt")
            controller.openDiff("")
            assertNull(openedChangeset!!.diff)
            assertTrue(reads.isEmpty())

            controller.openDiff("file:///work/a.kt")
            val first = openedChangeset!!.diff!!
            first.load.first { it is DiffLoad.Loaded }
            controller.openDiff("file:///work/a.kt")
            runCurrent()
            assertSame(first, openedChangeset!!.diff, "a second tap does not start another read")
            assertEquals(2, reads.size, "one file read once, two sides")
        }
    }

    @Test
    fun `going back from a file returns to the changeset's files and keeps the changeset`() = runTest {
        val reads = mutableListOf<String>()
        withRig(script = diffScript(reads)) {
            openChangesetOf(this)
            controller.openDiff("file:///work/a.kt")
            openedChangeset!!.diff!!.load.first { it is DiffLoad.Loaded }

            controller.closeDiff()
            runCurrent()

            assertNull(openedChangeset!!.diff)
            assertEquals(CHANGESET_A, openedChangeset!!.uri)
            assertTrue(unsubscribed().none { it == CHANGESET_A })
            controller.closeDiff()
        }
    }

    @Test
    fun `leaving the file, the changeset or the session drops a read that is still going`() = runTest {
        val leaves = listOf<(AppController) -> Unit>({ it.closeDiff() }, { it.closeChangeset() }, { it.closeSession() })
        for (leave in leaves) {
            val reads = mutableListOf<String>()
            withRig(script = diffScript(reads, answer = false)) {
                openChangesetOf(this)
                controller.openDiff("file:///work/a.kt")
                val load = openedChangeset!!.diff!!.load
                runCurrent()
                assertEquals(1, reads.size, "the read is on its way")

                leave(controller)
                advanceTimeBy(120_000)
                runCurrent()

                assertEquals(DiffLoad.Loading, load.value, "a dropped read never reports back")
            }
        }
    }

    @Test
    fun `a host that never answers ends in a failure in words, not a screen that waits for ever`() = runTest {
        val reads = mutableListOf<String>()
        withRig(script = diffScript(reads, answer = false)) {
            openChangesetOf(this)
            controller.openDiff("file:///work/a.kt")

            val load = openedChangeset!!.diff!!.load.first { it is DiffLoad.Loaded } as DiffLoad.Loaded

            assertIs<DiffResult.Failed>(load.result)
        }
    }

    private val reviewableChangeset = """{"label":"Uncommitted","uriTemplate":"$CHANGESET_A","changeKind":"uncommitted","capabilities":{"review":{}}}"""

    private fun reviewScript(changeset: String = reviewableChangeset, clientId: () -> String) = {
        HostScript(
            sessionChats = "[${chatSummaryJson(CHAT_A)}]",
            sessionChangesets = "[$changeset]",
            changesetState = changesetStateJson("ready", changesetFileJson("work/a.kt")),
            onDispatch = { echoOf(it, SNAPSHOT_SEQ + 1, clientId = clientId()) },
        )
    }

    @Test
    fun `an address that is too long is never subscribed to`() = runTest {
        withRig(script = decideScript { "" }, interaction = allowed, approvalGate = ApprovalGate { true }) {
            val connected = connectToFirstHost()
            connected.session.canInteract.first { it }
            val tooLong = "ahp-chat:/" + "x".repeat(ApprovalTarget.MAX_CHAT_URI)

            assertEquals(InteractResult.NotAllowed, controller.decideToolCall(tooLong, "c1", approve = true))
            assertTrue(channelsSent("subscribe").none { it == tooLong })
            assertTrue(dispatches().isEmpty())
        }
    }

    @Test
    fun `a blank address is never subscribed to`() = runTest {
        withRig(script = decideScript { "" }, interaction = allowed, approvalGate = ApprovalGate { true }) {
            val connected = connectToFirstHost()
            connected.session.canInteract.first { it }

            assertEquals(InteractResult.NotAllowed, controller.decideToolCall("", "c1", approve = true))
            assertTrue(channelsSent("subscribe").none { it.isEmpty() })
            assertTrue(dispatches().isEmpty())
        }
    }

    @Test
    fun `a chat that never loads gives up with nothing sent, and is left again`() = runTest {
        val missing = "ahp-chat:/00000000-0000-4000-8000-00000000dead"
        withRig(script = decideScript(missing = setOf(missing)) { "" }, interaction = allowed, approvalGate = ApprovalGate { true }) {
            val connected = connectToFirstHost()
            connected.session.canInteract.first { it }

            assertIs<InteractResult.NotSent>(controller.decideToolCall(missing, "c1", approve = true))

            assertTrue(dispatches().isEmpty())
            assertEquals(channelsSent("subscribe").count { it == missing }, channelsSent("unsubscribe").count { it == missing })
        }
    }

    @Test
    fun `a chat the screen already has open is shared for the decision and stays open`() = runTest {
        var clientId = ""
        withRig(script = decideScript { clientId }, interaction = allowed, approvalGate = ApprovalGate { true }) {
            val connected = connectToFirstHost()
            clientId = "client-${connected.host.id}"
            openChatInteractor(this)
            runCurrent()

            assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), controller.decideToolCall(CHAT_A, "c1", approve = true))
            assertTrue(channelsSent("unsubscribe").none { it == CHAT_A }, "the person's own subscription is not taken away")

            controller.closeChat()
            runCurrent()
            assertEquals(listOf(CHAT_A), channelsSent("unsubscribe").filter { it == CHAT_A })
        }
    }

    // ---- parking the live session while the app is out of sight ----------------------------------------------------

    private val oneChat = { HostScript(sessionChats = "[${chatSummaryJson(CHAT_A)}]") }

    @Test
    fun `the app out of sight for the grace period parks the live session, and coming back subscribes the chat again`() = runTest {
        withRig(script = oneChat) {
            val session = connectToFirstHost().session
            openChatInteractor(this)
            runCurrent()

            controller.setAppVisible(false)
            advanceTimeBy(29_999)
            runCurrent()
            assertFalse(session.parked)
            assertEquals(emptyList(), unsubscribed())

            advanceTimeBy(1)
            runCurrent()
            assertTrue(session.parked)
            assertEquals(listOf(CHAT_A), unsubscribed(), "the chat is let go of, and the session is not")

            controller.setAppVisible(true)
            runCurrent()
            assertFalse(session.parked)
            assertEquals(2, channelsSent("subscribe").count { it == CHAT_A })
            assertEquals(1, channelsSent("subscribe").count { it == SESSION_C })
        }
    }

    @Test
    fun `a return inside the grace period sends nothing`() = runTest {
        withRig(script = oneChat) {
            val session = connectToFirstHost().session
            openChatInteractor(this)
            runCurrent()

            controller.setAppVisible(false)
            advanceTimeBy(10_000)
            controller.setAppVisible(true)
            advanceTimeBy(120_000)
            runCurrent()

            assertFalse(session.parked)
            assertEquals(emptyList(), unsubscribed())
            assertEquals(1, channelsSent("subscribe").count { it == CHAT_A })
        }
    }

    @Test
    fun `a session connected while the app is out of sight starts parked, and one connected after it returned does not`() = runTest {
        withRig {
            vault.save(savedToken)
            server.json(fixture("list-tunnels.json"))
            server.json(fixture("list-tunnels.json"))
            controller.start()
            awaitHosts()
            controller.setAppVisible(false)
            advanceTimeBy(30_000)
            runCurrent()

            controller.connect(loaded().first().tunnel)
            val first = assertIs<AppState.Connected>(state).session
            assertTrue(first.parked, "the app was already out of sight")

            controller.setAppVisible(true)
            runCurrent()
            assertFalse(first.parked)

            controller.disconnect()
            awaitHosts()
            controller.connect(loaded().first().tunnel)
            val second = assertIs<AppState.Connected>(state).session
            assertNotSame(first, second)
            assertFalse(second.parked)
        }
    }

    @Test
    fun `leaving a host while the app is out of sight parks the next session too, and returning unparks it`() = runTest {
        withRig {
            server.json(fixture("list-tunnels.json"))
            val first = connectToFirstHost().session
            controller.setAppVisible(false)
            advanceTimeBy(30_000)
            runCurrent()
            assertTrue(first.parked)

            controller.disconnect()
            awaitHosts()
            controller.connect(loaded().first().tunnel)
            val second = assertIs<AppState.Connected>(state).session
            assertTrue(second.parked, "the app is still away")

            controller.setAppVisible(true)
            runCurrent()
            assertFalse(second.parked)
        }
    }

    @Test
    fun `an approval from a notification while parked subscribes the chat for the decision, sends it, and leaves the chat unsubscribed`() = runTest {
        var clientId = ""
        withRig(script = decideScript { clientId }, interaction = allowed, approvalGate = ApprovalGate { true }) {
            val connected = connectToFirstHost()
            clientId = "client-${connected.host.id}"
            connected.session.canInteract.first { it }
            controller.setAppVisible(false)
            advanceTimeBy(30_000)
            runCurrent()
            assertTrue(connected.session.parked)

            assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), controller.decideToolCall(CHAT_A, "c1", approve = true))
            runCurrent()

            assertEquals(listOf(Triple("chat/toolCallConfirmed", "c1", true)), decisions())
            assertEquals(1, channelsSent("subscribe").count { it == CHAT_A })
            assertEquals(1, channelsSent("unsubscribe").count { it == CHAT_A }, "left unsubscribed afterwards")
            assertTrue(connected.session.parked)
        }
    }

    @Test
    fun `a denial from a notification while parked acts on the chat's new snapshot, not on the transcript the screen kept`() = runTest {
        var clientId = ""
        var chatSubscribes = 0
        val script = {
            HostScript(
                sessionChats = "[${chatSummaryJson(CHAT_A)}]",
                // The first snapshot, for the screen, has nothing waiting; the call starts waiting while the app is away.
                onChatSubscribe = { id, _ ->
                    val waiting = if (++chatSubscribes == 1) null else pendingTool
                    result(id, """{"snapshot":${chatSnapshotJson(CHAT_A, SNAPSHOT_SEQ, activeTurn = waiting)}}""")
                },
                onDispatch = { echoOf(it, SNAPSHOT_SEQ + 1, clientId = clientId) },
            )
        }
        withRig(script = script, interaction = allowed, approvalGate = ApprovalGate { true }) {
            val connected = connectToFirstHost()
            clientId = "client-${connected.host.id}"
            connected.session.canInteract.first { it }
            openChatInteractor(this)
            runCurrent()
            controller.setAppVisible(false)
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(listOf(CHAT_A), unsubscribed())

            assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), controller.decideToolCall(CHAT_A, "c1", approve = false))
            runCurrent()

            assertEquals(listOf(Triple("chat/toolCallConfirmed", "c1", false)), decisions())
            assertEquals(listOf(CHAT_A, CHAT_A), unsubscribed(), "let go again after the decision, though the screen still holds the chat")

            controller.setAppVisible(true)
            runCurrent()
            assertEquals(3, channelsSent("subscribe").count { it == CHAT_A }, "coming back subscribes the chat the screen holds")
        }
    }

    @Test
    fun `a decision from a notification is not sent when the chat the screen kept is found gone, though its old transcript still shows the call`() = runTest {
        var clientId = ""
        var chatSubscribes = 0
        val script = {
            HostScript(
                sessionChats = "[${chatSummaryJson(CHAT_A)}]",
                onChatSubscribe = { id, _ ->
                    if (++chatSubscribes == 1) {
                        result(id, """{"snapshot":${chatSnapshotJson(CHAT_A, SNAPSHOT_SEQ, activeTurn = pendingTool)}}""")
                    } else {
                        error(id, -32008, "no such chat")
                    }
                },
                onDispatch = { echoOf(it, SNAPSHOT_SEQ + 1, clientId = clientId) },
            )
        }
        withRig(script = script, interaction = allowed, approvalGate = ApprovalGate { true }) {
            val connected = connectToFirstHost()
            clientId = "client-${connected.host.id}"
            connected.session.canInteract.first { it }
            openChatInteractor(this)
            runCurrent()
            controller.setAppVisible(false)
            advanceTimeBy(30_000)
            runCurrent()

            assertIs<InteractResult.NotSent>(controller.decideToolCall(CHAT_A, "c1", approve = true))

            assertTrue(dispatches().isEmpty(), "nothing is decided on a transcript that is no longer being kept")
            assertEquals(2, channelsSent("subscribe").count { it == CHAT_A })
        }
    }

    // ---- coming back after the process was killed ------------------------------------------------------------------

    /** A saved sign-in whose host list is the fixture's three hosts, of which [last] is the last used one; the first and the third are online. */
    private suspend fun Rig.savedSignIn(last: String? = "test-tunnel-1") {
        vault.save(savedToken)
        lastHost.id = last
        server.json(fixture("list-tunnels.json"))
    }

    @Test
    fun `a restored sign-in connects to the last host by itself when monitoring is on and that host is online`() = runTest {
        withRig(autoConnect = { true }) {
            savedSignIn()

            controller.start()
            val connected = assertIs<AppState.Connected>(await { it is AppState.Connected })
            connected.session.connection.first { it == ConnState.Online }
            runCurrent()

            assertEquals("test-tunnel-1", connected.host.id)
            assertEquals(1, created.size)
            assertEquals(listOf("ghu_saved"), tokensSeenByHost)
        }
    }

    @Test
    fun `with monitoring off a restored sign-in stops at the host list`() = runTest {
        withRig(autoConnect = { false }) {
            savedSignIn()

            controller.start()
            awaitHosts()
            runCurrent()

            assertIs<AppState.Hosts>(state)
            assertTrue(loaded().first { it.tunnel.tunnelId == "test-tunnel-1" }.isLastUsed)
            assertTrue(created.isEmpty())
        }
    }

    @Test
    fun `a last host that is offline, or not listed, or none at all is not connected to`() = runTest {
        for (last in listOf("test-tunnel-2", "no-such-tunnel", null)) {
            withRig(autoConnect = { true }) {
                savedSignIn(last)

                controller.start()
                awaitHosts()
                runCurrent()

                assertIs<AppState.Hosts>(state, "last host $last")
                assertTrue(created.isEmpty(), "last host $last")
            }
        }
    }

    @Test
    fun `a host list that cannot be loaded does not connect, and a later refresh does not either`() = runTest {
        withRig(autoConnect = { true }) {
            vault.save(savedToken)
            lastHost.id = "test-tunnel-1"
            server.json("{}", code = 500)
            server.json(fixture("list-tunnels.json"))

            controller.start()
            assertIs<HostList.Failed>(awaitHosts())
            controller.refreshHosts()
            assertIs<HostList.Loaded>(awaitHosts())
            runCurrent()

            assertIs<AppState.Hosts>(state)
            assertTrue(created.isEmpty())
        }
    }

    @Test
    fun `going back to the host list and refreshing it never connect again`() = runTest {
        withRig(autoConnect = { true }) {
            savedSignIn()
            server.json(fixture("list-tunnels.json"))
            server.json(fixture("list-tunnels.json"))
            controller.start()
            assertIs<AppState.Connected>(await { it is AppState.Connected }).session.connection.first { it == ConnState.Online }

            controller.disconnect()
            awaitHosts()
            runCurrent()
            assertIs<AppState.Hosts>(state)
            controller.refreshHosts()
            awaitHosts()
            runCurrent()

            assertIs<AppState.Hosts>(state)
            assertEquals(1, created.size, "only the cold start connected")
        }
    }

    @Test
    fun `a fresh sign-in lands on the host list, whatever the last host and the monitoring switch`() = runTest {
        withRig(autoConnect = { true }) {
            lastHost.id = "test-tunnel-1"
            server.json(deviceCode)
            server.json(approved)
            server.json(fixture("list-tunnels.json"))
            controller.start()
            runCurrent()

            controller.signIn()
            awaitHosts()
            runCurrent()

            assertIs<AppState.Hosts>(state)
            assertTrue(created.isEmpty())
        }
    }

    @Test
    fun `a sign-in GitHub refused at the first list does not leave a connection waiting for the next sign-in`() = runTest {
        withRig(autoConnect = { true }) {
            vault.save(savedToken)
            lastHost.id = "test-tunnel-1"
            server.json("{}", code = 401)
            controller.start()
            await { it is AppState.SignedOut }
            server.json(deviceCode)
            server.json(approved)
            server.json(fixture("list-tunnels.json"))

            controller.signIn()
            awaitHosts()
            runCurrent()

            assertIs<AppState.Hosts>(state)
            assertTrue(created.isEmpty())
        }
    }

    @Test
    fun `ensureConnected connects from the host list when monitoring allows it, and waits until the connection is online`() = runTest {
        var monitoring = false
        withRig(autoConnect = { monitoring }) {
            savedSignIn()
            controller.start()
            awaitHosts()
            runCurrent()
            assertIs<AppState.Hosts>(state)

            monitoring = true

            assertTrue(controller.ensureConnected(10_000))
            val connected = assertIs<AppState.Connected>(state)
            assertEquals(ConnState.Online, connected.session.connection.value)
            assertEquals("test-tunnel-1", connected.host.id)
            assertEquals(1, created.size)
        }
    }

    // The sign-in is restored from disk and the host list read over a real socket, outside the test's virtual time. A timeout that starts
    // before that read ends would be skipped to at once, so a call that begins at a cold start runs on a real dispatcher.
    @Test
    fun `ensureConnected called while the sign-in is still being restored waits for it, and makes one connection`() = runTest {
        withRig(autoConnect = { true }) {
            savedSignIn()
            controller.start()
            assertIs<AppState.Restoring>(state)

            assertTrue(withContext(Dispatchers.Default) { controller.ensureConnected(10_000) })

            assertIs<AppState.Connected>(state)
            assertEquals(1, created.size, "the cold start and the caller share one connection")
        }
    }

    @Test
    fun `ensureConnected is true at once when already connected and online, and asks for nothing`() = runTest {
        withRig {
            connectToFirstHost()

            assertTrue(controller.ensureConnected(1))

            assertEquals(1, created.size)
        }
    }

    @Test
    fun `ensureConnected gives up when the connection does not come in time`() = runTest {
        withRig(script = { HostScript(failure = IOException("host unreachable")) }, autoConnect = { true }) {
            savedSignIn()
            controller.start()
            val connected = assertIs<AppState.Connected>(await { it is AppState.Connected })

            assertFalse(controller.ensureConnected(5_000))

            assertIs<ConnState.Reconnecting>(connected.session.connection.value)
        }
    }

    @Test
    fun `ensureConnected gives up on a connection that failed for good`() = runTest {
        withRig(script = { HostScript(failure = DevTunnelsException.TunnelNotFound("test-tunnel-1")) }, autoConnect = { true }) {
            savedSignIn()
            controller.start()
            val connected = assertIs<AppState.Connected>(await { it is AppState.Connected })

            val before = testScheduler.currentTime
            assertFalse(controller.ensureConnected(60_000))

            assertIs<ConnState.Failed>(connected.session.connection.value)
            assertTrue(testScheduler.currentTime - before < 60_000, "it returned when the connection failed, not when the time ran out")
        }
    }

    @Test
    fun `ensureConnected is false when signed out, and never signs in`() = runTest {
        withRig(autoConnect = { true }) {
            controller.start()
            runCurrent()

            assertFalse(controller.ensureConnected(10_000))

            assertIs<AppState.SignedOut>(state)
            assertTrue(created.isEmpty())
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `ensureConnected is false when monitoring is off, and leaves the host list showing`() = runTest {
        withRig(autoConnect = { false }) {
            restoredToHostList()

            assertFalse(controller.ensureConnected(10_000))

            assertIs<AppState.Hosts>(state)
            assertTrue(created.isEmpty())
        }
    }

    @Test
    fun `ensureConnected is false when the last host is offline`() = runTest {
        withRig(autoConnect = { true }) {
            restoredToHostList(last = "test-tunnel-2")

            assertFalse(controller.ensureConnected(10_000))

            assertIs<AppState.Hosts>(state)
            assertTrue(created.isEmpty())
        }
    }

    /** The cold start has finished with the host list showing and nothing connected, as when monitoring was off at the time. */
    private suspend fun Rig.restoredToHostList(last: String? = "test-tunnel-1") {
        savedSignIn(last)
        controller.start()
        awaitHosts()
        assertIs<AppState.Hosts>(state)
    }

    @Test
    fun `a notification button pressed after the process was killed connects, then decides`() = runTest {
        var monitoring = false
        withRig(script = decideScript { "client-test-tunnel-1" }, interaction = allowed, approvalGate = ApprovalGate { true }, autoConnect = { monitoring }) {
            restoredToHostList()
            monitoring = true

            assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), controller.decideToolCall(CHAT_A, "c1", approve = false, background = true))

            assertEquals(listOf(Triple("chat/toolCallConfirmed", "c1", false)), decisions())
            assertEquals(1, created.size)
            assertEquals(listOf(CHAT_A), channelsSent("unsubscribe").filter { it == CHAT_A }, "opened for the decision and closed after it")
        }
    }

    @Test
    fun `an approval after the process was killed still goes through the device check once connected`() = runTest {
        var monitoring = false
        withRig(script = decideScript { "client-test-tunnel-1" }, interaction = allowed, autoConnect = { monitoring }) {
            restoredToHostList()
            monitoring = true

            assertEquals(InteractResult.NotConfirmed, controller.decideToolCall(CHAT_A, "c1", approve = true, background = true))

            assertTrue(dispatches().isEmpty())
        }
    }

    @Test
    fun `a notification button that cannot get a connection says so and sends nothing`() = runTest {
        withRig(script = decideScript { "" }, interaction = allowed, approvalGate = ApprovalGate { true }, autoConnect = { true }) {
            restoredToHostList(last = "test-tunnel-2")

            assertEquals(InteractResult.NotConnected, controller.decideToolCall(CHAT_A, "c1", approve = false, background = true))
            assertEquals(InteractResult.NotConnected, controller.decideToolCall(CHAT_A, "c1", approve = true, background = true))

            assertTrue(connectors.isEmpty(), "no connection was made, so nothing could have been sent")
        }
    }

    @Test
    fun `a notification button whose connection does not come up in time says so and sends nothing`() = runTest {
        withRig(script = { HostScript(failure = IOException("host unreachable")) }, interaction = allowed, approvalGate = ApprovalGate { true }, autoConnect = { true }) {
            savedSignIn()
            controller.start()
            await { it is AppState.Connected }

            assertEquals(InteractResult.NotConnected, controller.decideToolCall(CHAT_A, "c1", approve = false, background = true))

            assertTrue(connectors.all { it.sockets.isEmpty() }, "the host was never reached")
        }
    }

    @Test
    fun `a notification button answers within its budget when the connection and the chat are both slow`() = runTest {
        var monitoring = false
        val chatNeverLoads = { HostScript(sessionChats = "[${chatSummaryJson(CHAT_A)}]", chatActiveTurn = pendingTool, onChatSubscribe = { _, _ -> null }) }
        withRig(script = chatNeverLoads, connectDelayMs = 9_000, interaction = allowed, approvalGate = ApprovalGate { true }, autoConnect = { monitoring }) {
            restoredToHostList()
            monitoring = true

            val before = testScheduler.currentTime
            val result = controller.decideToolCall(CHAT_A, "c1", approve = false, background = true)

            assertIs<InteractResult.NotSent>(result)
            assertTrue(testScheduler.currentTime - before < 12_000, "connecting and reading the chat share one budget: ${testScheduler.currentTime - before} ms")
            assertTrue(dispatches().isEmpty())
        }
    }

    @Test
    fun `with monitoring off a notification button does not connect, and is not allowed`() = runTest {
        withRig(script = decideScript { "" }, interaction = allowed, approvalGate = ApprovalGate { true }, autoConnect = { false }) {
            restoredToHostList()

            assertEquals(InteractResult.NotAllowed, controller.decideToolCall(CHAT_A, "c1", approve = false, background = true))

            assertTrue(connectors.isEmpty())
        }
    }

    @Test
    fun `the inbox on screen does not wait for a connection that is down, and does not call it a reason to open the app`() = runTest {
        withRig(script = { HostScript(failure = IOException("host unreachable")) }, interaction = allowed, approvalGate = ApprovalGate { true }, autoConnect = { true }) {
            savedSignIn()
            controller.start()
            await { it is AppState.Connected }

            val before = testScheduler.currentTime
            val result = controller.decideToolCall(CHAT_A, "c1", approve = false)

            assertIs<InteractResult.NotSent>(result)
            assertTrue(testScheduler.currentTime - before < 10_000, "no wait for a connection beyond the chat's own: ${testScheduler.currentTime - before} ms")
        }
    }

    @Test
    fun `the inbox on screen never connects by itself, even with monitoring on`() = runTest {
        var monitoring = false
        withRig(script = decideScript { "" }, interaction = allowed, approvalGate = ApprovalGate { true }, autoConnect = { monitoring }) {
            restoredToHostList()
            monitoring = true

            assertEquals(InteractResult.NotAllowed, controller.decideToolCall(CHAT_A, "c1", approve = false))
            assertNull(controller.readWaiting(SESSION_C))

            assertTrue(connectors.isEmpty())
        }
    }

    @Test
    fun `a waiting session is read after the process was killed too, once the connection is made`() = runTest {
        var monitoring = false
        withRig(script = { HostScript(inputNeeded = question) }, autoConnect = { monitoring }) {
            restoredToHostList()
            monitoring = true

            val read = controller.readWaiting(SESSION_C, background = true)

            assertEquals("Which season do you prefer?", read?.text)
            assertEquals(1, created.size)
        }
    }

    @Test
    fun `a waiting session is not read when no connection can be made`() = runTest {
        withRig(autoConnect = { true }) {
            restoredToHostList(last = "test-tunnel-2")

            assertNull(controller.readWaiting(SESSION_C, background = true))

            assertTrue(connectors.isEmpty())
        }
    }

    @Test
    fun `signing out runs what follows it, but a sign-in the host refused does not`() = runTest {
        var ran = 0
        withRig(script = { HostScript(failure = DevTunnelsException.AuthExpired()) }, afterSignOut = { ran++ }) {
            vault.save(savedToken)
            server.json(fixture("list-tunnels.json"))
            controller.start()
            awaitHosts()
            controller.connect(loaded().first().tunnel)
            await { it is AppState.SignedOut }
            runCurrent()

            assertEquals(0, ran, "a refused sign-in is not the person leaving")

            controller.signOut()
            runCurrent()

            assertEquals(1, ran)
        }
    }

    @Test
    fun `an open chat's interactor resolves the session model from the session state or session summary when the chat has no turns`() = runTest {
        val summaryWithModel = fixture("live/list-sessions.json").replace(
            """"resource": "$SESSION_C",""",
            """"_meta": {"ahpd.model": "claude-sonnet-5.5"}, "resource": "$SESSION_C",""",
        )
        withRig(script = { HostScript(listJson = summaryWithModel, sessionChats = "[${chatSummaryJson(CHAT_A)}]") }) {
            connectToFirstHost()
            val interactor = openChatInteractor(this)

            assertEquals(dev.ahpclient.state.ModelChoice("claude-sonnet-5.5"), interactor.modelInUse())
        }
        val stateConfigAndModel = """{"schema":{"type":"object","properties":{}},"values":{}},"_meta":{"ahpd.model":"auto"}"""
        withRig(script = { HostScript(listJson = summaryWithModel, sessionChats = "[${chatSummaryJson(CHAT_A)}]", sessionConfig = stateConfigAndModel) }) {
            connectToFirstHost()
            val interactor = openChatInteractor(this)

            assertEquals(dev.ahpclient.state.ModelChoice("auto"), interactor.modelInUse(), "session state model takes precedence over summary model")
        }
    }
}

class SafeLinksTest {
    @Test
    fun `any plain https page is an acceptable web link`() {
        listOf(
            "https://example.com",
            "https://example.com/path/to/page?q=1&r=two#section",
            "https://docs.example.co.uk:8443/a",
            "https://sub-domain.example.com/%E2%9C%93",
        ).forEach { assertTrue(SafeLinks.isSafeWebLink(it), it) }
    }

    @Test
    fun `a web link must be https with a real ASCII host name and no credentials`() {
        listOf(
            "http://example.com/",
            "javascript:alert(1)",
            "intent://example.com#Intent;scheme=https;end",
            "file:///sdcard/secret.txt",
            "content://com.android.contacts/contacts",
            "data:text/html,<script>alert(1)</script>",
            "mailto:someone@example.com",
            "tel:+15551234567",
            "https://user:pass@example.com/",
            "https://example.com@evil.example/",
            "https://аpple.com/",
            "https://exаmple.com/",
            "https:///no-host",
            "https://",
            "//example.com/x",
            "example.com/x",
            "https://exa mple.com/",
            "https://example.com/a b",
            "",
            "not a uri at all",
        ).forEach { assertFalse(SafeLinks.isSafeWebLink(it), it) }
    }

    @Test
    fun `an enormous web link is refused`() {
        assertFalse(SafeLinks.isSafeWebLink("https://example.com/" + "a".repeat(3_000)))
        assertTrue(SafeLinks.isSafeWebLink("https://example.com/" + "a".repeat(1_000)))
    }

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
