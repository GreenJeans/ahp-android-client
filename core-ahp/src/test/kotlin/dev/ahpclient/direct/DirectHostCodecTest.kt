package dev.ahpclient.direct

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectHostCodecTest {
    private val a = DirectHost("direct:aaaaaaaa-1", "Laptop", "ws://192.168.1.20:9187")
    private val b = DirectHost("direct:bbbbbbbb-2", "Server", "wss://agents.example.com")

    @Test
    fun `hosts survive a round trip and hold no token`() {
        val text = DirectHostCodec.encode(listOf(a, b))
        assertEquals(listOf(a, b), DirectHostCodec.decode(text))
        assertFalse(text.contains("token", ignoreCase = true))
    }

    @Test
    fun `nothing saved, damaged or of the wrong shape reads as no hosts`() {
        listOf(null, "", "  ", "not json", "[]", """{"hosts":5}""", """{"v":1}""").forEach { assertEquals(emptyList(), DirectHostCodec.decode(it), it.toString()) }
    }

    @Test
    fun `a bad entry is dropped and the good ones kept`() {
        val text = """{"v":1,"hosts":[
            {"id":"tunnel-1","name":"x","url":"wss://a.example.com"},
            {"id":"direct:cccccccc-3","name":"","url":"wss://a.example.com"},
            {"id":"direct:dddddddd-4","name":"Public ws","url":"ws://8.8.8.8:9187"},
            {"id":"direct:eeeeeeee-5","name":"Token in url","url":"ws://10.0.0.1:9187/?tkn=x"},
            {"id":"direct:aaaaaaaa-1","name":"Laptop","url":"ws://192.168.1.20:9187"},
            {"id":"direct:aaaaaaaa-1","name":"Repeat","url":"ws://192.168.1.21:9187"},
            5, {"id":7}
        ]}"""
        assertEquals(listOf(a), DirectHostCodec.decode(text))
    }

    @Test
    fun `an address that rules now refuse is not loaded even if it was saved`() {
        val text = """{"v":1,"hosts":[{"id":"direct:ffffffff-6","name":"Old","url":"ws://example.com:9187"}]}"""
        assertEquals(emptyList(), DirectHostCodec.decode(text))
    }

    @Test
    fun `names are one clean line of limited length`() {
        assertEquals("ab", DirectHostCodec.cleanName("  a\u0000\nb  "))
        assertEquals(DirectHostCodec.MAX_NAME, DirectHostCodec.cleanName("x".repeat(500)).length)
    }

    @Test
    fun `the list is cut to the limit`() {
        val many = (1..30).map { DirectHost("direct:%08d".format(it), "h$it", "wss://h$it.example.com") }
        assertEquals(DirectHostCodec.MAX_HOSTS, DirectHostCodec.decode(DirectHostCodec.encode(many)).size)
        assertTrue(DirectHost.isDirectId("direct:x"))
        assertFalse(DirectHost.isDirectId("abc"))
    }
}
