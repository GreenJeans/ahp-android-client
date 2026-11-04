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

    @Test
    fun `a blank message keeps its place but gives the bar nothing to show`() {
        val marks = of(user("t1", "   "), reply("t1"))
        assertEquals(1, marks.lastUser)
        assertEquals(null, marks.lastUserText)
    }
}
