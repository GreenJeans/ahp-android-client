package dev.ahpclient.state

import com.microsoft.agenthostprotocol.generated.TerminalState
import dev.ahpclient.platform.AtomicBoolean
import kotlinx.coroutines.flow.StateFlow

/**
 * A terminal watched from a running tool call's block in the chat, read only (U7). A tool result only names its terminal and
 * carries the output once the command ends, so without this the chat showed nothing until then and the whole output at once.
 * [state] is the terminal as the host streams it, null until its snapshot arrives. [close] gives up the subscription; it does so
 * once however often it is called, so a block torn down twice never takes away another screen's hold on the same terminal.
 */
class LiveTerminal(val state: StateFlow<TerminalState?>, private val onClose: () -> Unit) {
    private val closed = AtomicBoolean(false)

    fun close() {
        if (closed.compareAndSet(false, true)) onClose()
    }

    companion object {
        /** About what fits in a block without pushing the rest of the chat off a phone's screen. */
        const val TAIL_LINES = 12

        /**
         * The last [max] lines of [state] as the terminal screen shows them. Blank lines at the end are left out: a command
         * that has just printed a newline would otherwise show an empty last line, and the tail would look stalled.
         */
        fun tail(state: TerminalState, max: Int = TAIL_LINES): List<String> {
            if (max <= 0) return emptyList()
            return TerminalView.from(state).lines.dropLastWhile { it.isBlank() }.takeLast(max)
        }
    }
}
