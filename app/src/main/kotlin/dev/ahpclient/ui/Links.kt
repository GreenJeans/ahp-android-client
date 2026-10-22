package dev.ahpclient.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import dev.ahpclient.app.SafeLinks

/**
 * Opens the GitHub device-flow page in a Custom Tab, or the browser if none supports tabs. Refuses anything that is
 * not an https github.com page, even though the caller already checked: this is the last line before a launch.
 *
 * @return false when nothing could be opened, so the caller can tell the user.
 */
fun openInBrowser(context: Context, url: String): Boolean {
    if (!SafeLinks.isSafeVerificationUri(url)) return false
    val uri = url.toUri()
    return try {
        CustomTabsIntent.Builder().build().launchUrl(context, uri)
        true
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}

fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    // Android 13 and newer show their own "copied" confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
}
