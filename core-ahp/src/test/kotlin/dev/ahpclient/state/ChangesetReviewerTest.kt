package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.Changeset
import com.microsoft.agenthostprotocol.generated.ChangesetOperation
import com.microsoft.agenthostprotocol.generated.InvokeChangesetOperationResult
import com.microsoft.agenthostprotocol.generated.StateAction
import dev.ahpclient.ahp.AhpException
import dev.ahpclient.ahp.CapabilityDeniedException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject

class ChangesetReviewerTest {
    private class Rig(
        var allowed: Boolean = true,
        var files: Set<String>? = setOf("f1", "f2"),
        var ops: List<ChangesetOperation>? = listOf(
            Ahp.json.decodeFromString(ChangesetOperation.serializer(), """{"id":"stage","label":"Stage all","scopes":["changeset"],"status":"idle"}"""),
            Ahp.json.decodeFromString(ChangesetOperation.serializer(), """{"id":"discard","label":"Discard all","scopes":["changeset"],"confirmation":"Discard all changes?","status":"idle"}"""),
            Ahp.json.decodeFromString(ChangesetOperation.serializer(), """{"id":"file-op","label":"Stage file","scopes":["resource"],"status":"idle"}"""),
            Ahp.json.decodeFromString(ChangesetOperation.serializer(), """{"id":"running-op","label":"Running","scopes":["changeset"],"status":"running"}"""),
        ),
    ) {
        val sent = mutableListOf<Pair<String, StateAction>>()
        val invoked = mutableListOf<Pair<String, String>>()
        val asked = mutableListOf<ApprovalRequest>()
        var answer = true
        var gateFailure: Exception? = null
        var whileAsking: () -> Unit = {}
        var outcome: DispatchOutcome = DispatchOutcome.Accepted
        var failure: Exception? = null
        var invokeResult: InvokeChangesetOperationResult =
            Ahp.json.decodeFromString(InvokeChangesetOperationResult.serializer(), """{"message":"Done"}""")
        val reviewer = ChangesetReviewer(
            allowed = { allowed },
            reviewableFiles = { files },
            dispatch = { channel, action ->
                failure?.let { throw it }
                sent += channel to action
                outcome
            },
            operations = { ops },
            invoke = { uri, opId ->
                failure?.let { throw it }
                invoked += uri to opId
                invokeResult
            },
            gate = ApprovalGate { req ->
                asked += req
                gateFailure?.let { throw it }
                whileAsking()
                answer
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

    @Test
    fun `invoking an operation without confirmation calls invoke directly and returns its message`() = runTest {
        val rig = Rig()

        assertEquals(ManageResult.Done("Done"), rig.reviewer.invokeOperation("ahp-changeset:/c", "stage"))
        assertEquals(listOf("ahp-changeset:/c" to "stage"), rig.invoked)
        assertTrue(rig.asked.isEmpty())
    }

    @Test
    fun `invoking an operation with confirmation requires the device check and re-verifies state`() = runTest {
        val rig = Rig()

        assertEquals(ManageResult.Done("Done"), rig.reviewer.invokeOperation("ahp-changeset:/c", "discard"))
        assertEquals(
            listOf(ApprovalRequest(toolName = "Discard all", summary = "Discard all changes?", title = ChangesetReviewer.OPERATION_TITLE)),
            rig.asked,
        )
        assertEquals(listOf("ahp-changeset:/c" to "discard"), rig.invoked)

        val declined = Rig().apply { answer = false }
        assertEquals(ManageResult.NotConfirmed, declined.reviewer.invokeOperation("ahp-changeset:/c", "discard"))
        assertTrue(declined.invoked.isEmpty())

        val vanished = Rig().apply { whileAsking = { ops = emptyList() } }
        assertEquals(ManageResult.NoSuchTarget, vanished.reviewer.invokeOperation("ahp-changeset:/c", "discard"))
        assertTrue(vanished.invoked.isEmpty())
    }

    @Test
    fun `invoking an unknown or non-changeset or running operation is refused`() = runTest {
        val rig = Rig()

        assertEquals(ManageResult.NoSuchTarget, rig.reviewer.invokeOperation("ahp-changeset:/c", "unknown"))
        assertEquals(ManageResult.NoSuchTarget, rig.reviewer.invokeOperation("ahp-changeset:/c", ""))
        assertEquals(ManageResult.NoSuchTarget, rig.reviewer.invokeOperation("ahp-changeset:/c", "file-op"))
        assertEquals(ManageResult.NoSuchTarget, rig.reviewer.invokeOperation("ahp-changeset:/c", "running-op"))

        rig.allowed = false
        assertEquals(ManageResult.NotAllowed, rig.reviewer.invokeOperation("ahp-changeset:/c", "stage"))

        rig.allowed = true
        rig.failure = AhpException.Rpc(-32000, "git failed", null)
        assertIs<ManageResult.Refused>(rig.reviewer.invokeOperation("ahp-changeset:/c", "stage"))

        rig.failure = CapabilityDeniedException("invokeChangesetOperation", null)
        assertEquals(ManageResult.NotAllowed, rig.reviewer.invokeOperation("ahp-changeset:/c", "stage"))

        rig.failure = IOException("offline")
        assertIs<ManageResult.NotSent>(rig.reviewer.invokeOperation("ahp-changeset:/c", "stage"))
    }
}
