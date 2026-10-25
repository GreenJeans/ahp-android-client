package dev.ahpclient.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import dev.ahpclient.app.SafeLinks
import dev.ahpclient.state.LinkTarget

/** A browser installed on the phone, for the one links open in. */
data class BrowserOption(val packageName: String, val label: String)

object Browsers {
    /** The browsers on the phone, by name: apps that handle web pages and call themselves a browser, not every app that can open a link. */
    fun installed(context: Context): List<BrowserOption> {
        val pm = context.packageManager
        val view = Intent(Intent.ACTION_VIEW, "https://example.com".toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        val browsers = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
        val handlers = query(pm, view).map { it.activityInfo.packageName }.toSet()
        val named = query(pm, browsers).map { it.activityInfo.packageName }.toSet()
        // Where the phone does not say which apps are browsers, every handler of web pages is offered.
        val wanted = if (named.isEmpty()) handlers else handlers.intersect(named)
        return query(pm, view)
            .filter { it.activityInfo.packageName in wanted }
            .map { BrowserOption(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun isInstalled(context: Context, packageName: String): Boolean = installed(context).any { it.packageName == packageName }

    @Suppress("DEPRECATION")
    private fun query(pm: PackageManager, intent: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
}

/**
 * Opens the GitHub device-flow page in [browser], or the system's choice when there is none or it is gone. Refuses anything that
 * is not an https github.com page, even though the caller already checked: this is the last line before a launch.
 *
 * @return false when nothing could be opened, so the caller can tell the user.
 */
fun openInBrowser(context: Context, url: String, browser: String? = null): Boolean {
    if (!SafeLinks.isSafeVerificationUri(url)) return false
    return launchWeb(context, url.toUri(), browser)
}

/**
 * Does what a link in an agent's text is for, with the intent that is for it: a web page in the browser, a mail in the mail app, a
 * number in the dialer (which only fills it in; it never calls). Anything that is not one of those is refused, even though the
 * caller already confirmed it with the person: this is the last line before a launch.
 *
 * @return false when nothing could be opened, so the caller can tell the user.
 */
fun openLink(context: Context, raw: String, browser: String? = null): Boolean = when (val target = LinkTarget.of(raw)) {
    is LinkTarget.Web -> launchWeb(context, target.url.toUri(), browser)
    is LinkTarget.Mail -> start(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", target.address, null)))
    is LinkTarget.Phone -> start(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", target.number, null)))
    null -> false
}

private fun launchWeb(context: Context, uri: Uri, browser: String?): Boolean {
    val chosen = browser?.takeIf { Browsers.isInstalled(context, it) }
    return try {
        val tabs = CustomTabsIntent.Builder().build()
        if (chosen != null) tabs.intent.setPackage(chosen)
        tabs.launchUrl(context, uri)
        true
    } catch (_: ActivityNotFoundException) {
        start(context, Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).also { if (chosen != null) it.setPackage(chosen) }) ||
            (chosen != null && start(context, Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)))
    }
}

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