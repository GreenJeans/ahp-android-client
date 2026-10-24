package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which folders a new session may start in: recent ones first, and never the host's working copies or scratch folders. */
class RecentFoldersTest {
    private fun summary(modified: String, dirs: List<String>, project: String? = null, n: Int = 0) = Ahp.json.decodeFromString(
        SessionSummary.serializer(),
        """{"resource":"ahp-session:/s$n","provider":"copilotcli","title":"T","status":1,"createdAt":"2026-10-01T09:00:00.000Z","modifiedAt":"$modified",
            "workingDirectories":[${dirs.joinToString(",") { "\"$it\"" }}]${project?.let { ""","project":{"uri":"$it","displayName":"p"}""" }.orEmpty()}}""",
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
}
