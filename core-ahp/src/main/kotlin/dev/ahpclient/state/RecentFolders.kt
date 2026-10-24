package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.SessionSummary
import java.time.DateTimeException
import java.time.Instant

/**
 * The folders a new session may start in. They come only from the host's own session list, never from anything typed, and
 * are ordered by when a session in them was last used, most recent first, each once.
 *
 * Left out: the host's own working copies of a project (a git worktree such as `repo.worktrees/branch`, or any folder that is
 * not inside its session's project), and the host's own scratch folders under `.copilot`. When a session ran in a working
 * copy, its project folder is offered instead, so a project is still there to pick. Starting a session in a new working copy
 * is not supported yet, so a working copy is never offered as the place to start.
 */
object RecentFolders {
    /** How many the new-session dialog shows before the person asks to see the rest. */
    const val SHOWN = 8

    private val WORKING_COPY = Regex("\\.worktrees(/|$)")
    private val HOST_INTERNAL = Regex("/\\.copilot(/|$)")

    fun from(sessions: List<SessionSummary>): List<String> =
        sessions
            .withIndex()
            .sortedWith(compareByDescending<IndexedValue<SessionSummary>> { modified(it.value) }.thenBy { it.index })
            .flatMap { (_, session) -> session.workingDirectories.orEmpty().mapNotNull { folderFor(it, session.project?.uri) } }
            .distinct()

    /** The folder to offer for a session that ran in [directory], or null when there is none worth offering. */
    internal fun folderFor(directory: String, project: String?): String? {
        if (isHostInternal(directory)) return null
        if (!isWorkingCopy(directory, project)) return directory
        return project?.takeIf { !isHostInternal(it) && !isWorkingCopy(it, null) }
    }

    /** An unreadable time sorts last, so a session with a broken stamp never leads the list. */
    private fun modified(session: SessionSummary): Instant =
        try {
            Instant.parse(session.modifiedAt)
        } catch (_: DateTimeException) {
            Instant.EPOCH
        }

    private fun isHostInternal(uri: String): Boolean = HOST_INTERNAL.containsMatchIn(path(uri))

    private fun isWorkingCopy(directory: String, project: String?): Boolean {
        val path = path(directory)
        if (WORKING_COPY.containsMatchIn(path)) return true
        val root = project?.let(::path) ?: return false
        return !path.equals(root, ignoreCase = true) && !path.startsWith("$root/", ignoreCase = true)
    }

    private fun path(uri: String): String = displayPath(uri).replace('\\', '/').trimEnd('/')
}
