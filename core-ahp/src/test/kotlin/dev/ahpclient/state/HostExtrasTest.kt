package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ListSessionsResult
import com.microsoft.agenthostprotocol.generated.PartialSessionSummary
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The inline `_meta` shapes are written by hand from the 2026-10-28 census (`docs/verification/extensions.md`); the test over
 * `live/vscode-session-meta.json` uses a redacted capture of the VS Code host (`tools/redact-meta.mjs`).
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
        assertTrue(HostExtras.session(meta("""{"vscode.external":true,"workspaceless":false}""")).isEmpty)
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
    fun `every git shape the VS Code host sent reads, and a session with no git reads as empty`() {
        val metas = Json.parseToJsonElement(fixture("live/vscode-session-meta.json")).jsonObject["metas"]!!.jsonArray.map { it.jsonObject }
        assertTrue(metas.size >= 5, "fixture should hold several shapes")

        metas.forEach { meta ->
            val git = meta["git"]?.jsonObject ?: meta["gitData"]?.jsonObject?.values?.firstOrNull { it.jsonObject.containsKey("branchName") }?.jsonObject
            val extras = HostExtras.session(meta)
            if (git == null) {
                assertTrue(extras.isEmpty, meta.toString())
            } else {
                assertEquals(git["branchName"]?.jsonPrimitive?.content, extras.branch, meta.toString())
                assertEquals(git["outgoingChanges"]?.jsonPrimitive?.int, extras.outgoing, meta.toString())
                assertEquals(git["incomingChanges"]?.jsonPrimitive?.int, extras.incoming, meta.toString())
                assertEquals(git["uncommittedChanges"]?.jsonPrimitive?.int, extras.uncommitted, meta.toString())
            }
        }
        assertTrue(metas.any { it.containsKey("gitData") }, "the multi-root shape should be in the fixture")
        assertTrue(metas.any { !it.containsKey("git") && !it.containsKey("gitData") }, "a session with no git should be in the fixture")
    }

    @Test
    fun `the branch label and counts read the way a person would say them`() {
        val extras = SessionExtras(branch = "feature", outgoing = 2, incoming = 0, uncommitted = 3)

        assertEquals("feature", extras.branchLabel)
        assertEquals("2 outgoing, 3 uncommitted", extras.countsText)
        assertEquals(SessionExtras.DETACHED, SessionExtras(detachedHead = true).branchLabel)
        assertEquals("feature", SessionExtras(branch = "feature", detachedHead = true).branchLabel)
        assertNull(SessionExtras.NONE.branchLabel)
        assertNull(SessionExtras(branch = "b", outgoing = 0, incoming = 0, uncommitted = 0).countsText)
        assertNull(SessionExtras(branch = "b").countsText)
    }

    @Test
    fun `the session list row and the detail carry the branch, and a host without one shows none`() {
        val summary = Ahp.json.decodeFromString(ListSessionsResult.serializer(), fixture("live/list-sessions.json")).items.first()
        val now = java.time.Instant.parse("2026-10-22T12:00:00Z")

        assertNull(SessionListModel.row(summary, now).branch)
        assertEquals("users/x/feature", SessionListModel.row(summary.copy(meta = meta(live)), now).branch)

        val store = SessionStore(SESSION_C)
        store.seed(Ahp.json.decodeFromString(com.microsoft.agenthostprotocol.generated.Snapshot.serializer(), sessionSnapshot(SESSION_C, fromSeq = 1, title = "t")))
        val state = store.state.value!!
        assertTrue(SessionDetail.from(state, now).extras.isEmpty)
        val detail = SessionDetail.from(state.copy(meta = meta(live)), now)
        assertEquals("users/x/feature", detail.extras.branchLabel)
        assertEquals("2 outgoing, 1 incoming, 3 uncommitted", detail.extras.countsText)
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

    @Test
    fun `a session metaChanged action replaces the session's extras and an empty meta clears them`() {
        val store = SessionStore(SESSION_C)
        store.seed(Ahp.json.decodeFromString(com.microsoft.agenthostprotocol.generated.Snapshot.serializer(), sessionSnapshot(SESSION_C, fromSeq = 1, title = "t")))
        assertTrue(store.state.value!!.extras.isEmpty)

        val changed = Ahp.json.decodeFromString(
            com.microsoft.agenthostprotocol.generated.ActionEnvelope.serializer(),
            """{"channel":"$SESSION_C","serverSeq":2,"action":{"type":"session/metaChanged","_meta":$live}}""",
        )
        store.apply(changed)
        assertEquals("users/x/feature", store.state.value!!.extras.branchLabel)
        assertEquals(2, store.state.value!!.extras.outgoing)

        val cleared = Ahp.json.decodeFromString(
            com.microsoft.agenthostprotocol.generated.ActionEnvelope.serializer(),
            """{"channel":"$SESSION_C","serverSeq":3,"action":{"type":"session/metaChanged","_meta":{}}}""",
        )
        store.apply(cleared)
        assertTrue(store.state.value!!.extras.isEmpty)
    }

    @Test
    fun `pull requests are read with their state and branch, and only plain github urls are openable`() {
        val extras = HostExtras.session(
            meta(
                """{"git":{"branchName":"feat","pullRequests":[
                    {"url":"https://github.com/org/repo/pull/42","state":"open","branchName":"feat"},
                    {"link":"https://evil.example/org/repo/pull/42","status":"merged"},
                    {"uri":"https://github.com","state":"closed"},
                    {"url":"http://github.com/org/repo/pull/1","state":"open"},
                    {"url":"https://user:pass@github.com/org/repo/pull/2"}
                ]}}""",
            ),
        )

        assertEquals(5, extras.pullRequests.size)
        val first = extras.pullRequests[0]
        assertEquals(PullRequest("https://github.com/org/repo/pull/42", "open", "feat"), first)
        assertEquals(42, first.number)
        assertEquals("PR #42 · open", first.label)
        assertEquals("https://github.com/org/repo/pull/42", first.openableUrl)

        assertEquals("PR #42 · merged", extras.pullRequests[1].label)
        assertNull(extras.pullRequests[1].openableUrl, "non-github.com URLs are shown as text, never opened")
        assertEquals("PR · closed", extras.pullRequests[2].label)
        assertNull(extras.pullRequests[2].openableUrl, "bare github.com root is not a PR link")
        assertNull(extras.pullRequests[3].openableUrl, "http is refused")
        assertNull(extras.pullRequests[4].openableUrl, "embedded credentials are refused")
        assertEquals("open", extras.pullRequests[4].state, "missing state defaults to open")

        val single = HostExtras.session(meta("""{"pullRequest":{"url":"https://github.com/org/repo/pulls/7","state":"draft"}}"""))
        assertEquals(listOf(PullRequest("https://github.com/org/repo/pulls/7", "draft")), single.pullRequests)
    }

    @Test
    fun `a running tool call's progress message is cleaned to one plain line and ignored once finished`() {
        val running = Ahp.json.decodeFromString(
            com.microsoft.agenthostprotocol.generated.ToolCallState.serializer(),
            """{"status":"running","toolCallId":"c1","toolName":"t","displayName":"Run","invocationMessage":"m","toolInput":"{}","confirmed":"not-needed",
                "_meta":{"progressMessage":"Running `gradle test`\nin [core-ahp](file:///x)"}}""",
        )
        val completed = Ahp.json.decodeFromString(
            com.microsoft.agenthostprotocol.generated.ToolCallState.serializer(),
            """{"status":"completed","toolCallId":"c1","toolName":"t","displayName":"Run","invocationMessage":"m","toolInput":"{}","confirmed":"not-needed",
                "success":true,"pastTenseMessage":"Done","_meta":{"progressMessage":"Running gradle test"}}""",
        )
        val vscode = HostExtras.stepRunning(meta("""{"vscode.toolCall.progressMessage":"Searching **files**"}"""))
        val ahpd = HostExtras.stepRunning(meta("""{"ahpd.progressMessage":"Indexing"}"""))
        val oversized = HostExtras.stepRunning(meta("""{"progressMessage":"${"x".repeat(1_000)}"}"""))

        assertEquals(StepExtras("Running gradle test in core-ahp"), running.extras)
        assertTrue(completed.extras.isEmpty, "once finished, the normal result view takes over")
        assertEquals("Searching files", vscode.progressMessage)
        assertEquals("Indexing", ahpd.progressMessage)
        assertEquals(HostExtras.MAX_TEXT, oversized.progressMessage!!.length)
        assertTrue(HostExtras.stepRunning(meta("""{"progressMessage":42}""")).isEmpty)
    }

    @Test
    fun `a message flagged hidden by either spelling is marked hidden, and a string flag is ignored`() {
        assertTrue(HostExtras.message(meta("""{"vscode.chat.hiddenFromTranscript":true}""")).hiddenFromTranscript)
        assertTrue(HostExtras.message(meta("""{"vscode":{"chat":{"hiddenFromTranscript":true}}}""")).hiddenFromTranscript)
        assertTrue(HostExtras.message(meta("""{"requestHiddenFromTranscript":true}""")).hiddenFromTranscript)
        assertFalse(HostExtras.message(meta("""{"vscode.chat.hiddenFromTranscript":"true"}""")).hiddenFromTranscript)
        assertFalse(HostExtras.message(meta("""{"vscode.chat.hiddenFromTranscript":false}""")).hiddenFromTranscript)
        assertTrue(HostExtras.message(null).isEmpty)
    }

    @Test
    fun `toolKind, completed duration, mcp server, and subagent fields read from both hosts' tool-call _meta shapes`() {
        val calls = Json.parseToJsonElement(fixture("live/ahpd-tool-meta.json")).jsonObject["toolCalls"]!!.jsonArray.map {
            Ahp.json.decodeFromJsonElement(com.microsoft.agenthostprotocol.generated.ToolCallState.serializer(), it)
        }
        assertEquals(4, calls.size)

        assertEquals(StepExtras(toolKind = "terminal"), calls[0].extras, "a running call has no completed duration")
        assertNull(calls[0].extras.durationText)

        assertEquals(StepExtras(toolKind = "terminal", durationMs = 4250L), calls[1].extras)
        assertEquals("4s", calls[1].extras.durationText)

        assertEquals(StepExtras(toolKind = "mcp", mcpServerName = "server-redacted-1"), calls[2].extras)
        assertEquals(
            StepExtras(toolKind = "subagent", subagentAgentName = "agent-redacted-1", subagentDescription = "lorem ipsum dolor"),
            calls[3].extras,
        )

        val runningWithDuration = HostExtras.stepMeta(meta("""{"ahpd.durationMs":1200}"""), running = true, completed = false)
        assertNull(runningWithDuration.durationMs, "durationMs is only meaningful once the call has completed")
        val negativeDuration = HostExtras.stepMeta(meta("""{"ahpd.durationMs":-5}"""), running = false, completed = true)
        assertNull(negativeDuration.durationMs)
        val stringDuration = HostExtras.stepMeta(meta("""{"ahpd.durationMs":"1200"}"""), running = false, completed = true)
        assertNull(stringDuration.durationMs)
    }

    @Test
    fun `different host spellings for branch, counts, and model produce the same SessionExtras`() {
        val vscodeShape = HostExtras.session(
            meta("""{"git":{"branchName":"feat/x","outgoingChanges":2,"incomingChanges":1,"uncommittedChanges":3},"model":"claude-sonnet"}"""),
        )
        val conformanceShape = HostExtras.session(
            meta("""{"git":{"branch":"feat/x","ahead":2,"behind":1,"uncommitted":3},"ahpd.model":"claude-sonnet"}"""),
        )
        val nestedAhpdShape = HostExtras.session(
            meta("""{"branch":"feat/x","ahead":2,"behind":1,"uncommitted":3,"ahpd":{"model":"claude-sonnet"}}"""),
        )

        val expected = SessionExtras(branch = "feat/x", outgoing = 2, incoming = 1, uncommitted = 3, model = "claude-sonnet")
        assertEquals(expected, vscodeShape)
        assertEquals(expected, conformanceShape)
        assertEquals(expected, nestedAhpdShape)
    }
}
