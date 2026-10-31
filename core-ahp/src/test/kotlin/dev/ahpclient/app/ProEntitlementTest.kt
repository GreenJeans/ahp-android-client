package dev.ahpclient.app

import com.microsoft.agenthostprotocol.generated.Implementation
import dev.ahpclient.ahp.CapabilityPolicy
import dev.ahpclient.auth.GitHubDeviceAuth
import dev.ahpclient.direct.DirectHost
import dev.ahpclient.direct.InMemoryDirectHostStore
import dev.ahpclient.security.DirectHostVault
import dev.ahpclient.security.FakeBox
import dev.ahpclient.security.FakeStore
import dev.ahpclient.security.TokenVault
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.FinishedSession
import dev.ahpclient.state.FinishedWatch
import dev.ahpclient.state.HostConnector
import dev.ahpclient.state.HostScript
import dev.ahpclient.state.HostSession
import dev.ahpclient.state.InputNeeded
import dev.ahpclient.state.ScriptedConnector
import dev.ahpclient.tunnel.DevTunnelsClient
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer

private class FakeEntitlementSettings(saved: Boolean = false) : EntitlementSettings {
    private val value = MutableStateFlow(saved)
    var failReads = false
    var failWrites = false
    val writes = mutableListOf<Boolean>()

    val saved: Boolean get() = value.value

    override val unlocked: Flow<Boolean> = flow {
        if (failReads) throw IOException("disk unavailable")
        emit(value.value)
    }

    override suspend fun setUnlocked(unlocked: Boolean) {
        if (failWrites) throw IOException("disk full")
        writes += unlocked
        value.value = unlocked
    }
}

private class StubMonitorSettings(initial: Boolean = false) : MonitorSettings {
    val flow = MutableStateFlow(initial)
    override val enabled: Flow<Boolean> = flow

    override suspend fun setEnabled(on: Boolean) {
        flow.value = on
    }
}

private class StubMonitorService : MonitorService {
    var starts = 0
    var stops = 0

    override fun start(): Boolean {
        starts++
        return true
    }

    override fun stop() {
        stops++
    }
}

private class StubInputSink : InputNeededSink {
    override fun post(request: InputNeeded) {}
    override fun clear(resource: String) {}
}

private class StubWatchSettings : FinishedWatchSettings {
    val flow = MutableStateFlow(FinishedWatch())
    override val watch: Flow<FinishedWatch> = flow

    override suspend fun setAll(on: Boolean) {
        flow.value = flow.value.copy(all = on)
    }

    override suspend fun setSession(resource: String, on: Boolean) {
        flow.value = flow.value.copy(sessions = if (on) flow.value.sessions + resource else flow.value.sessions - resource)
    }
}

private class StubFinishedSink : FinishedSink {
    override fun post(finished: FinishedSession) {}
    override fun clear(resource: String) {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProEntitlementTest {
    private val clientInfo = Implementation(name = "ahp-android-client-test", version = "0.0.1")
    private val hostA = DirectHost("direct:11111111-aaaa", "Laptop", "ws://192.168.1.20:9187")
    private val hostB = DirectHost("direct:22222222-bbbb", "Workstation", "ws://192.168.1.21:9187")

    @Test
    fun `pro entitlement loads saved state and fails closed on read or write error`() = runTest {
        val store = FakeEntitlementSettings(saved = false)
        val pro = ProEntitlement(store)
        pro.load()
        assertFalse(pro.unlocked.value)

        store.failWrites = true
        assertFalse(pro.setUnlocked(true))
        assertFalse(pro.unlocked.value)

        store.failWrites = false
        assertTrue(pro.setUnlocked(true))
        assertTrue(pro.unlocked.value)
        assertEquals(listOf(true), store.writes)

        // Revoking takes effect in memory even if disk write fails.
        store.failWrites = true
        assertFalse(pro.setUnlocked(false))
        assertFalse(pro.unlocked.value)

        // Unreadable store fails closed on load.
        val unreadable = FakeEntitlementSettings(saved = true).apply { failReads = true }
        val proUnreadable = ProEntitlement(unreadable)
        proUnreadable.load()
        assertFalse(proUnreadable.unlocked.value)
    }

    @Test
    fun `alwaysUnlocked keeps pro unlocked regardless of storage`() = runTest {
        val store = FakeEntitlementSettings(saved = false).apply {
            failReads = true
            failWrites = true
        }
        val pro = ProEntitlement(store, alwaysUnlocked = true)
        assertTrue(pro.unlocked.value)

        pro.load()
        assertTrue(pro.unlocked.value)

        assertTrue(pro.setUnlocked(false))
        assertTrue(pro.unlocked.value)
    }

    @Test
    fun `interaction consent refuses write and management tiers while pro is locked`() = runTest {
        var entitled = false
        val interactStore = FakeInteractionSettings(saved = true)
        val manageStore = FakeInteractionSettings(saved = true)
        val consent = InteractionConsent(
            settings = interactStore,
            supported = true,
            managementSettings = manageStore,
            entitled = { entitled },
        )

        consent.load()
        assertFalse(consent.allowed.value)
        assertFalse(consent.managementAllowed.value)
        assertSame(CapabilityPolicy.READ, consent.policy())

        assertFalse(consent.set(true))
        assertFalse(consent.setManagement(true))
        assertTrue(interactStore.writes.isEmpty())
        assertTrue(manageStore.writes.isEmpty())

        entitled = true
        assertTrue(consent.set(true))
        assertTrue(consent.setManagement(true))
        assertSame(CapabilityPolicy.MANAGE, consent.policy())

        consent.revokeEntitlement()
        assertFalse(consent.allowed.value)
        assertFalse(consent.managementAllowed.value)
        assertSame(CapabilityPolicy.READ, consent.policy())
    }

    @Test
    fun `monitor coordinator refuses to enable while locked and stops service when entitlement is revoked`() = runTest {
        val entitled = MutableStateFlow(false)
        val settings = StubMonitorSettings(initial = false)
        val service = StubMonitorService()
        val watch = StubWatchSettings()
        val session = HostSession(ScriptedConnector(HostScript()), backgroundScope, clientInfo)
        session.start()
        runCurrent()
        val appState = MutableStateFlow<AppState>(AppState.Connected(HostRef.Direct(hostA), session))

        val coordinator = MonitorCoordinator(
            state = appState,
            settings = settings,
            service = service,
            sink = StubInputSink(),
            scope = backgroundScope,
            finished = FinishedAlerts(watch, StubFinishedSink()),
            entitled = entitled,
        )
        coordinator.start()
        runCurrent()

        coordinator.setEnabled(true)
        coordinator.setWatchAll(true)
        coordinator.setWatched("ahp-session:/1", true)
        assertFalse(settings.flow.value)
        assertFalse(watch.flow.value.all)
        assertTrue(watch.flow.value.sessions.isEmpty())
        assertNull(coordinator.status.value)
        assertEquals(0, service.starts)

        entitled.value = true
        coordinator.setEnabled(true)
        runCurrent()
        assertTrue(coordinator.enabled.first())
        assertEquals(1, service.starts)

        // Revoking Pro entitlement stops the active foreground monitor immediately.
        entitled.value = false
        runCurrent()
        assertFalse(coordinator.enabled.first())
        assertNull(coordinator.status.value)
        assertTrue(service.stops >= 1)
        session.stop()
    }

    @Test
    fun `controller enforces single host and read tier when pro is locked and revokes cleanly when locked mid-session`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val pro = ProEntitlement(InMemoryEntitlementSettings(initial = false))
            val consents = mutableMapOf<String, InteractionConsent>()
            val controller = AppController(
                auth = GitHubDeviceAuth(baseUrl = server.url("/").toString()),
                vault = TokenVault(FakeBox(), FakeStore()),
                devTunnels = DevTunnelsClient(serviceUrl = server.url("/").toString()),
                lastHost = object : LastHostStore {
                    var id: String? = null
                    override suspend fun get(): String? = id
                    override suspend fun set(tunnelId: String) { id = tunnelId }
                },
                sessions = HostSessionFactory { _, _ -> HostSession(ScriptedConnector(HostScript(), HostScript()), backgroundScope, clientInfo) },
                scope = backgroundScope,
                directStore = InMemoryDirectHostStore(listOf(hostA, hostB)),
                directVault = DirectHostVault(FakeBox(), FakeStore()),
                directSessions = DirectSessionFactory { _, _, consent ->
                    val scripted = ScriptedConnector(HostScript(), HostScript(), HostScript(), HostScript())
                    val connector = HostConnector { clientId, seq, scope ->
                        scripted.policy = consent.policy()
                        scripted.connect(clientId, seq, scope)
                    }
                    HostSession(connector, backgroundScope, clientInfo)
                },
                directConsent = { host ->
                    consents.getOrPut(host.id) {
                        InteractionConsent(entitled = { pro.unlocked.value })
                    }
                },
                pro = pro,
            )

            controller.start()
            runCurrent()
            assertFalse(controller.proUnlocked.value)

            controller.connect(hostA)
            runCurrent()
            val firstConnected = assertIs<AppState.Connected>(controller.state.value)
            assertEquals(hostA.id, firstConnected.host.id)
            assertEquals(1, controller.connectedHosts.value.size)

            // While Pro is locked, turning on interaction/management is refused and connectHost switches exclusively instead of multi-connecting.
            assertFalse(controller.setInteraction(true))
            assertFalse(controller.setManagement(true))
            assertFalse(controller.interactionAllowed.value)

            controller.connectHost(HostRef.Direct(hostB))
            runCurrent()
            assertEquals(1, controller.connectedHosts.value.size)
            assertEquals(hostB.id, controller.connectedHosts.value.single().host.id)
            assertEquals(ConnState.Stopped, firstConnected.session.connection.value)

            // Unlock Pro: multi-host and interaction/management now succeed.
            assertTrue(controller.setProUnlocked(true))
            assertTrue(controller.proUnlocked.value)

            controller.connectHost(HostRef.Direct(hostA))
            runCurrent()
            assertEquals(2, controller.connectedHosts.value.size)
            assertTrue(controller.setInteraction(true))
            assertTrue(controller.setManagement(true))
            assertTrue(controller.interactionAllowed.value)
            assertTrue(controller.managementAllowed.value)

            // Revoke Pro while connected to two hosts with write/manage permissions active:
            // permissions are cleared, extra host B is stopped, and only the focused host A remains on READ.
            assertTrue(controller.setProUnlocked(false))
            runCurrent()
            assertFalse(controller.proUnlocked.value)
            assertFalse(controller.interactionAllowed.value)
            assertFalse(controller.managementAllowed.value)
            assertEquals(1, controller.connectedHosts.value.size)
            assertEquals(hostA.id, controller.connectedHosts.value.single().host.id)
            assertFalse( controller.connectedHosts.value.single().session.canInteract.value)

            controller.disconnect()
            runCurrent()
        }
    }

    @Test
    fun `purchasePro and restorePro unlock entitlement when billing succeeds and surface errors when store or disk fails`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val settings = FakeEntitlementSettings(saved = false)
            val pro = ProEntitlement(settings)
            var nextPurchase: BillingResult = BillingResult.Cancelled
            var nextRestore: BillingResult = BillingResult.NotFound
            val billing = object : BillingGateway {
                override suspend fun purchasePro(): BillingResult = nextPurchase
                override suspend fun restorePurchases(): BillingResult = nextRestore
            }
            val controller = AppController(
                auth = GitHubDeviceAuth(baseUrl = server.url("/").toString()),
                vault = TokenVault(FakeBox(), FakeStore()),
                devTunnels = DevTunnelsClient(serviceUrl = server.url("/").toString()),
                lastHost = object : LastHostStore {
                    var id: String? = null
                    override suspend fun get(): String? = id
                    override suspend fun set(tunnelId: String) { id = tunnelId }
                },
                sessions = HostSessionFactory { _, _ -> HostSession(ScriptedConnector(HostScript()), backgroundScope, clientInfo) },
                scope = backgroundScope,
                pro = pro,
                billing = billing,
            )

            controller.start()
            runCurrent()
            assertFalse(controller.proUnlocked.value)

            // Cancelled purchase leaves Pro locked.
            assertSame(BillingResult.Cancelled, controller.purchasePro())
            assertFalse(controller.proUnlocked.value)

            // Restore with no purchase returns NotFound and leaves Pro locked.
            assertSame(BillingResult.NotFound, controller.restorePro())
            assertFalse(controller.proUnlocked.value)

            // Purchase succeeds, but disk fails: fails closed and returns Unavailable.
            settings.failWrites = true
            nextPurchase = BillingResult.Unlocked
            val writeFail = assertIs<BillingResult.Unavailable>(controller.purchasePro())
            assertTrue(writeFail.message.contains("Could not save"))
            assertFalse(controller.proUnlocked.value)

            // Disk recovered: purchase unlocks Pro and persists.
            settings.failWrites = false
            assertSame(BillingResult.Unlocked, controller.purchasePro())
            assertTrue(controller.proUnlocked.value)
            assertTrue(settings.saved)

            // Revoke, then restore: restore unlocks Pro again.
            assertTrue(controller.setProUnlocked(false))
            assertFalse(controller.proUnlocked.value)
            nextRestore = BillingResult.Unlocked
            assertSame(BillingResult.Unlocked, controller.restorePro())
            assertTrue(controller.proUnlocked.value)
        }
    }
}
