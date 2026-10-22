package dev.ahpclient.tunnel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TunnelTagsTest {
    @Test
    fun `reads the protocol and display name from a live label order`() {
        val tags = TunnelTags(listOf("dev-box", "protocolv6", "vscode-server-launcher", "_flag3"))

        assertEquals(6, tags.protocolVersion)
        assertEquals("dev-box", tags.name)
    }

    @Test
    fun `name is the first label that is not the launcher, a flag or a protocol tag`() {
        val tags = TunnelTags(listOf("_flag", "vscode-server-launcher", "protocolv5", "first", "second"))

        assertEquals("first", tags.name)
    }

    @Test
    fun `defaults to protocol 2 with no protocol tag`() {
        val tags = TunnelTags(listOf("vscode-server-launcher"))

        assertEquals(2, tags.protocolVersion)
        assertNull(tags.name)
    }

    @Test
    fun `ignores a non numeric protocol tag and does not treat it as a name`() {
        val tags = TunnelTags(listOf("protocolvX", "vscode-server-launcher"))

        assertEquals(2, tags.protocolVersion)
        assertNull(tags.name)
    }

    @Test
    fun `the last protocol tag wins`() {
        assertEquals(6, TunnelTags(listOf("protocolv5", "protocolv6")).protocolVersion)
    }
}
