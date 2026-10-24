package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ResolveSessionConfigResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

class SessionConfigFormTest {
    private fun result(properties: String, values: String = "{}", required: List<String> = emptyList()): ResolveSessionConfigResult {
        val requiredPart = if (required.isEmpty()) "" else ",\"required\":[" + required.joinToString(",") { "\"$it\"" } + "]"
        return Ahp.json.decodeFromString(
            ResolveSessionConfigResult.serializer(),
            "{\"schema\":{\"type\":\"object\",\"properties\":{$properties}$requiredPart},\"values\":$values}",
        )
    }

    private fun prop(id: String, body: String) = "\"$id\":$body"

    private val thinking = prop("thinking", """{"type":"string","title":"Thinking","description":"How hard to think","enum":["low","high"],"enumLabels":["Quick","Thorough"]}""")
    private val approve = prop("autoApprove", """{"type":"boolean","title":"Approve tools automatically"}""")

    @Test
    fun `a list of text values becomes a setting with the host's labels, title and description`() {
        val field = SessionConfigForm.from(result(thinking, """{"thinking":"low"}""")).fields.single()

        assertEquals("thinking", field.id)
        assertEquals("Thinking", field.title)
        assertEquals("How hard to think", field.description)
        assertEquals(listOf(ConfigPick.Text("low") to "Quick", ConfigPick.Text("high") to "Thorough"), field.options.map { it.pick to it.label })
        assertEquals(ConfigPick.Text("low"), field.value)
    }

    @Test
    fun `a boolean becomes an on-off setting`() {
        val field = SessionConfigForm.from(result(approve, """{"autoApprove":true}""")).fields.single()

        assertEquals(listOf(ConfigPick.Flag(true) to "On", ConfigPick.Flag(false) to "Off"), field.options.map { it.pick to it.label })
        assertEquals(ConfigPick.Flag(true), field.value)
    }

    @Test
    fun `a value without a label is shown as itself, and a missing title falls back to the id`() {
        val bare = prop("effort", """{"type":"string","title":"","enum":["a","b"]}""")

        val field = SessionConfigForm.from(result(bare)).fields.single()

        assertEquals("effort", field.title)
        assertEquals(listOf("a", "b"), field.options.map { it.label })
        assertNull(field.value, "the host holds no value yet")
    }

    @Test
    fun `settings this app cannot show are left out and counted, and the host keeps its own value`() {
        val readOnly = prop("fixed", """{"type":"string","title":"Fixed","enum":["x"],"readOnly":true}""")
        val dynamic = prop("branch", """{"type":"string","title":"Branch","enum":["main"],"enumDynamic":true}""")
        val free = prop("note", """{"type":"string","title":"Note"}""")
        val number = prop("n", """{"type":"number","title":"N"}""")
        val mixed = prop("mixed", """{"type":"string","title":"Mixed","enum":[1,2]}""")
        val nested = prop("o", """{"type":"object","title":"O"}""")

        val form = SessionConfigForm.from(result("$readOnly,$dynamic,$free,$number,$mixed,$nested,$thinking"))

        assertEquals(listOf("thinking"), form.fields.map { it.id })
        assertEquals(6, form.omitted)
    }

    @Test
    fun `a value the host holds that is not one of the options is not taken as the current value`() {
        val field = SessionConfigForm.from(result(thinking, """{"thinking":"extreme"}""")).fields.single()

        assertNull(field.value)
    }

    @Test
    fun `repeated or blank values are dropped, and the lists are capped`() {
        val repeated = prop("r", """{"type":"string","title":"R","enum":["a","a"," ","b"]}""")
        val many = (1..SessionConfigForm.MAX_OPTIONS + 10).joinToString(",") { "\"v$it\"" }
        val long = prop("long", """{"type":"string","title":"Long","enum":[$many]}""")
        val lots = (1..SessionConfigForm.MAX_FIELDS + 5).joinToString(",") { prop("s$it", """{"type":"boolean","title":"S$it"}""") }

        assertEquals(listOf("a", "b"), SessionConfigForm.from(result(repeated)).fields.single().options.map { it.label })
        assertEquals(SessionConfigForm.MAX_OPTIONS, SessionConfigForm.from(result(long)).fields.single().options.size)
        val capped = SessionConfigForm.from(result(lots))
        assertEquals(SessionConfigForm.MAX_FIELDS, capped.fields.size)
        assertEquals(5, capped.omitted)
    }

    @Test
    fun `the host's words are bounded`() {
        val wordy = prop("w", """{"type":"string","title":"${"t".repeat(300)}","description":"${"d".repeat(900)}","enum":["${"v".repeat(300)}"]}""")

        val field = SessionConfigForm.from(result(wordy)).fields.single()

        assertEquals(80, field.title.length)
        assertEquals(200, field.description!!.length)
        assertEquals(80, field.options.single().label.length)
    }

    // ---- what may be sent ----------------------------------------------------------------------------------------

    private val form get() = SessionConfigForm.from(result("$thinking,$approve", """{"thinking":"high","autoApprove":false}""", required = listOf("autoApprove")))

    @Test
    fun `a pick is accepted only when it is an offered option and the host's answer shows it took`() {
        assertTrue(form.accepts(emptyMap()))
        assertTrue(form.accepts(mapOf("thinking" to ConfigPick.Text("high"))))
        assertTrue(form.accepts(mapOf("autoApprove" to ConfigPick.Flag(false))))

        assertFalse(form.accepts(mapOf("thinking" to ConfigPick.Text("low"))), "the host holds high, so low did not take")
        assertFalse(form.accepts(mapOf("thinking" to ConfigPick.Text("extreme"))))
        assertFalse(form.accepts(mapOf("thinking" to ConfigPick.Flag(true))))
        assertFalse(form.accepts(mapOf("nope" to ConfigPick.Text("high"))))
    }

    @Test
    fun `a required setting counts as missing only without a value and without a pick`() {
        val noValue = SessionConfigForm.from(result("$thinking,$approve", """{"thinking":"high"}""", required = listOf("autoApprove")))

        assertEquals(listOf("autoApprove"), noValue.missingRequired(emptyMap()).map { it.id })
        assertEquals(emptyList(), noValue.missingRequired(mapOf("autoApprove" to ConfigPick.Flag(true))))
        assertEquals(emptyList(), form.missingRequired(emptyMap()), "the host already holds a value")
    }

    @Test
    fun `picks are described as setting and value in the order of the settings`() {
        val picks = mapOf("autoApprove" to ConfigPick.Flag(true), "thinking" to ConfigPick.Text("high"))

        assertEquals(listOf("Thinking: Thorough", "Approve tools automatically: On"), form.describe(picks))
    }

    @Test
    fun `what is sent is the host's own values for settings it lists, bounded`() {
        val values = """{"thinking":"high","autoApprove":false,"stray":"x","big":"${"z".repeat(5000)}"}"""
        val big = prop("big", """{"type":"string","title":"Big"}""")

        val wire = SessionConfigForm.from(result("$thinking,$approve,$big", values)).wireConfig()

        assertEquals(mapOf("thinking" to JsonPrimitive("high"), "autoApprove" to JsonPrimitive(false)), wire, "a stray key and an oversized value are not sent")
    }

    @Test
    fun `no more values are sent than the cap`() {
        val props = (1..SessionConfigForm.MAX_VALUES + 10).joinToString(",") { prop("k$it", """{"type":"string","title":"K"}""") }
        val values = (1..SessionConfigForm.MAX_VALUES + 10).joinToString(",") { "\"k$it\":\"v\"" }

        assertEquals(SessionConfigForm.MAX_VALUES, SessionConfigForm.from(result(props, "{$values}")).wireConfig().size)
    }

    @Test
    fun `a pick is typed on the wire`() {
        assertEquals(JsonPrimitive("high"), ConfigPick.Text("high").toJson())
        assertEquals(JsonPrimitive(true), ConfigPick.Flag(true).toJson())
    }
}
