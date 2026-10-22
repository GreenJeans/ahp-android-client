package dev.ahpclient.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ahpclient.state.ActivityState
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.SessionGoneException
import dev.ahpclient.tunnel.TunnelInfo
import dev.ahpclient.ui.theme.AhpTheme
import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.SessionState
import com.microsoft.agenthostprotocol.generated.SessionSummary
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The sessions list and the session detail, as pure functions of what the host reported. */
@RunWith(AndroidJUnit4::class)
class SessionScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val host = TunnelInfo("t-online", "usw2", "dev-box", 6, 1, listOf(31546))
    private val now = Instant.parse("2026-10-26T10:30:00Z")

    private fun summary(uri: String, title: String, status: Int, modified: String, extra: String = ""): SessionSummary =
        Ahp.json.decodeFromString(
            SessionSummary.serializer(),
            """{"resource":"$uri","provider":"copilotcli","title":"$title","status":$status,"createdAt":"2026-10-20T10:00:00.000Z","modifiedAt":"$modified"$extra}""",
        )

    private val waiting = summary("ahp-session:/w", "Needs answer", 56, "2026-10-26T10:00:00.000Z", ""","project":{"uri":"file:///w","displayName":"repo-w"}""")
    private val working = summary("ahp-session:/k", "Working", 40, "2026-10-26T10:25:00.000Z", ""","activity":"Running tests"""")
    private val unread = summary("ahp-session:/u", "Unread one", 1, "2026-10-26T08:30:00.000Z")
    private val archived = summary("ahp-session:/a", "Old one", 97, "2026-10-26T09:30:00.000Z")
    private val all = listOf(unread, archived, working, waiting)

    // Material 3 controls draw smaller than they touch, so the guideline is checked on touch bounds.
    private fun SemanticsNodeInteraction.assertTouchTargetAtLeast(minimum: Dp): SemanticsNodeInteraction {
        val bounds = fetchSemanticsNode().touchBoundsInRoot
        val minimumPx = with(rule.density) { minimum.toPx() }
        assertTrue("touch target is ${bounds.width} x ${bounds.height} px, smaller than $minimum ($minimumPx px)", bounds.height + 0.5f >= minimumPx && bounds.width + 0.5f >= minimumPx)
        return this
    }

    private fun showSessions(
        sessions: List<SessionSummary> = all,
        connection: ConnState = ConnState.Online,
        initialShowArchived: Boolean = false,
        onOpen: (String) -> Unit = {},
        onToggle: () -> Unit = {},
        onDisconnect: () -> Unit = {},
    ) = rule.setContent {
        var showArchived by androidx.compose.runtime.remember { mutableStateOf(initialShowArchived) }
        AhpTheme {
            SessionsScreen(host, connection, sessions, now, showArchived, rememberLazyListState(), { showArchived = !showArchived; onToggle() }, onOpen, onDisconnect)
        }
    }

    private fun detailState(inputNeeded: String? = null): SessionState = Ahp.json.decodeFromString(
        SessionState.serializer(),
        """{"provider":"copilotcli","title":"Fix the build","status":56,"lifecycle":"ready","activeClients":[],"activity":"Waiting on you",
            "project":{"uri":"file:///work/repo","displayName":"repo"},"workingDirectories":["file:///work/repo"],
            "chats":[{"resource":"ahp-chat://default/x","title":"Main chat","status":56,"modifiedAt":"2026-10-26T10:29:00.000Z"}]
            ${if (inputNeeded != null) ""","inputNeeded":$inputNeeded""" else ""}}""",
    )

    private fun showDetail(state: SessionState?, error: Throwable? = null, connection: ConnState = ConnState.Online, onBack: () -> Unit = {}) =
        rule.setContent { AhpTheme { SessionDetailScreen(state, error, connection, now, onBack) } }

    // ---- list -------------------------------------------------------------------------------------------------

    @Test
    fun rowsShowNeedsInputFirstAndHideArchived() {
        showSessions()

        rule.onAllNodesWithTag("session_row").assertCountEquals(3)
        rule.onAllNodesWithTag("session_row")[0].assertContentDescriptionEquals("Needs answer, Needs input, repo-w, 30 min ago")
        rule.onAllNodesWithTag("session_row")[1].assertContentDescriptionEquals("Working, In progress, Running tests, 5 min ago")
        rule.onAllNodesWithTag("session_row")[2].assertContentDescriptionEquals("Unread one, Idle, 2 h ago, unread")
    }

    @Test
    fun theSummaryLineCountsWhatIsHappening() {
        showSessions()

        rule.onNodeWithTag("summary").assertTextEquals("1 needs input · 1 in progress")
    }

    @Test
    fun everyRowStatesItsStateInWords() {
        showSessions()

        val labels = rule.onAllNodesWithText("Needs input", useUnmergedTree = true)
        labels.assertCountEquals(1)
        rule.onAllNodesWithText("In progress", useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodesWithText("Idle", useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodesWithTag("status_chip", useUnmergedTree = true).assertCountEquals(3)
    }

    @Test
    fun theArchivedToggleRevealsArchivedSessionsAndKeepsItsLabel() {
        var toggles = 0
        showSessions(onToggle = { toggles++ })

        rule.onNodeWithTag("archived_toggle").assertTextContains("Show archived (1)").assertTouchTargetAtLeast(48.dp).performClick()

        assertEquals(1, toggles)
        rule.onAllNodesWithTag("session_row").assertCountEquals(4)
        rule.onNodeWithTag("archived_toggle").assertTextContains("Show archived")
    }

    @Test
    fun noToggleIsOfferedWhenNothingIsArchived() {
        showSessions(listOf(waiting, working))

        rule.onAllNodesWithTag("archived_toggle").assertCountEquals(0)
    }

    @Test
    fun tappingARowOpensThatSession() {
        val opened = mutableListOf<String>()
        showSessions(onOpen = { opened += it })

        rule.onAllNodesWithTag("session_row")[1].assertTouchTargetAtLeast(48.dp).performClick()

        assertEquals(listOf("ahp-session:/k"), opened)
    }

    @Test
    fun backToHostsWorksAndIsBigEnough() {
        var back = 0
        showSessions(onDisconnect = { back++ })

        rule.onNodeWithTag("disconnect").assertTouchTargetAtLeast(48.dp).performClick()

        assertEquals(1, back)
    }

    @Test
    fun anEmptyHostSaysSo() {
        showSessions(emptyList())

        rule.onNodeWithTag("empty_list").assertIsDisplayed()
        rule.onNodeWithText("No sessions on this host yet.").assertIsDisplayed()
    }

    @Test
    fun aHostWithOnlyArchivedSessionsPointsAtTheToggle() {
        showSessions(listOf(archived))

        rule.onNodeWithText("All 1 sessions are archived", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("archived_toggle").assertIsDisplayed()
    }

    @Test
    fun whileConnectingTheListShowsAWordLabelNotJustASpinner() {
        showSessions(emptyList(), connection = ConnState.Connecting)

        rule.onNodeWithText("Loading sessions", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("connection_status").assertTextContains("Connecting", substring = true)
    }

    @Test
    fun aConnectionFailureIsShownAsText() {
        showSessions(emptyList(), connection = ConnState.Failed(IllegalStateException("The tunnel lists no running agent host")))

        rule.onNodeWithTag("connection_status").assertTextContains("no running agent host", substring = true)
    }

    @Test
    fun aDroppedConnectionKeepsTheSessionsVisible() {
        showSessions(connection = ConnState.Reconnecting(1, null))

        rule.onNodeWithTag("connection_status").assertTextContains("Reconnecting", substring = true)
        rule.onAllNodesWithTag("session_row").assertCountEquals(3)
    }

    // ---- detail -----------------------------------------------------------------------------------------------

    @Test
    fun detailShowsALoadingLabelUntilTheSnapshotArrives() {
        showDetail(null)

        rule.onNodeWithTag("detail_loading").assertIsDisplayed()
        rule.onNodeWithText("Loading session", substring = true).assertIsDisplayed()
    }

    @Test
    fun detailSaysWhenTheSessionIsGone() {
        showDetail(null, SessionGoneException("ahp-session:/x"))

        rule.onNodeWithTag("session_gone").assertTextContains("no longer on the host", substring = true)
    }

    @Test
    fun detailShowsAnUnexpectedErrorByType() {
        showDetail(null, IllegalStateException("boom"))

        rule.onNodeWithTag("detail_error").assertTextContains("IllegalStateException", substring = true)
    }

    @Test
    fun detailShowsTheSessionAndWhatItWaitsOn() {
        val request = """[{"kind":"chatInput","id":"r1","chat":"c","request":{"id":"q","message":"Which branch should I use?"}}]"""
        showDetail(detailState(request))

        rule.onNodeWithTag("detail_title").assertTextEquals("Fix the build")
        rule.onNodeWithTag("detail_activity").assertTextEquals("Waiting on you")
        rule.onNodeWithTag("input_request").assertTextContains("Which branch should I use?", substring = true)
        rule.onNodeWithTag("answer_hint").assertTextEquals("Answer in VS Code.")
        rule.onNodeWithTag("chat_row").assertTextContains("Main chat", substring = true)
        rule.onNodeWithText("repo").assertIsDisplayed()
    }

    @Test
    fun detailOffersNoWaitingSectionWhenNothingIsPending() {
        showDetail(detailState())

        rule.onAllNodesWithTag("input_needed").assertCountEquals(0)
        rule.onAllNodesWithTag("answer_hint").assertCountEquals(0)
    }

    @Test
    fun detailBackWorksAndIsBigEnough() {
        var back = 0
        showDetail(detailState(), onBack = { back++ })

        rule.onNodeWithTag("back").assertTouchTargetAtLeast(48.dp).performClick()

        assertEquals(1, back)
    }

    @Test
    fun detailMentionsALostConnection() {
        showDetail(detailState(), connection = ConnState.Reconnecting(3, null))

        rule.onNodeWithTag("connection_status").assertTextContains("Reconnecting (attempt 3)", substring = true)
    }

    @Test
    fun theChipShowsAWordForEveryState() {
        rule.setContent {
            AhpTheme {
                androidx.compose.foundation.layout.Column {
                    ActivityState.entries.forEach { StatusChip(it) }
                }
            }
        }

        val chips = rule.onAllNodesWithTag("status_chip")
        chips.assertCountEquals(4)
        listOf("Needs input", "In progress", "Error", "Idle").forEachIndexed { i, words -> chips[i].assertTextEquals(words) }
    }
}
