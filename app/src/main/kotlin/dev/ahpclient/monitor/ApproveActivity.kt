package dev.ahpclient.monitor

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import dev.ahpclient.AhpApp
import dev.ahpclient.state.InteractMessages

/**
 * The Approve button on a "needs your approval" notification. It has no screen of its own: it exists so the device check
 * (a fingerprint, face or screen-lock prompt that names the tool) has an activity to appear over, including over the lock
 * screen, and it ends as soon as the decision is made or refused. It is not exported, and the intent that starts it is
 * immutable, so only this app's own notification can start it. It runs the decision once: a recreated copy does nothing,
 * and a second tap while one is pending is delivered to the running copy, which ignores it.
 */
class ApproveActivity : FragmentActivity() {
    private val container get() = (application as AhpApp).container
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setShowWhenLocked(true)
        // A copy restored after the system dropped it must never repeat an approval from a stale intent.
        if (savedInstanceState != null) finish()
    }

    override fun onStart() {
        super.onStart()
        if (isFinishing) return
        container.approvalGate.attach(this)
        if (started) return
        started = true
        val chat = intent.getStringExtra(EXTRA_CHAT_URI)
        val call = intent.getStringExtra(EXTRA_TOOL_CALL_ID)
        if (chat == null || call == null) {
            finish()
            return
        }
        container.decideFromNotification(chat, call, approve = true) { result ->
            Toast.makeText(applicationContext, InteractMessages.describeDecision(approved = true, result), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onStop() {
        container.approvalGate.detach(this)
        super.onStop()
    }
}
