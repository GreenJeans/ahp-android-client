package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.TerminalState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A terminal as the screen shows it: plain, bounded text, and its state in words. */
class TerminalViewTest {
    private fun json(text: String) = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\u001B", "\\u001b").replace("\t", "\\t").replace("\b", "\\b").replace("\u0007", "\\u0007")

    private fun state(
        output: String = "",
        lifecycle: String = """{"status":"running"}""",
        title: String = "build",
        cwd: String? = "file:///c%3A/work/repo",
        content: String? = null,
    ): TerminalState = Ahp.json.decodeFromString(
        TerminalState.serializer(),
        """{"title":"${json(title)}",${cwd?.let { """"cwd":"$it",""" }.orEmpty()}"content":[${content ?: """{"type":"unclassified","value":"${json(output)}"}"""}],
            "lifecycle":$lifecycle,"claim":{"kind":"client","clientId":"x"}}""",
    )

    private fun lines(output: String) = TerminalView.from(state(output)).lines

    @Test
    fun `plain output becomes lines, and a trailing newline adds no empty line`() {
        assertEquals(listOf("one", "two"), lines("one\ntwo\n"))
        assertEquals(listOf("one", "two"), lines("one\r\ntwo\r\n"))
        assertEquals(listOf("one", "", "three"), lines("one\n\nthree"))
        assertEquals(emptyList(), lines(""))
    }

    @Test
    fun `colours, cursor moves and window titles are dropped`() {
        assertEquals(listOf("red and plain"), lines("\u001B[31mred\u001B[0m and plain\n"))
        assertEquals(listOf("shown"), lines("\u001B[2K\u001B[1Ashown\n"))
        assertEquals(listOf("text"), lines("\u001B]0;a window title\u0007text\n"))
        assertEquals(listOf("text"), lines("\u001B]8;;https://example.com\u001B\\text\u001B]8;;\u001B\\\n"))
        assertEquals(listOf("kept"), lines("\u001B(B\u001B[?25lkept\n"))
    }

    @Test
    fun `a carriage return overwrites the line as a terminal would`() {
        assertEquals(listOf("100% done"), lines("  5% ....\r 50% ....\r100% done\n"))
        assertEquals(listOf("xyz45"), lines("12345\rxyz\n"))
    }

    @Test
    fun `a backspace steps back and the next character overwrites`() {
        assertEquals(listOf("abX"), lines("abc\bX\n"))
        assertEquals(listOf("a"), lines("\b\ba\n"))
    }

    @Test
    fun `a tab moves to the next stop of eight`() {
        assertEquals(listOf("ab      c"), lines("ab\tc\n"))
    }

    @Test
    fun `other control characters are dropped, and text is kept intact`() {
        assertEquals(listOf("ab"), lines("a\u0000\u0007\u000Bb\n"))
        assertEquals(listOf("é – 日本語 😀 ok"), lines("é – 日本語 😀 ok\n"))
    }

    @Test
    fun `a character that reorders text on screen is swapped for a visible mark`() {
        assertEquals(listOf("a\uFFFDb\uFFFDc"), lines("a\u202Eb\u2067c\n"))
    }

    @Test
    fun `an escape sequence that never ends is dropped, and nothing throws`() {
        assertEquals(listOf("text"), lines("text\u001B"))
        assertEquals(listOf("text"), lines("text\u001B["))
        assertEquals(listOf("text"), lines("text\u001B[31;1"))
        assertEquals(listOf("text"), lines("text\u001B]0;title with no end"))
        assertEquals(listOf("text"), lines("text\u001B("))
    }

    @Test
    fun `a line is bounded and the cut is not larger than the bound`() {
        val long = lines("x".repeat(5_000) + "\n").single()

        assertTrue(long.length <= LineDiff.MAX_LINE_CHARS + 1, "${long.length}")
    }

    @Test
    fun `only the last lines are kept and the count left out is said`() {
        val text = (1..(TerminalView.MAX_LINES + 50)).joinToString("") { "line $it\n" }

        val view = TerminalView.from(state(text))

        assertEquals(TerminalView.MAX_LINES, view.lines.size)
        assertEquals(50, view.omittedLines)
        assertEquals("line 51", view.lines.first())
        assertEquals("line ${TerminalView.MAX_LINES + 50}", view.lines.last())
    }

    @Test
    fun `an enormous output is read from its end, and the fragment at the cut is not shown`() {
        val huge = "A".repeat(TerminalView.MAX_RAW_CHARS) + "\nlast line\n"

        val view = TerminalView.from(state(huge))

        assertEquals("last line", view.lines.last())
        assertFalse(view.lines.any { it.startsWith("AAAAAAAAAA") && it.length > LineDiff.MAX_LINE_CHARS + 1 })
    }

    @Test
    fun `a cut in the middle of a long run of output drops the half line it leaves`() {
        val body = "0123456789\n".repeat(TerminalView.MAX_RAW_CHARS / 11 + 100)

        val view = TerminalView.from(state(body))

        assertTrue(view.lines.all { it == "0123456789" }, "no fragment at the start: ${view.lines.first()}")
    }

    @Test
    fun `command parts show the command line, its output and a failing exit code`() {
        val content = """{"type":"command","commandId":"c1","commandLine":"npm test","output":"ok\r\n","timestamp":1,"isComplete":true,"exitCode":1},
            {"type":"unclassified","value":"$ "}"""

        val view = TerminalView.from(state(content = content))

        assertEquals(listOf("$ npm test", "ok", "[exit code 1]", "$ "), view.lines)
    }

    @Test
    fun `a command that succeeded, or is still running, adds no exit line`() {
        val done = """{"type":"command","commandId":"c1","commandLine":"ls","output":"a\r\n","timestamp":1,"isComplete":true,"exitCode":0}"""
        val running = """{"type":"command","commandId":"c2","commandLine":"sleep 9","output":"","timestamp":1,"isComplete":false}"""

        assertEquals(listOf("$ ls", "a"), TerminalView.from(state(content = done)).lines)
        assertEquals(listOf("$ sleep 9"), TerminalView.from(state(content = running)).lines)
    }

    @Test
    fun `state is said in words`() {
        assertEquals("Running", TerminalView.from(state()).status)
        assertTrue(TerminalView.from(state()).running)

        val ok = TerminalView.from(state(lifecycle = """{"status":"exited","exitCode":0}"""))
        assertEquals("Exited with code 0", ok.status)
        assertFalse(ok.running)
        assertEquals("Exited with code 2", TerminalView.from(state(lifecycle = """{"status":"exited","exitCode":2}""")).status)
        assertEquals("Exited", TerminalView.from(state(lifecycle = """{"status":"exited"}""")).status)
    }

    @Test
    fun `the title and folder are plain text, bounded, and a blank title gets a name`() {
        val view = TerminalView.from(state(title = "a\u202Eb" + "t".repeat(500), cwd = "file:///c%3A/Users/me/repo"))

        assertTrue(view.title.startsWith("a\uFFFDb"))
        assertTrue(view.title.length <= 125, "${view.title.length}")
        assertEquals("c:/Users/me/repo", view.cwd)
        assertEquals("Terminal", TerminalView.from(state(title = "  ")).title)
        assertNull(TerminalView.from(state(cwd = null)).cwd)
    }

    @Test
    fun `a terminal address is told from anything else`() {
        assertTrue(TerminalLinks.isTerminalUri("ahp-terminal:/t1"))
        assertTrue(TerminalLinks.isTerminalUri(TERMINAL_REAL))
        listOf("", "ahp-terminal:/", "agenthost-terminal://", "ahp-chat:/x", "https://example.com", "ahp-terminal:/a\u0000b", "ahp-terminal:/" + "x".repeat(2_000))
            .forEach { assertFalse(TerminalLinks.isTerminalUri(it), it.take(40)) }
    }
}
