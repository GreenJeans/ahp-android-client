package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.SessionSummary
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A session that has just started waiting on the user. [detail] is what it is waiting for (the question, or the tool to
 * approve) when that could be read in time; null means only the session [title] is known.
 */
data class InputNeeded(val resource: String, val title: String, val detail: String? = null)

/**
 * Finds the sessions that have begun waiting on the user between one list and the next. The first list only records
 * who is already waiting, so connecting to a host that has open questions does not notify for all of them; after
 * that, a session is reported once when it enters the state and again only after it has left and re-entered.
 */
class InputNeededDetector {
    private val waiting = HashSet<String>()
    private var seeded = false

    fun onSessions(sessions: List<SessionSummary>): List<InputNeeded> {
        val nowWaiting = sessions.filter { it.status.activityState == ActivityState.NEEDS_INPUT }
        val started = if (seeded) nowWaiting.filter { it.resource !in waiting } else emptyList()
        waiting.clear()
        nowWaiting.forEach { waiting += it.resource }
        seeded = true
        return started.map { InputNeeded(it.resource, it.title.ifBlank { SessionListModel.UNTITLED }) }
    }
}

/**
 * Turns session lists into notifications. A session must still be waiting [debounceMs] after it started, so a question
 * that is answered or withdrawn straight away does not buzz the phone. The session list does not say what the session is
 * waiting for, so after the debounce [readDetail] is asked for it; it gets [readTimeoutMs], and if it is slow, fails or
 * has nothing, the alert goes out with the session title alone rather than not at all. [resolved] is called when a
 * session that was notified stops waiting, so a stale notification can be removed.
 */
class InputNeededNotifier(
    private val sessions: Flow<List<SessionSummary>>,
    private val notify: (InputNeeded) -> Unit,
    private val resolved: (String) -> Unit = {},
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val readDetail: suspend (String) -> String? = { null },
    private val readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
) {
    private val detector = InputNeededDetector()
    private val pending = ConcurrentHashMap<String, Job>()
    private val notified = ConcurrentHashMap.newKeySet<String>()

    /** Runs until cancelled. */
    suspend fun run() = coroutineScope {
        sessions.collect { list ->
            val waiting = list.filter { it.status.activityState == ActivityState.NEEDS_INPUT }.map { it.resource }.toSet()
            pending.keys.filter { it !in waiting }.forEach { pending.remove(it)?.cancel() }
            notified.filter { it !in waiting }.forEach { uri ->
                notified.remove(uri)
                resolved(uri)
            }
            detector.onSessions(list).forEach { started ->
                pending[started.resource] = launch {
                    delay(debounceMs)
                    // The job stays registered while the question is read, so a session that stops waiting meanwhile cancels it.
                    val detail = read(started.resource)
                    pending.remove(started.resource)
                    notified += started.resource
                    notify(started.copy(detail = detail))
                }
            }
        }
    }

    private suspend fun read(resource: String): String? = try {
        withTimeoutOrNull(readTimeoutMs) { readDetail(resource) }?.let(::tidy)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 3_000L
        const val DEFAULT_READ_TIMEOUT_MS = 4_000L
        const val MAX_DETAIL_CHARS = 240

        /** One line of plain text, short enough for a notification. Null when nothing readable is left. */
        fun tidy(text: String): String? {
            val oneLine = text.replace(Regex("\\s+"), " ").trim()
            if (oneLine.isEmpty()) return null
            return if (oneLine.length <= MAX_DETAIL_CHARS) oneLine else oneLine.take(MAX_DETAIL_CHARS - 1).trimEnd() + "…"
        }
    }
}

/** The persistent notification's words while monitoring is on. */
data class MonitorStatus(val title: String, val text: String) {
    companion object {
        fun from(host: String, sessions: List<SessionSummary>, connection: ConnState): MonitorStatus {
            val title = "Monitoring $host"
            val text = when (connection) {
                ConnState.Online -> {
                    val active = sessions.filter { !it.status.isArchived }
                    val waiting = active.count { it.status.activityState == ActivityState.NEEDS_INPUT }
                    when {
                        active.isEmpty() -> "No active sessions"
                        waiting > 0 -> "${active.size} active ${plural(active.size)} · $waiting need${if (waiting == 1) "s" else ""} input"
                        else -> "${active.size} active ${plural(active.size)}"
                    }
                }
                ConnState.Connecting -> "Connecting…"
                is ConnState.Reconnecting -> "Connection lost. Reconnecting…"
                is ConnState.Failed -> "Disconnected. Open the app for details."
                ConnState.Stopped -> "Stopped"
            }
            return MonitorStatus(title, text)
        }

        private fun plural(count: Int) = if (count == 1) "session" else "sessions"
    }
}
