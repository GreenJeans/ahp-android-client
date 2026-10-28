package dev.ahpclient.monitor

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.ahpclient.AhpApp
import dev.ahpclient.MainActivity
import dev.ahpclient.app.AppState
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.MonitorTileState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * The Quick Settings tile (F2): shows whether background monitoring is running and how many sessions are waiting on the
 * user. Tapping it opens the app on the inbox (after unlocking, if the phone is locked). It does not toggle monitoring
 * itself, because Android does not reliably allow a tile to start a foreground service from the background.
 */
class MonitorTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val container get() = (application as? AhpApp ?: applicationContext as AhpApp).container

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onStartListening() {
        super.onStartListening()
        container.start()
        job?.cancel()
        val sessionState = container.controller.state.flatMapLatest { state ->
            val session = (state as? AppState.Connected)?.session
            if (session == null) {
                flowOf(emptyList<com.microsoft.agenthostprotocol.generated.SessionSummary>() to false)
            } else {
                combine(session.root.sessions, session.connection) { list, conn -> list to (conn == ConnState.Online) }
            }
        }
        job = scope.launch {
            combine(container.monitor.status, sessionState) { status, (sessions, online) ->
                MonitorTileState.from(status, sessions, online)
            }.collect(::applyTile)
        }
    }

    override fun onStopListening() {
        job?.cancel()
        job = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val open = Runnable { launchInbox() }
        if (isLocked) unlockAndRun(open) else open.run()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun applyTile(state: MonitorTileState) {
        val tile = qsTile ?: return
        tile.state = if (state.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = state.label
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = state.subtitle
        }
        tile.updateTile()
    }

    // The PendingIntent overload only exists from Android 14 (API 34); older Android versions need the Intent overload.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchInbox() {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_INBOX, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
