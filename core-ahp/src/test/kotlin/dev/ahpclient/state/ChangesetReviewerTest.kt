package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.Changeset
import com.microsoft.agenthostprotocol.generated.ChangesetFile
import com.microsoft.agenthostprotocol.generated.ChangesetOperation
import com.microsoft.agenthostprotocol.generated.ChangesetOperationResourceTarget
import com.microsoft.agenthostprotocol.generated.ChangesetOperationTarget
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
            Ahp.json.decodeFromString(ChangesetOperation.serializer(), """{"id":"revert-file","label":"Revert file","scopes":["resource"],"confirmation":"Revert this file?","status":"idle"}"""),
            Ahp.json.decodeFromString(ChangesetOperation.serializer(), """{"id":"running-op","label":"Running","scopes":["changeset"],"status":"running"}"""),
        ),
        var csFiles: List<ChangesetFile>? = listOf(
            Ahp.json.decodeFromString(ChangesetFile.serializer(), """{"id":"f1","edit":{"after":{"uri":"file:///c:/work/a.kt","content":{"uri":"ahp-content:/1"}}}}"""),
            Ahp.json.decodeFromString(ChangesetFile.serializer(), """{"id":"f2","edit":{"before":{"uri":"file:///c:/work/b.kt","content":{"uri":"ahp-content:/2"}}}}"""),
        ),
    ) {
        val sent = mutableListOf<Pair<String, StateAction>>()
        val invoked = mutableListOf<Pair<String, String>>()
        val targets = mutableListOf<ChangesetOperationTarget?>()
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
            files = { csFiles },
            invoke = { uri, opId, target ->
                failure?.let { throw it }
                invoked += uri to opId
                targets += target
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

    @Test
    fun `invoking a resource-scoped operation targets the file's resource uri and verifies it across the device check`() = runTest {
        val rig = Rig()

        assertEquals(ManageResult.Done("Done"), rig.reviewer.invokeOperation("ahp-changeset:/c", "file-op", fileId = "f1"))
        assertEquals(listOf("ahp-changeset:/c" to "file-op"), rig.invoked)
        assertEquals(listOf<ChangesetOperationTarget?>(ChangesetOperationTarget.Resource(ChangesetOperationResourceTarget(resource = "file:///c:/work/a.kt"))), rig.targets)
        assertTrue(rig.asked.isEmpty())

        assertEquals(ManageResult.Done("Done"), rig.reviewer.invokeOperation("ahp-changeset:/c", "revert-file", fileId = "f2"))
        assertEquals(
            listOf(ApprovalRequest(toolName = "Revert file", summary = "c:/work/b.kt — Revert this file?", title = ChangesetReviewer.OPERATION_TITLE)),
            rig.asked,
        )
        assertEquals(ChangesetOperationTarget.Resource(ChangesetOperationResourceTarget(resource = "file:///c:/work/b.kt")), rig.targets.last())

        assertEquals(ManageResult.NoSuchTarget, rig.reviewer.invokeOperation("ahp-changeset:/c", "file-op", fileId = "unknown"))
        assertEquals(ManageResult.NoSuchTarget, rig.reviewer.invokeOperation("ahp-changeset:/c", "file-op", fileId = ""))
        assertEquals(ManageResult.NoSuchTarget, rig.reviewer.invokeOperation("ahp-changeset:/c", "stage", fileId = "f1"))

        val vanishedFile = Rig().apply { whileAsking = { csFiles = emptyList() } }
        assertEquals(ManageResult.NoSuchTarget, vanishedFile.reviewer.invokeOperation("ahp-changeset:/c", "revert-file", fileId = "f1"))
        assertTrue(vanishedFile.invoked.isEmpty())
    }
}
