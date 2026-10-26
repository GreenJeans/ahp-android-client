package dev.ahpclient.app

import dev.ahpclient.state.ConnState
import dev.ahpclient.state.FinishedNotifier
import dev.ahpclient.state.FinishedSession
import dev.ahpclient.state.FinishedWatch
import dev.ahpclient.state.InputNeeded
import dev.ahpclient.state.InputNeededNotifier
import dev.ahpclient.state.MonitorStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** The one saved choice: keep watching in the background, or only while the app is open. */
interface MonitorSettings {
    val enabled: Flow<Boolean>

    suspend fun setEnabled(on: Boolean)
}

/** Starts and stops whatever keeps the process alive in the background (on Android, a foreground service). */
interface MonitorService {
    fun start()

    fun stop()
}

/** Where "a session needs you" goes: a notification on the phone. */
interface InputNeededSink {
    fun post(request: InputNeeded)

    fun clear(resource: String)
}

/** Which sessions to tell the user about when they finish, kept between runs. */
interface FinishedWatchSettings {
    val watch: Flow<FinishedWatch>

    suspend fun setAll(on: Boolean)

    suspend fun setSession(resource: String, on: Boolean)
}

/** Where "a session finished" goes: a notification on the phone, taken away again if the session starts working. */
interface FinishedSink {
    fun post(finished: FinishedSession)

    fun clear(resource: String)
}

/**
 * The session the person is looking at: the open one, and only while the app is in front. With the app in the background (the
 * phone locked, another app on top) nothing is being looked at, even though the session is still "open" in the app.
 */
object OnScreen {
    fun session(inForeground: Boolean, state: AppState): String? = if (inForeground) (state as? AppState.Connected)?.open?.uri else null
}

/**
 * What "tell me when it finishes" needs besides the session list: the user's choice of sessions, where the alert goes, and which
 * session is on screen right now (null when none is, or the app is not in front), which never gets an alert.
 */
class FinishedAlerts(
    val settings: FinishedWatchSettings,
    val sink: FinishedSink,
    val viewing: () -> String? = { null },
)

/**
 * Decides when background monitoring runs and feeds it. Monitoring is on exactly while the user has turned it on and
 * is connected to a host: signing out, leaving the host or turning it off stops it, with nothing left running. While
 * it runs, [status] is the persistent notification's text and sessions that start waiting on the user go to [sink]. With [finished]
 * set, sessions the user chose to watch (or all of them) also go to its sink when they stop working.
 */
class MonitorCoordinator(
    private val state: StateFlow<AppState>,
    private val settings: MonitorSettings,
    private val service: MonitorService,
    private val sink: InputNeededSink,
    private val scope: CoroutineScope,
    private val debounceMs: Long = InputNeededNotifier.DEFAULT_DEBOUNCE_MS,
    private val finished: FinishedAlerts? = null,
    private val finishedDebounceMs: Long = FinishedNotifier.DEFAULT_DEBOUNCE_MS,
) {
    private val _status = MutableStateFlow<MonitorStatus?>(null)

    /** Null when monitoring is off. */
    val status: StateFlow<MonitorStatus?> = _status.asStateFlow()

    val enabled: Flow<Boolean> get() = settings.enabled

    suspend fun setEnabled(on: Boolean) = settings.setEnabled(on)

    /** The sessions the user wants a "finished" alert for. Kept whether or not monitoring is on; the alerts need it on. */
    val finishedWatch: Flow<FinishedWatch> get() = finished?.settings?.watch ?: flowOf(FinishedWatch())

    suspend fun setWatchAll(on: Boolean) {
        finished?.settings?.setAll(on)
    }

    suspend fun setWatched(resource: String, on: Boolean) {
        finished?.settings?.setSession(resource, on)
    }

    fun start() {
        scope.launch { run() }
    }

    private suspend fun run() {
        combine(state, settings.enabled) { current, on -> if (on) current as? AppState.Connected else null }
            .distinctUntilChanged { old, new -> old?.session === new?.session }
            .collectLatest { connected ->
                if (connected == null) {
                    _status.value = null
                    service.stop()
                } else {
                    monitor(connected)
                }
            }
    }

    private suspend fun monitor(connected: AppState.Connected) = coroutineScope {
        val session = connected.session
        // The status is set before the service starts, so the service never sees "off" while it is starting.
        _status.value = MonitorStatus.from(connected.host.name, session.root.sessions.value, session.connection.value)
        service.start()
        launch {
            combine(session.connection, session.root.sessions) { connection, list -> MonitorStatus.from(connected.host.name, list, connection) }
                .collect { _status.value = it }
        }
        launch {
            // The list is complete once the connection is online, so what is waiting then is the baseline, not news.
            session.connection.first { it == ConnState.Online }
            InputNeededNotifier(
                sessions = session.root.sessions,
                notify = sink::post,
                resolved = sink::clear,
                debounceMs = debounceMs,
                readDetail = { resource -> WaitingRequests.read(session, resource) },
            ).run()
        }
        finished?.let { alerts ->
            launch {
                session.connection.first { it == ConnState.Online }
                FinishedNotifier(
                    sessions = session.root.sessions,
                    watch = alerts.settings.watch,
                    notify = alerts.sink::post,
                    cleared = alerts.sink::clear,
                    viewing = alerts.viewing,
                    debounceMs = finishedDebounceMs,
                ).run()
            }
        }
    }
}
