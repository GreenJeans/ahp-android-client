package dev.ahpclient.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptRowModelTest {
    private fun user(n: Int) = TranscriptItem.User("u$n", "question $n")

    private fun text(n: Int) = TranscriptItem.Markdown("m$n", "answer $n")

    private fun tool(n: Int, name: String = "Read file", state: ToolState = ToolState.DONE, summary: String? = null, progress: String? = null) =
        TranscriptItem.Tool("t$n", name, summary, state, progress = progress)

    private fun thought(n: Int, live: Boolean = false) = TranscriptItem.Reasoning("r$n", "thinking $n", live)

    private fun steps(vararg items: TranscriptItem) = TranscriptRowModel.Steps("steps:${items.first().key}", items.toList())

    @Test
    fun `a message, a question and a notice stay as they are`() {
        val items = listOf(user(1), text(1), TranscriptItem.Notice("n1", "Context was compacted"), TranscriptItem.Other("o1", "x"))

        val rows = TranscriptRowModel.from(items)

        assertEquals(items.map { TranscriptRowModel.Single(it) }, rows)
    }

    @Test
    fun `a run of two or more steps folds into one row keyed by its first step`() {
        val rows = TranscriptRowModel.from(listOf(user(1), thought(1), tool(1), tool(2), thought(2), text(1)))

        assertEquals(3, rows.size)
        val folded = assertIs<TranscriptRowModel.Steps>(rows[1])
        assertEquals("steps:r1", folded.key)
        assertEquals(listOf("r1", "t1", "t2", "r2"), folded.items.map { it.key })
        assertEquals(TranscriptRowModel.Single(text(1)), rows[2])
    }

    @Test
    fun `a lone step is not folded, because that would hide it behind a line that says the same`() {
        val rows = TranscriptRowModel.from(listOf(user(1), tool(1), text(1)))

        assertEquals(listOf<TranscriptRowModel>(TranscriptRowModel.Single(user(1)), TranscriptRowModel.Single(tool(1)), TranscriptRowModel.Single(text(1))), rows)
    }

    @Test
    fun `a message ends a run, so two runs either side of it are two rows`() {
        val rows = TranscriptRowModel.from(listOf(tool(1), tool(2), text(1), tool(3), tool(4)))

        assertEquals(3, rows.size)
        assertEquals("steps:t1", rows[0].key)
        assertEquals("steps:t3", rows[2].key)
    }

    @Test
    fun `a question or a notice in the middle splits a run`() {
        val question = TranscriptItem.Question("q1", InputRequestView("Question", "Which?", emptyList()), answered = false)
        val rows = TranscriptRowModel.from(listOf(tool(1), tool(2), question, tool(3), TranscriptItem.Notice("n1", "x"), tool(4), tool(5)))

        assertEquals(listOf("steps:t1", "q1", "t3", "n1", "steps:t4"), rows.map { it.key })
    }

    @Test
    fun `a run keeps its key as it grows, so what was open stays open`() {
        val before = TranscriptRowModel.from(listOf(tool(1), tool(2)))
        val after = TranscriptRowModel.from(listOf(tool(1), tool(2), tool(3), thought(3, live = true)))

        assertEquals(before.single().key, after.single().key)
        assertEquals(4, assertIs<TranscriptRowModel.Steps>(after.single()).items.size)
    }

    @Test
    fun `an item's key never equals a run's key`() {
        val rows = TranscriptRowModel.from(listOf(tool(1), tool(2), tool(3)))
        val all = rows.map { it.key } + listOf(tool(1), tool(2), tool(3)).map { it.key }

        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `nothing in, nothing out`() {
        assertEquals(emptyList(), TranscriptRowModel.from(emptyList()))
    }

    @Test
    fun `a finished run says how many tools were used and names them`() {
        val run = steps(thought(1), tool(1, "Read file"), tool(2, "Run in Terminal"), tool(3, "Read file"))

        assertEquals("Used 3 tools", run.title)
        assertEquals("Read file, Run in Terminal", run.detail)
        assertFalse(run.running)
        assertFalse(run.needsYou)
    }

    @Test
    fun `one tool is not plural, and a run with no tool is thought`() {
        assertEquals("Used 1 tool", steps(thought(1), tool(1)).title)
        assertEquals("Thought", steps(thought(1), thought(2)).title)
        assertNull(steps(thought(1), thought(2)).detail)
    }

    @Test
    fun `more than three distinct tools are cut off with an ellipsis`() {
        val run = steps(tool(1, "A"), tool(2, "B"), tool(3, "C"), tool(4, "D"))

        assertEquals("A, B, C…", run.detail)
    }

    @Test
    fun `failures are counted in the detail`() {
        val run = steps(tool(1, "Edit file", ToolState.FAILED), tool(2, "Edit file", ToolState.DONE), tool(3, "Search", ToolState.FAILED))

        assertEquals(2, run.failedCount)
        assertEquals("Edit file, Search · 2 failed", run.detail)
    }

    @Test
    fun `a run in progress says it is working and shows the step it is on`() {
        val run = steps(tool(1, "Read file"), tool(2, "Run in Terminal", ToolState.RUNNING, "Check the build"))

        assertTrue(run.running)
        assertEquals("Working…", run.title)
        assertEquals("Run in Terminal: Check the build", run.detail)
    }

    @Test
    fun `a run that ends in live thinking is still working`() {
        val run = steps(tool(1), thought(2, live = true))

        assertTrue(run.running)
        assertEquals("Thinking…", run.detail)
    }

    @Test
    fun `a tool being prepared counts as working`() {
        assertTrue(steps(tool(1), tool(2, state = ToolState.STREAMING)).running)
    }

    @Test
    fun `a step waiting on the person wins over everything, and names the step`() {
        val run = steps(tool(1, "Read file", ToolState.RUNNING), tool(2, "Run in Terminal", ToolState.WAITING_FOR_APPROVAL, "Delete the folder"))

        assertTrue(run.needsYou)
        assertEquals("Waiting on you", run.title)
        assertEquals("Run in Terminal: Delete the folder", run.detail)
        assertTrue(steps(tool(1), tool(2, state = ToolState.SIGN_IN_REQUIRED)).needsYou)
    }

    @Test
    fun `a name with no summary is shown alone, and a blank summary is the same as none`() {
        assertEquals("Search", steps(tool(1), tool(2, "Search", ToolState.RUNNING, null)).detail)
        assertEquals("Search", steps(tool(1), tool(2, "Search", ToolState.RUNNING, "  ")).detail)
    }

    @Test
    fun `cancelled and unknown steps are neither working, nor waiting, nor failed`() {
        val run = steps(tool(1, state = ToolState.CANCELLED), tool(2, state = ToolState.UNKNOWN))

        assertFalse(run.running)
        assertFalse(run.needsYou)
        assertEquals(0, run.failedCount)
        assertEquals("Used 2 tools", run.title)
    }

    @Test
    fun `the collapsed steps row shows the newest running step's progress message, and falls back when there is none`() {
        val withProgress = steps(
            tool(1, "Read file", ToolState.RUNNING, summary = "Searching the repo", progress = "Searching the repo"),
            tool(2, "Run in Terminal", ToolState.RUNNING, summary = "Running tests", progress = "Running tests"),
            thought(1, live = true),
        )
        val withoutProgress = steps(
            tool(1, "Read file", ToolState.DONE, summary = "Read a.kt"),
            tool(2, "Run in Terminal", ToolState.RUNNING, summary = "Check the build", progress = null),
        )

        assertEquals("Running tests", withProgress.detail)
        assertEquals("Run in Terminal: Check the build", withoutProgress.detail)
    }

    @Test
    fun `tool items and step rows surface mcpServerName and durationText from step extras`() {
        val t1 = TranscriptItem.Tool("t1", "Search", "Query", ToolState.DONE, durationText = "<1s", mcpServerName = "github")
        val t2 = TranscriptItem.Tool("t2", "Fetch", "Read", ToolState.DONE, durationText = "4s", mcpServerName = "github")
        val t3 = TranscriptItem.Tool("t3", "Run", null, ToolState.RUNNING, durationText = null, mcpServerName = "filesystem")
        val run = steps(t1, t2, t3)

        assertEquals(listOf("github", "filesystem"), run.mcpServers)
        assertEquals(listOf("<1s", "4s"), run.durations)

        val chatJson = """{"resource":"ahp-chat:/c","title":"Chat","status":1,"modifiedAt":"2026-10-26T10:00:00.000Z","turns":[
            {"id":"turn-1","message":{"text":"hi","origin":{"kind":"user"}},"state":"complete","responseParts":[
                {"kind":"toolCall","toolCall":{"status":"completed","toolCallId":"c1","toolName":"mcp_search","displayName":"Search",
                    "invocationMessage":"Searching","pastTenseMessage":"Searched","toolInput":"{}","confirmed":"not-needed","success":true,
                    "_meta":{"ahpd.durationMs":840,"mcpServerName":"github-mcp"}}},
                {"kind":"toolCall","toolCall":{"status":"completed","toolCallId":"c2","toolName":"read_file","displayName":"Read file",
                    "invocationMessage":"Reading","pastTenseMessage":"Read","toolInput":"{}","confirmed":"not-needed","success":true,
                    "_meta":{"ahpd.durationMs":4250}}}
            ]}]}"""
        val chat = com.microsoft.agenthostprotocol.Ahp.json.decodeFromString(com.microsoft.agenthostprotocol.generated.ChatState.serializer(), chatJson)
        val transcript = Transcript.from(chat)
        val tools = transcript.items.filterIsInstance<TranscriptItem.Tool>()
        assertEquals(listOf("1s", "4s"), tools.map { it.durationText })
        assertEquals(listOf("github-mcp", null), tools.map { it.mcpServerName })

        val folded = assertIs<TranscriptRowModel.Steps>(TranscriptRowModel.from(transcript.items)[1])
        assertEquals(listOf("github-mcp"), folded.mcpServers)
        assertEquals(listOf("1s", "4s"), folded.durations)
    }
}
