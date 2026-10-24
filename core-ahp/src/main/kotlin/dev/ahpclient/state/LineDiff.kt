package dev.ahpclient.state

enum class DiffLineKind { CONTEXT, ADDED, REMOVED }

/** One line of a diff. [oldNumber] is null for an added line and [newNumber] for a removed one; both start at 1. */
data class DiffLine(val kind: DiffLineKind, val oldNumber: Int?, val newNumber: Int?, val text: String)

/** A run of changed lines with a few unchanged ones either side. */
data class DiffHunk(val lines: List<DiffLine>) {
    /** Where the hunk is: the lines it covers in the new file, or in the old one when the file was deleted. */
    val label: String
        get() {
            val inNew = lines.mapNotNull { it.newNumber }
            if (inNew.isNotEmpty()) return range("", inNew.min(), inNew.max())
            val inOld = lines.mapNotNull { it.oldNumber }
            return range("Deleted ", inOld.min(), inOld.max())
        }

    private fun range(prefix: String, from: Int, to: Int) = if (from == to) "${prefix}line $from".replaceFirstChar { it.uppercase() } else "${prefix}lines $from–$to".replaceFirstChar { it.uppercase() }
}

/**
 * What changed in one file, line by line. [added] and [removed] count every changed line; [hunks] hold the lines to show, at
 * most [LineDiff.MAX_SHOWN] of them, and [omittedLines] says how many more there were.
 */
data class FileDiff(val hunks: List<DiffHunk>, val added: Int, val removed: Int, val omittedLines: Int)

/**
 * A line diff worked out on the phone from the two sides of a file. The inputs are bounded before and during the work, so a
 * file the host sends can never make the phone chew on it for long: [MAX_LINES] lines a side, and [MAX_EDITS] changed lines
 * (the diff gives up beyond that and says so, rather than showing something wrong). Text is shown, never run or rendered.
 */
internal object LineDiff {
    const val MAX_LINES = 5_000
    const val MAX_EDITS = 1_000
    const val MAX_SHOWN = 2_000
    const val MAX_LINE_CHARS = 500
    private const val CONTEXT = 3

    enum class Op { EQUAL, INSERT, DELETE }

    /** One step of the edit script. An index is -1 when the step has no line on that side. */
    data class Edit(val op: Op, val oldIndex: Int, val newIndex: Int)

    /** Splits text into lines, treating `\r\n` as one break and a final break as the end of the last line, not a new line. */
    fun lines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val parts = text.split('\n')
        val kept = if (parts.last().isEmpty()) parts.dropLast(1) else parts
        return kept.map { it.removeSuffix("\r") }
    }

    /** The shortest script turning [old] into [new], or null when it would take more than [maxEdits] changed lines. */
    fun edits(old: List<String>, new: List<String>, maxEdits: Int = MAX_EDITS): List<Edit>? {
        var prefix = 0
        while (prefix < old.size && prefix < new.size && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < old.size - prefix && suffix < new.size - prefix && old[old.size - 1 - suffix] == new[new.size - 1 - suffix]) suffix++
        val middle = myers(old.subList(prefix, old.size - suffix), new.subList(prefix, new.size - suffix), maxEdits) ?: return null
        val script = ArrayList<Edit>(old.size + new.size)
        for (i in 0 until prefix) script += Edit(Op.EQUAL, i, i)
        for (e in middle) {
            script += when (e.op) {
                Op.EQUAL -> Edit(Op.EQUAL, e.oldIndex + prefix, e.newIndex + prefix)
                Op.DELETE -> Edit(Op.DELETE, e.oldIndex + prefix, -1)
                Op.INSERT -> Edit(Op.INSERT, -1, e.newIndex + prefix)
            }
        }
        for (i in 0 until suffix) script += Edit(Op.EQUAL, old.size - suffix + i, new.size - suffix + i)
        return script
    }

    /** The diff of [old] against [new] as hunks with three lines of context, or null when they differ too much to compare here. */
    fun diff(old: List<String>, new: List<String>): FileDiff? {
        val script = edits(old, new) ?: return null
        val lines = script.map { e ->
            when (e.op) {
                Op.EQUAL -> DiffLine(DiffLineKind.CONTEXT, e.oldIndex + 1, e.newIndex + 1, clean(new[e.newIndex]))
                Op.INSERT -> DiffLine(DiffLineKind.ADDED, null, e.newIndex + 1, clean(new[e.newIndex]))
                Op.DELETE -> DiffLine(DiffLineKind.REMOVED, e.oldIndex + 1, null, clean(old[e.oldIndex]))
            }
        }
        val keep = BooleanArray(lines.size)
        lines.forEachIndexed { i, line ->
            if (line.kind != DiffLineKind.CONTEXT) {
                for (j in maxOf(0, i - CONTEXT)..minOf(lines.lastIndex, i + CONTEXT)) keep[j] = true
            }
        }
        val hunks = ArrayList<DiffHunk>()
        var shown = 0
        var omitted = 0
        var i = 0
        while (i < lines.size) {
            if (!keep[i]) {
                i++
                continue
            }
            val start = i
            while (i < lines.size && keep[i]) i++
            val run = lines.subList(start, i)
            val room = (MAX_SHOWN - shown).coerceAtLeast(0)
            if (run.size <= room) {
                hunks += DiffHunk(run)
                shown += run.size
            } else {
                if (room > 0) hunks += DiffHunk(run.take(room))
                shown += room
                omitted += run.size - room
            }
        }
        return FileDiff(hunks, added = script.count { it.op == Op.INSERT }, removed = script.count { it.op == Op.DELETE }, omittedLines = omitted)
    }

    /**
     * A line as it is shown: bounded, and with the characters that reorder text on screen swapped for a visible mark, so
     * a file cannot make code read differently from how it is written.
     */
    fun clean(text: String): String {
        val bounded = if (text.length > MAX_LINE_CHARS) {
            val cut = text.take(MAX_LINE_CHARS)
            (if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut) + "…"
        } else {
            text
        }
        if (bounded.none(::isBidiControl)) return bounded
        return bounded.map { if (isBidiControl(it)) '\uFFFD' else it }.joinToString("")
    }

    private fun isBidiControl(c: Char) = c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069' || c == '\u200E' || c == '\u200F' || c == '\u061C'

    private fun myers(old: List<String>, new: List<String>, maxEdits: Int): List<Edit>? {
        val n = old.size
        val m = new.size
        if (n == 0) return List(m) { Edit(Op.INSERT, -1, it) }
        if (m == 0) return List(n) { Edit(Op.DELETE, it, -1) }
        val limit = minOf(n + m, maxEdits)
        val offset = limit + 1
        val v = IntArray(2 * limit + 3)
        val trace = ArrayList<IntArray>()
        var found = -1
        search@ for (d in 0..limit) {
            trace += v.copyOf()
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) v[offset + k + 1] else v[offset + k - 1] + 1
                var y = x - k
                while (x < n && y < m && old[x] == new[y]) {
                    x++
                    y++
                }
                v[offset + k] = x
                if (x >= n && y >= m) {
                    found = d
                    break@search
                }
                k += 2
            }
        }
        if (found < 0) return null
        val script = ArrayList<Edit>()
        var x = n
        var y = m
        for (d in found downTo 0) {
            val before = trace[d]
            val k = x - y
            val previousK = if (k == -d || (k != d && before[offset + k - 1] < before[offset + k + 1])) k + 1 else k - 1
            val previousX = before[offset + previousK]
            val previousY = previousX - previousK
            while (x > previousX && y > previousY) {
                script += Edit(Op.EQUAL, x - 1, y - 1)
                x--
                y--
            }
            if (d > 0) script += if (x == previousX) Edit(Op.INSERT, -1, previousY) else Edit(Op.DELETE, previousX, -1)
            x = previousX
            y = previousY
        }
        script.reverse()
        return script
    }
}
