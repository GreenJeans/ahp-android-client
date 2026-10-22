package dev.ahpclient.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.ahpclient.MainActivity
import dev.ahpclient.R
import dev.ahpclient.app.InputNeededSink
import dev.ahpclient.state.InputNeeded

/** The extra that carries the session a notification points at. The app only opens it if the host lists that session. */
const val EXTRA_SESSION_URI = "dev.ahpclient.extra.SESSION_URI"

object MonitorChannels {
    const val MONITOR = "monitor"
    const val INPUT_NEEDED = "input_needed"

    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(MONITOR, "Background monitoring", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the app keeps watching your sessions with the screen off."
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(INPUT_NEEDED, "Sessions that need input", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A session is waiting for your answer or approval in VS Code."
            },
        )
    }
}

/**
 * One notification per session that starts waiting on the user, posted by tag so it can be replaced or cleared when the
 * question goes away. The session's title can be sensitive, so a locked screen shows only that something needs input.
 */
class NotificationInputNeededSink(private val context: Context) : InputNeededSink {
    private val manager = context.getSystemService(NotificationManager::class.java)

    override fun post(request: InputNeeded) {
        MonitorChannels.ensure(context)
        val open = PendingIntent.getActivity(
            context,
            request.resource.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_SESSION_URI, request.resource)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val lockScreen = Notification.Builder(context, MonitorChannels.INPUT_NEEDED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("A session needs your input")
            .build()
        val notification = Notification.Builder(context, MonitorChannels.INPUT_NEEDED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Needs your input")
            .setContentText(request.title)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(lockScreen)
            .build()
        manager.notify(request.resource, NOTIFICATION_ID, notification)
    }

    override fun clear(resource: String) {
        manager.cancel(resource, NOTIFICATION_ID)
    }

    private companion object {
        const val NOTIFICATION_ID = 1
    }
}
