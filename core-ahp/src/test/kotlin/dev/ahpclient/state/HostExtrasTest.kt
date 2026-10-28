package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ListSessionsResult
import com.microsoft.agenthostprotocol.generated.PartialSessionSummary
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `_meta` shapes below are the ones the 2026-10-28 census saw from the VS Code host (`docs/verification/extensions.md`), written by
 * hand; they are replaced by redacted captures when AC-E0.4 is done.
 */
class HostExtrasTest {
    private fun meta(json: String): Map<String, JsonElement> = Json.parseToJsonElement(json).jsonObject

    private val live = """
        {"git":{"branchName":"users/x/feature","baseBranchName":"main","defaultBranchName":"main","upstreamBranchName":"origin/users/x/feature",
        "outgoingChanges":2,"incomingChanges":1,"uncommittedChanges":3,"hasGitHubRemote":true,"hasGitRemote":true,"hasBaseBranchChanges":true},
        "vscode.external":false,"workspaceless":false}
    """.trimIndent()

    @Test
    fun `a branch and its counts are read from the git object`() {
        val extras = HostExtras.session(meta(live))

        assertEquals(SessionExtras(branch = "users/x/feature", outgoing = 2, incoming = 1, uncommitted = 3), extras)
    }

    @Test
    fun `a dotted key and a nested object read the same`() {
        val flat = HostExtras.session(meta("""{"git.branchName":"b","git.outgoingChanges":4}"""))
        val nested = HostExtras.session(meta("""{"git":{"branchName":"b","outgoingChanges":4}}"""))

        assertEquals(nested, flat)
        assertEquals(SessionExtras(branch = "b", outgoing = 4), flat)
    }

    @Test
    fun `a multi-root session reads the first folder that has a branch`() {
        val extras = HostExtras.session(meta("""{"gitData":{"folderA":{"hasGitRemote":false},"folderB":{"branchName":"feature","uncommittedChanges":1}}}"""))

        assertEquals(SessionExtras(branch = "feature", uncommitted = 1), extras)
    }

    @Test
    fun `git wins over gitData when both are sent`() {
        val extras = HostExtras.session(meta("""{"git":{"branchName":"one"},"gitData":{"f":{"branchName":"two"}}}"""))

        assertEquals("one", extras.branch)
    }

    @Test
    fun `a detached head has no branch and says so`() {
        val extras = HostExtras.session(meta("""{"git":{"isDetachedHead":true,"uncommittedChanges":0}}"""))

        assertNull(extras.branch)
        assertTrue(extras.detachedHead)
        assertEquals(0, extras.uncommitted)
    }

    @Test
    fun `a host that sends nothing gives the empty value`() {
        assertTrue(HostExtras.session(null).isEmpty)
        assertTrue(HostExtras.session(emptyMap()).isEmpty)
        assertTrue(HostExtras.session(meta("""{"ahpd.model":"m","vscode.external":true}""")).isEmpty)
    }

    @Test
    fun `wrong types give defaults and no exception`() {
        val values = listOf(
            """{"git":"main"}""",
            """{"git":[1,2]}""",
            """{"git":{"branchName":5,"outgoingChanges":"2","incomingChanges":1.5,"uncommittedChanges":-1,"isDetachedHead":"true"}}""",
            """{"git":{"branchName":{"a":1},"outgoingChanges":null,"isDetachedHead":null}}""",
            """{"gitData":["x"]}""",
            """{"gitData":{"f":7}}""",
        )

        values.forEach { assertTrue(HostExtras.session(meta(it)).isEmpty, it) }
    }

    @Test
    fun `an oversized or hostile branch is cut and cleaned`() {
        val long = HostExtras.session(meta("""{"git":{"branchName":"${"a".repeat(1_000_000)}"}}"""))
        val control = HostExtras.session(meta("""{"git":{"branchName":"  mai\u0000n\u001b[31m\n  "}}"""))
        val blank = HostExtras.session(meta("""{"git":{"branchName":" \t "}}"""))

        assertEquals(HostExtras.MAX_TEXT, long.branch!!.length)
        assertEquals("main[31m", control.branch)
        assertFalse(control.branch!!.any { it.isISOControl() })
        assertNull(blank.branch)
    }

    @Test
    fun `an absurd count is dropped, not clamped`() {
        val extras = HostExtras.session(meta("""{"git":{"branchName":"b","outgoingChanges":99999999999,"incomingChanges":2000000}}"""))

        assertNull(extras.outgoing)
        assertNull(extras.incoming)
        assertEquals("b", extras.branch)
    }

    @Test
    fun `a summary carries its extras and a new meta replaces the old one whole`() {
        val summary = Ahp.json.decodeFromString(ListSessionsResult.serializer(), fixture("live/list-sessions.json")).items.first()
            .copy(meta = meta(live))
        assertEquals("users/x/feature", summary.extras.branch)

        val moved = SessionCatalog.merge(summary, PartialSessionSummary(meta = meta("""{"git":{"branchName":"other"}}""")))
        assertEquals(SessionExtras(branch = "other"), moved.extras)

        val removed = SessionCatalog.merge(summary, PartialSessionSummary(meta = JsonObject(emptyMap())))
        assertTrue(removed.extras.isEmpty)

        val untouched = SessionCatalog.merge(summary, PartialSessionSummary(activity = "x"))
        assertEquals("users/x/feature", untouched.extras.branch)
    }
}
