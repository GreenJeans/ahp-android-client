package dev.ahpclient.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.unit.Dp
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ahpclient.state.AutomationRow
import dev.ahpclient.state.AutomationRunRow
import dev.ahpclient.ui.theme.AhpTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The host's automations (P7): what each shows, the switch and the cancel button only on the manage tier, and a run opening its session. */
@RunWith(AndroidJUnit4::class)
class AutomationsSheetTest {
    @get:Rule
    val rule = createComposeRule()

    private val running = AutomationRunRow("ahp-automation-run:/r2", "Running", "59m", "ahp-session:/s2", canCancel = true)
    private val done = AutomationRunRow("ahp-automation-run:/r1", "Done", "23h", "ahp-session:/s1", canCancel = false)
    private val digest = AutomationRow("ahp-automation:/a1", "Morning digest", "Weekdays at 06:00 (America/Los_Angeles)", enabled = true, canToggle = true, nextRun = "Next run in 1d", runs = listOf(running, done))
    private val manual = AutomationRow("ahp-automation:/a2", "Context snapshot", "Manual only", enabled = false, canToggle = false, nextRun = null, runs = emptyList())

    private val toggled = mutableListOf<Pair<String, Boolean>>()
    private val cancelled = mutableListOf<String>()
    private val opened = mutableListOf<String>()

    private fun SemanticsNodeInteraction.assertTouchTargetAtLeast(minimum: Dp): SemanticsNodeInteraction {
        val bounds = fetchSemanticsNode().touchBoundsInRoot
        val minimumPx = with(rule.density) { minimum.toPx() }
        assertTrue("touch target is ${bounds.width} x ${bounds.height} px, smaller than $minimum ($minimumPx px)", bounds.height + 0.5f >= minimumPx && bounds.width + 0.5f >= minimumPx)
        return this
    }

    private fun show(rows: List<AutomationRow>?, canManage: Boolean) = rule.setContent {
        AhpTheme {
            AutomationsSheet(
                rows = rows,
                canManage = canManage,
                onToggle = { r, on -> toggled += r to on },
                onCancelRun = { cancelled += it },
                onOpenSession = { opened += it },
                onDismiss = {},
            )
        }
    }

    @Test
    fun eachAutomationShowsWhatSetsItOffWhetherItIsOnAndItsRuns() {
        show(listOf(digest, manual), canManage = false)

        rule.onNodeWithText("Morning digest").assertIsDisplayed()
        rule.onNodeWithText("Weekdays at 06:00 (America/Los_Angeles)").assertIsDisplayed()
        rule.onNodeWithText("On · Next run in 1d").assertIsDisplayed()
        rule.onNodeWithText("Manual only").assertIsDisplayed()
        rule.onNodeWithText("Off").assertIsDisplayed()
        rule.onAllNodesWithTag("automation_run").assertCountEquals(2)
    }

    @Test
    fun withoutTheManageTierThereIsNoSwitchAndNoCancel_butARunStillOpensItsSession() {
        show(listOf(digest), canManage = false)

        rule.onAllNodesWithTag("automation_switch", useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithTag("automation_run_cancel").assertCountEquals(0)
        rule.onAllNodesWithTag("automation_header").onFirst().performClick()
        assertEquals(emptyList<Pair<String, Boolean>>(), toggled)

        rule.onAllNodesWithTag("automation_run").onFirst().performClick()
        assertEquals(listOf("ahp-session:/s2"), opened)
    }

    @Test
    fun onTheManageTierTheSwitchTurnsAnAutomationOffAndARunningRunCanBeCancelled() {
        show(listOf(digest, manual), canManage = true)

        rule.onAllNodesWithTag("automation_switch", useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodesWithTag("automation_header").onFirst().assertIsOn().performClick()
        assertEquals(listOf("ahp-automation:/a1" to false), toggled)

        rule.onAllNodesWithTag("automation_run_cancel").assertCountEquals(1)
        rule.onNodeWithTag("automation_run_cancel").assertTouchTargetAtLeast(48.dp).performClick()
        assertEquals(listOf("ahp-automation-run:/r2"), cancelled)
    }

    @Test
    fun anAutomationTheHostDoesNotLetBeChangedHasNoSwitch() {
        show(listOf(manual), canManage = true)

        rule.onAllNodesWithTag("automation_switch", useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithTag("automation_header").onFirst().performClick()
        assertEquals(emptyList<Pair<String, Boolean>>(), toggled)
    }

    @Test
    fun theSheetSaysWhileTheCatalogueLoadsAndWhenItIsEmpty() {
        show(null, canManage = true)
        rule.onNodeWithTag("automations_loading").assertIsDisplayed()
    }

    @Test
    fun anEmptyCatalogueSaysSo() {
        show(emptyList(), canManage = true)
        rule.onNodeWithTag("automations_empty").assertIsDisplayed()
    }
}
