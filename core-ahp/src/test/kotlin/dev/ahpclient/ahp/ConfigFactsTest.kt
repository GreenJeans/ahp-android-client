package dev.ahpclient.ahp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

class ConfigFactsTest {
    private fun parse(json: String) = ConfigFacts.fromResult(Json.parseToJsonElement(json))

    private val sessionProps = """{"autoApprove":{"type":"string","title":"A","chatMutable":true},"mode":{"type":"string","title":"M","chatMutable":false},
        "sandboxEnabled":{"type":"string","title":"S","chatMutable":true},"effectiveApprovalMode":{"type":"string","title":"E","chatMutable":true,"readOnly":true}}"""

    private fun session(resource: String = "ahp-session:/s", config: String? = """{"schema":{"type":"object","properties":$sessionProps},"values":{}}""") =
        """{"resource":"$resource","fromSeq":5,"state":{"provider":"p","title":"t","status":1,"lifecycle":"ready","activeClients":[],"chats":[]${config?.let { ""","config":$it""" }.orEmpty()}}}"""

    private fun chat(resource: String = "ahp-chat:/c", config: String? = """{"values":{"mode":"plan","autoApprove":"autoApprove"}}""") =
        """{"resource":"$resource","fromSeq":7,"state":{"resource":"$resource","title":"c","status":1,"modifiedAt":"2026-10-22T10:00:00.000Z","turns":[]${config?.let { ""","config":$it""" }.orEmpty()}}}"""

    @Test
    fun `a chat snapshot gives the chat's own values`() {
        val facts = parse("""{"snapshot":${chat()}}""")

        assertEquals(mapOf("mode" to JsonPrimitive("plan"), "autoApprove" to JsonPrimitive("autoApprove")), facts.getValue("ahp-chat:/c").chatValues)
        assertNull(facts.getValue("ahp-chat:/c").chatMutableKeys)
    }

    @Test
    fun `a chat with no config of its own has none, which is not the same as not knowing`() {
        val facts = parse("""{"snapshot":${chat(config = null)}}""")

        assertEquals(emptyMap(), facts.getValue("ahp-chat:/c").chatValues)
    }

    @Test
    fun `only the settings this app reads are kept from a chat`() {
        val facts = parse("""{"snapshot":${chat(config = """{"values":{"mode":"plan","permissions":{"allow":["rm"]},"sandboxEnabled":"off","effectiveApprovalMode":"assisted"}}""")}}""")

        assertEquals(setOf("mode", "effectiveApprovalMode"), facts.getValue("ahp-chat:/c").chatValues!!.keys)
    }

    @Test
    fun `a session snapshot gives the settings its schema lets a chat set, among those this app reads`() {
        val facts = parse("""{"snapshot":${session()}}""")

        // sandboxEnabled is chat-mutable but not read by this app; mode says false.
        assertEquals(setOf("autoApprove", "effectiveApprovalMode"), facts.getValue("ahp-session:/s").chatMutableKeys)
        assertNull(facts.getValue("ahp-session:/s").chatValues)
    }

    @Test
    fun `a session whose schema has no chat flags, or no config, lets a chat set nothing`() {
        val noFlags = session(config = """{"schema":{"type":"object","properties":{"mode":{"type":"string","title":"M"}}},"values":{}}""")

        assertEquals(emptySet(), parse("""{"snapshot":$noFlags}""").getValue("ahp-session:/s").chatMutableKeys)
        assertEquals(emptySet(), parse("""{"snapshot":${session(config = null)}}""").getValue("ahp-session:/s").chatMutableKeys)
    }

    @Test
    fun `a flag that is not the boolean true counts for nothing`() {
        val odd = session(config = """{"schema":{"type":"object","properties":{"mode":{"type":"string","title":"M","chatMutable":"true"},"autoApprove":{"type":"string","title":"A","chatMutable":1}}},"values":{}}""")

        assertEquals(emptySet(), parse("""{"snapshot":$odd}""").getValue("ahp-session:/s").chatMutableKeys)
    }

    @Test
    fun `initialize and a full reconnect give several snapshots at once`() {
        val facts = parse("""{"snapshots":[${session("ahp-session:/a")},${chat("ahp-chat:/b")},${session("ahp-session:/c", config = null)}]}""")

        assertEquals(setOf("ahp-session:/a", "ahp-chat:/b", "ahp-session:/c"), facts.keys)
    }

    @Test
    fun `a root or a terminal snapshot holds nothing to read`() {
        val root = """{"resource":"ahp-root://","fromSeq":1,"state":{"agents":[],"config":{"values":{"mode":"plan"}}}}"""
        val terminal = """{"resource":"ahp-terminal:/t","fromSeq":1,"state":{"title":"x","content":[],"config":{"values":{"mode":"plan"}}}}"""

        assertEquals(emptyMap(), parse("""{"snapshots":[$root,$terminal]}"""))
    }

    @Test
    fun `a reply with no snapshot, or one of another shape, gives nothing and never throws`() {
        listOf(
            "null", "[]", "\"x\"", "{}", """{"type":"replay","actions":[],"missing":[]}""",
            """{"snapshot":null}""", """{"snapshot":"x"}""", """{"snapshots":"x"}""", """{"snapshots":[1,null,"x",[]]}""",
            """{"snapshot":{"resource":5,"state":{}}}""", """{"snapshot":{"resource":"r"}}""", """{"snapshot":{"resource":"r","state":"x"}}""",
            """{"snapshot":{"resource":"ahp-chat:/c","state":{"turns":[],"config":"x"}}}""",
            """{"snapshot":{"resource":"ahp-chat:/c","state":{"turns":[],"config":{"values":"x"}}}}""",
        ).forEach { json ->
            val facts = parse(json)
            facts.values.forEach { assertEquals(emptyMap(), it.chatValues ?: emptyMap(), json) }
        }
        assertEquals(emptyMap(), ConfigFacts.fromResult(null))
    }

    @Test
    fun `a resource name that is empty or too long is ignored`() {
        val tooLong = "ahp-chat:/" + "x".repeat(600)

        assertEquals(emptyMap(), parse("""{"snapshots":[${chat("")},${chat(tooLong)}]}"""))
    }

    @Test
    fun `a reply with endless snapshots is read only as far as a bound`() {
        val many = (1..200).joinToString(",") { chat("ahp-chat:/$it") }

        assertEquals(64, parse("""{"snapshots":[$many]}""").size)
    }

    @Test
    fun `a chat value of any type is kept as it came, for the reader to judge`() {
        val facts = parse("""{"snapshot":${chat(config = """{"values":{"mode":7,"autoApprove":null}}""")}}""")

        assertEquals(setOf("mode", "autoApprove"), facts.getValue("ahp-chat:/c").chatValues!!.keys)
    }
}
