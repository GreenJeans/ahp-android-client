package dev.ahpclient.state

import dev.ahpclient.ahp.CapabilityDeniedException
import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ChatState
import com.microsoft.agenthostprotocol.generated.StateAction
import java.io.IOException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalCoroutinesApi::class)
class ChatInteractorTest {
    private val uri = "ahp-chat:/c"
    private val at = Instant.parse("2026-10-22T12:00:00Z")

    private fun chat(active: String? = null, interactivity: String? = null): ChatState = Ahp.json.decodeFromString(
        ChatState.serializer(),
        """{"resource":"$uri","title":"Chat","status":8,"modifiedAt":"2026-10-22T10:00:00.000Z","turns":[]
            ${interactivity?.let { ""","interactivity":"$it"""" }.orEmpty()}
            ${active?.let { ""","activeTurn":$it""" }.orEmpty()}}""",
    )

    private fun activeTurn(parts: String = "") =
        """{"id":"t2","message":{"text":"now","origin":{"kind":"user"}},"responseParts":[$parts],"startedAt":"2026-10-22T10:05:00.000Z"}"""

    private fun pendingCall(id: String = "c1", options: String = "[]", intention: String? = null) =
        """{"kind":"toolCall","toolCall":{"status":"pending-confirmation","toolCallId":"$id","toolName":"run_in_terminal","displayName":"Run in Terminal",
            "invocationMessage":"Run it","toolInput":"{}","confirmationTitle":"Run?","options":$options${intention?.let { ""","intention":"$it"""" }.orEmpty()}}}"""

    private val twoOptions =
        """[{"id":"o-allow","label":"Allow once","kind":"approve"},{"id":"o-session","label":"Allow in this session","kind":"approve","group":1},{"id":"o-no","label":"Not now","kind":"deny"}]"""

    private class Rig(var chat: ChatState?, uri: String, at: Instant) {
        var canInteract = true
        var gateAnswer = true
        var gateThrows = false
        var outcome: DispatchOutcome = DispatchOutcome.Accepted
        var dispatchFails: Exception? = null
        var onGate: () -> Unit = {}
        val sent = mutableListOf<Pair<String, StateAction>>()
        val prompts = mutableListOf<ApprovalRequest>()
        val order = mutableListOf<String>()
        private var ids = 0

        val interactor = ChatInteractor(
            chatUri = uri,
            chat = { chat },
            canInteract = { canInteract },
            dispatch = { channel, action ->
                order += "dispatch"
                dispatchFails?.let { throw it }
                sent += channel to action
                outcome
            },
            gate = ApprovalGate {
                order += "gate"
                prompts += it
                onGate()
                if (gateThrows) throw IllegalStateException("biometric hardware error")
                gateAnswer
            },
            newId = { "id-${++ids}" },
            now = { at },
        )

        fun types() = sent.map { (_, action) -> Ahp.json.encodeToJsonElement(StateAction.serializer(), action).jsonObject["type"]!!.jsonPrimitive.content }

        fun wire(index: Int = 0) = Ahp.json.encodeToJsonElement(StateAction.serializer(), sent[index].second).jsonObject
    }

    private fun rig(chat: ChatState? = chat()) = Rig(chat, uri, at)

    @Test
    fun `a message to an idle chat starts a turn`() = runTest {
        val rig = rig()

        val result = rig.interactor.send("hello")

        assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), result)
        assertEquals(listOf("chat/turnStarted"), rig.types())
        assertEquals(uri, rig.sent.single().first)
        val json = rig.wire()
        assertEquals("id-1", json["turnId"]!!.jsonPrimitive.content)
        assertEquals("2026-10-22T12:00:00Z", json["startedAt"]!!.jsonPrimitive.content)
        assertEquals("hello", json["message"]!!.jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a message to a chat that is already working is queued, not a second turn`() = runTest {
        val rig = rig(chat(active = activeTurn()))

        rig.interactor.send("and then this")

        assertEquals(listOf("chat/pendingMessageSet"), rig.types())
        assertEquals("queued", rig.wire()["kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a blank message is not sent`() = runTest {
        val rig = rig()

        listOf("", "   ", "\n\t ").forEach { assertEquals(InteractResult.Blank, rig.interactor.send(it)) }

        assertTrue(rig.sent.isEmpty())
    }

    @Test
    fun `the text is sent exactly as typed`() = runTest {
        val rig = rig()

        rig.interactor.send("  indented\n    code  ")

        assertEquals("  indented\n    code  ", rig.wire()["message"]!!.jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nothing can be done when the connection cannot interact, the chat is unknown, or the host shows it read only or hidden`() = runTest {
        val situations = listOf(
            rig().apply { canInteract = false },
            rig(null),
            rig(chat(interactivity = "read-only")),
            rig(chat(interactivity = "hidden")),
        )
        situations.forEach { rig ->
            assertEquals(InteractResult.NotAllowed, rig.interactor.send("x"))
            assertEquals(InteractResult.NotAllowed, rig.interactor.cancel())
            assertEquals(InteractResult.NotAllowed, rig.interactor.answer("r", emptyMap()))
            assertEquals(InteractResult.NotAllowed, rig.interactor.declineInput("r"))
            assertEquals(InteractResult.NotAllowed, rig.interactor.dismissInput("r"))
            assertEquals(InteractResult.NotAllowed, rig.interactor.approve("c1"))
            assertEquals(InteractResult.NotAllowed, rig.interactor.deny("c1"))
            assertEquals(InteractResult.NotAllowed, rig.interactor.steer("x"))
            assertEquals(InteractResult.NotAllowed, rig.interactor.saveDraft("r", "q", AnswerValue.Text("x")))
            assertEquals(InteractResult.NotAllowed, rig.interactor.approveResult("c1"))
            assertEquals(InteractResult.NotAllowed, rig.interactor.denyResult("c1"))
            assertTrue(rig.sent.isEmpty())
            assertTrue(rig.prompts.isEmpty(), "no device check for something that cannot be sent")
        }
    }

    private fun resultCall(id: String = "c1") =
        """{"kind":"toolCall","toolCall":{"status":"pending-result-confirmation","toolCallId":"$id","toolName":"run_in_terminal","displayName":"Run in Terminal",
            "invocationMessage":"Run it","toolInput":"{}","pastTenseMessage":"Ran it","confirmed":"not-needed","success":true,"intention":"Run the tests"}}"""

    private fun question(id: String = "req-1", response: String? = null) =
        """{"kind":"inputRequest","request":{"id":"$id","message":"Which?","questions":[{"kind":"text","id":"a","title":"A","message":"A"}]}${response?.let { ""","response":"$it"""" }.orEmpty()}}"""

    @Test
    fun `a steering message needs a running turn and is sent as steering`() = runTest {
        val working = rig(chat(active = activeTurn()))
        val idle = rig()

        assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), working.interactor.steer("go left"))
        assertEquals(InteractResult.NoActiveTurn, idle.interactor.steer("go left"))
        assertEquals(InteractResult.Blank, working.interactor.steer("  "))

        assertEquals(listOf("chat/pendingMessageSet"), working.types())
        assertEquals("steering", working.wire()["kind"]!!.jsonPrimitive.content)
        assertTrue(idle.sent.isEmpty())
    }

    @Test
    fun `a draft is sent for a question that is waiting, with no device check, and not for one that is not`() = runTest {
        val rig = rig(chat(active = activeTurn(question("req-1") + "," + question("req-2", response = "accept"))))

        assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), rig.interactor.saveDraft("req-1", "a", AnswerValue.Text("hel")))
        assertEquals(InteractResult.NoSuchRequest, rig.interactor.saveDraft("req-2", "a", AnswerValue.Text("x")), "already answered")
        assertEquals(InteractResult.NoSuchRequest, rig.interactor.saveDraft("nope", "a", AnswerValue.Text("x")))
        assertEquals(InteractResult.NoSuchRequest, rig(chat()).interactor.saveDraft("req-1", "a", null), "no running turn")

        assertEquals(listOf("chat/inputAnswerChanged"), rig.types())
        assertEquals("req-1", rig.wire()["requestId"]!!.jsonPrimitive.content)
        assertTrue(rig.prompts.isEmpty())
    }

    @Test
    fun `approving a result asks the device first, says it is about a result, and sends the verdict`() = runTest {
        val rig = rig(chat(active = activeTurn(resultCall())))

        val result = rig.interactor.approveResult("c1")

        assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), result)
        assertEquals(listOf("gate", "dispatch"), rig.order)
        assertEquals(ApprovalRequest("Run in Terminal", "Run the tests", forResult = true), rig.prompts.single())
        assertEquals(listOf("chat/toolCallResultConfirmed"), rig.types())
        assertEquals("true", rig.wire()["approved"]!!.jsonPrimitive.content)
        assertEquals("t2", rig.wire()["turnId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a result approval that the device refuses, or for a call not waiting on its result, sends nothing`() = runTest {
        val refused = rig(chat(active = activeTurn(resultCall()))).apply { gateAnswer = false }
        val notResult = rig(chat(active = activeTurn(pendingCall())))

        assertEquals(InteractResult.NotConfirmed, refused.interactor.approveResult("c1"))
        assertEquals(InteractResult.NoSuchRequest, notResult.interactor.approveResult("c1"), "a call waiting for approval is not a result")
        assertEquals(InteractResult.NoSuchRequest, notResult.interactor.denyResult("c1"))

        assertTrue(refused.sent.isEmpty())
        assertTrue(notResult.sent.isEmpty())
        assertTrue(notResult.prompts.isEmpty())
    }

    @Test
    fun `denying a result needs no device check`() = runTest {
        val rig = rig(chat(active = activeTurn(resultCall()))).apply { gateAnswer = false }

        assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), rig.interactor.denyResult("c1"))

        assertTrue(rig.prompts.isEmpty())
        assertEquals("false", rig.wire()["approved"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a call waiting on its result is offered plain approve and deny, marked as a result`() {
        val waiting = PendingApproval.waitingFor(
            (activeResult().activeTurn!!.responseParts.single() as com.microsoft.agenthostprotocol.generated.ResponsePartToolCall).value.toolCall,
        )!!

        assertTrue(waiting.forResult)
        assertEquals("c1", waiting.toolCallId)
        assertEquals(listOf("Approve", "Deny"), waiting.choices.map { it.label })
    }

    private fun activeResult(): ChatState = chat(active = activeTurn(resultCall()))

    @Test
    fun `a chat that does not say how interactive it is counts as full`() = runTest {
        assertTrue(ChatInteractor.available(chat(), canInteract = true))
        assertTrue(ChatInteractor.available(chat(interactivity = "full"), canInteract = true))
        assertFalse(ChatInteractor.available(chat(), canInteract = false))
        assertFalse(ChatInteractor.available(null, canInteract = true))
    }

    @Test
    fun `cancel stops the running turn, and says so when there is none`() = runTest {
        val working = rig(chat(active = activeTurn()))
        val idle = rig()

        working.interactor.cancel()
        assertEquals(InteractResult.NoActiveTurn, idle.interactor.cancel())

        assertEquals(listOf("chat/turnCancelled"), working.types())
        assertEquals("t2", working.wire()["turnId"]!!.jsonPrimitive.content)
        assertTrue(idle.sent.isEmpty())
    }

    @Test
    fun `answering, declining and dismissing a question send the three responses`() = runTest {
        val rig = rig(chat(active = activeTurn()))

        rig.interactor.answer("req-1", mapOf("q" to AnswerValue.Text("blue")))
        rig.interactor.declineInput("req-1")
        rig.interactor.dismissInput("req-1")

        assertEquals(listOf("accept", "decline", "cancel"), rig.sent.indices.map { rig.wire(it)["response"]!!.jsonPrimitive.content })
        assertTrue(rig.wire(0)["answers"]!!.jsonObject.containsKey("q"))
        assertNull(rig.wire(1)["answers"])
    }

    @Test
    fun `approving asks the device first, shows what is being allowed, and only then sends`() = runTest {
        val rig = rig(chat(active = activeTurn(pendingCall(intention = "Run the unit tests"))))

        val result = rig.interactor.approve("c1")

        assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), result)
        assertEquals(listOf("gate", "dispatch"), rig.order)
        assertEquals(ApprovalRequest("Run in Terminal", "Run the unit tests"), rig.prompts.single())
        assertEquals(listOf("chat/toolCallConfirmed"), rig.types())
        val json = rig.wire()
        assertEquals("t2", json["turnId"]!!.jsonPrimitive.content)
        assertEquals("c1", json["toolCallId"]!!.jsonPrimitive.content)
        assertEquals("true", json["approved"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a device check that is declined, fails or throws sends nothing`() = runTest {
        val declined = rig(chat(active = activeTurn(pendingCall()))).apply { gateAnswer = false }
        val broken = rig(chat(active = activeTurn(pendingCall()))).apply { gateThrows = true }

        assertEquals(InteractResult.NotConfirmed, declined.interactor.approve("c1"))
        assertEquals(InteractResult.NotConfirmed, broken.interactor.approve("c1"))

        assertTrue(declined.sent.isEmpty())
        assertTrue(broken.sent.isEmpty())
    }

    @Test
    fun `a call that is not waiting is never approved and the device is not even asked`() = runTest {
        val rig = rig(chat(active = activeTurn(pendingCall("c1"))))

        assertEquals(InteractResult.NoSuchRequest, rig.interactor.approve("c-other"))
        assertEquals(InteractResult.NoSuchRequest, rig(chat()).interactor.approve("c1"))

        assertTrue(rig.sent.isEmpty())
        assertTrue(rig.prompts.isEmpty())
    }

    @Test
    fun `a call that finishes while the person is deciding is not approved blind`() = runTest {
        val rig = rig(chat(active = activeTurn(pendingCall())))
        rig.onGate = { rig.chat = chat() }

        val result = rig.interactor.approve("c1")

        assertEquals(InteractResult.NoSuchRequest, result)
        assertTrue(rig.sent.isEmpty())
    }

    @Test
    fun `only an approve option the host offered can approve, and only a deny option can deny`() = runTest {
        val rig = rig(chat(active = activeTurn(pendingCall(options = twoOptions))))

        assertEquals(InteractResult.NoSuchRequest, rig.interactor.approve("c1", "o-no"), "a deny option cannot approve")
        assertEquals(InteractResult.NoSuchRequest, rig.interactor.approve("c1", "made-up"))
        assertEquals(InteractResult.NoSuchRequest, rig.interactor.deny("c1", "o-allow"), "an approve option cannot deny")
        assertTrue(rig.sent.isEmpty())
        assertTrue(rig.prompts.isEmpty())

        rig.interactor.approve("c1", "o-session")
        rig.interactor.deny("c1", "o-no")

        assertEquals("o-session", rig.wire(0)["selectedOptionId"]!!.jsonPrimitive.content)
        assertEquals("o-no", rig.wire(1)["selectedOptionId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `denying never needs the device check`() = runTest {
        val rig = rig(chat(active = activeTurn(pendingCall()))).apply { gateAnswer = false }

        val result = rig.interactor.deny("c1")

        assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), result)
        assertTrue(rig.prompts.isEmpty())
        assertEquals("false", rig.wire()["approved"]!!.jsonPrimitive.content)
    }

    @Test
    fun `only a call that is waiting has buttons`() {
        val waiting = (activeTurn(pendingCall()).let { chat(active = it) }.activeTurn!!.responseParts.single() as com.microsoft.agenthostprotocol.generated.ResponsePartToolCall).value.toolCall
        val running = Ahp.json.decodeFromString(
            com.microsoft.agenthostprotocol.generated.ToolCallState.serializer(),
            """{"status":"running","toolCallId":"c1","toolName":"t","displayName":"T","invocationMessage":"Go","toolInput":"{}","confirmed":"user-action"}""",
        )

        assertEquals(listOf("Approve", "Deny"), PendingApproval.waitingFor(waiting)!!.choices.map { it.label })
        assertEquals("c1", PendingApproval.waitingFor(waiting)!!.toolCallId)
        assertNull(PendingApproval.waitingFor(running))
        assertNull(PendingApproval.waitingFor(null))
    }

    @Test
    fun `the choices are the host's own options, or a plain approve and deny`() {
        val plain = PendingApproval.find(chat(active = activeTurn(pendingCall())), "c1")!!
        val offered = PendingApproval.find(chat(active = activeTurn(pendingCall(options = twoOptions))), "c1")!!

        assertEquals(listOf(ApprovalChoice(null, "Approve", true), ApprovalChoice(null, "Deny", false)), plain.choices)
        assertEquals(
            listOf(
                ApprovalChoice("o-allow", "Allow once", true),
                ApprovalChoice("o-session", "Allow in this session", true),
                ApprovalChoice("o-no", "Not now", false),
            ),
            offered.choices,
        )
    }

    @Test
    fun `a failure before anything left the phone is NotSent, a refusal by the host is Sent`() = runTest {
        val offline = rig().apply { dispatchFails = IOException("Not connected to the host") }
        val denied = rig().apply { dispatchFails = CapabilityDeniedException("dispatchAction", "chat/turnStarted") }
        val rejected = rig().apply { outcome = DispatchOutcome.Rejected("a turn is already running") }
        val silent = rig().apply { outcome = DispatchOutcome.NoReply }

        assertIs<IOException>(assertIs<InteractResult.NotSent>(offline.interactor.send("x")).cause)
        assertIs<CapabilityDeniedException>(assertIs<InteractResult.NotSent>(denied.interactor.send("x")).cause)
        assertEquals(InteractResult.Sent(DispatchOutcome.Rejected("a turn is already running")), rejected.interactor.send("x"))
        assertEquals(InteractResult.Sent(DispatchOutcome.NoReply), silent.interactor.send("x"))
    }
}
