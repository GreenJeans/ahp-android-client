package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ChatOrigin
import com.microsoft.agenthostprotocol.generated.ListSessionsResult
import com.microsoft.agenthostprotocol.generated.SessionChatSummary
import com.microsoft.agenthostprotocol.generated.SessionOrigin
import com.microsoft.agenthostprotocol.generated.SessionStatus
import com.microsoft.agenthostprotocol.generated.SessionSummary
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

class SessionFilterTest {
    private val baseSession: SessionSummary =
        Ahp.json.decodeFromString(ListSessionsResult.serializer(), fixture("live/list-sessions.json")).items.first { it.title == "Session B" }
    private val now = Instant.parse("2026-04-20T12:00:00Z")

    private fun meta(json: String): JsonObject = Ahp.json.decodeFromString(JsonObject.serializer(), json)

    private fun session(
        resource: String = "ahp-session:/1",
        provider: String = "claude",
        title: String = "Session 1",
        status: SessionStatus = SessionStatus.IDLE or SessionStatus.IS_READ,
        createdAt: String = "2026-04-19T10:00:00Z",
        modifiedAt: String = "2026-04-20T11:00:00Z",
        workingDirectories: List<String>? = listOf("file:///home/user/repo-a"),
        activity: String? = null,
        origin: SessionOrigin? = null,
        chats: List<SessionChatSummary>? = baseSession.chats,
        metaJson: String? = null,
    ): SessionSummary = baseSession.copy(
        resource = resource,
        provider = provider,
        title = title,
        status = status,
        createdAt = createdAt,
        modifiedAt = modifiedAt,
        workingDirectories = workingDirectories,
        project = null,
        activity = activity,
        origin = origin,
        chats = chats,
        meta = metaJson?.let(::meta),
    )

    @Test
    fun `external tool-spawned and automation sessions are hidden by default and shown when showExternal is true`() {
        val regular = session(resource = "ahp-session:/regular", title = "Regular")
        val externalMeta = session(
            resource = "ahp-session:/ext",
            title = "External CLI",
            metaJson = """{"vscode.external":true}""",
        )
        val toolOrigin = Ahp.json.decodeFromString(ChatOrigin.serializer(), """{"kind":"tool","toolCallId":"call-1","chat":"ahp-chat:/parent"}""")
        val toolChat = baseSession.chats!!.first().copy(origin = toolOrigin)
        val toolSpawned = session(
            resource = "ahp-session:/tool",
            title = "Subagent",
            chats = listOf(toolChat),
        )
        val automationOrigin = Ahp.json.decodeFromString(SessionOrigin.serializer(), """{"kind":"automation","automation":"ahp-auto:/1","run":"run-1"}""")
        val automation = session(
            resource = "ahp-session:/auto",
            title = "Scheduled hook",
            origin = automationOrigin,
        )
        val entries = listOf(regular, externalMeta, toolSpawned, automation).map {
            HostSessionEntry(hostId = "h1", hostName = "Workstation", session = it)
        }

        val defaultModel = SessionListModel.fromEntries(entries, filter = SessionFilter.DEFAULT, now = now, zone = ZoneOffset.UTC)
        assertEquals(listOf("Regular"), defaultModel.rows.map { it.title })
        assertEquals(3, defaultModel.hiddenExternal)
        assertEquals(3, defaultModel.hiddenByFilter)

        val withExternal = SessionListModel.fromEntries(
            entries,
            filter = SessionFilter(showExternal = true),
            now = now,
            zone = ZoneOffset.UTC,
        )
        assertEquals(4, withExternal.rows.size)
        assertEquals(0, withExternal.hiddenExternal)
    }

    @Test
    fun `a session waiting on input is never hidden by external done recent or archived filters`() {
        val waitingExternalOld = session(
            resource = "ahp-session:/waiting-ext",
            title = "Waiting external",
            status = SessionStatus.INPUT_NEEDED or SessionStatus.IS_ARCHIVED,
            createdAt = "2026-01-01T00:00:00Z",
            modifiedAt = "2026-01-02T00:00:00Z",
            metaJson = """{"vscode.external":true}""",
        )
        val entry = HostSessionEntry("h1", "Host 1", waitingExternalOld)

        val model = SessionListModel.fromEntries(
            listOf(entry),
            filter = SessionFilter(
                showExternal = false,
                showDone = false,
                showArchived = false,
                showScope = SessionShowScope.RECENT,
            ),
            now = now,
            zone = ZoneOffset.UTC,
        )
        assertEquals(listOf("Waiting external"), model.rows.map { it.title })
    }

    @Test
    fun `ordering switches between updated and created while keeping needs input first`() {
        val olderCreatedNewerModified = session(
            resource = "ahp-session:/1",
            title = "Older created, newer modified",
            createdAt = "2026-04-15T10:00:00Z",
            modifiedAt = "2026-04-20T11:30:00Z",
        )
        val newerCreatedOlderModified = session(
            resource = "ahp-session:/2",
            title = "Newer created, older modified",
            createdAt = "2026-04-19T10:00:00Z",
            modifiedAt = "2026-04-19T11:00:00Z",
        )
        val waitingOld = session(
            resource = "ahp-session:/3",
            title = "Needs input",
            status = SessionStatus.INPUT_NEEDED,
            createdAt = "2026-04-10T10:00:00Z",
            modifiedAt = "2026-04-10T11:00:00Z",
        )
        val entries = SessionCatalog.sortedEntries(
            listOf(olderCreatedNewerModified, newerCreatedOlderModified, waitingOld).map {
                HostSessionEntry("h1", "Host 1", it)
            },
        )

        val byUpdated = SessionListModel.fromEntries(
            entries,
            filter = SessionFilter(ordering = SessionOrdering.UPDATED),
            now = now,
            zone = ZoneOffset.UTC,
        )
        assertEquals(
            listOf("Needs input", "Older created, newer modified", "Newer created, older modified"),
            byUpdated.rows.map { it.title },
        )

        val byCreated = SessionListModel.fromEntries(
            entries,
            filter = SessionFilter(ordering = SessionOrdering.CREATED),
            now = now,
            zone = ZoneOffset.UTC,
        )
        assertEquals(
            listOf("Needs input", "Newer created, older modified", "Older created, newer modified"),
            byCreated.rows.map { it.title },
        )
    }

    @Test
    fun `showScope RECENT hides idle sessions older than 14 days or beyond cap while ALL shows them`() {
        val recentIdle = session(
            resource = "ahp-session:/recent",
            title = "Recent idle",
            modifiedAt = "2026-04-18T12:00:00Z",
        )
        val oldIdle = session(
            resource = "ahp-session:/old",
            title = "Old idle",
            createdAt = "2026-03-01T12:00:00Z",
            modifiedAt = "2026-03-05T12:00:00Z",
        )
        val oldWorking = session(
            resource = "ahp-session:/working",
            title = "Old but working",
            status = SessionStatus.IN_PROGRESS,
            createdAt = "2026-03-01T12:00:00Z",
            modifiedAt = "2026-03-05T12:00:00Z",
        )
        val entries = listOf(recentIdle, oldIdle, oldWorking).map { HostSessionEntry("h1", "Host 1", it) }

        val recentModel = SessionListModel.fromEntries(
            entries,
            filter = SessionFilter(showScope = SessionShowScope.RECENT),
            now = now,
            zone = ZoneOffset.UTC,
        )
        assertEquals(listOf("Recent idle", "Old but working"), recentModel.rows.map { it.title })

        val allModel = SessionListModel.fromEntries(
            entries,
            filter = SessionFilter(showScope = SessionShowScope.ALL),
            now = now,
            zone = ZoneOffset.UTC,
        )
        assertEquals(listOf("Recent idle", "Old idle", "Old but working"), allModel.rows.map { it.title })
    }

    @Test
    fun `showDone false hides read idle sessions but keeps unread working error and waiting sessions`() {
        val readIdle = session(
            resource = "ahp-session:/read-idle",
            title = "Done read",
            status = SessionStatus.IDLE or SessionStatus.IS_READ,
        )
        val unreadIdle = session(
            resource = "ahp-session:/unread-idle",
            title = "Done unread",
            status = SessionStatus.IDLE,
        )
        val working = session(
            resource = "ahp-session:/working",
            title = "Working",
            status = SessionStatus.IN_PROGRESS or SessionStatus.IS_READ,
        )
        val entries = listOf(readIdle, unreadIdle, working).map { HostSessionEntry("h1", "Host 1", it) }

        val hideDone = SessionListModel.fromEntries(
            entries,
            filter = SessionFilter(showDone = false),
            now = now,
            zone = ZoneOffset.UTC,
        )
        assertEquals(listOf("Done unread", "Working"), hideDone.rows.map { it.title })
    }

    @Test
    fun `facet filters narrow by host provider workspace and initiator and expose available facets`() {
        val s1 = HostSessionEntry(
            hostId = "host-laptop",
            hostName = "Laptop",
            session = session(
                resource = "ahp-session:/1",
                provider = "claude",
                title = "Laptop Claude",
                workingDirectories = listOf("file:///Users/tyler/ahp-android"),
                metaJson = """{"vscode.session.initiator.name":"VS Code"}""",
            ),
        )
        val s2 = HostSessionEntry(
            hostId = "host-cloud",
            hostName = "Cloud Box",
            session = session(
                resource = "ahp-session:/2",
                provider = "copilotcli",
                title = "Cloud Copilot",
                workingDirectories = listOf("file:///workspaces/backend-service"),
                metaJson = """{"vscode.session.initiator.name":"GitHub Web"}""",
            ),
        )
        val entries = listOf(s1, s2)

        val unfiltered = SessionListModel.fromEntries(entries, filter = SessionFilter.DEFAULT, now = now, zone = ZoneOffset.UTC)
        assertEquals(2, unfiltered.availableHosts.size)
        assertEquals(2, unfiltered.availableProviders.size)
        assertEquals(2, unfiltered.availableWorkspaces.size)
        assertEquals(2, unfiltered.availableInitiators.size)

        val hostFiltered = SessionListModel.fromEntries(
            entries,
            filter = SessionFilter.DEFAULT.toggleHost("host-cloud"),
            now = now,
            zone = ZoneOffset.UTC,
        )
        assertEquals(listOf("Cloud Copilot"), hostFiltered.rows.map { it.title })

        val providerFiltered = SessionListModel.fromEntries(
            entries,
            filter = SessionFilter.DEFAULT.toggleProvider("claude"),
            now = now,
            zone = ZoneOffset.UTC,
        )
        assertEquals(listOf("Laptop Claude"), providerFiltered.rows.map { it.title })

        val workspaceFiltered = SessionListModel.fromEntries(
            entries,
            filter = SessionFilter.DEFAULT.toggleWorkspace("ahp-android"),
            now = now,
            zone = ZoneOffset.UTC,
        )
        assertEquals(listOf("Laptop Claude"), workspaceFiltered.rows.map { it.title })

        val initiatorFiltered = SessionListModel.fromEntries(
            entries,
            filter = SessionFilter.DEFAULT.toggleInitiator("GitHub Web"),
            now = now,
            zone = ZoneOffset.UTC,
        )
        assertEquals(listOf("Cloud Copilot"), initiatorFiltered.rows.map { it.title })
    }

    @Test
    fun `grouping organizes rows by time workspace and host`() {
        val sWaiting = HostSessionEntry(
            hostId = "h1",
            hostName = "Laptop",
            session = session(
                resource = "ahp-session:/3",
                title = "Waiting session",
                status = SessionStatus.INPUT_NEEDED,
                modifiedAt = "2026-04-15T08:00:00Z",
                workingDirectories = null,
            ),
        )
        val s1 = HostSessionEntry(
            hostId = "h1",
            hostName = "Laptop",
            session = session(
                resource = "ahp-session:/1",
                title = "Today session",
                modifiedAt = "2026-04-20T10:00:00Z",
                workingDirectories = listOf("file:///work/alpha"),
            ),
        )
        val s2 = HostSessionEntry(
            hostId = "h2",
            hostName = "Desktop",
            session = session(
                resource = "ahp-session:/2",
                title = "Yesterday session",
                modifiedAt = "2026-04-19T08:00:00Z",
                workingDirectories = listOf("file:///work/beta"),
            ),
        )
        val entries = listOf(sWaiting, s1, s2)

        val byTime = SessionListModel.fromEntries(
            entries,
            filter = SessionFilter(grouping = SessionGrouping.TIME),
            now = now,
            zone = ZoneOffset.UTC,
        )
        assertEquals(listOf("Needs you", "Today", "Yesterday"), byTime.groups.map { it.title })

        val byWorkspace = SessionListModel.fromEntries(
            entries,
            filter = SessionFilter(grouping = SessionGrouping.WORKSPACE),
            now = now,
            zone = ZoneOffset.UTC,
        )
        assertEquals(listOf("alpha", "beta", "No workspace"), byWorkspace.groups.map { it.title })

        val byHost = SessionListModel.fromEntries(
            entries,
            filter = SessionFilter(grouping = SessionGrouping.HOST),
            now = now,
            zone = ZoneOffset.UTC,
        )
        assertEquals(listOf("Laptop", "Desktop"), byHost.groups.map { it.title })
    }

    @Test
    fun `activeCount and isDefault reflect non-default filter selections`() {
        assertTrue(SessionFilter.DEFAULT.isDefault)
        assertEquals(0, SessionFilter.DEFAULT.activeCount)

        val modified = SessionFilter.DEFAULT
            .copy(
                ordering = SessionOrdering.CREATED,
                grouping = SessionGrouping.WORKSPACE,
                showScope = SessionShowScope.ALL,
                showExternal = true,
                showDone = false,
                showArchived = true,
            )
            .toggleHost("h1")
            .toggleProvider("claude")
            .toggleWorkspace("repo")
            .toggleInitiator("VS Code")

        assertFalse(modified.isDefault)
        assertEquals(9, modified.activeCount)
        assertTrue(
            modified
                .toggleHost("h1")
                .toggleProvider("claude")
                .toggleWorkspace("repo")
                .toggleInitiator("VS Code")
                .copy(
                    ordering = SessionOrdering.UPDATED,
                    grouping = SessionGrouping.NONE,
                    showScope = SessionShowScope.RECENT,
                    showExternal = false,
                    showDone = true,
                    showArchived = false,
                ).isDefault,
        )
    }

    @Test
    fun `inbox fromEntries preserves hostId and hostName across connected hosts`() {
        val waitingOnH1 = HostSessionEntry(
            hostId = "h1",
            hostName = "Host One",
            session = session(
                resource = "ahp-session:/w1",
                title = "Needs approval",
                status = SessionStatus.INPUT_NEEDED,
            ),
        )
        val finishedOnH2 = HostSessionEntry(
            hostId = "h2",
            hostName = "Host Two",
            session = session(
                resource = "ahp-session:/f2",
                title = "Unread finished",
                status = SessionStatus.IDLE,
                modifiedAt = "2026-04-20T11:00:00Z",
            ),
        )
        val inbox = Inbox.fromEntries(listOf(waitingOnH1, finishedOnH2), now)
        assertEquals(1, inbox.waiting.size)
        assertEquals("h1", inbox.waiting.single().row.hostId)
        assertEquals("Host One", inbox.waiting.single().row.hostName)
        assertEquals(1, inbox.finished.size)
        assertEquals("h2", inbox.finished.single().row.hostId)
        assertEquals("Host Two", inbox.finished.single().row.hostName)
    }
}
