package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.SessionSummary
import dev.ahpclient.platform.INSTANT_EPOCH
import dev.ahpclient.platform.Instant
import dev.ahpclient.platform.instantNow
import dev.ahpclient.platform.parseInstant
import dev.ahpclient.platform.ZoneId
import dev.ahpclient.platform.systemDefaultZone

data class SessionRow(
    val resource: String,
    val title: String,
    /** What the session is doing now, or else its project: the most useful second line a row has. */
    val subtitle: String?,
    val state: ActivityState,
    val isRead: Boolean,
    val isArchived: Boolean,
    val whenText: String,
    val chatCount: Int,
    val changes: ChangeStats? = null,
    /** The git branch the host reports for the session, or null when it reports none (E2). */
    val branch: String? = null,
    /** Which host this session belongs to, when known. */
    val hostId: String? = null,
    /** The host's display name, carried when sessions from several hosts are shown together. */
    val hostName: String? = null,
    /** The agent harness / provider id (for example `claude` or `copilotcli`). */
    val provider: String = "",
    /** The workspace or repository name, when the session has one. */
    val workspace: String? = null,
    /** Where the session was started (`VS Code`, `External`, `Automation`, `Subagent`, or a host-reported initiator). */
    val initiator: String? = null,
    /** Whether the session was created externally, spawned by a tool, or started by an automation. */
    val isExternal: Boolean = false,
) {
    /** Unique key across hosts for Compose lists. */
    val key: String get() = if (hostId != null) "$hostId::$resource" else resource
}

/** One section of rows in the sessions list. [title] is null when [SessionGrouping.NONE] is used. */
data class SessionGroup(
    val key: String,
    val title: String?,
    val rows: List<SessionRow>,
)

/** What the sessions screen shows, computed from the host's list. Pure, so it is tested without a UI. */
data class SessionListModel(
    val rows: List<SessionRow>,
    val needsInput: Int,
    val inProgress: Int,
    val errors: Int,
    /** Archived sessions that are currently hidden by the archive filter. */
    val hiddenArchived: Int,
    /** All sessions the host(s) reported, shown or not. */
    val total: Int,
    /** The search the rows were narrowed by, trimmed; empty when none. The counts above ignore it, so the summary stays true to the host. */
    val query: String = "",
    /** The rows arranged into sections according to [SessionFilter.grouping]. */
    val groups: List<SessionGroup> = if (rows.isEmpty()) emptyList() else listOf(SessionGroup("all", null, rows)),
    /** The active filter that produced this model. */
    val filter: SessionFilter = SessionFilter.DEFAULT,
    /** Non-archived sessions hidden by [filter] (external/spawned, done, recent scope, or facet filters). */
    val hiddenByFilter: Int = 0,
    /** How many non-archived sessions were hidden specifically because they are external or spawned. */
    val hiddenExternal: Int = 0,
    /** Connected hosts represented in the unfiltered catalog, with their session counts. */
    val availableHosts: List<FilterFacetOption> = emptyList(),
    /** Harnesses (`provider`) represented in the unfiltered catalog, with their session counts. */
    val availableProviders: List<FilterFacetOption> = emptyList(),
    /** Workspaces / repositories represented in the unfiltered catalog, with their session counts. */
    val availableWorkspaces: List<FilterFacetOption> = emptyList(),
    /** Initiators ("Created In") represented in the unfiltered catalog, with their session counts. */
    val availableInitiators: List<FilterFacetOption> = emptyList(),
) {
    /** One line for the top of the list, for example "2 need you · 1 working". Empty when nothing is happening. */
    val summary: String
        get() = listOfNotNull(
            needsInput.takeIf { it > 0 }?.let { "$it need${if (it == 1) "s" else ""} you" },
            inProgress.takeIf { it > 0 }?.let { "$it working" },
            errors.takeIf { it > 0 }?.let { "$it error${if (it == 1) "" else "s"}" },
        ).joinToString(" · ")

    companion object {
        const val UNTITLED = "Untitled session"

        private val TIME_BUCKET_ORDER = listOf("Needs you", "Today", "Yesterday", "Last 7 days", "Older")

        /**
         * Builds the model for a single host's [sessions] with only the archive toggle and search [query], preserving the
         * existing signature for callers and tests that do not pass a full [SessionFilter].
         */
        fun from(
            sessions: List<SessionSummary>,
            showArchived: Boolean,
            now: Instant,
            query: String = "",
            filter: SessionFilter = SessionFilter.DEFAULT.copy(showArchived = showArchived),
            zone: ZoneId = systemDefaultZone(),
        ): SessionListModel = fromEntries(
            entries = sessions.map { HostSessionEntry(null, it) },
            filter = filter.copy(showArchived = showArchived),
            now = now,
            query = query,
            zone = zone,
        )

        /**
         * Builds the model across one or more connected hosts ([entries]), applying [filter], [query], ordering and grouping.
         *
         * Sessions in [ActivityState.NEEDS_INPUT] are never hidden by archive, external/spawned, done or recent filters.
         */
        fun fromEntries(
            entries: List<HostSessionEntry>,
            filter: SessionFilter,
            now: Instant,
            query: String = "",
            zone: ZoneId = systemDefaultZone(),
        ): SessionListModel {
            val hosts = buildHostFacets(entries)
            val multiHost = hosts.size > 1
            val nonArchivedPool = entries.filter { filter.showArchived || !it.summary.status.isArchived || it.summary.status.activityState == ActivityState.NEEDS_INPUT }
            val providers = buildFacets(nonArchivedPool.mapNotNull { it.summary.provider.trim().ifEmpty { null } }) { SessionFilter.harnessLabel(it) }
            val workspaces = buildFacets(nonArchivedPool.mapNotNull { it.summary.workspaceLabel }) { it }
            val initiators = buildFacets(nonArchivedPool.mapNotNull { it.summary.initiatorLabel }) { it }

            val orderedEntries = if (filter.ordering == SessionOrdering.CREATED) {
                SessionCatalog.sortedEntries(nonArchivedPool, SessionOrdering.CREATED)
            } else {
                nonArchivedPool
            }

            var recentIdleSeen = 0
            var hiddenExt = 0
            val filteredEntries = orderedEntries.filter { entry ->
                val s = entry.summary
                val state = s.status.activityState
                val needsYou = state == ActivityState.NEEDS_INPUT
                if (filter.hostIds.isNotEmpty() && entry.hostId !in filter.hostIds) return@filter false
                if (filter.providers.isNotEmpty() && s.provider.trim() !in filter.providers) return@filter false
                if (filter.workspaces.isNotEmpty() && s.workspaceLabel !in filter.workspaces) return@filter false
                if (filter.initiators.isNotEmpty() && s.initiatorLabel !in filter.initiators) return@filter false
                if (!filter.showExternal && !needsYou && s.isSpawnedOrExternal) {
                    hiddenExt++
                    return@filter false
                }
                if (!filter.showDone && !needsYou && state == ActivityState.IDLE && s.status.isRead) {
                    return@filter false
                }
                if (filter.showScope == SessionShowScope.RECENT && !needsYou && state == ActivityState.IDLE) {
                    val ts = filter.ordering.timestampOf(s)
                    if (!SessionFilter.isWithinRecentWindow(ts, now)) return@filter false
                    recentIdleSeen++
                    if (recentIdleSeen > SessionFilter.MAX_RECENT) return@filter false
                }
                true
            }

            val allRows = filteredEntries.map { entry ->
                row(
                    session = entry.summary,
                    now = now,
                    ordering = filter.ordering,
                    hostId = entry.hostId,
                    hostName = entry.hostName?.takeIf { multiHost && filter.grouping != SessionGrouping.HOST },
                )
            }

            val needle = query.trim()
            val rows = if (needle.isEmpty()) {
                allRows
            } else {
                allRows.filter { row ->
                    row.title.contains(needle, ignoreCase = true) ||
                        row.subtitle?.contains(needle, ignoreCase = true) == true ||
                        row.branch?.contains(needle, ignoreCase = true) == true ||
                        row.workspace?.contains(needle, ignoreCase = true) == true ||
                        row.hostName?.contains(needle, ignoreCase = true) == true
                }
            }

            val groups = groupRows(rows, filteredEntries.associate { rowKey(it.hostId, it.summary.resource) to it }, filter, now, zone)

            return SessionListModel(
                rows = rows,
                needsInput = allRows.count { it.state == ActivityState.NEEDS_INPUT },
                inProgress = allRows.count { it.state == ActivityState.IN_PROGRESS },
                errors = allRows.count { it.state == ActivityState.ERROR },
                hiddenArchived = entries.size - nonArchivedPool.size,
                total = entries.size,
                query = needle,
                groups = groups,
                filter = filter,
                hiddenByFilter = nonArchivedPool.size - filteredEntries.size,
                hiddenExternal = hiddenExt,
                availableHosts = hosts,
                availableProviders = providers,
                availableWorkspaces = workspaces,
                availableInitiators = initiators,
            )
        }

        internal fun row(
            session: SessionSummary,
            now: Instant,
            ordering: SessionOrdering = SessionOrdering.UPDATED,
            hostId: String? = null,
            hostName: String? = null,
        ) = SessionRow(
            resource = session.resource,
            title = session.title.ifBlank { UNTITLED },
            subtitle = session.activity?.takeIf { it.isNotBlank() } ?: session.project?.displayName?.takeIf { it.isNotBlank() },
            state = session.status.activityState,
            isRead = session.status.isRead,
            isArchived = session.status.isArchived,
            whenText = RelativeTime.format(ordering.timestampOf(session), now),
            chatCount = session.chats?.size ?: 0,
            changes = ChangeStats.of(session.changes),
            branch = session.extras.branchLabel,
            hostId = hostId,
            hostName = hostName,
            provider = session.provider.trim(),
            workspace = session.workspaceLabel,
            initiator = session.initiatorLabel,
            isExternal = session.isSpawnedOrExternal,
        )

        private fun rowKey(hostId: String?, resource: String): String = if (hostId != null) "$hostId::$resource" else resource

        private fun buildHostFacets(entries: List<HostSessionEntry>): List<FilterFacetOption> {
            val counts = LinkedHashMap<String, Pair<String, Int>>()
            for (entry in entries) {
                val id = entry.hostId ?: continue
                val name = entry.hostName ?: id
                val current = counts[id]
                counts[id] = name to ((current?.second ?: 0) + 1)
            }
            return counts.map { (id, pair) -> FilterFacetOption(id, pair.first, pair.second) }
        }

        private fun buildFacets(values: List<String>, labelFor: (String) -> String): List<FilterFacetOption> {
            val counts = LinkedHashMap<String, Int>()
            for (v in values) {
                counts[v] = (counts[v] ?: 0) + 1
            }
            return counts.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { labelFor(it.key) })
                .map { (id, count) -> FilterFacetOption(id, labelFor(id), count) }
        }

        private fun groupRows(
            rows: List<SessionRow>,
            byKey: Map<String, HostSessionEntry>,
            filter: SessionFilter,
            now: Instant,
            zone: ZoneId,
        ): List<SessionGroup> {
            if (rows.isEmpty()) return emptyList()
            return when (filter.grouping) {
                SessionGrouping.NONE -> listOf(SessionGroup("all", null, rows))
                SessionGrouping.TIME -> {
                    val buckets = LinkedHashMap<String, MutableList<SessionRow>>()
                    for (bucket in TIME_BUCKET_ORDER) buckets[bucket] = ArrayList()
                    for (row in rows) {
                        val summary = byKey[row.key]?.summary
                        val bucket = if (summary != null) {
                            SessionFilter.timeBucket(summary, filter.ordering, now, zone)
                        } else {
                            "Older"
                        }
                        buckets.getOrPut(bucket) { ArrayList() }.add(row)
                    }
                    buckets.entries.filter { it.value.isNotEmpty() }.map { (title, list) ->
                        SessionGroup("time:$title", title, list)
                    }
                }
                SessionGrouping.WORKSPACE -> {
                    val buckets = LinkedHashMap<String, MutableList<SessionRow>>()
                    for (row in rows) {
                        val ws = row.workspace ?: SessionFilter.NO_WORKSPACE
                        buckets.getOrPut(ws) { ArrayList() }.add(row)
                    }
                    val noWorkspace = buckets.remove(SessionFilter.NO_WORKSPACE)
                    if (noWorkspace != null) buckets[SessionFilter.NO_WORKSPACE] = noWorkspace
                    buckets.entries.map { (title, list) ->
                        SessionGroup("workspace:$title", title, list)
                    }
                }
                SessionGrouping.HOST -> {
                    val buckets = LinkedHashMap<String, MutableList<SessionRow>>()
                    for (row in rows) {
                        val host = row.hostName ?: byKey[row.key]?.hostName ?: "This host"
                        buckets.getOrPut(host) { ArrayList() }.add(row)
                    }
                    buckets.entries.map { (title, list) ->
                        SessionGroup("host:$title", title, list)
                    }
                }
            }
        }
    }
}
