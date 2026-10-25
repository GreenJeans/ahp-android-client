package dev.ahpclient.ui.chat

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ahpclient.state.ActivityState
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.ToolState
import dev.ahpclient.state.Transcript
import dev.ahpclient.state.TranscriptItem
import dev.ahpclient.ui.theme.AhpTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The agent's work between two messages is one folded card per run of steps, and the bubble for what the person said. */
@RunWith(AndroidJUnit4::class)
class StepsRowTest {
    @get:Rule
    val rule = createComposeRule()

    private fun tool(n: Int, name: String = "Read file", summary: String? = "a$n.txt", state: ToolState = ToolState.DONE) =
        TranscriptItem.Tool("t$n", name, summary, state)

    private fun show(vararg items: TranscriptItem, expanded: Set<String> = emptySet(), onToggle: (String) -> Unit = {}) = rule.setContent {
        AhpTheme {
            ChatScreen(
                Transcript("Fix the build", ActivityState.IN_PROGRESS, null, items.toList(), hasOlder = false),
                null, false, null, ConnState.Online, {}, {}, {}, expanded = expanded, onToggle = onToggle,
            )
        }
    }

    private fun title() = rule.onNodeWithTag("steps_title", useUnmergedTree = true)

    private fun detail() = rule.onNodeWithTag("steps_detail", useUnmergedTree = true)

    @Test
    fun aRunOfStepsIsOneLineThatCountsThemAndHidesThem() {
        show(tool(1), tool(2, "Search", "foo"), tool(3))

        title().assertTextEquals("Used 3 tools")
        detail().assertTextEquals("Read file, Search")
        rule.onAllNodesWithTag("row_tool").assertCountEquals(0)
        rule.onAllNodesWithTag("steps_body").assertCountEquals(0)
    }

    @Test
    fun aLoneStepStaysAPlainRowWithNothingToFoldInto() {
        show(tool(1))

        rule.onAllNodesWithTag("row_steps").assertCountEquals(0)
        rule.onNodeWithTag("row_tool").assertIsDisplayed()
    }

    @Test
    fun theOpenStateOfARunComesFromItsKeyAndShowsEveryStep() {
        show(tool(1), tool(2), expanded = setOf("steps:t1"))

        rule.waitUntil(5_000) { rule.onAllNodesWithTag("row_tool").fetchSemanticsNodes().size == 2 }
        rule.onNodeWithTag("steps_body").assertIsDisplayed()
    }

    @Test
    fun tappingTheHeaderAsksForTheRunsKeyToBeToggled() {
        val toggled = mutableListOf<String>()
        show(tool(1), tool(2), onToggle = toggled::add)

        rule.onNodeWithTag("steps_header").performClick()

        assertEquals(listOf("steps:t1"), toggled)
    }

    @Test
    fun theHeaderIsAButtonThatSaysWhetherItIsOpen() {
        show(tool(1), tool(2))
        rule.onNodeWithTag("steps_header").assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
    }

    @Test
    fun anOpenRunSaysItIsOpen() {
        show(tool(1), tool(2), expanded = setOf("steps:t1"))

        rule.onNodeWithTag("steps_header").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
    }

    @Test
    fun theHeaderIsAtLeastAsTallAsATouchTarget() {
        show(tool(1), tool(2))

        rule.onNodeWithTag("steps_header").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun aRunStillAtWorkSaysSoAndNamesTheStepInProgress() {
        show(tool(1), tool(2, "Run in Terminal", "dotnet build", ToolState.RUNNING))

        title().assertTextEquals("Working…")
        detail().assertTextEquals("Run in Terminal: dotnet build")
    }

    @Test
    fun aRunWithAFailedStepSaysHowManyFailed() {
        show(tool(1), tool(2, "Run in Terminal", "dotnet build", ToolState.FAILED))

        detail().assertTextEquals("Read file, Run in Terminal · 1 failed")
    }

    @Test
    fun aRunWithAStepWaitingOnThePersonIsOpenAndCannotBeFoldedOverIt() {
        val toggled = mutableListOf<String>()
        show(tool(1), tool(2, "Run in Terminal", "dotnet test", ToolState.WAITING_FOR_APPROVAL), onToggle = toggled::add)

        title().assertTextEquals("Waiting on you")
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("steps_body").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("steps_header").assertHasNoClickAction()
        assertEquals(emptyList<String>(), toggled)
    }

    @Test
    fun whatThePersonSaidIsABubbleThatAScreenReaderCallsTheirs() {
        show(TranscriptItem.User("u1", "hello there"))

        rule.onNodeWithTag("row_user").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "You"))
    }
}
