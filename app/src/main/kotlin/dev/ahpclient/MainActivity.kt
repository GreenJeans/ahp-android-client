package dev.ahpclient

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ahpclient.monitor.EXTRA_SESSION_URI
import dev.ahpclient.ui.AppRoot
import dev.ahpclient.ui.copyToClipboard
import dev.ahpclient.ui.openInBrowser

class MainActivity : ComponentActivity() {
    private var notificationsAllowed by mutableStateOf(true)
    private val container get() = (application as AhpApp).container

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = areNotificationsAllowed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        container.start()
        // A recreated activity (rotation) still holds the launching intent; only a fresh launch should act on it.
        if (savedInstanceState == null) openRequestedSession(intent)
        setContent {
            val monitorEnabled by container.monitor.enabled.collectAsState(initial = false)
            AppRoot(
                controller = container.controller,
                onOpenGitHub = { url ->
                    if (!openInBrowser(this, url)) Toast.makeText(this, "No browser found. Open the address on another device.", Toast.LENGTH_LONG).show()
                },
                onCopyCode = { code -> copyToClipboard(this, "GitHub code", code) },
                monitorEnabled = monitorEnabled,
                notificationsAllowed = notificationsAllowed,
                onMonitorChange = ::setMonitoring,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequestedSession(intent)
    }

    override fun onResume() {
        super.onResume()
        notificationsAllowed = areNotificationsAllowed()
    }

    private fun setMonitoring(on: Boolean) {
        container.setMonitoring(on)
        // Keeping the connection alive works without notifications, so a refusal does not undo the choice.
        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun areNotificationsAllowed(): Boolean =
        hasNotificationPermission() && getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    /** The extra is removed once used so the same tap is never acted on twice. The controller ignores sessions the host does not list. */
    private fun openRequestedSession(intent: Intent?) {
        val uri = intent?.getStringExtra(EXTRA_SESSION_URI) ?: return
        intent.removeExtra(EXTRA_SESSION_URI)
        container.controller.focusSession(uri)
    }
}
