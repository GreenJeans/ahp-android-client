package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.CompletionsResult
import com.microsoft.agenthostprotocol.generated.MessageAttachment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject

class CompletionsTest {
    private val triggers = listOf("@", "/")

    private fun items(vararg json: String) = Ahp.json.decodeFromString(CompletionsResult.serializer(), """{"items":[${json.joinToString(",")}]}""").items

    private fun resource(label: String, insert: String = "@$label", range: String = "", extra: String = "") =
        """{"insertText":"$insert"$range,"attachment":{"type":"resource","label":"$label","uri":"file:///$label"$extra}}"""

    private fun range(start: Int, end: Int) = ""","rangeStart":$start,"rangeEnd":$end"""

    // ---- when to ask -------------------------------------------------------------------------------------------

    @Test
    fun `a word starts after whitespace and must begin with a trigger`() {
        assertEquals(0, Completions.wordStart("@fi", triggers))
        assertEquals(8, Completions.wordStart("look at @fi", triggers))
        assertEquals(4, Completions.wordStart("a\nb /he", triggers))
        assertEquals(0, Completions.wordStart("/", triggers))
        assertNull(Completions.wordStart("name@host", triggers))
        assertNull(Completions.wordStart("@done ", triggers))
        assertNull(Completions.wordStart("", triggers))
        assertNull(Completions.wordStart("@fi", emptyList()))
        assertNull(Completions.wordStart("#tag", triggers))
    }

    @Test
    fun `a trigger may be more than one character, and an empty one never matches`() {
        assertEquals(0, Completions.wordStart("##x", listOf("##")))
        assertNull(Completions.wordStart("#x", listOf("##")))
        assertNull(Completions.wordStart("abc", listOf("")))
    }

    // ---- reading the answer --------------------------------------------------------------------------------------

    @Test
    fun `suggestions keep the host's order and name each by its attachment`() {
        val all = Completions.suggestions(items(resource("b.kt", range = range(0, 2)), resource("a.kt", range = range(0, 2))), "@x")
        assertEquals(listOf("b.kt", "a.kt"), all.map { it.label })
    }

    @Test
    fun `an item whose range does not fit the text is dropped, never adjusted`() {
        val text = "@fi"
        val fitting = resource("ok.kt", range = range(0, 3))
        val tooLong = resource("long.kt", range = range(0, 4))
        val backwards = resource("back.kt", range = range(2, 1))
        val negative = resource("neg.kt", range = range(-1, 2))
        val halfOpen = resource("half.kt", range = ""","rangeStart":0""")
        assertEquals(listOf("ok.kt"), Completions.suggestions(items(tooLong, fitting, backwards, negative, halfOpen), text).map { it.label })
    }

    @Test
    fun `an item with no range is put in at the end, where the cursor is`() {
        val s = Completions.suggestions(items(resource("a.kt", insert = "@a.kt")), "see ").single()
        assertEquals("see @a.kt", Completions.accept("see ", s)!!.text)
    }

    @Test
    fun `no more than the cap is offered`() {
        val many = items(*Array(Completions.MAX_SUGGESTIONS + 5) { resource("f$it.kt", range = range(0, 1)) })
        assertEquals(Completions.MAX_SUGGESTIONS, Completions.suggestions(many, "@").size)
    }

    @Test
    fun `a label falls back to the inserted text when the attachment has none`() {
        val unlabelled = items("""{"insertText":"@x","rangeStart":0,"rangeEnd":1,"attachment":{"type":"future"}}""")
        assertEquals("@x", Completions.suggestions(unlabelled, "@").single().label)
    }

    // ---- accepting -----------------------------------------------------------------------------------------------

    @Test
    fun `accepting replaces the host's range and leaves the rest of the text alone`() {
        val s = Completions.suggestions(items(resource("a.kt", range = range(8, 11))), "look at @fi").single()
        val accepted = Completions.accept("look at @fi", s)!!
        assertEquals("look at @a.kt", accepted.text)
        assertTrue(accepted.attachment.fromHost)
        assertEquals("a.kt", accepted.attachment.label)
    }

    @Test
    fun `a suggestion cannot be accepted into text that has since shrunk past its range`() {
        val s = Completions.suggestions(items(resource("a.kt", range = range(0, 3))), "@fi").single()
        assertNull(Completions.accept("@", s))
    }

    @Test
    fun `the same mention is added once`() {
        val s = Completions.suggestions(items(resource("a.kt", range = range(0, 1))), "@").single()
        val first = Completions.accept("@", s)!!.attachment
        val again = Completions.accept("@", s)!!.attachment
        assertEquals(1, Completions.plus(Completions.plus(emptyList(), first), again).size)
    }

    // ---- what is sent ---------------------------------------------------------------------------------------------

    private fun accepted(extra: String = "") =
        Completions.accept("@", Completions.suggestions(items(resource("a.kt", range = range(0, 1), extra = extra)), "@").single())!!

    @Test
    fun `a mention stays while its words are in the message, files always stay`() {
        val mention = accepted().attachment
        val file = ComposerAttachment("n.txt", "text/plain", byteArrayOf(1))
        assertEquals(listOf(file, mention), Completions.live("see @a.kt", listOf(file, mention)))
        assertEquals(listOf(file), Completions.live("see it", listOf(file, mention)))
    }

    @Test
    fun `the host's range is kept while everything before the mention is unchanged and left out once it is not`() {
        val withRange = ComposerAttachment.supplied(
            "a.kt",
            HostSupplied(Ahp.json.decodeFromString(MessageAttachment.serializer(), """{"type":"future","label":"a.kt","range":{"x":1}}"""), "@a.kt", "hi @a.kt"),
        )
        fun sent(text: String) = Completions.wires(text, listOf(withRange)).single().let { Ahp.json.encodeToJsonElement(MessageAttachment.serializer(), it).jsonObject }

        assertTrue("range" in sent("hi @a.kt and more"))
        assertFalse("range" in sent("oh hi @a.kt"))
        assertEquals("a.kt", sent("oh hi @a.kt")["label"]!!.toString().trim('"'))
    }

    @Test
    fun `a mention is not a file for the file limits`() {
        val files = List(Attachments.MAX_COUNT) { ComposerAttachment("f$it.txt", "text/plain", byteArrayOf(1)) }
        assertNull(Attachments.problem(files + accepted().attachment, vision = false))
        assertNotNull(Attachments.problem(files + files.first(), vision = null))
    }
}
