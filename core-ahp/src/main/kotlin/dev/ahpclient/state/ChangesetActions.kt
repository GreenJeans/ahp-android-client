package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.StateAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds the one changeset action the MANAGE tier may send. Assembled as structured JSON and decoded through the protocol
 * library's own serializer, like [SessionActions]: the library decodes an unrecognised `type` as a harmless no-op, so the
 * builder is pinned to its exact type and fields by tests. Nothing here talks to the host.
 */
object ChangesetActions {
    /** Sets the "viewed" flag of the listed files of a changeset, on the changeset's own channel. Nothing is read or written. */
    fun setReviewed(fileIds: List<String>, reviewed: Boolean): StateAction =
        Ahp.json.decodeFromJsonElement(
            StateAction.serializer(),
            buildJsonObject {
                put("type", "changeset/filesReviewChanged")
                put("files", JsonArray(fileIds.map(::JsonPrimitive)))
                put("reviewed", reviewed)
            },
        )
}
