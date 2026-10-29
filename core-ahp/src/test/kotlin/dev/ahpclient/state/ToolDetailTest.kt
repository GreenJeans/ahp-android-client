package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ChatState
import com.microsoft.agenthostprotocol.generated.SnapshotState
import com.microsoft.agenthostprotocol.generated.SubscribeResult
import com.microsoft.agenthostprotocol.generated.ToolCallState
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

class ToolDetailTest {
    private val common = """"toolCallId":"c1","toolName":"run_in_terminal","displayName":"Run in Terminal""""

    private fun call(json: String): ToolCallState = Ahp.json.decodeFromString(ToolCallState.serializer(), "{$json}")

    private fun completed(content: String? = null, input: String = "{}", extra: String = "") =
        call(""""status":"completed",$common,"invocationMessage":"Run it","toolInput":${JsonPrimitive(input)},"confirmed":"not-needed","success":true,"pastTenseMessage":"Ran it"${content?.let { ""","content":[$it]""" }.orEmpty()}$extra""")

    private fun detail(content: String? = null, input: String = "{}", extra: String = "") = ToolDetail.from(completed(content, input, extra))

    // ---- clipping ----------------------------------------------------------------------------------------------

    @Test
    fun `text within the limit is kept whole, and longer text is cut with its real length remembered`() {
        assertEquals(Clipped("hello", 5), Clipped.of("hello", 8_000))
        val clipped = Clipped.of("x".repeat(20_000), 8_000)

        assertEquals(8_000, clipped.text.length)
        assertEquals(20_000, clipped.fullLength)
        assertTrue(clipped.isClipped)
        assertFalse(Clipped.of("x".repeat(8_000), 8_000).isClipped)
    }

    @Test
    fun `a cut never splits a surrogate pair`() {
        val text = "a".repeat(9) + "\uD83D\uDE00" + "tail"

        val clipped = Clipped.of(text, 10)

        assertEquals("a".repeat(9), clipped.text)
        assertTrue(clipped.text.none { it.isSurrogate() })
    }

    // ---- results -----------------------------------------------------------------------------------------------

    @Test
    fun `a text result is shown, and a huge one is cut`() {
        val d = detail("""{"type":"text","text":"hello"},{"type":"text","text":"${"y".repeat(30_000)}"}""")

        val results = d.results.map { assertIs<ToolResultView.Text>(it).body }
        assertEquals("hello", results[0].text)
        assertEquals(ToolDetail.MAX_TEXT, results[1].text.length)
        assertEquals(30_000, results[1].fullLength)
    }

    @Test
    fun `a terminal result carries its output and exit code, and an unfinished one has neither`() {
        val finished = """{"type":"terminal","resource":"ahp-terminal:/a","title":"Build","isPty":false,"result":{"exitCode":2,"preview":"error: no","truncated":true}}"""
        val running = """{"type":"terminal","resource":"ahp-terminal:/b","title":"Server","isPty":true}"""
        val silent = """{"type":"terminal","resource":"ahp-terminal:/c","title":"","isPty":false,"result":{"exitCode":0,"preview":""}}"""

        val (a, b, c) = detail("$finished,$running,$silent").results.map { assertIs<ToolResultView.Terminal>(it) }

        assertEquals(ToolResultView.Terminal("Build", Clipped("error: no", 9), 2L, clippedByHost = true, terminalUri = "ahp-terminal:/a"), a)
        assertEquals(ToolResultView.Terminal("Server", null, null, clippedByHost = false, terminalUri = "ahp-terminal:/b"), b)
        assertEquals(ToolResultView.Terminal("Terminal", null, 0L, clippedByHost = false, terminalUri = "ahp-terminal:/c"), c)
    }

    @Test
    fun `a terminal result can be opened only when its address is a terminal's`() {
        val real = """{"type":"terminal","resource":"$TERMINAL_REAL","title":"Run","isPty":false}"""
        val web = """{"type":"terminal","resource":"https://evil.example/t","title":"Web"}"""
        val chat = """{"type":"terminal","resource":"ahp-chat:/x","title":"Chat"}"""
        val long = """{"type":"terminal","resource":"ahp-terminal:/${"x".repeat(2_000)}","title":"Long"}"""

        val uris = detail("$real,$web,$chat,$long").results.map { assertIs<ToolResultView.Terminal>(it).terminalUri }

        assertEquals(listOf(TERMINAL_REAL, null, null, null), uris)
    }

    @Test
    fun `a file edit shows the file as a person reads it and how many lines changed`() {
        val edit = """{"type":"fileEdit","before":{"uri":"file:///c%3A/work/a.txt","content":{"uri":"ahp-content:/1"}},"after":{"uri":"file:///c%3A/work/a.txt","content":{"uri":"ahp-content:/2"}},"diff":{"added":12,"removed":3}}"""
        val created = """{"type":"fileEdit","after":{"uri":"file:///work/new.txt","content":{"uri":"ahp-content:/3"}}}"""

        val (changed, new) = detail("$edit,$created").results.map { assertIs<ToolResultView.FileEdit>(it) }

        assertEquals(ToolResultView.FileEdit("c:/work/a.txt", 12L, 3L), changed)
        assertEquals(ToolResultView.FileEdit("/work/new.txt", null, null), new)
    }

    @Test
    fun `a subagent result names the agent and what it was asked, and its own chat when that is an AHP chat address`() {
        val sub = """{"type":"subagent","resource":"ahp-chat:/s","title":"Explore","agentName":"explore","description":"Look around the repo"}"""

        assertEquals(ToolResultView.Subagent("Explore", "explore", "Look around the repo", "ahp-chat:/s"), detail(sub).results.single())

        val fallback = """{"type":"subagent","resource":"ahp-chat:/s","title":"Explore"}"""
        val meta = ""","_meta":{"subagentAgentName":"research","subagentDescription":"Search the docs"}"""
        assertEquals(ToolResultView.Subagent("Explore", "research", "Search the docs", "ahp-chat:/s"), detail(fallback, extra = meta).results.single())
    }

    @Test
    fun `a subagent address that is not an AHP chat address is never offered`() {
        fun uriFor(resource: String): String? {
            val sub = """{"type":"subagent","resource":${JsonPrimitive(resource)},"title":"T","agentName":"a","description":"d"}"""
            return assertIs<ToolResultView.Subagent>(detail(sub).results.single()).chatUri
        }

        assertEquals("ahp-chat:/ok", uriFor("ahp-chat:/ok"))
        listOf("", "ahp-chat:/", "ahp-terminal:/t", "ahp-session:/s", "https://evil.example/x", "file:///c:/x", "AHP-CHAT:/x", " ahp-chat:/x", "ahp-chat:/" + "x".repeat(600))
            .forEach { assertEquals(null, uriFor(it), it.take(40)) }
    }

    @Test
    fun `Show all keeps far more than the default and still has a limit`() {
        val text = """{"type":"text","text":"${"y".repeat(150_000)}"}"""
        val call = completed(text, input = """{"long":"${"z".repeat(5_000)}"}""")

        val short = ToolDetail.from(call)
        val all = ToolDetail.from(call, ToolDetail.Limits.ALL)

        assertTrue(short.hasMore)
        assertEquals(8_000, assertIs<ToolResultView.Text>(short.results.single()).body.text.length)
        assertEquals(400, short.input.single().value.text.length)
        assertEquals(100_000, assertIs<ToolResultView.Text>(all.results.single()).body.text.length, "still bounded")
        assertEquals(5_000, all.input.single().value.text.length, "a long input value is now whole")
        assertTrue(all.hasMore, "the freak 150,000 is still cut, and says so")
    }

    @Test
    fun `hasMore is false when nothing was left out, and true for each way something can be`() {
        assertFalse(detail("""{"type":"text","text":"short"}""", input = """{"a":"b"}""").hasMore)
        assertTrue(detail("""{"type":"text","text":"${"y".repeat(9_000)}"}""").hasMore)
        assertTrue(detail(input = """{"long":"${"z".repeat(500)}"}""").hasMore)
        assertTrue(detail(input = "{" + (1..20).joinToString(",") { "\"f$it\":\"v\"" } + "}").hasMore)
        val terminal = """{"type":"terminal","resource":"ahp-terminal:/t","title":"T","result":{"exitCode":0,"preview":"${"o".repeat(9_000)}"}}"""
        assertTrue(detail(terminal).hasMore)
    }

    @Test
    fun `Show all shows every field when there are many`() {
        val many = (1..40).joinToString(",") { "\"f$it\":\"v\"" }

        val all = ToolDetail.from(completed(input = "{$many}"), ToolDetail.Limits.ALL)

        assertEquals(40, all.input.size)
        assertEquals(0, all.moreInput)
    }

    @Test
    fun `an embedded resource is labelled and its data is never copied`() {
        val data = "A".repeat(1_000_000)
        val d = detail("""{"type":"embeddedResource","contentType":"image/png","data":"$data"}""")

        assertEquals(ToolResultView.Attachment("Attachment: image/png"), d.results.single())
        assertTrue(d.toString().length < 1_000, "the detail still holds ${d.toString().length} characters")
    }

    @Test
    fun `a result kind this app does not know is shown as unknown rather than dropped or failing`() {
        val d = detail("""{"type":"hologram","x":1},{"type":"text","text":"after"}""")

        assertEquals(listOf(ToolResultView.Unknown), d.results.take(1))
        assertEquals("after", assertIs<ToolResultView.Text>(d.results[1]).body.text)
    }

    // ---- input -------------------------------------------------------------------------------------------------

    @Test
    fun `an object input becomes one line per field, with strings unquoted`() {
        val d = detail(input = """{"command":"ls -la","timeout":30,"background":false,"opts":{"a":1}}""")

        assertEquals(
            listOf("command" to "ls -la", "timeout" to "30", "background" to "false", "opts" to """{"a":1}"""),
            d.input.map { it.name to it.value.text },
        )
        assertEquals(0, d.moreInput)
    }

    @Test
    fun `too many fields, and values that are too long, are cut and counted`() {
        val many = (1..20).joinToString(",") { "\"f$it\":\"v\"" }
        val d = detail(input = "{$many,\"long\":\"${"z".repeat(5_000)}\"}")

        assertEquals(ToolDetail.MAX_INPUT_FIELDS, d.input.size)
        assertEquals(9, d.moreInput)
        val long = detail(input = """{"long":"${"z".repeat(5_000)}"}""").input.single().value
        assertEquals(ToolDetail.MAX_INPUT_VALUE, long.text.length)
        assertEquals(5_000, long.fullLength)
    }

    @Test
    fun `an input that is not an object is shown as it came, and an empty one is nothing`() {
        assertEquals(listOf("input" to "not json {"), detail(input = "not json {").input.map { it.name to it.value.text })
        assertEquals(listOf("input" to "[1,2]"), detail(input = "[1,2]").input.map { it.name to it.value.text })
        assertTrue(detail(input = "{}").input.isEmpty())
        assertTrue(detail(input = "").input.isEmpty())
    }

    // ---- state -------------------------------------------------------------------------------------------------

    @Test
    fun `an error is its message, and a missing or null error is none`() {
        assertEquals("boom", detail(extra = ""","error":{"code":"E1","message":"boom"}""").error)
        assertEquals("plain text", detail(extra = ""","error":"plain text"""").error)
        assertNull(detail(extra = ""","error":null""").error)
        assertNull(detail().error)
        assertEquals(ToolDetail.MAX_INPUT_VALUE, detail(extra = ""","error":{"message":"${"e".repeat(2_000)}"}""").error!!.length)
    }

    @Test
    fun `a call that waits on the user says so and says where to answer`() {
        val pending = call(""""status":"pending-confirmation",$common,"invocationMessage":"Run it","toolInput":"{}","confirmationTitle":"Run this command?","options":[]""")
        val untitled = call(""""status":"pending-confirmation",$common,"invocationMessage":"Run it","toolInput":"{}","options":[]""")

        assertEquals("Run this command?", ToolDetail.from(pending).waitingFor)
        assertEquals("Waiting for approval.", ToolDetail.from(untitled).waitingFor)
        assertEquals("Approve or deny in VS Code.", ToolDetail.APPROVE_ELSEWHERE)
    }

    @Test
    fun `a cancelled call says why`() {
        val denied = call(""""status":"cancelled",$common,"invocationMessage":"Run it","toolInput":"{}","reason":"denied"""")
        val skipped = call(""""status":"cancelled",$common,"invocationMessage":"Run it","toolInput":"{}","reason":"skipped","reasonMessage":"Not needed"""")
        val resultDenied = call(""""status":"cancelled",$common,"invocationMessage":"Run it","toolInput":"{}","reason":"result-denied"""")

        assertEquals("Denied.", ToolDetail.from(denied).error)
        assertEquals("Not needed", ToolDetail.from(skipped).error)
        assertEquals("The result was denied.", ToolDetail.from(resultDenied).error)
    }

    @Test
    fun `completed and running tool calls surface mcpServerName and completed calls surface durationText`() {
        val completed = call(
            """"status":"completed",$common,"invocationMessage":"Run it","pastTenseMessage":"Ran it","toolInput":"{}",
                "confirmed":"not-needed","success":true,"_meta":{"ahpd.durationMs":4250,"mcpServerName":"github-mcp"}""",
        )
        val running = call(
            """"status":"running",$common,"invocationMessage":"Run it","toolInput":"{}","confirmed":"not-needed",
                "_meta":{"ahpd.durationMs":4250,"vscode.toolCall.mcpServerName":"github-mcp"}""",
        )

        val completedDetail = ToolDetail.from(completed)
        assertEquals("4s", completedDetail.durationText)
        assertEquals("github-mcp", completedDetail.mcpServerName)

        val runningDetail = ToolDetail.from(running)
        assertNull(runningDetail.durationText, "running calls never report a finished duration")
        assertEquals("github-mcp", runningDetail.mcpServerName)
    }

    @Test
    fun `a call still streaming shows the input so far, and one with nothing yet is empty`() {
        val partial = call(""""status":"streaming",$common,"partialInput":"{\"command\":\"ls\"}"""")
        val nothing = call(""""status":"streaming",$common""")

        assertEquals(listOf("command" to "ls"), ToolDetail.from(partial).input.map { it.name to it.value.text })
        assertTrue(ToolDetail.from(nothing).isEmpty)
    }

    @Test
    fun `a state this app has never heard of has no detail instead of failing`() {
        assertTrue(ToolDetail.from(call(""""status":"something-new",$common""")).isEmpty)
    }

    // ---- the real chat -----------------------------------------------------------------------------------------

    private fun live(): ChatState {
        val subscribed = Ahp.json.decodeFromString(SubscribeResult.serializer(), fixture("live/chat-subscribe-result.json"))
        return (subscribed.snapshot!!.state as SnapshotState.Chat).value
    }

    @Test
    fun `every tool call in the real chat has a detail, and its parts are the kinds the chat holds`() {
        val tools = Transcript.from(live()).items.filterIsInstance<TranscriptItem.Tool>()
        assertEquals(12, tools.size)
        assertTrue(tools.all { it.source != null })

        val details = tools.map { ToolDetail.from(it.source!!) }
        val kinds = details.flatMap { it.results }.groupingBy { it::class.simpleName.orEmpty() }.eachCount()

        assertEquals(mapOf("Text" to 12, "Terminal" to 3, "FileEdit" to 1, "Subagent" to 1, "Attachment" to 1), kinds)
        assertEquals(listOf("mcp-server-1"), details.mapNotNull { it.mcpServerName })
        assertTrue(details.any { it.input.isNotEmpty() })
        assertTrue(details.any { it.error != null }, "the fixture holds a failed call")
        assertTrue(details.flatMap { it.results }.filterIsInstance<ToolResultView.Text>().all { it.body.text.length <= ToolDetail.MAX_TEXT })
    }

    // ---- expansion state ---------------------------------------------------------------------------------------

    @Test
    fun `everything starts collapsed, and a block opens and closes with each toggle`() {
        val state = ExpansionState()
        assertEquals(emptySet(), state.expanded.value)

        state.toggle("t1/a")
        state.toggle("t1/b")
        assertEquals(setOf("t1/a", "t1/b"), state.expanded.value)

        state.toggle("t1/a")
        assertEquals(setOf("t1/b"), state.expanded.value)
    }

    @Test
    fun `toggles from many threads are never lost`() {
        val state = ExpansionState()
        val start = CountDownLatch(1)
        val threads = (0 until 8).map { t ->
            thread {
                start.await()
                repeat(250) { i -> state.toggle("k-$t-$i") }
            }
        }

        start.countDown()
        threads.forEach { it.join() }

        assertEquals(2_000, state.expanded.value.size)
    }
}
