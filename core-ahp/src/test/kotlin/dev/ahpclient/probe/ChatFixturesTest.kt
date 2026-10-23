package dev.ahpclient.probe

import dev.ahpclient.state.fixture
import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ActionEnvelope
import com.microsoft.agenthostprotocol.generated.ChatState
import com.microsoft.agenthostprotocol.generated.ResponsePart
import com.microsoft.agenthostprotocol.generated.SnapshotState
import com.microsoft.agenthostprotocol.generated.SubscribeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Step 2.1: real chat-channel traffic through the pinned library. The fixtures are built by `tools/redact-chat.mjs`
 * from a live capture (2026-10-22): real shapes, placeholder text. They hold a snapshot (a few real turns chosen to cover
 * every part kind and tool-result content type seen, plus the active turn) and the 67 chat actions that streamed in
 * after it. `live/README.md` lists what the capture did not contain.
 */
class ChatFixturesTest {
    private val subscribed = Ahp.json.decodeFromString(SubscribeResult.serializer(), fixture("live/chat-subscribe-result.json"))
    private val envelopes = Ahp.json.decodeFromString(ListSerializer(ActionEnvelope.serializer()), fixture("live/chat-actions.json"))

    private fun chat(): ChatState = (subscribed.snapshot?.state as SnapshotState.Chat).value

    private fun replay(trace: ChatTrace, skip: (ActionEnvelope) -> Boolean = { false }) = envelopes.filterNot(skip).forEach { trace.apply(it.action) }

    private fun ResponsePart.json(): JsonObject = Ahp.json.encodeToJsonElement(ResponsePart.serializer(), this).jsonObject

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun toolCalls(state: ChatState): List<JsonObject> =
        (state.turns.flatMap { it.responseParts } + state.activeTurn?.responseParts.orEmpty()).map { it.json() }.filter { it.text("kind") == "toolCall" }.map { it.getValue("toolCall").jsonObject }

    @Test
    fun `the snapshot decodes as chat state with the real turn shapes`() {
        val state = chat()

        assertEquals(7, state.turns.size)
        assertNotNull(state.activeTurn)
        assertEquals(0, state.activeTurn!!.responseParts.size)
        assertEquals(mapOf("markdown" to 11, "reasoning" to 7, "systemNotification" to 2, "toolCall" to 12), ChatTrace(state).partKinds())
    }

    @Test
    fun `every tool-result content type seen live decodes and survives a round trip`() {
        val types = toolCalls(chat()).flatMap { it["content"]?.jsonArray.orEmpty() }.mapNotNull { (it as JsonObject).text("type") }.toSet()

        assertEquals(setOf("text", "terminal", "fileEdit", "embeddedResource", "subagent"), types)
    }

    @Test
    fun `tool calls in the snapshot carry both completed and failed outcomes`() {
        val calls = toolCalls(chat())

        assertTrue(calls.all { it.text("status") == "completed" }, "statuses: ${calls.map { it.text("status") }.toSet()}")
        assertTrue(calls.any { (it["success"] as? JsonPrimitive)?.content == "true" })
        assertTrue(calls.any { (it["success"] as? JsonPrimitive)?.content == "false" })
        assertTrue(calls.any { it["error"] != null })
    }

    @Test
    fun `every streamed action decodes and reduces with none unknown`() {
        val trace = ChatTrace(chat())

        replay(trace)

        assertTrue(trace.ok, "unknown=${trace.unknownTypes} reduceFailures=${trace.reduceFailures}")
        assertEquals(
            mapOf(
                "chat/reasoning" to 21,
                "chat/responsePart" to 2,
                "chat/toolCallComplete" to 5,
                "chat/toolCallContentChanged" to 4,
                "chat/toolCallDelta" to 13,
                "chat/toolCallReady" to 6,
                "chat/toolCallStart" to 6,
                "chat/usage" to 10,
            ),
            trace.actionTypes.toMap(),
        )
    }

    @Test
    fun `streamed actions build onto the active turn`() {
        val trace = ChatTrace(chat())

        replay(trace)

        val active = assertNotNull(trace.state.activeTurn)
        assertEquals(7, trace.state.turns.size)
        assertEquals(mapOf("markdown" to 11, "reasoning" to 9, "systemNotification" to 2, "toolCall" to 18), trace.partKinds())
        val activeCalls = active.responseParts.map { it.json() }.filter { it.text("kind") == "toolCall" }.map { it.getValue("toolCall").jsonObject }
        assertEquals(6, activeCalls.size)
        assertEquals(5, activeCalls.count { it.text("status") == "completed" })
        val reasoning = active.responseParts.map { it.json() }.filter { it.text("kind") == "reasoning" }
        assertEquals(2, reasoning.size)
        assertTrue(reasoning.all { (it.text("content") ?: "").isNotEmpty() }, "reasoning deltas were not applied")
    }

    @Test
    fun `replaying without the tool call starts changes the outcome, so the replay really exercises the reducer`() {
        val full = ChatTrace(chat()).also { replay(it) }
        val withoutStarts = ChatTrace(chat()).also { trace -> replay(trace) { it.action.let(ChatTrace::typeOf) == "chat/toolCallStart" } }

        assertEquals(18, full.partKinds()["toolCall"])
        assertEquals(12, withoutStarts.partKinds()["toolCall"])
    }

    @Test
    fun `actions are in server order and follow the snapshot`() {
        val fromSeq = assertNotNull(subscribed.snapshot).fromSeq

        assertTrue(envelopes.first().serverSeq > fromSeq)
        assertTrue(envelopes.zipWithNext().all { (a, b) -> b.serverSeq > a.serverSeq })
        assertTrue(envelopes.all { it.channel == assertNotNull(subscribed.snapshot).resource })
    }

    @Test
    fun `the fixtures stay small and carry none of the private blocks the redactor drops`() {
        val dropped = listOf("quotaSnapshots", "contextAttribution", "workingDirectoryKeys", "_vscodeEditAttribution", "branchName", "sandboxState")
        for (name in listOf("live/chat-subscribe-result.json", "live/chat-actions.json")) {
            val text = fixture(name)
            assertTrue(text.length < 100_000, "$name is ${text.length} chars")
            dropped.forEach { assertTrue(!text.contains(it), "$name contains $it") }
        }
    }
}
