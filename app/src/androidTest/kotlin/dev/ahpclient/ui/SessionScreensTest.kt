package dev.ahpclient.ui

import dev.ahpclient.app.HostRef
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ahpclient.state.ActivityState
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.ManageResult
import dev.ahpclient.state.RelativeTime
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

    private fun hasContentDescriptionContaining(text: String) = SemanticsMatcher("content description contains $text") { node ->
        node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.any { it.contains(text) } == true
    }

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
        onOpenHost: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
        initialQuery: String = "",
        selected: String? = null,
        connectedHost: TunnelInfo? = host,
        manage: ManageActionsUi? = null,
        watched: Set<String> = emptySet(),
        inboxCount: Int? = null,
        onOpenInbox: () -> Unit = {},
    ) = rule.setContent {
        var showArchived by androidx.compose.runtime.remember { mutableStateOf(initialShowArchived) }
        var query by androidx.compose.runtime.remember { mutableStateOf(initialQuery) }
        AhpTheme {
            DrawerContent(
                connectedHost?.let(HostRef::Tunnel), connection, sessions, now, selected, showArchived, { showArchived = !showArchived; onToggle() }, query, { query = it },
                rememberLazyListState(), manage, onOpenHost, onOpen, onOpenSettings, onSessionCreated = {}, watched = watched, inboxCount = inboxCount, onOpenInbox = onOpenInbox,
            )
        }
    }

    private fun showSettings(
        monitorEnabled: Boolean = false,
        notificationsAllowed: Boolean = true,
        onMonitorChange: (Boolean) -> Unit = {},
        onBack: () -> Unit = {},
        finishedAll: Boolean = false,
        onFinishedAll: (Boolean) -> Unit = {},
    ) = rule.setContent {
        AhpTheme {
            SettingsScreen(
                onBack, monitorEnabled = monitorEnabled, notificationsAllowed = notificationsAllowed, onMonitorChange = onMonitorChange,
                finishedAll = finishedAll, onFinishedAll = onFinishedAll,
            )
        }
    }

    private val mainChatUri = "ahp-chat://default/x"
    private val sideChatUri = "ahp-chat://default/y"

    private fun detailState(inputNeeded: String? = null, chats: String = """[{"resource":"$mainChatUri","title":"Main chat","status":56,"modifiedAt":"2026-10-26T10:29:00.000Z"}]""", defaultChat: String? = null, meta: String? = null): SessionState =
        Ahp.json.decodeFromString(
            SessionState.serializer(),
            """{"provider":"copilotcli","title":"Fix the build","status":56,"lifecycle":"ready","activeClients":[],"activity":"Waiting on you",
                "project":{"uri":"file:///work/repo","displayName":"repo"},"workingDirectories":["file:///work/repo"],
                "chats":$chats
                ${if (defaultChat != null) ""","defaultChat":"$defaultChat"""" else ""}
                ${if (inputNeeded != null) ""","inputNeeded":$inputNeeded""" else ""}
                ${if (meta != null) ""","_meta":$meta""" else ""}}""",
        )

    private val twoChats = """[{"resource":"$sideChatUri","title":"Side chat","status":56,"modifiedAt":"2026-10-26T10:28:00.000Z"},
        {"resource":"$mainChatUri","title":"Main chat","status":1,"modifiedAt":"2026-10-26T10:29:00.000Z"}]"""

    /** The session screen, with a stand-in for the chat and the selection the controller would keep: the screen asks, this holds the answer. */
    private fun showDetail(
        state: SessionState?,
        error: Throwable? = null,
        connection: ConnState = ConnState.Online,
        onBack: () -> Unit = {},
        shown: String? = mainChatUri,
        onSelectChat: (String) -> Unit = {},
    ) = rule.setContent {
        var shownChat by androidx.compose.runtime.remember { mutableStateOf(shown) }
        AhpTheme {
            SessionScreen(state, error, connection, now, onBack, shownChat = shownChat, onSelectChat = { shownChat = it; onSelectChat(it) }) {
                androidx.compose.material3.Text("chat body: $shownChat", Modifier.testTag("chat_body"))
            }
        }
    }

    private fun openDetails() {
        rule.onNodeWithTag("session_menu").performClick()
        rule.onNodeWithTag("menu_details").performClick()
        rule.waitForIdle()
    }

    // ---- list -------------------------------------------------------------------------------------------------

    @Test
    fun rowsShowNeedsInputFirstAndHideArchived() {
        showSessions()

        rule.onAllNodesWithTag("session_row").assertCountEquals(3)
        rule.onAllNodesWithTag("session_row")[0].assertContentDescriptionEquals("Needs answer, Needs you, repo-w, 30m")
        rule.onAllNodesWithTag("session_row")[1].assertContentDescriptionEquals("Working, Working, Running tests, 5m")
        rule.onAllNodesWithTag("session_row")[2].assertContentDescriptionEquals("Unread one, Idle, 2h, unread")
    }

    @Test
    fun theSummaryLineCountsWhatIsHappening() {
        showSessions()

        rule.onNodeWithTag("summary").assertTextEquals("1 needs you · 1 working")
    }

    @Test
    fun everyRowHasAStateMarkAndItsSpokenDescriptionStatesTheStateInWords() {
        showSessions()

        rule.onAllNodesWithTag("state_mark", useUnmergedTree = true).assertCountEquals(3)
        val spoken = (0 until 3).map { spokenRow(it) }
        assertTrue(spoken.toString(), spoken[0].contains("Needs you") && spoken[1].contains("Working") && spoken[2].contains("Idle"))
    }

    private fun spokenRow(index: Int): String =
        rule.onAllNodesWithTag("session_row")[index].fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString()

    @Test
    fun theArchivedToggleRevealsArchivedSessionsAndKeepsItsLabel() {
        var toggles = 0
        showSessions(onToggle = { toggles++ })

        rule.onNodeWithTag("archived_toggle").assertContentDescriptionEquals("Show archived").assertIsOff().assertTouchTargetAtLeast(48.dp).performClick()

        assertEquals(1, toggles)
        rule.onAllNodesWithTag("session_row").assertCountEquals(4)
        rule.onNodeWithTag("archived_toggle").assertContentDescriptionEquals("Show archived").assertIsOn()
    }

    @Test
    fun theArchivedToggleCountsWhatIsHiddenAndSaysOnOrOff() {
        showSessions()
        val state = androidx.compose.ui.semantics.SemanticsProperties.StateDescription

        rule.onNodeWithTag("archived_count", useUnmergedTree = true).assertTextEquals("1")
        assertEquals("Off, 1 hidden", rule.onNodeWithTag("archived_toggle").fetchSemanticsNode().config[state])

        rule.onNodeWithTag("archived_toggle").performClick()

        rule.onAllNodesWithTag("archived_count", useUnmergedTree = true).assertCountEquals(0)
        assertEquals("On", rule.onNodeWithTag("archived_toggle").fetchSemanticsNode().config[state])
    }

    @Test
    fun theArchivedToggleStaysWhileTheyAreShownEvenWhenNoneAreArchived() {
        showSessions(listOf(waiting, working), initialShowArchived = true)

        rule.onNodeWithTag("archived_toggle").assertContentDescriptionEquals("Show archived").assertIsOn()
    }

    @Test
    fun theDrawerHasIconButtonsButNoneOfTheirNamesAsText() {
        showSessions(manage = manageActions(), inboxCount = 2)

        listOf("Inbox", "Archived", "New session", "Settings").forEach { word ->
            rule.onAllNodesWithText(word, substring = true).assertCountEquals(0)
        }
        listOf("drawer_inbox", "archived_toggle", "new_session", "settings").forEach { tag ->
            rule.onNodeWithTag(tag).assertIsDisplayed().assertTouchTargetAtLeast(48.dp)
        }
    }

    @Test
    fun theActionRowSitsUnderTheHostAndAboveTheSessions() {
        showSessions(manage = manageActions(), inboxCount = 2)

        // Touch bounds, since the buttons draw smaller than they touch.
        fun top(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().touchBoundsInRoot.top
        fun left(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().touchBoundsInRoot.left
        assertTrue(top("drawer_host") < top("drawer_inbox"))
        assertEquals(top("drawer_inbox"), top("settings"), 0.5f)
        assertTrue(top("settings") < top("session_list"))
        assertTrue(left("drawer_inbox") < left("archived_toggle") && left("archived_toggle") < left("new_session") && left("new_session") < left("settings"))
        // Archived leaves the Sessions header, and Settings the bottom of the drawer.
        assertTrue(top("settings") < rule.onNodeWithText("Sessions").fetchSemanticsNode().boundsInRoot.top)
    }

    @Test
    fun aLongPressNamesAnIconButtonInATooltip() {
        showSessions()

        rule.onAllNodesWithText("Settings").assertCountEquals(0)
        rule.onNodeWithTag("settings").performTouchInput { longClick() }

        rule.onNodeWithText("Settings").assertIsDisplayed()
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
    fun theHostButtonNamesTheHostAndItsConnectionAndOpensTheHostSheet() {
        var opened = 0
        showSessions(onOpenHost = { opened++ })

        rule.onNodeWithTag("drawer_host_name", useUnmergedTree = true).assertTextEquals("dev-box")
        rule.onNodeWithTag("connection_status", useUnmergedTree = true).assertTextContains("Connected", substring = true)
        rule.onNodeWithTag("drawer_host").assertTouchTargetAtLeast(48.dp).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun withNoHostTheDrawerSaysToConnectAndHasNoList() {
        showSessions(emptyList(), connection = ConnState.Connecting, connectedHost = null)

        rule.onNodeWithTag("drawer_host_name", useUnmergedTree = true).assertTextEquals("Not connected")
        rule.onNodeWithTag("drawer_no_host").assertIsDisplayed()
        rule.onAllNodesWithTag("session_list").assertCountEquals(0)
        rule.onAllNodesWithTag("new_session").assertCountEquals(0)
        rule.onNodeWithTag("settings").assertIsDisplayed()
    }

    @Test
    fun theOpenSessionIsMarkedInTheList() {
        showSessions(selected = "ahp-session:/k")

        val state = androidx.compose.ui.semantics.SemanticsProperties.StateDescription
        assertEquals("Open", rule.onAllNodesWithTag("session_row")[1].fetchSemanticsNode().config[state])
        assertTrue(rule.onAllNodesWithTag("session_row")[0].fetchSemanticsNode().config.contains(state).not())
    }

    @Test
    fun aRowShowsWhatTheSessionChangedAndSpeaksItInWords() {
        val changed = summary("ahp-session:/c", "Changed one", 1, "2026-10-26T10:00:00.000Z", ""","changes":{"additions":1072,"deletions":8,"files":14}""")
        showSessions(listOf(changed, unread))

        rule.onAllNodesWithTag("row_changes", useUnmergedTree = true).assertCountEquals(1)
        rule.onNodeWithTag("row_changes", useUnmergedTree = true).assertTextEquals("(+1072, \u22128)")
        assertTrue(spokenRow(0), spokenRow(0).contains("1072 lines added, 8 removed, in 14 files"))
    }

    @Test
    fun aRowShowsTheBranchTheHostReportsAndASessionWithoutOneShowsNone() {
        val onBranch = summary("ahp-session:/b", "On a branch", 1, "2026-10-26T10:00:00.000Z", ""","_meta":{"git":{"branchName":"feature/phone","outgoingChanges":2}}""")
        showSessions(listOf(onBranch, unread))

        rule.onAllNodesWithTag("row_branch", useUnmergedTree = true).assertCountEquals(1)
        rule.onNodeWithTag("row_branch", useUnmergedTree = true).assertTextEquals("feature/phone")
        assertTrue(spokenRow(0), spokenRow(0).contains("branch feature/phone"))
        assertTrue(spokenRow(1), !spokenRow(1).contains("branch"))
    }

    @Test
    fun newSessionIsNotOfferedWhenTheAppMayNotManageSessions() {
        showSessions()
        rule.onAllNodesWithTag("new_session").assertCountEquals(0)
    }

    @Test
    fun theSettingsButtonInTheActionRowOpensSettingsAndIsBigEnough() {
        var opened = 0
        showSessions(onOpenSettings = { opened++ })

        rule.onNodeWithTag("settings").assertContentDescriptionEquals("Settings").assertTouchTargetAtLeast(48.dp).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun theNewSessionButtonIsAnIconWithASpokenNameAndAFullSizeTarget() {
        showSessions(manage = manageActions())

        rule.onNodeWithTag("new_session").assertContentDescriptionEquals("New session").assertTouchTargetAtLeast(48.dp).assertHasClickAction()
    }

    @Test
    fun theNewSessionIconOpensTheSameDialogAsTheButton() {
        showSessions(manage = manageActions())

        rule.onNodeWithTag("new_session").performClick()

        rule.onNodeWithText("Start a new session").assertIsDisplayed()
    }

    private fun manageActions() = ManageActionsUi(
        agents = emptyList(),
        folders = emptyList(),
        setRead = { _, _ -> ManageResult.Done() },
        setArchived = { _, _ -> ManageResult.Done() },
        newChat = { ManageResult.Done() },
        newSession = { _, _, _ -> ManageResult.Done() },
        disposeSession = { ManageResult.Done() },
        disposeChat = { _, _ -> ManageResult.Done() },
    )

    @Test
    fun theSwitchesAreNotOnTheListAnyMore() {
        showSessions()

        rule.onNodeWithTag("monitor_toggle").assertDoesNotExist()
        rule.onNodeWithTag("interaction_toggle").assertDoesNotExist()
        rule.onNodeWithTag("management_toggle").assertDoesNotExist()
    }

    @Test
    fun searchNarrowsTheListAsYouTypeAndClearsWithItsButton() {
        showSessions()

        rule.onNodeWithTag("search").performTextInput("work")

        rule.onAllNodesWithTag("session_row").assertCountEquals(1)
        rule.onAllNodesWithTag("session_row")[0].assertContentDescriptionEquals("Working, Working, Running tests, 5m")
        rule.onNodeWithTag("search_clear").assertContentDescriptionEquals("Clear search").assertTouchTargetAtLeast(48.dp).performClick()
        rule.onAllNodesWithTag("session_row").assertCountEquals(3)
        rule.onNodeWithTag("search_clear").assertDoesNotExist()
    }

    @Test
    fun aSearchWithNoMatchSaysSoAndNamesWhatWasSearchedFor() {
        showSessions(initialQuery = "zzz")

        rule.onNodeWithTag("empty_list").assertIsDisplayed()
        rule.onNodeWithText("No sessions match \"zzz\".").assertIsDisplayed()
        rule.onNodeWithTag("search").assertIsDisplayed()
    }

    @Test
    fun searchingDoesNotChangeTheSummaryOfWhatNeedsYou() {
        showSessions(initialQuery = "Unread")

        rule.onAllNodesWithTag("session_row").assertCountEquals(1)
        rule.onNodeWithTag("summary").assertTextEquals("1 needs you · 1 working")
    }

    @Test
    fun theFinishedAlertSwitchIsOffByDefaultAndTurnsOnWithAFullWidthTouchTarget() {
        val changes = mutableListOf<Boolean>()
        showSettings(monitorEnabled = true, onFinishedAll = { changes += it })

        rule.onNodeWithTag("finished_all_toggle").assertIsOff().assertTouchTargetAtLeast(48.dp).performClick()

        assertEquals(listOf(true), changes)
        rule.onNodeWithTag("finished_all_hint").assertTextContains("Off", substring = true)
    }

    @Test
    fun anOnFinishedAlertTurnsOffAndSaysWhatItDoes() {
        val changes = mutableListOf<Boolean>()
        showSettings(monitorEnabled = true, finishedAll = true, onFinishedAll = { changes += it })

        rule.onNodeWithTag("finished_all_toggle").assertIsOn().performClick()

        assertEquals(listOf(false), changes)
        rule.onNodeWithTag("finished_all_hint").assertTextContains("told when a session stops", substring = true)
    }

    @Test
    fun theFinishedAlertSaysItNeedsMonitoringWhenThatIsOff() {
        showSettings(monitorEnabled = false, finishedAll = true)

        rule.onNodeWithTag("finished_all_hint").assertTextContains("needs Monitor in background", substring = true)
    }

    @Test
    fun theFinishedAlertSwitchSitsWithTheMonitorSwitch() {
        showSettings()

        rule.onNodeWithTag("finished_all_toggle").assertIsDisplayed()
        rule.onNodeWithTag("monitor_toggle").assertIsDisplayed()
    }

    @Test
    fun aSessionChosenForAlertsHasABellInTheListAndSaysSo() {
        showSessions(watched = setOf("ahp-session:/k"))

        rule.onAllNodesWithTag("row_alert", useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodesWithTag("session_row").filter(hasText("Working")).onFirst().assert(hasContentDescriptionContaining("alert when done is on"))
        rule.onAllNodesWithTag("session_row").filter(hasText("Unread one")).onFirst().assert(!hasContentDescriptionContaining("alert when done"))
    }

    @Test
    fun theInboxEntryShowsHowManyWaitAndOpensTheInbox() {
        var opened = 0
        showSessions(inboxCount = 3, onOpenInbox = { opened++ })

        rule.onNodeWithTag("drawer_inbox").assertContentDescriptionEquals("Inbox, 3 need you").assertTouchTargetAtLeast(48.dp)
        rule.onNodeWithTag("inbox_badge", useUnmergedTree = true).assertTextEquals("3")
        rule.onNodeWithTag("drawer_inbox").performClick()

        assertEquals(1, opened)
    }

    @Test
    fun theInboxEntrySaysNeedsForOne() {
        showSessions(inboxCount = 1)

        rule.onNodeWithTag("drawer_inbox").assertContentDescriptionEquals("Inbox, 1 needs you")
    }

    @Test
    fun withNobodyWaitingTheInboxEntryHasNoBadge() {
        showSessions(inboxCount = 0)

        rule.onNodeWithTag("drawer_inbox").assertContentDescriptionEquals("Inbox")
        rule.onAllNodesWithTag("inbox_badge", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun withNoHostThereIsNoInboxEntry() {
        showSessions(connectedHost = null, inboxCount = null)

        rule.onAllNodesWithTag("drawer_inbox").assertCountEquals(0)
    }

    @Test
    fun withNoSessionChosenThereAreNoBells() {
        showSessions()

        rule.onAllNodesWithTag("row_alert", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun anEmptyHostHasNoSearchField() {
        showSessions(emptyList())

        rule.onAllNodesWithTag("search").assertCountEquals(0)
    }

    @Test
    fun settingsShowTheThreeSwitchesAndGoBack() {
        var back = 0
        showSettings(onBack = { back++ })

        rule.onNodeWithTag("settings_title").assertTextEquals("Settings")
        rule.onNodeWithTag("monitor_toggle").assertIsDisplayed()
        // The screen scrolls; on a small phone the permissions are below the first screenful.
        rule.onNodeWithTag("interaction_toggle").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("management_toggle").assertExists()
        rule.onNodeWithTag("back").assertContentDescriptionEquals("Back").assertTouchTargetAtLeast(48.dp).performClick()

        assertEquals(1, back)
    }

    @Test
    fun theMonitorSwitchIsOffByDefaultAndTurnsOnWithAFullWidthTouchTarget() {
        val changes = mutableListOf<Boolean>()
        showSettings(onMonitorChange = { changes += it })

        rule.onNodeWithTag("monitor_toggle").assertIsOff().assertTouchTargetAtLeast(48.dp).performClick()

        assertEquals(listOf(true), changes)
        rule.onNodeWithTag("monitor_hint").assertTextContains("Off", substring = true)
    }

    @Test
    fun anEnabledMonitorTurnsOffAndSaysWhatItDoes() {
        val changes = mutableListOf<Boolean>()
        showSettings(monitorEnabled = true, onMonitorChange = { changes += it })

        rule.onNodeWithTag("monitor_toggle").assertIsOn().performClick()

        assertEquals(listOf(false), changes)
        rule.onNodeWithTag("monitor_hint").assertTextContains("notifies you when a session needs you", substring = true)
    }

    @Test
    fun blockedNotificationsAreStatedSoTheUserKnowsTheyWillNotBeTold() {
        showSettings(monitorEnabled = true, notificationsAllowed = false)

        rule.onNodeWithTag("monitor_hint").assertTextContains("notifications are blocked", substring = true)
    }

    @Test
    fun anEmptyHostSaysSo() {
        showSessions(emptyList())

        rule.onNodeWithTag("empty_list").assertIsDisplayed()
        rule.onNodeWithText("No sessions yet.").assertIsDisplayed()
    }

    @Test
    fun aHostWithOnlyArchivedSessionsPointsAtTheToggle() {
        showSessions(listOf(archived))

        rule.onNodeWithText("All 1 are archived.").assertIsDisplayed()
        rule.onNodeWithTag("archived_toggle").assertIsDisplayed()
    }

    @Test
    fun whileConnectingTheListShowsAWordLabelNotJustASpinner() {
        showSessions(emptyList(), connection = ConnState.Connecting)

        rule.onNodeWithText("Loading sessions", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("connection_status", useUnmergedTree = true).assertTextContains("Connecting", substring = true)
    }

    @Test
    fun aConnectionFailureIsShownAsText() {
        showSessions(emptyList(), connection = ConnState.Failed(IllegalStateException("The tunnel lists no running agent host")))

        rule.onNodeWithTag("connection_status", useUnmergedTree = true).assertTextContains("no running agent host", substring = true)
    }

    @Test
    fun aDroppedConnectionKeepsTheSessionsVisible() {
        showSessions(connection = ConnState.Reconnecting(1, null))

        rule.onNodeWithTag("connection_status", useUnmergedTree = true).assertTextContains("Reconnecting", substring = true)
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
    fun theTopBarNamesTheSessionAndItsMenuHoldsDetails() {
        showDetail(detailState())

        rule.onNodeWithTag("detail_title").assertTextEquals("Fix the build")
        rule.onNodeWithTag("session_menu").assertContentDescriptionEquals("More options").assertTouchTargetAtLeast(48.dp).performClick()
        rule.onNodeWithTag("menu_details").assertIsDisplayed()
        rule.onAllNodesWithTag("toggle_read").assertCountEquals(0)
    }

    @Test
    fun detailsShowTheSessionAndWhatItWaitsOn() {
        val request = """[{"kind":"chatInput","id":"r1","chat":"c","request":{"id":"q","message":"Which branch should I use?"}}]"""
        showDetail(detailState(request))

        openDetails()

        rule.onNodeWithTag("detail_activity").assertTextEquals("Waiting on you")
        rule.onNodeWithTag("input_request").assertTextContains("Which branch should I use?", substring = true)
        rule.onNodeWithTag("answer_hint").assertTextEquals("Answer in VS Code.")
        rule.onNodeWithText("repo").assertIsDisplayed()
    }

    @Test
    fun detailsShowTheBranchAndGitStatusTheHostReportsAndNothingWhenItReportsNone() {
        showDetail(detailState(meta = """{"git":{"branchName":"feature/phone","outgoingChanges":2,"uncommittedChanges":3}}"""))
        openDetails()

        rule.onNodeWithText("Branch").assertIsDisplayed()
        rule.onNodeWithText("feature/phone").assertIsDisplayed()
        rule.onNodeWithText("2 outgoing, 3 uncommitted").assertIsDisplayed()
    }

    @Test
    fun detailsOfASessionWithNoGitDataHaveNoBranchTile() {
        showDetail(detailState())
        openDetails()

        rule.onAllNodesWithText("Branch").assertCountEquals(0)
        rule.onAllNodesWithText("Git status").assertCountEquals(0)
    }

    @Test
    fun theChatFillsTheScreenUnderTheBar() {
        showDetail(detailState())

        rule.onNodeWithTag("chat_body").assertTextEquals("chat body: $mainChatUri")
        rule.onAllNodesWithTag("chat_tabs").assertCountEquals(0)
    }

    @Test
    fun aSessionWithSeveralChatsHasATabForEachWithTheMainChatFirst() {
        showDetail(detailState(chats = twoChats, defaultChat = mainChatUri))

        val tabs = rule.onAllNodesWithTag("chat_tab")
        tabs.assertCountEquals(2)
        tabs[0].assertContentDescriptionEquals("Main chat").assertTouchTargetAtLeast(48.dp)
        tabs[1].assertContentDescriptionEquals("Side chat, Needs you").assertTouchTargetAtLeast(48.dp)
        rule.onAllNodesWithTag("chat_tab_dot", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun tappingATabAsksForThatChatAndTheChatBeneathFollows() {
        val asked = mutableListOf<String>()
        showDetail(detailState(chats = twoChats, defaultChat = mainChatUri), onSelectChat = { asked += it })

        rule.onAllNodesWithTag("chat_tab")[1].performClick()
        rule.waitForIdle()

        assertEquals(listOf(sideChatUri), asked)
        rule.onNodeWithTag("chat_body").assertTextEquals("chat body: $sideChatUri")
    }

    @Test
    fun withNoChatShownTheMainChatIsAskedForAtOnce() {
        val asked = mutableListOf<String>()
        showDetail(detailState(chats = twoChats, defaultChat = mainChatUri), shown = null, onSelectChat = { asked += it })
        rule.waitForIdle()

        assertEquals(listOf(mainChatUri), asked)
        rule.onNodeWithTag("chat_body").assertTextEquals("chat body: $mainChatUri")
    }

    @Test
    fun aShownChatThatLeftTheSessionIsReplacedByTheMainChat() {
        val asked = mutableListOf<String>()
        showDetail(detailState(chats = twoChats, defaultChat = mainChatUri), shown = "ahp-chat://default/gone", onSelectChat = { asked += it })
        rule.waitForIdle()

        assertEquals(listOf(mainChatUri), asked)
    }

    @Test
    fun noChatIsAskedForWhileTheSessionIsLoading() {
        val asked = mutableListOf<String>()
        showDetail(null, shown = null, onSelectChat = { asked += it })
        rule.waitForIdle()

        assertEquals(emptyList<String>(), asked)
        rule.onAllNodesWithTag("chat_body").assertCountEquals(0)
    }

    @Test
    fun noChatIsAskedForWhileTheSessionHasFailedToStart() {
        val asked = mutableListOf<String>()
        val failed = Ahp.json.decodeFromString(
            SessionState.serializer(),
            """{"provider":"copilotcli","title":"T","status":1,"lifecycle":"failed","activeClients":[],"chats":[],"creationError":{"errorType":"E","message":"no licence"}}""",
        )
        rule.setContent { AhpTheme { SessionScreen(failed, null, ConnState.Online, now, {}, shownChat = null, onSelectChat = { asked += it }) } }
        rule.waitForIdle()

        assertEquals(emptyList<String>(), asked)
        rule.onNodeWithTag("session_failed").assertTextContains("no licence", substring = true)
        rule.onAllNodesWithTag("chat_body").assertCountEquals(0)
    }

    @Test
    fun aQuestionWithOptionsListsThemSoTheChoiceIsClearFromThePhone() {
        val request = """[{"kind":"chatInput","id":"r1","chat":"c","request":{"id":"q","questions":[
            {"kind":"single-select","id":"a","message":"Which branch?","options":[
                {"id":"m","label":"main","recommended":true},{"id":"d","label":"develop"}]}]}}]"""
        showDetail(detailState(request))
        openDetails()

        val options = rule.onAllNodesWithTag("question_option", useUnmergedTree = true)
        options.assertCountEquals(2)
        options[0].assertTextEquals("• main (recommended)")
        options[1].assertTextEquals("• develop")
        rule.onNodeWithTag("input_request").assertTextContains("Pick one", substring = true)
        rule.onNodeWithTag("input_request").assertTextContains("Which branch?", substring = true)
    }

    @Test
    fun detailsOfferNoWaitingSectionWhenNothingIsPending() {
        showDetail(detailState())
        openDetails()

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
        listOf("Needs you", "Working", "Error", "Idle").forEachIndexed { i, words -> chips[i].assertTextEquals(words) }
    }

    @Test
    fun agesKeepCountingBetweenHostUpdates() {
        var clockNow = Instant.parse("2026-10-26T10:30:00Z")
        rule.setContent {
            val ticking by rememberNow(periodMs = 1_000) { clockNow }
            AhpTheme { androidx.compose.material3.Text(RelativeTime.format("2026-10-26T10:29:00.000Z", ticking), Modifier.testTag("age")) }
        }
        rule.onNodeWithTag("age").assertTextEquals("1m")

        clockNow = Instant.parse("2026-10-26T10:33:00Z")
        rule.mainClock.advanceTimeBy(1_100)

        rule.onNodeWithTag("age").assertTextEquals("4m")
    }
}
