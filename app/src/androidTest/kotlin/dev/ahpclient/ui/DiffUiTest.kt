package dev.ahpclient.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ahpclient.app.DiffLoad
import dev.ahpclient.state.ChangesetOperationItem
import dev.ahpclient.state.ChangesetOperationPhase
import dev.ahpclient.state.ManageResult
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.DiffHunk
import dev.ahpclient.state.DiffLine
import dev.ahpclient.state.DiffLineKind
import dev.ahpclient.state.DiffResult
import dev.ahpclient.state.FileDiff
import dev.ahpclient.ui.theme.AhpTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** One file's line-by-line changes: what each state of loading says, and how a diff reads. */
@RunWith(AndroidJUnit4::class)
class DiffUiTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(load: DiffLoad, connection: ConnState = ConnState.Online, onBack: () -> Unit = {}) =
        rule.setContent { AhpTheme { FileDiffScreen("/work/a.kt", load, connection, onBack) } }

    private fun ready(diff: FileDiff) = DiffLoad.Loaded(DiffResult.Ready(diff))

    private val context = DiffLineKind.CONTEXT
    private val removed = DiffLineKind.REMOVED
    private val added = DiffLineKind.ADDED

    private val oneChange = FileDiff(
        hunks = listOf(
            DiffHunk(
                listOf(
                    DiffLine(context, 4, 4, "fun main() {"),
                    DiffLine(removed, 5, null, "    println(\"old\")"),
                    DiffLine(added, null, 5, "    println(\"new\")"),
                    DiffLine(context, 6, 6, "}"),
                ),
            ),
        ),
        added = 1,
        removed = 1,
        omittedLines = 0,
    )

    @Test
    fun theFileIsNamedAndLoadingIsSaid() {
        show(DiffLoad.Loading)

        rule.onNodeWithTag("diff_title").assertTextEquals("/work/a.kt")
        rule.onNodeWithTag("diff_loading").assertIsDisplayed()
    }

    @Test
    fun aDiffShowsItsTotalsItsBlockAndEveryLineWithASignAndANumber() {
        show(ready(oneChange))

        rule.onNodeWithTag("diff_summary").assertTextEquals("+1 −1 lines")
        rule.onNodeWithTag("diff_hunk").assertTextEquals("Lines 4–6")
        val lines = rule.onAllNodesWithTag("diff_line")
        lines.assertCountEquals(4)
        lines[1].assertTextContains("−", substring = false)
        lines[1].assertTextContains("5", substring = false)
        lines[1].assertTextContains("    println(\"old\")", substring = false)
        lines[2].assertTextContains("+", substring = false)
        lines[2].assertTextContains("    println(\"new\")", substring = false)
    }

    @Test
    fun eachLineIsNamedForAScreenReaderSoColourIsNeverTheOnlySign() {
        show(ready(oneChange))

        val lines = rule.onAllNodesWithTag("diff_line")
        lines[0].assertContentDescriptionEquals("Unchanged, line 4: fun main() {")
        lines[1].assertContentDescriptionEquals("Removed, line 5:     println(\"old\")")
        lines[2].assertContentDescriptionEquals("Added, line 5:     println(\"new\")")
    }

    @Test
    fun aBlankLineIsNamedBlank() {
        show(ready(FileDiff(listOf(DiffHunk(listOf(DiffLine(added, null, 1, "")))), 1, 0, 0)))

        rule.onAllNodesWithTag("diff_line")[0].assertContentDescriptionEquals("Added, line 1: blank")
    }

    @Test
    fun aFileWithNoLineChangesSaysSo() {
        show(ready(FileDiff(emptyList(), 0, 0, 0)))

        rule.onNodeWithTag("diff_none").assertTextEquals("No line changes.")
        rule.onAllNodesWithTag("diff_summary").assertCountEquals(0)
    }

    @Test
    fun linesLeftOutAreCounted() {
        show(ready(FileDiff(oneChange.hunks, 1, 1, omittedLines = 37)))

        rule.onNodeWithTag("diff_list").performScrollToNode(androidx.compose.ui.test.hasTestTag("diff_omitted"))
        rule.onNodeWithTag("diff_omitted").assertTextEquals("37 more lines not shown.")
    }

    @Test
    fun aLongDiffIsScrollable() {
        val lines = (1..1500).map { DiffLine(added, null, it, "line number $it") }
        show(ready(FileDiff(listOf(DiffHunk(lines)), 1500, 0, 0)))

        rule.onNodeWithTag("diff_list").performScrollToNode(hasText("line number 1400", substring = true))
        rule.onNodeWithTag("diff_list").assertIsDisplayed()
    }

    @Test
    fun textInAFileIsShownAsItIsNeverAsMarkupOrALink() {
        val hostile = "<b>bold</b> [click](https://evil.example) <script>x()</script>"
        show(ready(FileDiff(listOf(DiffHunk(listOf(DiffLine(added, null, 1, hostile)))), 1, 0, 0)))

        rule.onAllNodesWithTag("diff_line")[0].assertTextContains(hostile, substring = false)
    }

    @Test
    fun eachWayLoadingCanEndIsSaidInWords() {
        val cases = listOf(
            DiffResult.NoSuchFile to "diff_gone",
            DiffResult.TooLarge to "diff_too_large",
            DiffResult.Binary to "diff_binary",
            DiffResult.NoEarlierVersion to "diff_no_earlier",
            DiffResult.Failed("The host said: nope") to "diff_failed",
        )
        val shown = mutableStateOf<DiffResult>(cases.first().first)
        rule.setContent { AhpTheme { FileDiffScreen("/work/a.kt", DiffLoad.Loaded(shown.value), ConnState.Online, {}) } }
        for ((result, tag) in cases) {
            shown.value = result
            rule.waitForIdle()
            rule.onNodeWithTag(tag).assertIsDisplayed()
        }
    }

    @Test
    fun aFailureShowsTheReason() {
        show(DiffLoad.Loaded(DiffResult.Failed("The host said: nope")))

        rule.onNodeWithTag("diff_failed").assertTextContains("The host said: nope", substring = true)
    }

    @Test
    fun backGoesBack() {
        var back = 0
        show(ready(oneChange), onBack = { back++ })

        rule.onNodeWithTag("back").performClick()

        assertEquals(1, back)
    }

    @Test
    fun resourceOperationsOnFileDiffScreenRunDirectlyOrAfterConfirmationDialog() {
        val invoked = mutableListOf<String>()
        val ops = listOf(
            ChangesetOperationItem("stage-file", "Stage file", null, null, ChangesetOperationPhase.IDLE, null),
            ChangesetOperationItem("revert-file", "Revert file", null, "Revert this file?", ChangesetOperationPhase.IDLE, null),
        )
        rule.setContent {
            AhpTheme {
                FileDiffScreen(
                    path = "/work/a.kt",
                    load = ready(oneChange),
                    connection = ConnState.Online,
                    onBack = {},
                    operations = ops,
                    onInvokeOperation = { opId ->
                        invoked += opId
                        ManageResult.Done("Ran $opId")
                    },
                )
            }
        }

        rule.onNodeWithTag("diff_operations").assertIsDisplayed()
        rule.onNodeWithTag("diff_op_stage-file").assertIsDisplayed().performClick()
        rule.waitUntil(5_000) { invoked == listOf("stage-file") }
        rule.onNodeWithTag("diff_operation_message").assertIsDisplayed().assertTextContains("Ran stage-file")

        rule.onNodeWithTag("diff_op_revert-file").assertIsDisplayed().performClick()
        rule.onNodeWithTag("diff_op_confirm_dialog").assertIsDisplayed()
        rule.onNodeWithTag("diff_op_confirm_text").assertTextContains("Revert this file?", substring = true)
        rule.onNodeWithTag("diff_op_confirm").performClick()
        rule.waitUntil(5_000) { invoked == listOf("stage-file", "revert-file") }
    }
}
