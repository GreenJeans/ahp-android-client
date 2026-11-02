package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ActionEnvelope
import com.microsoft.agenthostprotocol.generated.Annotation
import com.microsoft.agenthostprotocol.generated.AnnotationOrigin
import com.microsoft.agenthostprotocol.generated.AnnotationsState
import com.microsoft.agenthostprotocol.generated.CanvasState
import com.microsoft.agenthostprotocol.generated.Snapshot
import com.microsoft.agenthostprotocol.generated.SnapshotState
import com.microsoft.agenthostprotocol.generated.StateAction
import kotlin.test.Test
import kotlin.test.assertEquals

class CanvasAnnotationsStoreTest {
    @Test
    fun `canvas store seeds a snapshot and folds state changes`() {
        val uri = "ahp-canvas:/one"
        val store = canvasStore(uri)
        store.seed(Snapshot(uri, SnapshotState.Canvas(CanvasState("i1", "ext", canvasId = "web", title = "Before")), 5))

        store.apply(ActionEnvelope(uri, action("""{"type":"canvas/stateChanged","canvas":{"instanceId":"i1","extensionId":"ext","canvasId":"web","title":"After","status":"Ready"}}"""), 6))

        assertEquals("After", store.state.value?.title)
        assertEquals("Ready", store.state.value?.status)
    }

    @Test
    fun `annotations store folds entry addition and removal on a seeded thread`() {
        val uri = "ahp-annotations:/one"
        val annotation = Annotation(
            id = "a1",
            origin = AnnotationOrigin(session = "ahp-session:/s1"),
            resource = "file:///work/a.kt",
            resolved = false,
            entries = emptyList(),
        )
        val store = annotationsStore(uri)
        store.seed(Snapshot(uri, SnapshotState.Annotations(AnnotationsState(listOf(annotation))), 10))

        store.apply(ActionEnvelope(uri, AnnotationActions.setEntry("a1", "e1", "A note"), 11))
        assertEquals("A note", (store.state.value!!.annotations.single().entries.single().text as com.microsoft.agenthostprotocol.generated.StringOrMarkdown.Plain).value)

        store.apply(ActionEnvelope(uri, AnnotationActions.removeEntry("a1", "e1"), 12))
        assertEquals(emptyList(), store.state.value!!.annotations.single().entries)
    }

    private fun action(json: String): StateAction = Ahp.json.decodeFromString(StateAction.serializer(), json)
}
