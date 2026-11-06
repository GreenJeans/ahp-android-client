package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which folders a new session may start in: recent ones first, and never the host's working copies or scratch folders. */
class RecentFoldersTest {
    private fun summary(modified: String, dirs: List<String>, project: String? = null, n: Int = 0, metaJson: String? = null) = Ahp.json.decodeFromString(
        SessionSummary.serializer(),
        """{"resource":"ahp-session:/s$n","provider":"copilotcli","title":"T","status":1,"createdAt":"2026-10-01T09:00:00.000Z","modifiedAt":"$modified",
            "workingDirectories":[${dirs.joinToString(",") { "\"$it\"" }}]${project?.let { ""","project":{"uri":"$it","displayName":"p"}""" }.orEmpty()}${metaJson?.let { ""","_meta":$it""" }.orEmpty()}}""",
    )

    private val repoA = "file:///c%3A/src/repos/repo-a"
    private val repoB = "file:///c%3A/src/repos/repo-b"

    @Test
    fun `the most recently used folder comes first, each folder once`() {
        val sessions = listOf(
            summary("2026-10-20T10:00:00.000Z", listOf(repoA), n = 1),
            summary("2026-10-22T10:00:00.000Z", listOf(repoB), n = 2),
            summary("2026-10-21T10:00:00.000Z", listOf(repoA), n = 3),
        )

        assertEquals(listOf(repoB, repoA), RecentFolders.from(sessions))
    }

    @Test
    fun `sessions with the same time keep the host's own order`() {
        val sessions = listOf(summary("2026-10-20T10:00:00.000Z", listOf(repoB), n = 1), summary("2026-10-20T10:00:00.000Z", listOf(repoA), n = 2))

        assertEquals(listOf(repoB, repoA), RecentFolders.from(sessions))
    }

    @Test
    fun `a time that cannot be read sorts last`() {
        val sessions = listOf(summary("not a time", listOf(repoA), n = 1), summary("2026-10-01T10:00:00.000Z", listOf(repoB), n = 2))

        assertEquals(listOf(repoB, repoA), RecentFolders.from(sessions))
    }

    @Test
    fun `a worktree folder is not offered, its project is`() {
        val worktree = "file:///c%3A/src/repos/repo-a.worktrees/fix-the-build"
        val sessions = listOf(summary("2026-10-22T10:00:00.000Z", listOf(worktree), project = repoA, n = 1))

        assertEquals(listOf(repoA), RecentFolders.from(sessions))
    }

    @Test
    fun `a worktree with no known project is simply left out`() {
        val worktree = "file:///c%3A/src/repos/repo-a.worktrees/fix-the-build"

        assertEquals(listOf(repoB), RecentFolders.from(listOf(summary("2026-10-22T10:00:00.000Z", listOf(worktree), n = 1), summary("2026-10-21T10:00:00.000Z", listOf(repoB), n = 2))))
    }

    @Test
    fun `a folder outside its own project is a working copy, so its project is offered instead`() {
        val elsewhere = "file:///c%3A/tmp/wt-task1"

        assertEquals(listOf(repoA), RecentFolders.from(listOf(summary("2026-10-22T10:00:00.000Z", listOf(elsewhere), project = repoA, n = 1))))
    }

    @Test
    fun `a folder inside its project, or the project itself, is kept as it is`() {
        val sub = "$repoA/packages/web"
        val sessions = listOf(summary("2026-10-22T10:00:00.000Z", listOf(sub), project = repoA, n = 1), summary("2026-10-21T10:00:00.000Z", listOf(repoA), project = repoA, n = 2))

        assertEquals(listOf(sub, repoA), RecentFolders.from(sessions))
    }

    @Test
    fun `the project is not offered when it is itself a working copy or scratch folder`() {
        val worktree = "file:///c%3A/src/repos/repo-a.worktrees/x"
        val scratch = "file:///c%3A/Users/me/.copilot/chats/1234"
        val sessions = listOf(
            summary("2026-10-22T10:00:00.000Z", listOf("file:///c%3A/tmp/a"), project = worktree, n = 1),
            summary("2026-10-21T10:00:00.000Z", listOf("file:///c%3A/tmp/b"), project = scratch, n = 2),
        )

        assertEquals(emptyList(), RecentFolders.from(sessions))
    }

    @Test
    fun `the host's own scratch folders are left out`() {
        val sessions = listOf(
            summary("2026-10-22T10:00:00.000Z", listOf("file:///c%3A/Users/me/.copilot/chats/1234"), n = 1),
            summary("2026-10-21T10:00:00.000Z", listOf("file:///c%3A/Users/me/.copilot/session-state/9/files/x"), n = 2),
            summary("2026-10-20T10:00:00.000Z", listOf(repoA), n = 3),
        )

        assertEquals(listOf(repoA), RecentFolders.from(sessions))
    }

    @Test
    fun `a folder that only looks like a scratch folder is kept`() {
        val lookalike = "file:///c%3A/src/repos/my.copilot-notes"

        assertEquals(listOf(lookalike), RecentFolders.from(listOf(summary("2026-10-22T10:00:00.000Z", listOf(lookalike), n = 1))))
    }

    @Test
    fun `a session in several folders offers each, in its own order`() {
        assertEquals(listOf(repoB, repoA), RecentFolders.from(listOf(summary("2026-10-22T10:00:00.000Z", listOf(repoB, repoA), n = 1))))
    }

    @Test
    fun `no sessions means no folders`() {
        assertEquals(emptyList(), RecentFolders.from(emptyList()))
    }

    @Test
    fun `trailing slashes and letter case do not make one folder two`() {
        val sessions = listOf(
            summary("2026-10-22T10:00:00.000Z", listOf("file:///c%3A/src/repos/repo-a.worktrees/x/"), project = "file:///c%3A/src/repos/REPO-A/", n = 1),
            summary("2026-10-21T10:00:00.000Z", listOf("file:///c%3A/src/repos/REPO-A/packages"), project = "file:///c%3A/src/repos/repo-a", n = 2),
        )

        assertEquals(listOf("file:///c%3A/src/repos/REPO-A/", "file:///c%3A/src/repos/REPO-A/packages"), RecentFolders.from(sessions))
    }

    @Test
    fun `claude, antigravity, and copilot worktree folders are treated as working copies`() {
        val claudeWt = "file:///c%3A/src/repos/repo-a/.claude/worktrees/u6-merge"
        val agyWt = "file:///c%3A/Users/me/.gemini/antigravity/worktrees/repo-a/fix"
        val copilotWt = "file:///c%3A/Users/me/copilot-worktrees/repo-b/branch-1"
        val sessions = listOf(
            summary("2026-10-23T10:00:00.000Z", listOf(claudeWt), project = repoA, n = 1),
            summary("2026-10-22T10:00:00.000Z", listOf(agyWt), n = 2),
            summary("2026-10-21T10:00:00.000Z", listOf(copilotWt), project = repoB, n = 3),
        )

        assertEquals(listOf(repoA, repoB), RecentFolders.from(sessions))
    }

    @Test
    fun `OS temporary folders are left out unless the session has a real project folder`() {
        val winTemp = "file:///c%3A/Users/me/AppData/Local/Temp/nexus-gate-cli-probe-20261104-054603"
        val winShortTemp = "file:///c%3A/Users/ME~1/AppData/Local/Temp/nexus-routing-eval-8opFvb"
        val unixTmp = "file:///tmp/scratch-probe"
        val macTmp = "file:///var/folders/ab/cd1234/T/probe"
        val sessions = listOf(
            summary("2026-10-25T10:00:00.000Z", listOf(winTemp), n = 1),
            summary("2026-10-24T10:00:00.000Z", listOf(winShortTemp), n = 2),
            summary("2026-10-23T10:00:00.000Z", listOf(unixTmp), project = repoA, n = 3),
            summary("2026-10-22T10:00:00.000Z", listOf(macTmp), n = 4),
            summary("2026-10-21T10:00:00.000Z", listOf(repoB), n = 5),
        )

        assertEquals(listOf(repoA, repoB), RecentFolders.from(sessions))
    }

    @Test
    fun `non-local project URLs are never offered`() {
        val tempDir = "file:///c%3A/Users/me/AppData/Local/Temp/wu-smoke/s6"
        val githubUrl = "https://github.com/example/wu-smoke"
        val sessions = listOf(
            summary("2026-10-22T10:00:00.000Z", listOf(tempDir), project = githubUrl, n = 1),
            summary("2026-10-21T10:00:00.000Z", listOf(githubUrl), n = 2),
            summary("2026-10-20T10:00:00.000Z", listOf(repoA), n = 3),
        )

        assertEquals(listOf(repoA), RecentFolders.from(sessions))
    }

    @Test
    fun `workspaceless sessions and host-marked scratch folders are left out`() {
        val scratchDir = "file:///c%3A/Users/me/scratch-1"
        val sessions = listOf(
            summary("2026-10-23T10:00:00.000Z", listOf(scratchDir), n = 1, metaJson = """{"workspaceless":true}"""),
            summary("2026-10-22T10:00:00.000Z", listOf(scratchDir, repoA), n = 2, metaJson = """{"vscode.scratchFolders":["$scratchDir"]}"""),
        )

        assertEquals(listOf(repoA), RecentFolders.from(sessions))
    }

    @Test
    fun `folders reported missing by the host are left out regardless of slash or case`() {
        val sessions = listOf(
            summary("2026-10-22T10:00:00.000Z", listOf(repoA), n = 1),
            summary("2026-10-21T10:00:00.000Z", listOf(repoB), n = 2),
        )

        assertEquals(listOf(repoB), RecentFolders.from(sessions, missing = setOf("file:///c%3A/src/repos/REPO-A/")))
    }
}
