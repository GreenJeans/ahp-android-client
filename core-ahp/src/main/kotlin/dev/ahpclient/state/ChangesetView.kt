package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.Changeset
import com.microsoft.agenthostprotocol.generated.ChangesetState

/** What changed in one file, from which sides the host reports: only an after side is an addition, only a before side a deletion. */
enum class ChangeKind { ADDED, MODIFIED, DELETED }

data class ChangeRow(
    val id: String,
    val path: String,
    val kind: ChangeKind,
    val added: Long?,
    val removed: Long?,
    val reviewed: Boolean,
) {
    /** The file's own name (e.g. `mcp-tool-cache.ts` for `c:/.../src/mcp/mcp-tool-cache.ts`), so the row leads with what changed. */
    val fileName: String
        get() = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

    /** The folder holding the file (e.g. `c:/.../src/mcp`), or null when [path] has no folder part. */
    val directory: String?
        get() = path.trimEnd('/').substringBeforeLast('/', "").takeIf { it.isNotEmpty() }
}

/** Where the host is in working out a changeset. A word this client does not know is [OTHER], never taken as ready. */
enum class ChangesetPhase {
    COMPUTING,
    RECOMPUTING,
    READY,
    ERROR,
    OTHER,
    ;

    companion object {
        fun of(raw: String): ChangesetPhase = when (raw) {
            "computing" -> COMPUTING
            "recomputing" -> RECOMPUTING
            "ready" -> READY
            "error" -> ERROR
            else -> OTHER
        }
    }
}

/**
 * A changeset as a screen shows it: the files that changed with their line counts. The files' contents are fetched only when a
 * file is opened (see [ChangesetDiffs]); here nothing is read. At most [MAX_ROWS] files are listed, and how many
 * were left out is said.
 */
data class ChangesetView(
    val phase: ChangesetPhase,
    val error: String?,
    val rows: List<ChangeRow>,
    val omitted: Int,
    val totalAdded: Long,
    val totalRemoved: Long,
) {
    companion object {
        const val MAX_ROWS = 500
        private const val MAX_ERROR_CHARS = 200

        fun from(state: ChangesetState): ChangesetView {
            val all = state.files.map { file ->
                val edit = file.edit
                val uri = edit.after?.uri ?: edit.before?.uri ?: file.id
                ChangeRow(
                    id = file.id,
                    path = displayPath(uri),
                    kind = when {
                        edit.before == null && edit.after != null -> ChangeKind.ADDED
                        edit.after == null && edit.before != null -> ChangeKind.DELETED
                        else -> ChangeKind.MODIFIED
                    },
                    added = edit.diff?.added,
                    removed = edit.diff?.removed,
                    reviewed = file.reviewed == true,
                )
            }
            return ChangesetView(
                phase = ChangesetPhase.of(state.status.rawValue),
                error = state.error?.message?.take(MAX_ERROR_CHARS)?.takeIf { it.isNotBlank() },
                rows = all.take(MAX_ROWS),
                omitted = (all.size - MAX_ROWS).coerceAtLeast(0),
                totalAdded = all.sumOf { it.added ?: 0 },
                totalRemoved = all.sumOf { it.removed ?: 0 },
            )
        }
    }
}

/**
 * A changeset a session advertises. [uri] is set only when the template needs no values filled in, so it can be opened as it
 * stands. [reviewable] is true when the host says files in it can be marked reviewed.
 */
data class ChangesetEntry(val label: String, val description: String?, val kind: String, val uri: String?, val reviewable: Boolean = false) {
    companion object {
        private const val MAX_LABEL = 120
        private const val MAX_URI = 512

        fun from(changeset: Changeset): ChangesetEntry {
            val template = changeset.uriTemplate
            val plain = template.isNotBlank() && '{' !in template && template.length <= MAX_URI
            return ChangesetEntry(
                label = changeset.label.ifBlank { "Changes" }.take(MAX_LABEL),
                description = changeset.description?.takeIf { it.isNotBlank() }?.take(MAX_LABEL * 2),
                kind = changeset.changeKind,
                uri = template.takeIf { plain },
                reviewable = changeset.capabilities?.review != null,
            )
        }
    }
}
