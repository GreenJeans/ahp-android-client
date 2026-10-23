package dev.ahpclient.ui.chat

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollTo
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
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.DispatchOutcome
import dev.ahpclient.state.FormOption
import dev.ahpclient.state.FormQuestion
import dev.ahpclient.state.InputForm
import dev.ahpclient.state.InputRequestView
import dev.ahpclient.state.InteractResult
import dev.ahpclient.state.QuestionKind
import dev.ahpclient.state.ToolState
import dev.ahpclient.state.Transcript
import dev.ahpclient.state.TranscriptItem
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
        var cancels = 0
        val approved = mutableListOf<Pair<String, String?>>()
        val denied = mutableListOf<Pair<String, String?>>()
        val answered = mutableListOf<Pair<String, Map<String, AnswerValue>>>()
        val declined = mutableListOf<String>()
        val dismissed = mutableListOf<String>()
        var result: InteractResult = InteractResult.Sent(DispatchOutcome.Accepted)
    }

    private fun actions(calls: Calls, running: Boolean = false) = ChatActionsUi(
        running = running,
        send = { calls.sent += it; calls.result },
        cancel = { calls.cancels++; calls.result },
        approve = { id, option -> calls.approved += id to option; calls.result },
        deny = { id, option -> calls.denied += id to option; calls.result },
        answer = { id, answers -> calls.answered += id to answers; calls.result },
        decline = { calls.declined += it; calls.result },
        dismiss = { calls.dismissed += it; calls.result },
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

    private fun question(form: InputForm?) =
        TranscriptItem.Question("q1", InputRequestView("Question", "Which season?"), answered = false, form = form)

    private fun form(vararg questions: FormQuestion) = InputForm("req-1", null, questions.toList())

    private fun text(id: String = "a", required: Boolean = true) = FormQuestion(id, "Your name", QuestionKind.TEXT, required)

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
        rule.onNodeWithText("Send").assertIsDisplayed()
    }

    @Test
    fun whileTheAgentWorksSendSaysQueueAndStopStopsIt() {
        val calls = Calls()
        show(transcript(), actions(calls, running = true))

        rule.onNodeWithText("Queue").assertIsDisplayed()
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
        rule.onNodeWithTag("option_m_g").performScrollTo().performClick()
        rule.onNodeWithTag("option_m_u").performScrollTo().performClick()
        rule.onNodeWithTag("question_submit").performScrollTo().performClick()

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
        rule.onNodeWithTag("row_question_note").assertTextContains("Answer in VS Code", substring = true)
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    private fun textInBox(text: String): Boolean =
        rule.onNodeWithTag("composer_input").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.EditableText)?.text == text
}
