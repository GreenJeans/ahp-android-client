package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.ChatInteractivity
import com.microsoft.agenthostprotocol.generated.ChatOrigin
import com.microsoft.agenthostprotocol.generated.ChatState

/** How a chat came about, from the host's `ChatSummary.origin`. [parentChat] is the chat it came from, when the host names one. */
sealed interface ChatRelation {
    val parentChat: String? get() = null

    /** A copy of [parentChat] up to [turnId], going its own way from there. */
    data class Fork(override val parentChat: String, val turnId: String) : ChatRelation

    /** A conversation of its own, started from [turnId] of [parentChat]. */
    data class SideChat(override val parentChat: String, val turnId: String) : ChatRelation

    /** Started by a tool call running in [parentChat], such as the agent handing work to a sub-agent. */
    data class ByAgent(override val parentChat: String) : ChatRelation

    /** The person made it, in this app or on the host. */
    data object ByYou : ChatRelation

    /** The host gave no origin, or one of a kind this app does not know: it cannot be told apart, so it is not guessed at. */
    data object Unknown : ChatRelation

    companion object {
        fun of(origin: ChatOrigin?): ChatRelation = when (origin) {
            is ChatOrigin.Fork -> Fork(origin.value.chat, origin.value.turnId)
            is ChatOrigin.SideChat -> SideChat(origin.value.chat, origin.value.turnId)
            is ChatOrigin.Tool -> ByAgent(origin.value.chat)
            is ChatOrigin.User -> ByYou
            else -> Unknown
        }
    }
}

/**
 * The groups of the chat switcher, in the order the sheet shows them. Only the agent's own chats start folded away; the hidden
 * chats are left out altogether until the person asks for them.
 */
enum class SwitcherGroupKind(val title: String, val collapsedByDefault: Boolean) {
    /**
     * Any chat but the main one that is waiting on the person, whatever its relation, and even when it is archived or hidden,
     * so nothing that needs them is folded, buried or left out.
     */
    NEEDS_YOU("Waiting on you", false),
    FORKS("Forks", false),
    SIDE_CHATS("Side chats", false),
    BY_YOU("Started by you", false),
    OTHER("Other chats", false),

    /** Chats a tool call started, and chats the host marks as watch-only: the person rarely needs them. */
    AGENT("Chats the agent uses", true),

    /** Chats the host marks hidden (internal to the agent). Listed only when the person asks to see them. */
    HIDDEN("Hidden chats", false),
}

/** One chat in the switcher. [depth] is how far it sits under the chat it came from, when that chat is in the same group. */
data class SwitcherRow(
    val uri: String,
    val title: String,
    /** Where it came from, in words ("Fork of turn 3"), or null when the host does not say. */
    val relation: String?,
    val depth: Int,
    val needsYou: Boolean,
    val unread: Boolean,
    val archived: Boolean,
    val shown: Boolean,
    val whenText: String,
) {
    /** Whether the row carries a dot. */
    val marked: Boolean get() = needsYou || unread

    /** The line under the title. */
    val detail: String get() = listOfNotNull(relation, whenText.takeIf { it.isNotBlank() }).joinToString(" · ")

    /** What a screen reader says for the row, so the dot and the indent are never the only cue. */
    val spoken: String
        get() = buildList {
            add(title)
            relation?.let(::add)
            if (needsYou) add(ActivityState.NEEDS_INPUT.label)
            if (unread) add("Unread")
            if (archived) add("Archived")
            if (shown) add("Shown")
        }.joinToString(", ")
}

data class SwitcherGroup(val kind: SwitcherGroupKind, val rows: List<SwitcherRow>) {
    val title: String get() = kind.title
    val collapsedByDefault: Boolean get() = kind.collapsedByDefault
    val unreadCount: Int get() = rows.count { it.unread }

    /** Said beside the title, so a folded group still tells how many chats it holds and whether any is unread. */
    val summary: String
        get() = buildList {
            add(countOf(rows.size, "chat", "chats"))
            if (unreadCount > 0) add("$unreadCount unread")
        }.joinToString(", ")
}

/**
 * What the session screen shows of a session's chats: the main chat on its own, one tap away, and every other chat behind a
 * single control ("2 other chats") that opens a sheet of [groups]. Chats waiting on the person come first; the rest are
 * grouped by how they came about, each shown under the chat it came from when that chat is in the same group; the agent's own
 * chats are folded away, and the ones the host marks hidden are left out unless asked for. A chat is unread when the host has
 * not marked it read and it is not the one on screen; that shows on its row, not on the control.
 *
 * [hiddenCount] is how many chats the host marks hidden, other than those waiting on the person (which are always listed):
 * left out of [groups] unless they were asked for, when they make up the [SwitcherGroupKind.HIDDEN] group.
 */
data class ChatSwitcher(val main: SwitcherRow?, val groups: List<SwitcherGroup>, val hiddenCount: Int = 0) {
    val others: List<SwitcherRow> get() = groups.flatMap { it.rows }
    val otherCount: Int get() = others.size
    val needsYouCount: Int get() = others.count { it.needsYou }

    /** Whether there is any other chat to reach, listed or hidden, so whether the control and its sheet are offered. */
    val hasOthers: Boolean get() = otherCount > 0 || hiddenCount > 0

    /** Every chat the switcher lets the person reach: the main chat and the listed others. */
    val reachable: List<String> get() = listOfNotNull(main?.uri) + others.map { it.uri }

    /** Whether the control carries a dot: only when some other chat is waiting on the person. Unread alone does not light it. */
    val marked: Boolean get() = needsYouCount > 0

    /** The control's text: the chats the sheet lists, or the hidden ones when those are all there is. */
    val label: String
        get() = if (otherCount == 0 && hiddenCount > 0) countOf(hiddenCount, "hidden chat", "hidden chats") else countOf(otherCount, "other chat", "other chats")

    /** The control's spoken description, carrying what the dot means. */
    val spoken: String
        get() = buildList {
            add(label)
            if (needsYouCount > 0) add("$needsYouCount waiting on you")
        }.joinToString(", ")

    companion object {
        val EMPTY = ChatSwitcher(null, emptyList())

        /** The main chat's relation line: where it came from matters less than that it is the session's own. */
        const val MAIN = "Main chat"

        /**
         * Builds the switcher from the session's chats as [SessionDetail.chats] lists them. Archived chats other than the main
         * one are left out unless [showArchived], as the tabs did, and chats the host marks hidden unless [showHidden]; a chat
         * waiting on the person is never left out, whichever it is. [turnNumber] says which turn of a chat a turn id is, when
         * this app holds enough of that chat to count; a relation without one names the chat instead.
         */
        fun of(
            chats: List<ChatRow>,
            shownChat: String?,
            showArchived: Boolean = false,
            showHidden: Boolean = false,
            turnNumber: (chatUri: String, turnId: String) -> Int? = { _, _ -> null },
        ): ChatSwitcher {
            val mainChat = chats.firstOrNull { it.isMain } ?: chats.firstOrNull() ?: return EMPTY
            val titles = chats.associate { it.uri to it.title }
            fun row(chat: ChatRow, depth: Int) = SwitcherRow(
                uri = chat.uri,
                title = chat.title,
                relation = if (chat.uri == mainChat.uri) MAIN else relationText(chat, mainChat.uri, titles, turnNumber),
                depth = depth,
                needsYou = chat.state == ActivityState.NEEDS_INPUT,
                unread = !chat.isRead && chat.uri != shownChat,
                archived = chat.isArchived,
                shown = chat.uri == shownChat,
                whenText = chat.whenText,
            )
            val others = chats
                .filter { it.uri != mainChat.uri && (showArchived || !it.isArchived || it.state == ActivityState.NEEDS_INPUT) }
                .distinctBy { it.uri }
            val byKind = others.groupBy(::kindOf)
            val hiddenCount = byKind[SwitcherGroupKind.HIDDEN]?.size ?: 0
            val groups = SwitcherGroupKind.entries.mapNotNull { kind ->
                if (kind == SwitcherGroupKind.HIDDEN && !showHidden) return@mapNotNull null
                val members = byKind[kind] ?: return@mapNotNull null
                // Waiting chats are listed flat, in the host's order: under a tree one could sit below another and be missed.
                val ordered = if (kind == SwitcherGroupKind.NEEDS_YOU) members.map { it to 0 } else treeOrder(members)
                SwitcherGroup(kind, ordered.map { (chat, depth) -> row(chat, depth) })
            }
            return ChatSwitcher(row(mainChat, 0), groups, hiddenCount)
        }

        /**
         * Which turn of [state] the turn [turnId] is, counting from 1, or null when it cannot be told: the turn is not among
         * those held, or older turns have not been loaded, so its place from the start is not known.
         */
        fun turnNumberIn(state: ChatState, turnId: String): Int? {
            if (state.turnsNextCursor != null) return null
            val index = state.turns.indexOfFirst { it.id == turnId }
            return when {
                index >= 0 -> index + 1
                state.activeTurn?.id == turnId -> state.turns.size + 1
                else -> null
            }
        }

        private fun agentOnly(chat: ChatRow): Boolean = chat.relation is ChatRelation.ByAgent || chat.interactivity == ChatInteractivity.READ_ONLY

        private fun kindOf(chat: ChatRow): SwitcherGroupKind = when {
            chat.state == ActivityState.NEEDS_INPUT -> SwitcherGroupKind.NEEDS_YOU
            chat.interactivity == ChatInteractivity.HIDDEN -> SwitcherGroupKind.HIDDEN
            agentOnly(chat) -> SwitcherGroupKind.AGENT
            else -> when (chat.relation) {
                is ChatRelation.Fork -> SwitcherGroupKind.FORKS
                is ChatRelation.SideChat -> SwitcherGroupKind.SIDE_CHATS
                ChatRelation.ByYou -> SwitcherGroupKind.BY_YOU
                is ChatRelation.ByAgent -> SwitcherGroupKind.AGENT
                ChatRelation.Unknown -> SwitcherGroupKind.OTHER
            }
        }

        /**
         * The group's chats with each one after the chat it came from, one level deeper, when that chat is in the same group;
         * otherwise in the host's order. A loop of parents, which only a faulty host could send, is still listed in full.
         */
        private fun treeOrder(rows: List<ChatRow>): List<Pair<ChatRow, Int>> {
            val inGroup = rows.map { it.uri }.toSet()
            val children = rows.groupBy { row -> row.relation.parentChat?.takeIf { it in inGroup && it != row.uri } }
            val out = mutableListOf<Pair<ChatRow, Int>>()
            val seen = mutableSetOf<String>()
            fun visit(row: ChatRow, depth: Int) {
                if (!seen.add(row.uri)) return
                out += row to depth
                children[row.uri].orEmpty().forEach { visit(it, depth + 1) }
            }
            children[null].orEmpty().forEach { visit(it, 0) }
            rows.forEach { visit(it, 0) }
            return out
        }

        private fun relationText(chat: ChatRow, mainUri: String, titles: Map<String, String>, turnNumber: (String, String) -> Int?): String? {
            // The chat it came from, by name; the main chat goes without saying when a turn is named, and one no longer listed is "another chat".
            fun from(parent: String): String = if (parent == mainUri) "the main chat" else titles[parent] ?: "another chat"
            fun inOther(parent: String): String = if (parent == mainUri) "" else " in ${from(parent)}"
            val origin = when (val relation = chat.relation) {
                is ChatRelation.Fork -> turnNumber(relation.parentChat, relation.turnId)
                    ?.let { "Fork of turn $it${inOther(relation.parentChat)}" }
                    ?: "Fork of ${from(relation.parentChat)}"
                is ChatRelation.SideChat -> turnNumber(relation.parentChat, relation.turnId)
                    ?.let { "Side chat from turn $it${inOther(relation.parentChat)}" }
                    ?: "Side chat from ${from(relation.parentChat)}"
                is ChatRelation.ByAgent -> "Started by the agent${inOther(relation.parentChat)}"
                ChatRelation.ByYou -> "Started by you"
                ChatRelation.Unknown -> null
            }
            val access = when (chat.interactivity) {
                ChatInteractivity.READ_ONLY -> "watch only"
                ChatInteractivity.HIDDEN -> "internal to the agent"
                ChatInteractivity.FULL -> null
            }
            return listOfNotNull(origin, access).joinToString(", ").ifEmpty { null }?.replaceFirstChar { it.uppercase() }
        }
    }
}

private fun countOf(n: Int, one: String, many: String): String = "$n ${if (n == 1) one else many}"
