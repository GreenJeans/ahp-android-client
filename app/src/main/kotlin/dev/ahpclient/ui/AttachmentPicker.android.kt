package dev.ahpclient.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import dev.ahpclient.state.AttachmentProblem
import dev.ahpclient.state.Attachments
import dev.ahpclient.state.ComposerAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** The types the chooser offers; [Attachments.accepts] is what decides, after the file is read. */
private val OFFERED_TYPES = arrayOf("image/*", "text/*", "application/json")

/**
 * The system document chooser (no storage permission needed: the person hands over exactly the files they pick). Each file is read
 * off the main thread and never past [Attachments.MAX_BYTES], so a huge file cannot fill memory. Files that can be attached come
 * back together in [onPicked]; one that cannot is named in [onUnreadable].
 */
@Composable
fun rememberAttachmentPicker(onPicked: (List<ComposerAttachment>) -> Unit, onUnreadable: (String) -> Unit): AttachmentPicker? {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val picked by rememberUpdatedState(onPicked)
    val unreadable by rememberUpdatedState(onUnreadable)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val results = withContext(Dispatchers.IO) { uris.map { readAttachment(resolver, it) } }
            results.filterIsInstance<Read.Failed>().forEach { unreadable(it.reason) }
            results.filterIsInstance<Read.Ok>().map { it.file }.takeIf { it.isNotEmpty() }?.let(picked)
        }
    }
    return remember(launcher) { AttachmentPicker { launcher.launch(OFFERED_TYPES) } }
}

private sealed interface Read {
    class Ok(val file: ComposerAttachment) : Read

    class Failed(val reason: String) : Read
}

private fun readAttachment(resolver: ContentResolver, uri: Uri): Read {
    val name = displayName(resolver, uri)
    val type = contentType(resolver, uri, name)
    if (!Attachments.accepts(type)) return Read.Failed(Attachments.describe(AttachmentProblem.Unsupported(name)))
    val bytes = try {
        resolver.openInputStream(uri)?.use { it.readUpTo(Attachments.MAX_BYTES + 1) }
    } catch (_: Exception) {
        null
    } ?: return Read.Failed("Could not read $name.")
    if (bytes.size > Attachments.MAX_BYTES) return Read.Failed(Attachments.describe(AttachmentProblem.TooBig(name, Attachments.MAX_BYTES)))
    return Read.Ok(ComposerAttachment(name, type, bytes))
}

/** At most [limit] bytes, so a file larger than that is noticed without being held whole. */
private fun InputStream.readUpTo(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (total < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - total))
        if (count < 0) break
        out.write(buffer, 0, count)
        total += count
    }
    return out.toByteArray()
}

private fun displayName(resolver: ContentResolver, uri: Uri): String =
    try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')

/** The provider's own type, else one from the file's extension: some providers call every file `application/octet-stream`. */
private fun contentType(resolver: ContentResolver, uri: Uri, name: String): String {
    val reported = resolver.getType(uri)?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
    return reported
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        ?: "application/octet-stream"
}
