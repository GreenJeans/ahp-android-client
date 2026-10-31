package dev.ahpclient.state

import dev.ahpclient.app.SafeLinks
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What a link in an agent's text can be made to do on a phone. Only things that make sense here are listed: a web page, a mail
 * to write, a number to dial. A path on the host's disk, a `file:` or `vscode:` address, a relative link, or anything else is
 * not a target, so it is never made to look tappable. Pure, so it is tested without a phone.
 */
sealed interface LinkTarget {
    /** What the person is shown before it opens: the site, the address or the number. */
    val shown: String

    data class Web(val url: String, override val shown: String) : LinkTarget

    data class Mail(val address: String) : LinkTarget {
        override val shown: String get() = address
    }

    data class Phone(val number: String) : LinkTarget {
        override val shown: String get() = number
    }

    companion object {
        private const val MAX_LENGTH = 2_048
        private val MAIL = Regex("""[A-Za-z0-9._%+\-]{1,64}@[A-Za-z0-9\-]{1,63}(\.[A-Za-z0-9\-]{1,63}){1,5}""")
        private val PHONE = Regex("""\+?[0-9][0-9 ().\-]{2,19}""")

        fun of(raw: String): LinkTarget? {
            val link = raw.trim()
            if (link.isEmpty() || link.length > MAX_LENGTH) return null
            val lower = link.lowercase()
            return when {
                // The scheme is case-insensitive; the rule that decides what may be opened is written for the lower-case form.
                lower.startsWith("https://") -> ("https" + link.substring(5)).let { if (SafeLinks.isSafeWebLink(it)) Web(it, hostOf(it)) else null }
                lower.startsWith("mailto:") -> link.substring(7).substringBefore('?').takeIf { MAIL.matches(it) }?.let(::Mail)
                lower.startsWith("tel:") -> link.substring(4).takeIf { PHONE.matches(it) }?.let(::Phone)
                else -> null
            }
        }

        private fun hostOf(url: String): String = try {
            java.net.URI(url).host.orEmpty()
        } catch (_: java.net.URISyntaxException) {
            ""
        }
    }
}

/**
 * A file path or `file:` address on the host's disk, optionally with a 1-based line number or range (`#L10-L20` or `:10`).
 * Kept separate from [LinkTarget] because it does not launch an external phone app: when a host file reader is wired in,
 * tapping the link previews the file in-app via `resource/read`.
 */
data class HostFileTarget(
    val rawPath: String,
    val displayPath: String,
    val startLine: Int? = null,
    val endLine: Int? = null,
) {
    /** Builds the `file://` URI to pass to `resource/read`, resolving relative paths against the session's first working directory. */
    fun resolveUri(workingDirectories: List<String> = emptyList()): String {
        val clean = rawPath.replace('\\', '/')
        if (clean.lowercase().startsWith("file://")) return clean
        if (clean.startsWith("/")) return "file://$clean"
        if (WINDOWS_DRIVE.containsMatchIn(clean)) return "file:///$clean"
        val rel = clean.removePrefix("./").trimStart('/')
        val base = workingDirectories.firstOrNull { it.isNotBlank() }?.trim()?.replace('\\', '/')?.trimEnd('/')
        if (base != null) {
            return when {
                base.lowercase().startsWith("file://") -> "$base/$rel"
                base.startsWith("/") -> "file://$base/$rel"
                WINDOWS_DRIVE.containsMatchIn(base) -> "file:///$base/$rel"
                else -> "file:///$base/$rel"
            }
        }
        return "file:///$rel"
    }

    fun toInternalUri(): String {
        val encodedPath = URLEncoder.encode(rawPath, Charsets.UTF_8.name())
        val startParam = startLine?.let { "&start=$it" }.orEmpty()
        val endParam = endLine?.let { "&end=$it" }.orEmpty()
        return "$INTERNAL_PREFIX?path=$encodedPath$startParam$endParam"
    }

    companion object {
        private const val MAX_LENGTH = 2_048
        private const val INTERNAL_PREFIX = "ahp-file://open"
        private val SCHEME_PREFIX = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*:""")
        private val WINDOWS_DRIVE = Regex("""^[A-Za-z]:/""")
        private val FRAGMENT_LINES = Regex("""^[Ll](\d+)(?:[Cc]\d+)?(?:-[Ll]?(\d+)(?:[Cc]\d+)?)?$""")
        private val COLON_LINES = Regex("""^(.+?):(\d+)(?:-(\d+)|:\d+)?$""")
        private val SIMPLE_FILE = Regex("""^[A-Za-z0-9_.\-]+(\.[A-Za-z0-9]{1,12})+$""")

        fun of(raw: String): HostFileTarget? {
            val link = raw.trim()
            if (link.isEmpty() || link.length > MAX_LENGTH) return null
            if (link.any { it.isWhitespace() || it.code < 0x20 }) return null
            val lower = link.lowercase()
            if (lower.startsWith(INTERNAL_PREFIX)) return fromInternalUri(link)

            val withoutScheme = when {
                lower.startsWith("file:///") -> {
                    val rest = link.substring(7) // keeps leading '/' unless Windows drive '/C:/...'
                    if (rest.length >= 4 && rest[0] == '/' && rest[2] == ':' && (rest[3] == '/' || rest[3] == '\\')) rest.substring(1) else rest
                }
                lower.startsWith("file://") -> link.substring(7)
                else -> {
                    val normalized = link.replace('\\', '/')
                    if (SCHEME_PREFIX.containsMatchIn(normalized) && !WINDOWS_DRIVE.containsMatchIn(normalized)) return null
                    normalized
                }
            }.replace('\\', '/')

            if (withoutScheme.startsWith("//") || withoutScheme.startsWith("#")) return null

            val hashIndex = withoutScheme.indexOf('#')
            val pathPart = if (hashIndex >= 0) withoutScheme.substring(0, hashIndex) else withoutScheme
            val fragment = if (hashIndex >= 0) withoutScheme.substring(hashIndex + 1) else null

            var startLine: Int? = null
            var endLine: Int? = null
            if (fragment != null) {
                val match = FRAGMENT_LINES.matchEntire(fragment) ?: return null
                startLine = match.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
                endLine = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()?.takeIf { it > 0 }
            }

            var cleanPath = pathPart
            if (startLine == null) {
                val colonMatch = COLON_LINES.matchEntire(pathPart)
                if (colonMatch != null && colonMatch.groupValues[1].length > 1) {
                    cleanPath = colonMatch.groupValues[1]
                    startLine = colonMatch.groupValues[2].toIntOrNull()?.takeIf { it > 0 }
                    endLine = colonMatch.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull()?.takeIf { it > 0 }
                }
            }

            if (cleanPath.isBlank() || cleanPath == "/" || cleanPath == "." || cleanPath == "..") return null
            val isAbsoluteOrNested = cleanPath.startsWith("/") || WINDOWS_DRIVE.containsMatchIn(cleanPath) || '/' in cleanPath
            val isSimpleFile = SIMPLE_FILE.matches(cleanPath)
            if (!isAbsoluteOrNested && !isSimpleFile) return null
            if (cleanPath.endsWith("/")) return null

            val safeEnd = if (startLine != null && endLine != null && endLine >= startLine) endLine else null
            return HostFileTarget(
                rawPath = cleanPath,
                displayPath = displayPath(cleanPath),
                startLine = startLine,
                endLine = safeEnd,
            )
        }

        fun fromInternalUri(uri: String): HostFileTarget? {
            val trimmed = uri.trim()
            if (!trimmed.startsWith("$INTERNAL_PREFIX?")) return null
            val query = trimmed.substringAfter('?')
            var path: String? = null
            var start: Int? = null
            var end: Int? = null
            for (pair in query.split('&')) {
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                val key = pair.substring(0, eq)
                val value = pair.substring(eq + 1)
                when (key) {
                    "path" -> path = try {
                        URLDecoder.decode(value, Charsets.UTF_8.name())
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                    "start" -> start = value.toIntOrNull()?.takeIf { it > 0 }
                    "end" -> end = value.toIntOrNull()?.takeIf { it > 0 }
                }
            }
            val clean = path?.takeIf { it.isNotBlank() } ?: return null
            val safeEnd = if (start != null && end != null && end >= start) end else null
            return HostFileTarget(
                rawPath = clean,
                displayPath = displayPath(clean),
                startLine = start,
                endLine = safeEnd,
            )
        }
    }
}

/**
 * Takes the links out of markdown that [LinkTarget] would not open, leaving their text, so a path or an address that goes nowhere
 * is plain text and not a link that does nothing. When [allowHostFiles] is true, host file paths recognized by [HostFileTarget]
 * are rewritten to internal `ahp-file:` URIs so the chat view can open them in the in-app file viewer. An image becomes its
 * description, because no external image is ever fetched. Code (a fenced block or an inline span) is left exactly as written.
 */
object MarkdownLinks {
    private val INLINE = Regex("""(!?)\[([^\]\n]*)]\(\s*<?([^()\s>]*(?:\([^()\s]*\)[^()\s>]*)*)>?(?:\s+(?:"[^"\n]*"|'[^'\n]*'))?\s*\)""")
    private val FENCE = Regex("""^\s{0,3}(`{3,}|~{3,})""")

    fun keepOpenable(markdown: String, allowHostFiles: Boolean = false): String {
        if ('[' !in markdown) return markdown
        val out = StringBuilder(markdown.length)
        var fence: String? = null
        for ((i, line) in markdown.split("\n").withIndex()) {
            if (i > 0) out.append('\n')
            val marker = FENCE.find(line)?.groupValues?.get(1)
            when {
                fence != null -> if (marker != null && marker[0] == fence[0] && marker.length >= fence.length) fence = null
                marker != null -> fence = marker
            }
            if (fence != null || marker != null) out.append(line) else out.append(spans(line, allowHostFiles))
        }
        return out.toString()
    }

    /** Rewrites the links of one line outside its inline code spans. */
    private fun spans(line: String, allowHostFiles: Boolean): String {
        if ('[' !in line) return line
        val out = StringBuilder(line.length)
        var i = 0
        var textStart = 0
        while (i < line.length) {
            if (line[i] != '`') {
                i++
                continue
            }
            var run = 0
            while (i + run < line.length && line[i + run] == '`') run++
            val close = line.indexOf("`".repeat(run), i + run).takeIf { it >= 0 }
            if (close == null) {
                i += run
                continue
            }
            out.append(rewrite(line.substring(textStart, i), allowHostFiles))
            val end = close + run
            out.append(line, i, end)
            i = end
            textStart = end
        }
        out.append(rewrite(line.substring(textStart), allowHostFiles))
        return out.toString()
    }

    private fun rewrite(text: String, allowHostFiles: Boolean): String = INLINE.replace(text) { m ->
        val isImage = m.groupValues[1].isNotEmpty()
        val label = m.groupValues[2]
        val rawUrl = m.groupValues[3]
        when {
            isImage -> label.ifBlank { "image" }
            LinkTarget.of(rawUrl) != null -> m.value
            allowHostFiles -> {
                val hostTarget = HostFileTarget.of(rawUrl)
                if (hostTarget != null) "[$label](${hostTarget.toInternalUri()})" else label
            }
            else -> label
        }
    }
}
