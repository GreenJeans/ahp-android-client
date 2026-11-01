package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.ChangesetOperation
import com.microsoft.agenthostprotocol.generated.ChangesetOperationScope
import com.microsoft.agenthostprotocol.generated.InvokeChangesetOperationResult
import com.microsoft.agenthostprotocol.generated.StateAction
import dev.ahpclient.ahp.AhpException
import dev.ahpclient.ahp.CapabilityDeniedException
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Marks one changed file of the open changeset reviewed, or not: the "viewed" box, and runs changeset-scoped operations
 * offered by the host (`invokeChangesetOperation`). Needs the MANAGE tier and an open changeset that lists the file or
 * operation right now, so a stale screen or a made-up id finds nothing. Marking a file reviewed is reversible and needs
 * no device check; an operation that declares a confirmation prompt goes through the device check ([gate]).
 *
 * [reviewableFiles] gives the ids of the files in [changesetUri] when that changeset is open and reviewable, else null.
 */
class ChangesetReviewer(
    private val allowed: () -> Boolean,
    private val reviewableFiles: (changesetUri: String) -> Set<String>?,
    private val dispatch: suspend (channel: String, action: StateAction) -> DispatchOutcome,
    private val operations: (changesetUri: String) -> List<ChangesetOperation>? = { null },
    private val invoke: suspend (changesetUri: String, operationId: String) -> InvokeChangesetOperationResult = { _, _ -> InvokeChangesetOperationResult() },
    private val gate: ApprovalGate = ApprovalGate { false },
) {
    suspend fun setReviewed(changesetUri: String, fileId: String, reviewed: Boolean): ManageResult {
        if (!allowed()) return ManageResult.NotAllowed
        val files = reviewableFiles(changesetUri) ?: return ManageResult.NoSuchTarget
        if (fileId !in files) return ManageResult.NoSuchTarget
        return try {
            when (val outcome = dispatch(changesetUri, ChangesetActions.setReviewed(listOf(fileId), reviewed))) {
                DispatchOutcome.Accepted -> ManageResult.Done()
                else -> ManageResult.Unconfirmed(outcome)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: CapabilityDeniedException) {
            ManageResult.NotAllowed
        } catch (e: IOException) {
            ManageResult.NotSent(e)
        }
    }

    /**
     * Runs [operationId] on [changesetUri] when it is an active changeset-scoped operation in the open changeset.
     * Operations that carry a host confirmation prompt require the device check ([gate]) first and re-verify the operation
     * is still runnable afterwards.
     */
    suspend fun invokeOperation(changesetUri: String, operationId: String): ManageResult {
        if (!allowed()) return ManageResult.NotAllowed
        if (operationId.isBlank()) return ManageResult.NoSuchTarget
        val op = findRunnable(changesetUri, operationId) ?: return ManageResult.NoSuchTarget
        val confirmation = SessionDetail.plain(op.confirmation)?.takeIf { it.isNotBlank() }?.take(MAX_SUMMARY_CHARS)
        if (op.confirmation != null) {
            val request = ApprovalRequest(
                toolName = op.label.ifBlank { op.id }.take(MAX_LABEL_CHARS),
                summary = confirmation,
                title = OPERATION_TITLE,
            )
            if (!confirmed(request)) return ManageResult.NotConfirmed
            if (!allowed()) return ManageResult.NotAllowed
            if (findRunnable(changesetUri, operationId) == null) return ManageResult.NoSuchTarget
        }
        return try {
            val result = invoke(changesetUri, operationId)
            val message = SessionDetail.plain(result.message)?.takeIf { it.isNotBlank() }?.take(MAX_SUMMARY_CHARS)
            ManageResult.Done(message)
        } catch (e: CancellationException) {
            throw e
        } catch (_: CapabilityDeniedException) {
            ManageResult.NotAllowed
        } catch (e: AhpException.Rpc) {
            ManageResult.Refused(e.code, e.message.orEmpty())
        } catch (e: AhpException) {
            ManageResult.NotSent(e)
        } catch (e: IOException) {
            ManageResult.NotSent(e)
        }
    }

    private fun findRunnable(changesetUri: String, operationId: String): ChangesetOperation? =
        operations(changesetUri)
            ?.firstOrNull {
                it.id == operationId &&
                    (it.scopes.isEmpty() || ChangesetOperationScope.CHANGESET in it.scopes) &&
                    ChangesetOperationPhase.of(it.status.rawValue).canInvoke
            }

    private suspend fun confirmed(request: ApprovalRequest): Boolean =
        try {
            gate.confirm(request)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    companion object {
        const val OPERATION_TITLE = "Run changeset operation?"
        private const val MAX_LABEL_CHARS = 80
        private const val MAX_SUMMARY_CHARS = 200
    }
}
