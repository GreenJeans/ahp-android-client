package dev.ahpclient.direct

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HostAddressTest {
    private fun ok(input: String): HostAddressCheck.Ok = assertIs<HostAddressCheck.Ok>(HostAddress.check(input), input)

    private fun refused(input: String): String = assertIs<HostAddressCheck.Refused>(HostAddress.check(input), input).reason

    @Test
    fun `a bare address gets ahpd's port and ws`() {
        val checked = ok("192.168.1.20")
        assertEquals("ws://192.168.1.20:9187", checked.url)
        assertTrue(checked.cleartext)
    }

    @Test
    fun `a bare address keeps the port it was given`() {
        assertEquals("ws://10.0.2.2:9300", ok("10.0.2.2:9300").url)
        assertEquals("ws://localhost:9187", ok("localhost:9187").url)
    }

    @Test
    fun `a scheme that was written is kept and gets that scheme's own port`() {
        assertEquals("wss://agents.example.com", ok("wss://agents.example.com").url)
        assertEquals("wss://agents.example.com", ok("https://agents.example.com/").url)
        assertEquals("ws://127.0.0.1", ok("http://127.0.0.1").url)
        assertFalse(ok("wss://agents.example.com").cleartext)
    }

    @Test
    fun `ws is accepted for loopback and private ranges only`() {
        listOf(
            "127.0.0.1", "127.8.9.10", "10.0.0.5", "10.255.255.255", "172.16.0.1", "172.31.255.1", "192.168.0.1", "100.64.0.1", "100.127.255.255",
            "localhost", "[::1]", "[fd12:3456:789a::1]", "[fc00::1]",
        ).forEach { ok("ws://$it:9187") }
        listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.0.1", "100.63.0.1", "100.128.0.1", "11.0.0.1", "169.254.1.1", "[2001:db8::1]", "[fe80::1]", "laptop", "laptop.local", "agents.example.com")
            .forEach { assertTrue(refused("ws://$it:9187").contains("wss://"), it) }
    }

    @Test
    fun `an address that is not a valid ip is not taken for a private one`() {
        refused("ws://256.1.1.1:9187")
        refused("ws://10.0.0:9187")
    }

    @Test
    fun `wss is accepted to any host`() {
        ok("wss://8.8.8.8")
        ok("wss://agents.example.com:8443/ahp")
        assertEquals("wss://agents.example.com:8443/ahp", ok("wss://agents.example.com:8443/ahp/").url)
    }

    @Test
    fun `an address with a token, credentials or a query is refused so the token is never kept in it`() {
        assertTrue(refused("ws://192.168.1.2:9187/?tkn=secret").contains("token"))
        assertTrue(refused("ws://192.168.1.2:9187#x").contains("token"))
        assertTrue(refused("ws://user:pass@192.168.1.2:9187").contains("credentials"))
    }

    @Test
    fun `other schemes, empty, long and odd addresses are refused`() {
        listOf("", "   ", "ftp://192.168.1.2", "file:///etc/passwd", "javascript:alert(1)", "ws://", "ws://192.168.1.2:0", "ws://192.168.1.2:70000", "ws://192.168.1.2:9187/a b", "ws://host‮.com", "ws://[::1", "ws://192.168.1.2/<x>")
            .forEach { refused(it) }
        refused("wss://" + "a".repeat(HostAddress.MAX_LENGTH))
    }

    @Test
    fun `a name that merely begins like a private address is not one`() {
        refused("ws://10.0.0.1.evil.com:9187")
        refused("ws://localhost.evil.com:9187")
        refused("ws://192.168.1.1@evil.com:9187")
    }

    @Test
    fun `isPrivate agrees`() {
        assertTrue(HostAddress.isPrivate("[::1]"))
        assertTrue(HostAddress.isPrivate("10.0.2.2"))
        assertFalse(HostAddress.isPrivate("example.com"))
        assertFalse(HostAddress.isPrivate("1.2.3"))
    }
}
