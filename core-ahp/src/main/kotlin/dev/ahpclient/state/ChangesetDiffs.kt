package dev.ahpclient.state

import dev.ahpclient.ahp.AhpException
import com.microsoft.agenthostprotocol.generated.ChangesetFile
import com.microsoft.agenthostprotocol.generated.ContentEncoding
import com.microsoft.agenthostprotocol.generated.ContentRef
import com.microsoft.agenthostprotocol.generated.ResourceReadResult
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64
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
 * content, and it asks only for the content addresses that the open changeset's own file entry lists (the side before and the
 * side after), never for one that came from anywhere else. [files] is the open changeset's files as they are now, so a stale
 * or invented id finds nothing and nothing is read. A side that is too big by the host's own size hint is not even requested.
 */
class ChangesetDiffs(
    private val files: () -> List<ChangesetFile>,
    private val read: suspend (uri: String) -> ResourceReadResult,
) {
    suspend fun load(fileId: String): DiffResult {
        val file = files().firstOrNull { it.id == fileId } ?: return DiffResult.NoSuchFile
        if (file.edit.before == null && file.edit.after != null && (file.edit.diff?.removed ?: 0L) > 0L) return DiffResult.NoEarlierVersion
        return try {
            val old = when (val side = fetch(file.edit.before?.content)) {
                is Side.Text -> side.lines
                Side.Binary -> return DiffResult.Binary
                Side.TooLarge -> return DiffResult.TooLarge
                Side.Bad -> return DiffResult.Failed("The host gave an address that cannot be used.")
            }
            val new = when (val side = fetch(file.edit.after?.content)) {
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
            DiffResult.Failed("The connection failed (${e.javaClass.simpleName}).")
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

    private fun decode(data: String): String? =
        try {
            val bytes = Base64.getMimeDecoder().decode(data)
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: CharacterCodingException) {
            null
        }

    companion object {
        const val MAX_BYTES = 256 * 1024L
        private const val MAX_CHARS = 393_216
        private const val MAX_URI = 2_048
        private const val MAX_REASON = 200
    }
}
