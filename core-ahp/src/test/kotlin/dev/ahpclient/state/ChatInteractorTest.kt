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
        var models: List<ModelOption> = emptyList()
        var agents: List<AgentChoice> = emptyList()
        var config: ChatConfigView = ChatConfigView.NONE
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
            models = { models },
            agents = { agents },
            config = { config },
        )

        fun types() = sent.map { (_, action) -> Ahp.json.encodeToJsonElement(StateAction.serializer(), action).jsonObject["type"]!!.jsonPrimitive.content }

        fun wire(index: Int = 0) = Ahp.json.encodeToJsonElement(StateAction.serializer(), sent[index].second).jsonObject
    }

    private fun rig(chat: ChatState? = chat()) = Rig(chat, uri, at)

    // ---- mode and approvals ------------------------------------------------------------------------------------

    private fun view(
        mode: String = "interactive",
        approvals: String = "default",
        mutable: Set<String> = ALL_CHAT_MUTABLE,
        available: List<String>? = null,
    ): ChatConfigView {
        val props = properties(
            "autoApprove" to AUTO_APPROVE_PROPERTY, "mode" to MODE_PROPERTY,
            "availableApprovalModes" to AVAILABLE_APPROVAL_PROPERTY,
        )
        val values = """"autoApprove":"$approvals","mode":"$mode"${available?.let { ""","availableApprovalModes":[${it.joinToString(",") { v -> "\"$v\"" }}]""" }.orEmpty()}"""
        val chats = """[{"resource":"$uri","title":"Main","status":1,"modifiedAt":"2026-10-22T10:00:00.000Z"}]"""
        return ChatConfig.view(configSession(props, values, chats), uri, mutable, emptyMap())
    }

    private fun Rig.configOf(index: Int = 0) = wire(index)["config"]!!.jsonObject

    @Test
    fun `taking freedom away is sent with no device check`() = runTest {
        val rig = rig().apply { config = view(mode = "autopilot", approvals = "autoApprove") }

        assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), rig.interactor.setConfig("autoApprove", "default"))
        assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), rig.interactor.setConfig("mode", "plan"))

        assertEquals(listOf("dispatch", "dispatch"), rig.order)
        assertEquals(listOf("chat/configChanged", "chat/configChanged"), rig.types())
        assertEquals("""{"autoApprove":"default"}""", rig.configOf(0).toString())
        assertEquals("""{"mode":"plan"}""", rig.configOf(1).toString())
        assertEquals(uri, rig.sent.first().first)
    }

    @Test
    fun `giving the agent more freedom goes through the device check, in this app's words, before anything is sent`() = runTest {
        val rig = rig().apply { config = view() }

        val result = rig.interactor.setConfig("autoApprove", "autoApprove")

        assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), result)
        assertEquals(listOf("gate", "dispatch"), rig.order)
        val prompt = rig.prompts.single()
        assertEquals(ChatConfigPrompt.TITLE, prompt.title)
        assertEquals("Allow all: tool calls run without asking", prompt.toolName)
        assertEquals(ChatConfigPrompt.SUMMARY, prompt.summary)
        assertEquals("""{"autoApprove":"autoApprove"}""", rig.configOf().toString())
    }

    @Test
    fun `assisted and autopilot are loosening too, and plan is not`() = runTest {
        val rig = rig().apply { config = view() }

        rig.interactor.setConfig("autoApprove", "assisted")
        rig.interactor.setConfig("mode", "autopilot")
        rig.interactor.setConfig("mode", "plan")

        assertEquals(listOf("gate", "dispatch", "gate", "dispatch", "dispatch"), rig.order)
        assertEquals(
            listOf("Assisted: the host decides which tool calls need you", "Autopilot: the agent works on its own"),
            rig.prompts.map { it.toolName },
        )
    }

    @Test
    fun `a device check that is not passed sends nothing`() = runTest {
        val rig = rig().apply { config = view(); gateAnswer = false }

        assertEquals(InteractResult.NotConfirmed, rig.interactor.setConfig("autoApprove", "autoApprove"))

        assertTrue(rig.sent.isEmpty())
    }

    @Test
    fun `a device check that errors sends nothing`() = runTest {
        val rig = rig().apply { config = view(); gateThrows = true }

        assertEquals(InteractResult.NotConfirmed, rig.interactor.setConfig("mode", "autopilot"))

        assertTrue(rig.sent.isEmpty())
    }

    @Test
    fun `a setting the host withdrew while the person was at the device check is not sent`() = runTest {
        val rig = rig().apply {
            config = view()
            onGate = { config = view(available = listOf("default", "assisted")) }
        }

        assertEquals(InteractResult.InvalidConfig, rig.interactor.setConfig("autoApprove", "autoApprove"))

        assertTrue(rig.sent.isEmpty())
    }

    @Test
    fun `a chat that stopped being interactive while the person was at the device check is not sent to`() = runTest {
        val rig = rig().apply {
            config = view()
            onGate = { canInteract = false }
        }

        assertEquals(InteractResult.NotAllowed, rig.interactor.setConfig("autoApprove", "autoApprove"))

        assertTrue(rig.sent.isEmpty())
    }

    @Test
    fun `the same value is nothing to send`() = runTest {
        val rig = rig().apply { config = view(approvals = "assisted") }

        assertEquals(InteractResult.Blank, rig.interactor.setConfig("autoApprove", "assisted"))

        assertTrue(rig.sent.isEmpty())
        assertTrue(rig.prompts.isEmpty())
    }

    @Test
    fun `a setting the host does not offer, or does not let this chat change, is never sent`() = runTest {
        val rig = rig().apply { config = view(mutable = setOf("mode")) }

        assertEquals(InteractResult.InvalidConfig, rig.interactor.setConfig("autoApprove", "default"), "not chat-mutable")
        assertEquals(InteractResult.InvalidConfig, rig.interactor.setConfig("mode", "yolo"), "not a value")
        assertEquals(InteractResult.InvalidConfig, rig.interactor.setConfig("permissions", "x"), "not a setting")
        assertEquals(InteractResult.InvalidConfig, rig.interactor.setConfig("sandboxEnabled", "off"), "not a setting")
        assertEquals(InteractResult.InvalidConfig, rig.interactor.setConfig("effectiveApprovalMode", "autoApprove"), "not a setting")

        assertTrue(rig.sent.isEmpty())
        assertTrue(rig.prompts.isEmpty())
    }

    @Test
    fun `a level the host withholds is never sent`() = runTest {
        val rig = rig().apply { config = view(available = listOf("default", "assisted")) }

        assertEquals(InteractResult.InvalidConfig, rig.interactor.setConfig("autoApprove", "autoApprove"))
        assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), rig.interactor.setConfig("autoApprove", "assisted"))

        assertEquals(listOf("assisted"), rig.sent.map { (_, a) -> Ahp.json.encodeToJsonElement(StateAction.serializer(), a).jsonObject["config"]!!.jsonObject["autoApprove"]!!.jsonPrimitive.content })
    }

    @Test
    fun `with no settings offered nothing can be set`() = runTest {
        val rig = rig()

        assertEquals(InteractResult.InvalidConfig, rig.interactor.setConfig("mode", "plan"))
        assertTrue(rig.sent.isEmpty())
    }

    @Test
    fun `a chat the person may not act in cannot have its settings changed`() = runTest {
        val readOnly = rig(chat(interactivity = "read-only")).apply { config = view() }
        val offline = rig().apply { config = view(); canInteract = false }
        val gone = rig(null).apply { config = view() }

        assertEquals(InteractResult.NotAllowed, readOnly.interactor.setConfig("mode", "plan"))
        assertEquals(InteractResult.NotAllowed, offline.interactor.setConfig("mode", "plan"))
        assertEquals(InteractResult.NotAllowed, gone.interactor.setConfig("mode", "plan"))
        listOf(readOnly, offline, gone).forEach { assertTrue(it.sent.isEmpty()) }
    }

    @Test
    fun `a refusal by the host and a failed send are reported, not hidden`() = runTest {
        val refused = rig().apply { config = view(); outcome = DispatchOutcome.Rejected("not allowed by policy") }
        val failed = rig().apply { config = view(); dispatchFails = IOException("offline") }

        assertEquals(InteractResult.Sent(DispatchOutcome.Rejected("not allowed by policy")), refused.interactor.setConfig("mode", "plan"))
        assertIs<InteractResult.NotSent>(failed.interactor.setConfig("mode", "plan"))
    }

    @Test
    fun `the view the screen shows is the one the interactor checks against`() = runTest {
        val rig = rig().apply { config = view(mode = "plan") }

        assertEquals("plan", rig.interactor.configView().mode!!.current)
    }

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

    private val sonnet = ModelOption(
        "claude-sonnet-5.5",
        "Claude Sonnet 5.5",
        listOf(ModelSetting("thinking", "Thinking", listOf(ModelSettingValue("low", "Low"), ModelSettingValue("high", "High")))),
    )

    private fun Rig.modelOf(index: Int = 0) = wire(index)["message"]!!.jsonObject["model"]?.toString()

    @Test
    fun `a model the host offers goes out with the message, whether it starts a turn, queues or steers`() = runTest {
        val idle = rig().apply { models = listOf(sonnet) }
        idle.interactor.send("hi", ModelChoice("claude-sonnet-5.5", mapOf("thinking" to "high")))
        assertEquals("""{"id":"claude-sonnet-5.5","config":{"thinking":"high"}}""", idle.modelOf())

        val busy = rig(chat(active = activeTurn())).apply { models = listOf(sonnet) }
        busy.interactor.send("later", ModelChoice("claude-sonnet-5.5"))
        busy.interactor.steer("now", ModelChoice("claude-sonnet-5.5"))
        assertEquals(listOf("queued", "steering"), listOf(busy.wire(0)["kind"], busy.wire(1)["kind"]).map { it!!.jsonPrimitive.content })
        assertEquals("""{"id":"claude-sonnet-5.5"}""", busy.modelOf(0))
        assertEquals("""{"id":"claude-sonnet-5.5"}""", busy.modelOf(1))
    }

    @Test
    fun `a model the host does not offer is never sent, by a new message or a steering one`() = runTest {
        val rig = rig().apply { models = listOf(sonnet) }

        assertEquals(InteractResult.InvalidModel, rig.interactor.send("hi", ModelChoice("gpt-made-up")))
        assertEquals(InteractResult.InvalidModel, rig.interactor.send("hi", ModelChoice("claude-sonnet-5.5", mapOf("thinking" to "extreme"))))
        assertEquals(InteractResult.InvalidModel, rig.interactor.send("hi", ModelChoice("claude-sonnet-5.5", mapOf("unknown" to "x"))))
        assertTrue(rig.sent.isEmpty())

        val busy = rig(chat(active = activeTurn())).apply { models = listOf(sonnet) }
        assertEquals(InteractResult.InvalidModel, busy.interactor.steer("now", ModelChoice("nope")))
        assertTrue(busy.sent.isEmpty())
    }

    // ---- the custom agent ----------------------------------------------------------------------------------------

    private val plan = AgentChoice("file:///a/plan.agent.md", "plan", null)
    private val review = AgentChoice("file:///a/review.agent.md", "review", null)

    private fun chatOnAgent(agent: String?, active: Boolean = false): ChatState = Ahp.json.decodeFromString(
        ChatState.serializer(),
        """{"resource":"$uri","title":"Chat","status":8,"modifiedAt":"2026-10-22T10:00:00.000Z",
            "turns":[{"id":"t1","message":{"text":"x","origin":{"kind":"user"}${agent?.let { ""","agent":{"uri":"$it"}""" }.orEmpty()}},"responseParts":[],"startedAt":"2026-10-22T10:00:00.000Z","state":"complete"}]
            ${if (active) ""","activeTurn":{"id":"t2","message":{"text":"now","origin":{"kind":"user"}${agent?.let { ""","agent":{"uri":"$it"}""" }.orEmpty()}},"responseParts":[],"startedAt":"2026-10-22T10:05:00.000Z"}""" else ""}}""",
    )

    private fun Rig.agentOf(index: Int = 0) = wire(index)["message"]!!.jsonObject["agent"]?.toString()

    @Test
    fun `a message that says nothing about the agent keeps the one the chat is on`() = runTest {
        val rig = rig(chatOnAgent(plan.uri)).apply { agents = listOf(plan, review) }

        rig.interactor.send("go on")

        assertEquals("""{"uri":"file:///a/plan.agent.md"}""", rig.agentOf())
    }

    @Test
    fun `a chat on no agent stays on none, and the agent is kept even when the host no longer lists it`() = runTest {
        val none = rig(chatOnAgent(null)).apply { agents = listOf(plan) }
        none.interactor.send("go on")
        assertNull(none.agentOf())

        val unlisted = rig(chatOnAgent("file:///gone.agent.md")).apply { agents = listOf(plan) }
        unlisted.interactor.send("go on")
        assertEquals("""{"uri":"file:///gone.agent.md"}""", unlisted.agentOf(), "what the chat is on is sent back as it was")
    }

    @Test
    fun `a picked agent goes out with the message, whether it starts a turn, queues or steers`() = runTest {
        val idle = rig(chatOnAgent(null)).apply { agents = listOf(plan, review) }
        idle.interactor.send("hi", agent = AgentPick.Use(review.uri))
        assertEquals("""{"uri":"file:///a/review.agent.md"}""", idle.agentOf())

        val busy = rig(chatOnAgent(plan.uri, active = true)).apply { agents = listOf(plan, review) }
        busy.interactor.send("later", agent = AgentPick.Use(review.uri))
        busy.interactor.steer("now", agent = AgentPick.Use(review.uri))
        assertEquals(listOf("queued", "steering"), listOf(busy.wire(0)["kind"], busy.wire(1)["kind"]).map { it!!.jsonPrimitive.content })
        assertEquals("""{"uri":"file:///a/review.agent.md"}""", busy.agentOf(0))
        assertEquals("""{"uri":"file:///a/review.agent.md"}""", busy.agentOf(1))
    }

    @Test
    fun `choosing no agent sends none, which takes the chat off the one it was on`() = runTest {
        val rig = rig(chatOnAgent(plan.uri)).apply { agents = listOf(plan) }

        rig.interactor.send("hi", agent = AgentPick.None)

        assertNull(rig.agentOf())
        assertEquals("chat/turnStarted", rig.types().single())
    }

    @Test
    fun `a kept agent rides on a queued and a steering message too`() = runTest {
        val busy = rig(chatOnAgent(plan.uri, active = true)).apply { agents = listOf(plan) }

        busy.interactor.send("later")
        busy.interactor.steer("now")

        assertEquals("""{"uri":"file:///a/plan.agent.md"}""", busy.agentOf(0))
        assertEquals("""{"uri":"file:///a/plan.agent.md"}""", busy.agentOf(1))
    }

    @Test
    fun `an agent the host does not offer is never sent, by a new message or a steering one`() = runTest {
        val rig = rig(chatOnAgent(null)).apply { agents = listOf(plan) }

        assertEquals(InteractResult.InvalidAgent, rig.interactor.send("hi", agent = AgentPick.Use("file:///made-up.agent.md")))
        assertTrue(rig.sent.isEmpty())

        val busy = rig(chatOnAgent(null, active = true)).apply { agents = listOf(plan) }
        assertEquals(InteractResult.InvalidAgent, busy.interactor.steer("now", agent = AgentPick.Use("file:///made-up.agent.md")))
        assertTrue(busy.sent.isEmpty())
    }

    @Test
    fun `a bad agent is told apart from a blank message, and from a bad model`() = runTest {
        val rig = rig(chatOnAgent(null)).apply { agents = listOf(plan); models = listOf(sonnet) }

        assertEquals(InteractResult.Blank, rig.interactor.send("  ", agent = AgentPick.Use("file:///x")))
        assertEquals(InteractResult.InvalidModel, rig.interactor.send("hi", ModelChoice("nope"), AgentPick.Use("file:///x")))
    }

    @Test
    fun `a blank message and a refused chat are told apart from a bad model`() = runTest {
        val blank = rig().apply { models = listOf(sonnet) }
        assertEquals(InteractResult.Blank, blank.interactor.send("  ", ModelChoice("nope")))

        val refused = rig().apply { canInteract = false; models = listOf(sonnet) }
        assertEquals(InteractResult.NotAllowed, refused.interactor.send("hi", ModelChoice("nope")))
    }

    @Test
    fun `with no model picked nothing about a model is added, even when the host lists none`() = runTest {
        val rig = rig()

        assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), rig.interactor.send("hi"))

        assertNull(rig.modelOf())
    }

    @Test
    fun `the options are whatever the host offers now`() {
        val rig = rig()
        assertEquals(emptyList(), rig.interactor.modelOptions())

        rig.models = listOf(sonnet)

        assertEquals(listOf(sonnet), rig.interactor.modelOptions())
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
    fun `the device check names the hosts option that is being approved`() = runTest {
        val rig = rig(chat(active = activeTurn(pendingCall(options = twoOptions))))

        rig.interactor.approve("c1", "o-session")

        assertEquals("Allow in this session", rig.prompts.single().choice)
        assertTrue(rig.prompts.single().description!!.startsWith("Your choice: Allow in this session"))
    }

    @Test
    fun `an approval with no option names no choice`() = runTest {
        val rig = rig(chat(active = activeTurn(pendingCall())))

        rig.interactor.approve("c1")

        assertNull(rig.prompts.single().choice)
    }

    @Test
    fun `the prompt description puts the choice first, cut short, and skips a blank one`() {
        assertEquals("Your choice: Allow once\nRun the tests", ApprovalRequest("Run", "Run the tests", choice = "Allow once").description)
        assertEquals("Run the tests", ApprovalRequest("Run", "Run the tests", choice = "  ").description)
        assertEquals("Run the tests", ApprovalRequest("Run", "Run the tests").description)
        assertNull(ApprovalRequest("Run", null).description)
        assertEquals("Your choice: " + "x".repeat(60), ApprovalRequest("Run", null, choice = "x".repeat(200)).description)
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

    private fun pendingQuestion(
        requestId: String = "req-1",
        questions: String = """[{"kind":"text","id":"q1","title":"Branch","message":"Which branch?","min":2,"max":20}]""",
        answered: Boolean = false,
    ) = """{"kind":"inputRequest","request":{"id":"$requestId","questions":$questions}${if (answered) ""","response":"accept"""" else ""}}"""

    @Test
    fun `answerText answers a waiting free-text question without a device check and refuses anything else`() = runTest {
        val rig = rig(chat(active = activeTurn(pendingQuestion()))).apply { gateAnswer = false }

        assertEquals(InteractResult.Blank, rig.interactor.answerText("req-1", "   "))
        assertEquals(InteractResult.Blank, rig.interactor.answerText("req-1", "a"), "shorter than min")
        assertEquals(InteractResult.NoSuchRequest, rig.interactor.answerText("other", "main"))
        assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), rig.interactor.answerText("req-1", "main"))
        assertTrue(rig.prompts.isEmpty(), "answering a question needs no device check")
        assertEquals("accept", rig.wire()["response"]!!.jsonPrimitive.content)
        assertEquals("main", rig.wire()["answers"]!!.jsonObject["q1"]!!.jsonObject["value"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("Sent", InteractMessages.describeAnswer(InteractResult.Sent(DispatchOutcome.Accepted)))

        val alreadyAnswered = rig(chat(active = activeTurn(pendingQuestion(answered = true))))
        assertEquals(InteractResult.NoSuchRequest, alreadyAnswered.interactor.answerText("req-1", "main"))
        assertEquals("That is no longer waiting for an answer.", InteractMessages.describeAnswer(InteractResult.NoSuchRequest))

        val multiQuestion = rig(chat(active = activeTurn(pendingQuestion(questions = """[{"kind":"text","id":"q1","title":"One","message":"One"},{"kind":"text","id":"q2","title":"Two","message":"Two"}]"""))))
        assertEquals(InteractResult.NoSuchRequest, multiQuestion.interactor.answerText("req-1", "main"))

        val readOnly = rig(chat(active = activeTurn(pendingQuestion()))).apply { canInteract = false }
        assertEquals(InteractResult.NotAllowed, readOnly.interactor.answerText("req-1", "main"))
        assertEquals("Nothing was sent.", InteractMessages.describeAnswer(InteractResult.Blank))
    }

    // ---- checkpoints, edit & resend, retry, stop & send, pending queue ---------------------------------------------

    private fun multiTurnChat(
        active: Boolean = false,
        lastState: String = "complete",
        lastErrorResumable: Boolean? = null,
        steeringId: String? = null,
        queuedIds: List<String> = emptyList(),
    ): ChatState {
        val lastParts = if (lastErrorResumable != null) {
            """[{"kind":"error","error":{"errorType":"network","message":"Network error"},"resumable":$lastErrorResumable}]"""
        } else {
            "[]"
        }
        val steeringJson = steeringId?.let { ""","steeringMessage":{"id":"$it","message":{"text":"steer-$it","origin":{"kind":"user"}}}""" }.orEmpty()
        val queuedJson = if (queuedIds.isNotEmpty()) {
            ""","queuedMessages":[${queuedIds.joinToString(",") { """{"id":"$it","message":{"text":"queue-$it","origin":{"kind":"user"}}}""" }}]"""
        } else {
            ""
        }
        val activeJson = if (active) {
            ""","activeTurn":{"id":"t3","message":{"text":"active prompt","origin":{"kind":"user"}},"responseParts":[],"startedAt":"2026-10-22T10:05:00.000Z"}"""
        } else {
            ""
        }
        return Ahp.json.decodeFromString(
            ChatState.serializer(),
            """{"resource":"$uri","title":"Chat","status":8,"modifiedAt":"2026-10-22T10:00:00.000Z",
                "turns":[
                    {"id":"t1","message":{"text":"first prompt","origin":{"kind":"user"}},"responseParts":[],"startedAt":"2026-10-22T10:00:00.000Z","state":"complete"},
                    {"id":"t2","message":{"text":"second prompt","origin":{"kind":"user"}},"responseParts":$lastParts,"startedAt":"2026-10-22T10:02:00.000Z","state":"$lastState"}
                ]$steeringJson$queuedJson$activeJson}""",
        )
    }

    @Test
    fun `restoreCheckpoint truncates to the preceding turn, or clears all turns before the first turn`() = runTest {
        val rig = rig(multiTurnChat(active = true))

        assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), rig.interactor.restoreCheckpoint("t2"))
        assertEquals("chat/truncated", rig.types().last())
        assertEquals("t1", rig.wire(0)["turnId"]!!.jsonPrimitive.content)

        assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), rig.interactor.restoreCheckpoint("t1"))
        assertNull(rig.wire(1)["turnId"])

        assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), rig.interactor.restoreCheckpoint("t3"))
        assertEquals("t2", rig.wire(2)["turnId"]!!.jsonPrimitive.content)

        assertEquals(InteractResult.NoSuchRequest, rig.interactor.restoreCheckpoint("t-missing"))
    }

    @Test
    fun `startOver truncates all turns and refuses when the chat is already empty`() = runTest {
        val empty = rig(chat())
        assertEquals(InteractResult.NoSuchRequest, empty.interactor.startOver())
        assertTrue(empty.sent.isEmpty())

        val populated = rig(multiTurnChat())
        assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), populated.interactor.startOver())
        assertEquals(listOf("chat/truncated"), populated.types())
        assertNull(populated.wire()["turnId"])
    }

    @Test
    fun `editAndResend truncates before the edited turn and starts a new turn with the edited text`() = runTest {
        val rig = rig(multiTurnChat()).apply { models = listOf(sonnet); agents = listOf(plan) }

        val res = rig.interactor.editAndResend("t2", "revised second", ModelChoice("claude-sonnet-5.5"), AgentPick.Use(plan.uri))

        assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), res)
        assertEquals(listOf("chat/truncated", "chat/turnStarted"), rig.types())
        assertEquals("t1", rig.wire(0)["turnId"]!!.jsonPrimitive.content)
        assertEquals("revised second", rig.wire(1)["message"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("claude-sonnet-5.5", rig.wire(1)["message"]!!.jsonObject["model"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(plan.uri, rig.wire(1)["message"]!!.jsonObject["agent"]!!.jsonObject["uri"]!!.jsonPrimitive.content)
    }

    @Test
    fun `editAndResend does not send a new turn when truncation is rejected or inputs are invalid`() = runTest {
        val rig = rig(multiTurnChat()).apply { models = listOf(sonnet); agents = listOf(plan) }

        assertEquals(InteractResult.Blank, rig.interactor.editAndResend("t1", "   "))
        assertEquals(InteractResult.InvalidModel, rig.interactor.editAndResend("t1", "ok", ModelChoice("bad")))
        assertEquals(InteractResult.InvalidAgent, rig.interactor.editAndResend("t1", "ok", agent = AgentPick.Use("file:///bad")))
        assertEquals(InteractResult.NoSuchRequest, rig.interactor.editAndResend("t-missing", "ok"))
        assertTrue(rig.sent.isEmpty())

        rig.outcome = DispatchOutcome.Rejected("cannot truncate")
        assertEquals(InteractResult.Sent(DispatchOutcome.Rejected("cannot truncate")), rig.interactor.editAndResend("t1", "ok"))
        assertEquals(listOf("chat/truncated"), rig.types(), "startTurn must not be sent when truncate was rejected")
    }

    @Test
    fun `retryTurn uses turnResume on a resumable error at the end, and truncates + resends otherwise`() = runTest {
        val resumableRig = rig(multiTurnChat(lastState = "error", lastErrorResumable = true))
        assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), resumableRig.interactor.retryTurn("t2"))
        assertEquals(listOf("chat/turnResume"), resumableRig.types())
        assertEquals("t2", resumableRig.wire()["turnId"]!!.jsonPrimitive.content)

        val nonResumableRig = rig(multiTurnChat(lastState = "error", lastErrorResumable = false))
        assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), nonResumableRig.interactor.retryTurn("t2"))
        assertEquals(listOf("chat/truncated", "chat/turnStarted"), nonResumableRig.types())
        assertEquals("t1", nonResumableRig.wire(0)["turnId"]!!.jsonPrimitive.content)
        assertEquals("second prompt", nonResumableRig.wire(1)["message"]!!.jsonObject["text"]!!.jsonPrimitive.content)

        val earlierTurnRig = rig(multiTurnChat(lastState = "error", lastErrorResumable = true))
        assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), earlierTurnRig.interactor.retryTurn("t1"))
        assertEquals(listOf("chat/truncated", "chat/turnStarted"), earlierTurnRig.types())
        assertNull(earlierTurnRig.wire(0)["turnId"])
        assertEquals("first prompt", earlierTurnRig.wire(1)["message"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(InteractResult.NoSuchRequest, earlierTurnRig.interactor.retryTurn("t-missing"))
    }

    @Test
    fun `stopAndSend cancels the active turn before starting the new turn, and queue delegates to send`() = runTest {
        val busy = rig(multiTurnChat(active = true))
        assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), busy.interactor.stopAndSend("urgent"))
        assertEquals(listOf("chat/turnCancelled", "chat/turnStarted"), busy.types())
        assertEquals("t3", busy.wire(0)["turnId"]!!.jsonPrimitive.content)
        assertEquals("urgent", busy.wire(1)["message"]!!.jsonObject["text"]!!.jsonPrimitive.content)

        val queued = rig(multiTurnChat(active = true))
        assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), queued.interactor.queue("later"))
        assertEquals(listOf("chat/pendingMessageSet"), queued.types())

        val rejectedStop = rig(multiTurnChat(active = true)).apply { outcome = DispatchOutcome.Rejected("cannot stop") }
        assertEquals(InteractResult.Sent(DispatchOutcome.Rejected("cannot stop")), rejectedStop.interactor.stopAndSend("urgent"))
        assertEquals(listOf("chat/turnCancelled"), rejectedStop.types())
    }

    @Test
    fun `removePending and reorderQueue manage steering and queued messages and validate ids`() = runTest {
        val rig = rig(multiTurnChat(active = true, steeringId = "s1", queuedIds = listOf("q1", "q2")))

        assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), rig.interactor.removePending("s1"))
        assertEquals("steering", rig.wire(0)["kind"]!!.jsonPrimitive.content)
        assertEquals("s1", rig.wire(0)["id"]!!.jsonPrimitive.content)

        assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), rig.interactor.removePending("q2"))
        assertEquals("queued", rig.wire(1)["kind"]!!.jsonPrimitive.content)
        assertEquals("q2", rig.wire(1)["id"]!!.jsonPrimitive.content)

        assertEquals(InteractResult.NoSuchRequest, rig.interactor.removePending("unknown"))

        assertEquals(InteractResult.Blank, rig.interactor.reorderQueue(listOf("q1", "q2")))
        assertEquals(InteractResult.NoSuchRequest, rig.interactor.reorderQueue(listOf("q1")))
        assertEquals(InteractResult.NoSuchRequest, rig.interactor.reorderQueue(listOf("q1", "q3")))
        assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), rig.interactor.reorderQueue(listOf("q2", "q1")))
        assertEquals("chat/queuedMessagesReordered", rig.types().last())

        val msg = rig.interactor.buildInitialMessage("fork text")
        assertEquals("fork text", msg?.text)
        assertNull(rig.interactor.buildInitialMessage("   "))
    }
}
