package dev.ahpclient.ui

import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * System Back for the screens over a session ([OverlayBackHandlers]): the one on top closes and nothing else does. The app's own
 * wiring of it in `AppRoot` needs a live controller, so the screens are held here and each close is counted. Back is the
 * activity's own dispatcher, as the system's Back is.
 */
@RunWith(AndroidJUnit4::class)
class OverlayBackTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val screens = mutableStateOf(OverlayScreens())
    private val closed = mutableListOf<String>()

    private fun show(initial: OverlayScreens) {
        screens.value = initial
        closed.clear()
        rule.setContent {
            OverlayBackHandlers(
                screens = screens.value,
                onCloseChat = { closed += "chat" },
                onCloseCanvas = { closed += "canvas" },
                onCloseTerminal = { closed += "terminal" },
                onCloseChangeset = { closed += "changeset" },
                onCloseDiff = { closed += "diff" },
            )
        }
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test
    fun backOnTheCanvasScreenClosesTheCanvas() {
        show(OverlayScreens(canvas = true))

        back()

        assertEquals(listOf("canvas"), closed)
    }

    @Test
    fun backOnACanvasOpenedFromASubagentClosesTheCanvasAndNotTheSubagent() {
        show(OverlayScreens(subagent = true, canvas = true))

        back()

        assertEquals(listOf("canvas"), closed)
    }

    @Test
    fun backOnATerminalOpenedFromASubagentClosesTheTerminalAndNotTheSubagent() {
        show(OverlayScreens(subagent = true, terminal = true))

        back()

        assertEquals(listOf("terminal"), closed)
    }

    @Test
    fun backOnASubagentWithNothingOverItClosesTheSubagent() {
        show(OverlayScreens(subagent = true))

        back()

        assertEquals(listOf("chat"), closed)
    }

    @Test
    fun backOnAChangesetClosesIt() {
        show(OverlayScreens(changeset = true))

        back()

        assertEquals(listOf("changeset"), closed)
    }

    @Test
    fun backOnAFileDiffClosesTheDiffAndNotTheChangesetUnderIt() {
        show(OverlayScreens(changeset = true, diff = true))

        back()

        assertEquals(listOf("diff"), closed)
    }

    @Test
    fun backWithNoScreenOverTheSessionIsLeftToTheRest() {
        var unhandled = 0
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.addCallback(rule.activity) { unhandled++ } }
        show(OverlayScreens())

        back()

        assertEquals(emptyList<String>(), closed)
        assertEquals(1, unhandled)
    }

    @Test
    fun backClosesEachScreenInTurnAsTheyAreClosed() {
        show(OverlayScreens(subagent = true, changeset = true, diff = true))

        back()
        screens.value = OverlayScreens(subagent = true, changeset = true)
        rule.waitForIdle()
        back()
        screens.value = OverlayScreens(subagent = true)
        rule.waitForIdle()
        back()

        assertEquals(listOf("diff", "changeset", "chat"), closed)
    }
}
