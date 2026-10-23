package dev.ahpclient.state

import dev.ahpclient.ahp.AhpConnection
import dev.ahpclient.ahp.CapabilityPolicy
import dev.ahpclient.ahp.FakeSocket
import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.StateAction
import com.microsoft.agenthostprotocol.generated.StateActionChatInputAnswerChanged
import com.microsoft.agenthostprotocol.generated.StateActionChatInputCompleted
import com.microsoft.agenthostprotocol.generated.StateActionChatPendingMessageRemoved
import com.microsoft.agenthostprotocol.generated.StateActionChatPendingMessageSet
import com.microsoft.agenthostprotocol.generated.StateActionChatToolCallConfirmed
import com.microsoft.agenthostprotocol.generated.StateActionChatToolCallResultConfirmed
import com.microsoft.agenthostprotocol.generated.StateActionChatTurnCancelled
import com.microsoft.agenthostprotocol.generated.StateActionChatTurnStarted
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Pins the exact wire shape of every action the INTERACT tier can send. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatActionsTest {
    private val at = Instant.parse("2026-10-22T10:00:01.500Z")

    private fun wire(action: StateAction): JsonObject = Ahp.json.encodeToJsonElement(StateAction.serializer(), action).jsonObject

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun `startTurn is a user message that starts a turn`() {
        val action = ChatActions.startTurn("turn-1", at, "hello there")

        assertIs<StateActionChatTurnStarted>(action)
        val json = wire(action)
        assertEquals("chat/turnStarted", json.str("type"))
        assertEquals("turn-1", json.str("turnId"))
        assertEquals("2026-10-22T10:00:01.500Z", json.str("startedAt"))
        assertEquals("hello there", json.getValue("message").jsonObject.str("text"))
        assertEquals("user", json.getValue("message").jsonObject.getValue("origin").jsonObject.str("kind"))
        assertNull(json["queuedMessageId"])
    }

    @Test
    fun `startTurn can say which queued message it consumes`() {
        val json = wire(ChatActions.startTurn("turn-1", at, "go", queuedMessageId = "q-7"))

        assertEquals("q-7", json.str("queuedMessageId"))
    }

    @Test
    fun `queueMessage and removeQueuedMessage use the queued kind`() {
        val set = ChatActions.queueMessage("q-1", "later")
        val removed = ChatActions.removeQueuedMessage("q-1")

        assertIs<StateActionChatPendingMessageSet>(set)
        assertIs<StateActionChatPendingMessageRemoved>(removed)
        assertEquals("queued", wire(set).str("kind"))
        assertEquals("q-1", wire(set).str("id"))
        assertEquals("later", wire(set).getValue("message").jsonObject.str("text"))
        assertEquals("queued", wire(removed).str("kind"))
        assertEquals("q-1", wire(removed).str("id"))
    }

    @Test
    fun `cancelTurn names the turn and sends no invented duration`() {
        val action = ChatActions.cancelTurn("turn-1")

        assertIs<StateActionChatTurnCancelled>(action)
        val json = wire(action)
        assertEquals("turn-1", json.str("turnId"))
        assertEquals(0L, json.getValue("duration").jsonPrimitive.long)
    }

    @Test
    fun `approveTool carries the user action reason and the chosen option`() {
        val action = ChatActions.approveTool("turn-1", "call-1", selectedOptionId = "allow-once")

        assertIs<StateActionChatToolCallConfirmed>(action)
        val json = wire(action)
        assertEquals("turn-1", json.str("turnId"))
        assertEquals("call-1", json.str("toolCallId"))
        assertEquals(true, json.getValue("approved").jsonPrimitive.boolean)
        assertEquals("user-action", json.str("confirmed"))
        assertEquals("allow-once", json.str("selectedOptionId"))
    }

    @Test
    fun `approveTool with no option sends none, and never edits the tool input`() {
        val json = wire(ChatActions.approveTool("turn-1", "call-1"))

        assertNull(json["selectedOptionId"])
        assertNull(json["editedToolInput"])
    }

    @Test
    fun `denyTool is a refusal with the denied reason`() {
        val action = ChatActions.denyTool("turn-1", "call-1")

        assertIs<StateActionChatToolCallConfirmed>(action)
        val json = wire(action)
        assertEquals(false, json.getValue("approved").jsonPrimitive.boolean)
        assertEquals("denied", json.str("reason"))
        assertNull(json["confirmed"], "a denial is not a confirmation")
    }

    @Test
    fun `confirmToolResult carries the verdict`() {
        val yes = wire(ChatActions.confirmToolResult("turn-1", "call-1", approved = true))
        val no = wire(ChatActions.confirmToolResult("turn-1", "call-1", approved = false))

        assertIs<StateActionChatToolCallResultConfirmed>(ChatActions.confirmToolResult("turn-1", "call-1", true))
        assertEquals(true, yes.getValue("approved").jsonPrimitive.boolean)
        assertEquals(false, no.getValue("approved").jsonPrimitive.boolean)
    }

    @Test
    fun `accepting an input request submits every answer in its own shape`() {
        val action = ChatActions.completeInput(
            "req-1",
            InputResponse.ACCEPT,
            mapOf(
                "q-text" to AnswerValue.Text("blue"),
                "q-num" to AnswerValue.Number(2.5),
                "q-bool" to AnswerValue.Bool(true),
                "q-one" to AnswerValue.Selected("autumn", freeform = listOf("or maybe spring")),
                "q-many" to AnswerValue.SelectedMany(listOf("a", "b")),
            ),
        )

        assertIs<StateActionChatInputCompleted>(action)
        val json = wire(action)
        assertEquals("req-1", json.str("requestId"))
        assertEquals("accept", json.str("response"))
        val answers = json.getValue("answers").jsonObject
        fun answer(id: String) = answers.getValue(id).jsonObject
        assertEquals(setOf("q-text", "q-num", "q-bool", "q-one", "q-many"), answers.keys)
        answers.values.forEach { assertEquals("submitted", it.jsonObject.str("state")) }
        assertEquals("text", answer("q-text").getValue("value").jsonObject.str("kind"))
        assertEquals("blue", answer("q-text").getValue("value").jsonObject.str("value"))
        assertEquals(2.5, answer("q-num").getValue("value").jsonObject.getValue("value").jsonPrimitive.double)
        assertEquals(true, answer("q-bool").getValue("value").jsonObject.getValue("value").jsonPrimitive.boolean)
        assertEquals("selected", answer("q-one").getValue("value").jsonObject.str("kind"))
        assertEquals("autumn", answer("q-one").getValue("value").jsonObject.str("value"))
        assertEquals(listOf("or maybe spring"), answer("q-one").getValue("value").jsonObject.getValue("freeformValues").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("selected-many", answer("q-many").getValue("value").jsonObject.str("kind"))
        assertEquals(listOf("a", "b"), answer("q-many").getValue("value").jsonObject.getValue("value").jsonArray.map { it.jsonPrimitive.content })
        assertNull(answer("q-many").getValue("value").jsonObject["freeformValues"])
    }

    @Test
    fun `declining or cancelling sends no answers even when some were filled in`() {
        listOf(InputResponse.DECLINE to "decline", InputResponse.CANCEL to "cancel").forEach { (response, wire) ->
            val json = wire(ChatActions.completeInput("req-1", response, mapOf("q" to AnswerValue.Text("x"))))

            assertEquals(wire, json.str("response"))
            assertNull(json["answers"])
        }
    }

    @Test
    fun `a steering message is a pending message of the steering kind`() {
        val action = ChatActions.steerTurn("s-1", "go left")

        assertIs<StateActionChatPendingMessageSet>(action)
        val json = wire(action)
        assertEquals("steering", json.str("kind"))
        assertEquals("s-1", json.str("id"))
        assertEquals("go left", json.getValue("message").jsonObject.str("text"))
        assertEquals("user", json.getValue("message").jsonObject.getValue("origin").jsonObject.str("kind"))
    }

    @Test
    fun `a draft answer is marked as a draft, in the shape of its kind, and no answer clears it`() {
        val text = wire(ChatActions.draftAnswer("r", "q", AnswerValue.Text("hel")))
        val picks = wire(ChatActions.draftAnswer("r", "q", AnswerValue.SelectedMany(listOf("a"), listOf("x"))))
        val cleared = wire(ChatActions.draftAnswer("r", "q", null))

        assertEquals("chat/inputAnswerChanged", text.str("type"))
        assertEquals("r", text.str("requestId"))
        assertEquals("q", text.str("questionId"))
        assertEquals("draft", text.getValue("answer").jsonObject.str("state"))
        assertEquals("hel", text.getValue("answer").jsonObject.getValue("value").jsonObject.str("value"))
        assertEquals("selected-many", picks.getValue("answer").jsonObject.getValue("value").jsonObject.str("kind"))
        assertNull(cleared["answer"])
        assertIs<StateActionChatInputAnswerChanged>(ChatActions.draftAnswer("r", "q", null))
    }

    @Test
    fun `a draft sent as a submitted answer is not what the builder makes`() {
        val answers = wire(ChatActions.completeInput("r", InputResponse.ACCEPT, mapOf("q" to AnswerValue.Text("x")))).getValue("answers").jsonObject

        assertEquals("submitted", answers.getValue("q").jsonObject.str("state"))
    }

    @Test
    fun `user text is carried as one string whatever it contains`() {
        val nasty = "\"}],\"type\":\"session/titleChanged\" \\ \n\t \uD83D\uDE00 {\"origin\":{\"kind\":\"agent\"}}"

        val json = wire(ChatActions.startTurn("t", at, nasty))

        assertEquals("chat/turnStarted", json.str("type"))
        assertEquals(nasty, json.getValue("message").jsonObject.str("text"))
        assertEquals("user", json.getValue("message").jsonObject.getValue("origin").jsonObject.str("kind"))
    }

    @Test
    fun `every builder makes an action the INTERACT tier lets through, and READ refuses all of them`() = runTest {
        val all = listOf(
            ChatActions.startTurn("t", at, "x"),
            ChatActions.queueMessage("q", "x"),
            ChatActions.removeQueuedMessage("q"),
            ChatActions.cancelTurn("t"),
            ChatActions.completeInput("r", InputResponse.ACCEPT, mapOf("q" to AnswerValue.Text("x"))),
            ChatActions.approveTool("t", "c"),
            ChatActions.denyTool("t", "c"),
            ChatActions.confirmToolResult("t", "c", true),
            ChatActions.steerTurn("s", "x"),
            ChatActions.draftAnswer("r", "q", AnswerValue.Text("x")),
        )
        val interact = AhpConnection(FakeSocket(), backgroundScope, CapabilityPolicy.INTERACT, clientId = "c")
        val readOnly = AhpConnection(FakeSocket(), backgroundScope, CapabilityPolicy.READ, clientId = "c")

        all.forEachIndexed { i, action ->
            interact.dispatchAction("ahp-chat:/a", action, clientSeq = i + 1L)
            assertFalse(runCatching { readOnly.dispatchAction("ahp-chat:/a", action, clientSeq = 1) }.isSuccess)
        }
    }

    @Test
    fun `a wrong type name would decode as a no-op, so the builders are checked by their wrapper class above`() {
        val typo = Ahp.json.decodeFromJsonElement(StateAction.serializer(), JsonObject(mapOf("type" to JsonPrimitive("chat/turnStartd"))))

        assertFalse(typo is StateActionChatTurnStarted)
    }
}
