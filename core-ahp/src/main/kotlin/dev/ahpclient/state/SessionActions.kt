package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.StateAction
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds the two session actions the MANAGE tier may send, and only those. Assembled as structured JSON and decoded through
 * the protocol library's own serializer, like [ChatActions]: the library decodes an unrecognised `type` as a harmless no-op,
 * so each builder is pinned to its exact type and fields by tests. Nothing here talks to the host.
 */
object SessionActions {
    /** Marks the session read or unread. Dispatched on the session's own channel. */
    fun setRead(isRead: Boolean): StateAction = action("session/isReadChanged") { put("isRead", isRead) }

    /** Archives or restores the session. Archiving removes nothing: the session stays on the host and can be brought back. */
    /** Archives or restores the session. Archiving removes nothing: the session stays on the host and can be brought back. */
    fun setArchived(isArchived: Boolean): StateAction = action("session/isArchivedChanged") { put("isArchived", isArchived) }

    /** Sets the session's title. Dispatched on the session's own channel. */
    /** Sets the session's title. Dispatched on the session's own channel. */
    fun setTitle(title: String): StateAction = action("session/titleChanged") { put("title", title) }

    /** Marks a chat read or unread. Dispatched on the chat's own channel. */
    fun setChatRead(isRead: Boolean): StateAction = action("chat/isReadChanged") { put("isRead", isRead) }

    /** Archives or restores a chat. Dispatched on the chat's own channel. */
    /** Archives or restores a chat. Dispatched on the chat's own channel. */
    fun setChatArchived(isArchived: Boolean): StateAction = action("chat/isArchivedChanged") { put("isArchived", isArchived) }

    /** Requests starting an MCP server in the session. Dispatched on the session's own channel. */
    fun startMcpServer(id: String): StateAction = action("session/mcpServerStartRequested") { put("id", id) }

    /** Requests stopping an MCP server in the session. Dispatched on the session's own channel. */
    fun stopMcpServer(id: String): StateAction = action("session/mcpServerStopRequested") { put("id", id) }

    /** Requests moving a starting MCP server to the background. Dispatched on the session's own channel. */
    fun backgroundMcpServer(id: String): StateAction = action("session/mcpServerBackgroundRequested") { put("id", id) }

    private fun action(type: String, fields: JsonObjectBuilder.() -> Unit): StateAction =
        Ahp.json.decodeFromJsonElement(
            StateAction.serializer(),
            buildJsonObject {
                put("type", type)
                fields()
            },
        )
}
