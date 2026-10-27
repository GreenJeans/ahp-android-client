package dev.ahpclient.monitor

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import dev.ahpclient.AhpApp
import dev.ahpclient.state.InteractMessages

/**
 * The Approve button on a "needs your approval" notification. It has no screen of its own beyond a spinner: it exists so the
 * device check (a fingerprint, face or screen-lock prompt that names the tool) has an activity to appear over, including over
 * the lock screen, and it ends as soon as the decision is made or refused. The spinner shows while the app waits to reconnect
 * to the host after the process was killed, for as long as the decision allows, so the person is not left looking at nothing.
 * It is not exported, and the intent that starts it is immutable, so only this app's own notification can start it. It runs the
 * decision once: a recreated copy does nothing, and a second tap while one is pending is delivered to the running copy, which ignores it.
 */
class ApproveActivity : FragmentActivity() {
    private val container get() = (application as AhpApp).container
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) setShowWhenLocked(true)
        // A copy restored after the system dropped it must never repeat an approval from a stale intent.
        if (savedInstanceState != null) finish()
        setContentView(waitingView())
    }

    /** A spinner over a dim backdrop, for as long as the decision is being made. */
    private fun waitingView() = FrameLayout(this).apply {
        setBackgroundColor(SCRIM)
        addView(
            ProgressBar(this@ApproveActivity).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
                contentDescription = "Waiting for the host"
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
    }

    override fun onStart() {
        super.onStart()
        if (isFinishing) return
        container.approvalGate.attach(this)
        if (started) return
        started = true
        val chat = intent.getStringExtra(EXTRA_CHAT_URI)
        val call = intent.getStringExtra(EXTRA_TOOL_CALL_ID)
        val option = intent.getStringExtra(EXTRA_OPTION_ID)
        if (chat == null || call == null) {
            finish()
            return
        }
        container.decideFromNotification(chat, call, approve = true, optionId = option) { result ->
            Toast.makeText(applicationContext, InteractMessages.describeDecision(approved = true, result), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onStop() {
        container.approvalGate.detach(this)
        super.onStop()
    }

    private companion object {
        /** Half-opaque black, so the spinner reads over a lock screen of any colour. */
        const val SCRIM = 0x80000000.toInt()
    }
}
