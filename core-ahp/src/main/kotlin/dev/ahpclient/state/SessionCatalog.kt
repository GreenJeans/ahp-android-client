package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.PartialSessionSummary
import com.microsoft.agenthostprotocol.generated.SessionSummary

/** Pure rules for the session list: how a partial update applies, and the order rows appear in. */
object SessionCatalog {
    /**
     * Applies a `root/sessionSummaryChanged` update: omitted fields are unchanged. `provider`, `resource` and
     * `createdAt` never change, so they are ignored if a host sends them. `_meta` is replaced as a whole.
     */
    fun merge(current: SessionSummary, update: PartialSessionSummary): SessionSummary = current.copy(
        title = update.title ?: current.title,
        status = update.status ?: current.status,
        activity = update.activity ?: current.activity,
        origin = update.origin ?: current.origin,
        project = update.project ?: current.project,
        workingDirectories = update.workingDirectories ?: current.workingDirectories,
        annotations = update.annotations ?: current.annotations,
        modifiedAt = update.modifiedAt ?: current.modifiedAt,
        changes = update.changes ?: current.changes,
        meta = update.meta ?: current.meta,
        chats = update.chats ?: current.chats,
        defaultChat = update.defaultChat ?: current.defaultChat,
    )

    /**
     * Sessions that need the user come first, then the most recently modified. `modifiedAt` is an ISO-8601 UTC
     * timestamp, so comparing the strings orders them in time.
     */
    fun sorted(sessions: Collection<SessionSummary>): List<SessionSummary> = sessions.sortedWith(
        compareBy<SessionSummary> { if (it.status.activityState == ActivityState.NEEDS_INPUT) 0 else 1 }
            .thenByDescending { it.modifiedAt }
            .thenBy { it.resource },
    )
}
