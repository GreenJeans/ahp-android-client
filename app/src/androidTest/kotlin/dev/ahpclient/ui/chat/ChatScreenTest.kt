package dev.ahpclient.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ahpclient.state.ActivityState
import dev.ahpclient.state.ChatGoneException
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.InputRequestView
import dev.ahpclient.state.ToolState
import dev.ahpclient.state.Transcript
import dev.ahpclient.state.TranscriptItem
import dev.ahpclient.ui.theme.AhpTheme
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The transcript screen as a pure function of what the store reported, and how it behaves as rows arrive and links are tapped. */
@RunWith(AndroidJUnit4::class)
class ChatScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private fun user(n: Int, text: String = "message $n") = TranscriptItem.User("u$n", text)

    private fun rows(range: IntRange) = range.map { user(it) }

    private fun transcript(items: List<TranscriptItem>, hasOlder: Boolean = false, activity: String? = null) =
        Transcript("Fix the build", ActivityState.IN_PROGRESS, activity, items, hasOlder)

    private fun show(
        transcript: Transcript?,
        error: Throwable? = null,
        loadingOlder: Boolean = false,
        olderError: Throwable? = null,
        connection: ConnState = ConnState.Online,
        onBack: () -> Unit = {},
        onLoadOlder: () -> Unit = {},
        onOpenLink: (String) -> Unit = {},
    ) = rule.setContent { AhpTheme { ChatScreen(transcript, error, loadingOlder, olderError, connection, onBack, onLoadOlder, onOpenLink) } }

    private fun y(text: String): Float = rule.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    /** The font size, in sp, the first line of the node's text was laid out with. */
    private fun fontSizeOf(text: String): Float {
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.first().layoutInput.style.fontSize.value
    }

    private fun awaitText(text: String) = rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    // ---- what is shown -----------------------------------------------------------------------------------------

    @Test
    fun theHeaderNamesTheChatAndItsState() {
        show(transcript(rows(1..2), activity = "Running tests"))

        rule.onNodeWithTag("chat_title").assertTextEquals("Fix the build")
        rule.onNodeWithTag("status_chip", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag("chat_activity").assertTextEquals("Running tests")
    }

    @Test
    fun rowsReadInOrderWithTheNewestAtTheBottom() {
        show(
            transcript(
                listOf(
                    TranscriptItem.User("u1", "first question"),
                    TranscriptItem.Reasoning("r1", "thinking", live = false),
                    TranscriptItem.Tool("t1", "Run in Terminal", "List the files", ToolState.DONE),
                    TranscriptItem.Notice("n1", "Context was compacted"),
                    TranscriptItem.User("u2", "second question"),
                ),
            ),
        )

        val order = listOf("first question", "Thought", "Run in Terminal", "Context was compacted", "second question").map(::y)
        assertEquals("rows are not in reading order: $order", order.sorted(), order)
    }

    @Test
    fun aToolRowStatesItsStateInWordsAndItsPurpose() {
        show(
            transcript(
                listOf(
                    TranscriptItem.Tool("t1", "Run in Terminal", "List the files", ToolState.WAITING_FOR_APPROVAL),
                    TranscriptItem.Tool("t2", "Read file", null, ToolState.FAILED),
                ),
            ),
        )

        rule.onNode(hasTestTag("row_tool") and hasText("Run in Terminal")).assertTextContains("Waiting for approval in VS Code", substring = true)
        rule.onNode(hasTestTag("row_tool") and hasText("Run in Terminal")).assertTextContains("List the files", substring = true)
        rule.onNode(hasTestTag("row_tool") and hasText("Read file")).assertTextContains("Failed", substring = true)
        rule.onAllNodesWithTag("tool_badge", useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun thinkingIsAnnouncedWhileLiveAndPastTenseAfter() {
        show(transcript(listOf(TranscriptItem.Reasoning("r1", "x", live = true), TranscriptItem.Reasoning("r2", "y", live = false))))

        rule.onNodeWithText("Thinking…").assertIsDisplayed()
        rule.onNodeWithText("Thought").assertIsDisplayed()
    }

    @Test
    fun aQuestionShowsItsChoicesAndWhetherItWasAnswered() {
        show(
            transcript(
                listOf(
                    TranscriptItem.Question("q1", InputRequestView("Question", "Which season?", emptyList()), answered = false),
                    TranscriptItem.Question("q2", InputRequestView("Question", "Which color?", emptyList()), answered = true),
                ),
            ),
        )

        val notes = rule.onAllNodesWithTag("row_question_note").fetchSemanticsNodes().map { it.config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text }
        assertEquals(setOf("Answer in VS Code.", "Answered."), notes.toSet())
        rule.onNodeWithText("Which season?", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Which color?", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun anErrorNoticeAndAnUnknownPartAreBothShown() {
        show(transcript(listOf(TranscriptItem.Notice("n1", "Ended with an error", isError = true), TranscriptItem.Other("o1", "Content this app cannot show"))))

        rule.onNodeWithTag("row_notice").assertTextEquals("Ended with an error")
        rule.onNodeWithTag("row_other").assertTextEquals("Content this app cannot show")
    }

    @Test
    fun markdownIsRenderedNotShownAsMarkup() {
        show(transcript(listOf(TranscriptItem.Markdown("m1", "# Plan\n\nRun the **build** first.\n\n- one\n- two\n\n```kotlin\nval x = 1\n```"))))

        awaitText("Plan")
        rule.onAllNodesWithText("# Plan", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("**", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("```", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("val x = 1", substring = true).assertCountEquals(1)
    }

    @Test
    fun headingsUseTheTitleSizesNotTheDisplayOnes() {
        show(transcript(listOf(TranscriptItem.Markdown("m1", "# Plan\n\n## Steps\n\nbody text"))))
        awaitText("Plan")

        assertTrue("an h1 is ${fontSizeOf("Plan")} sp", fontSizeOf("Plan") <= 24f)
        assertTrue("an h2 is ${fontSizeOf("Steps")} sp", fontSizeOf("Steps") <= 20f)
    }

    @Test
    fun aLongLineOfCodeScrollsSidewaysInsteadOfWrappingOrBeingLost() {
        show(transcript(listOf(TranscriptItem.Markdown("m1", "before\n\n```\n" + "x".repeat(300) + "\n```\n\nafter"))))
        awaitText("after")

        val ranges = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            .fetchSemanticsNodes().map { it.config[SemanticsProperties.HorizontalScrollAxisRange] }
        assertTrue("no horizontally scrollable code block: ${ranges.map { it.maxValue() }}", ranges.any { it.maxValue() > 0f })
    }

    @Test
    fun htmlAndImagesInAgentTextAreInertAndDoNotStopTheRestRendering() {
        show(
            transcript(
                listOf(TranscriptItem.Markdown("m1", "<script>alert(1)</script>\n\n<img src=\"https://example.invalid/a.png\">\n\n![tracker](https://example.invalid/pixel.png)\n\nthe end")),
            ),
        )

        awaitText("the end")
        rule.onAllNodesWithTag("link_dialog").assertCountEquals(0)
    }

    @Test
    fun aChatThatHasNotArrivedYetSaysItIsLoading() {
        show(null)
        rule.onNodeWithTag("chat_loading").assertIsDisplayed()
    }

    @Test
    fun aChatTheHostNoLongerHasSaysSo() {
        show(null, error = ChatGoneException("ahp-chat:/x"))

        rule.onNodeWithTag("chat_gone").assertIsDisplayed()
    }

    @Test
    fun aChatThatCouldNotBeLoadedNamesTheFailureTypeOnly() {
        show(null, error = IllegalStateException("secret detail"))

        rule.onNodeWithTag("chat_error").assertTextContains("IllegalStateException", substring = true)
        rule.onAllNodesWithText("secret detail", substring = true).assertCountEquals(0)
    }

    @Test
    fun aChatWithNothingYetSaysSo() {
        show(transcript(emptyList()))

        rule.onNodeWithTag("chat_empty").assertIsDisplayed()
    }

    @Test
    fun aLostConnectionIsShownAboveTheTranscript() {
        show(transcript(rows(1..3)), connection = ConnState.Reconnecting(2, null))

        rule.onNodeWithTag("connection_status").assertTextContains("Reconnecting", substring = true)
        rule.onNodeWithText("message 3").assertIsDisplayed()
    }

    @Test
    fun backGoesBack() {
        var back = 0
        show(transcript(rows(1..2)), onBack = { back++ })

        rule.onNodeWithTag("back").performClick()

        assertEquals(1, back)
    }

    @Test
    fun theBackButtonNamesWhereItGoes() {
        show(transcript(rows(1..2)))
        rule.onNodeWithTag("back").assertTextEquals("Back to session")
    }

    @Test
    fun insideASubagentTranscriptBackSaysItReturnsToTheChat() {
        var back = 0
        rule.setContent { AhpTheme { ChatScreen(transcript(rows(1..2)), null, false, null, ConnState.Online, { back++ }, {}, {}, backLabel = "Back to chat") } }

        rule.onNodeWithTag("back").assertTextEquals("Back to chat").performClick()

        assertEquals(1, back)
    }

    // ---- links -------------------------------------------------------------------------------------------------

    private fun showLink(markdown: String, onOpenLink: (String) -> Unit = {}) {
        show(transcript(listOf(TranscriptItem.Markdown("m1", markdown))), onOpenLink = onOpenLink)
        awaitText("docs")
        rule.onNodeWithText("docs").performClick()
    }

    @Test
    fun tappingALinkAsksFirstAndNamesTheRealHost() {
        val opened = mutableListOf<String>()
        showLink("[docs](https://example.com/docs?page=1)", opened::add)

        rule.onNodeWithTag("link_dialog").assertIsDisplayed()
        rule.onNodeWithTag("link_host").assertTextEquals("example.com")
        rule.onNodeWithTag("link_url").assertTextContains("https://example.com/docs?page=1")
        assertEquals("nothing opens before the user says yes", emptyList<String>(), opened)
    }

    @Test
    fun confirmingOpensExactlyThatLinkAndClosesTheDialog() {
        val opened = mutableListOf<String>()
        showLink("[docs](https://example.com/docs)", opened::add)

        rule.onNodeWithTag("link_open").performClick()
        rule.waitForIdle()

        assertEquals(listOf("https://example.com/docs"), opened)
        rule.onAllNodesWithTag("link_dialog").assertCountEquals(0)
    }

    @Test
    fun cancellingOpensNothing() {
        val opened = mutableListOf<String>()
        showLink("[docs](https://example.com/docs)", opened::add)

        rule.onNodeWithTag("link_cancel").performClick()
        rule.waitForIdle()

        assertTrue(opened.isEmpty())
        rule.onAllNodesWithTag("link_dialog").assertCountEquals(0)
    }

    @Test
    fun aTextThatSaysOneSiteWhilePointingAtAnotherShowsTheRealOne() {
        showLink("[docs](https://evil.example.net/login)")

        rule.onNodeWithTag("link_host").assertTextEquals("evil.example.net")
    }

    @Test
    fun linksThatAreNotPlainHttpsAreExplainedAndCanNeverBeOpened() {
        val targets = listOf("javascript:alert(1)", "http://example.com/x", "intent://x#Intent;scheme=https;end", "file:///sdcard/a.txt", "https://user:pw@example.com/")
        val opened = mutableListOf<String>()
        show(transcript(targets.mapIndexed { i, target -> TranscriptItem.Markdown("m$i", "[bad$i]($target)") }), onOpenLink = opened::add)

        targets.indices.forEach { i ->
            awaitText("bad$i")
            rule.onNodeWithText("bad$i").performClick()

            rule.onNodeWithTag("link_dialog").assertIsDisplayed()
            rule.onAllNodesWithTag("link_open").assertCountEquals(0)
            rule.onAllNodesWithTag("link_host").assertCountEquals(0)
            rule.onNodeWithTag("link_cancel").assertTextEquals("OK").performClick()
            rule.waitForIdle()
        }

        assertTrue("opened: $opened", opened.isEmpty())
    }

    // ---- older turns -------------------------------------------------------------------------------------------

    @Test
    fun whenOlderTurnsExistThereIsAButtonToAskForThem() {
        var asked = 0
        show(transcript(rows(1..3), hasOlder = true), onLoadOlder = { asked++ })

        rule.onNodeWithTag("older_button").performClick()

        assertTrue("asked $asked times", asked >= 1)
    }

    @Test
    fun nothingAsksForOlderTurnsWhenThereAreNone() {
        show(transcript(rows(1..3), hasOlder = false))

        rule.onAllNodesWithTag("older_button").assertCountEquals(0)
        rule.onAllNodesWithTag("older_loading").assertCountEquals(0)
    }

    @Test
    fun loadingOlderTurnsIsShownAtTheTop() {
        show(transcript(rows(1..3), hasOlder = true), loadingOlder = true)

        rule.onNodeWithTag("older_loading").assertIsDisplayed()
        rule.onAllNodesWithTag("older_button").assertCountEquals(0)
    }

    @Test
    fun aFailureLoadingOlderTurnsOffersARetry() {
        var asked = 0
        show(transcript(rows(1..3), hasOlder = true), olderError = IOException("offline"), onLoadOlder = { asked++ })

        rule.onNodeWithTag("older_error").assertIsDisplayed()
        rule.onNodeWithTag("older_retry").performClick()

        assertTrue(asked >= 1)
    }

    @Test
    fun scrollingToTheOldestRowAsksForMoreOnceAndNotBefore() {
        var asked = 0
        show(transcript(rows(1..120), hasOlder = true), onLoadOlder = { asked++ })
        rule.waitForIdle()
        assertEquals("nothing is asked for while the newest rows are on screen", 0, asked)

        rule.onNodeWithTag("transcript").performScrollToIndex(120)
        rule.waitForIdle()

        assertEquals(1, asked)
    }

    // ---- scrolling ---------------------------------------------------------------------------------------------

    @Test
    fun aLongChatOpensAtItsLatestMessage() {
        show(transcript(rows(1..120)))

        rule.onNodeWithText("message 120").assertIsDisplayed()
        rule.onAllNodesWithText("message 1").assertCountEquals(0)
        rule.onAllNodesWithTag("jump_latest").assertCountEquals(0)
    }

    @Test
    fun aNewRowKeepsTheLatestInViewWhileTheUserIsAtTheBottom() {
        var current by mutableStateOf(transcript(rows(1..40)))
        rule.setContent { AhpTheme { ChatScreen(current, null, false, null, ConnState.Online, {}, {}, {}) } }
        rule.onNodeWithText("message 40").assertIsDisplayed()

        rule.runOnIdle { current = transcript(rows(1..41)) }
        rule.waitForIdle()

        rule.onNodeWithText("message 41").assertIsDisplayed()
        rule.onAllNodesWithTag("jump_latest").assertCountEquals(0)
    }

    @Test
    fun aNewRowDoesNotMoveAUserWhoScrolledUp_andAButtonBringsThemBack() {
        var current by mutableStateOf(transcript(rows(1..120)))
        rule.setContent { AhpTheme { ChatScreen(current, null, false, null, ConnState.Online, {}, {}, {}) } }
        rule.onNodeWithTag("transcript").performScrollToIndex(60)
        rule.waitForIdle()
        val before = y("message 60")
        rule.onNodeWithTag("jump_latest").assertIsDisplayed()

        rule.runOnIdle { current = transcript(rows(1..121)) }
        rule.waitForIdle()

        assertEquals("the row the user was reading moved", before, y("message 60"), 1f)
        rule.onAllNodesWithText("message 121").assertCountEquals(0)
        rule.onNodeWithTag("jump_latest").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("message 121").assertIsDisplayed()
        rule.onAllNodesWithTag("jump_latest").assertCountEquals(0)
    }

    @Test
    fun aStreamingRowBelowWhatTheUserIsReadingDoesNotMoveIt() {
        var current by mutableStateOf(transcript(rows(1..120)))
        rule.setContent { AhpTheme { ChatScreen(current, null, false, null, ConnState.Online, {}, {}, {}) } }
        rule.onNodeWithTag("transcript").performScrollToIndex(60)
        rule.waitForIdle()
        val before = y("message 60")

        for (words in listOf(50, 200, 600)) {
            val longer = rows(1..119) + user(120, "message 120 " + "word ".repeat(words))
            rule.runOnIdle { current = transcript(longer) }
            rule.waitForIdle()
        }

        assertEquals(before, y("message 60"), 1f)
    }

    @Test
    fun theUserStaysAtTheBottomWhileTheNewestRowKeepsGrowing() {
        var current by mutableStateOf(transcript(rows(1..40)))
        rule.setContent { AhpTheme { ChatScreen(current, null, false, null, ConnState.Online, {}, {}, {}) } }

        for (words in listOf(30, 100, 300)) {
            rule.runOnIdle { current = transcript(rows(1..39) + user(40, "message 40 " + "word ".repeat(words))) }
            rule.waitForIdle()
        }

        rule.onAllNodesWithTag("jump_latest").assertCountEquals(0)
        rule.onNodeWithTag("transcript").assertIsDisplayed()
    }
}
