package dev.ahpclient.ui

import androidx.compose.ui.test.assertIsDisplayed
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

    private fun show(state: ChangesetState?, error: Throwable? = null, connection: ConnState = ConnState.Online, onBack: () -> Unit = {}) =
        rule.setContent { AhpTheme { ChangesetScreen("Uncommitted", state, error, connection, onBack) } }

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

    // ---- the way in from a session ----------------------------------------------------------------------------------

    private fun session(changesets: String): SessionState = Ahp.json.decodeFromString(
        SessionState.serializer(),
        """{"provider":"copilotcli","title":"Fix the build","status":1,"lifecycle":"ready","activeClients":[],"chats":[],"changesets":$changesets}""",
    )

    @Test
    fun aPlainChangesetCanBeOpenedAndOneThatNeedsValuesIsOnlyMentioned() {
        val opened = mutableListOf<Pair<String, String>>()
        val state = session(
            """[{"label":"Uncommitted","uriTemplate":"ahp-changeset:/c1","changeKind":"uncommitted"},{"label":"Per turn","uriTemplate":"ahp-changeset:/t/{turnId}","changeKind":"turn"}]""",
        )
        rule.setContent {
            AhpTheme { SessionDetailScreen(state, null, ConnState.Online, Instant.parse("2026-10-26T10:30:00Z"), {}, onOpenChangeset = { u, l -> opened += u to l }) }
        }

        rule.onNodeWithTag("open_changeset").performScrollTo().performClick()
        rule.onNodeWithTag("changeset_unsupported").performScrollTo().assertTextContains("Per turn", substring = true)

        assertEquals(listOf("ahp-changeset:/c1" to "Uncommitted"), opened)
        assertEquals(1, rule.onAllNodes(hasTestTag("open_changeset")).fetchSemanticsNodes().size)
    }

    @Test
    fun aSessionWithNoChangesetsHasNoChangesSection() {
        rule.setContent { AhpTheme { SessionDetailScreen(session("[]"), null, ConnState.Online, Instant.parse("2026-10-26T10:30:00Z"), {}) } }

        rule.onNodeWithTag("changesets").assertDoesNotExist()
    }
}
