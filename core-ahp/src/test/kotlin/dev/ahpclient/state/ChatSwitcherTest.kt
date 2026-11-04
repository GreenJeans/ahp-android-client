package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ChatInteractivity
import com.microsoft.agenthostprotocol.generated.ChatState
import com.microsoft.agenthostprotocol.generated.SessionState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The chat switcher (U5): the main chat on its own, the others grouped by how they came about, waiting ones first. */
class ChatSwitcherTest {
    private val now = Instant.parse("2026-10-26T10:30:00.000Z")
    private val main = "ahp-chat:/main"

    private fun chat(
        uri: String,
        title: String = uri.substringAfterLast('/'),
        relation: ChatRelation = ChatRelation.Unknown,
        state: ActivityState = ActivityState.IDLE,
        isRead: Boolean = true,
        isArchived: Boolean = false,
        interactivity: ChatInteractivity = ChatInteractivity.FULL,
    ) = ChatRow(uri, title, state, null, "", isMain = uri == main, isRead = isRead, isArchived = isArchived, relation = relation, interactivity = interactivity)

    private val mainChat = chat(main, "Main")
    private fun fork(uri: String, of: String = main, turn: String = "t1", state: ActivityState = ActivityState.IDLE) =
        chat(uri, relation = ChatRelation.Fork(of, turn), state = state)

    private fun ChatSwitcher.kinds() = groups.map { it.kind }
    private fun ChatSwitcher.uris(kind: SwitcherGroupKind) = groups.first { it.kind == kind }.rows.map { it.uri }

    @Test
    fun `a session with only its main chat has no other chats, so no control`() {
        val switcher = ChatSwitcher.of(listOf(mainChat), shownChat = main)

        assertEquals(main, switcher.main?.uri)
        assertEquals(0, switcher.otherCount)
        assertEquals(emptyList(), switcher.groups)
        assertFalse(switcher.marked)
    }

    @Test
    fun `a session with no chats has neither a main chat nor groups`() {
        assertEquals(ChatSwitcher.EMPTY, ChatSwitcher.of(emptyList(), shownChat = null))
    }

    @Test
    fun `the main chat is the one marked main wherever the host lists it, else the first`() {
        val listedSecond = ChatSwitcher.of(listOf(chat("ahp-chat:/a"), mainChat), shownChat = null)
        assertEquals(main, listedSecond.main?.uri)
        assertEquals(listOf("ahp-chat:/a"), listedSecond.others.map { it.uri })

        val noMain = ChatSwitcher.of(listOf(chat("ahp-chat:/a"), chat("ahp-chat:/b")), shownChat = null)
        assertEquals("ahp-chat:/a", noMain.main?.uri)
        assertEquals(listOf("ahp-chat:/b"), noMain.others.map { it.uri })
    }

    @Test
    fun `chats are grouped by relation, in a fixed order, with the agent's own last and folded`() {
        val switcher = ChatSwitcher.of(
            listOf(
                mainChat,
                chat("ahp-chat:/tool", relation = ChatRelation.ByAgent(main)),
                chat("ahp-chat:/unknown"),
                chat("ahp-chat:/mine", relation = ChatRelation.ByYou),
                chat("ahp-chat:/side", relation = ChatRelation.SideChat(main, "t2")),
                fork("ahp-chat:/fork"),
            ),
            shownChat = main,
        )

        assertEquals(
            listOf(SwitcherGroupKind.FORKS, SwitcherGroupKind.SIDE_CHATS, SwitcherGroupKind.BY_YOU, SwitcherGroupKind.OTHER, SwitcherGroupKind.AGENT),
            switcher.kinds(),
        )
        assertEquals(listOf(false, false, false, false, true), switcher.groups.map { it.collapsedByDefault })
        assertEquals(listOf("Forks", "Side chats", "Started by you", "Other chats", "Chats the agent uses"), switcher.groups.map { it.title })
        assertEquals(5, switcher.otherCount)
        assertEquals("5 other chats", switcher.label)
    }

    @Test
    fun `chats waiting on the person come first, whatever their relation, and are never folded away`() {
        val switcher = ChatSwitcher.of(
            listOf(
                mainChat,
                fork("ahp-chat:/quiet"),
                chat("ahp-chat:/tool", relation = ChatRelation.ByAgent(main), state = ActivityState.NEEDS_INPUT),
                fork("ahp-chat:/asking", state = ActivityState.NEEDS_INPUT),
            ),
            shownChat = main,
        )

        assertEquals(SwitcherGroupKind.NEEDS_YOU, switcher.groups.first().kind)
        assertFalse(switcher.groups.first().collapsedByDefault)
        assertEquals(listOf("ahp-chat:/tool", "ahp-chat:/asking"), switcher.uris(SwitcherGroupKind.NEEDS_YOU))
        assertEquals(listOf("ahp-chat:/quiet"), switcher.uris(SwitcherGroupKind.FORKS))
        assertTrue(SwitcherGroupKind.AGENT !in switcher.kinds(), "the waiting agent chat is not also in the folded group")
        // The relation is still said on a waiting row, so it is clear where it came from.
        assertEquals("Started by the agent", switcher.groups.first().rows[0].relation)
        assertEquals(2, switcher.needsYouCount)
        assertEquals("3 other chats, 2 waiting on you", switcher.spoken)
    }

    @Test
    fun `watch-only and internal chats count as the agent's own, whatever their origin`() {
        val switcher = ChatSwitcher.of(
            listOf(
                mainChat,
                chat("ahp-chat:/worker", relation = ChatRelation.Fork(main, "t1"), interactivity = ChatInteractivity.READ_ONLY),
                chat("ahp-chat:/hidden", interactivity = ChatInteractivity.HIDDEN),
            ),
            shownChat = main,
        )

        assertEquals(listOf(SwitcherGroupKind.AGENT), switcher.kinds())
        assertEquals(listOf("Fork of the main chat, watch only", "Internal to the agent"), switcher.others.map { it.relation })
    }

    @Test
    fun `a chat of an origin this app does not know is listed under other chats with no relation`() {
        val state = Ahp.json.decodeFromString(
            SessionState.serializer(),
            """{"provider":"p","title":"S","status":1,"lifecycle":"ready","activeClients":[],"defaultChat":"$main",
                "chats":[
                    {"resource":"$main","title":"Main","status":33,"modifiedAt":"2026-10-26T10:29:00.000Z"},
                    {"resource":"ahp-chat:/odd","title":"Odd","status":33,"modifiedAt":"2026-10-26T10:29:00.000Z","origin":{"kind":"teleported","from":"x"}},
                    {"resource":"ahp-chat:/none","title":"None","status":33,"modifiedAt":"2026-10-26T10:29:00.000Z"}
                ]}""",
        )
        val switcher = ChatSwitcher.of(SessionDetail.from(state, now).chats, shownChat = main)

        assertEquals(listOf(SwitcherGroupKind.OTHER), switcher.kinds())
        assertEquals(listOf("ahp-chat:/odd", "ahp-chat:/none"), switcher.uris(SwitcherGroupKind.OTHER))
        assertEquals(listOf(null, null), switcher.others.map { it.relation })
    }

    @Test
    fun `the host's origin and interactivity are read into each chat row`() {
        val state = Ahp.json.decodeFromString(
            SessionState.serializer(),
            """{"provider":"p","title":"S","status":1,"lifecycle":"ready","activeClients":[],"defaultChat":"$main",
                "chats":[
                    {"resource":"$main","title":"Main","status":33,"modifiedAt":"2026-10-26T10:29:00.000Z","origin":{"kind":"user"}},
                    {"resource":"ahp-chat:/f","title":"F","status":33,"modifiedAt":"2026-10-26T10:29:00.000Z","origin":{"kind":"fork","chat":"$main","turnId":"t3"}},
                    {"resource":"ahp-chat:/s","title":"S","status":33,"modifiedAt":"2026-10-26T10:29:00.000Z","origin":{"kind":"sideChat","chat":"$main","turnId":"t4","selection":{"text":"this bit"}}},
                    {"resource":"ahp-chat:/t","title":"T","status":33,"modifiedAt":"2026-10-26T10:29:00.000Z","origin":{"kind":"tool","chat":"ahp-chat:/f","toolCallId":"c1"},"interactivity":"read-only"}
                ]}""",
        )
        val chats = SessionDetail.from(state, now).chats

        assertEquals(
            listOf(ChatRelation.ByYou, ChatRelation.Fork(main, "t3"), ChatRelation.SideChat(main, "t4"), ChatRelation.ByAgent("ahp-chat:/f")),
            chats.map { it.relation },
        )
        assertEquals(listOf(ChatInteractivity.FULL, ChatInteractivity.FULL, ChatInteractivity.FULL, ChatInteractivity.READ_ONLY), chats.map { it.interactivity })
    }

    @Test
    fun `a fork of a fork sits under its parent, one level deeper`() {
        val switcher = ChatSwitcher.of(
            listOf(
                mainChat,
                fork("ahp-chat:/grandchild", of = "ahp-chat:/child"),
                fork("ahp-chat:/a"),
                fork("ahp-chat:/child", of = "ahp-chat:/a"),
                fork("ahp-chat:/b"),
            ),
            shownChat = main,
        )
        val rows = switcher.groups.single().rows

        assertEquals(listOf("ahp-chat:/a", "ahp-chat:/child", "ahp-chat:/grandchild", "ahp-chat:/b"), rows.map { it.uri })
        assertEquals(listOf(0, 1, 2, 0), rows.map { it.depth })
        assertEquals("Fork of a", rows[1].relation)
    }

    @Test
    fun `a chat whose parent is in another group, or gone, starts its own branch and names where it came from`() {
        val switcher = ChatSwitcher.of(
            listOf(
                mainChat,
                chat("ahp-chat:/side", title = "Notes", relation = ChatRelation.SideChat(main, "t1")),
                fork("ahp-chat:/fork-of-side", of = "ahp-chat:/side"),
                fork("ahp-chat:/orphan", of = "ahp-chat:/removed"),
            ),
            shownChat = main,
        )

        assertEquals(listOf(0, 0), switcher.groups.first { it.kind == SwitcherGroupKind.FORKS }.rows.map { it.depth })
        assertEquals(listOf("Fork of Notes", "Fork of another chat"), switcher.groups.first { it.kind == SwitcherGroupKind.FORKS }.rows.map { it.relation })
    }

    @Test
    fun `a loop of parents from a faulty host still lists every chat once`() {
        val switcher = ChatSwitcher.of(
            listOf(mainChat, fork("ahp-chat:/x", of = "ahp-chat:/y"), fork("ahp-chat:/y", of = "ahp-chat:/x"), fork("ahp-chat:/self", of = "ahp-chat:/self")),
            shownChat = main,
        )

        assertEquals(setOf("ahp-chat:/x", "ahp-chat:/y", "ahp-chat:/self"), switcher.others.map { it.uri }.toSet())
        assertEquals(3, switcher.otherCount)
    }

    @Test
    fun `the turn is named when it can be counted, and the parent chat is named when it is not the main one`() {
        val numbers = mapOf((main to "t3") to 3, ("ahp-chat:/a" to "t9") to 2)
        val switcher = ChatSwitcher.of(
            listOf(
                mainChat,
                fork("ahp-chat:/a", turn = "t3"),
                chat("ahp-chat:/side", relation = ChatRelation.SideChat("ahp-chat:/a", "t9")),
                fork("ahp-chat:/unknown-turn", turn = "t-old"),
                chat("ahp-chat:/tool", relation = ChatRelation.ByAgent("ahp-chat:/a")),
            ),
            shownChat = main,
            turnNumber = { chat, turn -> numbers[chat to turn] },
        )

        assertEquals(
            listOf("Fork of turn 3", "Fork of the main chat", "Side chat from turn 2 in a", "Started by the agent in a"),
            switcher.others.map { it.relation },
        )
    }

    @Test
    fun `unread chats are counted and marked, but not the one on screen, and a folded group still says how many`() {
        val switcher = ChatSwitcher.of(
            listOf(
                chat(main, "Main", isRead = false),
                fork("ahp-chat:/seen"),
                chat("ahp-chat:/new", relation = ChatRelation.Fork(main, "t1"), isRead = false),
                chat("ahp-chat:/open", relation = ChatRelation.Fork(main, "t1"), isRead = false),
                chat("ahp-chat:/tool1", relation = ChatRelation.ByAgent(main), isRead = false),
                chat("ahp-chat:/tool2", relation = ChatRelation.ByAgent(main)),
            ),
            shownChat = "ahp-chat:/open",
        )

        assertEquals(2, switcher.unreadCount)
        assertTrue(switcher.marked)
        assertTrue(switcher.main!!.unread, "the main chat is not on screen, so it shows as unread")
        assertEquals(listOf(false, true, false), switcher.groups.first { it.kind == SwitcherGroupKind.FORKS }.rows.map { it.unread })
        assertTrue(switcher.others.single { it.uri == "ahp-chat:/open" }.shown)
        assertEquals("2 chats, 1 unread", switcher.groups.first { it.kind == SwitcherGroupKind.AGENT }.summary)
        assertEquals("5 other chats, 2 unread", switcher.spoken)
    }

    @Test
    fun `a waiting main chat is marked on its own, not counted among the others`() {
        val switcher = ChatSwitcher.of(listOf(chat(main, "Main", state = ActivityState.NEEDS_INPUT), fork("ahp-chat:/f")), shownChat = "ahp-chat:/f")

        assertTrue(switcher.main!!.needsYou)
        assertEquals("Main, Main chat, Needs you", switcher.main!!.spoken)
        assertEquals(0, switcher.needsYouCount)
        assertFalse(switcher.marked)
        assertEquals("1 other chat", switcher.spoken)
    }

    @Test
    fun `archived chats other than the main one are left out unless asked for`() {
        val chats = listOf(chat(main, "Main", isArchived = true), fork("ahp-chat:/live"), chat("ahp-chat:/old", relation = ChatRelation.Fork(main, "t1"), isArchived = true))

        assertEquals(listOf("ahp-chat:/live"), ChatSwitcher.of(chats, shownChat = main).others.map { it.uri })
        assertEquals(main, ChatSwitcher.of(chats, shownChat = main).main?.uri)
        val all = ChatSwitcher.of(chats, shownChat = main, showArchived = true)
        assertEquals(listOf("ahp-chat:/live", "ahp-chat:/old"), all.others.map { it.uri })
        assertEquals("old, Fork of the main chat, Archived", all.others[1].spoken)
    }

    @Test
    fun `a row's spoken description carries what the dot and the indent show`() {
        val row = ChatSwitcher.of(
            listOf(mainChat, chat("ahp-chat:/f", "Try B", ChatRelation.Fork(main, "t1"), ActivityState.NEEDS_INPUT, isRead = false)),
            shownChat = "ahp-chat:/f",
        ).others.single()

        assertEquals("Try B, Fork of the main chat, Needs you, Shown", row.spoken)
        assertTrue(row.marked)
    }

    @Test
    fun `a chat the host lists twice is shown once`() {
        val switcher = ChatSwitcher.of(listOf(mainChat, fork("ahp-chat:/f"), fork("ahp-chat:/f")), shownChat = main)

        assertEquals(1, switcher.otherCount)
    }

    private fun chatState(turns: List<String>, cursor: String? = null, active: String? = null): ChatState = Ahp.json.decodeFromString(
        ChatState.serializer(),
        """{"resource":"$main","title":"","status":1,"modifiedAt":"2026-10-22T10:00:00.000Z",
            "turns":[${turns.joinToString(",") { """{"id":"$it","message":{"text":"x","origin":{"kind":"user"}},"responseParts":[],"state":"complete"}""" }}]
            ${cursor?.let { ""","turnsNextCursor":"$it"""" }.orEmpty()}
            ${active?.let { ""","activeTurn":{"id":"$it","startedAt":"2026-10-22T10:00:00.000Z","message":{"text":"y","origin":{"kind":"user"}},"responseParts":[]}""" }.orEmpty()}}""",
    )

    @Test
    fun `a turn is counted from the start only when every turn is held`() {
        val all = chatState(listOf("t1", "t2", "t3"), active = "t4")
        assertEquals(1, ChatSwitcher.turnNumberIn(all, "t1"))
        assertEquals(3, ChatSwitcher.turnNumberIn(all, "t3"))
        assertEquals(4, ChatSwitcher.turnNumberIn(all, "t4"), "the turn under way comes after the finished ones")
        assertNull(ChatSwitcher.turnNumberIn(all, "t-missing"))

        // Older turns not yet loaded: the place from the start is not known, so no number is made up.
        assertNull(ChatSwitcher.turnNumberIn(chatState(listOf("t8", "t9"), cursor = "older"), "t9"))
    }
}
