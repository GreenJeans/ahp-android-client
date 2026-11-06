package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.SessionSummary
import dev.ahpclient.platform.DateTimeException
import dev.ahpclient.platform.INSTANT_EPOCH
import dev.ahpclient.platform.Instant
import dev.ahpclient.platform.parseInstant
import dev.ahpclient.platform.parseUri

/**
 * The folders a new session may start in. They come only from the host's own session list, never from anything typed, and
 * are ordered by when a session in them was last used, most recent first, each once.
 *
 * Left out: non-local addresses (such as an `https://` repository URL), folders the host has reported missing on this
 * connection, the host's own working copies of a project (a git worktree such as `repo.worktrees/branch`, `.claude/worktrees/...`,
 * `.gemini/antigravity/worktrees/...`, `copilot-worktrees/...`, or any folder that is not inside its session's project), OS
 * temporary folders (`AppData/Local/Temp`, `/tmp`, `/var/folders/.../T`), and the host's own scratch folders under `.copilot`
 * or marked in `_meta` (`workspaceless`, `vscode.scratchFolders`). When a session ran in a working copy or temporary folder,
 * its project folder is offered instead if that project is itself a real local folder. Starting a session in a new working copy
 * is not supported yet, so a working copy is never offered as the place to start.
 */
object RecentFolders {
    /** How many the new-session dialog shows before the person asks to see the rest. */
    const val SHOWN = 8

    private val WORKING_COPY = Regex(
        """(\.worktrees|/(copilot-worktrees|\.(claude|gemini/antigravity)/worktrees))(/|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val HOST_INTERNAL = Regex("""/\.copilot(/|$)""", RegexOption.IGNORE_CASE)
    private val TEMPORARY = Regex(
        """(/AppData/Local/Temp|/Local Settings/Temp|^([A-Za-z]:)?/(Windows/)?Temp|^([A-Za-z]:)?/tmp|^/(private/)?(var/)?tmp|^/(private/)?var/folders/[^/]+/[^/]+/T)(/|$)""",
        RegexOption.IGNORE_CASE,
    )

    fun from(sessions: List<SessionSummary>, missing: Set<String> = emptySet()): List<String> {
        val missingKeys = if (missing.isEmpty()) emptySet() else missing.mapTo(HashSet()) { key(it) }
        return sessions
            .withIndex()
            .sortedWith(compareByDescending<IndexedValue<SessionSummary>> { modified(it.value) }.thenBy { it.index })
            .flatMap { (_, session) -> foldersForSession(session, missingKeys) }
            .distinct()
    }

    private fun foldersForSession(session: SessionSummary, missingKeys: Set<String>): List<String> {
        val project = session.project?.uri
        val scratchKeys = HostExtras.scratchFolders(session.meta).mapTo(HashSet()) { key(it) }
        if (HostExtras.isWorkspaceless(session.meta)) {
            val fallback = project?.takeIf { isOfferableProject(it) && key(it) !in scratchKeys && key(it) !in missingKeys }
            return listOfNotNull(fallback)
        }
        return session.workingDirectories.orEmpty().mapNotNull { directory ->
            val candidate = if (key(directory) in scratchKeys) {
                project?.takeIf(::isOfferableProject)
            } else {
                folderFor(directory, project)
            }
            candidate?.takeIf { key(it) !in scratchKeys && key(it) !in missingKeys }
        }
    }

    /** The folder to offer for a session that ran in [directory], or null when there is none worth offering. */
    internal fun folderFor(directory: String, project: String?): String? {
        if (!isLocalFolder(directory) || isHostInternal(directory)) return null
        if (!isWorkingCopy(directory, project) && !isTemporary(directory)) return directory
        return project?.takeIf(::isOfferableProject)
    }

    private fun isOfferableProject(uri: String): Boolean =
        isLocalFolder(uri) && !isHostInternal(uri) && !isTemporary(uri) && !isWorkingCopy(uri, null)

    /** An unreadable time sorts last, so a session with a broken stamp never leads the list. */
    private fun modified(session: SessionSummary): Instant =
        try {
            parseInstant(session.modifiedAt)
        } catch (_: DateTimeException) {
            INSTANT_EPOCH
        }

    /** Only a `file:` URI or a local filesystem path is a folder on the host; `https://` repository URLs and custom schemes are not. */
    private fun isLocalFolder(uri: String): Boolean {
        val trimmed = uri.trim()
        if (trimmed.isEmpty()) return false
        val parsed = parseUri(trimmed) ?: return false
        val scheme = parsed.scheme ?: return true
        if (scheme.equals("file", ignoreCase = true)) return !parsed.path.isNullOrBlank()
        // A raw Windows path like C:/repos/app parses with a 1-letter scheme ("C").
        return scheme.length == 1 && scheme[0].isLetter()
    }

    private fun isHostInternal(uri: String): Boolean = HOST_INTERNAL.containsMatchIn(path(uri))

    private fun isTemporary(uri: String): Boolean = TEMPORARY.containsMatchIn(path(uri))

    private fun isWorkingCopy(directory: String, project: String?): Boolean {
        val path = path(directory)
        if (WORKING_COPY.containsMatchIn(path)) return true
        val root = project?.takeIf(::isLocalFolder)?.let(::path) ?: return false
        return !path.equals(root, ignoreCase = true) && !path.startsWith("$root/", ignoreCase = true)
    }

    internal fun key(uri: String): String = path(uri).lowercase()

    private fun path(uri: String): String = displayPath(uri).replace('\\', '/').trimEnd('/')
}
