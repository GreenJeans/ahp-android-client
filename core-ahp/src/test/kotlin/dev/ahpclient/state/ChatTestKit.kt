package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ActionEnvelope
import com.microsoft.agenthostprotocol.generated.ChatState
import com.microsoft.agenthostprotocol.generated.ResponsePartMarkdown
import com.microsoft.agenthostprotocol.generated.Snapshot

internal const val CHAT_A = "ahp-chat:/00000000-0000-4000-8000-000000000c01"
internal const val CHAT_B = "ahp-chat:/00000000-0000-4000-8000-000000000c02"

internal fun turnJson(id: String, text: String = "question $id") =
    """{"id":"$id","message":{"text":"$text","origin":{"kind":"user"}},"responseParts":[],"startedAt":"2026-10-22T10:00:00.000Z","state":"complete"}"""

internal fun chatStateJson(uri: String, turns: List<String> = emptyList(), cursor: String? = null) =
    """{"resource":"$uri","title":"Chat","status":1,"modifiedAt":"2026-10-22T10:00:00.000Z","turns":[${turns.joinToString(",")}]${cursor?.let { ""","turnsNextCursor":"$it"""" }.orEmpty()}}"""

internal fun chatSnapshotJson(uri: String, fromSeq: Long, turns: List<String> = emptyList(), cursor: String? = null) =
    """{"resource":"$uri","fromSeq":$fromSeq,"state":${chatStateJson(uri, turns, cursor)}}"""

internal fun chatSnapshot(uri: String, fromSeq: Long, turns: List<String> = emptyList(), cursor: String? = null): Snapshot =
    Ahp.json.decodeFromString(Snapshot.serializer(), chatSnapshotJson(uri, fromSeq, turns, cursor))

internal fun chatEnvelope(uri: String, actionJson: String, seq: Long): ActionEnvelope =
    Ahp.json.decodeFromString(ActionEnvelope.serializer(), """{"channel":"$uri","action":$actionJson,"serverSeq":$seq}""")

internal const val TURN_STARTED = """{"type":"chat/turnStarted","turnId":"t1","startedAt":"2026-10-22T10:00:01.000Z","message":{"text":"hello","origin":{"kind":"user"}}}"""
internal const val MARKDOWN_PART = """{"type":"chat/responsePart","turnId":"t1","part":{"kind":"markdown","id":"p1","content":""}}"""

internal fun deltaJson(text: String) = """{"type":"chat/delta","turnId":"t1","partId":"p1","content":"$text"}"""

internal fun turnsLoadedJson(turns: List<String>, cursor: String?) =
    """{"type":"chat/turnsLoaded","turns":[${turns.joinToString(",")}]${cursor?.let { ""","turnsNextCursor":"$it"""" }.orEmpty()}}"""

/** The text of the first markdown part in the running turn, or in the last finished one. */
internal fun ChatState.markdownText(): String? {
    val parts = activeTurn?.responseParts ?: turns.lastOrNull()?.responseParts ?: return null
    return parts.filterIsInstance<ResponsePartMarkdown>().firstOrNull()?.value?.content
}
