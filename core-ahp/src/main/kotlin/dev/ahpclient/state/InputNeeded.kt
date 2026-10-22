package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.SessionSummary
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** A session that has just started waiting on the user. */
data class InputNeeded(val resource: String, val title: String)

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
 * that is answered or withdrawn straight away does not buzz the phone. [resolved] is called when a session that was
 * notified stops waiting, so a stale notification can be removed.
 */
class InputNeededNotifier(
    private val sessions: Flow<List<SessionSummary>>,
    private val notify: (InputNeeded) -> Unit,
    private val resolved: (String) -> Unit = {},
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
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
                    pending.remove(started.resource)
                    notified += started.resource
                    notify(started)
                }
            }
        }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 3_000L
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
