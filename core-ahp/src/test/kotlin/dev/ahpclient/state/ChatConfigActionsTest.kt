package dev.ahpclient.state

import dev.ahpclient.ahp.CapabilityDeniedException
import dev.ahpclient.ahp.CapabilityPolicy
import dev.ahpclient.ahp.ChatConfigKeys
import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.StateAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class ChatConfigActionsTest {
    private fun wire(action: StateAction): JsonObject = Ahp.json.encodeToJsonElement(StateAction.serializer(), action).jsonObject

    @Test
    fun `a setting is one action naming one property and one word, and nothing else`() {
        assertEquals(Json.parseToJsonElement("""{"type":"chat/configChanged","config":{"mode":"plan"}}"""), wire(ChatConfigActions.set("mode", "plan")))
        assertEquals(
            Json.parseToJsonElement("""{"type":"chat/configChanged","config":{"autoApprove":"autoApprove"}}"""),
            wire(ChatConfigActions.set("autoApprove", "autoApprove")),
        )
        assertEquals(
            Json.parseToJsonElement("""{"type":"chat/configChanged","config":{"approvalMode":"allow-all"}}"""),
            wire(ChatConfigActions.set("approvalMode", "allow-all")),
        )
    }

    @Test
    fun `every word the policy allows can be built, and passes the policy`() {
        ChatConfigKeys.WRITABLE.forEach { (key, words) ->
            words.forEach { word ->
                val json = wire(ChatConfigActions.set(key, word))
                CapabilityPolicy.INTERACT.checkContent(CapabilityPolicy.CHAT_CONFIG_CHANGED, json)
                CapabilityPolicy.MANAGE.checkContent(CapabilityPolicy.CHAT_CONFIG_CHANGED, json)
            }
        }
    }

    @Test
    fun `a property or a word outside the list cannot be built`() {
        listOf(
            "permissions" to "x", "sandboxEnabled" to "off", "effectiveApprovalMode" to "autoApprove", "availableApprovalModes" to "x", "" to "x",
            "mode" to "yolo", "mode" to "", "mode" to "Plan", "mode" to "autoApprove", "autoApprove" to "plan", "autoApprove" to "allow-all", "approvalMode" to "autoApprove",
        ).forEach { (key, value) ->
            assertFailsWith<IllegalArgumentException>("$key=$value") { ChatConfigActions.set(key, value) }
        }
    }

    @Test
    fun `the device check names what is being allowed in this app's words`() {
        val view = ChatConfig.view(configSession(), CFG_CHAT, ALL_CHAT_MUTABLE, emptyMap())

        assertEquals("Allow all: tool calls run without asking", ChatConfigPrompt.name(view.approvals!!, "autoApprove"))
        assertEquals("Assisted: the host decides which tool calls need you", ChatConfigPrompt.name(view.approvals!!, "assisted"))
        assertEquals("Autopilot: the agent works on its own", ChatConfigPrompt.name(view.mode!!, "autopilot"))
        assertEquals("Mode: Plan", ChatConfigPrompt.name(view.mode!!, "plan"))
        assertTrue(ChatConfigPrompt.TITLE.isNotBlank())
    }

    @Test
    fun `the prompt's words do not come from the host`() {
        val renamed = properties("autoApprove" to AUTO_APPROVE_PROPERTY.replace("Allow all", "Harmless").replace("Runs tool calls without asking", "Perfectly safe"))
        val view = ChatConfig.view(configSession(properties = renamed, values = ""), CFG_CHAT, ALL_CHAT_MUTABLE, emptyMap())

        assertEquals("Allow all: tool calls run without asking", ChatConfigPrompt.name(view.approvals!!, "autoApprove"))
    }

    @Test
    fun `the policy refuses anything but exactly one allowed property with an allowed word`() {
        fun denied(json: String) = assertFailsWith<CapabilityDeniedException>(json) {
            CapabilityPolicy.INTERACT.checkContent("chat/configChanged", Json.parseToJsonElement(json).jsonObject)
        }
        fun allowed(json: String) = CapabilityPolicy.INTERACT.checkContent("chat/configChanged", Json.parseToJsonElement(json).jsonObject)

        allowed("""{"type":"chat/configChanged","config":{"mode":"autopilot"}}""")
        denied("""{"type":"chat/configChanged"}""")
        denied("""{"type":"chat/configChanged","config":{}}""")
        denied("""{"type":"chat/configChanged","config":{"mode":"plan","autoApprove":"autoApprove"}}""")
        denied("""{"type":"chat/configChanged","config":{"permissions":"x"}}""")
        denied("""{"type":"chat/configChanged","config":{"sandboxEnabled":"off"}}""")
        denied("""{"type":"chat/configChanged","config":{"effectiveApprovalMode":"autoApprove"}}""")
        denied("""{"type":"chat/configChanged","config":{"mode":"yolo"}}""")
        denied("""{"type":"chat/configChanged","config":{"mode":7}}""")
        denied("""{"type":"chat/configChanged","config":{"mode":null}}""")
        denied("""{"type":"chat/configChanged","config":{"mode":["plan"]}}""")
        denied("""{"type":"chat/configChanged","config":{"mode":{"v":"plan"}}}""")
        denied("""{"type":"chat/configChanged","config":"mode"}""")
        denied("""{"type":"chat/configChanged","config":{"mode":"plan"},"replace":true}""")
        denied("""{"type":"chat/configChanged","config":{"mode":"plan"},"extra":1}""")
        denied("""{"type":"chat/configChanged","config":{"mode":"plan "}}""")
    }

    @Test
    fun `the content check leaves the other allowed actions alone`() {
        CapabilityPolicy.INTERACT.checkContent("chat/turnStarted", Json.parseToJsonElement("""{"type":"chat/turnStarted","anything":1}""").jsonObject)
        CapabilityPolicy.INTERACT.checkContent(null, JsonObject(emptyMap()))
    }
}
