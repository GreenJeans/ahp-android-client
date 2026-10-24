package dev.ahpclient.state

import dev.ahpclient.ahp.CapabilityDeniedException
import com.microsoft.agenthostprotocol.generated.StateAction
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Marks one changed file of the open changeset reviewed, or not: the "viewed" box. It needs the MANAGE tier, a changeset
 * the host says can be reviewed, and a file that changeset lists right now, so a stale screen or a made-up id finds
 * nothing. It is reversible with the same action and changes no file, so there is no device check.
 *
 * [reviewableFiles] gives the ids of the files in [changesetUri] when that changeset is open and reviewable, else null.
 */
class ChangesetReviewer(
    private val allowed: () -> Boolean,
    private val reviewableFiles: (changesetUri: String) -> Set<String>?,
    private val dispatch: suspend (channel: String, action: StateAction) -> DispatchOutcome,
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
}
