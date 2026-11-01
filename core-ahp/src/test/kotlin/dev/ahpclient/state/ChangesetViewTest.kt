package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.Changeset
import com.microsoft.agenthostprotocol.generated.ChangesetState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChangesetViewTest {
    private fun changeset(status: String = "ready", files: String = "") =
        Ahp.json.decodeFromString(ChangesetState.serializer(), changesetStateJson(status, files))

    @Test
    fun `a file with both sides is modified, only an after side is added, only a before side is deleted`() {
        val view = ChangesetView.from(
            changeset(
                files = listOf(
                    changesetFileJson("work/a.kt"),
                    changesetFileJson("work/new.kt", before = false),
                    changesetFileJson("work/old.kt", after = false),
                ).joinToString(","),
            ),
        )

        assertEquals(listOf(ChangeKind.MODIFIED, ChangeKind.ADDED, ChangeKind.DELETED), view.rows.map { it.kind })
        assertEquals(listOf("/work/a.kt", "/work/new.kt", "/work/old.kt"), view.rows.map { it.path })
        assertEquals(listOf("a.kt", "new.kt", "old.kt"), view.rows.map { it.fileName })
        assertEquals(listOf("/work", "/work", "/work"), view.rows.map { it.directory })
    }

    @Test
    fun `line counts are shown per file and totalled, and a file without them counts as none`() {
        val view = ChangesetView.from(
            changeset(files = listOf(changesetFileJson("a", 3, 1), changesetFileJson("b", 10, 0), changesetFileJson("c", null, null)).joinToString(",")),
        )

        assertEquals(listOf(3L to 1L, 10L to 0L, null to null), view.rows.map { it.added to it.removed })
        assertEquals(13L, view.totalAdded)
        assertEquals(1L, view.totalRemoved)
    }

    @Test
    fun `the reviewed flag is shown, and absent means not reviewed`() {
        val view = ChangesetView.from(changeset(files = listOf(changesetFileJson("a", reviewed = true), changesetFileJson("b")).joinToString(",")))

        assertEquals(listOf(true, false), view.rows.map { it.reviewed })
    }

    @Test
    fun `phases map from the host's words, and one it does not know is never taken as ready`() {
        assertEquals(ChangesetPhase.COMPUTING, ChangesetPhase.of("computing"))
        assertEquals(ChangesetPhase.RECOMPUTING, ChangesetPhase.of("recomputing"))
        assertEquals(ChangesetPhase.READY, ChangesetPhase.of("ready"))
        assertEquals(ChangesetPhase.ERROR, ChangesetPhase.of("error"))
        assertEquals(ChangesetPhase.OTHER, ChangesetPhase.of("sleeping"))
        assertEquals(ChangesetPhase.COMPUTING, ChangesetView.from(changeset("computing")).phase)
    }

    @Test
    fun `at most the first rows are listed and the rest are counted`() {
        val files = (1..ChangesetView.MAX_ROWS + 25).joinToString(",") { changesetFileJson("f$it") }

        val view = ChangesetView.from(changeset(files = files))

        assertEquals(ChangesetView.MAX_ROWS, view.rows.size)
        assertEquals(25, view.omitted)
        assertEquals("/f1", view.rows.first().path)
    }

    @Test
    fun `an empty changeset has no rows and no totals`() {
        val view = ChangesetView.from(changeset())

        assertEquals(emptyList(), view.rows)
        assertEquals(0, view.omitted)
        assertEquals(0L, view.totalAdded)
    }

    @Test
    fun `a template that needs no values is opened as it stands, and one with variables is not offered`() {
        val plain = entry("ahp-changeset:/uncommitted")
        val perTurn = entry("ahp-changeset:/turn/{turnId}")
        val between = entry("ahp-changeset:/diff/{originalTurnId}/{modifiedTurnId}")

        assertEquals("ahp-changeset:/uncommitted", plain.uri)
        assertNull(perTurn.uri)
        assertNull(between.uri)
        assertEquals("Uncommitted", plain.label)
    }

    @Test
    fun `a blank or very long template is never offered, and a blank label gets a name`() {
        assertNull(entry("").uri)
        assertNull(entry("ahp-changeset:/" + "x".repeat(600)).uri)
        assertEquals("Changes", entry("ahp-changeset:/c", label = "").label)
    }

    @Test
    fun `a change row separates the file name from its directory and handles a bare file name`() {
        val deep = ChangeRow("1", "c:/Users/me/vscode-agent-host/src/mcp/mcp-tool-cache.ts", ChangeKind.ADDED, 192, 0, false)
        val bare = ChangeRow("2", "README.md", ChangeKind.MODIFIED, 1, 1, false)
        val root = ChangeRow("3", "/a.kt", ChangeKind.MODIFIED, 1, 0, false)

        assertEquals("mcp-tool-cache.ts", deep.fileName)
        assertEquals("c:/Users/me/vscode-agent-host/src/mcp", deep.directory)
        assertEquals("README.md", bare.fileName)
        assertNull(bare.directory)
        assertEquals("a.kt", root.fileName)
        assertNull(root.directory)
    }

    @Test
    fun `changeset-scoped operations are extracted and resource-only operations are excluded`() {
        val state = Ahp.json.decodeFromString(
            ChangesetState.serializer(),
            """{
                "status":"ready",
                "files":[],
                "operations":[
                    {"id":"stage","label":"Stage all","description":"Stage all files","scopes":["changeset"],"status":"idle"},
                    {"id":"discard","label":"Discard all","scopes":["changeset"],"confirmation":"Discard all uncommitted changes?","status":"idle"},
                    {"id":"file-only","label":"Stage file","scopes":["resource"],"status":"idle"},
                    {"id":"running-op","label":"Commit","scopes":["changeset"],"status":"running"},
                    {"id":"err-op","label":"Push","scopes":["changeset"],"status":"error","error":{"errorType":"E","message":"Push rejected"}},
                    {"id":"disabled-op","label":"Rebase","scopes":["changeset"],"status":"disabled"}
                ]
            }""",
        )

        val view = ChangesetView.from(state)

        assertEquals(listOf("stage", "discard", "running-op", "err-op", "disabled-op"), view.operations.map { it.id })
        assertEquals("Stage all", view.operations[0].label)
        assertEquals("Stage all files", view.operations[0].description)
        assertNull(view.operations[0].confirmation)
        assertTrue(view.operations[0].phase.canInvoke)

        assertEquals("Discard all uncommitted changes?", view.operations[1].confirmation)
        assertEquals(ChangesetOperationPhase.RUNNING, view.operations[2].phase)
        assertFalse(view.operations[2].phase.canInvoke)
        assertEquals(ChangesetOperationPhase.ERROR, view.operations[3].phase)
        assertTrue(view.operations[3].phase.canInvoke)
        assertEquals("Push rejected", view.operations[3].error)
        assertEquals(ChangesetOperationPhase.DISABLED, view.operations[4].phase)
        assertFalse(view.operations[4].phase.canInvoke)
        assertEquals(ChangesetOperationPhase.DISABLED, ChangesetOperationPhase.of("unknown-word"))
    }

    private fun entry(template: String, label: String = "Uncommitted") = ChangesetEntry.from(
        Ahp.json.decodeFromString(Changeset.serializer(), """{"label":"$label","uriTemplate":"$template","changeKind":"uncommitted"}"""),
    )
}
