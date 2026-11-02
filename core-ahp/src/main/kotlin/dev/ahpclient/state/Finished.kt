package dev.ahpclient.state

import dev.ahpclient.platform.Synchronized
import dev.ahpclient.platform.currentTimeMillis

import com.microsoft.agenthostprotocol.generated.SessionSummary
import dev.ahpclient.platform.ConcurrentHashMap
import dev.ahpclient.platform.AtomicReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Which sessions the user wants to hear about when they finish: every active one, or the ones picked one by one. */
data class FinishedWatch(val all: Boolean = false, val sessions: Set<String> = emptySet()) {
    fun covers(resource: String): Boolean = all || resource in sessions
}

/**
 * A session that has stopped working. [tookMs] is how long it worked since this app saw it start, and null when it was already
 * working when the app first looked, because then the start is not known and a guess would be wrong.
 */
data class FinishedSession(val resource: String, val title: String, val failed: Boolean, val tookMs: Long?)

/**
 * Finds the sessions that have stopped working between one list and the next: from working (in progress, or waiting on the user)
 * to idle or an error. A session that is already working when the first list arrives counts, so one started in VS Code and left
 * running is reported when it finishes; the first list only records who is working. Archived sessions are not followed.
 *
 * A session that stops is reported once. If it starts working again it is followed from its original start, so the time reported
 * is the whole run; [settled] ends the run once the caller has decided what to do about it.
 */
class FinishedDetector(private val clock: () -> Long = ::currentTimeMillis) {
    private val started = HashMap<String, Long>()
    private val stopped = HashSet<String>()
    private var seeded = false

    @Synchronized
    fun onSessions(sessions: List<SessionSummary>): List<FinishedSession> {
        val result = ArrayList<FinishedSession>()
        val live = HashSet<String>()
        for (session in sessions) {
            if (session.status.isArchived) continue
            live += session.resource
            val state = session.status.activityState
            if (state.working) {
                stopped -= session.resource
                if (session.resource !in started) started[session.resource] = if (seeded) clock() else UNKNOWN
            } else if (session.resource in started && stopped.add(session.resource)) {
                val took = started.getValue(session.resource).takeIf { it != UNKNOWN }?.let { (clock() - it).coerceAtLeast(0) }
                result += FinishedSession(session.resource, session.title.ifBlank { SessionListModel.UNTITLED }, state == ActivityState.ERROR, took)
            }
        }
        started.keys.retainAll(live)
        stopped.retainAll(live)
        seeded = true
        return result
    }

    /** The run of [resource] is over: whether it was reported or not, the next time it works is a new run. */
    @Synchronized
    fun settled(resource: String) {
        started.remove(resource)
        stopped.remove(resource)
    }

    private companion object {
        const val UNKNOWN = -1L
    }
}

private val ActivityState.working: Boolean get() = this == ActivityState.IN_PROGRESS || this == ActivityState.NEEDS_INPUT

/**
 * Turns session lists into "finished" alerts. A session must still be stopped [debounceMs] after it stopped, so one that pauses
 * between two steps of the same job does not buzz the phone; if it starts working again the alert is dropped, and one that was
 * already shown is taken away ([cleared]). The alert goes out only for a session the user's [watch] covers when the time is up
 * (the choice may change meanwhile), and never for the one the person is looking at ([viewing]).
 */
class FinishedNotifier(
    private val sessions: Flow<List<SessionSummary>>,
    private val watch: Flow<FinishedWatch>,
    private val notify: (FinishedSession) -> Unit,
    private val cleared: (String) -> Unit = {},
    private val viewing: () -> String? = { null },
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    clock: () -> Long = ::currentTimeMillis,
) {
    private val detector = FinishedDetector(clock)
    private val pending = ConcurrentHashMap<String, Job>()
    private val notified = ConcurrentHashMap.newKeySet<String>()

    /** Runs until cancelled. */
    suspend fun run() = coroutineScope {
        val current = AtomicReference(FinishedWatch())
        launch { watch.collect { current.set(it) } }
        sessions.collect { list ->
            val working = list.filter { !it.status.isArchived && it.status.activityState.working }.map { it.resource }.toSet()
            pending.keys.filter { it in working }.forEach { pending.remove(it)?.cancel() }
            notified.filter { it in working }.forEach { uri ->
                notified.remove(uri)
                cleared(uri)
            }
            detector.onSessions(list).forEach { finished ->
                pending[finished.resource] = launch {
                    // Cancelled if the session starts working again: its run goes on, and is reported when it next stops.
                    delay(debounceMs)
                    pending.remove(finished.resource)
                    detector.settled(finished.resource)
                    if (current.get().covers(finished.resource) && viewing() != finished.resource) {
                        notified += finished.resource
                        notify(finished)
                    }
                }
            }
        }
    }

    companion object {
        /** Longer than the wait before a "needs input" alert: stopping is common between steps, asking a question is not. */
        const val DEFAULT_DEBOUNCE_MS = 5_000L
    }
}

/** The words of a finished alert, so they are tested without a phone. */
object FinishedText {
    /** What the lock screen shows: the session's title and its result can both be sensitive. */
    const val PUBLIC_TITLE = "A session finished"

    /** "Done · 8m 16s", "Failed · 2m 3s", or the word alone when the length is not known. */
    fun detail(finished: FinishedSession): String {
        val word = if (finished.failed) "Failed" else "Done"
        val took = TurnDuration.format(finished.tookMs)
        return if (took == null) word else "$word · $took"
    }
}
