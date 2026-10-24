package dev.ahpclient.state

import dev.ahpclient.ahp.CapabilityDeniedException
import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.Changeset
import com.microsoft.agenthostprotocol.generated.StateAction
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject

class ChangesetReviewerTest {
    private class Rig(var allowed: Boolean = true, var files: Set<String>? = setOf("f1", "f2")) {
        val sent = mutableListOf<Pair<String, StateAction>>()
        var outcome: DispatchOutcome = DispatchOutcome.Accepted
        var failure: Exception? = null
        val reviewer = ChangesetReviewer(
            allowed = { allowed },
            reviewableFiles = { files },
            dispatch = { channel, action ->
                failure?.let { throw it }
                sent += channel to action
                outcome
            },
        )

        fun wire(index: Int = 0) = Ahp.json.encodeToJsonElement(StateAction.serializer(), sent[index].second).jsonObject.toString()
    }

    @Test
    fun `a file of the open changeset is marked on the changeset's own channel`() = runTest {
        val rig = Rig()

        assertEquals(ManageResult.Done(), rig.reviewer.setReviewed("ahp-changeset:/c", "f1", true))

        assertEquals(listOf("ahp-changeset:/c"), rig.sent.map { it.first })
        assertEquals("""{"type":"changeset/filesReviewChanged","files":["f1"],"reviewed":true}""", rig.wire())
    }

    @Test
    fun `clearing the mark sends reviewed false`() = runTest {
        val rig = Rig()

        rig.reviewer.setReviewed("ahp-changeset:/c", "f2", false)

        assertEquals("""{"type":"changeset/filesReviewChanged","files":["f2"],"reviewed":false}""", rig.wire())
    }

    @Test
    fun `nothing is sent when this connection may not look after sessions`() = runTest {
        val rig = Rig(allowed = false)

        assertEquals(ManageResult.NotAllowed, rig.reviewer.setReviewed("ahp-changeset:/c", "f1", true))

        assertTrue(rig.sent.isEmpty())
    }

    @Test
    fun `nothing is sent for a changeset that is not open and reviewable`() = runTest {
        val rig = Rig(files = null)

        assertEquals(ManageResult.NoSuchTarget, rig.reviewer.setReviewed("ahp-changeset:/c", "f1", true))

        assertTrue(rig.sent.isEmpty())
    }

    @Test
    fun `a file the changeset does not list is never sent`() = runTest {
        val rig = Rig()

        assertEquals(ManageResult.NoSuchTarget, rig.reviewer.setReviewed("ahp-changeset:/c", "somewhere/else", true))
        assertEquals(ManageResult.NoSuchTarget, rig.reviewer.setReviewed("ahp-changeset:/c", "", true))

        assertTrue(rig.sent.isEmpty())
    }

    @Test
    fun `a host that refuses or does not answer is reported as unconfirmed`() = runTest {
        val rig = Rig()
        rig.outcome = DispatchOutcome.Rejected("not reviewable")
        assertEquals(ManageResult.Unconfirmed(DispatchOutcome.Rejected("not reviewable")), rig.reviewer.setReviewed("ahp-changeset:/c", "f1", true))

        rig.outcome = DispatchOutcome.NoReply
        assertEquals(ManageResult.Unconfirmed(DispatchOutcome.NoReply), rig.reviewer.setReviewed("ahp-changeset:/c", "f1", true))
    }

    @Test
    fun `a tier that refuses the action and a lost connection are told apart`() = runTest {
        val rig = Rig()
        rig.failure = CapabilityDeniedException("dispatchAction", "changeset/filesReviewChanged")
        assertEquals(ManageResult.NotAllowed, rig.reviewer.setReviewed("ahp-changeset:/c", "f1", true))

        rig.failure = IOException("offline")
        assertIs<ManageResult.NotSent>(rig.reviewer.setReviewed("ahp-changeset:/c", "f1", true))
    }

    @Test
    fun `the action is pinned to its exact type and fields`() {
        val wire = Ahp.json.encodeToJsonElement(StateAction.serializer(), ChangesetActions.setReviewed(listOf("a", "b"), true)).jsonObject

        assertEquals("""{"type":"changeset/filesReviewChanged","files":["a","b"],"reviewed":true}""", wire.toString())
    }

    @Test
    fun `a changeset is reviewable only when the host advertises the review capability`() {
        fun reviewable(capabilities: String) = ChangesetEntry.from(
            Ahp.json.decodeFromString(Changeset.serializer(), """{"label":"x","uriTemplate":"ahp-changeset:/c","changeKind":"uncommitted"$capabilities}"""),
        ).reviewable

        assertTrue(reviewable(""","capabilities":{"review":{}}"""))
        assertFalse(reviewable(""))
        assertFalse(reviewable(""","capabilities":{}"""))
    }
}
