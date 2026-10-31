package dev.ahpclient.app

import com.microsoft.agenthostprotocol.generated.Implementation
import dev.ahpclient.auth.GitHubDeviceAuth
import dev.ahpclient.auth.GitHubToken
import dev.ahpclient.auth.Secret
import dev.ahpclient.direct.DirectHost
import dev.ahpclient.direct.InMemoryDirectHostStore
import dev.ahpclient.security.FakeBox
import dev.ahpclient.security.FakeStore
import dev.ahpclient.security.TokenVault
import dev.ahpclient.state.Backoff
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.HostConnector
import dev.ahpclient.state.HostScript
import dev.ahpclient.state.HostSession
import dev.ahpclient.state.SESSION_C
import dev.ahpclient.state.ScriptedConnector
import dev.ahpclient.state.fixture
import dev.ahpclient.tunnel.DevTunnelsClient
import dev.ahpclient.tunnel.TunnelInfo
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

@OptIn(ExperimentalCoroutinesApi::class)
class AppControllerMultiHostTest {
    private val clientInfo = Implementation(name = "ahp-android-client-test", version = "0.0.1")
    private val savedToken = GitHubToken(Secret("ghu_saved"), Secret("ghr_saved"), null)

    @Test
    fun `connectHost keeps multiple hosts connected concurrently and showSession switches the focused host when needed`() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(fixture("list-tunnels.json"))
                    .addHeader("Content-Type", "application/json"),
            )
            val vault = TokenVault(FakeBox(), FakeStore())
            vault.save(savedToken)
            val directStore = InMemoryDirectHostStore()

            val tunnelFactory = HostSessionFactory { tunnel: TunnelInfo, _ ->
                val scripted = ScriptedConnector(HostScript())
                val connector = HostConnector { clientId, seq, scope: CoroutineScope ->
                    scripted.connect(clientId, seq, scope)
                }
                HostSession(connector, backgroundScope, clientInfo, Backoff(), clientId = "client-${tunnel.tunnelId}")
            }
            val directFactory = DirectSessionFactory { host: DirectHost, _, _ ->
                val scripted = ScriptedConnector(HostScript())
                val connector = HostConnector { clientId, seq, scope: CoroutineScope ->
                    scripted.connect(clientId, seq, scope)
                }
                HostSession(connector, backgroundScope, clientInfo, Backoff(), clientId = "client-${host.id}")
            }
            val lastHost = object : LastHostStore {
                var id: String? = null
                override suspend fun get(): String? = id
                override suspend fun set(tunnelId: String) {
                    id = tunnelId
                }
            }
            val auth = GitHubDeviceAuth(baseUrl = server.url("/").toString(), now = { Instant.parse("2026-10-22T12:00:00Z") })
            val tunnels = DevTunnelsClient(serviceUrl = server.url("/").toString())
            val controller = AppController(
                auth = auth,
                vault = vault,
                devTunnels = tunnels,
                lastHost = lastHost,
                sessions = tunnelFactory,
                scope = backgroundScope,
                directStore = directStore,
                directSessions = directFactory,
            )

            controller.start()
            controller.state.first { it is AppState.Hosts }
            val loaded = controller.hosts.first { it.list is HostList.Loaded }.list as HostList.Loaded
            val added = assertIs<AppController.AddHostResult.Added>(
                controller.addDirectHost("Lab Box", "192.168.1.50:8123", ""),
            )
            val tunnelHost = HostRef.Tunnel(loaded.hosts.first().tunnel)
            val directRef = HostRef.Direct(added.host)

            // Connect the first host (tunnel)
            controller.connectHost(tunnelHost)
            runCurrent()
            val state1 = assertIs<AppState.Connected>(controller.state.value)
            state1.session.connection.first { it == ConnState.Online }
            assertEquals(listOf(tunnelHost.id), controller.connectedHosts.value.map { it.host.id })

            // Connect the second host concurrently (direct)
            controller.connectHost(directRef)
            runCurrent()
            assertEquals(listOf(tunnelHost.id, directRef.id), controller.connectedHosts.value.map { it.host.id })
            val secondSession = controller.connectedHosts.value.first { it.host.id == directRef.id }.session
            secondSession.connection.first { it == ConnState.Online }

            // Focused host is now the newly connected second host, while the first stays online in connectedHosts
            assertEquals(directRef.id, (controller.state.value as AppState.Connected).host.id)

            // Open a session on the first host: switches focused host back to the first host without disconnecting the second
            controller.showSession(SESSION_C, hostId = tunnelHost.id)
            runCurrent()
            val switched = assertIs<AppState.Connected>(controller.state.value)
            assertEquals(tunnelHost.id, switched.host.id)
            assertSame(state1.session, switched.session)
            assertEquals(SESSION_C, switched.open?.uri)
            assertEquals(2, controller.connectedHosts.value.size)

            // Disconnect only the focused host: falls back to the remaining connected host
            controller.disconnectHost(tunnelHost.id)
            runCurrent()
            val remaining = assertIs<AppState.Connected>(controller.state.value)
            assertEquals(directRef.id, remaining.host.id)
            assertEquals(listOf(directRef.id), controller.connectedHosts.value.map { it.host.id })

            // Disconnect all hosts: returns to Hosts screen
            controller.disconnect()
            runCurrent()
            assertIs<AppState.Hosts>(controller.state.value)
            assertEquals(emptyList(), controller.connectedHosts.value)
        }
    }
}
