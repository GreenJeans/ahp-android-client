package dev.ahpclient

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ActionEnvelope
import com.microsoft.agenthostprotocol.generated.ChatState
import com.microsoft.agenthostprotocol.generated.ResponsePart
import com.microsoft.agenthostprotocol.generated.SUPPORTED_PROTOCOL_VERSIONS
import com.microsoft.agenthostprotocol.generated.ToolCallState
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Step 1.1: proves the pinned AHP artifact resolves, decodes, and covers the chat channel.
class DependencySmokeTest {

    @Test
    fun `offers protocol 0_9_0 so the current VS Code host can negotiate`() {
        assertTrue("0.9.0" in SUPPORTED_PROTOCOL_VERSIONS, "supported: $SUPPORTED_PROTOCOL_VERSIONS")
    }

    @Test
    fun `decodes a server action envelope`() {
        val envelope = Ahp.json.decodeFromString(
            ActionEnvelope.serializer(),
            """{"channel":"ahp-session:/abc","action":{"type":"session/titleChanged","title":"new"},"serverSeq":42}""",
        )
        assertEquals("ahp-session:/abc", envelope.channel)
        assertEquals(42, envelope.serverSeq)
    }

    @Test
    fun `covers chat channel types needed for the transcript phase`() {
        val chatTypes: List<KClass<*>> = listOf(ChatState::class, ResponsePart::class, ToolCallState::class)
        assertEquals(3, chatTypes.size)
    }
}
