package dev.ahpclient.state

/**
 * Markdown a host wrote for a single line (a tool's message, a notice) read as plain text, for rows that show it as text: a link
 * becomes its label, an image its description, and code or bold markers are dropped. Nothing is fetched or opened, and text that is
 * not markup is left exactly as it was.
 */
object MarkdownPlain {
    private val LINK = Regex("""!?\[([^\]\n]*)]\((?:[^()\s]|\([^()\s]*\))*(?:\s+(?:"[^"\n]*"|'[^'\n]*'))?\s*\)""")
    private val CODE = Regex("""(`+)([^`\n]+?)\1""")
    private val BOLD = Regex("""\*\*([^*\n]+?)\*\*""")

    fun of(markdown: String): String {
        if ('[' !in markdown && '`' !in markdown && "**" !in markdown) return markdown
        return markdown
            .replace(LINK) { it.groupValues[1] }
            .replace(CODE) { it.groupValues[2].trim() }
            .replace(BOLD) { it.groupValues[1] }
    }
}
