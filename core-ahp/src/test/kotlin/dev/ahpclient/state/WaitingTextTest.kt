package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.SessionInputRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The words a notification uses for what a session is waiting on: one thing as it is, several as a count and the first. */
class WaitingTextTest {
    private fun requests(json: String): List<SessionInputRequest> =
        Ahp.json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(SessionInputRequest.serializer()), json)

    private fun tool(id: String, intention: String) =
        """{"kind":"toolConfirmation","id":"$id","chat":"c","turnId":"t","toolCall":{"status":"pending-confirmation","toolCallId":"$id","toolName":"p",
            "displayName":"Run command","intention":"$intention","invocationMessage":"x"}}"""

    private val question = """{"kind":"chatInput","id":"q1","chat":"c","request":{"id":"q","message":"Which season?"}}"""

    @Test
    fun `nothing waiting has no words`() {
        assertNull(WaitingText.of(emptyList()))
    }

    @Test
    fun `one thing is said as it is`() {
        assertEquals("Run command: Run it", WaitingText.of(requests("[${tool("a", "Run it")}]")))
        assertEquals("Which season?", WaitingText.of(requests("[$question]")))
    }

    @Test
    fun `several approvals are counted and the first is named`() {
        val text = WaitingText.of(requests("[${tool("a", "Run one")},${tool("b", "Run two")},${tool("c", "Run three")}]"))

        assertEquals("3 approvals waiting. First: Run command: Run one. Open the app to decide.", text)
    }

    @Test
    fun `a mix of kinds is called requests, not approvals`() {
        val text = WaitingText.of(requests("[$question,${tool("a", "Run one")}]"))

        assertEquals("2 requests waiting. First: Which season?. Open the app to decide.", text)
    }
}
