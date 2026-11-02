package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.AnnotationEntry
import com.microsoft.agenthostprotocol.generated.StateAction
import com.microsoft.agenthostprotocol.generated.StringOrMarkdown
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Builds the reversible annotation comment actions available to an INTERACT connection. */
object AnnotationActions {
    fun setEntry(annotationId: String, entryId: String, text: String): StateAction =
        Ahp.json.decodeFromJsonElement(
            StateAction.serializer(),
            buildJsonObject {
                put("type", "annotations/entrySet")
                put("annotationId", annotationId)
                put("entry", Ahp.json.encodeToJsonElement(AnnotationEntry.serializer(), AnnotationEntry(entryId, StringOrMarkdown.Plain(text))))
            },
        )

    fun removeEntry(annotationId: String, entryId: String): StateAction =
        Ahp.json.decodeFromJsonElement(
            StateAction.serializer(),
            buildJsonObject {
                put("type", "annotations/entryRemoved")
                put("annotationId", annotationId)
                put("entryId", entryId)
            },
        )
}
