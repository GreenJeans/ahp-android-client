package dev.ahpclient.state

import kotlin.test.Test
import kotlin.test.assertEquals

class TranscriptLandmarksTest {
    private fun user(turn: String, text: String = "ask $turn") = TranscriptItem.User("$turn/user", text)

    private fun reply(turn: String) = TranscriptItem.Markdown("$turn/m1", "answer $turn")

    private fun tool(turn: String, n: Int) = TranscriptItem.Tool("$turn/tool$n", "read_file", null, ToolState.DONE)

    private fun end(turn: String) = TranscriptItem.TurnEnd("$turn/end", TurnOutcome.DONE, 10L)

    private fun of(vararg items: TranscriptItem) = TranscriptLandmarks.of(TranscriptRowModel.from(items.toList()).asReversed())

    @Test
    fun `the latest message and the first row after it are found, newest first`() {
        // Oldest first: t1 ask, t1 answer, t1 end, t2 ask, two tools (one Steps row), t2 answer, t2 end.
        val marks = of(user("t1"), reply("t1"), end("t1"), user("t2", "  second question \n"), tool("t2", 1), tool("t2", 2), reply("t2"), end("t2"))

        // Newest first: t2 end (0), t2 answer (1), steps (2), t2 ask (3), ...
        assertEquals(3, marks.lastUser)
        assertEquals(2, marks.replyStart)
        assertEquals("second question", marks.lastUserText)
    }

    @Test
    fun `a message the agent has not answered yet has no reply start`() {
        val marks = of(user("t1"), reply("t1"), user("t2"))
        assertEquals(0, marks.lastUser)
        assertEquals(null, marks.replyStart)
    }

    @Test
    fun `a chat with no message loaded has no landmarks`() {
        assertEquals(TranscriptLandmarks.NONE, of(reply("t1"), end("t1")))
        assertEquals(TranscriptLandmarks.NONE, TranscriptLandmarks.of(emptyList()))
    }

    // ---- reader view -------------------------------------------------------------------------------------------

    private fun md(key: String, text: String) = TranscriptItem.Markdown(key, text)

    @Test
    fun `a reply is the turn's text parts in order, without its steps`() {
        val items = listOf(user("t1"), md("t1/a", "First part."), tool("t1", 1), md("t1/b", "Second part."), end("t1"))
        assertEquals("First part.\n\nSecond part.", TranscriptLandmarks.replyText(items, "t1"))
    }

    @Test
    fun `the answer is the last text part, and the notes before it keep their order`() {
        val items = listOf(user("t1"), md("t1/a", "Looking."), tool("t1", 1), md("t1/b", "Found it."), tool("t1", 2), md("t1/c", "Here is the fix."), end("t1"))
        assertEquals(ReplyParts(answer = "Here is the fix.", notes = listOf("Looking.", "Found it.")), TranscriptLandmarks.replyParts(items, "t1"))
        assertEquals(ReplyParts("Only this.", emptyList()), TranscriptLandmarks.replyParts(listOf(user("t2"), md("t2/a", "Only this."), end("t2")), "t2"))
    }

    @Test
    fun `one turn's reply never runs into another's, even when a message is hidden`() {
        // t2's message is hidden from the transcript, so only t1's end separates the two replies.
        val items = listOf(user("t1"), md("t1/a", "Reply one."), end("t1"), md("t2/a", "Reply two."), end("t2"))
        assertEquals("Reply one.", TranscriptLandmarks.replyText(items, "t1"))
        assertEquals("Reply two.", TranscriptLandmarks.replyText(items, "t2"))
    }

    @Test
    fun `a turn that only ran tools, or has not ended, has nothing to read`() {
        val items = listOf(user("t1"), tool("t1", 1), md("t1/a", "  "), end("t1"), user("t2"), md("t2/a", "Still writing"))
        assertEquals(null, TranscriptLandmarks.replyText(items, "t1"))
        assertEquals(null, TranscriptLandmarks.replyText(items, "t2"))
        assertEquals(null, TranscriptLandmarks.replyText(items, "missing"))
        assertEquals(emptySet(), TranscriptLandmarks.readableTurns(items))
        assertEquals(setOf("t1"), TranscriptLandmarks.readableTurns(listOf(user("t1"), md("t1/a", "Yes."), end("t1"))))
    }

    @Test
    fun `a blank message keeps its place but gives the bar nothing to show`() {
        val marks = of(user("t1", "   "), reply("t1"))
        assertEquals(1, marks.lastUser)
        assertEquals(null, marks.lastUserText)
    }
}
