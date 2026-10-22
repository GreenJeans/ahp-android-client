package dev.ahpclient.monitor

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.ahpclient.AhpApp
import dev.ahpclient.MainActivity
import dev.ahpclient.R
import dev.ahpclient.app.MonitorService as MonitorServiceControl
import dev.ahpclient.state.MonitorStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Starts and stops [MonitorService]. Starting from the background is refused by Android; that is reported, never thrown. */
class AndroidMonitorService(private val context: Context) : MonitorServiceControl {
    override fun start() {
        try {
            context.startForegroundService(Intent(context, MonitorService::class.java))
        } catch (_: IllegalStateException) {
            // Android 12+ refuses to start a foreground service while the app is in the background.
        } catch (_: SecurityException) {
            // The foreground-service permission is missing from the manifest.
        }
    }

    override fun stop() {
        context.stopService(Intent(context, MonitorService::class.java))
    }
}

/**
 * Keeps this process in the foreground while background monitoring is on, so Android does not freeze the app (and its
 * socket) when the screen turns off. It holds no connection itself: the live session belongs to the app, and this
 * service only shows the persistent notification and mirrors [MonitorStatus] into it.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val container get() = (application as AhpApp).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            container.setMonitoring(false)
            // The coordinator stops the service too when it sees the setting change; this covers a Stop tapped on a stale notification.
            stopSelf()
            return START_NOT_STICKY
        }
        MonitorChannels.ensure(this)
        enterForeground(container.monitor.status.value ?: MonitorStatus("Monitoring", "Starting…"))
        job?.cancel()
        job = scope.launch {
            container.monitor.status.collect { status ->
                if (status == null) stopSelf() else getSystemService(NotificationManager::class.java).notify(ID, notification(status))
            }
        }
        // Not sticky: a restarted process has no host connection, so there would be nothing to keep alive.
        return START_NOT_STICKY
    }

    /** Android 15 limits a data-sync service to about six hours; it must stop when told to, so say why and turn monitoring off. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        container.setMonitoring(false)
        MonitorChannels.ensure(this)
        getSystemService(NotificationManager::class.java).notify(
            TIMEOUT_ID,
            Notification.Builder(this, MonitorChannels.MONITOR)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Background monitoring paused")
                .setContentText("Android limits how long this can run. Turn it on again in the app.")
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build(),
        )
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun enterForeground(status: MonitorStatus) {
        val notification = notification(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, notification)
        }
    }

    private fun notification(status: MonitorStatus): Notification {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, MonitorChannels.MONITOR)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(status.title)
            .setContentText(status.text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val ACTION_STOP = "dev.ahpclient.action.STOP_MONITORING"
        const val ID = 100
        const val TIMEOUT_ID = 101
    }
}
