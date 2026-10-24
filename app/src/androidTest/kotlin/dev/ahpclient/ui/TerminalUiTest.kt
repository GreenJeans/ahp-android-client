package dev.ahpclient.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ahpclient.state.ChannelGoneException
import dev.ahpclient.state.ConnState
import dev.ahpclient.ui.theme.AhpTheme
import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.TerminalState
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The live output of a terminal: what each state of loading says, how output reads, and that the end is followed until the person scrolls away. */
@RunWith(AndroidJUnit4::class)
class TerminalUiTest {
    @get:Rule
    val rule = createComposeRule()

    private fun state(output: String, lifecycle: String = """{"status":"running"}""", title: String = "build", cwd: String? = "file:///c%3A/work/repo"): TerminalState =
        Ahp.json.decodeFromString(
            TerminalState.serializer(),
            """{"title":${JsonPrimitive(title)},${cwd?.let { """"cwd":${JsonPrimitive(it)},""" }.orEmpty()}"content":[{"type":"unclassified","value":${JsonPrimitive(output)}}],
                "lifecycle":$lifecycle,"claim":{"kind":"client","clientId":"x"}}""",
        )

    private fun show(state: TerminalState?, error: Throwable? = null, connection: ConnState = ConnState.Online, onBack: () -> Unit = {}, height: Int = 800) =
        rule.setContent { AhpTheme { Box(Modifier.size(360.dp, height.dp)) { TerminalScreen(state, error, connection, onBack) } } }

    @Test
    fun theTerminalIsNamedItsStateSaidAndItsFolderShown() {
        show(state("hello\n"))

        rule.onNodeWithTag("terminal_title").assertTextEquals("build")
        rule.onNodeWithTag("terminal_status").assertTextEquals("Running")
        rule.onNodeWithTag("terminal_cwd").assertTextEquals("c:/work/repo")
        rule.onNodeWithTag("terminal_note").assertTextContains("Read only", substring = true)
    }

    @Test
    fun aFinishedTerminalSaysHowItEnded() {
        show(state("done\n", lifecycle = """{"status":"exited","exitCode":3}"""))

        rule.onNodeWithTag("terminal_status").assertTextEquals("Exited with code 3")
    }

    @Test
    fun outputIsShownAsPlainLinesWithEscapeSequencesLeftOut() {
        show(state("\u001B[31mred\u001B[0m line\nsecond\n"))

        val lines = rule.onAllNodesWithTag("terminal_line")
        lines.assertCountEquals(2)
        lines[0].assertTextEquals("red line")
        lines[1].assertTextEquals("second")
    }

    @Test
    fun anEmptyTerminalSaysSo() {
        show(state(""))

        rule.onNodeWithTag("terminal_empty").assertTextEquals("No output yet.")
    }

    @Test
    fun loadingIsSaidInWordsUntilTheHostHasSentTheTerminal() {
        show(null)

        rule.onNodeWithTag("terminal_loading").assertIsDisplayed()
        rule.onNodeWithTag("terminal_title").assertTextEquals("Terminal")
    }

    @Test
    fun aTerminalThePhoneCannotGetSaysWhy() {
        show(null, error = ChannelGoneException("ahp-terminal:/t"))
        rule.onNodeWithTag("terminal_gone").assertTextContains("no longer on the host", substring = true)
    }

    @Test
    fun aHostAnswerThatCannotBeReadIsSaid() {
        show(null, error = IllegalStateException("x"))
        rule.onNodeWithTag("terminal_failed").assertTextContains("could not be read", substring = true)
    }

    @Test
    fun backWorksAndIsBigEnough() {
        var back = 0
        show(state("x\n"), onBack = { back++ })

        rule.onNodeWithTag("back").assertTextEquals("Back to chat").performClick()

        assertEquals(1, back)
        val bounds = rule.onNodeWithTag("back").fetchSemanticsNode().touchBoundsInRoot
        assertTrue("touch target ${bounds.height} px", bounds.height + 0.5f >= with(rule.density) { 48.dp.toPx() })
    }

    @Test
    fun aDroppedConnectionIsSaidAboveTheOutputThatStaysVisible() {
        show(state("kept\n"), connection = ConnState.Reconnecting(1, null))

        rule.onNodeWithTag("terminal_line").assertTextEquals("kept")
        rule.onNodeWithTag("terminal_title").assertIsDisplayed()
    }

    @Test
    fun lineThatWereLeftOutAreSaid() {
        val many = (1..2_050).joinToString("") { "line $it\n" }
        show(state(many))

        rule.onNodeWithTag("terminal_omitted").assertTextContains("The first 50 lines are not shown.", substring = true)
    }

    @Test
    fun theEndFollowsNewOutputWhileTheListIsAtTheEnd() {
        val many = (1..300).joinToString("") { "line $it\n" }
        show(state(many))
        rule.waitForIdle()

        rule.onNode(hasText("line 300")).assertIsDisplayed()
        rule.onNodeWithTag("terminal_latest").assertDoesNotExist()
    }

    @Test
    fun theEndKeepsFollowingAsMoreOutputArrives() {
        var current by mutableStateOf(state((1..300).joinToString("") { "line $it\n" }))
        rule.setContent { AhpTheme { Box(Modifier.size(360.dp, 800.dp)) { TerminalScreen(current, null, ConnState.Online, {}) } } }
        rule.waitForIdle()
        rule.onNode(hasText("line 300")).assertIsDisplayed()

        current = state((1..320).joinToString("") { "line $it\n" })
        rule.waitForIdle()

        rule.onNode(hasText("line 320")).assertIsDisplayed()
        rule.onNodeWithTag("terminal_latest").assertDoesNotExist()
    }

    @Test
    fun outputThatArrivesWhileScrolledAwayDoesNotPullTheListBack() {
        var current by mutableStateOf(state((1..300).joinToString("") { "line $it\n" }))
        rule.setContent { AhpTheme { Box(Modifier.size(360.dp, 800.dp)) { TerminalScreen(current, null, ConnState.Online, {}) } } }
        rule.waitForIdle()
        rule.onNodeWithTag("terminal_list").performTouchInput { repeat(3) { swipeDown() } }
        rule.waitForIdle()

        current = state((1..320).joinToString("") { "line $it\n" })
        rule.waitForIdle()

        rule.onNodeWithTag("terminal_latest").assertIsDisplayed()
        rule.onAllNodes(hasText("line 320")).assertCountEquals(0)
    }

    @Test
    fun scrollingAwayOffersLatestAndItBringsTheEndBack() {
        val many = (1..300).joinToString("") { "line $it\n" }
        show(state(many))
        rule.waitForIdle()

        // A swipe is the person scrolling; a programmatic jump is not, and must not count as leaving the end.
        rule.onNodeWithTag("terminal_list").performTouchInput { repeat(3) { swipeDown() } }
        rule.waitForIdle()
        rule.onNodeWithTag("terminal_latest").assertIsDisplayed().performClick()
        rule.waitForIdle()

        rule.onNode(hasText("line 300")).assertIsDisplayed()
        rule.onNodeWithTag("terminal_latest").assertDoesNotExist()
    }
}
