package dev.ahpclient.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class LinkTargetTest {
    @Test
    fun `an https page with a real host is a web target, shown by its host`() {
        val target = assertIs<LinkTarget.Web>(LinkTarget.of("https://github.com/microsoft/vscode/issues/339997"))

        assertEquals("github.com", target.shown)
        assertEquals("https://github.com/microsoft/vscode/issues/339997", target.url)
    }

    @Test
    fun `a mail address and a phone number are targets, and a mail's subject and body are dropped`() {
        assertEquals(LinkTarget.Mail("a.b@example.com"), LinkTarget.of("mailto:a.b@example.com?subject=Hi&body=pay%20me"))
        assertEquals(LinkTarget.Phone("+1 (555) 010-0100"), LinkTarget.of("tel:+1 (555) 010-0100"))
    }

    @Test
    fun `nothing that only makes sense on the host is a target`() {
        for (raw in listOf(
            "/home/me/repo/src/Main.kt", "src/Main.kt", "./README.md", "../x", "file:///C:/Users/me/x.txt", "vscode://file/x", "vscode-insiders://agents/session",
            "command:workbench.action.openSettings", "ahp-chat:/default/x", "sint://lorem.amet/elit", "javascript:alert(1)", "intent://x#Intent;end", "content://x", "data:text/html,hi",
            "#heading", "", "   ", "C:\\Users\\me\\x.txt", "http://example.com", "https:///nohost", "https://user:pw@example.com/",
        )) {
            assertNull(LinkTarget.of(raw), "'$raw' must not be tappable")
        }
    }

    @Test
    fun `a malformed mail address or number is not a target`() {
        assertNull(LinkTarget.of("mailto:not-an-address"))
        assertNull(LinkTarget.of("mailto:a@b"))
        assertNull(LinkTarget.of("mailto:"))
        assertNull(LinkTarget.of("tel:abc"))
        assertNull(LinkTarget.of("tel:+"))
        assertNull(LinkTarget.of("tel:1".repeat(40)))
    }

    @Test
    fun `an enormous link is not a target`() {
        assertNull(LinkTarget.of("https://example.com/" + "a".repeat(3_000)))
    }

    @Test
    fun `the scheme is matched whatever its case`() {
        assertIs<LinkTarget.Web>(LinkTarget.of("HTTPS://Example.com/x"))
        assertIs<LinkTarget.Mail>(LinkTarget.of("MAILTO:a@example.com"))
    }
}

class MarkdownLinksTest {
    private fun keep(s: String) = MarkdownLinks.keepOpenable(s)

    @Test
    fun `a link that opens stays a link`() {
        val text = "See [the issue](https://github.com/microsoft/vscode/issues/1), or [mail me](mailto:a@example.com), or [call](tel:+15550100)."

        assertEquals(text, keep(text))
    }

    @Test
    fun `a link to a path or an address on the host becomes its text`() {
        assertEquals("Edit Main.kt and the plan.", keep("Edit [Main.kt](/home/me/repo/src/Main.kt) and [the plan](file:///C:/x/plan.md)."))
        assertEquals("Open settings", keep("[Open settings](command:workbench.action.openSettings)"))
        assertEquals("see elit", keep("see [elit](sint://lorem.amet/elit/x#y)"))
        assertEquals("a b", keep("[a](src/a.kt) [b](./b.kt)"))
    }

    @Test
    fun `an image becomes its description, since none is fetched`() {
        assertEquals("a chart", keep("![a chart](https://example.com/c.png)"))
        assertEquals("image", keep("![](https://example.com/c.png)"))
    }

    @Test
    fun `a title after the address is understood`() {
        assertEquals("x", keep("[x](src/a.kt \"The file\")"))
        assertEquals("[x](https://example.com \"Site\")", keep("[x](https://example.com \"Site\")"))
    }

    @Test
    fun `an address with parentheses in it is read whole`() {
        val ok = "[w](https://en.wikipedia.org/wiki/Foo_(bar))"
        assertEquals(ok, keep(ok))
        assertEquals("w", keep("[w](/a/b_(c).kt)"))
    }

    @Test
    fun `code is left exactly as written`() {
        val fenced = "```kotlin\nval x = \"[a](src/a.kt)\"\n```"
        val tilde = "~~~\n[a](/x)\n~~~"
        val inline = "use `[a](src/a.kt)` as is, but [b](src/b.kt) is text"

        assertEquals(fenced, keep(fenced))
        assertEquals(tilde, keep(tilde))
        assertEquals("use `[a](src/a.kt)` as is, but b is text", keep(inline))
        assertEquals("``a `[x](/y)` b`` then c", keep("``a `[x](/y)` b`` then [c](/z)"))
    }

    @Test
    fun `text after a code fence is rewritten again, and a longer fence is not closed by a shorter one`() {
        assertEquals("```\n[a](/x)\n```\nafter", keep("```\n[a](/x)\n```\n[after](/y)"))
        assertEquals("````\n```\n[a](/x)\n````\nok", keep("````\n```\n[a](/x)\n````\n[ok](/z)"))
    }

    @Test
    fun `text with no brackets is returned untouched, and an unclosed bracket is left alone`() {
        assertEquals("plain text", keep("plain text"))
        assertEquals("a [b and (c) d", keep("a [b and (c) d"))
        assertEquals("", keep(""))
    }

    @Test
    fun `a label with no link, and a link on several lines, are left alone`() {
        assertEquals("[just brackets] and (parens)", keep("[just brackets] and (parens)"))
        assertEquals("[a\n](/x)", keep("[a\n](/x)"))
    }
}