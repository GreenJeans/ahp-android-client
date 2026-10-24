package dev.ahpclient.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ahpclient.state.ActivityState
import dev.ahpclient.state.ConnState
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
        onOpenSettings: () -> Unit = {},
        initialQuery: String = "",
    ) = rule.setContent {
        var showArchived by androidx.compose.runtime.remember { mutableStateOf(initialShowArchived) }
        var query by androidx.compose.runtime.remember { mutableStateOf(initialQuery) }
        AhpTheme {
            SessionsScreen(
                host, connection, sessions, now, showArchived, rememberLazyListState(), { showArchived = !showArchived; onToggle() }, onOpen, onDisconnect,
                onOpenSettings = onOpenSettings, query = query, onQueryChange = { query = it },
            )
        }
    }

    private fun showSettings(
        monitorEnabled: Boolean = false,
        notificationsAllowed: Boolean = true,
        onMonitorChange: (Boolean) -> Unit = {},
        onBack: () -> Unit = {},
    ) = rule.setContent {
        AhpTheme { SettingsScreen(onBack, monitorEnabled = monitorEnabled, notificationsAllowed = notificationsAllowed, onMonitorChange = onMonitorChange) }
    }

    private val mainChatUri = "ahp-chat://default/x"
    private val sideChatUri = "ahp-chat://default/y"

    private fun detailState(inputNeeded: String? = null, chats: String = """[{"resource":"$mainChatUri","title":"Main chat","status":56,"modifiedAt":"2026-10-26T10:29:00.000Z"}]""", defaultChat: String? = null): SessionState =
        Ahp.json.decodeFromString(
            SessionState.serializer(),
            """{"provider":"copilotcli","title":"Fix the build","status":56,"lifecycle":"ready","activeClients":[],"activity":"Waiting on you",
                "project":{"uri":"file:///work/repo","displayName":"repo"},"workingDirectories":["file:///work/repo"],
                "chats":$chats
                ${if (defaultChat != null) ""","defaultChat":"$defaultChat"""" else ""}
                ${if (inputNeeded != null) ""","inputNeeded":$inputNeeded""" else ""}}""",
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
    fun theSettingsGearOpensSettingsAndIsBigEnough() {
        var opened = 0
        showSessions(onOpenSettings = { opened++ })

        rule.onNodeWithTag("settings").assertContentDescriptionEquals("Settings").assertTouchTargetAtLeast(48.dp).performClick()

        assertEquals(1, opened)
    }

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
        rule.onAllNodesWithTag("session_row")[0].assertContentDescriptionEquals("Working, In progress, Running tests, 5 min ago")
        rule.onNodeWithTag("search_clear").assertContentDescriptionEquals("Clear search").assertTouchTargetAtLeast(48.dp).performClick()
        rule.onAllNodesWithTag("session_row").assertCountEquals(3)
        rule.onNodeWithTag("search_clear").assertDoesNotExist()
    }

    @Test
    fun aSearchWithNoMatchSaysSoAndNamesWhatWasSearchedFor() {
        showSessions(initialQuery = "zzz")

        rule.onNodeWithTag("empty_list").assertIsDisplayed()
        rule.onNodeWithText("No sessions match \"zzz\"").assertIsDisplayed()
        rule.onNodeWithTag("search").assertIsDisplayed()
    }

    @Test
    fun searchingDoesNotChangeTheSummaryOfWhatNeedsYou() {
        showSessions(initialQuery = "Unread")

        rule.onAllNodesWithTag("session_row").assertCountEquals(1)
        rule.onNodeWithTag("summary").assertTextEquals("1 needs input · 1 in progress")
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
        rule.onNodeWithTag("interaction_toggle").assertIsDisplayed()
        rule.onNodeWithTag("management_toggle").assertExists()
        rule.onNodeWithTag("back").assertContentDescriptionEquals("Back to sessions").assertTouchTargetAtLeast(48.dp).performClick()

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
        rule.onNodeWithTag("monitor_hint").assertTextContains("notifies you when a session needs input", substring = true)
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
        tabs[1].assertContentDescriptionEquals("Side chat, Needs input").assertTouchTargetAtLeast(48.dp)
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
        listOf("Needs input", "In progress", "Error", "Idle").forEachIndexed { i, words -> chips[i].assertTextEquals(words) }
    }

    @Test
    fun agesKeepCountingBetweenHostUpdates() {
        var clockNow = Instant.parse("2026-10-26T10:30:00Z")
        rule.setContent {
            val ticking by rememberNow(periodMs = 1_000) { clockNow }
            AhpTheme { androidx.compose.material3.Text(RelativeTime.format("2026-10-26T10:29:00.000Z", ticking), Modifier.testTag("age")) }
        }
        rule.onNodeWithTag("age").assertTextEquals("1 min ago")

        clockNow = Instant.parse("2026-10-26T10:33:00Z")
        rule.mainClock.advanceTimeBy(1_100)

        rule.onNodeWithTag("age").assertTextEquals("4 min ago")
    }
}
