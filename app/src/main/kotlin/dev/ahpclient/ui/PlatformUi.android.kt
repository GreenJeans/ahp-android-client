package dev.ahpclient.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

@Composable
fun rememberPlatformUiActions(): PlatformUiActions {
    val context = LocalContext.current
    return remember(context) {
        object : PlatformUiActions {
            override val isDebuggable: Boolean
                get() = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

            override fun showToast(message: String) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }

            override fun copyToClipboard(label: String, text: String) {
                copyToClipboard(context, label, text)
            }

            override fun shareText(text: String, title: String) {
                try {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, title)
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(intent, title))
                } catch (_: Exception) {
                    // Ignore if no activity can handle share on test devices
                }
            }

            override fun openLink(raw: String): Boolean = openLink(context, raw)

            override fun shareLink(url: String): Boolean = shareLink(context, url)

            override fun leaveApp() = leaveApp(context)
        }
    }
}

@Composable
fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}

@Composable
fun KeepScreenAwakeEffect(keepAwake: Boolean) {
    val view = LocalView.current
    DisposableEffect(keepAwake) {
        view.keepScreenOn = keepAwake
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
fun AnnounceForAccessibilityEffect(key: Int, message: String) {
    val view = LocalView.current
    LaunchedEffect(key) {
        @Suppress("DEPRECATION")
        if (key > 0) view.announceForAccessibility(message)
    }
}

fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? = try {
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
} catch (_: Exception) {
    null
}

/**
 * Puts the task in the background, as the system does for a root activity from Android 12, whatever the version: the activity and the
 * connection stay as they are. Never `finish()`.
 */
internal fun leaveApp(context: Context) {
    generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()?.moveTaskToBack(true)
}
