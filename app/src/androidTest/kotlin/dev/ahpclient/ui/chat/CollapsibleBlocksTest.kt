package dev.ahpclient.ui.chat

import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ahpclient.state.ActivityState
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.ExpansionState
import dev.ahpclient.state.ToolState
import dev.ahpclient.state.Transcript
import dev.ahpclient.state.TranscriptItem
import dev.ahpclient.ui.theme.AhpTheme
import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ToolCallState
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Reasoning, tool calls and their results fold up to one line, open on tap, and keep their state when the screen goes away. */
@RunWith(AndroidJUnit4::class)
class CollapsibleBlocksTest {
    @get:Rule
    val rule = createComposeRule()

    private val common = """"toolCallId":"c1","toolName":"run_in_terminal","displayName":"Run in Terminal""""

    private fun call(json: String): ToolCallState = Ahp.json.decodeFromString(ToolCallState.serializer(), "{$json}")

    private fun completed(content: String? = null, input: String = "{}", extra: String = "") =
        call(""""status":"completed",$common,"invocationMessage":"Run it","toolInput":${JsonPrimitive(input)},"confirmed":"not-needed","success":true,"pastTenseMessage":"Ran it"${content?.let { ""","content":[$it]""" }.orEmpty()}$extra""")

    private fun tool(key: String, source: ToolCallState?, state: ToolState = ToolState.DONE, name: String = "Run in Terminal") =
        TranscriptItem.Tool(key, name, "List the files", state, source)

    private fun transcript(vararg items: TranscriptItem) = Transcript("Fix the build", ActivityState.IN_PROGRESS, null, items.toList(), hasOlder = false)

    private fun show(transcript: Transcript, expanded: Set<String> = emptySet(), onToggle: (String) -> Unit = {}, onOpenSubagent: ((String) -> Unit)? = null, onOpenTerminal: ((String) -> Unit)? = null) =
        rule.setContent { AhpTheme { ChatScreen(transcript, null, false, null, ConnState.Online, {}, {}, {}, expanded = expanded, onToggle = onToggle, onOpenSubagent = onOpenSubagent, onOpenTerminal = onOpenTerminal) } }

    private fun headerOf(text: String) = hasTestTag("block_header") and hasText(text, substring = true)

    // ---- the block itself --------------------------------------------------------------------------------------

    @Test
    fun aBlockIsClosedByDefault_andItsBodyIsNotEvenBuiltUntilItOpens() {
        var built = 0
        var open by mutableStateOf(false)
        rule.setContent {
            AhpTheme {
                CollapsibleBlock(expanded = open, onToggle = { open = !open }, header = { Text("Summary") }) {
                    built++
                    Text("Body")
                }
            }
        }

        rule.onNodeWithText("Summary").assertIsDisplayed()
        rule.onAllNodesWithText("Body").assertCountEquals(0)
        assertEquals("a closed block must not compose its body", 0, built)

        rule.onNodeWithTag("block_header").performClick()
        rule.waitForIdle()

        rule.onNodeWithText("Body").assertIsDisplayed()
        assertTrue(built > 0)
    }

    @Test
    fun theHeaderIsOneButtonThatSaysWhetherItIsOpenAndWhatItWillDo() {
        var open by mutableStateOf(false)
        rule.setContent { AhpTheme { CollapsibleBlock(expanded = open, onToggle = { open = !open }, header = { Text("Summary") }) { Text("Body") } } }

        fun label() = rule.onNodeWithTag("block_header").fetchSemanticsNode().config[SemanticsActions.OnClick].label
        rule.onNodeWithTag("block_header").assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        assertEquals("Expand", label())

        rule.onNodeWithTag("block_header").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("block_header").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        assertEquals("Collapse", label())
    }

    @Test
    fun aBlockOpensAndClosesWithEachTap() {
        var open by mutableStateOf(false)
        rule.setContent { AhpTheme { CollapsibleBlock(expanded = open, onToggle = { open = !open }, header = { Text("Summary") }) { Text("Body") } } }

        repeat(2) {
            rule.onNodeWithTag("block_header").performClick()
            rule.waitForIdle()
            rule.onNodeWithText("Body").assertIsDisplayed()
            rule.onNodeWithTag("block_header").performClick()
            rule.waitForIdle()
            rule.onAllNodesWithText("Body").assertCountEquals(0)
        }
    }

    @Test
    fun theHeaderIsAtLeastAsTallAsATouchTarget() {
        rule.setContent { AhpTheme { CollapsibleBlock(expanded = false, onToggle = {}, header = { Text("Summary") }) { Text("Body") } } }

        val bounds = rule.onNodeWithTag("block_header").fetchSemanticsNode().touchBoundsInRoot
        val minimum = with(rule.density) { 48.dp.toPx() }
        assertTrue("the header is ${bounds.height} px tall, under $minimum", bounds.height + 0.5f >= minimum)
    }

    // ---- reasoning ---------------------------------------------------------------------------------------------

    @Test
    fun reasoningIsOneClosedLineUntilTapped() {
        show(transcript(TranscriptItem.Reasoning("r1", "I compared the two build scripts.", live = false)), onToggle = {})

        rule.onNodeWithText("Thought").assertIsDisplayed()
        rule.onAllNodesWithText("compared the two build scripts", substring = true).assertCountEquals(0)
    }

    @Test
    fun openReasoningShowsItsText() {
        show(transcript(TranscriptItem.Reasoning("r1", "I compared the two build scripts.", live = false)), expanded = setOf("r1"))

        rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodesWithText("compared the two build scripts", substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun reasoningWithNoTextIsAPlainLineNotAButtonThatOpensOntoNothing() {
        show(transcript(TranscriptItem.Reasoning("r1", "   ", live = true)))

        rule.onNodeWithText("Thinking…").assertIsDisplayed()
        rule.onAllNodesWithTag("block_header").assertCountEquals(0)
    }

    // ---- tool calls --------------------------------------------------------------------------------------------

    @Test
    fun aToolCallIsOneClosedLineWithItsBadge_andNothingOfItsResultShowing() {
        val source = completed(
            """{"type":"text","text":"file one"},{"type":"terminal","resource":"ahp-terminal:/a","title":"Build","isPty":false,"result":{"exitCode":2,"preview":"error: no such file"}}""",
            """{"command":"ls -la"}""",
        )
        show(transcript(tool("t1", source)))

        rule.onNode(headerOf("Run in Terminal")).assertIsDisplayed().assertTextContains("Done", substring = true)
        for (hidden in listOf("file one", "error: no such file", "ls -la")) rule.onAllNodesWithText(hidden).assertCountEquals(0)
        rule.onAllNodesWithTag("block_body").assertCountEquals(0)
    }

    @Test
    fun anOpenToolCallShowsItsInputAndEveryKindOfResult() {
        val source = completed(
            listOf(
                """{"type":"text","text":"file one"}""",
                """{"type":"terminal","resource":"ahp-terminal:/a","title":"Build","isPty":false,"result":{"exitCode":2,"preview":"error: no such file","truncated":true}}""",
                """{"type":"fileEdit","before":{"uri":"file:///work/repo/a.txt","content":{"uri":"ahp-content:/1"}},"after":{"uri":"file:///work/repo/a.txt","content":{"uri":"ahp-content:/2"}},"diff":{"added":12,"removed":3}}""",
                """{"type":"subagent","resource":"ahp-chat:/s","title":"Explore","agentName":"explore","description":"Look around the repo"}""",
                """{"type":"embeddedResource","contentType":"image/png","data":"AAAA"}""",
                """{"type":"hologram","x":1}""",
            ).joinToString(","),
            """{"command":"ls -la","timeout":30}""",
            ""","error":{"code":"E1","message":"the build failed"}""",
        )
        show(transcript(tool("t1", source)), expanded = setOf("t1"))

        rule.onNodeWithTag("tool_input").assertIsDisplayed()
        rule.onNodeWithText("command").assertIsDisplayed()
        rule.onNodeWithText("ls -la").assertIsDisplayed()
        rule.onNodeWithText("timeout").assertIsDisplayed()
        rule.onNodeWithText("file one").assertIsDisplayed()
        rule.onNodeWithTag("result_terminal").assertTextContains("Build", substring = true).assertTextContains("error: no such file", substring = true)
        rule.onNode(hasText("exit 2") and hasAnyAncestor(hasTestTag("exit_badge")), useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("The host cut this output short.").assertIsDisplayed()
        rule.onNodeWithTag("result_file_edit").assertTextContains("/work/repo/a.txt", substring = true)
        rule.onNodeWithTag("result_subagent").assertTextContains("Look around the repo", substring = true)
        rule.onNodeWithTag("result_attachment").assertTextEquals("Attachment: image/png")
        rule.onNodeWithTag("result_unknown").assertIsDisplayed()
        rule.onNodeWithTag("tool_error").assertTextEquals("the build failed")
    }

    @Test
    fun aToolCallWithStepExtrasShowsDurationInTheBadgeAndMcpServerInTheExpandedBody() {
        val source = completed(
            """{"type":"text","text":"ok"}""",
            extra = ""","_meta":{"ahpd.durationMs":4250,"mcpServerName":"github-mcp"}""",
        )
        show(transcript(tool("t1", source)), expanded = setOf("t1"))

        rule.onNode(headerOf("Run in Terminal")).assertTextContains("Done \u00b7 4s", substring = true)
        rule.onNodeWithTag("tool_meta", useUnmergedTree = true).assertTextEquals("MCP: github-mcp \u00b7 Duration: 4s")
    }

    @Test
    fun aFileEditSaysInWordsHowManyLinesChanged() {
        val source = completed("""{"type":"fileEdit","after":{"uri":"file:///work/repo/a.txt","content":{"uri":"ahp-content:/2"}},"diff":{"added":12,"removed":3}}""")
        show(transcript(tool("t1", source)), expanded = setOf("t1"))

        val descriptions = rule.onNodeWithTag("result_file_edit").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        assertTrue("descriptions: $descriptions", "12 lines added, 3 removed" in descriptions)
    }

    @Test
    fun aCallWaitingOnTheUserSaysSoAndSaysWhereToAnswer() {
        val source = call(""""status":"pending-confirmation",$common,"invocationMessage":"Run it","toolInput":"{}","confirmationTitle":"Run this command?","options":[]""")
        show(transcript(tool("t1", source, ToolState.WAITING_FOR_APPROVAL)), expanded = setOf("t1"))

        rule.onNodeWithTag("tool_waiting").assertTextContains("Run this command?", substring = true).assertTextContains("Approve or deny in VS Code.", substring = true)
    }

    @Test
    fun aCallWithNothingToShowSaysSoInsteadOfOpeningEmpty() {
        show(transcript(tool("t1", completed())), expanded = setOf("t1"))

        rule.onNodeWithTag("tool_nothing").assertIsDisplayed()
    }

    @Test
    fun aToolCallMadeWithoutTheHostsRecordIsAPlainRowWithNothingToOpen() {
        show(transcript(tool("t1", null)))

        rule.onNodeWithTag("row_tool").assertTextContains("Run in Terminal", substring = true)
        rule.onAllNodesWithTag("block_header").assertCountEquals(0)
    }

    @Test
    fun hugeOutputIsCutAndSaysHowMuchWasLeftOut() {
        val big = "x".repeat(30_000)
        val source = completed("""{"type":"text","text":"$big"}""")
        show(transcript(tool("t1", source)), expanded = setOf("t1"))

        rule.onNodeWithTag("clipped_note").assertTextContains("8,000", substring = true).assertTextContains("30,000", substring = true)
    }

    @Test
    fun aHugeAttachmentIsOnlyLabelled() {
        val source = completed("""{"type":"embeddedResource","contentType":"image/png","data":"${"A".repeat(500_000)}"}""")
        show(transcript(tool("t1", source)), expanded = setOf("t1"))

        rule.onNodeWithTag("result_attachment").assertTextEquals("Attachment: image/png")
    }

    @Test
    fun showAllLoadsTheRestOfALongResultAndThenGoesAway() {
        val big = "x".repeat(30_000)
        show(transcript(tool("t1", completed("""{"type":"text","text":"$big"}"""))), expanded = setOf("t1"))

        rule.onNodeWithTag("clipped_note").assertTextContains("30,000", substring = true)
        rule.onNodeWithTag("show_all").performScrollTo().performClick()

        rule.onNodeWithTag("clipped_note").assertDoesNotExist()
        rule.onNodeWithTag("show_all").assertDoesNotExist()
    }

    @Test
    fun aResultThatFitsHasNoShowAll() {
        show(transcript(tool("t1", completed("""{"type":"text","text":"short"}"""))), expanded = setOf("t1"))

        rule.onNodeWithTag("result_text").assertExists()
        rule.onNodeWithTag("show_all").assertDoesNotExist()
    }

    // ---- subagents ---------------------------------------------------------------------------------------------

    private fun subagent(resource: String) =
        completed("""{"type":"subagent","resource":${JsonPrimitive(resource)},"title":"Explore","agentName":"explore","description":"Look around"}""")

    @Test
    fun aSubagentResultOffersItsTranscriptAndHandsOverTheAddressItWasGiven() {
        val opened = mutableListOf<String>()
        show(transcript(tool("t1", subagent("ahp-chat:/s"))), expanded = setOf("t1"), onOpenSubagent = { opened += it })

        rule.onNodeWithTag("subagent_open").performScrollTo().assertTextEquals("Open its transcript").performClick()

        assertEquals(listOf("ahp-chat:/s"), opened)
    }

    @Test
    fun aSubagentWhoseAddressIsNotAChatIsNeverOffered() {
        val opened = mutableListOf<String>()
        show(transcript(tool("t1", subagent("https://example.test/x"))), expanded = setOf("t1"), onOpenSubagent = { opened += it })

        rule.onNodeWithTag("subagent_open").assertDoesNotExist()
        rule.onNodeWithText("Its own transcript is not available.").assertExists()
        assertTrue(opened.isEmpty())
    }

    @Test
    fun aSubagentIsNotOfferedWhereThereIsNowhereToOpenIt() {
        show(transcript(tool("t1", subagent("ahp-chat:/s"))), expanded = setOf("t1"))

        rule.onNodeWithTag("subagent_open").assertDoesNotExist()
        rule.onNodeWithText("Its own transcript is not available.").assertExists()
    }

    // ---- terminals ---------------------------------------------------------------------------------------------

    private fun terminal(resource: String) =
        completed("""{"type":"terminal","resource":${JsonPrimitive(resource)},"title":"Build","isPty":false,"result":{"exitCode":0,"preview":"ok"}}""")

    @Test
    fun aTerminalResultOffersItsLiveOutputAndHandsOverTheAddressItWasGiven() {
        val opened = mutableListOf<String>()
        show(transcript(tool("t1", terminal("ahp-terminal:/a"))), expanded = setOf("t1"), onOpenTerminal = { opened += it })

        rule.onNodeWithTag("terminal_open").performScrollTo().assertTextEquals("Open live output").performClick()

        assertEquals(listOf("ahp-terminal:/a"), opened)
    }

    @Test
    fun aTerminalWhoseAddressIsNotATerminalsIsNeverOffered() {
        val opened = mutableListOf<String>()
        show(transcript(tool("t1", terminal("https://example.test/x"))), expanded = setOf("t1"), onOpenTerminal = { opened += it })

        rule.onNodeWithTag("terminal_open").assertDoesNotExist()
        rule.onNodeWithTag("result_terminal").assertTextContains("ok", substring = true)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun aTerminalIsNotOfferedWhereThereIsNowhereToOpenIt() {
        show(transcript(tool("t1", terminal("ahp-terminal:/a"))), expanded = setOf("t1"))

        rule.onNodeWithTag("terminal_open").assertDoesNotExist()
    }

    // ---- state -------------------------------------------------------------------------------------------------

    @Test
    fun tappingAHeaderAsksForThatRowsKeyToBeToggled() {
        val toggled = mutableListOf<String>()
        show(
            transcript(
                tool("t1", completed("""{"type":"text","text":"one"}""")),
                TranscriptItem.Markdown("m1", "and then"),
                TranscriptItem.Reasoning("r1", "thinking about it", live = false),
            ),
            onToggle = toggled::add,
        )

        rule.onNode(headerOf("Run in Terminal")).performClick()
        rule.onNode(headerOf("Thought")).performClick()

        assertEquals(listOf("t1", "r1"), toggled)
    }

    @Test
    fun whatWasOpenIsStillOpenAfterTheScreenGoesAwayAndComesBack() {
        val state = ExpansionState()
        var shown by mutableStateOf(true)
        val t = transcript(tool("t1", completed("""{"type":"text","text":"file one"}""")))
        rule.setContent {
            AhpTheme {
                if (shown) {
                    val expanded by state.expanded.collectAsState()
                    ChatScreen(t, null, false, null, ConnState.Online, {}, {}, {}, expanded = expanded, onToggle = state::toggle)
                }
            }
        }
        rule.onAllNodesWithText("file one").assertCountEquals(0)
        rule.onNode(headerOf("Run in Terminal")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("file one").assertIsDisplayed()

        rule.runOnIdle { shown = false }
        rule.waitForIdle()
        rule.runOnIdle { shown = true }
        rule.waitForIdle()

        rule.onNodeWithText("file one").assertIsDisplayed()
    }

    @Test
    fun openingOneBlockLeavesTheOthersClosed() {
        val state = ExpansionState()
        val t = transcript(
            tool("t1", completed("""{"type":"text","text":"first result"}"""), name = "First tool"),
            tool("t2", completed("""{"type":"text","text":"second result"}"""), name = "Second tool"),
        )
        rule.setContent {
            AhpTheme {
                val expanded by state.expanded.collectAsState()
                ChatScreen(t, null, false, null, ConnState.Online, {}, {}, {}, expanded = expanded, onToggle = state::toggle)
            }
        }

        rule.onNode(hasTestTag("steps_header")).performClick()
        rule.waitForIdle()
        rule.onNode(headerOf("Second tool")).performClick()
        rule.waitForIdle()

        rule.onNodeWithText("second result").assertIsDisplayed()
        rule.onAllNodesWithText("first result").assertCountEquals(0)
    }
}
