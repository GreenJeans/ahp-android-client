package dev.ahpclient.state

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LineDiffTest {
    private fun lines(vararg text: String) = text.toList()

    private fun diff(old: List<String>, new: List<String>) = assertNotNull(LineDiff.diff(old, new))

    // ---- splitting text into lines -----------------------------------------------------------------------------

    @Test
    fun `a final line break ends the last line instead of starting another, and CRLF is one break`() {
        assertEquals(emptyList(), LineDiff.lines(""))
        assertEquals(listOf("a", "b"), LineDiff.lines("a\nb\n"))
        assertEquals(listOf("a", "b"), LineDiff.lines("a\nb"))
        assertEquals(listOf("a", "b"), LineDiff.lines("a\r\nb\r\n"))
        assertEquals(listOf(""), LineDiff.lines("\n"))
        assertEquals(listOf("a", ""), LineDiff.lines("a\n\n"))
    }

    // ---- the diff ----------------------------------------------------------------------------------------------

    @Test
    fun `identical files have no hunks and no counts`() {
        val result = diff(lines("a", "b", "c"), lines("a", "b", "c"))

        assertEquals(emptyList(), result.hunks)
        assertEquals(0, result.added)
        assertEquals(0, result.removed)
    }

    @Test
    fun `one changed line shows three unchanged lines either side, numbered on both sides`() {
        val old = (1..10).map { "line $it" }
        val new = old.toMutableList().also { it[4] = "line five, changed" }

        val result = diff(old, new)

        val hunk = result.hunks.single()
        assertEquals(1, result.added)
        assertEquals(1, result.removed)
        assertEquals(
            listOf(
                DiffLine(DiffLineKind.CONTEXT, 2, 2, "line 2"),
                DiffLine(DiffLineKind.CONTEXT, 3, 3, "line 3"),
                DiffLine(DiffLineKind.CONTEXT, 4, 4, "line 4"),
                DiffLine(DiffLineKind.REMOVED, 5, null, "line 5"),
                DiffLine(DiffLineKind.ADDED, null, 5, "line five, changed"),
                DiffLine(DiffLineKind.CONTEXT, 6, 6, "line 6"),
                DiffLine(DiffLineKind.CONTEXT, 7, 7, "line 7"),
                DiffLine(DiffLineKind.CONTEXT, 8, 8, "line 8"),
            ),
            hunk.lines,
        )
    }

    @Test
    fun `line numbers stay right after lines were added or removed above`() {
        val old = (1..12).map { "l$it" }
        val new = listOf("new top") + old.filterNot { it == "l3" }

        val result = diff(old, new)

        val first = result.hunks.first().lines
        assertEquals(DiffLine(DiffLineKind.ADDED, null, 1, "new top"), first.first { it.kind == DiffLineKind.ADDED })
        assertEquals(DiffLine(DiffLineKind.REMOVED, 3, null, "l3"), first.first { it.kind == DiffLineKind.REMOVED })
        assertEquals(DiffLine(DiffLineKind.CONTEXT, 4, 4, "l4"), first.first { it.oldNumber == 4 })
    }

    @Test
    fun `far apart changes make separate hunks and near ones are merged`() {
        val old = (1..40).map { "l$it" }
        val apart = old.toMutableList().also {
            it[1] = "x"
            it[35] = "y"
        }
        val near = old.toMutableList().also {
            it[10] = "x"
            it[16] = "y"
        }

        assertEquals(2, diff(old, apart).hunks.size)
        assertEquals(1, diff(old, near).hunks.size, "two changes with only six lines between them share their context")
    }

    @Test
    fun `a new file is all added and a deleted file is all removed`() {
        val created = diff(emptyList(), lines("a", "b"))
        val deleted = diff(lines("a", "b"), emptyList())

        assertEquals(listOf(DiffLineKind.ADDED, DiffLineKind.ADDED), created.hunks.single().lines.map { it.kind })
        assertEquals(listOf(null, null), created.hunks.single().lines.map { it.oldNumber })
        assertEquals(listOf(1, 2), created.hunks.single().lines.map { it.newNumber })
        assertEquals(listOf(DiffLineKind.REMOVED, DiffLineKind.REMOVED), deleted.hunks.single().lines.map { it.kind })
        assertEquals(2, created.added)
        assertEquals(2, deleted.removed)
    }

    @Test
    fun `the edit script is always valid and as short as it can be`() {
        val random = Random(42)
        repeat(400) {
            val old = List(random.nextInt(0, 14)) { "l${random.nextInt(0, 6)}" }
            val new = List(random.nextInt(0, 14)) { "l${random.nextInt(0, 6)}" }

            val script = assertNotNull(LineDiff.edits(old, new))

            var o = 0
            var n = 0
            for (edit in script) {
                when (edit.op) {
                    LineDiff.Op.EQUAL -> {
                        assertEquals(o, edit.oldIndex)
                        assertEquals(n, edit.newIndex)
                        assertEquals(old[o++], new[n++])
                    }
                    LineDiff.Op.DELETE -> assertEquals(o++, edit.oldIndex)
                    LineDiff.Op.INSERT -> assertEquals(n++, edit.newIndex)
                }
            }
            assertEquals(old.size, o)
            assertEquals(new.size, n)
            assertEquals(old.size + new.size - 2 * longestCommon(old, new), script.count { it.op != LineDiff.Op.EQUAL }, "shortest script for $old -> $new")
        }
    }

    private fun longestCommon(a: List<String>, b: List<String>): Int {
        val table = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices) for (j in b.indices) table[i + 1][j + 1] = if (a[i] == b[j]) table[i][j] + 1 else maxOf(table[i][j + 1], table[i + 1][j])
        return table[a.size][b.size]
    }

    @Test
    fun `a hunk says where it is, in the new file or, for a deleted file, in the old one`() {
        val modified = diff((1..10).map { "l$it" }, (1..10).map { if (it == 5) "x" else "l$it" })
        val created = diff(emptyList(), lines("only"))
        val deleted = diff(lines("a", "b", "c"), emptyList())
        val deletedOne = diff(lines("a"), emptyList())

        assertEquals("Lines 2–8", modified.hunks.single().label)
        assertEquals("Line 1", created.hunks.single().label)
        assertEquals("Deleted lines 1–3", deleted.hunks.single().label)
        assertEquals("Deleted line 1", deletedOne.hunks.single().label)
    }

    // ---- limits --------------------------------------------------------------------------------------------------

    @Test
    fun `a diff that needs more changed lines than the limit is refused, not cut short`() {
        val old = lines("a", "b")
        val new = lines("c", "d")

        assertNotNull(LineDiff.edits(old, new, maxEdits = 4))
        assertNull(LineDiff.edits(old, new, maxEdits = 3))
    }

    @Test
    fun `two files with nothing in common are refused when they differ in more lines than the limit`() {
        val old = (1..600).map { "old $it" }
        val new = (1..600).map { "new $it" }

        assertNull(LineDiff.diff(old, new))
    }

    @Test
    fun `at most the first lines are listed and the rest are counted`() {
        val result = diff(emptyList(), (1..LineDiff.MAX_SHOWN + 500).map { "l$it" })

        assertEquals(LineDiff.MAX_SHOWN, result.hunks.sumOf { it.lines.size })
        assertEquals(500, result.omittedLines)
        assertEquals(LineDiff.MAX_SHOWN + 500, result.added)
    }

    // ---- what a line is allowed to look like ------------------------------------------------------------------

    @Test
    fun `a very long line is cut and says so, without splitting a character`() {
        val cut = LineDiff.clean("x".repeat(LineDiff.MAX_LINE_CHARS + 50))
        assertEquals(LineDiff.MAX_LINE_CHARS + 1, cut.length)
        assertTrue(cut.endsWith("…"))

        val pair = "😀"
        val atTheEdge = LineDiff.clean("x".repeat(LineDiff.MAX_LINE_CHARS - 1) + pair + "tail")
        assertTrue(atTheEdge.none { it.isHighSurrogate() }, "the pair is dropped whole")
    }

    @Test
    fun `characters that reorder text on screen are swapped for a visible mark`() {
        val cleaned = LineDiff.clean("if (admin\u202E) { // \u2066hidden\u2069")

        assertEquals("if (admin\uFFFD) { // \uFFFDhidden\uFFFD", cleaned)
        assertEquals("plain text", LineDiff.clean("plain text"))
    }
}
