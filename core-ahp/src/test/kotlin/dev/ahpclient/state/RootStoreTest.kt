package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ListSessionsResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class RootStoreTest {
    private val live = Ahp.json.decodeFromString(ListSessionsResult.serializer(), fixture("live/list-sessions.json")).items
    private val saved = live.filter { it.resource == SESSION_A || it.resource == SESSION_C }.map { it.copy(title = "Saved ${it.title}") }

    @Test
    fun `a store with nothing yet is not stale`() {
        val root = RootStore()

        assertFalse(root.stale.value)
        assertFalse(root.hasLiveCatalog)
    }

    @Test
    fun `seeding shows the saved rows and marks them stale`() {
        val root = RootStore()

        assertTrue(root.seedStale(saved))

        assertTrue(root.stale.value)
        assertEquals(SessionCatalog.sorted(saved), root.sessions.value)
        assertFalse(root.hasLiveCatalog, "a seed is not the host's own list")
    }

    @Test
    fun `the host's own list replaces the seed and ends stale`() {
        val root = RootStore()
        root.seedStale(saved)

        root.replaceCatalog(live)

        assertFalse(root.stale.value)
        assertTrue(root.hasLiveCatalog)
        assertEquals(SessionCatalog.sorted(live), root.sessions.value)
    }

    @Test
    fun `a notification on stale data does not end stale`() {
        val root = RootStore()
        root.seedStale(saved)
        val changed = Json.parseToJsonElement(fixture("live/session-summary-changed.json")).jsonObject

        root.applyNotification("root/sessionSummaryChanged", changed)
        root.applyNotification("root/sessionRemoved", Json.parseToJsonElement("""{"channel":"ahp-root://","session":"${saved.first().resource}"}""").jsonObject)

        assertTrue(root.stale.value)
        assertFalse(root.hasLiveCatalog)
        assertEquals(listOf(SESSION_C), root.sessions.value.map { it.resource }, "it still changes what is shown")
        assertEquals("Waiting for approval", root.sessions.value.single().activity)
    }

    @Test
    fun `a seed that comes after the host's list is ignored`() {
        val root = RootStore()
        root.replaceCatalog(live)

        assertFalse(root.seedStale(saved))

        assertFalse(root.stale.value)
        assertEquals(SessionCatalog.sorted(live), root.sessions.value)
    }

    @Test
    fun `a later list from the host keeps it live`() {
        val root = RootStore()
        root.seedStale(saved)
        root.replaceCatalog(live)

        root.replaceCatalog(live.take(1))

        assertFalse(root.stale.value)
        assertEquals(1, root.sessions.value.size)
    }
}
