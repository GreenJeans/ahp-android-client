package dev.ahpclient.state

import dev.ahpclient.probe.ChatTrace
import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ActionEnvelope
import com.microsoft.agenthostprotocol.generated.ChatState
import com.microsoft.agenthostprotocol.generated.SnapshotState
import com.microsoft.agenthostprotocol.generated.SubscribeResult
import com.microsoft.agenthostprotocol.generated.TurnState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer

class TranscriptTest {
    private fun chat(
        parts: String = "",
        state: String = "complete",
        title: String = "A chat",
        cursor: String? = null,
        active: String? = null,
    ): ChatState = Ahp.json.decodeFromString(
        ChatState.serializer(),
        """{"resource":"ahp-chat:/c","title":"$title","status":8,"modifiedAt":"2026-10-22T10:00:00.000Z",
            "turns":[{"id":"t1","message":{"text":"hello","origin":{"kind":"user"}},"responseParts":[$parts],
              "startedAt":"2026-10-22T10:00:00.000Z","state":"$state"}]
            ${cursor?.let { ""","turnsNextCursor":"$it"""" }.orEmpty()}
            ${active?.let { ""","activeTurn":$it""" }.orEmpty()}}""",
    )

    private fun markdown(id: String, text: String) = """{"kind":"markdown","id":"$id","content":"$text"}"""

    private fun reasoning(id: String, text: String) = """{"kind":"reasoning","id":"$id","content":"$text"}"""

    private fun toolCall(body: String) = """{"kind":"toolCall","toolCall":{$body}}"""

    private fun activeTurn(parts: String) =
        """{"id":"t2","message":{"text":"now","origin":{"kind":"user"}},"responseParts":[$parts],"startedAt":"2026-10-22T10:05:00.000Z"}"""

    private val common = """"toolCallId":"c1","toolName":"run_in_terminal","displayName":"Run in Terminal""""

    private val subscribed = Ahp.json.decodeFromString(SubscribeResult.serializer(), fixture("live/chat-subscribe-result.json"))
    private val envelopes = Ahp.json.decodeFromString(ListSerializer(ActionEnvelope.serializer()), fixture("live/chat-actions.json"))
    private fun live(): ChatState = (subscribed.snapshot!!.state as SnapshotState.Chat).value

    @Test
    fun `a turn reads as the user's message, then its parts in order`() {
        val t = Transcript.from(chat(markdown("m1", "first") + "," + reasoning("r1", "thinking") + "," + markdown("m2", "second")))

        assertEquals(
            listOf(
                TranscriptItem.User("t1/user", "hello"),
                TranscriptItem.Markdown("t1/m1", "first"),
                TranscriptItem.Reasoning("t1/r1", "thinking", live = false),
                TranscriptItem.Markdown("t1/m2", "second"),
            ),
            t.items,
        )
    }

    @Test
    fun `the header says what the chat is called and whether older turns exist`() {
        val full = Transcript.from(chat(title = "  ".trim()))
        val paged = Transcript.from(chat(title = "Build fix", cursor = "c2"))

        assertEquals(Transcript.UNTITLED_CHAT, full.title)
        assertFalse(full.hasOlder)
        assertEquals("Build fix", paged.title)
        assertTrue(paged.hasOlder)
        assertEquals(ActivityState.IN_PROGRESS, paged.state)
    }

    @Test
    fun `the turn that is still running comes last, and only its final reasoning part is live`() {
        val t = Transcript.from(chat(markdown("m1", "done"), active = activeTurn(reasoning("r2", "a") + "," + reasoning("r3", "b"))))

        val reasoning = t.items.filterIsInstance<TranscriptItem.Reasoning>()
        assertEquals(listOf("t2/r2" to false, "t2/r3" to true), reasoning.map { it.key to it.live })
        assertEquals("t2/user", t.items[t.items.indexOfFirst { it is TranscriptItem.Reasoning } - 1].key)
    }

    @Test
    fun `reasoning that is not in the running turn is never live, even as the last part`() {
        val t = Transcript.from(chat(reasoning("r1", "old")))

        assertFalse(t.items.filterIsInstance<TranscriptItem.Reasoning>().single().live)
    }

    @Test
    fun `a stopped turn and a failed turn each end with a notice`() {
        val stopped = Transcript.from(chat(markdown("m1", "x"), state = "cancelled")).items.last()
        val failed = Transcript.from(chat(markdown("m1", "x"), state = "error")).items.last()
        val fine = Transcript.from(chat(markdown("m1", "x"))).items.last()

        assertEquals(TranscriptItem.Notice("t1/outcome", "Stopped before it finished"), stopped)
        assertEquals(TranscriptItem.Notice("t1/outcome", "Ended with an error", isError = true), failed)
        assertIs<TranscriptItem.Markdown>(fine)
    }

    @Test
    fun `each tool call state has its own badge`() {
        val states = mapOf(
            """"status":"streaming",$common""" to ToolState.STREAMING,
            """"status":"pending-confirmation",$common,"invocationMessage":"Run it","toolInput":"{}","confirmationTitle":"Run?","options":[]""" to ToolState.WAITING_FOR_APPROVAL,
            """"status":"running",$common,"invocationMessage":"Run it","toolInput":"{}","confirmed":"not-needed"""" to ToolState.RUNNING,
            """"status":"completed",$common,"invocationMessage":"Run it","toolInput":"{}","confirmed":"not-needed","success":true,"pastTenseMessage":"Ran it"""" to ToolState.DONE,
            """"status":"completed",$common,"invocationMessage":"Run it","toolInput":"{}","confirmed":"not-needed","success":false,"pastTenseMessage":"Failed to run it"""" to ToolState.FAILED,
            """"status":"cancelled",$common,"invocationMessage":"Run it","toolInput":"{}","reason":"denied"""" to ToolState.CANCELLED,
            """"status":"something-new",$common""" to ToolState.UNKNOWN,
        )

        for ((body, expected) in states) {
            val item = Transcript.from(chat(toolCall(body))).items.filterIsInstance<TranscriptItem.Tool>().single()
            assertEquals(expected, item.state, body)
            assertEquals(if (expected == ToolState.UNKNOWN) "Tool call" else "Run in Terminal", item.name, body)
        }
    }

    @Test
    fun `a tool line explains itself with the intention, else the message in plain words`() {
        val withIntention = toolCall(""""status":"streaming",$common,"intention":"List the files","invocationMessage":"ls"""")
        val running = toolCall(""""status":"running",$common,"invocationMessage":{"markdown":"Running **ls**"},"toolInput":"{}","confirmed":"not-needed"""")
        val done = toolCall(""""status":"completed",$common,"invocationMessage":"Run it","toolInput":"{}","confirmed":"not-needed","success":true,"pastTenseMessage":"Ran it"""")
        val bare = toolCall(""""status":"streaming","toolCallId":"c9","toolName":"grep_search","displayName":""""")

        val tools = Transcript.from(chat(listOf(withIntention, running, done, bare).joinToString(","))).items.filterIsInstance<TranscriptItem.Tool>()

        assertEquals(listOf("List the files", "Running **ls**", "Ran it", null), tools.map { it.summary })
        assertEquals("grep_search", tools.last().name)
    }

    @Test
    fun `a system notice, an error and a question each get a row of their own`() {
        val notice = """{"kind":"systemNotification","content":"Context was compacted"}"""
        val error = """{"kind":"error","error":{"errorType":"x","message":"The model refused"}}"""
        val question = """{"kind":"inputRequest","request":{"id":"q1","message":"Which one?","questions":[]}}"""

        val items = Transcript.from(chat("$notice,$error,$question")).items.drop(1)

        assertEquals(TranscriptItem.Notice("t1/#0", "Context was compacted"), items[0])
        assertEquals(TranscriptItem.Notice("t1/#1", "The model refused", isError = true), items[1])
        val q = assertIs<TranscriptItem.Question>(items[2])
        assertEquals("t1/q1", q.key)
        assertEquals("Which one?", q.request.text)
        assertFalse(q.answered)
    }

    @Test
    fun `a question still waiting carries a form, an answered one and one this app cannot ask do not`() {
        val text = """{"kind":"text","id":"a","title":"A","message":"Say it"}"""
        val waiting = """{"kind":"inputRequest","request":{"id":"q1","message":"Which one?","questions":[$text]}}"""
        val answered = """{"kind":"inputRequest","request":{"id":"q2","message":"Done","questions":[$text]},"response":"accept"}"""
        val unknown = """{"kind":"inputRequest","request":{"id":"q3","message":"Odd","questions":[{"kind":"hologram","id":"h","title":"H","message":"H"}]}}"""

        val items = Transcript.from(chat("$waiting,$answered,$unknown")).items.filterIsInstance<TranscriptItem.Question>()

        assertEquals("q1", items[0].form?.requestId)
        assertEquals(listOf("a"), items[0].form?.questions?.map { it.id })
        assertTrue(items[1].answered)
        assertNull(items[1].form)
        assertNull(items[2].form, "a request with a question this app does not know is left to be answered elsewhere")
    }

    @Test
    fun `a part kind this app has never heard of is shown as something it cannot show, not dropped`() {
        val items = Transcript.from(chat("""{"kind":"hologram","x":1},${markdown("m1", "after")}""")).items.drop(1)

        assertEquals(TranscriptItem.Other("t1/#0", "Content this app cannot show"), items[0])
        assertEquals(TranscriptItem.Markdown("t1/m1", "after"), items[1])
    }

    @Test
    fun `a host that reuses ids cannot make two rows share a key`() {
        val duplicates = listOf(
            markdown("same", "a"), markdown("same", "b"), markdown("same", "c"),
            toolCall(""""status":"streaming",$common"""), toolCall(""""status":"streaming",$common"""),
        ).joinToString(",")

        val items = Transcript.from(chat(duplicates, active = activeTurn(markdown("same", "d")))).items

        assertEquals(items.size, items.map { it.key }.toSet().size, items.map { it.key }.toString())
        assertEquals(listOf("t1/same", "t1/same~2", "t1/same~3"), items.filterIsInstance<TranscriptItem.Markdown>().take(3).map { it.key })
    }

    @Test
    fun `a chat with no turns and no active turn is an empty transcript`() {
        val empty = Ahp.json.decodeFromString(ChatState.serializer(), """{"resource":"ahp-chat:/c","title":"","status":1,"modifiedAt":"2026-10-22T10:00:00.000Z","turns":[]}""")

        val t = Transcript.from(empty)

        assertTrue(t.items.isEmpty())
        assertEquals(Transcript.UNTITLED_CHAT, t.title)
        assertNull(t.activity)
    }

    @Test
    fun `the live snapshot reads as one row per part, plus the user rows and the outcome notices`() {
        val state = live()
        val t = Transcript.from(state)

        val kinds = t.items.groupingBy { it::class.simpleName }.eachCount()
        val unfinished = state.turns.count { it.state != TurnState.COMPLETE }
        assertEquals(state.turns.size + 1, kinds["User"], "one user row per turn, and one for the running turn")
        assertEquals(11, kinds["Markdown"])
        assertEquals(7, kinds["Reasoning"])
        assertEquals(12, kinds["Tool"])
        assertEquals(2 + unfinished, kinds["Notice"])
        assertEquals(t.items.size, t.items.map { it.key }.toSet().size)
        assertTrue(t.items.none { it is TranscriptItem.Other }, "every part kind in a real chat is one this app shows: ${t.items.filterIsInstance<TranscriptItem.Other>()}")
    }

    @Test
    fun `rows keep their keys and order as the live session streams in`() {
        val trace = ChatTrace(live())
        val before = Transcript.from(trace.state).items.map { it.key }

        envelopes.forEach { trace.apply(it.action) }
        val after = Transcript.from(trace.state)

        assertEquals(before, after.items.map { it.key }.take(before.size), "existing rows were renumbered or reordered")
        assertTrue(after.items.size > before.size)
        assertEquals(after.items.size, after.items.map { it.key }.toSet().size)
        val running = trace.state.activeTurn!!.responseParts
        val lastIsReasoning = Transcript.from(trace.state).items.last() is TranscriptItem.Reasoning
        assertEquals(if (lastIsReasoning) 1 else 0, after.items.filterIsInstance<TranscriptItem.Reasoning>().count { it.live }, "a reasoning row is live only when it ends the running turn (${running.size} parts)")
    }
}
