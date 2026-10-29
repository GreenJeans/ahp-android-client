package dev.ahpclient.ui

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ahpclient.state.ActivityState
import dev.ahpclient.state.ApprovalTarget
import dev.ahpclient.state.ChangeStats
import dev.ahpclient.state.DecisionOption
import dev.ahpclient.state.DispatchOutcome
import dev.ahpclient.state.Inbox
import dev.ahpclient.state.InboxItem
import dev.ahpclient.state.InteractResult
import dev.ahpclient.state.ManageResult
import dev.ahpclient.state.RequestRead
import dev.ahpclient.state.SessionRow
import dev.ahpclient.ui.theme.AhpTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The inbox: what waits on you and what finished unread, with the buttons to decide a single tool call and to mark sessions read. */
@RunWith(AndroidJUnit4::class)
class InboxUiTest {
    @get:Rule
    val rule = createComposeRule()

    private fun row(n: Int, state: ActivityState, subtitle: String? = "repo-$n", changes: ChangeStats? = null) = SessionRow(
        resource = "ahp-session:/s$n", title = "Session $n", subtitle = subtitle, state = state, isRead = false, isArchived = false,
        whenText = "${n}h", chatCount = 1, changes = changes,
    )

    private fun waiting(n: Int) = InboxItem(row(n, ActivityState.NEEDS_INPUT), "stamp$n")

    private fun finished(n: Int, state: ActivityState = ActivityState.IDLE) = InboxItem(row(n, state), "stamp$n")

    private val tool = ApprovalTarget(chat = "ahp-chat:/c1", toolCallId = "call-1")
    private val done = InteractResult.Sent(DispatchOutcome.Accepted)

    private class Decisions {
        val decided = mutableListOf<List<Any?>>()
        val opened = mutableListOf<String>()
        val read = mutableListOf<Pair<String, Boolean>>()
        var result: InteractResult = InteractResult.Sent(DispatchOutcome.Accepted)
        var readResults = mutableListOf<ManageResult>()
        var gate: CompletableDeferred<Unit>? = null
        var backs = 0
    }

    private fun manage(d: Decisions) = ManageActionsUi(
        agents = emptyList(), folders = emptyList(),
        setRead = { uri, read -> d.read += uri to read; if (d.readResults.isNotEmpty()) d.readResults.removeAt(0) else ManageResult.Done() },
        setArchived = { _, _ -> ManageResult.Done() }, newChat = { ManageResult.Done() }, newSession = { _, _, _ -> ManageResult.Done() },
        disposeSession = { ManageResult.Done() }, disposeChat = { _, _ -> ManageResult.Done() },
    )

    private fun show(
        inbox: Inbox,
        details: Map<String, RequestRead?> = emptyMap(),
        d: Decisions = Decisions(),
        canDecide: Boolean = true,
        withManage: Boolean = true,
    ) = rule.setContent {
        AhpTheme {
            InboxScreen(
                inbox = inbox, details = details, onBack = { d.backs++ }, onOpen = { d.opened += it }, canDecide = canDecide,
                onDecide = { chat, call, approve, option -> d.gate?.await(); d.decided += listOf(chat, call, approve, option); d.result },
                manage = if (withManage) manage(d) else null,
            )
        }
    }

    // ---- empty and structure -----------------------------------------------------------------------------------

    @Test
    fun anEmptyInboxSaysNothingNeedsYou() {
        show(Inbox(emptyList(), emptyList()))

        rule.onNodeWithTag("inbox_empty").assertIsDisplayed()
        rule.onNodeWithText("Nothing needs you").assertIsDisplayed()
        rule.onAllNodesWithTag("inbox_list").assertCountEquals(0)
    }

    @Test
    fun theTitleAndBackButtonAreThere() {
        val d = Decisions()
        show(Inbox(emptyList(), emptyList()), d = d)

        rule.onNodeWithTag("inbox_title").assertTextEquals("Inbox")
        rule.onNodeWithTag("back").performClick()

        assertEquals(1, d.backs)
    }

    @Test
    fun theTwoGroupsHaveHeadersOnlyWhenTheyHaveSessions() {
        show(Inbox(listOf(waiting(1)), emptyList()))
        rule.onNodeWithTag("inbox_waiting_header").assertIsDisplayed()
        rule.onAllNodesWithTag("inbox_finished_header").assertCountEquals(0)
    }

    // ---- waiting ----------------------------------------------------------------------------------------------

    @Test
    fun aWaitingSessionShowsItsTitleWhatItWaitsForAndItsProject() {
        show(Inbox(listOf(waiting(1)), emptyList()), mapOf("ahp-session:/s1" to RequestRead("Which season do you prefer?")))

        rule.onNodeWithTag("inbox_header").assertContentDescriptionEquals("Session 1. Which season do you prefer?. repo-1. 1h")
        rule.onNodeWithTag("inbox_detail", useUnmergedTree = true).assertTextEquals("Which season do you prefer?")
    }

    @Test
    fun aWaitingSessionWhoseWordsHaveNotArrivedYetIsStillThereAndOpensOnATap() {
        val d = Decisions()
        show(Inbox(listOf(waiting(1)), emptyList()), details = emptyMap(), d = d)

        rule.onAllNodesWithTag("inbox_detail", useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithTag("inbox_approve").assertCountEquals(0)
        rule.onAllNodesWithTag("inbox_open").assertCountEquals(0)
        rule.onNodeWithTag("inbox_header").performClick()

        assertEquals(listOf("ahp-session:/s1"), d.opened)
    }

    @Test
    fun aSingleToolCallHasDenyAndApproveThatDecideThatCall() {
        val d = Decisions()
        show(Inbox(listOf(waiting(1)), emptyList()), mapOf("ahp-session:/s1" to RequestRead("Run in Terminal: ls", tool)), d)

        rule.onNodeWithTag("inbox_deny").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("inbox_approve").assertHeightIsAtLeast(48.dp).performClick()
        rule.waitForIdle()

        assertEquals(listOf(listOf("ahp-chat:/c1", "call-1", false, null), listOf("ahp-chat:/c1", "call-1", true, null)), d.decided)
    }

    @Test
    fun theOutcomeIsSaidInWords() {
        val d = Decisions()
        show(Inbox(listOf(waiting(1)), emptyList()), mapOf("ahp-session:/s1" to RequestRead("Run it", tool)), d)

        rule.onNodeWithTag("inbox_approve").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("inbox_said").assertTextEquals("Approved.")

        d.result = InteractResult.NotConfirmed
        rule.onNodeWithTag("inbox_approve").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("inbox_said").assertTextContains("device check was not completed", substring = true)
    }

    @Test
    fun aDenialIsSaidAsDenied() {
        val d = Decisions()
        show(Inbox(listOf(waiting(1)), emptyList()), mapOf("ahp-session:/s1" to RequestRead("Run it", tool)), d)

        rule.onNodeWithTag("inbox_deny").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("inbox_said").assertTextEquals("Denied.")
    }

    @Test
    fun theButtonsAreOffWhileADecisionIsBeingMade() {
        val d = Decisions().apply { gate = CompletableDeferred() }
        show(Inbox(listOf(waiting(1)), emptyList()), mapOf("ahp-session:/s1" to RequestRead("Run it", tool)), d)

        rule.onNodeWithTag("inbox_approve").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("inbox_approve").assertIsNotEnabled()
        rule.onNodeWithTag("inbox_deny").assertIsNotEnabled()

        d.gate!!.complete(Unit)
        rule.waitForIdle()
        rule.onNodeWithTag("inbox_approve").assertIsEnabled()
        assertEquals("the second tap while it was pending did nothing", 1, d.decided.size)
    }

    @Test
    fun aCallWithTheHostsOwnOptionsHasThoseAsTheButtonsEachDecidingWithItsOption() {
        val d = Decisions()
        val withOptions = ApprovalTarget(
            "ahp-chat:/c1", "call-1",
            listOf(DecisionOption("o-no", "Not now", false), DecisionOption("o-once", "Allow once", true), DecisionOption("o-session", "Allow in this session", true)),
        )
        show(Inbox(listOf(waiting(1)), emptyList()), mapOf("ahp-session:/s1" to RequestRead("Run it", withOptions)), d)

        rule.onAllNodesWithTag("inbox_option").assertCountEquals(3)
        rule.onAllNodesWithTag("inbox_deny").assertCountEquals(0)
        rule.onAllNodesWithTag("inbox_option")[0].assertTextEquals("Not now").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag("inbox_option")[2].assertTextEquals("Allow in this session").performClick()
        rule.waitForIdle()

        assertEquals(
            listOf(listOf("ahp-chat:/c1", "call-1", false, "o-no"), listOf("ahp-chat:/c1", "call-1", true, "o-session")),
            d.decided,
        )
    }

    @Test
    fun whenTheAppMayNotActForYouThereAreNoDecisionButtonsAndThereIsOpen() {
        val d = Decisions()
        show(Inbox(listOf(waiting(1)), emptyList()), mapOf("ahp-session:/s1" to RequestRead("Run it", tool)), d, canDecide = false)

        rule.onAllNodesWithTag("inbox_approve").assertCountEquals(0)
        rule.onAllNodesWithTag("inbox_deny").assertCountEquals(0)
        rule.onNodeWithTag("inbox_open").assertHeightIsAtLeast(48.dp).performClick()

        assertEquals(listOf("ahp-session:/s1"), d.opened)
        assertTrue(d.decided.isEmpty())
    }

    @Test
    fun aQuestionHasOpenNotApprove() {
        val d = Decisions()
        show(Inbox(listOf(waiting(1)), emptyList()), mapOf("ahp-session:/s1" to RequestRead("Which season do you prefer?", null)), d)

        rule.onAllNodesWithTag("inbox_approve").assertCountEquals(0)
        rule.onNodeWithTag("inbox_open").performClick()

        assertEquals(listOf("ahp-session:/s1"), d.opened)
    }

    @Test
    fun aSessionWithNothingToShowStillHasOpen() {
        show(Inbox(listOf(waiting(1)), emptyList()), mapOf("ahp-session:/s1" to null))

        rule.onNodeWithTag("inbox_open").assertIsDisplayed()
    }

    @Test
    fun eachWaitingSessionDecidesItsOwnCall() {
        val d = Decisions()
        val other = ApprovalTarget("ahp-chat:/c2", "call-2")
        show(
            Inbox(listOf(waiting(1), waiting(2)), emptyList()),
            mapOf("ahp-session:/s1" to RequestRead("one", tool), "ahp-session:/s2" to RequestRead("two", other)),
            d,
        )

        rule.onAllNodesWithTag("inbox_approve")[1].performClick()
        rule.waitForIdle()

        assertEquals(listOf(listOf("ahp-chat:/c2", "call-2", true, null)), d.decided)
        rule.onAllNodesWithTag("inbox_said").assertCountEquals(1)
    }

    // ---- finished ---------------------------------------------------------------------------------------------

    @Test
    fun aFinishedSessionSaysItFinishedAndAFailedOneSaysItFailed() {
        show(Inbox(emptyList(), listOf(finished(1), finished(2, ActivityState.ERROR))))

        val headers = rule.onAllNodesWithTag("inbox_header")
        headers[0].assertContentDescriptionEquals("Session 1. Finished · repo-1. 1h")
        headers[1].assertContentDescriptionEquals("Session 2. Failed · repo-2. 2h")
    }

    @Test
    fun theChangesAreShownOnAFinishedSession() {
        val withChanges = InboxItem(row(1, ActivityState.IDLE, changes = ChangeStats(1072, 8, 0)), "s")
        show(Inbox(emptyList(), listOf(withChanges)))

        rule.onNodeWithTag("inbox_header").assertContentDescriptionEquals("Session 1. Finished · repo-1 · (+1072, −8). 1h")
    }

    @Test
    fun aFinishedSessionOpensOnATap() {
        val d = Decisions()
        show(Inbox(emptyList(), listOf(finished(3))), d = d)

        rule.onNodeWithTag("inbox_header").performClick()

        assertEquals(listOf("ahp-session:/s3"), d.opened)
    }

    @Test
    fun markReadIsOfferedOnlyWhenTheAppMayLookAfterSessionsAndMarksThatSession() {
        val d = Decisions()
        show(Inbox(emptyList(), listOf(finished(1), finished(2))), d = d)

        rule.onAllNodesWithTag("inbox_mark_read").assertCountEquals(2)
        rule.onAllNodesWithTag("inbox_mark_read")[1].assertHeightIsAtLeast(48.dp).performClick()
        rule.waitForIdle()

        assertEquals(listOf("ahp-session:/s2" to true), d.read)
    }

    @Test
    fun withoutThatPermissionThereIsNoWayToMarkRead() {
        show(Inbox(emptyList(), listOf(finished(1), finished(2))), withManage = false)

        rule.onAllNodesWithTag("inbox_mark_read").assertCountEquals(0)
        rule.onAllNodesWithTag("inbox_mark_all").assertCountEquals(0)
    }

    @Test
    fun aRefusedMarkReadSaysWhy() {
        val d = Decisions().apply { readResults = mutableListOf(ManageResult.NotConfirmed) }
        show(Inbox(emptyList(), listOf(finished(1))), d = d)

        rule.onNodeWithTag("inbox_mark_read").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("inbox_problem").assertIsDisplayed()
    }

    @Test
    fun markAllReadIsOfferedForSeveralAndMarksEachInOrder() {
        val d = Decisions()
        show(Inbox(emptyList(), listOf(finished(1), finished(2), finished(3))), d = d)

        rule.onNodeWithTag("inbox_mark_all").assertHeightIsAtLeast(48.dp).performClick()
        rule.waitForIdle()

        assertEquals(listOf("ahp-session:/s1" to true, "ahp-session:/s2" to true, "ahp-session:/s3" to true), d.read)
    }

    @Test
    fun markAllReadStopsAtTheFirstRefusalAndSaysSo() {
        val d = Decisions().apply { readResults = mutableListOf(ManageResult.Done(), ManageResult.NotConfirmed) }
        show(Inbox(emptyList(), listOf(finished(1), finished(2), finished(3))), d = d)

        rule.onNodeWithTag("inbox_mark_all").performClick()
        rule.waitForIdle()

        assertEquals(listOf("ahp-session:/s1" to true, "ahp-session:/s2" to true), d.read)
        rule.onNodeWithTag("inbox_notice").assertIsDisplayed()
    }

    @Test
    fun aSingleFinishedSessionHasNoMarkAll() {
        show(Inbox(emptyList(), listOf(finished(1))))

        rule.onAllNodesWithTag("inbox_mark_all").assertCountEquals(0)
        rule.onNodeWithTag("inbox_mark_read").assertIsDisplayed()
    }

    @Test
    fun bothGroupsAreShownTogether() {
        show(Inbox(listOf(waiting(1)), listOf(finished(2))), mapOf("ahp-session:/s1" to RequestRead("Run it", tool)))

        rule.onNodeWithTag("inbox_waiting_header").assertIsDisplayed()
        rule.onNodeWithTag("inbox_waiting").assertIsDisplayed()
        rule.onNodeWithTag("inbox_finished_header").assertIsDisplayed()
        rule.onNodeWithTag("inbox_finished").assertIsDisplayed()
    }
}
