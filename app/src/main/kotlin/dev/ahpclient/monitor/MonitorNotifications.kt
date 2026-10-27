package dev.ahpclient.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import dev.ahpclient.MainActivity
import dev.ahpclient.R
import dev.ahpclient.app.FinishedSink
import dev.ahpclient.app.InputNeededSink
import dev.ahpclient.app.MonitorProblemSink
import dev.ahpclient.state.ApprovalTarget
import dev.ahpclient.state.FinishedSession
import dev.ahpclient.state.FinishedText
import dev.ahpclient.state.InputNeeded

/** The extra that carries the session a notification points at. The app only opens it if the host lists that session. */
const val EXTRA_SESSION_URI = "dev.ahpclient.extra.SESSION_URI"

object MonitorChannels {
    const val MONITOR = "monitor"
    const val INPUT_NEEDED = "input_needed"
    const val FINISHED = "finished"
    const val PROBLEM = "monitor_problem"

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
        manager.createNotificationChannel(
            NotificationChannel(FINISHED, "Finished sessions", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A session you chose to watch has stopped working."
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(PROBLEM, "Monitoring problems", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Background monitoring stopped, or could not start. Tap to open the app and resume it."
            },
        )
    }
}

/**
 * The one warning that background monitoring is not running: "Monitoring stopped", from the periodic check, or "could not start", from
 * the coordinator. It has a fixed id so a second one replaces the first, makes a sound only the first time, and opens the app when
 * tapped, which connects again when monitoring is on. Nothing in it is sensitive.
 */
class NotificationMonitorProblemSink(private val context: Context) : MonitorProblemSink {
    private val manager = context.getSystemService(NotificationManager::class.java)

    override fun post(text: String) {
        MonitorChannels.ensure(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, MonitorChannels.PROBLEM)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .build(),
        )
    }

    override fun clear() {
        manager.cancel(NOTIFICATION_ID)
    }

    internal companion object {
        /** Not an id the service uses for its own notifications (100 and 101). */
        const val NOTIFICATION_ID = 102
    }
}

/**
 * One notification per session that starts waiting on the user, posted by tag so it can be replaced or cleared when the
 * question goes away. The body is what the session is waiting for (the question, or the tool to approve) with the session
 * title beside it; when that could not be read in time it is the title alone. Both can be sensitive, so a phone set to hide
 * sensitive content shows only that something needs input.
 *
 * When what it waits for is one tool call and the app may act for the user ([canDecide]), the notification also has buttons,
 * on the lock screen version too: a plain Deny and Approve, or, when the host offered options of its own, those options in
 * the host's words (at most three, the refusal first). A refusal needs no check. An approval opens a screenless activity so the
 * phone's own device check can appear (after unlocking, on a locked phone) and name the tool, and the option, before anything
 * is sent. The buttons carry only which call and option to decide; the live chat is what says whether it still can be.
 */
class NotificationInputNeededSink(
    private val context: Context,
    /** Asked each time one is posted: only while the user has allowed the app to act for them are buttons offered. */
    private val canDecide: () -> Boolean = { false },
) : InputNeededSink {
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
        val builder = Notification.Builder(context, MonitorChannels.INPUT_NEEDED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Needs your input")
            .setContentText(request.detail ?: request.title)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        request.detail?.let { detail ->
            builder.setSubText(request.title).setStyle(Notification.BigTextStyle().bigText(detail))
        }
        request.approval?.takeIf { canDecide() }?.let { target ->
            decisionActions(request.resource, target).forEach { action ->
                builder.addAction(action)
                lockScreen.addAction(action)
            }
        }
        builder.setPublicVersion(lockScreen.build())
        manager.notify(request.resource, NOTIFICATION_ID, builder.build())
    }

    override fun clear(resource: String) {
        manager.cancel(resource, NOTIFICATION_ID)
    }

    private class Button(val label: String, val approves: Boolean, val optionId: String?)

    private fun decisionActions(resource: String, target: ApprovalTarget): List<Notification.Action> {
        val icon = Icon.createWithResource(context, R.drawable.ic_notification)
        // With no options of its own the host expects a plain approve or deny; with options, its own words are the buttons.
        val buttons = if (target.options.isEmpty()) {
            listOf(Button("Deny", approves = false, optionId = null), Button("Approve", approves = true, optionId = null))
        } else {
            target.options.map { Button(it.label, it.approves, it.id) }
        }
        return buttons.map { button ->
            val pending = if (button.approves) {
                PendingIntent.getActivity(
                    context,
                    0,
                    decisionIntent(Intent(context, ApproveActivity::class.java), resource, target, "approve", button.optionId),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            } else {
                PendingIntent.getBroadcast(
                    context,
                    0,
                    decisionIntent(Intent(context, DecisionReceiver::class.java).setAction(ACTION_DENY_TOOL_CALL), resource, target, "deny", button.optionId),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
            Notification.Action.Builder(icon, button.label, pending).build()
        }
    }

    /**
     * Android treats two intents that differ only in their extras as the same one, so a second notification's buttons would
     * overwrite the first's. The data address differs for every session, call, button and option, which keeps each button its own.
     */
    private fun decisionIntent(intent: Intent, resource: String, target: ApprovalTarget, button: String, optionId: String?): Intent =
        intent
            .putExtra(EXTRA_CHAT_URI, target.chat)
            .putExtra(EXTRA_TOOL_CALL_ID, target.toolCallId)
            .apply { if (optionId != null) putExtra(EXTRA_OPTION_ID, optionId) }
            .setData(
                Uri.Builder()
                    .scheme("ahp-decision")
                    .authority(button)
                    .appendQueryParameter("session", resource)
                    .appendQueryParameter("chat", target.chat)
                    .appendQueryParameter("call", target.toolCallId)
                    .apply { if (optionId != null) appendQueryParameter("option", optionId) }
                    .build(),
            )

    private companion object {
        const val NOTIFICATION_ID = 1
    }
}

/**
 * One notification per session that has stopped working, posted by tag so it is replaced if the session finishes again and can
 * be taken away when it starts working. The title is the session's and the text says the result and how long it took; both can be
 * sensitive, so the lock-screen version says only that a session finished. Tapping it opens the session. There are no buttons:
 * nothing here is waiting on a decision.
 */
class NotificationFinishedSink(private val context: Context) : FinishedSink {
    private val manager = context.getSystemService(NotificationManager::class.java)

    override fun post(finished: FinishedSession) {
        MonitorChannels.ensure(context)
        val open = PendingIntent.getActivity(
            context,
            finished.resource.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_SESSION_URI, finished.resource)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val lockScreen = Notification.Builder(context, MonitorChannels.FINISHED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(FinishedText.PUBLIC_TITLE)
            .build()
        manager.notify(
            finished.resource,
            NOTIFICATION_ID,
            Notification.Builder(context, MonitorChannels.FINISHED)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(finished.title.take(MAX_TITLE))
                .setContentText(FinishedText.detail(finished))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(lockScreen)
                .build(),
        )
    }

    override fun clear(resource: String) {
        manager.cancel(resource, NOTIFICATION_ID)
    }

    private companion object {
        /** Not the id of the "needs input" notification of the same session, so the two live side by side. */
        const val NOTIFICATION_ID = 2
        const val MAX_TITLE = 120
    }
}
