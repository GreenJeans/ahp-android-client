package dev.ahpclient.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.ahpclient.AhpApp
import dev.ahpclient.state.InteractMessages
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What a notification's Approve and Deny buttons carry: only which tool call to decide. What it is, whether it is still
 * waiting and whether it may be decided are all read from the live chat when the button is pressed, never from the button.
 */
const val EXTRA_CHAT_URI = "dev.ahpclient.extra.CHAT_URI"
const val EXTRA_TOOL_CALL_ID = "dev.ahpclient.extra.TOOL_CALL_ID"
const val EXTRA_OPTION_ID = "dev.ahpclient.extra.OPTION_ID"
const val ACTION_DENY_TOOL_CALL = "dev.ahpclient.action.DENY_TOOL_CALL"

/**
 * The Deny button on a "needs your approval" notification. Saying no needs no device check, so it works from the lock screen
 * without a prompt. It is not exported, and its intent is immutable, so only this app's own notification can reach it.
 */
class DecisionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DENY_TOOL_CALL) return
        val chat = intent.getStringExtra(EXTRA_CHAT_URI) ?: return
        val call = intent.getStringExtra(EXTRA_TOOL_CALL_ID) ?: return
        val option = intent.getStringExtra(EXTRA_OPTION_ID)
        val app = context.applicationContext
        val pending = goAsync()
        val finished = AtomicBoolean(false)
        val finish = { if (finished.compareAndSet(false, true)) pending.finish() }
        // A receiver may not run for long; if the host is slow the decision carries on without holding it open.
        Handler(Looper.getMainLooper()).postDelayed(finish, RECEIVER_BUDGET_MS)
        (app as AhpApp).container.decideFromNotification(chat, call, approve = false, optionId = option) { result ->
            Toast.makeText(app, InteractMessages.describeDecision(approved = false, result), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private companion object {
        const val RECEIVER_BUDGET_MS = 8_000L
    }
}
