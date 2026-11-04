package dev.ahpclient.state

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.TerminalState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** The live tail a running tool call's block shows of its terminal (U7): the end of the terminal screen's own lines. */
class LiveTerminalTest {
    private fun state(output: String): TerminalState = Ahp.json.decodeFromString(
        TerminalState.serializer(),
        terminalStateJson(content = """{"type":"unclassified","value":${JsonPrimitive(output)}}"""),
    )

    @Test
    fun `the tail is the last lines, in order`() {
        val output = (1..30).joinToString("") { "line $it\r\n" }

        assertEquals((19..30).map { "line $it" }, LiveTerminal.tail(state(output)))
        assertEquals(listOf("line 29", "line 30"), LiveTerminal.tail(state(output), max = 2))
    }

    @Test
    fun `fewer lines than the tail holds are all shown, and no output is no lines`() {
        assertEquals(listOf("one", "two"), LiveTerminal.tail(state("one\ntwo\n")))
        assertEquals(emptyList(), LiveTerminal.tail(state("")))
        assertEquals(emptyList(), LiveTerminal.tail(state("one\n"), max = 0))
    }

    @Test
    fun `blank lines at the end are left out, so the tail does not look stalled, but blank lines between are kept`() {
        assertEquals(listOf("one", "", "two"), LiveTerminal.tail(state("one\n\ntwo\n\n\n   \n")))
    }

    @Test
    fun `the tail is plain text, as the terminal screen shows it`() {
        assertEquals(listOf("red done"), LiveTerminal.tail(state("\u001B[31mred\u001B[0m 50%\rred done\n")))
    }

    @Test
    fun `a command run in the terminal shows its command line and output`() {
        val command = """{"type":"command","commandId":"c1","commandLine":"npm test","output":"ok 1\nok 2\n","timestamp":1,"isComplete":false}"""
        val terminal = Ahp.json.decodeFromString(TerminalState.serializer(), terminalStateJson(content = command))

        assertEquals(listOf("$ npm test", "ok 1", "ok 2"), LiveTerminal.tail(terminal))
    }

    @Test
    fun `closing lets go once, however often it is called`() {
        var closes = 0
        val live = LiveTerminal(MutableStateFlow(null)) { closes++ }

        live.close()
        live.close()

        assertEquals(1, closes)
    }
}
