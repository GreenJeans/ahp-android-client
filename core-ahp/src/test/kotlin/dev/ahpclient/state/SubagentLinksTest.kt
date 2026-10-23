package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ChatState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubagentLinksTest {
    private fun subagentCall(resource: String, id: String = "c1") =
        """{"kind":"toolCall","toolCall":{"status":"completed","toolCallId":"$id","toolName":"runSubagent","displayName":"Subagent","invocationMessage":"Go",
            "toolInput":"{}","confirmed":"not-needed","success":true,"pastTenseMessage":"Done",
            "content":[{"type":"subagent","resource":"$resource","title":"T","agentName":"a","description":"d"}]}}"""

    private fun chat(finished: String = "", active: String? = null): ChatState = Ahp.json.decodeFromString(
        ChatState.serializer(),
        """{"resource":"ahp-chat:/c","title":"C","status":1,"modifiedAt":"2026-10-22T10:00:00.000Z",
            "turns":[{"id":"t1","message":{"text":"hi","origin":{"kind":"user"}},"responseParts":[$finished],"startedAt":"2026-10-22T10:00:00.000Z","state":"complete"}]
            ${active?.let { ""","activeTurn":{"id":"t2","message":{"text":"now","origin":{"kind":"user"}},"responseParts":[$it],"startedAt":"2026-10-22T10:05:00.000Z"}""" }.orEmpty()}}""",
    )

    @Test
    fun `a chat with no subagent results points at nothing`() {
        assertTrue(SubagentLinks.referencedBy(chat()).isEmpty())
    }

    @Test
    fun `the chats named by subagent results are found in finished turns and in the running one`() {
        val found = SubagentLinks.referencedBy(chat(finished = subagentCall("ahp-chat:/one"), active = subagentCall("ahp-chat:/two", "c2")))

        assertEquals(setOf("ahp-chat:/one", "ahp-chat:/two"), found)
    }

    @Test
    fun `an address that is not an AHP chat is never among them`() {
        val found = SubagentLinks.referencedBy(chat(finished = subagentCall("ahp-terminal:/t") + "," + subagentCall("https://evil.example/x", "c2") + "," + subagentCall("ahp-chat:/ok", "c3")))

        assertEquals(setOf("ahp-chat:/ok"), found)
    }
}
