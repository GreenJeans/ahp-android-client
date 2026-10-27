package dev.ahpclient.app

import dev.ahpclient.auth.GitHubDeviceAuth
import dev.ahpclient.auth.GitHubToken
import dev.ahpclient.auth.Secret
import dev.ahpclient.direct.DirectHost
import dev.ahpclient.direct.InMemoryDirectHostStore
import dev.ahpclient.security.DirectHostVault
import dev.ahpclient.security.FakeBox
import dev.ahpclient.security.FakeStore
import dev.ahpclient.security.SessionListCache
import dev.ahpclient.security.TokenVault
import dev.ahpclient.state.Backoff
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.HostConnector
import dev.ahpclient.state.HostScript
import dev.ahpclient.state.HostSession
import dev.ahpclient.state.ScriptedConnector
import dev.ahpclient.state.fixture
import dev.ahpclient.tunnel.DevTunnelsClient
import com.microsoft.agenthostprotocol.generated.Implementation
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

private class RecordingLastHost(var id: String? = null) : LastHostStore {
    override suspend fun get(): String? = id

    override suspend fun set(tunnelId: String) {
        id = tunnelId
    }
}

/** Hosts added by address: usable with no GitHub sign-in, each with a permission and a token of its own. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppControllerDirectHostsTest {
    private val clientInfo = Implementation(name = "ahp-android-client-test", version = "0.0.1")
    private val savedToken = GitHubToken(Secret("ghu_saved"), Secret("ghr_saved"), null)

    private class Rig(
        val server: MockWebServer,
        val controller: AppController,
        val github: TokenVault,
        val directStore: InMemoryDirectHostStore,
        val directVault: DirectHostVault,
        val directBlob: FakeStore,
        val lastHost: RecordingLastHost,
        val consents: MutableMap<String, InteractionConsent>,
        val consentSettings: MutableMap<String, Pair<dev.ahpclient.app.FakeInteractionSettings, dev.ahpclient.app.FakeInteractionSettings>>,
        val tokensSeen: MutableList<Pair<String, String?>>,
        val connectors: MutableList<ScriptedConnector>,
        val cache: SessionListCache,
        val cacheStore: FakeStore,
    ) {
        val state get() = controller.state.value
    }

    private val laptop = DirectHost("direct:11111111-aaaa", "Laptop", "ws://192.168.1.20:9187")
    private val other = DirectHost("direct:22222222-bbbb", "Server", "wss://agents.example.com")

    private suspend fun TestScope.withRig(
        saved: List<DirectHost> = emptyList(),
        tokens: Map<String, String> = emptyMap(),
        lastId: String? = null,
        allowed: Set<String> = emptySet(),
        autoConnect: Boolean = false,
        signedIn: Boolean = false,
        script: () -> HostScript = { HostScript() },
        block: suspend Rig.() -> Unit,
    ) = MockWebServer().use { server ->
        server.start()
        val githubStore = FakeStore()
        val github = TokenVault(FakeBox(), githubStore)
        if (signedIn) github.save(savedToken)
        val directBlob = FakeStore()
        val directVault = DirectHostVault(FakeBox(), directBlob)
        tokens.forEach { (id, token) -> directVault.put(id, Secret(token)) }
        val store = InMemoryDirectHostStore(saved)
        val last = RecordingLastHost(lastId)
        val connectors = mutableListOf<ScriptedConnector>()
        val seen = mutableListOf<Pair<String, String?>>()
        val consents = mutableMapOf<String, InteractionConsent>()
        val settings = mutableMapOf<String, Pair<FakeInteractionSettings, FakeInteractionSettings>>()
        val cacheStore = FakeStore()
        val cache = SessionListCache(FakeBox(), cacheStore)
        val tunnelFactory = HostSessionFactory { tunnel, accessToken ->
            val scripted = ScriptedConnector(script(), script(), script()).also { connectors += it }
            HostSession(HostConnector { id, seq, scope: CoroutineScope -> accessToken(); scripted.connect(id, seq, scope) }, backgroundScope, clientInfo, Backoff(), clientId = "client-${tunnel.tunnelId}")
        }
        val directFactory = DirectSessionFactory { host, token, consent ->
            val scripted = ScriptedConnector(script(), script(), script()).also { connectors += it }
            val connector = HostConnector { id, seq, scope: CoroutineScope ->
                seen += host.id to token()?.reveal()
                scripted.policy = consent.policy()
                scripted.connect(id, seq, scope)
            }
            HostSession(connector, backgroundScope, clientInfo, Backoff(), clientId = "client-${host.id}", cache = cache, cacheKey = host.id)
        }
        val controller = AppController(
            GitHubDeviceAuth(baseUrl = server.url("/").toString()), github, DevTunnelsClient(serviceUrl = server.url("/").toString()), last, tunnelFactory, backgroundScope,
            autoConnect = { autoConnect },
            sessionCache = cache,
            directStore = store,
            directVault = directVault,
            directSessions = directFactory,
            directConsent = { host ->
                val i = FakeInteractionSettings(saved = host.id in allowed)
                val m = FakeInteractionSettings()
                settings[host.id] = i to m
                InteractionConsent(i, true, m).also { consents[host.id] = it }
            },
        )
        Rig(server, controller, github, store, directVault, directBlob, last, consents, settings, seen, connectors, cache, cacheStore).block()
    }

    private suspend fun Rig.awaitHosts(): AppState.Hosts = controller.state.first { it is AppState.Hosts && it.list !is HostList.Loading } as AppState.Hosts

    @Test
    fun `with hosts added and no sign in the app opens on the host list, not the sign in screen`() = runTest {
        withRig(saved = listOf(laptop, other)) {
            controller.start()
            val hosts = awaitHosts()

            assertEquals(HostList.NotSignedIn, hosts.list)
            assertEquals(listOf("Laptop", "Server"), hosts.direct.map { it.host.name })
            assertFalse(controller.isSignedIn)
        }
    }

    @Test
    fun `with nothing added and no sign in the sign in screen is shown, with the way to skip it`() = runTest {
        withRig {
            controller.start()
            runCurrent()
            assertEquals(AppState.SignedOut(SignInStep.Idle, null), state)

            controller.useDirectHosts()

            val hosts = assertIs<AppState.Hosts>(state)
            assertEquals(HostList.NotSignedIn, hosts.list)
            assertTrue(hosts.direct.isEmpty())
        }
    }

    @Test
    fun `a host is added, kept with its token in the vault, and connected to with no github token`() = runTest {
        withRig {
            controller.start()
            runCurrent()
            controller.useDirectHosts()

            val added = assertIs<AppController.AddHostResult.Added>(controller.addDirectHost("  Laptop ", "192.168.1.20", " tok-1 "))

            assertEquals("ws://192.168.1.20:9187", added.host.url)
            assertEquals("Laptop", added.host.name)
            assertEquals(listOf(added.host), directStore.load())
            assertEquals("tok-1", directVault.get(added.host.id)?.reveal())
            assertEquals(listOf(added.host), (state as AppState.Hosts).direct.map { it.host })

            controller.connect(added.host)
            val connected = assertIs<AppState.Connected>(state)
            connected.session.connection.first { it == ConnState.Online }

            assertEquals(HostRef.Direct(added.host), connected.host)
            assertEquals(listOf<Pair<String, String?>>(added.host.id to "tok-1"), tokensSeen)
            assertEquals(0, server.requestCount, "no call to GitHub or the Dev Tunnels service")
            assertEquals(added.host.id, lastHost.id)
        }
    }

    @Test
    fun `the name defaults to the host and a host that takes no token keeps none`() = runTest {
        withRig {
            controller.start()
            runCurrent()
            controller.useDirectHosts()

            val added = assertIs<AppController.AddHostResult.Added>(controller.addDirectHost("", "localhost:9187", ""))

            assertEquals("localhost", added.host.name)
            assertNull(directVault.get(added.host.id))
            assertNull(directBlob.blob)
        }
    }

    @Test
    fun `an address that is refused, repeated or one too many is not added`() = runTest {
        withRig(saved = List(20) { DirectHost("direct:%08d-x".format(it), "h$it", "wss://h$it.example.com") }) {
            controller.start()
            runCurrent()
            assertTrue(assertIs<AppController.AddHostResult.Invalid>(controller.addDirectHost("x", "wss://new.example.com", "")).reason.contains("as many"))
        }
        withRig(saved = listOf(laptop)) {
            controller.start()
            runCurrent()
            assertTrue(assertIs<AppController.AddHostResult.Invalid>(controller.addDirectHost("x", "8.8.8.8:9187", "")).reason.contains("wss://"))
            assertTrue(assertIs<AppController.AddHostResult.Invalid>(controller.addDirectHost("again", "192.168.1.20:9187", "")).reason.contains("already added"))
            assertTrue(assertIs<AppController.AddHostResult.Invalid>(controller.addDirectHost("x", "ws://192.168.1.9:9187/?tkn=abc", "")).reason.contains("token"))
            assertEquals(listOf(laptop), directStore.load())
        }
    }

    @Test
    fun `a host that could not be saved is not added and leaves no token behind`() = runTest {
        withRig {
            controller.start()
            runCurrent()
            controller.useDirectHosts()
            val failing = object : dev.ahpclient.direct.DirectHostStore {
                override suspend fun load() = emptyList<DirectHost>()
                override suspend fun save(hosts: List<DirectHost>) = throw IOException("disk full")
            }
            val c = AppController(
                GitHubDeviceAuth(baseUrl = server.url("/").toString()), github, DevTunnelsClient(serviceUrl = server.url("/").toString()), lastHost,
                { _, _ -> error("unused") }, backgroundScope, directStore = failing, directVault = directVault,
                directSessions = DirectSessionFactory { _, _, _ -> error("unused") },
            )
            c.start()
            runCurrent()

            assertEquals(AppController.AddHostResult.NotSaved, c.addDirectHost("x", "192.168.1.9", "tok"))
            assertNull(directBlob.blob)
        }
    }

    @Test
    fun `a new direct host starts read only and its permission is its own`() = runTest {
        withRig(saved = listOf(laptop, other), allowed = setOf(laptop.id)) {
            controller.start()
            awaitHosts()

            controller.connect(other)
            val connected = assertIs<AppState.Connected>(state)
            connected.session.connection.first { it == ConnState.Online }
            assertFalse(controller.interactionAllowed.value)
            assertFalse(connected.session.canInteract.value)
            assertEquals("Server", controller.consentScope)

            // Allowing it here is allowed here only.
            assertTrue(controller.setInteraction(true))
            assertTrue(controller.interactionAllowed.value)
            assertTrue(consentSettings.getValue(other.id).first.saved)
            assertTrue(consentSettings.getValue(laptop.id).first.writes.isEmpty(), "the other host choice is untouched")
            connected.session.connection.first { it == ConnState.Online }
            runCurrent()
            assertTrue(connected.session.canInteract.value)

            controller.disconnect()
            awaitHosts()
            assertNull(controller.consentScope)
            assertFalse(controller.interactionAllowed.value, "back on the tunnel hosts' own permission")
        }
    }

    @Test
    fun `a host that was allowed before connects under that tier`() = runTest {
        withRig(saved = listOf(laptop), allowed = setOf(laptop.id)) {
            controller.start()
            awaitHosts()

            controller.connect(laptop)
            val connected = assertIs<AppState.Connected>(state)
            connected.session.connection.first { it == ConnState.Online }
            runCurrent()

            assertTrue(controller.interactionAllowed.value)
            assertTrue(connected.session.canInteract.value)
            assertFalse(connected.session.canManage.value)
            assertFalse(controller.managementAllowed.value)
        }
    }

    @Test
    fun `permission flags follow the host switched to`() = runTest {
        withRig(saved = listOf(laptop, other), allowed = setOf(laptop.id)) {
            controller.start()
            awaitHosts()
            val seen = mutableListOf<Boolean>()
            val job = backgroundScope.launch { controller.interactionAllowed.collect { seen += it } }
            runCurrent()

            controller.connect(laptop)
            runCurrent()
            controller.disconnect()
            runCurrent()
            controller.connect(other)
            runCurrent()
            job.cancel()

            assertEquals(listOf(false, true, false), seen)
        }
    }

    @Test
    fun `removing a host removes its token, its permission and the saved list, and disconnects it`() = runTest {
        withRig(saved = listOf(laptop, other), tokens = mapOf(laptop.id to "tok-L", other.id to "tok-S"), allowed = setOf(laptop.id)) {
            controller.start()
            awaitHosts()
            controller.connect(laptop)
            (state as AppState.Connected).session.connection.first { it == ConnState.Online }
            cache.save(laptop.id, emptyList())

            controller.removeDirectHost(laptop.id)

            assertIs<AppState.Hosts>(state)
            assertEquals(listOf(other), directStore.load())
            assertNull(directVault.get(laptop.id))
            assertEquals("tok-S", directVault.get(other.id)?.reveal())
            assertNull(cacheStore.blob)
            assertFalse(consentSettings.getValue(laptop.id).first.saved)
            assertEquals(listOf(other), (state as AppState.Hosts).direct.map { it.host })
        }
    }

    @Test
    fun `signing out of github leaves hosts added by address and their tokens alone`() = runTest {
        withRig(saved = listOf(laptop), tokens = mapOf(laptop.id to "tok-L"), signedIn = true) {
            server.enqueue(MockResponse().setBody(fixture("list-tunnels.json")).addHeader("Content-Type", "application/json"))
            controller.start()
            awaitHosts()
            assertTrue(controller.isSignedIn)

            controller.signOut()
            controller.state.first { it is AppState.SignedOut }
            runCurrent()

            assertFalse(controller.isSignedIn)
            assertEquals(listOf(laptop), directStore.load())
            assertEquals("tok-L", directVault.get(laptop.id)?.reveal())
            controller.useDirectHosts()
            assertEquals(listOf(laptop), (state as AppState.Hosts).direct.map { it.host })
        }
    }

    @Test
    fun `signed in, the tunnel hosts and the added hosts are listed together, and a tunnel failure does not hide the added ones`() = runTest {
        withRig(saved = listOf(laptop), signedIn = true) {
            server.enqueue(MockResponse().setResponseCode(503))
            controller.start()
            val hosts = awaitHosts()

            assertIs<HostList.Failed>(hosts.list)
            assertEquals(listOf(laptop), hosts.direct.map { it.host })
        }
    }

    @Test
    fun `a direct host and a tunnel host are never live together`() = runTest {
        withRig(saved = listOf(laptop, other)) {
            controller.start()
            awaitHosts()
            controller.connect(laptop)
            val first = assertIs<AppState.Connected>(state)

            controller.connect(other)

            assertEquals(HostRef.Direct(laptop), first.host)
            assertEquals(HostRef.Direct(laptop), (state as AppState.Connected).host, "a second connect from a screen that is not the host list is ignored")
            assertEquals(1, connectors.size)
        }
    }

    @Test
    fun `a host that is no longer on the list cannot be connected to`() = runTest {
        withRig(saved = listOf(laptop)) {
            controller.start()
            awaitHosts()

            controller.connect(other)

            assertIs<AppState.Hosts>(state)
            assertTrue(connectors.isEmpty())
        }
    }

    @Test
    fun `the last host, when it is a direct one, is connected to by itself with no sign in, only when allowed`() = runTest {
        withRig(saved = listOf(laptop, other), lastId = other.id, autoConnect = false) {
            controller.start()
            awaitHosts()
            runCurrent()
            assertIs<AppState.Hosts>(state)
            assertEquals(other.id, (state as AppState.Hosts).direct.first().host.id, "the last used one is listed first")
            assertTrue((state as AppState.Hosts).direct.first().isLastUsed)
        }
        withRig(saved = listOf(laptop, other), lastId = other.id, autoConnect = true, tokens = mapOf(other.id to "tok-S")) {
            controller.start()
            val connected = controller.state.first { it is AppState.Connected } as AppState.Connected
            connected.session.connection.first { it == ConnState.Online }

            assertEquals(HostRef.Direct(other), connected.host)
            assertEquals(listOf<Pair<String, String?>>(other.id to "tok-S"), tokensSeen)
        }
    }

    @Test
    fun `ensureConnected makes the connection for a notification button when the last host is a direct one`() = runTest {
        withRig(saved = listOf(laptop), lastId = laptop.id, autoConnect = true) {
            controller.start()

            assertTrue(controller.ensureConnected(10_000))
            assertEquals(HostRef.Direct(laptop), (state as AppState.Connected).host)
        }
    }

    @Test
    fun `from the host list of a person who is not signed in, signing in starts the sign in`() = runTest {
        withRig(saved = listOf(laptop)) {
            server.enqueue(MockResponse().setBody("""{"device_code":"dc","user_code":"ABCD-1234","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}""").addHeader("Content-Type", "application/json"))
            controller.start()
            awaitHosts()

            controller.signIn()
            val signedOut = controller.state.first { it is AppState.SignedOut && it.step is SignInStep.AwaitingApproval }

            assertIs<AppState.SignedOut>(signedOut)
        }
    }

    @Test
    fun `without a way to build direct sessions nothing can be added`() = runTest {
        withRig {
            val bare = AppController(
                GitHubDeviceAuth(baseUrl = server.url("/").toString()), github, DevTunnelsClient(serviceUrl = server.url("/").toString()), lastHost,
                { _, _ -> error("unused") }, backgroundScope,
            )
            bare.start()
            runCurrent()
            assertIs<AppController.AddHostResult.Invalid>(bare.addDirectHost("x", "192.168.1.9", ""))
        }
    }
}
