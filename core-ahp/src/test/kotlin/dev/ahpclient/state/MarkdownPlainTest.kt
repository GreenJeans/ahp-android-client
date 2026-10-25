package dev.ahpclient.state

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownPlainTest {
    @Test
    fun `a link becomes its label, whatever it points at`() {
        assertEquals("Creating mode-config.cjs", MarkdownPlain.of("Creating [mode-config.cjs](file:///c%3A/Users/me/mode-config.cjs)"))
        assertEquals("See the issue now", MarkdownPlain.of("See [the issue](https://example.com/a?b=1 \"Title\") now"))
        assertEquals("Reading Foo", MarkdownPlain.of("Reading [Foo](https://en.wikipedia.org/wiki/Foo_(bar))"))
    }

    @Test
    fun `an image becomes its description`() {
        assertEquals("a chart", MarkdownPlain.of("![a chart](https://example.com/c.png)"))
    }

    @Test
    fun `code and bold markers are dropped and their text kept`() {
        assertEquals("Install tool-admin deps in worktree completed", MarkdownPlain.of("`Install tool-admin deps in worktree` completed"))
        assertEquals("Ran build", MarkdownPlain.of("Ran **build**"))
        assertEquals("a `b", MarkdownPlain.of("a `b"), "an unclosed backtick is left alone")
    }

    @Test
    fun `text that is not markup is returned as it was`() {
        for (text in listOf("", "Running tests", "a [b] c", "snake__case__name", "2 * 3 * 4", "[](x)".take(0))) {
            assertEquals(text, MarkdownPlain.of(text))
        }
    }

    @Test
    fun `a link with no address is left alone`() {
        assertEquals("[label]", MarkdownPlain.of("[label]"))
    }
}
