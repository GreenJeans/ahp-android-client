package dev.ahpclient.ui.chat

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.ModelChoice
import dev.ahpclient.state.ModelOption
import dev.ahpclient.state.ModelSetting
import dev.ahpclient.state.ModelSettingValue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ahpclient.state.ActivityState
import dev.ahpclient.state.AnswerValue
import dev.ahpclient.state.AgentChoice
import dev.ahpclient.state.AgentPick
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.DispatchOutcome
import dev.ahpclient.state.FormOption
import dev.ahpclient.state.FormQuestion
import dev.ahpclient.state.FormValue
import dev.ahpclient.state.InputForm
import dev.ahpclient.state.InputRequestView
import dev.ahpclient.state.InteractResult
import dev.ahpclient.state.ManageResult
import dev.ahpclient.state.PendingMessageKind
import dev.ahpclient.state.PendingMessageView
import dev.ahpclient.state.QuestionKind
import dev.ahpclient.state.QuickReplyChip
import dev.ahpclient.state.QuickReplyMode
import dev.ahpclient.state.QuickReplyProvider
import dev.ahpclient.state.QuickReplySource
import dev.ahpclient.state.QuickReplySuggestion
import dev.ahpclient.state.CustomizationCategory
import dev.ahpclient.state.CustomizationEntry
import dev.ahpclient.state.CustomizationsView
import dev.ahpclient.state.SlashCommand
import dev.ahpclient.state.SlashCommandKind
import dev.ahpclient.state.ToolState
import dev.ahpclient.ui.CustomizationsSheetContent
import dev.ahpclient.state.Transcript
import dev.ahpclient.state.TranscriptItem
import dev.ahpclient.state.TurnOutcome
import dev.ahpclient.ui.theme.AhpTheme
import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ToolCallState
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What the person can do in a chat, and what they cannot when the chat is read only. */
@RunWith(AndroidJUnit4::class)
class InteractionUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val done = InteractResult.Sent(DispatchOutcome.Accepted)

    private class Calls {
        val sent = mutableListOf<String>()
        val sentModels = mutableListOf<ModelChoice?>()
        val sentAgents = mutableListOf<AgentPick>()
        val steeredAgents = mutableListOf<AgentPick>()
        val steeredModels = mutableListOf<ModelChoice?>()
        val steered = mutableListOf<String>()
        var cancels = 0
        val approved = mutableListOf<Pair<String, String?>>()
        val denied = mutableListOf<Pair<String, String?>>()
        val resultApproved = mutableListOf<String>()
        val resultDenied = mutableListOf<String>()
        val answered = mutableListOf<Pair<String, Map<String, AnswerValue>>>()
        val drafts = mutableListOf<Triple<String, String, AnswerValue?>>()
        val declined = mutableListOf<String>()
        val dismissed = mutableListOf<String>()
        val restoredCheckpoints = mutableListOf<String>()
        var startOvers = 0
        val editedResends = mutableListOf<Pair<String, String>>()
        val retriedTurns = mutableListOf<String>()
        val stopAndSends = mutableListOf<String>()
        val removedPending = mutableListOf<String>()
        val reorderedQueues = mutableListOf<List<String>>()
        val forkedTurns = mutableListOf<String>()
        val editedForks = mutableListOf<Pair<String?, String>>()
        val openedSideChats = mutableListOf<Pair<String, String?>>()
        var result: InteractResult = InteractResult.Sent(DispatchOutcome.Accepted)
        var manageResult: ManageResult = ManageResult.Done("ahp-chat:/new")
    }

    private fun actions(
        calls: Calls,
        running: Boolean = false,
        models: List<ModelOption> = emptyList(),
        modelInUse: ModelChoice? = null,
        agents: List<AgentChoice> = emptyList(),
        agentInUse: String? = null,
        canFork: Boolean = false,
        canSideChat: Boolean = false,
        slashCommands: List<SlashCommand> = emptyList(),
        onOpenCustomizations: ((CustomizationCategory?, String) -> Unit)? = null,
        quickReplyMode: QuickReplyMode = QuickReplyMode.OFF,
        quickReplyProvider: QuickReplyProvider? = null,
    ) = ChatActionsUi(
        running = running,
        send = { text, model, agent -> calls.sent += text; calls.sentModels += model; calls.sentAgents += agent; calls.result },
        steer = { text, model, agent -> calls.steered += text; calls.steeredModels += model; calls.steeredAgents += agent; calls.result },
        cancel = { calls.cancels++; calls.result },
        approve = { id, option -> calls.approved += id to option; calls.result },
        deny = { id, option -> calls.denied += id to option; calls.result },
        approveResult = { calls.resultApproved += it; calls.result },
        denyResult = { calls.resultDenied += it; calls.result },
        answer = { id, answers -> calls.answered += id to answers; calls.result },
        saveDraft = { id, question, answer -> calls.drafts += Triple(id, question, answer); calls.result },
        decline = { calls.declined += it; calls.result },
        dismiss = { calls.dismissed += it; calls.result },
        models = models,
        modelInUse = modelInUse,
        agents = agents,
        agentInUse = agentInUse,
        restoreCheckpoint = { beforeTurnId -> calls.restoredCheckpoints += beforeTurnId; calls.result },
        startOver = { calls.startOvers++; calls.result },
        editAndResend = { turnId, newText, _, _ -> calls.editedResends += turnId to newText; calls.result },
        retryTurn = { turnId, _, _ -> calls.retriedTurns += turnId; calls.result },
        stopAndSend = { text, _, _ -> calls.stopAndSends += text; calls.result },
        removePending = { id -> calls.removedPending += id; calls.result },
        reorderQueue = { order -> calls.reorderedQueues += order; calls.result },
        canFork = canFork,
        forkFromTurn = { turnId -> calls.forkedTurns += turnId; calls.manageResult },
        editAsFork = { prevTurnId, newText, _, _ -> calls.editedForks += prevTurnId to newText; calls.manageResult },
        canSideChat = canSideChat,
        openSideChat = { turnId, initialText, _, _ -> calls.openedSideChats += turnId to initialText; calls.manageResult },
        slashCommands = slashCommands,
        onOpenCustomizations = onOpenCustomizations,
        quickReplyMode = quickReplyMode,
        quickReplyProvider = quickReplyProvider,
    )

    private fun transcript(vararg items: TranscriptItem) = Transcript("Fix the build", ActivityState.IN_PROGRESS, null, items.toList(), hasOlder = false)

    private fun show(transcript: Transcript, actions: ChatActionsUi?) = rule.setContent {
        AhpTheme { ChatScreen(transcript, null, false, null, ConnState.Online, {}, {}, {}, actions = actions) }
    }

    private fun call(body: String): ToolCallState = Ahp.json.decodeFromString(ToolCallState.serializer(), "{$body}")

    private val common = """"toolCallId":"c1","toolName":"run_in_terminal","displayName":"Run in Terminal""""

    private fun waiting(options: String = "[]") = TranscriptItem.Tool(
        "t1/c1", "Run in Terminal", "Run the tests", ToolState.WAITING_FOR_APPROVAL,
        call(""""status":"pending-confirmation",$common,"invocationMessage":"Run it","toolInput":"{}","confirmationTitle":"Run?","options":$options"""),
    )

    private val running = TranscriptItem.Tool(
        "t2/c2", "Read file", null, ToolState.RUNNING,
        call(""""status":"running","toolCallId":"c2","toolName":"read","displayName":"Read file","invocationMessage":"Reading","toolInput":"{}","confirmed":"user-action""""),
    )

    private val resultWaiting = TranscriptItem.Tool(
        "t3/c1", "Run in Terminal", "Run the tests", ToolState.WAITING_FOR_APPROVAL,
        call(""""status":"pending-result-confirmation",$common,"invocationMessage":"Run it","toolInput":"{}","pastTenseMessage":"Ran it","confirmed":"not-needed","success":true,"intention":"Run the tests""""),
    )

    private fun question(form: InputForm?) =
        TranscriptItem.Question("q1", InputRequestView("Question", "Which season?"), answered = false, form = form)

    private fun form(vararg questions: FormQuestion) = InputForm("req-1", null, questions.toList())

    private fun text(id: String = "a", required: Boolean = true) = FormQuestion(id, "Your name", QuestionKind.TEXT, required)

    // ---- the model picker ---------------------------------------------------------------------------------------

    private val sonnet = ModelOption(
        "claude-sonnet-5.5",
        "Claude Sonnet 5.5",
        listOf(ModelSetting("thinking", "Thinking", listOf(ModelSettingValue("low", "Quick"), ModelSettingValue("high", "Thorough")))),
    )
    private val auto = ModelOption("auto", "Auto", emptyList())

    private fun pickSonnetThorough() {
        rule.onNodeWithTag("model_button").performClick()
        rule.onAllNodesWithTag("model_option")[1].performClick()
        rule.onAllNodesWithTag("model_setting_value")[1].performClick()
        rule.onNodeWithTag("model_use").performClick()
    }

    private fun sendHi() {
        rule.onNodeWithTag("composer_input").performTextInput("hi")
        rule.onNodeWithTag("composer_send").performClick()
        rule.waitForIdle()
    }

    @Test
    fun withNoModelsThereIsNoPickerAndMessagesGoWithNoModel() {
        val calls = Calls()
        show(transcript(), actions(calls))

        rule.onNodeWithTag("model_button").assertDoesNotExist()
        sendHi()

        assertEquals(listOf<ModelChoice?>(null), calls.sentModels)
    }

    @Test
    fun theButtonSaysDefaultModelWhenNoneIsKnownYet() {
        show(transcript(), actions(Calls(), models = listOf(auto, sonnet)))

        rule.onNodeWithTag("model_button").assertTextContains("Default model", substring = true)
        rule.onNodeWithTag("model_button").assertContentDescriptionEquals("Model: Default model")
    }

    @Test
    fun aChatShowsTheModelItIsUsingAndSendsItWithTheMessage() {
        val calls = Calls()
        val inUse = ModelChoice("claude-sonnet-5.5", mapOf("thinking" to "high"))
        show(transcript(), actions(calls, models = listOf(auto, sonnet), modelInUse = inUse))

        rule.onNodeWithTag("model_button").assertTextContains("Claude Sonnet 5.5 · Thorough", substring = true)
        sendHi()

        assertEquals(listOf<ModelChoice?>(inUse), calls.sentModels)
    }

    @Test
    fun aPickWinsOverTheModelInUse() {
        val calls = Calls()
        show(transcript(), actions(calls, models = listOf(auto, sonnet), modelInUse = ModelChoice("claude-sonnet-5.5")))

        rule.onNodeWithTag("model_button").performClick()
        rule.onAllNodesWithTag("model_option")[0].performClick()
        rule.onNodeWithTag("model_use").performClick()
        rule.onNodeWithTag("model_button").assertTextContains("Auto", substring = true)
        sendHi()

        assertEquals(listOf<ModelChoice?>(ModelChoice("auto")), calls.sentModels)
    }

    @Test
    fun thePickerMarksTheModelInUseAndOffersNoHostChoosesRow() {
        show(transcript(), actions(Calls(), models = listOf(auto, sonnet), modelInUse = ModelChoice("claude-sonnet-5.5")))

        rule.onNodeWithTag("model_button").performClick()

        rule.onAllNodesWithTag("model_option")[1].assertIsSelected()
        rule.onAllNodesWithTag("model_default").assertCountEquals(0)
    }

    @Test
    fun withNoModelInUseThePickerOffersTheHostsChoiceAsDefault() {
        show(transcript(), actions(Calls(), models = listOf(auto, sonnet)))

        rule.onNodeWithTag("model_button").performClick()

        rule.onNodeWithTag("model_default").assertIsDisplayed().assertIsSelected()
    }

    private fun showInWindowOfHeight(heightDp: Int, agents: List<AgentChoice> = emptyList(), agentInUse: String? = null) = rule.setContent {
        AhpTheme {
            androidx.compose.foundation.layout.Box(Modifier.size(360.dp, heightDp.dp)) {
                ChatScreen(transcript(), null, false, null, ConnState.Online, {}, {}, {}, actions = actions(Calls(), models = listOf(auto, sonnet), agents = agents, agentInUse = agentInUse))
            }
        }
    }

    @Test
    fun theModelButtonSitsUnderTheMessageBoxOnTheRowOfTheSendButton() {
        showInWindowOfHeight(900)

        val chip = rule.onNodeWithTag("model_button").fetchSemanticsNode().boundsInRoot
        val field = rule.onNodeWithTag("composer_input").fetchSemanticsNode().boundsInRoot
        val send = rule.onNodeWithTag("composer_send").fetchSemanticsNode().boundsInRoot
        assertTrue("the chip (top ${chip.top}) is under the field (bottom ${field.bottom})", chip.top >= field.bottom - 1f)
        assertTrue("the chip (${chip.left}..${chip.right}) is left of the send button (${send.left}..${send.right})", chip.right <= send.left)
        assertTrue("the chip (${chip.top}..${chip.bottom}) shares a row with the send button (${send.top}..${send.bottom})", chip.top < send.bottom && chip.bottom > send.top)
    }

    @Test
    fun inAShortWindowTheModelButtonStillWorks() {
        showInWindowOfHeight(400)

        rule.onNodeWithTag("model_button").performClick()
        rule.onNodeWithTag("model_use").assertIsDisplayed()
    }

    @Test
    fun aPickedModelAndItsSettingAreShownAndSentWithTheMessage() {
        val calls = Calls()
        show(transcript(), actions(calls, models = listOf(auto, sonnet)))

        pickSonnetThorough()
        rule.onNodeWithTag("model_button").assertTextContains("Claude Sonnet 5.5 · Thorough", substring = true)
        sendHi()

        assertEquals(listOf<ModelChoice?>(ModelChoice("claude-sonnet-5.5", mapOf("thinking" to "high"))), calls.sentModels)
    }

    @Test
    fun aSteeringMessageCarriesTheModelToo() {
        val calls = Calls()
        show(transcript(), actions(calls, running = true, models = listOf(auto, sonnet)))

        pickSonnetThorough()
        rule.onNodeWithTag("composer_input").performTextInput("now")
        rule.onNodeWithTag("composer_steer").performClick()
        rule.waitForIdle()

        assertEquals(listOf<ModelChoice?>(ModelChoice("claude-sonnet-5.5", mapOf("thinking" to "high"))), calls.steeredModels)
    }

    @Test
    fun aModelWithNoSettingChosenSendsJustTheModel() {
        val calls = Calls()
        show(transcript(), actions(calls, models = listOf(auto, sonnet)))

        rule.onNodeWithTag("model_button").performClick()
        rule.onAllNodesWithTag("model_option")[0].performClick()
        rule.onNodeWithTag("model_use").performClick()
        rule.onNodeWithTag("model_button").assertTextContains("Auto", substring = true)
        sendHi()

        assertEquals(listOf<ModelChoice?>(ModelChoice("auto")), calls.sentModels)
    }

    @Test
    fun hostDefaultSendsNoModelAtAll() {
        val calls = Calls()
        show(transcript(), actions(calls, models = listOf(auto, sonnet)))
        pickSonnetThorough()

        rule.onNodeWithTag("model_button").performClick()
        rule.onNodeWithTag("model_default").performClick()
        rule.onNodeWithTag("model_use").performClick()
        sendHi()

        assertEquals(listOf<ModelChoice?>(null), calls.sentModels)
    }

    @Test
    fun anotherModelStartsWithItsOwnDefaultSettings() {
        val calls = Calls()
        show(transcript(), actions(calls, models = listOf(auto, sonnet)))
        pickSonnetThorough()

        rule.onNodeWithTag("model_button").performClick()
        rule.onAllNodesWithTag("model_option")[0].performClick()
        rule.onAllNodesWithTag("model_option")[1].performClick()
        rule.onNodeWithTag("model_use").performClick()
        sendHi()

        assertEquals(listOf<ModelChoice?>(ModelChoice("claude-sonnet-5.5")), calls.sentModels)
    }

    @Test
    fun cancellingKeepsTheEarlierPick() {
        show(transcript(), actions(Calls(), models = listOf(auto, sonnet)))
        pickSonnetThorough()

        rule.onNodeWithTag("model_button").performClick()
        rule.onAllNodesWithTag("model_option")[0].performClick()
        rule.onNodeWithTag("model_cancel").performClick()

        rule.onNodeWithTag("model_button").assertTextContains("Claude Sonnet 5.5 · Thorough", substring = true)
    }

    @Test
    fun aPickTheHostNoLongerOffersIsDroppedAndNeverSent() {
        val calls = Calls()
        val models = mutableStateOf(listOf(auto, sonnet))
        rule.setContent {
            AhpTheme { ChatScreen(transcript(), null, false, null, ConnState.Online, {}, {}, {}, actions = actions(calls, models = models.value)) }
        }
        pickSonnetThorough()

        models.value = listOf(auto)
        rule.waitForIdle()
        rule.onNodeWithTag("model_button").assertTextContains("Default model", substring = true)
        sendHi()

        assertEquals(listOf<ModelChoice?>(null), calls.sentModels)
    }

    @Test
    fun theRowsAreBigEnoughAndAreRadioButtonsAndTheHostsWordsAreJustText() {
        val odd = ModelOption("x", "<b>bold</b> [link](https://evil.example)", emptyList())
        show(transcript(), actions(Calls(), models = listOf(odd)))

        rule.onNodeWithTag("model_button").performClick()

        rule.onAllNodesWithTag("model_option")[0].assert(isSelectable()).assertHeightIsAtLeast(48.dp)
            .assertTextContains("<b>bold</b> [link](https://evil.example)", substring = true)
        rule.onNodeWithTag("model_default").assert(isSelectable()).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun tappingTheChosenModelAgainKeepsItsSettings() {
        val calls = Calls()
        show(transcript(), actions(calls, models = listOf(auto, sonnet)))

        rule.onNodeWithTag("model_button").performClick()
        rule.onAllNodesWithTag("model_option")[1].performClick()
        rule.onAllNodesWithTag("model_setting_value")[1].performClick()
        rule.onAllNodesWithTag("model_option")[1].performClick()
        rule.onNodeWithTag("model_use").performClick()
        sendHi()

        assertEquals(listOf<ModelChoice?>(ModelChoice("claude-sonnet-5.5", mapOf("thinking" to "high"))), calls.sentModels)
    }

    @Test
    fun reopeningThePickerShowsWhatIsPickedNow() {
        show(transcript(), actions(Calls(), models = listOf(auto, sonnet)))
        pickSonnetThorough()

        rule.onNodeWithTag("model_button").performClick()

        rule.onAllNodesWithTag("model_option")[1].assertIsSelected()
        rule.onAllNodesWithTag("model_option")[0].assertIsNotSelected()
        rule.onNodeWithTag("model_default").assertIsNotSelected()
        rule.onAllNodesWithTag("model_setting_value")[1].assertIsSelected()
        rule.onNodeWithTag("model_setting_default").assertIsNotSelected()
    }

    @Test
    fun aSettingCanGoBackToTheModelsOwnDefault() {
        val calls = Calls()
        show(transcript(), actions(calls, models = listOf(auto, sonnet)))
        pickSonnetThorough()

        rule.onNodeWithTag("model_button").performClick()
        rule.onNodeWithTag("model_setting_default").performClick()
        rule.onNodeWithTag("model_use").performClick()
        sendHi()

        assertEquals(listOf<ModelChoice?>(ModelChoice("claude-sonnet-5.5")), calls.sentModels)
    }

    @Test
    fun aPickSurvivesRotation() {
        val calls = Calls()
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            AhpTheme { ChatScreen(transcript(), null, false, null, ConnState.Online, {}, {}, {}, actions = actions(calls, models = listOf(auto, sonnet))) }
        }
        pickSonnetThorough()

        restoration.emulateSavedInstanceStateRestore()

        rule.onNodeWithTag("model_button").assertTextContains("Claude Sonnet 5.5 · Thorough", substring = true)
        sendHi()
        assertEquals(listOf<ModelChoice?>(ModelChoice("claude-sonnet-5.5", mapOf("thinking" to "high"))), calls.sentModels)
    }

    // ---- the custom agent ---------------------------------------------------------------------------------------

    private val plan = AgentChoice("file:///a/plan.agent.md", "plan", "Plan first, then act")
    private val review = AgentChoice("file:///a/review.agent.md", "review", null)

    @Test
    fun withNoAgentsOfferedAndNoneInUseThereIsNoAgentChip() {
        show(transcript(), actions(Calls(), models = listOf(auto)))

        rule.onNodeWithTag("agent_button").assertDoesNotExist()
    }

    @Test
    fun theChipSaysDefaultAgentForAChatOnNoCustomAgent() {
        show(transcript(), actions(Calls(), agents = listOf(plan, review)))

        rule.onNodeWithTag("agent_button").assertTextContains("Default agent").assertContentDescriptionEquals("Agent: Default agent")
    }

    @Test
    fun theChipNamesTheAgentTheChatIsOn_byItsOfferedName_else_byItsFile() {
        show(transcript(), actions(Calls(), agents = listOf(plan, review), agentInUse = plan.uri))
        rule.onNodeWithTag("agent_button").assertTextContains("plan")
    }

    @Test
    fun anAgentTheHostNoLongerListsIsStillNamedByItsFile() {
        show(transcript(), actions(Calls(), agents = listOf(review), agentInUse = "file:///a/gone.agent.md"))

        rule.onNodeWithTag("agent_button").assertTextContains("gone")
    }

    @Test
    fun sendingKeepsTheAgentTheChatIsOnWhenNothingWasPicked() {
        val calls = Calls()
        show(transcript(), actions(calls, agents = listOf(plan, review), agentInUse = plan.uri))

        sendHi()

        assertEquals(listOf<AgentPick>(AgentPick.Keep), calls.sentAgents)
    }

    @Test
    fun theDialogListsDefaultAndEachAgentWithItsDescriptionAndMarksTheOneInUse() {
        show(transcript(), actions(Calls(), agents = listOf(plan, review), agentInUse = plan.uri))

        rule.onNodeWithTag("agent_button").performClick()

        rule.onNodeWithTag("agent_dialog").assertIsDisplayed()
        rule.onNodeWithTag("agent_default").assert(isSelectable()).assertIsNotSelected().assertHeightIsAtLeast(48.dp)
        rule.onAllNodesWithTag("agent_option").assertCountEquals(2)
        rule.onAllNodesWithTag("agent_option")[0].assertIsSelected().assertTextContains("Plan first, then act", substring = true)
        rule.onAllNodesWithTag("agent_option")[1].assertIsNotSelected().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun aLongListOpensAtTheAgentTheChatIsOnSoItIsInViewAndSelected() {
        val many = (1..15).map { AgentChoice("file:///a/agent$it.agent.md", "agent$it", "Does thing $it") }
        show(transcript(), actions(Calls(), agents = many, agentInUse = many.last().uri))

        rule.onNodeWithTag("agent_button").performClick()

        rule.onNode(hasTestTag("agent_option") and isSelected()).assertIsDisplayed().assertTextContains("agent15", substring = true)
        rule.onNodeWithTag("agent_default").assertDoesNotExist()
    }

    @Test
    fun anAgentPickedIsShownAndSentWithEveryMessageUntilChanged() {
        val calls = Calls()
        show(transcript(), actions(calls, agents = listOf(plan, review), agentInUse = null))

        rule.onNodeWithTag("agent_button").performClick()
        rule.onAllNodesWithTag("agent_option")[1].performClick()
        rule.onNodeWithTag("agent_use").performClick()
        rule.onNodeWithTag("agent_button").assertTextContains("review")
        sendHi()
        sendHi()

        assertEquals(listOf<AgentPick>(AgentPick.Use(review.uri), AgentPick.Use(review.uri)), calls.sentAgents)
        rule.onNodeWithTag("agent_button").assertTextContains("review")
    }

    @Test
    fun anAgentPickedGoesWithASteeringMessageToo() {
        val calls = Calls()
        show(transcript(), actions(calls, running = true, agents = listOf(plan, review)))

        rule.onNodeWithTag("agent_button").performClick()
        rule.onAllNodesWithTag("agent_option")[0].performClick()
        rule.onNodeWithTag("agent_use").performClick()
        rule.onNodeWithTag("composer_input").performTextInput("now")
        rule.onNodeWithTag("composer_steer").performClick()
        rule.waitForIdle()

        assertEquals(listOf<AgentPick>(AgentPick.Use(plan.uri)), calls.steeredAgents)
    }

    @Test
    fun choosingDefaultAgentOnAChatThatIsOnOneSendsNone() {
        val calls = Calls()
        show(transcript(), actions(calls, agents = listOf(plan, review), agentInUse = plan.uri))

        rule.onNodeWithTag("agent_button").performClick()
        rule.onNodeWithTag("agent_default").performClick()
        rule.onNodeWithTag("agent_use").performClick()
        rule.onNodeWithTag("agent_button").assertTextContains("Default agent")
        sendHi()

        assertEquals(listOf<AgentPick>(AgentPick.None), calls.sentAgents)
    }

    @Test
    fun choosingTheAgentTheChatIsAlreadyOnIsKeepSoALaterChangeElsewhereIsNotUndone() {
        val calls = Calls()
        show(transcript(), actions(calls, agents = listOf(plan, review), agentInUse = plan.uri))

        rule.onNodeWithTag("agent_button").performClick()
        rule.onAllNodesWithTag("agent_option")[1].performClick()
        rule.onAllNodesWithTag("agent_option")[0].performClick()
        rule.onNodeWithTag("agent_use").performClick()
        sendHi()

        assertEquals(listOf<AgentPick>(AgentPick.Keep), calls.sentAgents)
    }

    @Test
    fun cancellingTheAgentDialogChangesNothing() {
        val calls = Calls()
        show(transcript(), actions(calls, agents = listOf(plan, review), agentInUse = plan.uri))

        rule.onNodeWithTag("agent_button").performClick()
        rule.onAllNodesWithTag("agent_option")[1].performClick()
        rule.onNodeWithTag("agent_cancel").performClick()

        rule.onNodeWithTag("agent_dialog").assertDoesNotExist()
        rule.onNodeWithTag("agent_button").assertTextContains("plan")
        sendHi()
        assertEquals(listOf<AgentPick>(AgentPick.Keep), calls.sentAgents)
    }

    @Test
    fun anAgentTheChatIsOnButTheHostNoLongerListsIsShownInTheDialogAndCanBeLeft() {
        show(transcript(), actions(Calls(), agents = listOf(review), agentInUse = "file:///a/gone.agent.md"))

        rule.onNodeWithTag("agent_button").performClick()

        rule.onAllNodesWithTag("agent_option").assertCountEquals(2)
        rule.onAllNodesWithTag("agent_option")[0].assertIsSelected().assertTextContains("Not offered now", substring = true)
    }

    @Test
    fun aPickedAgentTheHostStopsOfferingIsDroppedAndTheChatsOwnIsKept() {
        val calls = Calls()
        val offered = mutableStateOf(listOf(plan, review))
        rule.setContent {
            AhpTheme { ChatScreen(transcript(), null, false, null, ConnState.Online, {}, {}, {}, actions = actions(calls, agents = offered.value, agentInUse = plan.uri)) }
        }
        rule.onNodeWithTag("agent_button").performClick()
        rule.onAllNodesWithTag("agent_option")[1].performClick()
        rule.onNodeWithTag("agent_use").performClick()
        rule.onNodeWithTag("agent_button").assertTextContains("review")

        offered.value = listOf(plan)
        rule.waitForIdle()
        rule.onNodeWithTag("agent_button").assertTextContains("plan")
        sendHi()

        assertEquals(listOf<AgentPick>(AgentPick.Keep), calls.sentAgents)
    }

    @Test
    fun anAgentPickSurvivesRotation() {
        val calls = Calls()
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            AhpTheme { ChatScreen(transcript(), null, false, null, ConnState.Online, {}, {}, {}, actions = actions(calls, agents = listOf(plan, review))) }
        }
        rule.onNodeWithTag("agent_button").performClick()
        rule.onAllNodesWithTag("agent_option")[1].performClick()
        rule.onNodeWithTag("agent_use").performClick()

        restoration.emulateSavedInstanceStateRestore()

        rule.onNodeWithTag("agent_button").assertTextContains("review")
        sendHi()
        assertEquals(listOf<AgentPick>(AgentPick.Use(review.uri)), calls.sentAgents)
    }

    @Test
    fun theAgentAndModelChipsShareTheRowWithoutPushingTheButtonsOff() {
        showInWindowOfHeight(900, agents = listOf(plan, review), agentInUse = "file:///a/a-long-agent-name-that-would-not-fit.agent.md")

        val agent = rule.onNodeWithTag("agent_button").fetchSemanticsNode().boundsInRoot
        val model = rule.onNodeWithTag("model_button").fetchSemanticsNode().boundsInRoot
        val send = rule.onNodeWithTag("composer_send").fetchSemanticsNode().boundsInRoot
        assertTrue("the chips overlap: agent $agent, model $model", agent.right <= model.left + 1f)
        assertTrue("the model chip (${model.right}) runs into the send button (${send.left})", model.right <= send.left)
    }

    // ---- a read only chat has nothing to act with ---------------------------------------------------------------

    @Test
    fun aReadOnlyChatHasNoComposerNoButtonsAndNoForm() {
        show(transcript(waiting(), question(form(text()))), actions = null)

        rule.onNodeWithTag("composer_input").assertDoesNotExist()
        rule.onNodeWithTag("composer_send").assertDoesNotExist()
        rule.onNodeWithTag("approve_plain").assertDoesNotExist()
        rule.onNodeWithTag("question_form").assertDoesNotExist()
        rule.onNodeWithTag("row_question_note").assertTextContains("Answer in VS Code", substring = true)
        rule.onNode(hasText("Waiting for approval in VS Code", substring = true), useUnmergedTree = true).assertExists()
    }

    // ---- the composer -------------------------------------------------------------------------------------------

    @Test
    fun sendIsOffUntilThereIsTextAndSendsExactlyWhatWasTyped() {
        val calls = Calls()
        show(transcript(), actions(calls))

        rule.onNodeWithTag("composer_send").assertIsNotEnabled()
        rule.onNodeWithTag("composer_input").performTextInput("  hello there  ")
        rule.onNodeWithTag("composer_send").assertIsEnabled().performClick()

        rule.waitUntil(5_000) { calls.sent.isNotEmpty() }
        assertEquals(listOf("  hello there  "), calls.sent)
    }

    @Test
    fun aMessageTheHostTookIsClearedFromTheBox() {
        val calls = Calls()
        show(transcript(), actions(calls))

        rule.onNodeWithTag("composer_input").performTextInput("hello")
        rule.onNodeWithTag("composer_send").performClick()

        rule.waitUntil(5_000) { calls.sent.isNotEmpty() }
        rule.waitUntil(5_000) { rule.onAllNodesWithTagCount("composer_send") == 1 && !textInBox("hello") }
        rule.onNodeWithTag("composer_notice").assertDoesNotExist()
    }

    @Test
    fun aMessageThatWasRefusedStaysInTheBoxWithTheReason() {
        val calls = Calls().apply { result = InteractResult.Sent(DispatchOutcome.Rejected("not now")) }
        show(transcript(), actions(calls))

        rule.onNodeWithTag("composer_input").performTextInput("hello")
        rule.onNodeWithTag("composer_send").performClick()

        rule.waitUntil(5_000) { rule.onAllNodesWithTagCount("composer_notice") == 1 }
        rule.onNodeWithTag("composer_notice").assertTextContains("The host refused it: not now")
        assertTrue("the text must still be there to retry", textInBox("hello"))
    }

    @Test
    fun aMessageWhoseFateIsUnknownStaysAndWarnsAgainstSendingItTwice() {
        val calls = Calls().apply { result = InteractResult.Sent(DispatchOutcome.NoReply) }
        show(transcript(), actions(calls))

        rule.onNodeWithTag("composer_input").performTextInput("hello")
        rule.onNodeWithTag("composer_send").performClick()

        rule.waitUntil(5_000) { rule.onAllNodesWithTagCount("composer_notice") == 1 }
        rule.onNodeWithTag("composer_notice").assertTextContains("before sending it again", substring = true)
        assertTrue(textInBox("hello"))
    }

    @Test
    fun aSendThatNeverLeftThePhoneStaysAndSaysSo() {
        val calls = Calls().apply { result = InteractResult.NotSent(IOException("offline")) }
        show(transcript(), actions(calls))

        rule.onNodeWithTag("composer_input").performTextInput("hello")
        rule.onNodeWithTag("composer_send").performClick()

        rule.waitUntil(5_000) { rule.onAllNodesWithTagCount("composer_notice") == 1 }
        rule.onNodeWithTag("composer_notice").assertTextContains("Could not send", substring = true)
        assertTrue(textInBox("hello"))
    }

    @Test
    fun anIdleChatOffersSendAndNoStop() {
        val idle = Calls()
        show(transcript(), actions(idle, running = false))
        rule.onNodeWithTag("composer_stop").assertDoesNotExist()
        rule.onNodeWithContentDescription("Send").assertIsDisplayed()
    }

    @Test
    fun whileTheAgentWorksSendSaysQueueAndStopStopsIt() {
        val calls = Calls()
        show(transcript(), actions(calls, running = true))

        rule.onNodeWithContentDescription("Queue").assertIsDisplayed()
        rule.onNodeWithTag("composer_stop").assertIsDisplayed().performClick()

        rule.waitUntil(5_000) { calls.cancels == 1 }
    }

    // ---- approvals ----------------------------------------------------------------------------------------------

    @Test
    fun aCallWaitingForTheUserHasApproveAndDenyAndItsBadgeSaysItIsTheirs() {
        val calls = Calls()
        show(transcript(waiting()), actions(calls))

        rule.onNode(hasText("Waiting for your approval", substring = true), useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Waiting for approval in VS Code", substring = true, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("approve_plain").assertIsDisplayed().performClick()
        rule.waitUntil(5_000) { calls.approved.isNotEmpty() }
        assertEquals(listOf("c1" to null), calls.approved)

        rule.onNodeWithTag("deny_plain").performClick()
        rule.waitUntil(5_000) { calls.denied.isNotEmpty() }
        assertEquals(listOf("c1" to null), calls.denied)
    }

    @Test
    fun theHostsOwnOptionsReplaceThePlainButtonsAndKeepTheirMeaning() {
        val calls = Calls()
        show(
            transcript(waiting(options = """[{"id":"o-once","label":"Allow once","kind":"approve"},{"id":"o-no","label":"Not now","kind":"deny"}]""")),
            actions(calls),
        )

        rule.onNodeWithTag("approve_plain").assertDoesNotExist()
        rule.onNodeWithTag("approve_o-once").performClick()
        rule.onNodeWithTag("deny_o-no").performClick()

        rule.waitUntil(5_000) { calls.approved.isNotEmpty() && calls.denied.isNotEmpty() }
        assertEquals(listOf("c1" to "o-once"), calls.approved)
        assertEquals(listOf("c1" to "o-no"), calls.denied)
    }

    @Test
    fun anApprovalThatTheDeviceCheckRefusedSaysSoAndNothingElse() {
        val calls = Calls().apply { result = InteractResult.NotConfirmed }
        show(transcript(waiting()), actions(calls))

        rule.onNodeWithTag("approve_plain").performClick()

        rule.waitUntil(5_000) { rule.onAllNodesWithTagCount("approval_notice") == 1 }
        rule.onNodeWithTag("approval_notice").assertTextContains("device check", substring = true)
    }

    @Test
    fun aCallThatIsNotWaitingHasNoButtons() {
        show(transcript(running), actions(Calls()))

        rule.onNodeWithTag("approve_plain").assertDoesNotExist()
        rule.onNodeWithTag("deny_plain").assertDoesNotExist()
    }

    // ---- questions ----------------------------------------------------------------------------------------------

    @Test
    fun aRequiredAnswerLeftBlankShowsItsProblemAndSendsNothing() {
        val calls = Calls()
        show(transcript(question(form(text()))), actions(calls))

        rule.onNodeWithTag("question_submit").performClick()

        rule.onNodeWithText("Enter an answer.").assertIsDisplayed()
        assertTrue(calls.answered.isEmpty())
    }

    @Test
    fun aFilledInAnswerIsSentOnceInItsOwnShape() {
        val calls = Calls()
        show(
            transcript(
                question(
                    form(
                        text("a"),
                        FormQuestion("n", "How many", QuestionKind.NUMBER, true),
                        FormQuestion("b", "Sure", QuestionKind.BOOLEAN, true),
                        FormQuestion("s", "Season", QuestionKind.SINGLE, true, listOf(FormOption("sp", "Spring"), FormOption("au", "Autumn"))),
                        FormQuestion("m", "Colors", QuestionKind.MULTI, true, listOf(FormOption("r", "Red"), FormOption("g", "Green"), FormOption("u", "Blue"))),
                    ),
                ),
            ),
            actions(calls),
        )

        rule.onNodeWithTag("field_a").performScrollTo().performTextInput("Ada")
        rule.onNodeWithTag("field_n").performScrollTo().performTextInput("2.5")
        rule.onNodeWithTag("field_b").performScrollTo().performClick()
        rule.onNodeWithTag("option_s_au").performScrollTo().performClick()
        // Tapped by action, not by position: the floating jump button can sit over a row that was scrolled to the bottom edge.
        rule.onNodeWithTag("option_m_g").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("option_m_u").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("question_submit").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)

        rule.waitUntil(5_000) { calls.answered.isNotEmpty() }
        val (requestId, answers) = calls.answered.single()
        assertEquals("req-1", requestId)
        assertEquals(
            mapOf(
                "a" to AnswerValue.Text("Ada"),
                "n" to AnswerValue.Number(2.5),
                "b" to AnswerValue.Bool(true),
                "s" to AnswerValue.Selected("au"),
                "m" to AnswerValue.SelectedMany(listOf("g", "u")),
            ),
            answers,
        )
    }

    @Test
    fun aNumberThatIsNotANumberShowsItsProblem() {
        val calls = Calls()
        show(transcript(question(form(FormQuestion("n", "How many", QuestionKind.NUMBER, true)))), actions(calls))

        rule.onNodeWithTag("field_n").performTextInput("lots")
        rule.onNodeWithTag("question_submit").performClick()

        rule.onNodeWithText("Enter a number.").assertIsDisplayed()
        assertTrue(calls.answered.isEmpty())
    }

    @Test
    fun declineAndDismissSendTheirOwnResponsesWithoutAnyAnswers() {
        val calls = Calls()
        show(transcript(question(form(text()))), actions(calls))

        rule.onNodeWithTag("field_a").performTextInput("typed but not sent")
        rule.onNodeWithTag("question_decline").performClick()
        rule.onNodeWithTag("question_dismiss").performClick()

        rule.waitUntil(5_000) { calls.declined.isNotEmpty() && calls.dismissed.isNotEmpty() }
        assertEquals(listOf("req-1"), calls.declined)
        assertEquals(listOf("req-1"), calls.dismissed)
        assertTrue(calls.answered.isEmpty())
    }

    @Test
    fun aQuestionThisAppCannotAskStillSaysWhereToAnswerIt() {
        show(transcript(question(null)), actions(Calls()))

        rule.onNodeWithTag("question_form").assertDoesNotExist()
        rule.onNodeWithTag("input_request").assertIsDisplayed()
        rule.onNodeWithTag("row_question_note").assertTextContains("Answer in VS Code", substring = true)
    }

    @Test
    fun anAnswerableQuestionShowsTheFormWithoutDuplicatingTheReadOnlyCardAndShowsFullPromptsHeadingsAndOptions() {
        val longPrompt = "The spec has already passed on f3 (1 passed, 59.5s) and failed on qa in earlier CI runs. " +
            "It only creates a user and custom attributes via API and checks the Reports UI, so it cannot explain why Publish disappeared."
        val longOption = "Exclude the spec from the gate via excludeSpecs, keep it runnable on demand, and document the follow-up ticket."
        val richForm = InputForm(
            requestId = "req-rich",
            message = "The CI check says the Publish loss is deterministic. Nothing is queued or pushed until you answer.",
            questions = listOf(
                FormQuestion(
                    id = "policy",
                    title = longPrompt,
                    heading = "reports-custom-attributes policy",
                    kind = QuestionKind.SINGLE,
                    required = true,
                    options = listOf(
                        FormOption("exclude", longOption, description = "Keeps CI green while preserving manual runs", recommended = true),
                        FormOption("keep", "Keep it in the gate"),
                    ),
                ),
            ),
        )

        show(transcript(question(richForm)), actions(Calls()))

        rule.onNodeWithTag("question_form").assertIsDisplayed()
        rule.onNodeWithTag("input_request").assertDoesNotExist()
        rule.onNodeWithTag("question_message").assertTextContains("Nothing is queued or pushed until you answer.", substring = true)
        rule.onNodeWithText("reports-custom-attributes policy").assertIsDisplayed()
        rule.onNodeWithText(longPrompt).assertIsDisplayed()
        rule.onNodeWithTag("option_policy_exclude").assertTextContains("$longOption (recommended)", substring = true)
        rule.onNodeWithTag("option_policy_exclude").assertTextContains("Keeps CI green while preserving manual runs", substring = true)
    }

    // ---- steering -----------------------------------------------------------------------------------------------

    @Test
    fun steerAppearsOnceThereIsTextAndSteersInsteadOfQueueing() {
        val calls = Calls()
        show(transcript(), actions(calls, running = true))

        rule.onNodeWithTag("composer_steer").assertDoesNotExist()
        rule.onNodeWithTag("composer_input").performTextInput(" turn left ")
        rule.onNodeWithTag("composer_steer").assertIsEnabled().performClick()

        rule.waitUntil(5_000) { calls.steered.isNotEmpty() }
        assertEquals(listOf(" turn left "), calls.steered)
        assertTrue("steering is not queueing", calls.sent.isEmpty())
        rule.waitUntil(5_000) { !textInBox(" turn left ") }
    }

    @Test
    fun anIdleChatHasNoSteerButton() {
        show(transcript(), actions(Calls(), running = false))

        rule.onNodeWithTag("composer_steer").assertDoesNotExist()
    }

    @Test
    fun aSteerTheHostRefusedKeepsTheTextWithTheReason() {
        val calls = Calls().apply { result = InteractResult.Sent(DispatchOutcome.Rejected("turn over")) }
        show(transcript(), actions(calls, running = true))

        rule.onNodeWithTag("composer_input").performTextInput("hello")
        rule.onNodeWithTag("composer_steer").performClick()

        rule.waitUntil(5_000) { rule.onAllNodesWithTagCount("composer_notice") == 1 }
        rule.onNodeWithTag("composer_notice").assertTextContains("The host refused it: turn over")
        assertTrue(textInBox("hello"))
    }

    // ---- approving what a tool produced -------------------------------------------------------------------------

    @Test
    fun aResultWaitingForTheUserHasItsOwnButtonsThatUseTheResultActions() {
        val calls = Calls()
        show(transcript(resultWaiting), actions(calls))

        rule.onNodeWithTag("approve_plain").assertDoesNotExist()
        rule.onNodeWithTag("deny_plain").assertDoesNotExist()
        rule.onNodeWithTag("approve_result").assertIsDisplayed().performClick()
        rule.onNodeWithTag("deny_result").performClick()

        rule.waitUntil(5_000) { calls.resultApproved.isNotEmpty() && calls.resultDenied.isNotEmpty() }
        assertEquals(listOf("c1"), calls.resultApproved)
        assertEquals(listOf("c1"), calls.resultDenied)
        assertTrue("the call's own approval is a different action", calls.approved.isEmpty() && calls.denied.isEmpty())
        rule.onNodeWithText("Let the agent see the result").assertExists()
    }

    @Test
    fun aResultWaitingInAReadOnlyChatHasNoButtons() {
        show(transcript(resultWaiting), actions = null)

        rule.onNodeWithTag("approve_result").assertDoesNotExist()
        rule.onNodeWithTag("deny_result").assertDoesNotExist()
    }

    // ---- drafts -------------------------------------------------------------------------------------------------

    @Test
    fun whatIsTypedIsSentAsADraftOnlyAfterAPauseAndOnlyTheLatestText() {
        val calls = Calls()
        show(transcript(question(form(text("a"), text("b", required = false)))), actions(calls))
        rule.mainClock.autoAdvance = false

        rule.onNodeWithTag("field_a").performTextInput("A")
        rule.mainClock.advanceTimeBy(400)
        rule.onNodeWithTag("field_a").performTextInput("da")
        rule.mainClock.advanceTimeBy(400)
        assertTrue("the pause starts over with each change", calls.drafts.isEmpty())

        rule.mainClock.advanceTimeBy(1_000)
        assertEquals(listOf(Triple("req-1", "a", AnswerValue.Text("Ada"))), calls.drafts)
    }

    @Test
    fun aDraftTheHostAlreadyHoldsIsNotSentBackButClearingItIs() {
        val calls = Calls()
        val seeded = InputForm("req-1", null, listOf(text("a"), text("b", required = false)), drafts = mapOf("a" to FormValue.Text("from VS Code")))
        show(transcript(question(seeded)), actions(calls))
        rule.mainClock.autoAdvance = false

        rule.onNodeWithTag("field_a").assertTextContains("from VS Code")
        rule.onNodeWithTag("field_b").performTextInput("x")
        rule.mainClock.advanceTimeBy(1_500)
        assertEquals(listOf(Triple("req-1", "b", AnswerValue.Text("x"))), calls.drafts)

        rule.onNodeWithTag("field_a").performTextClearance()
        rule.mainClock.advanceTimeBy(1_500)
        assertEquals(Triple("req-1", "a", null), calls.drafts.last())
        assertEquals(2, calls.drafts.size)
    }

    @Test
    fun noDraftFollowsADecline() {
        val calls = Calls()
        show(transcript(question(form(text()))), actions(calls))
        rule.mainClock.autoAdvance = false

        rule.onNodeWithTag("field_a").performTextInput("typed")
        rule.onNodeWithTag("question_decline").performClick()
        rule.mainClock.advanceTimeBy(2_000)

        assertEquals(listOf("req-1"), calls.declined)
        assertTrue("a draft sent after the decline could bring the question back", calls.drafts.isEmpty())
    }

    @Test
    fun aDeclineTheHostRefusedLetsDraftsResume() {
        val calls = Calls().apply { result = InteractResult.Sent(DispatchOutcome.Rejected("no")) }
        show(transcript(question(form(text()))), actions(calls))
        rule.mainClock.autoAdvance = false

        rule.onNodeWithTag("field_a").performTextInput("typed")
        rule.onNodeWithTag("question_decline").performClick()
        rule.mainClock.advanceTimeBy(2_000)

        assertEquals(listOf(Triple("req-1", "a", AnswerValue.Text("typed"))), calls.drafts)
    }

    // ---- checkpoints, forking, editing, retrying & pending tray -------------------------------------------------

    @Test
    fun checkpointRestoreAndStartOverConfirmTurnCountAndPrefillTheComposer() {
        val calls = Calls()
        val items = listOf(
            TranscriptItem.User("t1/user", "first prompt", turnId = "t1", turnIndex = 0, previousTurnId = null, turnsFromHereCount = 2),
            TranscriptItem.Markdown("t1/m1", "first answer"),
            TranscriptItem.TurnEnd("t1/end", TurnOutcome.DONE, 1_000L, turnId = "t1"),
            TranscriptItem.User("t2/user", "second prompt", turnId = "t2", turnIndex = 1, previousTurnId = "t1", turnsFromHereCount = 1),
            TranscriptItem.Markdown("t2/m1", "second answer"),
            TranscriptItem.TurnEnd("t2/end", TurnOutcome.DONE, 2_000L, turnId = "t2"),
        )
        show(Transcript("Fix the build", ActivityState.IDLE, null, items, hasOlder = false, lastTurnId = "t2"), actions(calls, canFork = true))

        rule.onNodeWithTag("checkpoint_fork_t2").performScrollTo().performClick()
        rule.waitUntil(5_000) { calls.forkedTurns == listOf("t1") }

        rule.onNodeWithTag("checkpoint_restore_t2").performScrollTo().performClick()
        rule.onNodeWithTag("confirm_truncate_body").assertTextContains("1 turn will be removed", substring = true)
        rule.onNodeWithTag("confirm_truncate_accept").performClick()

        rule.waitUntil(5_000) { calls.restoredCheckpoints == listOf("t2") }
        rule.waitUntil(5_000) { textInBox("second prompt") }

        rule.onNodeWithTag("checkpoint_start_over_t1").performScrollTo().performClick()
        rule.onNodeWithTag("confirm_truncate_body").assertTextContains("2 turns will be removed", substring = true)
        rule.onNodeWithTag("confirm_truncate_accept").performClick()

        rule.waitUntil(5_000) { calls.startOvers == 1 }
    }

    @Test
    fun tappingAUserMessageOpensInlineEditWithSendAndForkAndSend() {
        val calls = Calls()
        val items = listOf(
            TranscriptItem.User("t1/user", "first prompt", turnId = "t1", turnIndex = 0, previousTurnId = null, turnsFromHereCount = 2),
            TranscriptItem.TurnEnd("t1/end", TurnOutcome.DONE, 1_000L, turnId = "t1"),
            TranscriptItem.User("t2/user", "second prompt", turnId = "t2", turnIndex = 1, previousTurnId = "t1", turnsFromHereCount = 1),
            TranscriptItem.TurnEnd("t2/end", TurnOutcome.DONE, 1_000L, turnId = "t2"),
        )
        show(Transcript("Fix the build", ActivityState.IDLE, null, items, hasOlder = false, lastTurnId = "t2"), actions(calls, canFork = true))

        rule.onNodeWithText("second prompt").performScrollTo().performClick()
        rule.onNodeWithTag("user_edit_card_t2").assertIsDisplayed()
        rule.onNodeWithTag("user_edit_input_t2").performTextClearance()
        rule.onNodeWithTag("user_edit_input_t2").performTextInput("updated second")
        rule.onNodeWithTag("user_edit_fork_t2").performClick()

        rule.waitUntil(5_000) { calls.editedForks == listOf("t1" to "updated second") }

        rule.onNodeWithText("second prompt").performScrollTo().performClick()
        rule.onNodeWithTag("user_edit_input_t2").performTextClearance()
        rule.onNodeWithTag("user_edit_input_t2").performTextInput("resent second")
        rule.onNodeWithTag("user_edit_send_t2").performClick()
        rule.onNodeWithTag("confirm_truncate_accept").performClick()

        rule.waitUntil(5_000) { calls.editedResends == listOf("t2" to "resent second") }
    }

    @Test
    fun turnEndOffersResumeWithoutConfirmAndRegenerateForkAndSideChat() {
        val calls = Calls()
        val items = listOf(
            TranscriptItem.User("t1/user", "first prompt", turnId = "t1", turnIndex = 0, previousTurnId = null, turnsFromHereCount = 1),
            TranscriptItem.TurnEnd("t1/end", TurnOutcome.FAILED, 1_000L, turnId = "t1", resumable = true),
        )
        show(Transcript("Fix the build", ActivityState.IDLE, null, items, hasOlder = false, lastTurnId = "t1"), actions(calls, canFork = true, canSideChat = true))

        rule.onNodeWithTag("turn_retry_t1").performClick()
        rule.waitUntil(5_000) { calls.retriedTurns == listOf("t1") }

        rule.onNodeWithTag("turn_fork_t1").performClick()
        rule.waitUntil(5_000) { calls.forkedTurns == listOf("t1") }

        rule.onNodeWithTag("turn_side_chat_t1").performClick()
        rule.waitUntil(5_000) { calls.openedSideChats == listOf("t1" to null) }
    }

    @Test
    fun pendingMessageTrayAndRunningSendOptionsSupportEditRemoveReorderStopAndSendAndSideChat() {
        val calls = Calls()
        val pending = listOf(
            PendingMessageView("s1", "steer next", PendingMessageKind.STEERING),
            PendingMessageView("q1", "queued first", PendingMessageKind.QUEUED),
            PendingMessageView("q2", "queued second", PendingMessageKind.QUEUED),
        )
        show(
            Transcript("Fix the build", ActivityState.IN_PROGRESS, null, emptyList(), hasOlder = false, pendingMessages = pending, activeTurnId = "t1"),
            actions(calls, running = true, canSideChat = true),
        )

        rule.onNodeWithTag("pending_tray").assertIsDisplayed()
        rule.onNodeWithTag("pending_down_q1").performClick()
        rule.waitUntil(5_000) { calls.reorderedQueues == listOf(listOf("q2", "q1")) }

        rule.onNodeWithTag("pending_remove_s1").performClick()
        rule.waitUntil(5_000) { calls.removedPending.contains("s1") }

        rule.onNodeWithTag("pending_edit_q2").performClick()
        rule.waitUntil(5_000) { calls.removedPending.contains("q2") && textInBox("queued second") }

        rule.onNodeWithTag("composer_send_options").performClick()
        rule.onNodeWithTag("composer_mode_stop_and_send").performClick()
        rule.waitUntil(5_000) { calls.stopAndSends == listOf("queued second") }

        rule.onNodeWithTag("composer_input").performTextInput("side question")
        rule.onNodeWithTag("composer_send_options").performClick()
        rule.onNodeWithTag("composer_mode_side_chat").performClick()
        rule.waitUntil(5_000) { calls.openedSideChats == listOf("t1" to "side question") }
    }

    @Test
    fun slashShortcutAndTypingSlashShowMatchingSkillsAndSendInvokesSkill() {
        val calls = Calls()
        val skills = listOf(
            SlashCommand("skill-eval", "Score a skill pack against the quality rubric", argumentHint = "[signals]", kind = SlashCommandKind.SKILL, uri = "file:///s/eval/SKILL.md"),
            SlashCommand("skill-kb", "Build a pack's KB", kind = SlashCommandKind.SKILL, uri = "file:///s/kb/SKILL.md"),
            SlashCommand("clear", "Start over and clear all turns in this chat", kind = SlashCommandKind.BUILT_IN),
        )
        show(transcript(), actions(calls, slashCommands = skills))

        rule.onNodeWithTag("slash_button").performClick()
        rule.onNodeWithTag("composer_slash_menu").assertIsDisplayed()
        rule.onNodeWithTag("slash_option_skill-eval").assertIsDisplayed()
        rule.onNodeWithTag("slash_option_skill-kb").assertIsDisplayed()

        rule.onNodeWithTag("slash_option_skill-eval").performClick()
        rule.onNodeWithTag("composer_active_skill").assertIsDisplayed().assertTextContains("[signals]", substring = true)

        rule.onNodeWithTag("composer_send").performClick()
        rule.waitUntil(5_000) { calls.sent == listOf("/skill-eval") }
    }

    @Test
    fun slashSubsequenceMatchingAndShowAllPickerSheetWorkWithLargeCommandList() {
        val calls = Calls()
        val skills = listOf(
            SlashCommand("pr-watch", "Watch pull request checks", kind = SlashCommandKind.SKILL, uri = "file:///s/pr-watch/SKILL.md", source = "github-pack"),
            SlashCommand("chat-customization-diagnostics", "Runs Chat Customization diagnostics", kind = SlashCommandKind.SKILL, uri = "file:///s/diag/SKILL.md", source = "core-pack"),
        )
        show(transcript(), actions(calls, slashCommands = skills))

        rule.onNodeWithTag("composer_input").performTextInput("/prw")
        rule.onNodeWithTag("composer_slash_menu").assertIsDisplayed()
        rule.onNodeWithTag("slash_option_pr-watch").assertIsDisplayed().assertTextContains("/pr-watch", substring = true)

        rule.onNodeWithTag("composer_show_all_slash_commands").performClick()
        rule.onNodeWithTag("slash_picker_sheet").assertIsDisplayed()
        rule.onNodeWithTag("slash_picker_item_pr-watch").assertIsDisplayed().performClick()
        rule.onNodeWithTag("composer_active_skill").assertIsDisplayed().assertTextContains("/pr-watch", substring = true)
    }

    @Test
    fun customizationsSheetFiltersCategoriesAndInsertsSelectedSkill() {
        val pickedSkill = mutableListOf<String>()
        val view = CustomizationsView(
            listOf(
                CustomizationEntry(
                    id = "skill:1",
                    category = CustomizationCategory.SKILLS,
                    name = "skill-eval",
                    description = "Score a skill pack",
                    uri = "file:///s/eval/SKILL.md",
                    argumentHint = "[signals]",
                ),
                CustomizationEntry(
                    id = "mcp:1",
                    category = CustomizationCategory.MCP_SERVERS,
                    name = "github",
                    statusLabel = "Ready",
                    isReady = true,
                ),
            ),
        )
        rule.setContent {
            AhpTheme {
                CustomizationsSheetContent(
                    customizations = view,
                    onDismiss = {},
                    onUseSlashCommand = { pickedSkill += it },
                )
            }
        }

        rule.onNodeWithTag("customizations_overview_card").assertIsDisplayed()
        rule.onNodeWithTag("customizations_count_SKILLS").assertTextContains("1")
        rule.onNodeWithTag("customizations_count_MCP_SERVERS").assertTextContains("1")

        rule.onNodeWithTag("customization_use_skill_skill-eval").performClick()
        assertEquals(listOf("skill-eval"), pickedSkill)
    }

    @Test
    fun quickReplyBarShowsInAutoModeAndTappingChipFillsComposerInputForEditing() {
        val calls = Calls()
        val items = listOf(
            TranscriptItem.User("t1/user", "Can you update the parser?", turnId = "t1"),
            TranscriptItem.Markdown("t1/m1", "Should I proceed with refactoring the parser?"),
            TranscriptItem.TurnEnd("t1/end", TurnOutcome.DONE, 100L, "t1"),
        )
        show(
            Transcript("Fix parser", ActivityState.IDLE, null, items, hasOlder = false, running = false, lastTurnId = "t1"),
            actions(calls, running = false, quickReplyMode = QuickReplyMode.AUTO),
        )

        rule.onNodeWithTag("quick_reply_bar").assertIsDisplayed()
        rule.onNodeWithTag("quick_reply_chip_0").assertIsDisplayed().performClick()
        rule.waitUntil(5_000) { textInBox("Yes, please proceed.") }
        assertTrue(calls.sent.isEmpty())
    }

    @Test
    fun quickReplyOnDemandModeShowsSuggestButtonAndAiProviderUpgradesSuggestions() {
        val calls = Calls()
        val provider = QuickReplyProvider { ctx ->
            QuickReplySuggestion(
                turnKey = ctx.turnKey,
                source = QuickReplySource.GEMINI_NANO,
                summary = "AI proposed options",
                chips = listOf(QuickReplyChip("Run unit tests", "Please run the unit tests now.")),
            )
        }
        val items = listOf(
            TranscriptItem.Markdown("t1/m1", "I finished updating the parser implementation."),
            TranscriptItem.TurnEnd("t1/end", TurnOutcome.DONE, 100L, "t1"),
        )
        show(
            Transcript("Fix parser", ActivityState.IDLE, null, items, hasOlder = false, running = false, lastTurnId = "t1"),
            actions(calls, running = false, quickReplyMode = QuickReplyMode.ON_DEMAND, quickReplyProvider = provider),
        )

        rule.onNodeWithTag("quick_reply_bar").assertDoesNotExist()
        rule.onNodeWithTag("quick_reply_suggest_button").assertIsDisplayed().performClick()

        rule.onNodeWithTag("quick_reply_bar").assertIsDisplayed()
        rule.onNodeWithTag("quick_reply_summary").assertTextContains("AI proposed options", substring = true)
        rule.onNodeWithTag("quick_reply_chip_0").assertTextContains("Run unit tests", substring = true).performClick()
        rule.waitUntil(5_000) { textInBox("Please run the unit tests now.") }
    }

    @Test
    fun quickReplyBarSurfacesProviderErrorWithoutClaimingGeminiNano() {
        val calls = Calls()
        val provider = QuickReplyProvider { ctx ->
            QuickReplySuggestion(
                turnKey = ctx.turnKey,
                source = QuickReplySource.HEURISTIC,
                providerLabel = "Google Gemini · gemini-3.6-flash",
                error = "Google Gemini · gemini-3.6-flash: models/gemini-3.6-flash is not found for API version v1beta",
                chips = listOf(QuickReplyChip("Yes, proceed", "Yes, please proceed.")),
            )
        }
        val items = listOf(
            TranscriptItem.Markdown("t1/m1", "Should I proceed with refactoring the parser?"),
            TranscriptItem.TurnEnd("t1/end", TurnOutcome.DONE, 100L, "t1"),
        )
        show(
            Transcript("Fix parser", ActivityState.IDLE, null, items, hasOlder = false, running = false, lastTurnId = "t1"),
            actions(calls, running = false, quickReplyMode = QuickReplyMode.AUTO, quickReplyProvider = provider),
        )

        rule.waitUntil(5_000) {
            rule.onAllNodes(androidx.compose.ui.test.hasTestTag("quick_reply_error")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("quick_reply_error")
            .assertIsDisplayed()
            .assertTextContains("gemini-3.6-flash is not found", substring = true)
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    private fun textInBox(text: String): Boolean =
        rule.onNodeWithTag("composer_input").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.EditableText)?.text == text
}
