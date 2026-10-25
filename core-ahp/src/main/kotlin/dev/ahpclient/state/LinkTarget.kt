package dev.ahpclient.state

import dev.ahpclient.app.SafeLinks

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
 * Takes the links out of markdown that [LinkTarget] would not open, leaving their text, so a path or an address that goes nowhere
 * is plain text and not a link that does nothing. An image becomes its description, because no image is ever fetched. Code (a
 * fenced block or an inline span) is left exactly as written.
 */
object MarkdownLinks {
    private val INLINE = Regex("""(!?)\[([^\]\n]*)]\(\s*<?([^()\s>]*(?:\([^()\s]*\)[^()\s>]*)*)>?(?:\s+(?:"[^"\n]*"|'[^'\n]*'))?\s*\)""")
    private val FENCE = Regex("""^\s{0,3}(`{3,}|~{3,})""")

    fun keepOpenable(markdown: String): String {
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
            if (fence != null || marker != null) out.append(line) else out.append(spans(line))
        }
        return out.toString()
    }

    /** Rewrites the links of one line outside its inline code spans. */
    private fun spans(line: String): String {
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
            out.append(rewrite(line.substring(textStart, i)))
            val end = close + run
            out.append(line, i, end)
            i = end
            textStart = end
        }
        out.append(rewrite(line.substring(textStart)))
        return out.toString()
    }

    private fun rewrite(text: String): String = INLINE.replace(text) { m ->
        val isImage = m.groupValues[1].isNotEmpty()
        val label = m.groupValues[2]
        when {
            isImage -> label.ifBlank { "image" }
            LinkTarget.of(m.groupValues[3]) != null -> m.value
            else -> label
        }
    }
}