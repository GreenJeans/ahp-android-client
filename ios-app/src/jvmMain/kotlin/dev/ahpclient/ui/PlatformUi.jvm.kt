package dev.ahpclient.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

@Composable
fun rememberPlatformUiActions(): PlatformUiActions = remember { JvmStubPlatformUiActions }

object JvmStubPlatformUiActions : PlatformUiActions {
    override val isDebuggable: Boolean = true
    override fun showToast(message: String) = Unit
    override fun copyToClipboard(label: String, text: String) = Unit
    override fun shareText(text: String, title: String) = Unit
    override fun openLink(raw: String): Boolean = false
    override fun shareLink(url: String): Boolean = false
    override fun leaveApp() = Unit
}

@Composable
fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit) = Unit

@Composable
fun KeepScreenAwakeEffect(keepAwake: Boolean) = Unit

@Composable
fun AnnounceForAccessibilityEffect(key: Int, message: String) = Unit

fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? = try {
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
} catch (_: Exception) {
    null
}
