package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.ChatState
import com.microsoft.agenthostprotocol.generated.ResponsePartToolCall

/** Which other chats a chat points at: the transcripts of the subagents its tool calls started. */
object SubagentLinks {
    /**
     * The chat addresses that subagent results in [chat] name, and only those. A person can open exactly these from this
     * chat, so a host cannot make the app subscribe to an address of its choosing by putting it somewhere else.
     */
    fun referencedBy(chat: ChatState): Set<String> {
        val uris = LinkedHashSet<String>()
        val parts = chat.turns.flatMap { it.responseParts } + chat.activeTurn?.responseParts.orEmpty()
        for (part in parts) {
            val call = (part as? ResponsePartToolCall)?.value?.toolCall ?: continue
            ToolDetail.from(call).results.filterIsInstance<ToolResultView.Subagent>().mapNotNullTo(uris) { it.chatUri }
        }
        return uris
    }
}
