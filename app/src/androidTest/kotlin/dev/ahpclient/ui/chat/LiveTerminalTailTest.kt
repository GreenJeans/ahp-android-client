package dev.ahpclient.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.TerminalState
import com.microsoft.agenthostprotocol.generated.ToolCallState
import dev.ahpclient.state.ActivityState
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.LiveTerminal
import dev.ahpclient.state.ToolState
import dev.ahpclient.state.Transcript
import dev.ahpclient.state.TranscriptItem
import dev.ahpclient.ui.theme.AhpTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A running command's terminal, tailed live in its tool call's block (U7): shown and updated as the terminal streams, watched only
 * while the block is open and the call runs, and let go of when either stops. The provider here is a fake that records each watch.
 */
@RunWith(AndroidJUnit4::class)
class LiveTerminalTailTest {
    @get:Rule
    val rule = createComposeRule()

    private val uri = "ahp-terminal:/t1"
    private val common = """"toolCallId":"c1","toolName":"run_in_terminal","displayName":"Run in Terminal""""
    private val terminalContent = """{"type":"terminal","resource":${JsonPrimitive(uri)},"title":"Build","isPty":false}"""

    private fun call(json: String): ToolCallState = Ahp.json.decodeFromString(ToolCallState.serializer(), "{$json}")

    private fun running(content: String = terminalContent) =
        call(""""status":"running",$common,"invocationMessage":"Run it","toolInput":"{}","confirmed":"not-needed","content":[$content]""")

    private fun completed() = call(
        """"status":"completed",$common,"invocationMessage":"Run it","toolInput":"{}","confirmed":"not-needed","success":true,"pastTenseMessage":"Ran it",""" +
            """"content":[{"type":"terminal","resource":${JsonPrimitive(uri)},"title":"Build","isPty":false,"result":{"exitCode":0,"preview":"all done"}}]""",
    )

    private fun transcriptOf(source: ToolCallState, state: ToolState) =
        Transcript("Fix the build", ActivityState.IN_PROGRESS, null, listOf(TranscriptItem.Tool("t1", "Run in Terminal", "npm test", state, source)), hasOlder = false)

    private fun terminal(output: String): TerminalState = Ahp.json.decodeFromString(
        TerminalState.serializer(),
        """{"title":"build","content":[{"type":"unclassified","value":${JsonPrimitive(output)}}],"lifecycle":{"status":"running"},"claim":{"kind":"client","clientId":"x"}}""",
    )

    /** Hands out one live terminal per watch, and records what was watched and what was let go of. */
    private class FakeWatcher(initial: TerminalState?, private val refuse: Boolean = false) {
        val flow = MutableStateFlow(initial)
        val watched = mutableListOf<String>()
        val closed = mutableListOf<String>()

        fun watch(uri: String): LiveTerminal? {
            watched += uri
            return if (refuse) null else LiveTerminal(flow) { closed += uri }
        }
    }

    private var transcript by mutableStateOf<Transcript?>(null)
    private var expanded by mutableStateOf(setOf("t1"))

    private fun show(watcher: FakeWatcher, start: Transcript, opened: MutableList<String> = mutableListOf()) {
        transcript = start
        rule.setContent {
            AhpTheme {
                ChatScreen(
                    transcript, null, false, null, ConnState.Online, {}, {}, {},
                    expanded = expanded, onOpenTerminal = { opened += it }, liveTerminal = watcher::watch,
                )
            }
        }
        rule.waitForIdle()
    }

    private fun tail() = rule.onNodeWithTag("terminal_live_tail", useUnmergedTree = true)

    /** The live block itself or anything inside it. */
    private val inTail = hasTestTag("terminal_live") or hasAnyAncestor(hasTestTag("terminal_live"))

    /** The tail's text, which sits in a child of the tagged block, inside the result's merged words. */
    private fun tailText(text: String) = rule.onNode(hasAnyAncestor(hasTestTag("terminal_live_tail")) and hasText(text, substring = true), useUnmergedTree = true)

    @Test
    fun aRunningCommandsTerminalIsTailedLive_andTheTailUpdatesAsOutputArrives() {
        val watcher = FakeWatcher(terminal("hello\n"))
        val opened = mutableListOf<String>()
        show(watcher, transcriptOf(running(), ToolState.RUNNING), opened)

        tailText("hello").assertExists()
        // Decided: no "Live" label over the tail, and no live region in it, so TalkBack stays quiet as lines arrive.
        rule.onNode(hasText("Live"), useUnmergedTree = true).assertDoesNotExist()
        rule.onAllNodes(inTail and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion), useUnmergedTree = true).assertCountEquals(0)
        assertEquals(listOf(uri), watcher.watched)

        watcher.flow.value = terminal("hello\nmore output\n")
        rule.waitForIdle()

        tailText("more output").assertExists()
        assertEquals("a new line does not watch again", listOf(uri), watcher.watched)
        rule.onNodeWithTag("terminal_open").assertExists()
        assertTrue("watching opens nothing", opened.isEmpty())
    }

    @Test
    fun onlyTheLastLinesAreShown() {
        val watcher = FakeWatcher(terminal((1..30).joinToString("") { "line $it\n" }))
        show(watcher, transcriptOf(running(), ToolState.RUNNING))

        tailText("line 30").assertExists()
        tailText("line ${31 - LiveTerminal.TAIL_LINES}\n").assertExists()
        tailText("line ${30 - LiveTerminal.TAIL_LINES}\n").assertDoesNotExist()
    }

    @Test
    fun aFoldedBlockWatchesNothing_andFoldingItLetsGo() {
        val watcher = FakeWatcher(terminal("hello\n"))
        expanded = emptySet()
        show(watcher, transcriptOf(running(), ToolState.RUNNING))

        tail().assertDoesNotExist()
        assertTrue("a folded block subscribes nothing", watcher.watched.isEmpty())

        expanded = setOf("t1")
        rule.waitForIdle()
        assertEquals(listOf(uri), watcher.watched)

        expanded = emptySet()
        rule.waitForIdle()
        assertEquals(listOf(uri), watcher.closed)
    }

    @Test
    fun aCompletedCallShowsItsResult_andNoLiveTail() {
        val watcher = FakeWatcher(terminal("hello\n"))
        show(watcher, transcriptOf(completed(), ToolState.DONE))

        rule.onNodeWithTag("terminal_live", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("result_terminal").assertTextContains("all done", substring = true)
        assertTrue(watcher.watched.isEmpty())
    }

    @Test
    fun whenTheCallCompletesTheTailGoesAndTheTerminalIsLetGo() {
        val watcher = FakeWatcher(terminal("hello\n"))
        show(watcher, transcriptOf(running(), ToolState.RUNNING))
        tail().assertExists()

        transcript = transcriptOf(completed(), ToolState.DONE)
        rule.waitForIdle()

        tail().assertDoesNotExist()
        rule.onNodeWithTag("result_terminal").assertTextContains("all done", substring = true)
        assertEquals(listOf(uri), watcher.closed)
    }

    @Test
    fun aTerminalThatCannotBeWatchedSaysThereIsNoOutputYet() {
        val watcher = FakeWatcher(null, refuse = true)
        show(watcher, transcriptOf(running(), ToolState.RUNNING))

        tail().assertDoesNotExist()
        rule.onNodeWithTag("result_terminal").assertTextContains("No output yet.", substring = true)
    }

    @Test
    fun anAddressThatIsNotATerminalsIsNeverWatched() {
        val watcher = FakeWatcher(terminal("hello\n"))
        show(watcher, transcriptOf(running("""{"type":"terminal","resource":"https://example.test/x","title":"Build","isPty":false}"""), ToolState.RUNNING))

        rule.onNodeWithTag("terminal_live", useUnmergedTree = true).assertDoesNotExist()
        assertTrue(watcher.watched.isEmpty())
    }
}
