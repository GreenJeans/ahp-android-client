package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.ChangesSummary

/** How much a session has changed, as the host totals it: lines added and removed, and how many files. */
data class ChangeStats(val added: Long, val removed: Long, val files: Long? = null) {
    /** For a list or a header: "(+1072, −8)". */
    val text: String get() = "(+$added, \u2212$removed)"

    /** For a screen reader: the signs are read as words, not as "plus" and "dash". */
    val spoken: String
        get() = "$added ${if (added == 1L) "line" else "lines"} added, $removed removed" + (files?.let { ", in $it ${if (it == 1L) "file" else "files"}" } ?: "")

    companion object {
        /** Null when the host gave no totals, or the session has changed nothing: there is nothing to show then. */
        fun of(summary: ChangesSummary?): ChangeStats? {
            summary ?: return null
            val added = (summary.additions ?: 0).coerceAtLeast(0)
            val removed = (summary.deletions ?: 0).coerceAtLeast(0)
            if (added == 0L && removed == 0L) return null
            return ChangeStats(added, removed, summary.files?.takeIf { it >= 0 })
        }
    }
}