package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.Implementation
import dev.ahpclient.tunnel.DevTunnelsException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

/** [HostSession] with a network to watch: no attempts without one, a fresh start when one returns, and a dead socket ended at once when it changes. */
@OptIn(ExperimentalCoroutinesApi::class)
class HostSessionNetworkTest {
    private val info = Implementation(name = "ahp-android-client-test", version = "0.0.1")
    private val wifi = NetworkState.Available("wifi")
    private val mobile = NetworkState.Available("mobile")

    private class Rig(val connector: ScriptedConnector, val host: HostSession, val network: MutableStateFlow<NetworkState>) {
        val socket get() = connector.sockets.last()
    }

    /** [whileConnecting] runs at the start of each connect call, with the number of that call counting from 0: a network that changes as a connection is made. */
    private fun TestScope.rig(
        vararg scripts: HostScript,
        network: NetworkState = wifi,
        whileConnecting: (call: Int, network: MutableStateFlow<NetworkState>) -> Unit = { _, _ -> },
    ): Rig {
        val flow = MutableStateFlow(network)
        val scripted = ScriptedConnector(*scripts)
        var calls = 0
        val connector = HostConnector { clientId, serverSeq, scope ->
            whileConnecting(calls++, flow)
            scripted.connect(clientId, serverSeq, scope)
        }
        return Rig(scripted, HostSession(connector, backgroundScope, info, Backoff(jitter = 0.0), "client-1", flow), flow)
    }

    private fun failing() = HostScript(failure = IOException("boom"))

    private suspend fun HostSession.awaitState(match: (ConnState) -> Boolean): ConnState = withTimeout(300_000) { connection.first(match) }

    private suspend fun Rig.start(): Rig = also {
        host.start()
        host.awaitState { it == ConnState.Online }
    }

    /** Every state the session passes through from now on, as it happens. */
    private fun TestScope.record(host: HostSession): List<ConnState> {
        val seen = mutableListOf<ConnState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { host.connection.collect { seen += it } }
        return seen
    }

    @Test
    fun `offline from the start makes no connection attempt and says it is waiting`() = runTest {
        val rig = rig(HostScript(), network = NetworkState.Unavailable)

        rig.host.start()
        advanceTimeBy(600_000)

        assertEquals(ConnState.WaitingForNetwork, rig.host.connection.value)
        assertEquals(0, rig.connector.connects.size)
    }

    @Test
    fun `when the network returns the first connection starts at once, whatever backoff was pending`() = runTest {
        val rig = rig(failing(), failing(), failing(), HostScript())
        rig.host.start()
        rig.host.awaitState { it is ConnState.Reconnecting && it.attempt == 3 }
        rig.network.value = NetworkState.Unavailable
        runCurrent()
        advanceTimeBy(600_000)
        assertEquals(ConnState.WaitingForNetwork, rig.host.connection.value)
        assertEquals(3, rig.connector.connects.size, "nothing was tried while there was no network")

        rig.network.value = wifi
        runCurrent()

        assertEquals(4, rig.connector.connects.size)
        assertEquals(ConnState.Online, rig.host.connection.value)
    }

    @Test
    fun `a failure after the network returns is attempt one again, not a continuation of the old schedule`() = runTest {
        val rig = rig(failing(), failing(), failing(), HostScript())
        rig.host.start()
        rig.host.awaitState { it is ConnState.Reconnecting && it.attempt == 2 }
        rig.network.value = NetworkState.Unavailable
        runCurrent()
        assertEquals(ConnState.WaitingForNetwork, rig.host.connection.value)

        rig.network.value = wifi
        val retry = rig.host.awaitState { it is ConnState.Reconnecting }
        val failedAt = testScheduler.currentTime
        rig.host.awaitState { it == ConnState.Online }

        assertEquals(1, assertIs<ConnState.Reconnecting>(retry).attempt)
        assertEquals(1_000L, testScheduler.currentTime - failedAt, "the wait was the first step of the schedule")
        assertEquals(4, rig.connector.connects.size)
    }

    @Test
    fun `a change of network while online closes the socket and the next connection resumes with reconnect`() = runTest {
        val rig = rig(HostScript(), HostScript()).start()
        val first = rig.socket
        assertEquals(listOf("initialize", "listSessions"), first.sentMethods)
        val changedAt = testScheduler.currentTime

        rig.network.value = mobile
        runCurrent()

        assertEquals(2, rig.connector.sockets.size)
        assertFalse(first.send("{}"), "the socket made on the old network was closed")
        assertEquals(listOf("reconnect", "listSessions"), rig.socket.sentMethods)
        assertEquals(ConnState.Online, rig.host.connection.value)
        assertEquals(changedAt, testScheduler.currentTime, "no wait in between")
    }

    @Test
    fun `losing the network while online waits for it rather than counting a failure, and resumes on return`() = runTest {
        val rig = rig(HostScript(), HostScript())
        val seen = record(rig.host)
        rig.start()

        rig.network.value = NetworkState.Unavailable
        runCurrent()
        advanceTimeBy(600_000)
        assertEquals(ConnState.WaitingForNetwork, rig.host.connection.value)
        assertEquals(1, rig.connector.connects.size)

        rig.network.value = wifi
        runCurrent()

        assertEquals(ConnState.Online, rig.host.connection.value)
        assertEquals(listOf("reconnect", "listSessions"), rig.socket.sentMethods)
        assertTrue(ConnState.WaitingForNetwork in seen)
        assertTrue(seen.none { it is ConnState.Reconnecting }, "a lost network is not a failed attempt")
    }

    @Test
    fun `a change of network during a backoff wait ends the wait`() = runTest {
        val rig = rig(failing(), failing(), failing(), HostScript())
        rig.host.start()
        rig.host.awaitState { it is ConnState.Reconnecting && it.attempt == 3 }
        val waitingSince = testScheduler.currentTime

        rig.network.value = mobile
        runCurrent()

        assertEquals(4, rig.connector.connects.size)
        assertEquals(ConnState.Online, rig.host.connection.value)
        assertEquals(waitingSince, testScheduler.currentTime, "the four second wait was not served")
    }

    @Test
    fun `a change of network during a backoff wait starts the schedule again`() = runTest {
        val rig = rig(failing(), failing(), failing(), failing(), HostScript())
        val seen = record(rig.host)
        rig.host.start()
        rig.host.awaitState { it is ConnState.Reconnecting && it.attempt == 3 }
        val switchedAt = testScheduler.currentTime

        rig.network.value = mobile
        rig.host.awaitState { it == ConnState.Online }

        assertEquals(listOf(1, 2, 3, 1), seen.filterIsInstance<ConnState.Reconnecting>().map { it.attempt })
        assertEquals(1_000L, testScheduler.currentTime - switchedAt, "the first failure on the new network waited the first step, not the fourth")
    }

    @Test
    fun `a drop on an unchanged network still waits out the backoff`() = runTest {
        val rig = rig(HostScript(), HostScript()).start()

        rig.socket.serverCloses(1011)
        rig.host.awaitState { it is ConnState.Reconnecting }
        val droppedAt = testScheduler.currentTime
        rig.host.awaitState { it == ConnState.Online }

        assertEquals(1_000L, testScheduler.currentTime - droppedAt)
    }

    @Test
    fun `a connection that fails on a network that has since changed is retried at once on the new one`() = runTest {
        val rig = rig(failing(), HostScript(), whileConnecting = { call, network -> if (call == 0) network.value = mobile })
        val seen = record(rig.host)

        rig.host.start()
        runCurrent()

        assertEquals(ConnState.Online, rig.host.connection.value)
        assertEquals(2, rig.connector.connects.size)
        assertEquals(0L, testScheduler.currentTime)
        assertTrue(seen.none { it is ConnState.Reconnecting })
    }

    @Test
    fun `a connection that fails on a network that has since changed starts the schedule again`() = runTest {
        val rig = rig(failing(), failing(), failing(), failing(), HostScript(), whileConnecting = { call, network -> if (call == 2) network.value = mobile })
        val seen = record(rig.host)

        rig.host.start()
        rig.host.awaitState { it == ConnState.Online }

        assertEquals(listOf(1, 2, 1), seen.filterIsInstance<ConnState.Reconnecting>().map { it.attempt })
        assertEquals(4_000L, testScheduler.currentTime, "1 s and 2 s on the old network, then 1 s on the new one")
    }

    @Test
    fun `a connection made while the network changed is closed at once and replaced, resuming`() = runTest {
        val rig = rig(HostScript(), HostScript(), whileConnecting = { call, network -> if (call == 0) network.value = mobile })

        rig.host.start()
        runCurrent()

        assertEquals(ConnState.Online, rig.host.connection.value)
        assertEquals(listOf("initialize", "listSessions"), rig.connector.sockets[0].sentMethods)
        assertEquals(listOf("reconnect", "listSessions"), rig.connector.sockets[1].sentMethods)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `a connection that fails as the network goes waits for it without counting an attempt`() = runTest {
        val rig = rig(failing(), HostScript(), whileConnecting = { call, network -> if (call == 0) network.value = NetworkState.Unavailable })
        val seen = record(rig.host)

        rig.host.start()
        advanceTimeBy(600_000)
        assertEquals(ConnState.WaitingForNetwork, rig.host.connection.value)
        assertEquals(1, rig.connector.connects.size)

        rig.network.value = wifi
        runCurrent()

        assertEquals(ConnState.Online, rig.host.connection.value)
        assertTrue(seen.none { it is ConnState.Reconnecting })
    }

    @Test
    fun `a connection that fails as the network goes starts the schedule again when it returns`() = runTest {
        val rig = rig(failing(), failing(), failing(), failing(), HostScript(), whileConnecting = { call, network -> if (call == 2) network.value = NetworkState.Unavailable })
        val seen = record(rig.host)

        rig.host.start()
        advanceTimeBy(600_000)
        assertEquals(ConnState.WaitingForNetwork, rig.host.connection.value)
        assertEquals(3, rig.connector.connects.size)

        rig.network.value = wifi
        rig.host.awaitState { it == ConnState.Online }

        assertEquals(listOf(1, 2, 1), seen.filterIsInstance<ConnState.Reconnecting>().map { it.attempt })
    }

    @Test
    fun `restart still ends the live connection and connects again at once`() = runTest {
        val rig = rig(HostScript(), HostScript()).start()
        val restartedAt = testScheduler.currentTime

        rig.host.restart()
        runCurrent()

        assertEquals(2, rig.connector.sockets.size)
        assertEquals(ConnState.Online, rig.host.connection.value)
        assertEquals(restartedAt, testScheduler.currentTime)
    }

    @Test
    fun `a restart while waiting for the network makes no attempt until there is one`() = runTest {
        val rig = rig(HostScript(), network = NetworkState.Unavailable)
        rig.host.start()
        runCurrent()

        rig.host.restart()
        advanceTimeBy(600_000)
        assertEquals(0, rig.connector.connects.size)
        assertEquals(ConnState.WaitingForNetwork, rig.host.connection.value)

        rig.network.value = wifi
        runCurrent()

        assertEquals(ConnState.Online, rig.host.connection.value)
    }

    @Test
    fun `a terminal failure is final whatever the network does afterwards`() = runTest {
        val rig = rig(HostScript(failure = DevTunnelsException.AuthExpired()), HostScript())
        rig.host.start()
        runCurrent()
        assertIs<DevTunnelsException.AuthExpired>(assertIs<ConnState.Failed>(rig.host.connection.value).cause)

        rig.network.value = NetworkState.Unavailable
        runCurrent()
        rig.network.value = mobile
        advanceTimeBy(600_000)

        assertEquals(1, rig.connector.connects.size)
        assertIs<ConnState.Failed>(rig.host.connection.value)
    }

    @Test
    fun `stop while waiting for the network ends the session, and a returning network does not revive it`() = runTest {
        val rig = rig(HostScript(), network = NetworkState.Unavailable)
        rig.host.start()
        runCurrent()

        rig.host.stop()
        rig.network.value = wifi
        advanceTimeBy(600_000)

        assertEquals(ConnState.Stopped, rig.host.connection.value)
        assertEquals(0, rig.connector.connects.size)
    }
}
