package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.AnnotationsState
import com.microsoft.agenthostprotocol.generated.CanvasState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ChannelViewsTest {
    private fun canvas(fields: String) = CanvasDetail.from(
        Ahp.json.decodeFromString(CanvasState.serializer(), """{"instanceId":"i1","extensionId":"ext","canvasId":"web"$fields}"""),
    )

    private fun annotations(json: String) = AnnotationsView.from(Ahp.json.decodeFromString(AnnotationsState.serializer(), """{"annotations":[$json]}"""))

    private fun annotation(id: String = "a1", range: String = "", entries: String = """{"id":"e1","text":"one"}""") =
        """{"id":"$id","origin":{"session":"ahp-session:/s1"},"resource":"file:///work/a.kt"$range,"resolved":false,"entries":[$entries]}"""

    @Test
    fun `a canvas falls back from its title to its extension and then to a plain name`() {
        assertEquals("Build", canvas(""","title":"Build","extensionName":"Ext"""").title)
        assertEquals("Ext", canvas(""","title":"  ","extensionName":"Ext"""").title)
        assertEquals("Canvas", canvas("").title)
    }

    @Test
    fun `canvas text is one bounded line with reordering characters marked`() {
        val detail = canvas(""","title":"evil‮gnp\nname","status":"${"s".repeat(400)}"""")

        assertEquals("evil�gnp name", detail.title)
        assertFalse(detail.status!!.length > 300)
    }

    @Test
    fun `only a plain https canvas address can be opened`() {
        assertEquals("https://example.com/p", canvas(""","url":"https://example.com/p"""").url)
        assertNull(canvas(""","url":"http://example.com/p"""").url)
        assertNull(canvas(""","url":"javascript:alert(1)"""").url)
        assertNull(canvas(""","url":"file:///etc/passwd"""").url)
        assertNull(canvas("").url)
    }

    @Test
    fun `an annotation names its file and one based line, and keeps a comment's lines while marking reordering characters`() {
        val row = annotations(
            annotation(
                range = ""","range":{"start":{"line":9,"character":0},"end":{"line":9,"character":4}}""",
                entries = """{"id":"e1","text":"first\nse‮cond"},{"id":"e2","text":{"markdown":"**bold**"}}""",
            ),
        ).rows.single()

        assertEquals("/work/a.kt:10", row.target)
        assertEquals("first\nse�cond", row.entries[0].text)
        assertEquals(2, row.entries.size)
    }

    @Test
    fun `annotations and their comments are bounded, and what was left out is counted`() {
        val many = (1..AnnotationsView.MAX_ANNOTATIONS + 3).joinToString(",") { annotation("a$it") }
        val view = annotations(many)
        assertEquals(AnnotationsView.MAX_ANNOTATIONS, view.rows.size)
        assertEquals(3, view.omitted)

        val entries = (1..AnnotationsView.MAX_ENTRIES + 2).joinToString(",") { """{"id":"e$it","text":"x"}""" }
        val row = annotations(annotation(entries = entries)).rows.single()
        assertEquals(AnnotationsView.MAX_ENTRIES, row.entries.size)
        assertEquals(2, row.omittedEntries)
    }
}
