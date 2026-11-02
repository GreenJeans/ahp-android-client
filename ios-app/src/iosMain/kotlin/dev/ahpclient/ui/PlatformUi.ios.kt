@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.ahpclient.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.ahpclient.app.SafeLinks
import dev.ahpclient.state.LinkTarget
import kotlinx.cinterop.useContents
import org.jetbrains.skia.Image
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.UIKit.UIAccessibilityAnnouncementNotification
import platform.UIKit.UIAccessibilityPostNotification
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIAlertAction
import platform.UIKit.UIAlertActionStyleDefault
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyleAlert
import platform.UIKit.UIApplication
import platform.UIKit.UIPasteboard
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.popoverPresentationController

@Composable
fun rememberPlatformUiActions(): PlatformUiActions = remember { IosPlatformUiActions }

object IosPlatformUiActions : PlatformUiActions {
    override var isDebuggable: Boolean = true

    override fun showToast(message: String) {
        val vc = topViewController() ?: return
        val alert = UIAlertController.alertControllerWithTitle(
            title = null,
            message = message,
            preferredStyle = UIAlertControllerStyleAlert,
        )
        alert.addAction(UIAlertAction.actionWithTitle("OK", style = UIAlertActionStyleDefault, handler = null))
        vc.presentViewController(alert, animated = true, completion = null)
    }

    override fun copyToClipboard(label: String, text: String) {
        UIPasteboard.generalPasteboard.string = text
    }

    override fun shareText(text: String, title: String) {
        val vc = topViewController() ?: return
        val activityVc = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
        // Required on iPadOS so UIActivityViewController does not crash without a popover anchor.
        activityVc.popoverPresentationController?.apply {
            sourceView = vc.view
            sourceRect = vc.view.bounds.useContents {
                CGRectMake(size.width / 2.0, size.height / 2.0, 1.0, 1.0)
            }
            permittedArrowDirections = 0u
        }
        vc.presentViewController(activityVc, animated = true, completion = null)
    }

    override fun openLink(raw: String): Boolean = openLinkOnIos(raw)

    override fun shareLink(url: String): Boolean {
        shareText(url, "Link")
        return true
    }

    override fun leaveApp() = Unit
}

fun openInBrowserOnIos(url: String): Boolean {
    if (!SafeLinks.isSafeVerificationUri(url)) return false
    val nsUrl = NSURL.URLWithString(url) ?: return false
    val app = UIApplication.sharedApplication
    if (!app.canOpenURL(nsUrl)) return false
    app.openURL(nsUrl, options = emptyMap<Any?, Any>(), completionHandler = null)
    return true
}

fun openLinkOnIos(raw: String): Boolean {
    val uriString = when (val target = LinkTarget.of(raw)) {
        is LinkTarget.Web -> target.url
        is LinkTarget.Mail -> "mailto:${target.address}"
        is LinkTarget.Phone -> "tel:${target.number}"
        null -> return false
    }
    val nsUrl = NSURL.URLWithString(uriString) ?: return false
    val app = UIApplication.sharedApplication
    if (!app.canOpenURL(nsUrl)) return false
    app.openURL(nsUrl, options = emptyMap<Any?, Any>(), completionHandler = null)
    return true
}

private fun topViewController(): UIViewController? {
    val app = UIApplication.sharedApplication
    val scenes = app.connectedScenes
    val windowScene = scenes.firstOrNull { it is UIWindowScene } as? UIWindowScene
    val window = windowScene?.windows?.firstOrNull { (it as? UIWindow)?.isKeyWindow() == true } as? UIWindow
        ?: app.keyWindow
    var top = window?.rootViewController
    while (top?.presentedViewController != null) {
        top = top.presentedViewController
    }
    return top
}

@Composable
fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    // iOS navigation uses top bar back affordance and sheet dismissal.
}

@Composable
fun KeepScreenAwakeEffect(keepAwake: Boolean) {
    DisposableEffect(keepAwake) {
        UIApplication.sharedApplication.idleTimerDisabled = keepAwake
        onDispose {
            UIApplication.sharedApplication.idleTimerDisabled = false
        }
    }
}

@Composable
fun AnnounceForAccessibilityEffect(key: Int, message: String) {
    LaunchedEffect(key) {
        if (key > 0) {
            UIAccessibilityPostNotification(UIAccessibilityAnnouncementNotification, message)
        }
    }
}

fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? = try {
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
} catch (_: Exception) {
    null
}
