package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Things the pinned AHP library (1.0.0) cannot do, written as tests so they are not forgotten. Each one says what is
 * missing and what it blocks; when a library update makes one of these fail, the thing it blocks can be built.
 */
class LibraryGapsTest {
    // The library's `SnapshotStateSerializer` picks a snapshot's kind from the keys it holds, and checks `containsKey("lifecycle")` before `containsKey("content")`: a session's lifecycle is a
    // word, but `TerminalState.lifecycle` is a required object (`TerminalLifecycle`), so every terminal snapshot is taken for a session and fails to decode. A client built on
    // this library cannot subscribe to a terminal, and a reconnect that includes one would fail as a whole.
    // The terminal viewer works around it: `AhpConnection.subscribeTerminal` reads that one reply by hand, and a terminal is never
    // named in `initialize` or `reconnect`. When this test starts failing the library is fixed, and that workaround can go.
    @Test
    fun `a terminal snapshot does not decode, so terminals are subscribed by hand`() {
        val outcome = runCatching { Ahp.json.decodeFromString(Snapshot.serializer(), terminalSnapshotJson("ahp-terminal:/t1", 5)) }

        assertTrue(outcome.isFailure, "terminal snapshots decode now: `AhpConnection.subscribeTerminal` and `AuxStore.byHand` can be removed")
    }

    @Test
    fun `a changeset snapshot does decode, which is what the changeset viewer relies on`() {
        val snapshot = Ahp.json.decodeFromString(Snapshot.serializer(), changesetSnapshotJson("ahp-changeset:/c1", 5))

        assertEquals("ahp-changeset:/c1", snapshot.resource)
    }
}
