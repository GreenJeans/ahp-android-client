package dev.ahpclient.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import dev.ahpclient.app.SafeLinks
import dev.ahpclient.state.LinkTarget

/**
 * Opens the GitHub device-flow page in the phone's default browser. Refuses anything that is not an https github.com page, even
 * though the caller already checked: this is the last line before a launch.
 *
 * @return false when nothing could be opened, so the caller can tell the user.
 */
fun openInBrowser(context: Context, url: String): Boolean {
    if (!SafeLinks.isSafeVerificationUri(url)) return false
    return launchWeb(context, url.toUri())
}

/**
 * Does what a link in an agent's text is for, with the intent that is for it: a web page in the browser, a mail in the mail app, a
 * number in the dialer (which only fills it in; it never calls). Anything that is not one of those is refused, even though the
 * caller already confirmed it with the person: this is the last line before a launch.
 *
 * @return false when nothing could be opened, so the caller can tell the user.
 */
fun openLink(context: Context, raw: String): Boolean = when (val target = LinkTarget.of(raw)) {
    is LinkTarget.Web -> launchWeb(context, target.url.toUri())
    is LinkTarget.Mail -> start(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", target.address, null)))
    is LinkTarget.Phone -> start(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", target.number, null)))
    null -> false
}

/** A Custom Tab with no package set, so the phone's default browser opens it; a plain view of the page when nothing handles Custom Tabs. */
private fun launchWeb(context: Context, uri: Uri): Boolean = try {
    CustomTabsIntent.Builder().build().launchUrl(context, uri)
    true
} catch (_: ActivityNotFoundException) {
    start(context, Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
}

/** The system share sheet's intent for [url]: it is where "open in another browser", copy and every messaging app already are. */
fun shareIntent(url: String): Intent =
    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null)

/** @return false when nothing could be started, so the caller can tell the user. */
fun shareLink(context: Context, url: String): Boolean = start(context, shareIntent(url))

private fun start(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
}

fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    // Android 13 and newer show their own "copied" confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
}