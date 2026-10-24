package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.changesetReducer
import com.microsoft.agenthostprotocol.generated.ActionEnvelope
import com.microsoft.agenthostprotocol.generated.ChangesetState
import com.microsoft.agenthostprotocol.generated.Implementation
import com.microsoft.agenthostprotocol.generated.Snapshot
import com.microsoft.agenthostprotocol.generated.SnapshotState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Changesets through [HostSession]: read only, subscribed like chats are, and kept current. */
@OptIn(ExperimentalCoroutinesApi::class)
class HostSessionChannelTest {
    private val info = Implementation(name = "ahp-android-client-test", version = "0.0.1")

    private class Rig(val connector: ScriptedConnector, val host: HostSession) {
        val socket get() = connector.sockets.last()

        fun sent(method: String) = socket.sent.filter { it["method"]?.jsonPrimitive?.content == method }.map { it["params"]!!.jsonObject["channel"]!!.jsonPrimitive.content }
    }

    private fun TestScope.rig(vararg scripts: HostScript): Rig {
        val connector = ScriptedConnector(*scripts)
        return Rig(connector, HostSession(connector, backgroundScope, info, Backoff(), "client-1"))
    }

    private suspend fun HostSession.awaitState(match: (ConnState) -> Boolean) = withTimeout(300_000) { connection.first(match) }

    private suspend fun Rig.start(): Rig = also {
        host.start()
        host.awaitState { it == ConnState.Online }
    }

    private fun TestScope.settle() {
        advanceTimeBy(100)
        runCurrent()
    }

    private fun addFile(path: String) = """{"type":"changeset/fileSet","file":${changesetFileJson(path, 5, 2, before = false)}}"""

    @Test
    fun `an open changeset is seeded from its snapshot and follows a file being added`() = runTest {
        val rig = rig(HostScript(changesetState = changesetStateJson("ready", changesetFileJson("a")))).start()

        val store = rig.host.openChangeset(CHANGESET_A)
        runCurrent()
        assertEquals(1, ChangesetView.from(store.state.value!!).rows.size)
        assertEquals(listOf(CHANGESET_A), rig.sent("subscribe").filter { it == CHANGESET_A })

        rig.socket.serverSends(actionFrame(CHANGESET_A, addFile("b"), SNAPSHOT_SEQ + 1))
        settle()

        assertEquals(listOf("/a", "/b"), ChangesetView.from(store.state.value!!).rows.map { it.path })
    }

    @Test
    fun `an update at or before the snapshot is not applied twice`() = runTest {
        val rig = rig(HostScript()).start()
        val store = rig.host.openChangeset(CHANGESET_A)
        runCurrent()

        rig.socket.serverSends(actionFrame(CHANGESET_A, addFile("old"), SNAPSHOT_SEQ))
        settle()

        assertEquals(emptyList(), ChangesetView.from(store.state.value!!).rows)
    }

    private fun newStore() = ChannelStore<ChangesetState>(CHANGESET_A, { (it as? SnapshotState.Changeset)?.value }, ::changesetReducer)

    @Test
    fun `updates that arrive before the snapshot are held and applied in order after it`() {
        val store = newStore()
        store.apply(envelope(addFile("second"), SNAPSHOT_SEQ + 2))
        store.apply(envelope(addFile("first"), SNAPSHOT_SEQ + 1))
        assertNull(store.state.value)

        store.seed(Ahp.json.decodeFromString(Snapshot.serializer(), changesetSnapshotJson(CHANGESET_A, SNAPSHOT_SEQ)))

        assertEquals(listOf("/first", "/second"), ChangesetView.from(store.state.value!!).rows.map { it.path })
    }

    @Test
    fun `a snapshot of another kind seeds nothing and the store keeps waiting`() {
        val store = newStore()

        val seeded = store.seed(Ahp.json.decodeFromString(Snapshot.serializer(), sessionSnapshot(SESSION_C, SNAPSHOT_SEQ)))

        assertFalse(seeded)
        assertNull(store.state.value)
        assertTrue(store.needsSubscribe)
    }

    @Test
    fun `closing a changeset unsubscribes only when the last opener leaves`() = runTest {
        val rig = rig(HostScript()).start()
        rig.host.openChangeset(CHANGESET_A)
        rig.host.openChangeset(CHANGESET_A)
        runCurrent()

        rig.host.closeChannel(CHANGESET_A)
        assertEquals(emptyList(), rig.sent("unsubscribe"))

        rig.host.closeChannel(CHANGESET_A)
        assertEquals(listOf(CHANGESET_A), rig.sent("unsubscribe"))
    }

    @Test
    fun `a changeset the host no longer has is reported gone, not left loading`() = runTest {
        val rig = rig(HostScript(missingSessions = setOf(CHANGESET_A))).start()

        val store = rig.host.openChangeset(CHANGESET_A)
        runCurrent()

        assertNull(store.state.value)
        assertIs<ChannelGoneException>(store.error.value)
    }

    @Test
    fun `an open changeset subscribes again after the connection drops`() = runTest {
        val rig = rig(HostScript(), HostScript()).start()
        val store = rig.host.openChangeset(CHANGESET_A)
        runCurrent()
        assertEquals(ChangesetPhase.READY, ChangesetView.from(store.state.value!!).phase)

        rig.socket.serverCloses(1011)
        rig.host.awaitState { it is ConnState.Reconnecting }
        rig.host.awaitState { it == ConnState.Online }
        runCurrent()

        assertEquals(2, rig.connector.sockets.size)
        val asked = rig.connector.sockets.last().sent.joinToString { it.toString() }
        assertTrue(CHANGESET_A in asked, "the new connection asks for it again: $asked")
    }

    @Test
    fun `watching a changeset sends nothing outside the read tier`() = runTest {
        val rig = rig(HostScript()).start()
        rig.host.openChangeset(CHANGESET_A)
        runCurrent()
        rig.socket.serverSends(actionFrame(CHANGESET_A, addFile("b"), SNAPSHOT_SEQ + 1))
        settle()
        rig.host.closeChannel(CHANGESET_A)

        val allowed = setOf("initialize", "reconnect", "ping", "subscribe", "unsubscribe", "listSessions", "fetchTurns")
        assertEquals(emptySet(), rig.socket.sentMethods.toSet() - allowed)
        assertFalse(rig.host.canInteract.value)
    }

    private fun envelope(actionJson: String, seq: Long) =
        Ahp.json.decodeFromString(ActionEnvelope.serializer(), """{"channel":"$CHANGESET_A","action":$actionJson,"serverSeq":$seq}""")
}
