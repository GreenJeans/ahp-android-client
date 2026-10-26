package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ActionEnvelope
import com.microsoft.agenthostprotocol.generated.InitializeResult
import com.microsoft.agenthostprotocol.generated.ListSessionsResult
import com.microsoft.agenthostprotocol.generated.PartialSessionSummary
import com.microsoft.agenthostprotocol.generated.SessionStatus
import com.microsoft.agenthostprotocol.generated.SessionSummaryChangedParams
import com.microsoft.agenthostprotocol.generated.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionCatalogTest {
    private val sessions = Ahp.json.decodeFromString(ListSessionsResult.serializer(), fixture("live/list-sessions.json")).items

    private fun byTitle(title: String) = sessions.first { it.title == title }

    @Test
    fun `the redacted live fixtures decode with the AHP 1_0 types`() {
        assertEquals(6, sessions.size)
        val init = Ahp.json.decodeFromString(InitializeResult.serializer(), fixture("live/initialize-result.json"))
        assertEquals("1.0.0", init.protocolVersion)
        assertEquals(1, init.snapshots.size)
        val changed = Ahp.json.decodeFromString(SessionSummaryChangedParams.serializer(), fixture("live/session-summary-changed.json"))
        assertEquals(SESSION_C, changed.session)
    }

    @Test
    fun `needs input first, then newest first`() {
        val order = SessionCatalog.sorted(sessions).map { it.title }

        assertEquals(
            listOf("Session E (synthetic: input needed)", "Session F (synthetic: error)", "Session D", "Session C", "Session B", "Session A"),
            order,
        )
    }

    @Test
    fun `sorting does not depend on input order`() {
        assertEquals(SessionCatalog.sorted(sessions), SessionCatalog.sorted(sessions.reversed()))
    }

    @Test
    fun `merge applies only the fields present`() {
        val before = byTitle("Session C")

        val after = SessionCatalog.merge(before, PartialSessionSummary(activity = "Running tests"))

        assertEquals("Running tests", after.activity)
        assertEquals(before.copy(activity = "Running tests"), after)
    }

    @Test
    fun `merge ignores identity fields a host should not send`() {
        val before = byTitle("Session C")

        val after = SessionCatalog.merge(before, PartialSessionSummary(resource = "ahp-session:/other", provider = "other", createdAt = "1999-01-01T00:00:00.000Z", title = "Renamed"))

        assertEquals(before.resource, after.resource)
        assertEquals(before.provider, after.provider)
        assertEquals(before.createdAt, after.createdAt)
        assertEquals("Renamed", after.title)
    }

    @Test
    fun `a status change can move a session into needs input`() {
        val after = SessionCatalog.merge(byTitle("Session C"), PartialSessionSummary(status = SessionStatus.INPUT_NEEDED or SessionStatus.IS_READ))

        assertEquals(ActivityState.NEEDS_INPUT, after.status.activityState)
        assertNull(SessionCatalog.merge(byTitle("Session B"), PartialSessionSummary()).activity)
    }

    @Test
    fun `backoff doubles from one second up to the cap`() {
        val backoff = Backoff(jitter = 0.0)

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (1..7).map(backoff::delayMs))
    }

    @Test
    fun `session store holds actions that arrive before the snapshot and drops duplicates`() {
        val store = SessionStore(SESSION_C)
        fun envelope(seq: Long, title: String) =
            Ahp.json.decodeFromString(ActionEnvelope.serializer(), """{"channel":"$SESSION_C","action":${titleChanged(title)},"serverSeq":$seq}""")
        store.apply(envelope(11, "early and newer"))
        store.apply(envelope(9, "early but already in the snapshot"))
        assertNull(store.state.value)

        assertTrue(store.seed(Ahp.json.decodeFromString(Snapshot.serializer(), sessionSnapshot(SESSION_C, fromSeq = 10, title = "snapshot"))))

        assertEquals("early and newer", store.state.value!!.title)
        store.apply(envelope(11, "duplicate of 11"))
        store.apply(envelope(10, "older than 11"))
        assertEquals("early and newer", store.state.value!!.title)
        store.apply(envelope(12, "next"))
        assertEquals("next", store.state.value!!.title)
    }

    @Test
    fun `a snapshot with no session state seeds nothing`() {
        val store = SessionStore(SESSION_C)
        val rootSnapshot = Ahp.json.decodeFromString(
            InitializeResult.serializer(),
            fixture("live/initialize-result.json"),
        ).snapshots.single()

        assertTrue(!store.seed(rootSnapshot))
        assertNull(store.state.value)
    }
}
