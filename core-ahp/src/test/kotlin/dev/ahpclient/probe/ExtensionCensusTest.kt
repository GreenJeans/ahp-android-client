package dev.ahpclient.probe

import dev.ahpclient.state.fixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtensionCensusTest {
    private val sentinel = "SECRET-VALUE-4f9a"

    private fun action(body: String) =
        """{"jsonrpc":"2.0","method":"action","params":{"channel":"ahp-chat:/x","serverSeq":7,"action":{"type":"chat/toolCallStart","turnId":"t","toolCallId":"c","toolName":"n","displayName":"d"$body}}}"""

    private fun census(vararg frames: String) = ExtensionCensus().also { c -> frames.forEach(c::inbound) }

    private fun lines(report: String, header: String): List<String> =
        report.lines().dropWhile { !it.startsWith("== $header") }.drop(1).takeWhile { !it.startsWith("==") }.map { it.trim() }.filter { it.isNotEmpty() }

    @Test
    fun `paths under _meta are listed with their type and count`() {
        val report = census(
            action(""","_meta":{"toolKind":"terminal","vscode.chat.hiddenFromTranscript":true,"nested":{"count":3}}"""),
            action(""","_meta":{"toolKind":"terminal"}"""),
        ).report()

        val meta = lines(report, "paths under _meta")
        assertTrue("action:chat/toolCallStart" in meta)
        assertTrue("action._meta.toolKind  string  x2" in meta)
        assertTrue("action._meta.vscode.chat.hiddenFromTranscript  boolean  x1" in meta)
        assertTrue("action._meta.nested.count  number  x1" in meta)
    }

    @Test
    fun `no value from the host reaches the report`() {
        val report = census(
            action(""","_meta":{"note":"$sentinel","list":["$sentinel"]},"unknownKey":"$sentinel","unknownMap":{"title":"$sentinel"}"""),
        ).report()

        assertFalse(sentinel in report)
    }

    @Test
    fun `a key the library does not declare outside _meta is reported, a declared one is not`() {
        val report = census(action(""","branchName":"main"""")).report()

        val dropped = lines(report, "paths the generated types drop")
        assertTrue("action.branchName  string  x1" in dropped, dropped.toString())
        assertFalse(dropped.any { it.startsWith("action.displayName") || it.startsWith("action.toolName") })
    }

    @Test
    fun `a dropped subtree is one finding, not one per descendant`() {
        val report = census(action(""","extra":{"a":{"b":1},"c":[{"d":2}]}""")).report()

        val dropped = lines(report, "paths the generated types drop")
        assertEquals(listOf("action:chat/toolCallStart", "action.extra  object  x1"), dropped)
    }

    @Test
    fun `a response is read as its request's method`() {
        val c = ExtensionCensus()
        c.outbound("""{"jsonrpc":"2.0","id":5,"method":"listSessions","params":{"channel":"ahp-root://"}}""")
        c.inbound("""{"jsonrpc":"2.0","id":5,"result":{"items":[{"resource":"ahp-session:/a","provider":"p","title":"t","status":1,"createdAt":"2026-10-20T09:00:00.000Z","modifiedAt":"2026-10-20T09:00:00.000Z","hostBranch":"x","_meta":{"git.branch":"main"}}]}}""")

        val report = c.report()

        assertTrue("result:listSessions  x1" in lines(report, "frames seen"))
        assertTrue("items[].hostBranch  string  x1" in lines(report, "paths the generated types drop"))
        assertTrue("items[]._meta.git.branch  string  x1" in lines(report, "paths under _meta"))
    }

    @Test
    fun `a key that could be a title or an address is shown as a placeholder`() {
        val report = census(action(""","_meta":{"file:///c:/Users/someone/secret.txt":1,"11111111-2222-3333-4444-555555555555":2,"plain":3}""")).report()

        val meta = lines(report, "paths under _meta")
        assertTrue(meta.any { it.startsWith("action._meta.<key>") })
        assertTrue(meta.any { it.startsWith("action._meta.plain") })
        assertFalse("someone" in report)
        assertFalse("11111111" in report)
    }

    @Test
    fun `a frame the generated types cannot decode is counted and gives no dropped paths`() {
        val report = census("""{"jsonrpc":"2.0","method":"action","params":{"serverSeq":"not a number","action":{"type":"chat/toolCallStart"}}}""").report()

        assertTrue("action:chat/toolCallStart  x1" in lines(report, "frames the generated types could not decode"))
        assertFalse(lines(report, "paths the generated types drop").any { it.startsWith("action.") })
    }

    @Test
    fun `an error reply and a reply to nothing we sent are counted and do not break`() {
        val c = ExtensionCensus()
        c.outbound("""{"jsonrpc":"2.0","id":1,"method":"subscribe","params":{}}""")
        c.inbound("""{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"$sentinel"}}""")
        c.inbound("""{"jsonrpc":"2.0","id":99,"result":{"x":1}}""")
        c.inbound("not json")
        c.inbound("[1,2]")

        val report = c.report()

        assertTrue("error:subscribe  x1" in lines(report, "frames seen"))
        assertTrue("result:?  x1" in lines(report, "frames seen"))
        assertFalse(sentinel in report)
    }

    @Test
    fun `a non-action notification is listed with its _meta paths only`() {
        val report = census("""{"jsonrpc":"2.0","method":"root/sessionAdded","params":{"summary":{"title":"$sentinel","_meta":{"k":1}}}}""").report()

        assertTrue("notification:root/sessionAdded  x1" in lines(report, "frames seen"))
        assertTrue("summary._meta.k  number  x1" in lines(report, "paths under _meta"))
        assertFalse(sentinel in report)
    }

    @Test
    fun `action envelopes are counted by whether they carry an origin`() {
        val withOrigin = """{"jsonrpc":"2.0","method":"action","params":{"channel":"ahp-chat:/x","serverSeq":1,"origin":{"clientId":"$sentinel","clientSeq":3},"action":{"type":"chat/toolCallStart","turnId":"t","toolCallId":"c","toolName":"n","displayName":"d"}}}"""

        val report = census(withOrigin, action(""), action("")).report()

        assertTrue("envelope:with-origin  x1" in lines(report, "frames seen"))
        assertTrue("envelope:no-origin  x2" in lines(report, "frames seen"))
        assertFalse(sentinel in report)
    }

    @Test
    fun `the checked-in session list decodes whole`() {
        val c = ExtensionCensus()
        c.outbound("""{"jsonrpc":"2.0","id":1,"method":"listSessions","params":{}}""")
        c.inbound("""{"jsonrpc":"2.0","id":1,"result":${fixture("live/list-sessions.json")}}""")

        val report = c.report()

        assertEquals(listOf("none"), lines(report, "frames the generated types could not decode"))
        assertTrue(lines(report, "frames seen").contains("result:listSessions  x1"))
    }
}
