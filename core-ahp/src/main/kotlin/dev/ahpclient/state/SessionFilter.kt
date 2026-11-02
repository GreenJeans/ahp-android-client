package dev.ahpclient.state

import dev.ahpclient.app.HostRef
import dev.ahpclient.platform.DateTimeException
import dev.ahpclient.platform.Instant
import dev.ahpclient.platform.ZoneId
import dev.ahpclient.platform.calendarDaysBetween
import dev.ahpclient.platform.durationBetween
import dev.ahpclient.platform.durationOfDays
import dev.ahpclient.platform.parseInstant
import dev.ahpclient.platform.systemDefaultZone
import com.microsoft.agenthostprotocol.generated.SessionSummary

/** How the sessions list is ordered below the pinned "needs you" sessions. */
enum class SessionOrdering(val label: String) {
    UPDATED("Updated"),
    CREATED("Created"),
    ;

    fun timestampOf(session: SessionSummary): String = when (this) {
        UPDATED -> session.modifiedAt
        CREATED -> session.createdAt
    }

    companion object {
        fun of(raw: String?): SessionOrdering = entries.firstOrNull { it.name == raw } ?: UPDATED
    }
}

/** How the sessions list is grouped into section headers. */
enum class SessionGrouping(val label: String) {
    NONE("None"),
    TIME("Time"),
    WORKSPACE("Workspace"),
    HOST("Environment"),
    ;

    companion object {
        fun of(raw: String?): SessionGrouping = entries.firstOrNull { it.name == raw } ?: NONE
    }
}

/** Whether to cap the list to recent sessions or show the entire history. */
enum class SessionShowScope(val label: String) {
    RECENT("Recent"),
    ALL("All"),
    ;

    companion object {
        fun of(raw: String?): SessionShowScope = entries.firstOrNull { it.name == raw } ?: RECENT
    }
}

/** One selectable facet value in the filter sheet, with how many sessions on the connected host(s) have it. */
data class FilterFacetOption(
    val id: String,
    val label: String,
    val count: Int,
)

/** A session paired with the host it came from, so sessions from several connected hosts can share one list. */
data class HostSessionEntry(
    val host: HostRef? = null,
    val summary: SessionSummary,
    val hostId: String? = host?.id,
    val hostName: String? = host?.name,
) {
    constructor(hostId: String?, hostName: String?, session: SessionSummary) : this(
        host = null,
        summary = session,
        hostId = hostId,
        hostName = hostName,
    )

    val session: SessionSummary get() = summary
}

/**
 * Which sessions the side menu shows, how they are ordered, and how they are grouped.
 *
 * Defaults match the VS Code Agents Panel workflow on a phone:
 * - [ordering] is [SessionOrdering.UPDATED] (with sessions waiting on the user always pinned first).
 * - [grouping] is [SessionGrouping.NONE].
 * - [showScope] is [SessionShowScope.RECENT] (last [RECENT_DAYS] days and at most [MAX_RECENT] idle sessions; active sessions always show).
 * - [showExternal] is `false` so background CLI runs, tool-spawned subagent sessions and automations do not clutter the list unless asked for.
 * - [showDone] is `true` (idle sessions are shown; turning it off narrows the list to active/waiting/failed or unread sessions).
 * - [showArchived] is `false`.
 * - Empty facet sets ([hostIds], [providers], [workspaces], [initiators]) mean "All".
 *
 * Safety invariant: a session in [ActivityState.NEEDS_INPUT] is never hidden by [showArchived], [showExternal], [showDone] or [showScope].
 */
data class SessionFilter(
    val ordering: SessionOrdering = SessionOrdering.UPDATED,
    val grouping: SessionGrouping = SessionGrouping.NONE,
    val showScope: SessionShowScope = SessionShowScope.RECENT,
    val showExternal: Boolean = false,
    val showDone: Boolean = true,
    val showArchived: Boolean = false,
    val hostIds: Set<String> = emptySet(),
    val providers: Set<String> = emptySet(),
    val workspaces: Set<String> = emptySet(),
    val initiators: Set<String> = emptySet(),
) {
    /** Whether every setting is at [DEFAULT]. */
    val isDefault: Boolean get() = this == DEFAULT

    /**
     * How many filter, ordering or grouping choices differ from [DEFAULT] (excluding [showArchived], which has its own toggle and badge
     * in the drawer's top action row). Shown as a badge on the filter button next to search.
     */
    val activeCount: Int
        get() {
            var count = 0
            if (ordering != DEFAULT.ordering) count++
            if (grouping != DEFAULT.grouping) count++
            if (showScope != DEFAULT.showScope) count++
            if (showExternal != DEFAULT.showExternal) count++
            if (showDone != DEFAULT.showDone) count++
            if (hostIds.isNotEmpty()) count++
            if (providers.isNotEmpty()) count++
            if (workspaces.isNotEmpty()) count++
            if (initiators.isNotEmpty()) count++
            return count
        }

    fun toggleHost(id: String): SessionFilter = copy(hostIds = hostIds.toggle(id))
    fun toggleProvider(id: String): SessionFilter = copy(providers = providers.toggle(id))
    fun toggleWorkspace(id: String): SessionFilter = copy(workspaces = workspaces.toggle(id))
    fun toggleInitiator(id: String): SessionFilter = copy(initiators = initiators.toggle(id))

    private fun Set<String>.toggle(item: String): Set<String> = if (item in this) this - item else this + item

    companion object {
        val DEFAULT = SessionFilter()

        /** Sessions modified/created within this window are considered recent under [SessionShowScope.RECENT]. */
        const val RECENT_DAYS = 14L

        /** At most this many sessions are kept under [SessionShowScope.RECENT] (active sessions beyond this still stay). */
        const val MAX_RECENT = 50

        const val NO_WORKSPACE = "No workspace"

        fun harnessLabel(provider: String): String = when (provider.trim().lowercase()) {
            "claude" -> "Claude"
            "copilotcli" -> "Copilot CLI"
            "copilot" -> "Copilot"
            "" -> "Unknown"
            else -> provider.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }

        internal fun isWithinRecentWindow(iso: String, now: Instant): Boolean {
            val parsed = try {
                parseInstant(iso)
            } catch (_: DateTimeException) {
                return true
            }
            return durationBetween(parsed, now) <= durationOfDays(RECENT_DAYS)
        }

        internal fun timeBucket(
            session: SessionSummary,
            ordering: SessionOrdering,
            now: Instant,
            zone: ZoneId = systemDefaultZone(),
        ): String {
            if (session.status.activityState == ActivityState.NEEDS_INPUT) return "Needs you"
            val iso = ordering.timestampOf(session)
            val instant = try {
                parseInstant(iso)
            } catch (_: DateTimeException) {
                return "Older"
            }
            val days = calendarDaysBetween(instant, now, zone)
            return when {
                days <= 0L -> "Today"
                days == 1L -> "Yesterday"
                days <= 7L -> "Last 7 days"
                else -> "Older"
            }
        }
    }
}
