package dev.ahpclient.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
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
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.center
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ahpclient.state.ActivityState
import dev.ahpclient.state.BackgroundWorkKindView
import dev.ahpclient.state.BackgroundWorkView
import dev.ahpclient.state.CanvasView
import dev.ahpclient.state.ChatGoneException
import dev.ahpclient.state.ChatNotLoadedException
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.InputRequestView
import dev.ahpclient.state.TextScale
import dev.ahpclient.state.ToolState
import dev.ahpclient.state.Transcript
import dev.ahpclient.state.TranscriptItem
import dev.ahpclient.state.TurnOutcome
import dev.ahpclient.state.TurnUsage
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

    private fun transcript(items: List<TranscriptItem>, hasOlder: Boolean = false, activity: String? = null, running: Boolean = false) =
        Transcript("Fix the build", ActivityState.IN_PROGRESS, activity, items, hasOlder, running)

    private fun show(
        transcript: Transcript?,
        error: Throwable? = null,
        loadingOlder: Boolean = false,
        olderError: Throwable? = null,
        connection: ConnState = ConnState.Online,
        onBack: () -> Unit = {},
        onLoadOlder: () -> Unit = {},
        onOpenLink: (String) -> Unit = {},
        onShareLink: (String) -> Unit = {},
    ) = rule.setContent { AhpTheme { ChatScreen(transcript, error, loadingOlder, olderError, connection, onBack, onLoadOlder, onOpenLink, onShareLink = onShareLink) } }

    private fun y(text: String): Float = rule.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    /** The font size, in sp, the first line of the node's text was laid out with. */
    private fun fontSizeOf(text: String): Float {
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.first().layoutInput.style.fontSize.value
    }

    /** The font scale the first line of the node's text was laid out with: what the person's text size multiplies. */
    private fun fontScaleOf(text: String): Float {
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        return layouts.first().layoutInput.density.fontScale
    }

    /** How many parts of the node's text are drawn and handled as links. */
    private fun linksIn(text: String): Int {
        val annotated = rule.onNodeWithText(text, substring = true).fetchSemanticsNode().config[SemanticsProperties.Text].single()
        return annotated.getLinkAnnotations(0, annotated.length).size
    }

    private fun awaitText(text: String) = rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    // ---- what is shown -----------------------------------------------------------------------------------------

    @Test
    fun theHeaderNamesTheChatAndItsState() {
        show(transcript(rows(1..2), activity = "Running tests"))

        rule.onNodeWithTag("chat_title").assertTextEquals("Fix the build")
        rule.onNodeWithTag("subtitle").assertIsDisplayed()
        rule.onAllNodesWithTag("chat_activity").assertCountEquals(0)
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

        val order = listOf("first question", "Used 1 tool", "Context was compacted", "second question").map(::y)
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

        rule.onNode(hasTestTag("row_tool") and hasText("Run in Terminal", substring = true)).assertTextContains("Waiting for approval in VS Code", substring = true)
        rule.onNode(hasTestTag("row_tool") and hasText("Run in Terminal", substring = true)).assertTextContains("List the files", substring = true)
        rule.onNode(hasTestTag("row_tool") and hasText("Read file", substring = true)).assertTextContains("Failed", substring = true)
        rule.onAllNodesWithTag("tool_badge", useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun thinkingIsAnnouncedWhileLiveAndPastTenseAfter() {
        show(transcript(listOf(TranscriptItem.Reasoning("r1", "x", live = true), TranscriptItem.Markdown("m1", "between"), TranscriptItem.Reasoning("r2", "y", live = false))))

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
        rule.onAllNodesWithTag("chat_loading_slow").assertCountEquals(0)
    }

    @Test
    fun aChatThatIsStillLoadingAfterAFewSecondsSaysWhyItTakesAWhile() {
        rule.mainClock.autoAdvance = false
        show(null)
        rule.mainClock.advanceTimeBy(2_000)
        rule.onAllNodesWithTag("chat_loading_slow").assertCountEquals(0)

        rule.mainClock.advanceTimeBy(5_000)

        rule.onNodeWithTag("chat_loading_slow").assertTextContains("long", substring = true)
        rule.onNodeWithTag("chat_loading").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertTouchTargetAtLeast(minimum: androidx.compose.ui.unit.Dp): androidx.compose.ui.test.SemanticsNodeInteraction {
        val bounds = fetchSemanticsNode().touchBoundsInRoot
        val minimumPx = with(rule.density) { minimum.toPx() }
        assertTrue("touch target is ${bounds.width} x ${bounds.height} px, smaller than $minimum", bounds.height + 0.5f >= minimumPx && bounds.width + 0.5f >= minimumPx)
        return this
    }

    private fun showNotLoaded(
        failure: ChatNotLoadedException,
        transcript: Transcript? = null,
        onRetry: (() -> Unit)? = {},
    ) = rule.setContent {
        AhpTheme { ChatScreen(transcript, failure, false, null, ConnState.Online, {}, {}, {}, onRetry = onRetry) }
    }

    @Test
    fun aChatTheHostWasTooSlowToSendSaysSoAndOffersToTryAgain() {
        var retries = 0
        showNotLoaded(ChatNotLoadedException("ahp-chat:/x", interrupted = false), onRetry = { retries++ })

        rule.onNodeWithTag("chat_not_loaded_text").assertTextContains("took too long", substring = true)
        rule.onNodeWithTag("chat_retry").assertIsDisplayed().assertTouchTargetAtLeast(48.dp).performClick()

        assertEquals(1, retries)
    }

    @Test
    fun aChatLostToDroppedConnectionsSaysThatInstead() {
        showNotLoaded(ChatNotLoadedException("ahp-chat:/x", interrupted = true))

        rule.onNodeWithTag("chat_not_loaded_text").assertTextContains("kept dropping", substring = true)
    }

    @Test
    fun withNoWayToRetryThereIsNoButton() {
        showNotLoaded(ChatNotLoadedException("ahp-chat:/x", interrupted = false), onRetry = null)

        rule.onNodeWithTag("chat_not_loaded").assertIsDisplayed()
        rule.onAllNodesWithTag("chat_retry").assertCountEquals(0)
    }

    @Test
    fun aChatThatStoppedUpdatingKeepsWhatItShowedAndSaysItIsOutOfDate() {
        showNotLoaded(ChatNotLoadedException("ahp-chat:/x", interrupted = true), transcript = transcript(rows(1..3)))

        rule.onNodeWithTag("chat_not_loaded_text").assertTextContains("not being kept up to date", substring = true)
        rule.onNodeWithTag("chat_retry").assertIsDisplayed()
        rule.onNodeWithText("message 3").assertIsDisplayed()
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
        rule.onNodeWithTag("back").assertContentDescriptionEquals("Back to session")
    }

    @Test
    fun insideASubagentTranscriptBackSaysItReturnsToTheChat() {
        var back = 0
        rule.setContent { AhpTheme { ChatScreen(transcript(rows(1..2)), null, false, null, ConnState.Online, { back++ }, {}, {}, backLabel = "Back to chat") } }

        rule.onNodeWithTag("back").assertContentDescriptionEquals("Back to chat").performClick()

        assertEquals(1, back)
    }

    // ---- links -------------------------------------------------------------------------------------------------

    private fun showLink(markdown: String, onOpenLink: (String) -> Unit = {}, onShareLink: (String) -> Unit = {}) {
        show(transcript(listOf(TranscriptItem.Markdown("m1", markdown))), onOpenLink = onOpenLink, onShareLink = onShareLink)
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
    fun aWebLinkOffersShareAndHandsOverTheFullAddressWithoutOpeningIt() {
        val opened = mutableListOf<String>()
        val shared = mutableListOf<String>()
        showLink("[docs](https://example.com/docs?page=1&q=a)", opened::add, shared::add)

        rule.onNodeWithTag("link_share").assertIsDisplayed().performClick()
        rule.waitForIdle()

        assertEquals(listOf("https://example.com/docs?page=1&q=a"), shared)
        assertTrue("sharing does not open the link", opened.isEmpty())
        rule.onAllNodesWithTag("link_dialog").assertCountEquals(0)
    }

    @Test
    fun aWebLinkHasNoShareWhenTheCallerGivesNoWayToShare() {
        val transcript = transcript(listOf(TranscriptItem.Markdown("m1", "[docs](https://example.com/docs)")))
        rule.setContent { AhpTheme { ChatScreen(transcript, null, false, null, ConnState.Online, {}, {}, {}) } }
        awaitText("docs")
        rule.onNodeWithText("docs").performClick()

        rule.onNodeWithTag("link_dialog").assertIsDisplayed()
        rule.onNodeWithTag("link_open").assertIsDisplayed()
        rule.onAllNodesWithTag("link_share").assertCountEquals(0)
    }

    @Test
    fun aMailLinkHasNoShare() {
        showLink("[docs](mailto:a@example.com)")

        rule.onNodeWithTag("link_dialog").assertIsDisplayed()
        rule.onAllNodesWithTag("link_share").assertCountEquals(0)
    }

    @Test
    fun aPhoneLinkHasNoShare() {
        showLink("[docs](tel:+15550100)")

        rule.onNodeWithTag("link_dialog").assertIsDisplayed()
        rule.onAllNodesWithTag("link_share").assertCountEquals(0)
    }

    @Test
    fun aTextThatSaysOneSiteWhilePointingAtAnotherShowsTheRealOne() {
        showLink("[docs](https://evil.example.net/login)")

        rule.onNodeWithTag("link_host").assertTextEquals("evil.example.net")
    }

    @Test
    fun linksThatCannotBeOpenedOnAPhoneAreShownAsPlainTextAndNeverAskToOpen() {
        val targets = listOf("javascript:alert(1)", "http://example.com/x", "intent://x#Intent;scheme=https;end", "file:///sdcard/a.txt", "/home/me/repo/Main.kt", "src/Main.kt", "vscode://file/x", "******example.com/")
        val opened = mutableListOf<String>()
        show(transcript(targets.mapIndexed { i, target -> TranscriptItem.Markdown("m$i", "See [bad$i]($target) here") }), onOpenLink = opened::add)

        targets.indices.forEach { i ->
            rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodesWithText("See bad$i here", substring = true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("bad$i is drawn as a link", 0, linksIn("See bad$i here"))
            rule.onNodeWithText("See bad$i here", substring = true).performClick()
            rule.waitForIdle()

            rule.onAllNodesWithTag("link_dialog").assertCountEquals(0)
        }

        rule.onAllNodesWithText("](", substring = true).assertCountEquals(0)
        assertTrue("opened: $opened", opened.isEmpty())
    }

    @Test
    fun linksThatOpenAreStillDrawnAsLinks() {
        show(transcript(listOf(TranscriptItem.Markdown("m1", "A [web](https://example.com/a), a [mail](mailto:a@example.com) and a [call](tel:+15550100) link"))))
        awaitText("A web, a mail and a call link")

        assertEquals(3, linksIn("A web, a mail and a call link"))
    }

    @Test
    fun aMailLinkAsksWhoItWritesToAndOpensOnlyTheAddress() {
        val opened = mutableListOf<String>()
        showLink("[docs](mailto:a@example.com?subject=hi&body=secret)", opened::add)

        rule.onNodeWithText("Write to?").assertIsDisplayed()
        rule.onNodeWithTag("link_host").assertTextEquals("a@example.com")
        rule.onAllNodesWithTag("link_url").assertCountEquals(0)
        rule.onNodeWithTag("link_open").performClick()

        assertEquals(listOf("mailto:a@example.com"), opened)
    }

    @Test
    fun aPhoneLinkAsksWhichNumberItDialsAndHandsOverThatNumber() {
        val opened = mutableListOf<String>()
        showLink("[docs](tel:+15550100)", opened::add)

        rule.onNodeWithText("Dial?").assertIsDisplayed()
        rule.onNodeWithTag("link_host").assertTextEquals("+15550100")
        rule.onNodeWithTag("link_open").performClick()

        assertEquals(listOf("tel:+15550100"), opened)
    }

    @Test
    fun aLinkDialogSurvivesTheAppBeingRecreated() {
        val restorer = StateRestorationTester(rule)
        restorer.setContent { AhpTheme { ChatScreen(transcript(listOf(TranscriptItem.Markdown("m1", "[docs](https://example.com/docs)"))), null, false, null, ConnState.Online, {}, {}, {}) } }
        awaitText("docs")
        rule.onNodeWithText("docs").performClick()
        rule.onNodeWithTag("link_dialog").assertIsDisplayed()

        restorer.emulateSavedInstanceStateRestore()

        rule.onNodeWithTag("link_dialog").assertIsDisplayed()
        rule.onNodeWithTag("link_host").assertTextEquals("example.com")
    }

    // ---- bubbles, working, stopped -----------------------------------------------------------------------------

    @Test
    fun eachThingTheAgentWroteIsABubbleOfItsOwn() {
        show(transcript(listOf(TranscriptItem.Markdown("m1", "first answer"), TranscriptItem.Markdown("m2", "second answer"))))
        awaitText("second answer")

        rule.onAllNodesWithTag("row_markdown").assertCountEquals(2)
    }

    @Test
    fun aFinishedTurnEndsWithHowItEndedAndHowLongItTook() {
        show(
            transcript(
                listOf(
                    TranscriptItem.TurnEnd("e1", TurnOutcome.DONE, 64_000),
                    TranscriptItem.TurnEnd("e2", TurnOutcome.STOPPED, null),
                    TranscriptItem.TurnEnd("e3", TurnOutcome.FAILED, 4_000),
                ),
            ),
        )

        val words = rule.onAllNodesWithTag("turn_end_text", useUnmergedTree = true).fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].single().text }
        assertEquals(setOf("Done \u00b7 1m 4s", "Stopped", "Failed \u00b7 4s"), words.toSet())
        rule.onAllNodesWithTag("row_working").assertCountEquals(0)
    }

    @Test
    fun backgroundWorkAndCanvasesBarsRenderAndOpenSubagentOrTerminal() {
        val openedTerminals = mutableListOf<String>()
        val openedSubagents = mutableListOf<String>()
        val t = Transcript(
            title = "Fix the build",
            state = ActivityState.IN_PROGRESS,
            activity = null,
            items = rows(1..1),
            hasOlder = false,
            backgroundWork = listOf(
                BackgroundWorkView("bw1", BackgroundWorkKindView.SHELL, "Dev server", command = "npm start", terminalUri = "ahp-terminal:/t1"),
                BackgroundWorkView("bw2", BackgroundWorkKindView.SUBAGENT, "Code reviewer", chatUri = "ahp-chat:/sub1"),
            ),
            canvases = listOf(CanvasView("ahp-canvas:/s1/arch-diagram", "arch-diagram")),
        )
        rule.setContent {
            AhpTheme {
                ChatScreen(
                    transcript = t,
                    error = null,
                    loadingOlder = false,
                    olderError = null,
                    connection = ConnState.Online,
                    onBack = {},
                    onLoadOlder = {},
                    onOpenLink = {},
                    onOpenSubagent = { openedSubagents += it },
                    onOpenTerminal = { openedTerminals += it },
                )
            }
        }

        rule.onNodeWithTag("background_work_bar").assertIsDisplayed()
        rule.onNodeWithTag("background_work_bw1").assertIsDisplayed()
        rule.onNodeWithTag("background_work_bw2").assertIsDisplayed()
        rule.onNodeWithTag("background_work_open_bw1").performClick()
        rule.onNodeWithTag("background_work_open_bw2").performClick()
        assertEquals(listOf("ahp-terminal:/t1"), openedTerminals)
        assertEquals(listOf("ahp-chat:/sub1"), openedSubagents)

        rule.onNodeWithTag("canvases_bar").assertIsDisplayed()
        rule.onNodeWithTag("canvases_text").assertTextEquals("arch-diagram")
    }

    @Test
    fun aFinishedTurnShowsTokenUsageAndCostWhenReported() {
        show(
            transcript(
                listOf(
                    TranscriptItem.TurnEnd(
                        key = "e1",
                        outcome = TurnOutcome.DONE,
                        durationMs = 41_000,
                        usage = TurnUsage(inputTokens = 10_000L, outputTokens = 2_400L, costAmount = 0.04),
                    ),
                ),
            ),
        )

        rule.onNodeWithTag("turn_end_text", useUnmergedTree = true).assertTextEquals("Done \u00b7 41s \u00b7 12.4k tok \u00b7 $0.04")
    }

    @Test
    fun whileATurnRunsTheEndOfTheChatPulsesWithWhatTheAgentIsDoing() {
        show(transcript(rows(1..2), activity = "Running tests", running = true))

        rule.onNodeWithTag("working_text", useUnmergedTree = true).assertTextEquals("Running tests")
        rule.onAllNodesWithTag("working_dots", useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodesWithTag("chat_progress", useUnmergedTree = true).assertCountEquals(1)
        assertTrue("the working row is below the newest message", rule.onNodeWithTag("row_working").fetchSemanticsNode().positionInRoot.y > y("message 2"))
    }

    @Test
    fun withNoActivityTheWorkingRowStillSaysWorking() {
        show(transcript(rows(1..2), running = true))

        rule.onNodeWithTag("working_text", useUnmergedTree = true).assertTextEquals("Working\u2026")
    }

    @Test
    fun whenTheTurnIsHeldUpOnThePersonItStopsPulsingAndSaysSo() {
        val waiting = TranscriptItem.Tool("t1", "Run in Terminal", "List the files", ToolState.WAITING_FOR_APPROVAL)
        show(transcript(listOf(user(1), waiting), activity = "Running tests", running = true))

        rule.onNodeWithTag("working_text", useUnmergedTree = true).assertTextEquals("Waiting for you")
        rule.onAllNodesWithTag("working_dots", useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithTag("chat_progress", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun whenNoTurnIsRunningThereIsNoWorkingRowAndNoBar() {
        show(transcript(listOf(user(1), TranscriptItem.TurnEnd("e1", TurnOutcome.DONE, 1_000)), activity = "Running tests"))

        rule.onAllNodesWithTag("row_working").assertCountEquals(0)
        rule.onAllNodesWithTag("chat_progress", useUnmergedTree = true).assertCountEquals(0)
    }

    // ---- text size ---------------------------------------------------------------------------------------------

    private fun showPinchable(initial: Float = 1f, onScale: (Float) -> Unit = {}) = rule.setContent {
        var scale by androidx.compose.runtime.remember { mutableStateOf(initial) }
        AhpTheme {
            ChatPane(transcript(rows(1..30)), null, false, null, ConnState.Online, {}, {}, textScale = scale, onTextScale = { scale = it; onScale(it) })
        }
    }

    private fun pinch(fromGap: Float, toGap: Float) = rule.onRoot().performTouchInput {
        val c = center
        pinch(Offset(c.x - fromGap / 2, c.y), Offset(c.x - toGap / 2, c.y), Offset(c.x + fromGap / 2, c.y), Offset(c.x + toGap / 2, c.y))
    }

    @Test
    fun spreadingTwoFingersMakesTheTextBigger() {
        val seen = mutableListOf<Float>()
        showPinchable(onScale = { seen += it })

        pinch(fromGap = 200f, toGap = 300f)
        rule.waitForIdle()

        assertEquals("the size is kept once, when the fingers lift: $seen", 1, seen.size)
        assertEquals(1.5f, seen.single(), 0.001f)
    }

    @Test
    fun aSizeThatIsKeptIsAWholePercent() {
        val seen = mutableListOf<Float>()
        showPinchable(onScale = { seen += it })

        pinch(fromGap = 300f, toGap = 371f)
        rule.waitForIdle()

        assertEquals(1.24f, seen.single(), 0.0001f)
    }

    private fun fingersDown(gap: Float) = rule.onRoot().performTouchInput {
        val c = center
        down(0, Offset(c.x - gap / 2, c.y))
        down(1, Offset(c.x + gap / 2, c.y))
    }

    private fun fingersTo(gap: Float) = rule.onRoot().performTouchInput {
        val c = center
        updatePointerTo(0, Offset(c.x - gap / 2, c.y))
        updatePointerTo(1, Offset(c.x + gap / 2, c.y))
        move()
    }

    private fun fingersUp() = rule.onRoot().performTouchInput {
        up(0)
        up(1)
    }

    @Test
    fun theTextFollowsTheFingersWhileTheyAreStillDown() {
        val seen = mutableListOf<Float>()
        showPinchable(onScale = { seen += it })
        awaitText("message 30")
        val before = fontScaleOf("message 30")

        fingersDown(200f)
        // Each step must reach the screen with the fingers still down: a change of density must not end the pinch after the first.
        for ((gap, want) in listOf(220f to 1.1f, 260f to 1.3f, 300f to 1.5f, 240f to 1.2f)) {
            fingersTo(gap)
            rule.waitForIdle()
            assertEquals("gap $gap", before * want, fontScaleOf("message 30"), 0.01f)
        }
        assertTrue("nothing is kept while the fingers are down: $seen", seen.isEmpty())

        fingersUp()
        rule.waitForIdle()

        assertEquals(listOf(1.2f), seen)
        assertEquals(before * 1.2f, fontScaleOf("message 30"), 0.01f)
    }

    @Test
    fun aSecondPinchStartsFromTheSizeTheFirstLeft() {
        val seen = mutableListOf<Float>()
        showPinchable(onScale = { seen += it })

        pinch(fromGap = 200f, toGap = 300f)
        rule.waitForIdle()
        pinch(fromGap = 300f, toGap = 270f)
        rule.waitForIdle()

        assertEquals(2, seen.size)
        assertEquals(1.5f, seen[0], 0.001f)
        assertEquals(1.35f, seen[1], 0.001f)
    }

    @Test
    fun aPinchStartsFromTheSizeAlreadyChosen() {
        val seen = mutableListOf<Float>()
        showPinchable(initial = 1.2f, onScale = { seen += it })

        pinch(fromGap = 200f, toGap = 250f)
        rule.waitForIdle()

        assertEquals(1.5f, seen.single(), 0.001f)
    }

    @Test
    fun aSizeChosenElsewhereReplacesWhatAPinchLeft() {
        var scale by mutableStateOf(1f)
        rule.setContent { AhpTheme { ChatPane(transcript(rows(1..3)), null, false, null, ConnState.Online, {}, {}, textScale = scale, onTextScale = {}) } }
        awaitText("message 1")
        val before = fontScaleOf("message 1")

        pinch(fromGap = 200f, toGap = 300f)
        rule.waitForIdle()
        assertEquals(before * 1.5f, fontScaleOf("message 1"), 0.01f)

        scale = 1.2f
        rule.waitForIdle()

        assertEquals(before * 1.2f, fontScaleOf("message 1"), 0.01f)
    }

    @Test
    fun pinchingTwoFingersTogetherMakesItSmallerButNotBelowTheLeastSize() {
        val seen = mutableListOf<Float>()
        showPinchable(onScale = { seen += it })

        pinch(fromGap = 400f, toGap = 40f)
        rule.waitForIdle()

        assertEquals(TextScale.MIN, seen.last(), 0.001f)
        assertTrue("never below the least: $seen", seen.all { it >= TextScale.MIN - 0.001f })
    }

    @Test
    fun spreadingFarNeverGoesPastTheLargestSize() {
        val seen = mutableListOf<Float>()
        showPinchable(onScale = { seen += it })

        pinch(fromGap = 100f, toGap = 900f)
        rule.waitForIdle()

        assertEquals(TextScale.MAX, seen.last(), 0.001f)
        assertTrue("never above the largest: $seen", seen.all { it <= TextScale.MAX + 0.001f })
    }

    @Test
    fun aOneFingerSwipeScrollsAndDoesNotChangeTheTextSize() {
        val seen = mutableListOf<Float>()
        showPinchable(onScale = { seen += it })

        val before = y("message 30")
        rule.onRoot().performTouchInput { swipeDown() }
        rule.waitForIdle()

        assertTrue("scales: $seen", seen.isEmpty())
        val after = rule.onAllNodesWithText("message 30").fetchSemanticsNodes().firstOrNull()?.positionInRoot?.y
        assertTrue("the list scrolled: message 30 went from $before to $after", after == null || after > before)
    }

    @Test
    fun theChatTextFollowsTheSizeAndTheBarsAroundItDoNot() {
        var scale by mutableStateOf(1f)
        rule.setContent { AhpTheme { ChatScreen(transcript(rows(1..2)), null, false, null, ConnState.Online, {}, {}, {}, textScale = scale) } }
        awaitText("message 1")
        val chatBefore = fontScaleOf("message 1")
        val titleBefore = fontScaleOf("Fix the build")

        scale = 1.5f
        rule.waitForIdle()

        assertEquals(chatBefore * 1.5f, fontScaleOf("message 1"), 0.01f)
        assertEquals(titleBefore, fontScaleOf("Fix the build"), 0.01f)
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
        rule.onNodeWithTag("jump_latest").assertIsDisplayed().assertContentDescriptionEquals("Jump to latest").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)

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

    @Test
    fun aMarkdownTableNeverTruncatesLongCellTextAndCanOpenFullScreenViewer() {
        val longCell = "This is a very long description inside a markdown table cell that must wrap cleanly across multiple lines without ever being truncated with an ellipsis."
        val md = """
            | Column A | Detailed Explanation |
            | :--- | :--- |
            | Item 1 | $longCell |
        """.trimIndent()
        show(transcript(listOf(TranscriptItem.Markdown("m1", md))))
        awaitText("Item 1")

        rule.onNodeWithTag("markdown_table").assertIsDisplayed()
        rule.onNodeWithText(longCell).assertIsDisplayed()
        rule.onNodeWithTag("table_expand").assertHeightIsAtLeast(48.dp).performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("rich_viewer").assertIsDisplayed()
        rule.onNodeWithTag("rich_viewer_table_content").assertIsDisplayed()
    }

    @Test
    fun mermaidAndCodeBlocksRenderRichlyInlineAndOpenFullScreenViewer() {
        val md = """
            ```mermaid
            flowchart LR
                A[Start] --> B[End]
            ```

            ```kotlin
            fun greet() = println("Hello world")
            ```
        """.trimIndent()
        show(transcript(listOf(TranscriptItem.Markdown("m1", md))))
        awaitText("Flowchart")

        rule.onNodeWithTag("markdown_diagram").assertIsDisplayed()
        rule.onNodeWithTag("markdown_code_block").assertIsDisplayed()
        rule.onNodeWithTag("diagram_toggle_code").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("diagram_source_text").assertIsDisplayed()

        rule.onNodeWithTag("diagram_expand").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("rich_viewer").assertIsDisplayed()
        rule.onNodeWithTag("rich_viewer_diagram_canvas").assertIsDisplayed()
    }

    @Test
    fun hostFileLinksOpenInAppFileViewerWhenReaderIsProvided() {
        val md = "Inspect [Main.kt](file:///repo/src/Main.kt#L1-L2) for details."
        val t = transcript(listOf(TranscriptItem.Markdown("m1", md)))
        rule.setContent {
            AhpTheme {
                ChatScreen(
                    transcript = t,
                    error = null,
                    loadingOlder = false,
                    olderError = null,
                    connection = ConnState.Online,
                    onBack = {},
                    onLoadOlder = {},
                    onOpenLink = {},
                    onReadResource = {
                        com.microsoft.agenthostprotocol.generated.ResourceReadResult(
                            encoding = com.microsoft.agenthostprotocol.generated.ContentEncoding.UTF8,
                            data = "package demo\nfun main() {}\n",
                        )
                    },
                )
            }
        }
        awaitText("Inspect Main.kt for details.")
        rule.onNodeWithText("Inspect Main.kt for details.", substring = true).performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag("link_dialog").assertCountEquals(0)
    }

    @Test
    fun scrollingBackSlidesTheComposerOffScreenAndJumpingBackRestoresIt() {
        val actions = ChatActionsUi(
            running = false,
            send = { _, _, _ -> dev.ahpclient.state.InteractResult.Sent(dev.ahpclient.state.DispatchOutcome.Accepted) },
            steer = { _, _, _ -> dev.ahpclient.state.InteractResult.Sent(dev.ahpclient.state.DispatchOutcome.Accepted) },
            cancel = { dev.ahpclient.state.InteractResult.Sent(dev.ahpclient.state.DispatchOutcome.Accepted) },
            approve = { _, _ -> dev.ahpclient.state.InteractResult.Sent(dev.ahpclient.state.DispatchOutcome.Accepted) },
            deny = { _, _ -> dev.ahpclient.state.InteractResult.Sent(dev.ahpclient.state.DispatchOutcome.Accepted) },
            approveResult = { dev.ahpclient.state.InteractResult.Sent(dev.ahpclient.state.DispatchOutcome.Accepted) },
            denyResult = { dev.ahpclient.state.InteractResult.Sent(dev.ahpclient.state.DispatchOutcome.Accepted) },
            answer = { _, _ -> dev.ahpclient.state.InteractResult.Sent(dev.ahpclient.state.DispatchOutcome.Accepted) },
            saveDraft = { _, _, _ -> dev.ahpclient.state.InteractResult.Sent(dev.ahpclient.state.DispatchOutcome.Accepted) },
            decline = { dev.ahpclient.state.InteractResult.Sent(dev.ahpclient.state.DispatchOutcome.Accepted) },
            dismiss = { dev.ahpclient.state.InteractResult.Sent(dev.ahpclient.state.DispatchOutcome.Accepted) },
        )
        rule.setContent {
            AhpTheme {
                ChatScreen(transcript(rows(1..120)), null, false, null, ConnState.Online, {}, {}, {}, actions = actions)
            }
        }
        rule.onNodeWithTag("composer_input").assertIsDisplayed()

        rule.onNodeWithTag("transcript").performScrollToIndex(60)
        rule.waitForIdle()

        rule.onAllNodesWithTag("composer_input").assertCountEquals(0)
        rule.onNodeWithTag("jump_latest").assertIsDisplayed().performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("composer_input").assertIsDisplayed()
    }
}
