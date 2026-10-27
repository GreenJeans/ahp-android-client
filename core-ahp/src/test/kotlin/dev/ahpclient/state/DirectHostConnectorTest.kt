package dev.ahpclient.state

import dev.ahpclient.ahp.CapabilityDeniedException
import dev.ahpclient.ahp.CapabilityPolicy
import dev.ahpclient.ahp.FakeSocket
import dev.ahpclient.ahp.result
import dev.ahpclient.auth.Secret
import dev.ahpclient.direct.DirectHost
import dev.ahpclient.tunnel.SocketOpener
import dev.ahpclient.tunnel.TextSocket
import dev.ahpclient.tunnel.UpgradeRejectedException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The path from "a direct host was chosen" to "an AHP connection under that host's own tier", with no GitHub and no Dev Tunnels. */
class DirectHostConnectorTest {
    private val host = DirectHost("direct:11111111-1", "Laptop", "ws://192.168.1.20:9187")

    private class RecordingOpener(private val socket: FakeSocket? = null, private val failure: Throwable? = null) : SocketOpener {
        val opened = mutableListOf<Pair<String, Map<String, String>>>()

        override suspend fun open(url: String, headers: Map<String, String>): TextSocket {
            opened += url to headers
            failure?.let { throw it }
            return socket!!
        }
    }

    private fun listSocket() = FakeSocket { method, id, _ -> if (method == "listSessions") result(id, """{"items":[]}""") else null }

    @Test
    fun `the token goes in a bearer header and never in the address`() = runTest {
        val opener = RecordingOpener(listSocket())
        val connection = DirectHostConnector(opener, host, { Secret("tok-123") }).connect("client-1", 0, backgroundScope)

        connection.listSessions()

        val (url, headers) = opener.opened.single()
        assertEquals("ws://192.168.1.20:9187", url)
        assertEquals(mapOf("Authorization" to "Bearer tok-123"), headers)
        assertFalse(url.contains("tok-123"))
    }

    @Test
    fun `a host that takes no token is opened with no credentials`() = runTest {
        for (token in listOf<Secret?>(null, Secret(""), Secret("  "))) {
            val opener = RecordingOpener(listSocket())
            DirectHostConnector(opener, host, { token }).connect("client-1", 0, backgroundScope)
            assertEquals(emptyMap(), opener.opened.single().second)
        }
    }

    @Test
    fun `the connection is read only unless the caller's policy says more, and the policy is asked for on every attempt`() = runTest {
        val tiers = ArrayDeque(listOf(CapabilityPolicy.READ, CapabilityPolicy.INTERACT))
        val connector = DirectHostConnector(RecordingOpener(listSocket()), host, { null }, policy = { tiers.removeFirst() })

        val first = connector.connect("c", 0, backgroundScope)
        val second = connector.connect("c", 0, backgroundScope)

        assertFalse(first.canDispatch)
        assertTrue(second.canDispatch)
        assertFalse(second.canManage)
        assertFailsWith<CapabilityDeniedException> { first.createChat("ahp-session:/x", "ahp-chat:/y") }
    }

    @Test
    fun `an address the rules refuse is never opened, even if it was saved`() = runTest {
        val opener = RecordingOpener(listSocket())
        val bad = host.copy(url = "ws://example.com:9187")

        val failure = assertFailsWith<DirectHostException> { DirectHostConnector(opener, bad, { null }).connect("c", 0, backgroundScope) }

        assertTrue(failure.message!!.contains("wss://"))
        assertTrue(opener.opened.isEmpty())
    }

    @Test
    fun `a refused token is said in plain words and ends the retrying`() = runTest {
        val refusedWith = RecordingOpener(failure = UpgradeRejectedException(401))
        assertEquals("The host refused the connection token.", assertFailsWith<DirectHostException> { DirectHostConnector(refusedWith, host, { Secret("t") }).connect("c", 0, backgroundScope) }.message)

        val none = RecordingOpener(failure = UpgradeRejectedException(403))
        assertTrue(assertFailsWith<DirectHostException> { DirectHostConnector(none, host, { null }).connect("c", 0, backgroundScope) }.message!!.contains("none is saved"))

        val wrongPath = RecordingOpener(failure = UpgradeRejectedException(404))
        assertTrue(assertFailsWith<DirectHostException> { DirectHostConnector(wrongPath, host, { null }).connect("c", 0, backgroundScope) }.message!!.contains("address"))
    }

    @Test
    fun `other failures are left as they are so the session retries them`() = runTest {
        val down = IOException("Connection refused")
        val thrown = assertFailsWith<IOException> { DirectHostConnector(RecordingOpener(failure = down), host, { null }).connect("c", 0, backgroundScope) }
        assertEquals(down, thrown)

        val server = UpgradeRejectedException(503)
        assertIs<UpgradeRejectedException>(assertFailsWith<IOException> { DirectHostConnector(RecordingOpener(failure = server), host, { null }).connect("c", 0, backgroundScope) })
    }
}
