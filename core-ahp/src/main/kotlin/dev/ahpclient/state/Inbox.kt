package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.SessionSummary
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/** One session in the inbox. [stamp] changes whenever the host reports the session changed, so what was read about it can be told stale. */
data class InboxItem(val row: SessionRow, val stamp: String)

/**
 * Where the person is needed: the sessions waiting on them (a question, an approval), and the ones that stopped on their own and have not
 * been read. A session that is working is not here, and neither is one the person has read. Computed from the host's list, so it is tested
 * without a phone.
 */
data class Inbox(val waiting: List<InboxItem>, val finished: List<InboxItem>) {
    val total: Int get() = waiting.size + finished.size
    val isEmpty: Boolean get() = total == 0

    companion object {
        /** A session that finished longer ago than this is no longer news: the inbox is for now, not for every session never opened. */
        const val RECENT_DAYS = 3L

        /**
         * Waiting sessions come first, as the side menu lists them, and include an archived one: hiding a question the agent waits on would be
         * worse than a cluttered list. A finished session must be idle or in error, not archived, not read, and have changed within
         * [RECENT_DAYS]; a failed one comes first, then the most recent.
         */
        fun from(sessions: List<SessionSummary>, now: Instant): Inbox =
            fromEntries(sessions.map { HostSessionEntry(hostId = null, hostName = null, session = it) }, now)

        /**
         * Computes the inbox across one or more connected hosts ([HostSessionEntry]), preserving each session's [HostSessionEntry.hostId]
         * and [HostSessionEntry.hostName] on [InboxItem.row].
         */
        fun fromEntries(entries: List<HostSessionEntry>, now: Instant): Inbox {
            val waiting = ArrayList<InboxItem>()
            val finished = ArrayList<InboxItem>()
            for (entry in SessionCatalog.sortedEntries(entries)) {
                val session = entry.session
                val state = session.status.activityState
                val row = SessionListModel.row(session, now, hostId = entry.hostId, hostName = entry.hostName)
                when {
                    state == ActivityState.NEEDS_INPUT -> waiting += InboxItem(row, session.modifiedAt)
                    state == ActivityState.IN_PROGRESS -> Unit
                    session.status.isArchived || session.status.isRead -> Unit
                    !recent(session.modifiedAt, now) -> Unit
                    else -> finished += InboxItem(row, session.modifiedAt)
                }
            }
            return Inbox(waiting, finished.sortedBy { if (it.row.state == ActivityState.ERROR) 0 else 1 })
        }

        private fun recent(modifiedAt: String, now: Instant): Boolean = try {
            Duration.between(Instant.parse(modifiedAt), now) <= Duration.ofDays(RECENT_DAYS)
        } catch (_: DateTimeException) {
            false
        }
    }
}

/**
 * Reads what each waiting session in the inbox is waiting for, one at a time, so the inbox can show the question or the tool and offer to
 * decide it. Each read opens the session read-only and closes it again (see `WaitingRequests`), so only as many as [max] are read, none
 * takes longer than [timeoutMs], and one that fails or is slow simply has nothing to show: the row is still there, with a way to open it.
 * What was read is kept until the session changes ([InboxItem.stamp]), and dropped when it leaves the inbox.
 */
class InboxReader(
    private val read: suspend (resource: String) -> RequestRead?,
    private val max: Int = MAX_READS,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    private class Entry(val stamp: String, val result: RequestRead?)

    private val kept = ConcurrentHashMap<String, Entry>()
    private val _details = MutableStateFlow<Map<String, RequestRead?>>(emptyMap())

    /** What was read, by session. A session that was read but had nothing to show is present with a null. */
    val details: StateFlow<Map<String, RequestRead?>> = _details.asStateFlow()

    /** Reads the first [max] of [waiting] that have not been read since they last changed. Returns when done; cancelled with the screen. */
    suspend fun refresh(waiting: List<InboxItem>) {
        val wanted = waiting.take(max)
        val current = wanted.associate { it.row.resource to it.stamp }
        kept.keys.filter { it !in current }.forEach { kept.remove(it) }
        publish()
        for (item in wanted) {
            val resource = item.row.resource
            if (kept[resource]?.stamp == item.stamp) continue
            val result = try {
                withTimeoutOrNull(timeoutMs) { read(resource) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            kept[resource] = Entry(item.stamp, result)
            publish()
        }
    }

    private fun publish() {
        _details.value = kept.entries.associate { it.key to it.value.result }
    }

    companion object {
        const val MAX_READS = 10
        const val TIMEOUT_MS = 6_000L
    }
}