package dev.ahpclient.app

import dev.ahpclient.state.ApprovalTarget
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.HostSession
import dev.ahpclient.state.InputNeeded
import dev.ahpclient.state.InputNeededNotifier
import dev.ahpclient.state.MonitorStatus
import dev.ahpclient.state.RequestRead
import dev.ahpclient.state.WaitingText
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

/**
 * Decides when background monitoring runs and feeds it. Monitoring is on exactly while the user has turned it on and
 * is connected to a host: signing out, leaving the host or turning it off stops it, with nothing left running. While
 * it runs, [status] is the persistent notification's text and sessions that start waiting on the user go to [sink].
 */
class MonitorCoordinator(
    private val state: StateFlow<AppState>,
    private val settings: MonitorSettings,
    private val service: MonitorService,
    private val sink: InputNeededSink,
    private val scope: CoroutineScope,
    private val debounceMs: Long = InputNeededNotifier.DEFAULT_DEBOUNCE_MS,
) {
    private val _status = MutableStateFlow<MonitorStatus?>(null)

    /** Null when monitoring is off. */
    val status: StateFlow<MonitorStatus?> = _status.asStateFlow()

    val enabled: Flow<Boolean> get() = settings.enabled

    suspend fun setEnabled(on: Boolean) = settings.setEnabled(on)

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
                readDetail = { resource -> readRequest(session, resource) },
            ).run()
        }
    }

    /**
     * What a session is waiting for, in words, and the tool call a notification may offer buttons for. The session list does
     * not carry it, so this opens the session read-only, takes what is pending from its snapshot and closes it again: one
     * `subscribe` and one `unsubscribe`, both in the read tier. Null when the session is gone or has nothing pending.
     */
    private suspend fun readRequest(session: HostSession, resource: String): RequestRead? {
        val store = session.openSession(resource)
        try {
            val state = combine(store.state, store.error) { loaded, error -> loaded to error }
                .first { (loaded, error) -> loaded != null || error != null }
                .first ?: return null
            val text = WaitingText.of(state.inputNeeded.orEmpty())
            return RequestRead(text, ApprovalTarget.from(state))
        } finally {
            session.closeSession(resource)
        }
    }
}
