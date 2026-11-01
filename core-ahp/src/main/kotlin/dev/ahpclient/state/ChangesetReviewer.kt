package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.ChangesetFile
import com.microsoft.agenthostprotocol.generated.ChangesetOperation
import com.microsoft.agenthostprotocol.generated.ChangesetOperationResourceTarget
import com.microsoft.agenthostprotocol.generated.ChangesetOperationScope
import com.microsoft.agenthostprotocol.generated.ChangesetOperationTarget
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
    private val files: (changesetUri: String) -> List<ChangesetFile>? = { null },
    private val invoke: suspend (changesetUri: String, operationId: String, target: ChangesetOperationTarget?) -> InvokeChangesetOperationResult = { _, _, _ -> InvokeChangesetOperationResult() },
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
     * Runs [operationId] on [changesetUri] (or on [fileId] within it when [fileId] is given) when it is an active
     * operation in the open changeset for that scope. Operations that carry a host confirmation prompt require the
     * device check ([gate]) first and re-verify the operation and file are still runnable afterwards.
     */
    suspend fun invokeOperation(changesetUri: String, operationId: String, fileId: String? = null): ManageResult {
        if (!allowed()) return ManageResult.NotAllowed
        if (operationId.isBlank()) return ManageResult.NoSuchTarget
        if (fileId != null && fileId.isBlank()) return ManageResult.NoSuchTarget
        val forResource = fileId != null
        val op = findRunnable(changesetUri, operationId, forResource) ?: return ManageResult.NoSuchTarget
        var target = if (fileId != null) resolveResourceTarget(changesetUri, fileId) ?: return ManageResult.NoSuchTarget else null
        val confirmation = SessionDetail.plain(op.confirmation)?.takeIf { it.isNotBlank() }
        if (op.confirmation != null) {
            val summary = if (target != null) {
                listOfNotNull(displayPath(target.value.resource).takeIf { it.isNotBlank() }, confirmation)
                    .joinToString(" — ")
                    .takeIf { it.isNotBlank() }
                    ?.take(MAX_SUMMARY_CHARS)
            } else {
                confirmation?.take(MAX_SUMMARY_CHARS)
            }
            val request = ApprovalRequest(
                toolName = op.label.ifBlank { op.id }.take(MAX_LABEL_CHARS),
                summary = summary,
                title = OPERATION_TITLE,
            )
            if (!confirmed(request)) return ManageResult.NotConfirmed
            if (!allowed()) return ManageResult.NotAllowed
            if (findRunnable(changesetUri, operationId, forResource) == null) return ManageResult.NoSuchTarget
            if (fileId != null) target = resolveResourceTarget(changesetUri, fileId) ?: return ManageResult.NoSuchTarget
        }
        return try {
            val result = invoke(changesetUri, operationId, target)
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

    private fun findRunnable(changesetUri: String, operationId: String, forResource: Boolean): ChangesetOperation? =
        operations(changesetUri)
            ?.firstOrNull {
                it.id == operationId &&
                    (if (forResource) ChangesetOperationScope.RESOURCE in it.scopes else (it.scopes.isEmpty() || ChangesetOperationScope.CHANGESET in it.scopes)) &&
                    ChangesetOperationPhase.of(it.status.rawValue).canInvoke
            }

    private fun resolveResourceTarget(changesetUri: String, fileId: String): ChangesetOperationTarget.Resource? {
        val file = files(changesetUri)?.firstOrNull { it.id == fileId } ?: return null
        val uri = (file.edit.after?.uri ?: file.edit.before?.uri ?: file.id).takeIf { it.isNotBlank() } ?: return null
        return ChangesetOperationTarget.Resource(ChangesetOperationResourceTarget(resource = uri))
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
