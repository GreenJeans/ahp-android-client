package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.ChatState
import com.microsoft.agenthostprotocol.generated.ResponsePartToolCall
import com.microsoft.agenthostprotocol.generated.TerminalContentPartCommand
import com.microsoft.agenthostprotocol.generated.TerminalContentPartUnclassified
import com.microsoft.agenthostprotocol.generated.TerminalLifecycleStateExited
import com.microsoft.agenthostprotocol.generated.TerminalLifecycleStateRunning
import com.microsoft.agenthostprotocol.generated.TerminalState

/** Which terminals a chat points at: the ones its tool calls ran commands in. */
object TerminalLinks {
    /** The longest terminal address accepted. A real one carries its session, chat and tool call in a query, and runs to about 500 characters. */
    const val MAX_URI = 1_024

    private val SCHEMES = listOf("agenthost-terminal://", "ahp-terminal:/")

    /** Only an address that is a terminal's, short enough and free of control characters, is ever subscribed to. */
    fun isTerminalUri(uri: String): Boolean =
        uri.length <= MAX_URI && SCHEMES.any { uri.startsWith(it) && uri.length > it.length } && uri.none { it.isISOControl() }

    /**
     * The terminal addresses that tool results in [chat] name, and only those. A person can open exactly these from this chat,
     * so a host cannot make the app subscribe to an address of its choosing by putting it somewhere else.
     */
    fun referencedBy(chat: ChatState): Set<String> {
        val uris = LinkedHashSet<String>()
        val parts = chat.turns.flatMap { it.responseParts } + chat.activeTurn?.responseParts.orEmpty()
        for (part in parts) {
            val call = (part as? ResponsePartToolCall)?.value?.toolCall ?: continue
            ToolDetail.from(call).results.filterIsInstance<ToolResultView.Terminal>().mapNotNullTo(uris) { it.terminalUri }
        }
        return uris
    }
}

/**
 * A terminal as the screen shows it: plain text lines, the last [MAX_LINES] of them, with its state in words. Nothing here
 * holds more than a bounded amount of text, whatever the terminal has printed.
 *
 * The output is turned into plain text: escape sequences (colours, cursor moves, window titles) are dropped, a carriage
 * return or backspace overwrites as a terminal would, other control characters are dropped, and characters that reorder text
 * on screen are swapped for a visible mark. Cursor movement is not replayed, so a program that redraws several lines in place
 * (a progress display) shows what it printed in order rather than what a terminal would end up showing.
 */
data class TerminalView(
    val title: String,
    val running: Boolean,
    val status: String,
    val cwd: String?,
    val lines: List<String>,
    /** How many earlier lines were left out because only the last [MAX_LINES] are kept. */
    val omittedLines: Int,
) {
    companion object {
        const val MAX_LINES = 2_000

        /** Only this much of the end of the output is read, so a terminal that has printed for hours cannot stall the screen. */
        const val MAX_RAW_CHARS = 256 * 1024

        private const val MAX_TITLE = 120
        private const val MAX_CWD = 200
        private const val TAB = 8

        fun from(state: TerminalState): TerminalView {
            val (raw, cut) = tailOf(state)
            val (lines, omitted) = plainLines(raw, dropFirst = cut)
            val lifecycle = state.lifecycle
            val running = lifecycle is TerminalLifecycleStateRunning
            val status = when (lifecycle) {
                is TerminalLifecycleStateRunning -> "Running"
                is TerminalLifecycleStateExited -> lifecycle.value.exitCode?.let { "Exited with code $it" } ?: "Exited"
                else -> "State unknown"
            }
            return TerminalView(
                title = LineDiff.clean(state.title.ifBlank { "Terminal" }.take(MAX_TITLE)),
                running = running,
                status = status,
                cwd = state.cwd?.takeIf { it.isNotBlank() }?.let { LineDiff.clean(displayPath(it).take(MAX_CWD)) },
                lines = lines,
                omittedLines = omitted,
            )
        }

        /** The end of the output as one string, and whether its start was cut (so the first line may be a fragment). */
        private fun tailOf(state: TerminalState): Pair<String, Boolean> {
            val pieces = ArrayList<String>()
            var total = 0
            var cut = false
            for (part in state.content.asReversed()) {
                val text = when (part) {
                    is TerminalContentPartUnclassified -> part.value.value
                    is TerminalContentPartCommand -> commandText(part)
                    else -> continue
                }
                if (total + text.length > MAX_RAW_CHARS) {
                    var from = text.length - (MAX_RAW_CHARS - total)
                    if (from in 1 until text.length && text[from].isLowSurrogate()) from++
                    pieces += text.substring(from.coerceIn(0, text.length))
                    cut = true
                    break
                }
                pieces += text
                total += text.length
            }
            return pieces.asReversed().joinToString("") to cut
        }

        /** What a command shows: the line that was run, its output, and, once it has finished with a failure, how it ended. */
        private fun commandText(part: TerminalContentPartCommand): String {
            val command = part.value
            val body = StringBuilder("$ ").append(command.commandLine).append('\n').append(command.output)
            if (command.isComplete && command.exitCode != null && command.exitCode != 0L) {
                if (!body.endsWith("\n")) body.append('\n')
                body.append("[exit code ").append(command.exitCode).append("]\n")
            }
            return body.toString()
        }

        private fun plainLines(raw: String, dropFirst: Boolean): Pair<List<String>, Int> {
            val done = ArrayDeque<String>()
            var omitted = 0
            var line = ArrayList<String>()
            var cursor = 0
            var first = true
            var i = 0

            fun finishLine() {
                val text = line.joinToString("")
                if (first && dropFirst) {
                    first = false
                } else {
                    first = false
                    done.addLast(LineDiff.clean(text))
                    if (done.size > MAX_LINES) {
                        done.removeFirst()
                        omitted++
                    }
                }
                line = ArrayList()
                cursor = 0
            }

            while (i < raw.length) {
                val c = raw[i]
                when {
                    c == '\u001B' -> i = skipEscape(raw, i)
                    c == '\n' -> finishLine()
                    c == '\r' -> cursor = 0
                    c == '\b' -> if (cursor > 0) cursor--
                    c == '\t' -> {
                        val spaces = TAB - (cursor % TAB)
                        repeat(spaces) { cursor = put(line, cursor, " ") }
                    }
                    c.code < 0x20 || c.code in 0x7F..0x9F -> Unit
                    else -> {
                        val point = raw.codePointAt(i)
                        val cell = String(Character.toChars(point))
                        cursor = put(line, cursor, cell)
                        i += Character.charCount(point) - 1
                    }
                }
                i++
            }
            if (line.isNotEmpty()) finishLine()
            return done.toList() to omitted
        }

        /** Writes [cell] at [cursor]: over what is there, or at the end. A line is never longer than [LineDiff.MAX_LINE_CHARS] cells. */
        private fun put(line: ArrayList<String>, cursor: Int, cell: String): Int {
            if (cursor < line.size) {
                line[cursor] = cell
            } else if (line.size < LineDiff.MAX_LINE_CHARS) {
                line += cell
            } else {
                return cursor
            }
            return cursor + 1
        }

        /** The index of the last character of the escape sequence that starts at [start], so the caller moves on past all of it. */
        private fun skipEscape(raw: String, start: Int): Int {
            var i = start + 1
            if (i >= raw.length) return raw.length - 1
            when (raw[i]) {
                '[' -> {
                    i++
                    while (i < raw.length && raw[i].code !in 0x40..0x7E) i++
                    return minOf(i, raw.length - 1)
                }
                ']', 'P', 'X', '^', '_' -> {
                    i++
                    while (i < raw.length) {
                        if (raw[i] == '\u0007') return i
                        if (raw[i] == '\u001B' && i + 1 < raw.length && raw[i + 1] == '\\') return i + 1
                        i++
                    }
                    return raw.length - 1
                }
                else -> {
                    // A character set choice (ESC ( B) has intermediate characters before its final one; most others are one character.
                    while (i < raw.length && raw[i].code in 0x20..0x2F) i++
                    return minOf(i, raw.length - 1)
                }
            }
        }

    }
}
