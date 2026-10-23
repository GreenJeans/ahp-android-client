package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** When a notification may offer Approve and Deny, and for which call. Everything else is decided in the app. */
class ApprovalTargetTest {
    private val chat = "ahp-chat://default/x"

    private fun state(inputNeeded: String?, chats: List<String> = listOf(chat)): SessionState = Ahp.json.decodeFromString(
        SessionState.serializer(),
        """{"provider":"copilotcli","title":"Fix the build","status":56,"lifecycle":"ready","activeClients":[],
            "chats":[${chats.joinToString(",") { """{"resource":"$it","title":"Main","status":56,"modifiedAt":"2026-10-26T10:29:00.000Z"}""" }}]
            ${if (inputNeeded != null) ""","inputNeeded":$inputNeeded""" else ""}}""",
    )

    private fun tool(chatUri: String = chat, id: String = "r1", call: String = pendingCall()) =
        """{"kind":"toolConfirmation","id":"$id","chat":"$chatUri","turnId":"t","toolCall":$call}"""

    private fun pendingCall(toolCallId: String = "c1", options: String = "") =
        """{"status":"pending-confirmation","toolCallId":"$toolCallId","toolName":"run_in_terminal","displayName":"Run in Terminal",
            "invocationMessage":"Run it","toolInput":"{}","confirmationTitle":"Run?"$options}"""

    private val resultCall =
        """{"status":"pending-result-confirmation","toolCallId":"c1","toolName":"run_in_terminal","displayName":"Run in Terminal",
            "invocationMessage":"Run it","toolInput":"{}","pastTenseMessage":"Ran it","confirmed":"not-needed","success":true}"""

    @Test
    fun `one plain tool call waiting in a chat the session lists gets buttons`() {
        assertEquals(ApprovalTarget(chat, "c1"), ApprovalTarget.from(state("[${tool()}]")))
    }

    @Test
    fun `an empty options list is still plain`() {
        assertEquals(ApprovalTarget(chat, "c1"), ApprovalTarget.from(state("[${tool(call = pendingCall(options = ""","options":[]"""))}]")))
    }

    @Test
    fun `a call with options of the hosts own is decided in the app`() {
        val options = ""","options":[{"id":"o1","label":"Allow once","kind":"approve"},{"id":"o2","label":"Not now","kind":"deny"}]"""

        assertNull(ApprovalTarget.from(state("[${tool(call = pendingCall(options = options))}]")))
    }

    @Test
    fun `the output of a tool is decided in the app, not from a notification`() {
        assertNull(ApprovalTarget.from(state("[${tool(call = resultCall)}]")))
    }

    @Test
    fun `a session waiting on more than one thing is decided in the app`() {
        assertNull(ApprovalTarget.from(state("[${tool(id = "r1")},${tool(id = "r2", call = pendingCall("c2"))}]")))
    }

    @Test
    fun `a question is not a tool call`() {
        val question = """[{"kind":"chatInput","id":"r1","chat":"$chat","request":{"id":"q","message":"Which season do you prefer?"}}]"""

        assertNull(ApprovalTarget.from(state(question)))
    }

    @Test
    fun `a session waiting on nothing has no target`() {
        assertNull(ApprovalTarget.from(state(null)))
        assertNull(ApprovalTarget.from(state("[]")))
    }

    @Test
    fun `a chat the session does not list is never targeted`() {
        assertNull(ApprovalTarget.from(state("[${tool(chatUri = "ahp-chat://default/elsewhere")}]")))
    }

    @Test
    fun `an address that is too long is never targeted, even if the session lists it`() {
        val tooLong = "ahp-chat://default/" + "x".repeat(ApprovalTarget.MAX_CHAT_URI)

        assertNull(ApprovalTarget.from(state("[${tool(chatUri = tooLong)}]", chats = listOf(chat, tooLong))))
    }

    @Test
    fun `a blank address is never targeted, even if the session lists it`() {
        assertNull(ApprovalTarget.from(state("[${tool(chatUri = "")}]", chats = listOf(chat, ""))))
    }
}
