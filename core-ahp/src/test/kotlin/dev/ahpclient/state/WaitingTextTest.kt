package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.SessionInputRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The words a notification uses for what a session is waiting on: one thing as it is, several as a count and the first. */
class WaitingTextTest {
    private fun requests(json: String): List<SessionInputRequest> =
        Ahp.json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(SessionInputRequest.serializer()), json)

    private fun tool(id: String, intention: String) =
        """{"kind":"toolConfirmation","id":"$id","chat":"c","turnId":"t","toolCall":{"status":"pending-confirmation","toolCallId":"$id","toolName":"p",
            "displayName":"Run command","intention":"$intention","invocationMessage":"x"}}"""

    private val question = """{"kind":"chatInput","id":"q1","chat":"c","request":{"id":"q","message":"Which season?"}}"""

    @Test
    fun `nothing waiting has no words`() {
        assertNull(WaitingText.of(emptyList()))
    }

    @Test
    fun `one thing is said as it is`() {
        assertEquals("Run command: Run it", WaitingText.of(requests("[${tool("a", "Run it")}]")))
        assertEquals("Which season?", WaitingText.of(requests("[$question]")))
    }

    @Test
    fun `several approvals are counted and the first is named`() {
        val text = WaitingText.of(requests("[${tool("a", "Run one")},${tool("b", "Run two")},${tool("c", "Run three")}]"))

        assertEquals("3 approvals waiting. First: Run command: Run one. Open the app to decide.", text)
    }

    @Test
    fun `a mix of kinds is called requests, not approvals`() {
        val text = WaitingText.of(requests("[$question,${tool("a", "Run one")}]"))

        assertEquals("2 requests waiting. First: Which season?. Open the app to decide.", text)
    }

    private fun sessionWith(inputNeeded: String, chats: String = """[{"resource":"ahp-chat:/c1","title":"Main","status":0,"createdAt":"2026-10-22T10:00:00Z","modifiedAt":"2026-10-22T10:00:00Z"}]"""): com.microsoft.agenthostprotocol.generated.SessionState =
        Ahp.json.decodeFromString(
            com.microsoft.agenthostprotocol.generated.SessionState.serializer(),
            """{"resource":"ahp-session:/s1","provider":"p","title":"S","status":2,"createdAt":"2026-10-22T10:00:00Z","modifiedAt":"2026-10-22T10:00:00Z","lifecycle":"ready","activeClients":[],"chats":$chats,"inputNeeded":$inputNeeded}""",
        )

    @Test
    fun `a single free-text question in a listed chat can be answered from the notification, and anything else cannot`() {
        val textQuestion = """[{"kind":"chatInput","id":"i1","chat":"ahp-chat:/c1","request":{"id":"req-1","questions":[{"kind":"text","id":"q1","title":"Branch","message":"Which branch?"}]}}]"""
        assertEquals(QuestionTarget("ahp-chat:/c1", "req-1"), QuestionTarget.from(sessionWith(textQuestion)))

        val twoQuestions = """[{"kind":"chatInput","id":"i1","chat":"ahp-chat:/c1","request":{"id":"req-1","questions":[{"kind":"text","id":"q1","title":"One","message":"One"},{"kind":"text","id":"q2","title":"Two","message":"Two"}]}}]"""
        assertNull(QuestionTarget.from(sessionWith(twoQuestions)))

        val singleSelect = """[{"kind":"chatInput","id":"i1","chat":"ahp-chat:/c1","request":{"id":"req-1","questions":[{"kind":"single-select","id":"q1","title":"Pick","message":"Pick","options":[{"id":"a","label":"A"}]}]}}]"""
        assertNull(QuestionTarget.from(sessionWith(singleSelect)))

        val unlistedChat = """[{"kind":"chatInput","id":"i1","chat":"ahp-chat:/other","request":{"id":"req-1","questions":[{"kind":"text","id":"q1","title":"Which","message":"Which?"}]}}]"""
        assertNull(QuestionTarget.from(sessionWith(unlistedChat)))

        val longId = "x".repeat(QuestionTarget.MAX_REQUEST_ID + 1)
        val tooLongReq = """[{"kind":"chatInput","id":"i1","chat":"ahp-chat:/c1","request":{"id":"$longId","questions":[{"kind":"text","id":"q1","title":"Which","message":"Which?"}]}}]"""
        assertNull(QuestionTarget.from(sessionWith(tooLongReq)))
    }

    @Test
    fun `the Quick Settings tile is inactive when monitoring is off and counts unarchived waiting sessions when online`() {
        assertEquals(
            MonitorTileState(active = false, label = "AHP Monitor", subtitle = "Monitoring off"),
            MonitorTileState.from(null, emptyList(), online = false),
        )

        val reconnecting = MonitorStatus("Monitoring host", "Connection lost. Reconnecting…")
        assertEquals(
            MonitorTileState(active = true, label = "AHP Monitor", subtitle = "Connection lost. Reconnecting…"),
            MonitorTileState.from(reconnecting, emptyList(), online = false),
        )

        val base = Ahp.json.decodeFromString(com.microsoft.agenthostprotocol.generated.ListSessionsResult.serializer(), fixture("live/list-sessions.json")).items.first()
        val waiting1 = base.copy(resource = "ahp-session:/w1", status = com.microsoft.agenthostprotocol.generated.SessionStatus.INPUT_NEEDED)
        val waiting2 = base.copy(resource = "ahp-session:/w2", status = com.microsoft.agenthostprotocol.generated.SessionStatus.INPUT_NEEDED)
        val archivedWaiting = base.copy(
            resource = "ahp-session:/w3",
            status = com.microsoft.agenthostprotocol.generated.SessionStatus.INPUT_NEEDED or com.microsoft.agenthostprotocol.generated.SessionStatus.IS_ARCHIVED,
        )
        val onlineStatus = MonitorStatus("Monitoring host", "2 active sessions · 2 need input")
        assertEquals(
            MonitorTileState(active = true, label = "AHP Monitor", subtitle = "2 waiting"),
            MonitorTileState.from(onlineStatus, listOf(waiting1, waiting2, archivedWaiting), online = true),
        )
        assertEquals(
            MonitorTileState(active = true, label = "AHP Monitor", subtitle = "No sessions waiting"),
            MonitorTileState.from(onlineStatus, listOf(archivedWaiting), online = true),
        )
    }
}
