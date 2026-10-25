package dev.ahpclient.ui

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.ManageResult
import kotlinx.coroutines.CompletableDeferred
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertCountEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ahpclient.state.ChangeStats
import dev.ahpclient.state.ChannelGoneException
import dev.ahpclient.state.ConnState
import dev.ahpclient.ui.theme.AhpTheme
import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ChangesetState
import com.microsoft.agenthostprotocol.generated.SessionState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The files a changeset changed, read only, and the way into it from a session. */
@RunWith(AndroidJUnit4::class)
class ChangesetUiTest {
    @get:Rule
    val rule = createComposeRule()

    private fun file(path: String, added: Int? = 3, removed: Int? = 1, before: Boolean = true, after: Boolean = true, reviewed: Boolean = false): String {
        val sides = listOfNotNull(
            if (before) """"before":{"uri":"file:///$path","content":{"uri":"ahp-content:/b-$path"}}""" else null,
            if (after) """"after":{"uri":"file:///$path","content":{"uri":"ahp-content:/a-$path"}}""" else null,
            if (added != null && removed != null) """"diff":{"added":$added,"removed":$removed}""" else null,
        )
        return """{"id":"file:///$path","edit":{${sides.joinToString(",")}}${if (reviewed) ""","reviewed":true""" else ""}}"""
    }

    private fun changeset(status: String = "ready", vararg files: String, error: String? = null): ChangesetState = Ahp.json.decodeFromString(
        ChangesetState.serializer(),
        """{"status":"$status","files":[${files.joinToString(",")}]${error?.let { ""","error":{"errorType":"E","message":"$it"}""" }.orEmpty()}}""",
    )

    private fun show(state: ChangesetState?, error: Throwable? = null, connection: ConnState = ConnState.Online, onBack: () -> Unit = {}, onSetReviewed: (suspend (String, Boolean) -> ManageResult)? = null, onOpenFile: ((String) -> Unit)? = null) =
        rule.setContent { AhpTheme { ChangesetScreen("Uncommitted", state, error, connection, onBack, onSetReviewed = onSetReviewed, onOpenFile = onOpenFile) } }

    @Test
    fun theTitleAndTheReadOnlyNoteAreShown() {
        show(changeset(files = arrayOf(file("work/a.kt"))))

        rule.onNodeWithTag("changeset_title").assertTextEquals("Uncommitted")
        rule.onNodeWithTag("changeset_note").assertTextContains("Read only", substring = true)
    }

    @Test
    fun eachFileShowsItsPathItsKindAndItsLineCounts() {
        show(
            changeset(
                files = arrayOf(
                    file("work/a.kt"),
                    file("work/new.kt", 10, 0, before = false),
                    file("work/old.kt", 0, 7, after = false),
                    file("work/x.kt", null, null, reviewed = true),
                ),
            ),
        )

        rule.onAllNodesWithTag("change_row").assertCountEquals(4)
        rule.onAllNodesWithTag("change_row")[0].assertTextContains("/work/a.kt", substring = true).assertTextContains("Modified", substring = true)
        rule.onAllNodesWithTag("change_row")[1].assertTextContains("Added", substring = true)
        rule.onAllNodesWithTag("change_row")[2].assertTextContains("Deleted", substring = true)
        rule.onAllNodesWithTag("change_row")[3].assertTextContains("Reviewed", substring = true)
        rule.onNodeWithTag("changeset_summary").assertTextEquals("4 files · +13 −8")
        assertEquals(3, rule.onAllNodesWithTag("change_counts", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun oneFileIsSaidInTheSingular() {
        show(changeset(files = arrayOf(file("work/a.kt"))))

        rule.onNodeWithTag("changeset_summary").assertTextEquals("1 file · +3 −1")
    }

    @Test
    fun aReadyChangesetWithNoFilesSaysSo() {
        show(changeset())

        rule.onNodeWithTag("changeset_empty").assertIsDisplayed()
        rule.onNodeWithTag("changeset_summary").assertDoesNotExist()
    }

    @Test
    fun aChangesetStillBeingWorkedOutSaysSoAndOneBeingRefreshedWarnsItMayBeOld() {
        show(changeset("computing"))
        rule.onNodeWithTag("changeset_computing").assertIsDisplayed()
        rule.onNodeWithTag("changeset_empty").assertDoesNotExist()
    }

    @Test
    fun aRefreshWarnsThatWhatIsShownMayBeOutOfDate() {
        show(changeset("recomputing", file("work/a.kt")))

        rule.onNodeWithTag("changeset_recomputing").assertTextContains("out of date", substring = true)
        rule.onAllNodesWithTag("change_row").assertCountEquals(1)
    }

    @Test
    fun aFailureShowsTheHostsReason() {
        show(changeset("error", error = "git is not installed"))

        rule.onNodeWithTag("changeset_failed").assertTextContains("git is not installed", substring = true)
    }

    @Test
    fun aStateThisAppDoesNotKnowIsNeverShownAsReady() {
        show(changeset("sleeping"))

        rule.onNodeWithTag("changeset_unknown").assertIsDisplayed()
        rule.onNodeWithTag("changeset_empty").assertDoesNotExist()
    }

    @Test
    fun aVeryLongChangesetListsTheFirstFilesAndCountsTheRest() {
        val files = (1..510).map { file("f$it") }.toTypedArray()
        show(changeset(files = files))

        rule.onNodeWithTag("changeset_summary").assertTextEquals("510 files · +1530 −510")
        rule.onNodeWithTag("changeset_list").performScrollToNode(hasTestTag("changeset_omitted"))
        rule.onNodeWithTag("changeset_omitted").assertTextEquals("10 more files not shown.")
    }

    @Test
    fun loadingIsSaid() {
        show(null)
        rule.onNodeWithTag("changeset_loading").assertIsDisplayed()
    }

    @Test
    fun aChangesetThatIsGoneSaysSo() {
        show(null, ChannelGoneException("ahp-changeset:/c1"))

        rule.onNodeWithTag("changeset_gone").assertIsDisplayed()
    }

    @Test
    fun aChangesetThatCouldNotBeLoadedSaysSo() {
        show(null, IllegalStateException("x"))

        rule.onNodeWithTag("changeset_error").assertTextContains("Could not load", substring = true)
    }

    @Test
    fun backGoesBack() {
        var back = 0
        show(changeset(), onBack = { back++ })

        rule.onNodeWithTag("back").performClick()

        assertEquals(1, back)
    }

    // ---- marking files viewed ---------------------------------------------------------------------------------------

    private val twoFiles get() = changeset(files = arrayOf(file("work/a.kt"), file("work/b.kt", reviewed = true)))

    @Test
    fun withoutTheReviewCallbackThereAreNoViewedBoxes() {
        show(twoFiles)

        rule.onAllNodesWithTag("viewed_toggle").assertCountEquals(0)
        rule.onNodeWithTag("changeset_note").assertTextContains("Read only", substring = true)
    }

    @Test
    fun eachFileGetsAViewedBoxShowingWhatTheHostHolds() {
        show(twoFiles, onSetReviewed = { _, _ -> ManageResult.Done() })

        rule.onAllNodesWithTag("viewed_toggle").assertCountEquals(2)
        rule.onAllNodesWithTag("viewed_toggle")[0].assertIsOff()
        rule.onAllNodesWithTag("viewed_toggle")[1].assertIsOn()
        rule.onNodeWithTag("changeset_note").assertTextContains("Viewed", substring = true)
    }

    @Test
    fun tappingAsksTheHostToFlipThatFileAndTheBoxStaysAsTheHostHoldsIt() {
        val asked = mutableListOf<Pair<String, Boolean>>()
        show(twoFiles, onSetReviewed = { id, on -> asked += id to on; ManageResult.Done() })

        rule.onAllNodesWithTag("viewed_toggle")[0].performClick()
        rule.onAllNodesWithTag("viewed_toggle")[1].performClick()
        rule.waitForIdle()

        assertEquals(listOf("file:///work/a.kt" to true, "file:///work/b.kt" to false), asked)
        // Nothing is ticked or cleared until the host echoes it, and the state passed in has not changed.
        rule.onAllNodesWithTag("viewed_toggle")[0].assertIsOff()
        rule.onAllNodesWithTag("viewed_toggle")[1].assertIsOn()
        rule.onNodeWithTag("review_notice").assertDoesNotExist()
    }

    @Test
    fun aRefusalIsSaidInWords() {
        show(twoFiles, onSetReviewed = { _, _ -> ManageResult.Refused(-32009, "not yours") })

        rule.onAllNodesWithTag("viewed_toggle")[0].performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("review_notice").assertTextContains("not yours", substring = true)
    }

    @Test
    fun aBoxIsDisabledWhileItsTapIsInFlightAndASecondTapIsNotSent() {
        val answer = CompletableDeferred<ManageResult>()
        var calls = 0
        show(twoFiles, onSetReviewed = { _, _ -> calls++; answer.await() })

        rule.onAllNodesWithTag("viewed_toggle")[0].performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag("viewed_toggle")[0].assertIsNotEnabled()
        rule.onAllNodesWithTag("viewed_toggle")[0].performClick()
        rule.onAllNodesWithTag("viewed_toggle")[1].assertIsEnabled()

        answer.complete(ManageResult.Done())
        rule.waitForIdle()
        rule.onAllNodesWithTag("viewed_toggle")[0].assertIsEnabled()
        assertEquals(1, calls)
    }

    @Test
    fun theBoxNamesItsFileAndIsBigEnoughToTap() {
        show(twoFiles, onSetReviewed = { _, _ -> ManageResult.Done() })

        rule.onAllNodesWithTag("viewed_toggle")[0].assertContentDescriptionEquals("Viewed: /work/a.kt").assert(isToggleable()).assertHeightIsAtLeast(48.dp)
    }

    // ---- opening a file ---------------------------------------------------------------------------------------------

    @Test
    fun tappingAFileAsksToOpenItAndTheRowSaysSo() {
        val opened = mutableListOf<String>()
        show(twoFiles, onOpenFile = { opened += it })

        rule.onAllNodesWithTag("change_row")[1].performClick()

        assertEquals(listOf("file:///work/b.kt"), opened)
        rule.onNodeWithTag("changeset_note").assertTextContains("Tap a file", substring = true)
        rule.onAllNodesWithTag("change_row")[0].assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun withoutTheOpenCallbackARowDoesNothingWhenTapped() {
        show(twoFiles)

        rule.onAllNodesWithTag("change_row")[0].performClick()

        rule.onNodeWithTag("changeset_note").assertTextContains("Read only", substring = true)
    }

    @Test
    fun tappingTheViewedBoxDoesNotOpenTheFile() {
        val opened = mutableListOf<String>()
        show(twoFiles, onSetReviewed = { _, _ -> ManageResult.Done() }, onOpenFile = { opened += it })

        rule.onAllNodesWithTag("viewed_toggle")[0].performClick()
        rule.waitForIdle()

        assertEquals(emptyList<String>(), opened)
    }

    // ---- the way in from a session ----------------------------------------------------------------------------------

    private fun session(changesets: String): SessionState = Ahp.json.decodeFromString(
        SessionState.serializer(),
        """{"provider":"copilotcli","title":"Fix the build","status":1,"lifecycle":"ready","activeClients":[],"chats":[],"changesets":$changesets}""",
    )

    private val clock = Instant.parse("2026-10-26T10:30:00Z")
    private val plain = """{"label":"Uncommitted","uriTemplate":"ahp-changeset:/c1","changeKind":"uncommitted"}"""
    private val second = """{"label":"Last commit","uriTemplate":"ahp-changeset:/c2","changeKind":"commit"}"""
    private val needsValues = """{"label":"Per turn","uriTemplate":"ahp-changeset:/t/{turnId}","changeKind":"turn"}"""

    private fun showBar(changesets: String, changes: ChangeStats?, opened: MutableList<Pair<String, String>> = mutableListOf()) = rule.setContent {
        AhpTheme { SessionScreen(session(changesets), null, ConnState.Online, clock, {}, changes = changes, onOpenChangeset = { u, l -> opened += u to l }) }
    }

    @Test
    fun theChangesInTheBarAreAButtonToTheFilesWhenThereIsOneSetToOpen() {
        val opened = mutableListOf<Pair<String, String>>()
        showBar("[$plain, $needsValues]", ChangeStats(1072, 8, 14), opened)

        rule.onNodeWithTag("changes_chip").assertTextEquals("(+1072, \u22128)").assertContentDescriptionEquals("1072 lines added, 8 removed, in 14 files. Show the files")
        rule.onNodeWithTag("changes_chip").performClick()

        assertEquals(listOf("ahp-changeset:/c1" to "Uncommitted"), opened)
    }

    @Test
    fun severalSetsOfChangesOpenAMenuToChooseFrom() {
        val opened = mutableListOf<Pair<String, String>>()
        showBar("[$plain, $second, $needsValues]", ChangeStats(5, 1), opened)

        rule.onNodeWithTag("changes_chip").performClick()
        assertEquals(emptyList<Pair<String, String>>(), opened)
        rule.onAllNodesWithTag("open_changeset").assertCountEquals(2)
        rule.onAllNodesWithTag("open_changeset")[1].performClick()

        assertEquals(listOf("ahp-changeset:/c2" to "Last commit"), opened)
    }

    @Test
    fun withNoTotalsButASetToOpenTheBarSaysChanges() {
        showBar("[$plain]", null)

        rule.onNodeWithTag("changes_chip").assertTextEquals("Changes").assertHasClickAction()
    }

    @Test
    fun totalsWithNothingToOpenAreShownAsPlainText() {
        showBar("[$needsValues]", ChangeStats(3, 0))

        rule.onNodeWithTag("changes_chip").assertTextEquals("(+3, \u22120)").assertHasNoClickAction()
    }

    @Test
    fun withNeitherTotalsNorASetToOpenThereIsNoChangesChip() {
        showBar("[$needsValues]", null)
        rule.onAllNodesWithTag("changes_chip").assertCountEquals(0)
    }

    @Test
    fun theDetailsSheetNoLongerListsTheChanges() {
        showBar("[$plain]", ChangeStats(3, 1))
        rule.onNodeWithTag("session_menu").performClick()
        rule.onNodeWithTag("menu_details").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("details_sheet").assertIsDisplayed()
        rule.onAllNodesWithTag("changesets").assertCountEquals(0)
        rule.onAllNodesWithTag("changeset_unsupported").assertCountEquals(0)
    }
}
