package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.Implementation
import com.microsoft.agenthostprotocol.generated.ListSessionsResult
import com.microsoft.agenthostprotocol.generated.SessionSummary
import dev.ahpclient.ahp.result
import dev.ahpclient.security.BlobStore
import dev.ahpclient.security.FakeBox
import dev.ahpclient.security.FakeStore
import dev.ahpclient.security.SessionListCache
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

/** A store that counts what is written to it and can be made to wait or fail, around a [FakeStore]. */
private class WatchedStore(val inner: FakeStore = FakeStore()) : BlobStore {
    var writes = 0
    var failing = false

    /** When set, a read waits for it: a disk that is slower than the network. */
    var readGate: CompletableDeferred<Unit>? = null

    override suspend fun read(): ByteArray? {
        readGate?.await()
        if (failing) throw IOException("disk unavailable")
        return inner.read()
    }

    override suspend fun write(blob: ByteArray) {
        writes++
        if (failing) throw IOException("disk full")
        inner.write(blob)
    }

    override suspend fun delete() = inner.delete()
}

/** [HostSession] with the list of sessions kept on the phone: shown at once when saved, marked stale, replaced by the host's own, and saved again as it changes. */
@OptIn(ExperimentalCoroutinesApi::class)
class HostSessionCacheTest {
    private val info = Implementation(name = "ahp-android-client-test", version = "0.0.1")
    private val key = "tunnel-1"
    private val live = Ahp.json.decodeFromString(ListSessionsResult.serializer(), fixture("live/list-sessions.json")).items
    private val saved = live.map { it.copy(title = "Saved ${it.title}") }

    private class Rig(
        val host: HostSession,
        val connector: ScriptedConnector,
        val store: WatchedStore,
        val cache: SessionListCache,
        /** Released to let the first connection be made; until then the session is connecting. */
        val gate: CompletableDeferred<Unit>,
    ) {
        val socket get() = connector.sockets.last()
    }

    private fun TestScope.rig(
        vararg scripts: HostScript,
        savedList: List<SessionSummary>? = null,
        savedFor: String = key,
        sessionKey: String? = key,
        withCache: Boolean = true,
        holdConnection: Boolean = false,
        store: WatchedStore = WatchedStore(),
    ): Rig {
        val cache = SessionListCache(FakeBox(), store)
        if (savedList != null) runBlockingSave(cache, savedFor, savedList)
        val scripted = ScriptedConnector(*scripts.ifEmpty { arrayOf(HostScript(), HostScript()) })
        val gate = CompletableDeferred<Unit>().also { if (!holdConnection) it.complete(Unit) }
        val connector = HostConnector { clientId, seq, scope ->
            gate.await()
            scripted.connect(clientId, seq, scope)
        }
        val host = HostSession(connector, backgroundScope, info, Backoff(jitter = 0.0), "client-1", cache = cache.takeIf { withCache }, cacheKey = sessionKey)
        return Rig(host, scripted, store, cache, gate)
    }

    private fun runBlockingSave(cache: SessionListCache, tunnelId: String, items: List<SessionSummary>) = runBlocking { cache.save(tunnelId, items) }

    private suspend fun HostSession.awaitOnline() = withTimeout(300_000) { connection.first { it == ConnState.Online } }

    private suspend fun Rig.start(): Rig = also {
        host.start()
        host.awaitOnline()
    }

    private fun changeATitle(rig: Rig) {
        rig.socket.serverSends(notification("root/sessionSummaryChanged", fixture("live/session-summary-changed.json")))
    }

    @Test
    fun `the saved list is shown, marked stale, before the first connection is online`() = runTest {
        val rig = rig(savedList = saved, holdConnection = true)

        rig.host.start()
        runCurrent()

        assertEquals(ConnState.Connecting, rig.host.connection.value)
        assertTrue(rig.host.root.stale.value)
        assertEquals(SessionCatalog.sorted(saved), rig.host.root.sessions.value)
    }

    @Test
    fun `the host's own list replaces it and clears the mark`() = runTest {
        val rig = rig(savedList = saved, holdConnection = true)
        rig.host.start()
        runCurrent()

        rig.gate.complete(Unit)
        rig.host.awaitOnline()

        assertFalse(rig.host.root.stale.value)
        assertEquals(SessionCatalog.sorted(live), rig.host.root.sessions.value)
    }

    @Test
    fun `it is still stale while the connection is made but the list has not come`() = runTest {
        val rig = rig(savedList = saved, holdConnection = true)
        rig.host.start()

        advanceTimeBy(60_000)

        assertTrue(rig.host.root.stale.value)
        assertEquals(0, rig.connector.connects.size)
    }

    @Test
    fun `a host with no saved list starts empty and not stale`() = runTest {
        val rig = rig(holdConnection = true)

        rig.host.start()
        runCurrent()

        assertFalse(rig.host.root.stale.value)
        assertEquals(emptyList(), rig.host.root.sessions.value)
    }

    @Test
    fun `a list saved for another host is not shown`() = runTest {
        val rig = rig(savedList = saved, savedFor = "tunnel-2", holdConnection = true)

        rig.host.start()
        runCurrent()

        assertFalse(rig.host.root.stale.value)
        assertEquals(emptyList(), rig.host.root.sessions.value)
        assertNull(rig.store.inner.blob, "and it is dropped")
    }

    @Test
    fun `without a cache, or without a key, nothing is read or written`() = runTest {
        val noCache = rig(savedList = saved, withCache = false, holdConnection = true)
        val noKey = rig(savedList = saved, sessionKey = null)

        noCache.host.start()
        noKey.start()
        advanceTimeBy(60_000)

        assertFalse(noCache.host.root.stale.value)
        assertFalse(noKey.host.root.stale.value)
        assertEquals(1, noKey.store.writes, "only the list put there by the test")
    }

    @Test
    fun `a live list that comes before the disk answers is not replaced by the old one`() = runTest {
        val store = WatchedStore().also { it.readGate = CompletableDeferred() }
        val rig = rig(savedList = saved, store = store).start()

        store.readGate!!.complete(Unit)
        runCurrent()

        assertFalse(rig.host.root.stale.value)
        assertEquals(SessionCatalog.sorted(live), rig.host.root.sessions.value)
    }

    @Test
    fun `the list is saved five seconds after it last changed`() = runTest {
        val rig = rig().start()
        val writes = rig.store.writes

        advanceTimeBy(4_999)
        assertEquals(writes, rig.store.writes, "not yet")
        advanceTimeBy(2)

        assertEquals(writes + 1, rig.store.writes)
        assertEquals(live.toSet(), rig.cache.load(key)?.toSet())
    }

    @Test
    fun `the host's list is saved five seconds after it came even when the connection is not yet online`() = runTest {
        val unanswered = mutableListOf<Pair<Long?, String>>()
        val slow = HostScript(onTerminalSubscribe = { id, channel -> unanswered += id to channel; null })
        val rig = rig(slow, savedList = saved)
        rig.host.openTerminal(TERMINAL_A)
        rig.host.openTerminal("ahp-terminal:/t2")

        rig.host.start()
        advanceTimeBy(3_000)
        assertEquals(1, unanswered.size)
        val (id, channel) = unanswered.single()
        rig.socket.serverSends(result(id, """{"snapshot":${terminalSnapshotJson(channel, SNAPSHOT_SEQ)}}"""))
        advanceTimeBy(2_500)

        assertEquals(2, unanswered.size, "the second subscription is still waiting for the host")
        assertEquals(ConnState.Connecting, rig.host.connection.value)
        assertTrue(rig.host.root.hasLiveCatalog)
        assertEquals(live.toSet(), rig.cache.load(key)?.toSet(), "the host's list replaced the old one on disk")
    }

    @Test
    fun `another change starts the five seconds again`() = runTest {
        val rig = rig().start()
        advanceTimeBy(3_000)

        changeATitle(rig)
        runCurrent()
        advanceTimeBy(4_900)
        assertEquals(0, rig.store.writes, "the first five seconds would be up by now")
        advanceTimeBy(200)

        assertEquals(1, rig.store.writes)
        val kept = assertNotNull(rig.cache.load(key))
        assertEquals(ActivityState.NEEDS_INPUT, kept.first { it.resource == SESSION_C }.status.activityState)
    }

    @Test
    fun `the list is saved when the session stops, without waiting out the five seconds`() = runTest {
        val rig = rig().start()
        changeATitle(rig)
        runCurrent()

        rig.host.stop()
        runCurrent()

        val kept = assertNotNull(rig.cache.load(key))
        assertEquals(ActivityState.NEEDS_INPUT, kept.first { it.resource == SESSION_C }.status.activityState)
    }

    @Test
    fun `the list is saved when the connection drops`() = runTest {
        val rig = rig().start()
        changeATitle(rig)
        runCurrent()

        rig.socket.serverCloses()
        runCurrent()

        val kept = assertNotNull(rig.cache.load(key))
        assertEquals(ActivityState.NEEDS_INPUT, kept.first { it.resource == SESSION_C }.status.activityState)
    }

    @Test
    fun `a list that has not changed since it was written is not written again`() = runTest {
        val rig = rig().start()
        advanceTimeBy(5_001)
        assertEquals(1, rig.store.writes)

        rig.socket.serverCloses()
        advanceTimeBy(5_000)
        rig.host.awaitOnline()
        rig.host.stop()
        runCurrent()

        assertEquals(1, rig.store.writes)
    }

    @Test
    fun `a list that is only the old one is never written back`() = runTest {
        val rig = rig(savedList = saved, holdConnection = true)
        val before = assertNotNull(rig.store.inner.blob).copyOf()
        val writes = rig.store.writes
        rig.host.start()

        advanceTimeBy(60_000)
        rig.host.stop()
        runCurrent()

        assertEquals(writes, rig.store.writes)
        assertContentEquals(before, rig.store.inner.blob)
    }

    @Test
    fun `a session that was stopped and forgotten writes nothing`() = runTest {
        val rig = rig().start()
        val writes = rig.store.writes

        rig.host.stopAndForget()
        advanceTimeBy(60_000)

        assertEquals(writes, rig.store.writes)
        assertNull(rig.store.inner.blob)
        assertEquals(ConnState.Stopped, rig.host.connection.value)
    }

    @Test
    fun `a cache that cannot be read or written does not disturb the connection`() = runTest {
        val store = WatchedStore().also { it.failing = true }
        val rig = rig(store = store).start()

        advanceTimeBy(30_000)

        assertEquals(ConnState.Online, rig.host.connection.value)
        assertFalse(rig.host.root.stale.value)
        assertEquals(SessionCatalog.sorted(live), rig.host.root.sessions.value)
        assertTrue(store.writes >= 1, "it was tried")
        rig.host.stop()
        runCurrent()
        assertEquals(ConnState.Stopped, rig.host.connection.value)
    }

    @Test
    fun `a cache that fails to read is the same as no cache`() = runTest {
        val store = WatchedStore().also { it.failing = true }
        val rig = rig(store = store, holdConnection = true)

        rig.host.start()
        runCurrent()

        assertFalse(rig.host.root.stale.value)
        assertEquals(emptyList(), rig.host.root.sessions.value)
    }
}
