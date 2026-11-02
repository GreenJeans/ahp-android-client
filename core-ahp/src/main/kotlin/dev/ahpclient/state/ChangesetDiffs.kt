package dev.ahpclient.state

import dev.ahpclient.ahp.AhpException
import com.microsoft.agenthostprotocol.generated.ChangesetFile
import com.microsoft.agenthostprotocol.generated.ContentEncoding
import com.microsoft.agenthostprotocol.generated.ContentRef
import com.microsoft.agenthostprotocol.generated.FileEditSide
import com.microsoft.agenthostprotocol.generated.ResourceReadResult
import dev.ahpclient.platform.IOException
import dev.ahpclient.platform.base64MimeDecode
import dev.ahpclient.platform.decodeUtf8Strict
import kotlinx.coroutines.CancellationException

/** How loading one file's changes ended. Everything but [Ready] has a reason a screen says in words. */
sealed interface DiffResult {
    data class Ready(val diff: FileDiff) : DiffResult

    /** The file is not (or is no longer) in the open changeset, so nothing was read. */
    data object NoSuchFile : DiffResult

    /** The file, or what changed in it, is more than the phone compares. */
    data object TooLarge : DiffResult

    /** The file is not text. */
    data object Binary : DiffResult

    /** The host reports removed lines but kept no earlier version, so there is nothing to compare the new one with. */
    data object NoEarlierVersion : DiffResult

    /** Reading failed. [reason] is bounded display text. */
    data class Failed(val reason: String) : DiffResult
}

/**
 * Reads the two sides of one changed file and works out the line diff. This is the only place the app asks the host to read
 * content for diffs, and it asks only for the content addresses that the open changeset's own file entry or an inline fileEdit
 * tool result lists (the side before and the side after). [files] is the open changeset's files as they are now, so a stale
 * or invented id finds nothing and nothing is read. A side that is too big by the host's own size hint is not even requested.
 */
class ChangesetDiffs(
    private val files: () -> List<ChangesetFile>,
    private val read: suspend (uri: String) -> ResourceReadResult,
) {
    suspend fun load(fileId: String): DiffResult {
        val file = files().firstOrNull { it.id == fileId } ?: return DiffResult.NoSuchFile
        return loadSides(file.edit.before, file.edit.after, file.edit.diff?.removed)
    }

    suspend fun loadSides(before: FileEditSide?, after: FileEditSide?, removedHint: Long? = null): DiffResult {
        if (before == null && after != null && (removedHint ?: 0L) > 0L) return DiffResult.NoEarlierVersion
        if (before == null && after == null) return DiffResult.NoSuchFile
        return try {
            val old = when (val side = fetch(before?.content)) {
                is Side.Text -> side.lines
                Side.Binary -> return DiffResult.Binary
                Side.TooLarge -> return DiffResult.TooLarge
                Side.Bad -> return DiffResult.Failed("The host gave an address that cannot be used.")
            }
            val new = when (val side = fetch(after?.content)) {
                is Side.Text -> side.lines
                Side.Binary -> return DiffResult.Binary
                Side.TooLarge -> return DiffResult.TooLarge
                Side.Bad -> return DiffResult.Failed("The host gave an address that cannot be used.")
            }
            LineDiff.diff(old, new)?.let { DiffResult.Ready(it) } ?: DiffResult.TooLarge
        } catch (e: CancellationException) {
            throw e
        } catch (e: AhpException.Rpc) {
            DiffResult.Failed("The host said: ${e.message.orEmpty().take(MAX_REASON)}")
        } catch (e: AhpException) {
            DiffResult.Failed("The connection failed (${(e::class.simpleName ?: "Exception")}).")
        } catch (e: IOException) {
            DiffResult.Failed("The connection failed.")
        }
    }

    private sealed interface Side {
        data class Text(val lines: List<String>) : Side

        data object Binary : Side

        data object TooLarge : Side

        data object Bad : Side
    }

    /** One side of the file: no lines when the file has no such side (an added file has no side before). */
    private suspend fun fetch(ref: ContentRef?): Side {
        if (ref == null) return Side.Text(emptyList())
        if (ref.uri.isBlank() || ref.uri.length > MAX_URI) return Side.Bad
        if ((ref.sizeHint ?: 0L) > MAX_BYTES) return Side.TooLarge
        val result = read(ref.uri)
        if (result.data.length > MAX_CHARS) return Side.TooLarge
        val text = when (result.encoding) {
            ContentEncoding.UTF8 -> result.data
            ContentEncoding.BASE64 -> decode(result.data) ?: return Side.Binary
        }
        if ('\u0000' in text) return Side.Binary
        val lines = LineDiff.lines(text)
        return if (lines.size > LineDiff.MAX_LINES) Side.TooLarge else Side.Text(lines)
    }

    private fun decode(data: String): String? {
        val bytes = base64MimeDecode(data) ?: return null
        return decodeUtf8Strict(bytes)
    }

    companion object {
        const val MAX_BYTES = 256 * 1024L
        private const val MAX_CHARS = 393_216
        private const val MAX_URI = 2_048
        private const val MAX_REASON = 200
    }
}

/** How reading a host file or referenced resource ended. */
sealed interface RichReadResult {
    data class Ready(
        val title: String,
        val payload: RichPayload,
        val startLine: Int? = null,
        val endLine: Int? = null,
    ) : RichReadResult

    data object TooLarge : RichReadResult

    data class Failed(val reason: String) : RichReadResult
}

/**
 * Reads a [HostFileTarget] or [ResourceRef] from the host with bounded size checks and decodes it into a [RichPayload].
 */
class HostFileReader(
    private val read: suspend (uri: String) -> ResourceReadResult,
    private val workingDirectories: () -> List<String> = { emptyList() },
) {
    suspend fun readTarget(target: HostFileTarget): RichReadResult {
        val uri = target.resolveUri(workingDirectories())
        return fetchUri(
            uri = uri,
            title = target.displayPath,
            contentType = guessMimeType(target.displayPath),
            sizeHint = null,
            startLine = target.startLine,
            endLine = target.endLine,
        )
    }

    suspend fun readResourceRef(ref: ResourceRef, title: String): RichReadResult {
        if (ref.embeddedData != null) {
            val payload = RichDecoder.decodeEmbedded(ref)
            return if (payload != null) RichReadResult.Ready(title = title, payload = payload) else RichReadResult.TooLarge
        }
        val uri = ref.uri ?: return RichReadResult.TooLarge
        if ((ref.sizeHint ?: 0L) > MAX_RESOURCE_BYTES) return RichReadResult.TooLarge
        return fetchUri(uri = uri, title = title, contentType = ref.contentType ?: guessMimeType(title), sizeHint = ref.sizeHint)
    }

    private suspend fun fetchUri(
        uri: String,
        title: String,
        contentType: String?,
        sizeHint: Long?,
        startLine: Int? = null,
        endLine: Int? = null,
    ): RichReadResult {
        if (uri.isBlank() || uri.length > MAX_URI) return RichReadResult.Failed("The host gave an address that cannot be used.")
        if ((sizeHint ?: 0L) > MAX_RESOURCE_BYTES) return RichReadResult.TooLarge
        return try {
            val result = read(uri)
            val payload = RichDecoder.decodeResourceResult(result, mimeHint = contentType, pathHint = title) ?: return RichReadResult.TooLarge
            RichReadResult.Ready(title = title, payload = payload, startLine = startLine, endLine = endLine)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AhpException.Rpc) {
            RichReadResult.Failed("The host said: ${e.message.orEmpty().take(MAX_REASON)}")
        } catch (e: AhpException) {
            RichReadResult.Failed("The connection failed (${(e::class.simpleName ?: "Exception")}).")
        } catch (e: IOException) {
            RichReadResult.Failed("The connection failed.")
        }
    }

    companion object {
        private const val MAX_URI = 2_048
        private const val MAX_REASON = 200
        private const val MAX_RESOURCE_BYTES = 512 * 1024L

        internal fun guessMimeType(path: String): String? {
            val ext = path.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "bmp" -> "image/bmp"
                "svg" -> "image/svg+xml"
                "md", "markdown" -> "text/markdown"
                "json" -> "application/json"
                "xml" -> "application/xml"
                "yaml", "yml" -> "application/yaml"
                "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "rs", "go", "c", "cpp", "h", "cs", "rb", "sh", "sql", "html", "css", "toml", "gradle", "txt" -> "text/plain"
                else -> null
            }
        }
    }
}
