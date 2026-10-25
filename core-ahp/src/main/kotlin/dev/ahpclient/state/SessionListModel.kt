package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.SessionSummary
import java.time.Instant

data class SessionRow(
    val resource: String,
    val title: String,
    /** What the session is doing now, or else its project: the most useful second line a row has. */
    val subtitle: String?,
    val state: ActivityState,
    val isRead: Boolean,
    val isArchived: Boolean,
    val whenText: String,
    val chatCount: Int,
    val changes: ChangeStats? = null,
)

/** What the sessions screen shows, computed from the host's list. Pure, so it is tested without a UI. */
data class SessionListModel(
    val rows: List<SessionRow>,
    val needsInput: Int,
    val inProgress: Int,
    val errors: Int,
    /** Archived sessions that are currently hidden by the filter. */
    val hiddenArchived: Int,
    /** All sessions the host reported, shown or not. */
    val total: Int,
    /** The search the rows were narrowed by, trimmed; empty when none. The counts above ignore it, so the summary stays true to the host. */
    val query: String = "",
) {
    /** One line for the top of the list, for example "2 need you · 1 working". Empty when nothing is happening. */
    val summary: String
        get() = listOfNotNull(
            needsInput.takeIf { it > 0 }?.let { "$it need${if (it == 1) "s" else ""} you" },
            inProgress.takeIf { it > 0 }?.let { "$it working" },
            errors.takeIf { it > 0 }?.let { "$it error${if (it == 1) "" else "s"}" },
        ).joinToString(" · ")

    companion object {
        const val UNTITLED = "Untitled session"

        /**
         * Archived sessions are hidden unless [showArchived], except one that needs the user: hiding a question the
         * agent is waiting on would be worse than a cluttered list. Row order is kept from [sessions]. A non-blank [query]
         * narrows the rows to those whose title or second line contains it, ignoring case; the counts are of the rows before
         * the search, so searching never makes the summary say fewer sessions need the user.
         */
        fun from(sessions: List<SessionSummary>, showArchived: Boolean, now: Instant, query: String = ""): SessionListModel {
            val visible = sessions.filter { showArchived || !it.status.isArchived || it.status.activityState == ActivityState.NEEDS_INPUT }
            val all = visible.map { row(it, now) }
            val needle = query.trim()
            val rows = if (needle.isEmpty()) all else all.filter { it.title.contains(needle, ignoreCase = true) || it.subtitle?.contains(needle, ignoreCase = true) == true }
            return SessionListModel(
                rows = rows,
                needsInput = all.count { it.state == ActivityState.NEEDS_INPUT },
                inProgress = all.count { it.state == ActivityState.IN_PROGRESS },
                errors = all.count { it.state == ActivityState.ERROR },
                hiddenArchived = sessions.size - visible.size,
                total = sessions.size,
                query = needle,
            )
        }

        private fun row(session: SessionSummary, now: Instant) = SessionRow(
            resource = session.resource,
            title = session.title.ifBlank { UNTITLED },
            subtitle = session.activity?.takeIf { it.isNotBlank() } ?: session.project?.displayName?.takeIf { it.isNotBlank() },
            state = session.status.activityState,
            isRead = session.status.isRead,
            isArchived = session.status.isArchived,
            whenText = RelativeTime.format(session.modifiedAt, now),
            chatCount = session.chats?.size ?: 0,
            changes = ChangeStats.of(session.changes),
        )
    }
}
