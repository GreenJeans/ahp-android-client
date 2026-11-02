package dev.ahpclient

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import dev.ahpclient.ui.AppRoot
import dev.ahpclient.ui.FinishedAlertsUi
import dev.ahpclient.ui.IosPlatformUiActions
import dev.ahpclient.ui.openInBrowserOnIos
import platform.UIKit.UIViewController

/**
 * Entry point called from Swift (`ContentView.swift`) to embed the full Compose Multiplatform UI in `UIViewController`.
 */
fun MainViewController(): UIViewController {
    val container = IosAppContainer.shared
    container.start()
    return ComposeUIViewController {
        val monitorEnabled by container.monitor.enabled.collectAsState()
        val watch by container.monitor.watch.collectAsState()
        var sessionRequests by remember { mutableIntStateOf(0) }

        DisposableEffect(Unit) {
            container.inForeground = true
            container.monitorService.onEnterForeground()
            onDispose {
                container.inForeground = false
                container.monitorService.onEnterBackground()
            }
        }

        AppRoot(
            controller = container.controller,
            ui = container.ui,
            onOpenGitHub = { url ->
                if (!openInBrowserOnIos(url)) {
                    IosPlatformUiActions.showToast("Could not open browser")
                }
            },
            onCopyCode = { code ->
                IosPlatformUiActions.copyToClipboard("GitHub verification code", code)
                IosPlatformUiActions.showToast("Code copied")
            },
            monitorEnabled = monitorEnabled,
            notificationsAllowed = true,
            onMonitorChange = container::setMonitoring,
            alerts = FinishedAlertsUi(
                watch = watch,
                onAll = container::setWatchAll,
                onSession = container::setWatched,
            ),
            sessionRequests = sessionRequests,
            quickReplyProvider = container.quickReplyProvider,
        )
    }
}
