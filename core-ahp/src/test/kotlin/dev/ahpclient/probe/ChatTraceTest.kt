package dev.ahpclient.probe

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ChatState
import com.microsoft.agenthostprotocol.generated.StateAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatTraceTest {
    private fun chat(): ChatState = Ahp.json.decodeFromString(
        ChatState.serializer(),
        """{"resource":"ahp-chat://default/x","title":"t","status":1,"modifiedAt":"2026-10-22T10:00:00.000Z","turns":[]}""",
    )

    private fun action(json: String): StateAction = Ahp.json.decodeFromString(StateAction.serializer(), json)

    private val started = """{"type":"chat/turnStarted","turnId":"t1","startedAt":"2026-10-22T10:00:01.000Z","message":{"text":"hello","origin":{"kind":"user"}}}"""
    private val markdown = """{"type":"chat/responsePart","turnId":"t1","part":{"kind":"markdown","id":"p1","content":"Hi"}}"""
    private val delta = """{"type":"chat/delta","turnId":"t1","partId":"p1","content":" there"}"""
    private val complete = """{"type":"chat/turnComplete","turnId":"t1","duration":1200}"""

    @Test
    fun `a turn streams in and completes with the text assembled`() {
        val trace = ChatTrace(chat())

        listOf(started, markdown, delta, delta, complete).forEach { trace.apply(action(it)) }

        assertEquals(mapOf("chat/delta" to 2, "chat/responsePart" to 1, "chat/turnComplete" to 1, "chat/turnStarted" to 1), trace.actionTypes.toMap())
        assertTrue(trace.ok)
        assertEquals(1, trace.state.turns.size)
        assertEquals(null, trace.state.activeTurn)
        assertEquals(mapOf("markdown" to 1), trace.partKinds())
        assertEquals("Hi there there", (trace.state.turns.single().responseParts.single() as com.microsoft.agenthostprotocol.generated.ResponsePartMarkdown).value.content)
    }

    @Test
    fun `part kinds count the running turn as well as the finished ones`() {
        val trace = ChatTrace(chat())

        listOf(started, markdown).forEach { trace.apply(action(it)) }

        assertEquals(mapOf("markdown" to 1), trace.partKinds())
        assertEquals(0, trace.state.turns.size)
    }

    @Test
    fun `an action the library does not know is reported by its type, and fails the trace`() {
        val trace = ChatTrace(chat())

        trace.apply(action("""{"type":"chat/somethingFromTheFuture","x":1}"""))

        assertEquals(1, trace.unknownActions)
        assertEquals(setOf("chat/somethingFromTheFuture"), trace.unknownTypes)
        assertFalse(trace.ok)
    }

    @Test
    fun `a delta for a turn the reducer has not seen does not crash the trace or invent a turn`() {
        val trace = ChatTrace(chat())

        trace.apply(action("""{"type":"chat/delta","turnId":"no-such-turn","partId":"p","content":"x"}"""))

        assertEquals(1, trace.actionTypes["chat/delta"])
        assertEquals(0, trace.state.turns.size)
        assertEquals(null, trace.state.activeTurn)
    }

    @Test
    fun `the report holds names and counts only, never content`() {
        val trace = ChatTrace(chat())
        listOf(started, markdown).forEach { trace.apply(action(it)) }

        val report = trace.actionTypes.toString() + trace.partKinds() + trace.unknownTypes

        assertFalse(report.contains("hello"))
        assertFalse(report.contains("Hi"))
    }
}
