package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.StateAction
import kotlin.test.Test
import kotlin.test.assertEquals

class AnnotationActionsTest {
    private fun wire(action: StateAction) = Ahp.json.encodeToJsonElement(StateAction.serializer(), action)

    @Test
    fun `annotation comments use the exact protocol action shapes`() {
        assertEquals(
            """{"type":"annotations/entrySet","annotationId":"a1","entry":{"id":"e1","text":"looks good"}}""",
            wire(AnnotationActions.setEntry("a1", "e1", "looks good")).toString(),
        )
        assertEquals(
            """{"type":"annotations/entryRemoved","annotationId":"a1","entryId":"e1"}""",
            wire(AnnotationActions.removeEntry("a1", "e1")).toString(),
        )
    }
}
