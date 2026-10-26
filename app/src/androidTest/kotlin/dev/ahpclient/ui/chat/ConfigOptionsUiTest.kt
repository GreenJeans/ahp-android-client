package dev.ahpclient.ui.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ahpclient.state.ActivityState
import dev.ahpclient.state.AgentChoice
import dev.ahpclient.state.AgentPick
import dev.ahpclient.state.ChatConfigView
import dev.ahpclient.state.ConfigChoice
import dev.ahpclient.state.ConfigControl
import dev.ahpclient.state.ConfigKind
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.DispatchOutcome
import dev.ahpclient.state.InteractResult
import dev.ahpclient.state.Transcript
import dev.ahpclient.ui.theme.AhpTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The mode and approvals of a chat in the message box: the chips that point out what is not at its default, and the options pop-up. */
@RunWith(AndroidJUnit4::class)
class ConfigOptionsUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val done = InteractResult.Sent(DispatchOutcome.Accepted)

    private val plan = AgentChoice("file:///a/plan.agent.md", "plan", "Plan first, then act")
    private val review = AgentChoice("file:///a/review.agent.md", "review", null)

    private class Calls {
        val set = mutableListOf<Pair<String, String>>()
        val sentAgents = mutableListOf<AgentPick>()
        var result: InteractResult = InteractResult.Sent(DispatchOutcome.Accepted)
        var setResults: MutableList<InteractResult> = mutableListOf()
    }

    private fun mode(current: String? = "interactive", writable: Boolean = true) = ConfigControl(
        ConfigKind.MODE, "mode",
        listOf(
            ConfigChoice("interactive", "Interactive", "Step-by-step collaboration", true),
            ConfigChoice("plan", "Plan", "Plan first, execute when ready", true),
            ConfigChoice("autopilot", "Autopilot", "Works autonomously within permissions", true),
        ),
        current, writable,
    )

    private fun approvals(current: String? = "default", writable: Boolean = true, withheld: Set<String> = emptySet()) = ConfigControl(
        ConfigKind.APPROVALS, "autoApprove",
        listOf(
            ConfigChoice("default", "Manual", "Asks when approval settings don't apply", "default" !in withheld),
            ConfigChoice("assisted", "Assisted", "Evaluates risk before running tools", "assisted" !in withheld),
            ConfigChoice("autoApprove", "Allow all", "Runs tool calls without asking", "autoApprove" !in withheld),
        ),
        current, writable,
    )

    private fun view(mode: ConfigControl? = mode(), approvals: ConfigControl? = approvals()) = ChatConfigView(mode, approvals)

    private fun actions(calls: Calls, config: ChatConfigView = view(), agents: List<AgentChoice> = emptyList(), agentInUse: String? = null, running: Boolean = false) = ChatActionsUi(
        running = running,
        send = { _, _, agent -> calls.sentAgents += agent; done },
        steer = { _, _, _ -> done },
        cancel = { done },
        approve = { _, _ -> done },
        deny = { _, _ -> done },
        approveResult = { done },
        denyResult = { done },
        answer = { _, _ -> done },
        saveDraft = { _, _, _ -> done },
        decline = { done },
        dismiss = { done },
        agents = agents,
        agentInUse = agentInUse,
        config = config,
        setConfig = { key, value ->
            calls.set += key to value
            if (calls.setResults.isNotEmpty()) calls.setResults.removeAt(0) else calls.result
        },
    )

    private fun show(actions: ChatActionsUi) = rule.setContent {
        AhpTheme { ChatScreen(Transcript("Fix the build", ActivityState.IDLE, null, emptyList(), hasOlder = false), null, false, null, ConnState.Online, {}, {}, {}, actions = actions) }
    }

    private fun open() {
        rule.onNodeWithTag("agent_button").performClick()
        rule.waitForIdle()
    }

    private fun use() {
        rule.onNodeWithTag("agent_use").performClick()
        rule.waitForIdle()
    }

    // ---- the chips ---------------------------------------------------------------------------------------------

    @Test
    fun atTheQuietDefaultsNothingIsPointedOut() {
        show(actions(Calls()))

        rule.onAllNodesWithTag("config_state_mode").assertCountEquals(0)
        rule.onAllNodesWithTag("config_state_approvals").assertCountEquals(0)
    }

    @Test
    fun planIsPointedOutAsAChipThatSaysSo() {
        show(actions(Calls(), view(mode = mode("plan"))))

        rule.onNodeWithTag("config_state_mode").assertIsDisplayed().assertTextContains("Plan").assertContentDescriptionEquals("Mode: Plan. Opens the options")
        rule.onAllNodesWithTag("config_state_approvals").assertCountEquals(0)
    }

    @Test
    fun allowAllIsPointedOutInWordsThatSayWhatItMeans() {
        show(actions(Calls(), view(approvals = approvals("autoApprove"))))

        rule.onNodeWithTag("config_state_approvals").assertIsDisplayed().assertTextContains("Allow all")
            .assertContentDescriptionEquals("Approvals: Allow all. Tool calls run without asking. Opens the options")
    }

    @Test
    fun bothSettingsAreShownTogetherAndTheBoxStillFits() {
        show(actions(Calls(), view(mode = mode("autopilot"), approvals = approvals("autoApprove")), agents = listOf(plan, review)))

        rule.onNodeWithTag("config_state_mode").assertIsDisplayed()
        rule.onNodeWithTag("config_state_approvals").assertIsDisplayed()
        rule.onNodeWithTag("composer_input").assertIsDisplayed()
        rule.onNodeWithTag("agent_button").assertIsDisplayed()
    }

    @Test
    fun aSettingTheHostGivesNoKnownValueForPointsNothingOut() {
        show(actions(Calls(), view(mode = mode(null), approvals = approvals(null))))

        rule.onAllNodesWithTag("config_state_mode").assertCountEquals(0)
        rule.onAllNodesWithTag("config_state_approvals").assertCountEquals(0)
    }

    @Test
    fun tappingAChipOpensTheOptions() {
        show(actions(Calls(), view(approvals = approvals("autoApprove"))))

        rule.onNodeWithTag("config_state_approvals").performClick()

        rule.onNodeWithTag("options_dialog").assertIsDisplayed()
    }

    // ---- the options -------------------------------------------------------------------------------------------

    @Test
    fun withOnlyTheSettingsOfferedTheAgentChipIsThereAndTheDialogHasNoAgentList() {
        show(actions(Calls()))

        rule.onNodeWithTag("agent_button").assertTextContains("Default agent")
        open()

        rule.onNodeWithTag("options_dialog").assertIsDisplayed()
        rule.onNodeWithText("Options").assertIsDisplayed()
        rule.onAllNodesWithTag("agent_dialog").assertCountEquals(0)
        rule.onNodeWithTag("config_section_mode").assertIsDisplayed()
        rule.onNodeWithTag("config_section_approvals").assertIsDisplayed()
    }

    @Test
    fun withNoSettingsOfferedTheDialogIsTheAgentPickerAsBefore() {
        show(actions(Calls(), ChatConfigView.NONE, agents = listOf(plan, review)))

        open()

        rule.onNodeWithText("Agent").assertIsDisplayed()
        rule.onAllNodesWithTag("config_section_mode").assertCountEquals(0)
        rule.onAllNodesWithTag("config_section_approvals").assertCountEquals(0)
        rule.onNodeWithTag("agent_dialog").assertIsDisplayed()
    }

    @Test
    fun withNeitherSettingsNorAgentsThereIsNoChip() {
        show(actions(Calls(), ChatConfigView.NONE))

        rule.onAllNodesWithTag("agent_button").assertCountEquals(0)
    }

    @Test
    fun theDialogShowsEveryChoiceWithTheOneInForceSelected() {
        show(actions(Calls(), view(mode = mode("plan"), approvals = approvals("assisted")), agents = listOf(plan, review)))
        open()

        rule.onNodeWithTag("config_mode_plan").assertIsSelected().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("config_mode_interactive").assertIsNotSelected()
        rule.onNodeWithTag("config_mode_autopilot").assertIsNotSelected()
        rule.onNodeWithTag("config_approvals_assisted").assertIsSelected().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("config_approvals_default").assertIsNotSelected()
        rule.onNodeWithTag("config_approvals_autoApprove").assertIsNotSelected().assertTextContains("Allow all")
        rule.onNodeWithTag("agent_dialog").assertIsDisplayed()
    }

    @Test
    fun usingAChoiceSendsExactlyThatChange() {
        val calls = Calls()
        show(actions(calls))
        open()

        rule.onNodeWithTag("config_mode_plan").performClick()
        use()

        assertEquals(listOf("mode" to "plan"), calls.set)
    }

    @Test
    fun usingTheDialogWithNothingChangedSendsNothing() {
        val calls = Calls()
        show(actions(calls, view(mode = mode("plan"))))
        open()

        use()

        assertTrue(calls.set.isEmpty())
    }

    @Test
    fun changingBackToWhatIsInForceSendsNothing() {
        val calls = Calls()
        show(actions(calls))
        open()

        rule.onNodeWithTag("config_mode_plan").performClick()
        rule.onNodeWithTag("config_mode_interactive").performClick()
        use()

        assertTrue(calls.set.isEmpty())
    }

    @Test
    fun cancelChangesNothing() {
        val calls = Calls()
        show(actions(calls))
        open()

        rule.onNodeWithTag("config_mode_plan").performClick()
        rule.onNodeWithTag("agent_cancel").performClick()

        assertTrue(calls.set.isEmpty())
        rule.onAllNodesWithTag("options_dialog").assertCountEquals(0)
    }

    @Test
    fun bothChangesAreSentOneAfterTheOtherModeFirst() {
        val calls = Calls()
        show(actions(calls))
        open()

        rule.onNodeWithTag("config_approvals_assisted").performClick()
        rule.onNodeWithTag("config_mode_autopilot").performClick()
        use()

        assertEquals(listOf("mode" to "autopilot", "autoApprove" to "assisted"), calls.set)
    }

    @Test
    fun aChoiceThatGivesMoreFreedomSaysItWillAskForTheDeviceCheck() {
        show(actions(Calls()))
        open()

        rule.onNodeWithTag("config_approvals_autoApprove").performClick()

        rule.onNodeWithTag("config_hint_approvals").assertTextContains("Allow all: tool calls run without asking", substring = true)
            .assertTextContains("fingerprint, face or screen lock", substring = true)
        rule.onNodeWithTag("config_mode_autopilot").performClick()
        rule.onNodeWithTag("config_hint_mode").assertTextContains("Autopilot", substring = true).assertTextContains("fingerprint", substring = true)
    }

    @Test
    fun aChoiceThatTakesFreedomAwayDoesNotMentionTheDeviceCheck() {
        show(actions(Calls(), view(approvals = approvals("autoApprove"))))
        open()

        rule.onNodeWithTag("config_approvals_default").performClick()

        rule.onAllNodesWithText("fingerprint", substring = true).assertCountEquals(0)
        rule.onNodeWithTag("config_hint_approvals").assertTextEquals("Asks when approval settings don't apply")
    }

    @Test
    fun aLevelTheHostWithholdsCannotBeChosenAndTheDialogSaysWhy() {
        val calls = Calls()
        show(actions(calls, view(approvals = approvals("default", withheld = setOf("autoApprove")))))
        open()

        rule.onNodeWithTag("config_approvals_autoApprove").assertIsNotEnabled()
        rule.onNodeWithTag("config_approvals_assisted").assertIsEnabled()
        rule.onNodeWithTag("config_hint_approvals").assertTextContains("not allowed by the host right now", substring = true)
        rule.onNodeWithTag("config_approvals_autoApprove").performClick()
        use()
        assertTrue(calls.set.isEmpty())
    }

    @Test
    fun aSettingThisAppMayNotChangeIsShownButCannotBeChosen() {
        val calls = Calls()
        show(actions(calls, view(approvals = approvals("assisted", writable = false))))
        open()

        rule.onNodeWithTag("config_approvals_assisted").assertIsSelected().assertIsNotEnabled()
        rule.onNodeWithTag("config_approvals_default").assertIsNotEnabled()
        rule.onNodeWithTag("config_hint_approvals").assertTextEquals("Can't be changed from here.")
        rule.onNodeWithTag("config_mode_plan").assertIsEnabled()
        use()
        assertTrue(calls.set.isEmpty())
    }

    @Test
    fun aSettingThatCannotBeChangedStillShowsItsStateWhenNotAtDefault() {
        show(actions(Calls(), view(approvals = approvals("autoApprove", writable = false))))

        rule.onNodeWithTag("config_state_approvals").assertIsDisplayed().assertTextContains("Allow all")
    }

    // ---- what happens after ------------------------------------------------------------------------------------

    @Test
    fun aChangeThatIsNotConfirmedIsReportedAndTheRestIsNotTried() {
        val calls = Calls().apply { setResults = mutableListOf(InteractResult.NotConfirmed) }
        show(actions(calls))
        open()

        rule.onNodeWithTag("config_mode_autopilot").performClick()
        rule.onNodeWithTag("config_approvals_assisted").performClick()
        use()

        assertEquals(listOf("mode" to "autopilot"), calls.set)
        rule.onNodeWithTag("composer_notice").assertTextContains("device check was not completed", substring = true)
    }

    @Test
    fun aChangeTheHostRefusesIsReportedInTheHostsWords() {
        val calls = Calls().apply { result = InteractResult.Sent(DispatchOutcome.Rejected("Allow all is turned off by policy")) }
        show(actions(calls))
        open()

        rule.onNodeWithTag("config_approvals_autoApprove").performClick()
        use()

        rule.onNodeWithTag("composer_notice").assertTextContains("Allow all is turned off by policy", substring = true)
    }

    @Test
    fun aChangeThatSucceedsSaysNothing() {
        show(actions(Calls()))
        open()

        rule.onNodeWithTag("config_mode_plan").performClick()
        use()

        rule.onAllNodesWithTag("composer_notice").assertCountEquals(0)
    }

    @Test
    fun theAgentPickStillAppliesWhenASettingIsRefused() {
        val calls = Calls().apply { setResults = mutableListOf(InteractResult.NotConfirmed) }
        show(actions(calls, agents = listOf(plan, review)))
        open()

        rule.onNodeWithTag("config_approvals_autoApprove").performClick()
        rule.onAllNodesWithTag("agent_option")[1].performClick()
        use()

        rule.onNodeWithTag("agent_button").assertTextContains("review")
        rule.onNodeWithTag("composer_input").performTextInput("go")
        rule.onNodeWithTag("composer_send").performClick()
        rule.waitForIdle()
        assertEquals(listOf<AgentPick>(AgentPick.Use(review.uri)), calls.sentAgents)
    }

    @Test
    fun theAgentPickAndTheMessageAreNotAffectedBySettingsThatAreUnchanged() {
        val calls = Calls()
        show(actions(calls, agents = listOf(plan, review), agentInUse = plan.uri))
        open()

        rule.onAllNodesWithTag("agent_option")[1].performClick()
        use()
        rule.onNodeWithTag("composer_input").performTextInput("go")
        rule.onNodeWithTag("composer_send").performClick()
        rule.waitForIdle()

        assertTrue(calls.set.isEmpty())
        assertEquals(listOf<AgentPick>(AgentPick.Use(review.uri)), calls.sentAgents)
    }

    @Test
    fun theChipsFollowWhatTheHostReports() {
        val current = mutableStateOf(view())
        rule.setContent {
            AhpTheme {
                ChatScreen(
                    Transcript("Fix the build", ActivityState.IDLE, null, emptyList(), hasOlder = false), null, false, null, ConnState.Online, {}, {}, {},
                    actions = actions(Calls(), current.value),
                )
            }
        }
        rule.onAllNodesWithTag("config_state_approvals").assertCountEquals(0)

        current.value = view(approvals = approvals("autoApprove"))
        rule.waitForIdle()
        rule.onNodeWithTag("config_state_approvals").assertIsDisplayed()

        current.value = view()
        rule.waitForIdle()
        rule.onAllNodesWithTag("config_state_approvals").assertCountEquals(0)
    }

    @Test
    fun theDraftSurvivesRotationWhileTheDialogIsOpen() {
        val restoration = StateRestorationTester(rule)
        val calls = Calls()
        restoration.setContent {
            AhpTheme { ChatScreen(Transcript("Fix the build", ActivityState.IDLE, null, emptyList(), hasOlder = false), null, false, null, ConnState.Online, {}, {}, {}, actions = actions(calls)) }
        }
        open()
        rule.onNodeWithTag("config_mode_plan").performClick()

        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()

        rule.onNodeWithTag("config_mode_plan").assertIsSelected()
        use()
        assertEquals(listOf("mode" to "plan"), calls.set)
    }

    @Test
    fun theComposerStillSendsAfterAChange() {
        val calls = Calls()
        show(actions(calls))
        open()
        rule.onNodeWithTag("config_mode_plan").performClick()
        use()

        rule.onNodeWithTag("composer_input").performTextInput("hello")
        rule.onNodeWithTag("composer_send").performClick()
        rule.waitForIdle()

        assertEquals(listOf<AgentPick>(AgentPick.Keep), calls.sentAgents)
        rule.onAllNodesWithTag("composer_notice").assertCountEquals(0)
    }
}
