package dev.ahpclient

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import dev.ahpclient.monitor.EXTRA_OPEN_INBOX
import dev.ahpclient.monitor.EXTRA_SESSION_URI
import dev.ahpclient.app.MonitorToggle
import dev.ahpclient.state.FinishedWatch
import dev.ahpclient.ui.FinishedAlertsUi
import dev.ahpclient.ui.AppRoot
import dev.ahpclient.ui.copyToClipboard
import dev.ahpclient.ui.openInBrowser

// A FragmentActivity because the device check that stands in front of tool approvals (BiometricPrompt) needs one.
class MainActivity : FragmentActivity() {
    private var notificationsAllowed by mutableStateOf(true)

    // Counts the notification taps that asked for a session, so the side menu can close for a tap on the session that is already open.
    private var sessionRequests by mutableIntStateOf(0)
    private val container get() = (application as AhpApp).container

    // Set while the permission dialog is up; called once the user has answered, whichever way.
    private var permissionAnswered: (() -> Unit)? = null

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = areNotificationsAllowed()
        permissionAnswered?.invoke()
        permissionAnswered = null
    }

    // The permission is asked for before monitoring starts: Android drops a foreground-service notification posted
    // while the permission is missing and does not bring it back once it is granted.
    private val monitorToggle = MonitorToggle(
        permissionGranted = ::hasNotificationPermission,
        askForPermission = { onAnswered ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionAnswered = onAnswered
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                onAnswered()
            }
        },
        apply = { on -> container.setMonitoring(on) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        container.start()
        // A recreated activity (rotation) still holds the launching intent; only a fresh launch should act on it.
        if (savedInstanceState == null) openRequestedSession(intent)
        setContent {
            val monitorEnabled by container.monitor.enabled.collectAsState(initial = false)
            val finishedWatch by container.monitor.finishedWatch.collectAsState(initial = FinishedWatch())
            AppRoot(
                controller = container.controller,
                ui = container.ui,
                onOpenGitHub = { url ->
                    if (!openInBrowser(this, url)) Toast.makeText(this, "No browser found. Open the address on another device.", Toast.LENGTH_LONG).show()
                },
                onCopyCode = { code -> copyToClipboard(this, "GitHub code", code) },
                monitorEnabled = monitorEnabled,
                notificationsAllowed = notificationsAllowed,
                onMonitorChange = ::setMonitoring,
                alerts = FinishedAlertsUi(finishedWatch, monitorEnabled, container::setWatchAll, container::setWatched),
                sessionRequests = sessionRequests,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        container.inForeground = true
        container.clearMonitorWarning()
        container.approvalGate.attach(this)
    }

    override fun onStop() {
        container.approvalGate.detach(this)
        container.inForeground = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequestedSession(intent)
    }

    override fun onResume() {
        super.onResume()
        notificationsAllowed = areNotificationsAllowed()
        // The permission may have been granted in system settings while monitoring was already on; bring the notification back.
        if (notificationsAllowed) container.refreshMonitorNotification()
    }

    private fun setMonitoring(on: Boolean) = monitorToggle.request(on)

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun areNotificationsAllowed(): Boolean =
        hasNotificationPermission() && getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    /** The extra is removed once used so the same tap is never acted on twice. The controller ignores sessions the host does not list. */
    private fun openRequestedSession(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_INBOX, false) == true) {
            intent.removeExtra(EXTRA_OPEN_INBOX)
            container.controller.closeSession()
            return
        }
        val uri = intent?.getStringExtra(EXTRA_SESSION_URI) ?: return
        intent.removeExtra(EXTRA_SESSION_URI)
        container.controller.focusSession(uri)
        sessionRequests++
    }
}
