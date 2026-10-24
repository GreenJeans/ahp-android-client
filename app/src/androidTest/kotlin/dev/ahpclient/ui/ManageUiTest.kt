package dev.ahpclient.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.ManageResult
import dev.ahpclient.state.SessionDetail
import dev.ahpclient.state.SessionPhase
import dev.ahpclient.tunnel.TunnelInfo
import dev.ahpclient.ui.theme.AhpTheme
import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.SessionState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Looking after sessions from the phone: the switch, the new-session dialog, and the buttons on one session. */
@RunWith(AndroidJUnit4::class)
class ManageUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val now = Instant.parse("2026-10-26T10:30:00Z")
    private val uri = "ahp-session:/s1"

    private class Calls {
        val read = mutableListOf<Pair<String, Boolean>>()
        val archived = mutableListOf<Pair<String, Boolean>>()
        val chats = mutableListOf<String>()
        val sessions = mutableListOf<Pair<String, String>>()
        val disposedSessions = mutableListOf<String>()
        val disposedChats = mutableListOf<Pair<String, String>>()
        var result: ManageResult = ManageResult.Done()
        var created: String? = null
    }

    private fun actions(
        calls: Calls,
        agents: List<AgentOption> = listOf(AgentOption("copilotcli", "Copilot"), AgentOption("claude", "Claude")),
        folders: List<String> = listOf("file:///work/a", "file:///work/b"),
    ) = ManageActionsUi(
        agents = agents,
        folders = folders,
        setRead = { u, r -> calls.read += u to r; calls.result },
        setArchived = { u, a -> calls.archived += u to a; calls.result },
        newChat = { calls.chats += it; calls.result },
        newSession = { p, f -> calls.sessions += p to f; if (calls.result is ManageResult.Done) ManageResult.Done(calls.created) else calls.result },
        disposeSession = { calls.disposedSessions += it; calls.result },
        disposeChat = { s, c -> calls.disposedChats += s to c; calls.result },
    )

    private fun detail(read: Boolean = false, archived: Boolean = false, phase: SessionPhase = SessionPhase.READY) = SessionDetail(
        title = "Fix the build", state = dev.ahpclient.state.ActivityState.IDLE, provider = "copilotcli", activity = null, project = null,
        workingDirectories = emptyList(), chats = emptyList(), inputNeeded = emptyList(), isArchived = archived, isRead = read, phase = phase,
    )

    // ---- the switch -------------------------------------------------------------------------------------------

    private val changes = mutableListOf<Boolean>()

    private fun showToggle(initial: Boolean, available: Boolean = true) {
        var allowed by mutableStateOf(initial)
        rule.setContent { AhpTheme { ManagementToggle(allowed, available, { changes += it; allowed = it }) } }
    }

    @Test
    fun theSwitchIsOffAndSaysNothingCanBeStartedArchivedOrRemoved() {
        showToggle(initial = false)

        rule.onNodeWithTag("management_toggle").assertIsOff()
        rule.onNodeWithTag("management_hint").assertTextContains("cannot start, archive or remove", substring = true)
    }

    @Test
    fun turningItOnAsksFirstAndChangesNothingUntilConfirmed() {
        showToggle(initial = false)

        rule.onNodeWithTag("management_toggle").performClick()
        rule.onNodeWithTag("management_dialog_text").assertIsDisplayed().assertTextContains("cannot be undone", substring = true)
        assertEquals(emptyList<Boolean>(), changes)

        rule.onNodeWithTag("management_cancel").performClick()
        rule.onNodeWithTag("management_dialog_text").assertDoesNotExist()
        assertEquals(emptyList<Boolean>(), changes)

        rule.onNodeWithTag("management_toggle").performClick()
        rule.onNodeWithTag("management_confirm").performClick()
        assertEquals(listOf(true), changes)
        rule.onNodeWithTag("management_toggle").assertIsOn()
    }

    @Test
    fun turningItOffNeedsNoQuestion() {
        showToggle(initial = true)

        rule.onNodeWithTag("management_toggle").performClick()

        assertEquals(listOf(false), changes)
        rule.onNodeWithTag("management_dialog_text").assertDoesNotExist()
    }

    @Test
    fun withoutActingForTheUserTheSwitchCannotBeUsedAndSaysWhy() {
        showToggle(initial = false, available = false)

        rule.onNodeWithTag("management_toggle").assertIsNotEnabled()
        rule.onNodeWithTag("management_hint").assertTextContains("act for me", substring = true)
        rule.onNodeWithTag("management_toggle").performClick()

        rule.onNodeWithTag("management_dialog_text").assertDoesNotExist()
        assertEquals(emptyList<Boolean>(), changes)
    }

    // ---- a new session ----------------------------------------------------------------------------------------

    private fun showNewSession(calls: Calls, manage: ManageActionsUi = actions(calls), onCreated: (String) -> Unit = {}) {
        rule.setContent { AhpTheme { NewSessionButton(manage, onCreated) } }
        rule.onNodeWithTag("new_session").performClick()
    }

    @Test
    fun startNeedsBothAnAgentAndAFolderAndSendsExactlyThoseTwo() {
        val calls = Calls().apply { created = "ahp-session:/new" }
        val opened = mutableListOf<String>()
        showNewSession(calls, onCreated = { opened += it })

        rule.onNodeWithTag("new_session_start").assertIsNotEnabled()
        rule.onNodeWithTag("agent_claude").performClick()
        rule.onNodeWithTag("new_session_start").assertIsNotEnabled()
        rule.onNodeWithTag("folder_file:///work/b").performClick()
        rule.onNodeWithTag("new_session_start").assertIsEnabled().performClick()

        rule.waitUntil(5_000) { calls.sessions.isNotEmpty() }
        assertEquals(listOf("claude" to "file:///work/b"), calls.sessions)
        rule.waitUntil(5_000) { opened.isNotEmpty() }
        assertEquals(listOf("ahp-session:/new"), opened)
        rule.onNodeWithTag("new_session_start").assertDoesNotExist()
    }

    @Test
    fun aSingleAgentAndFolderAreAlreadyChosen() {
        val calls = Calls()
        showNewSession(calls, actions(calls, agents = listOf(AgentOption("copilotcli", "Copilot")), folders = listOf("file:///work/a")))

        rule.onNodeWithTag("new_session_start").assertIsEnabled()
    }

    @Test
    fun withNothingToChooseItSaysSoAndCannotStart() {
        val calls = Calls()
        showNewSession(calls, actions(calls, agents = emptyList(), folders = emptyList()))

        rule.onNodeWithTag("no_agents").assertIsDisplayed()
        rule.onNodeWithTag("no_folders").assertTextContains("Start a session in VS Code first", substring = true)
        rule.onNodeWithTag("new_session_start").assertIsNotEnabled()
    }

    @Test
    fun aRefusedStartStaysOpenWithTheReasonAndOpensNothing() {
        val calls = Calls().apply { result = ManageResult.Refused(-32003, "already exists") }
        val opened = mutableListOf<String>()
        showNewSession(calls, onCreated = { opened += it })

        rule.onNodeWithTag("agent_claude").performClick()
        rule.onNodeWithTag("folder_file:///work/a").performClick()
        rule.onNodeWithTag("new_session_start").performClick()

        rule.waitUntil(5_000) { rule.onAllNodesCount("new_session_notice") == 1 }
        rule.onNodeWithTag("new_session_notice").assertTextContains("The host refused it: already exists")
        assertTrue(opened.isEmpty())
        rule.onNodeWithTag("new_session_start").assertIsDisplayed()
    }

    @Test
    fun aStartThatFailedTheDeviceCheckSaysSo() {
        val calls = Calls().apply { result = ManageResult.NotConfirmed }
        showNewSession(calls)

        rule.onNodeWithTag("agent_claude").performClick()
        rule.onNodeWithTag("folder_file:///work/a").performClick()
        rule.onNodeWithTag("new_session_start").performClick()

        rule.waitUntil(5_000) { rule.onAllNodesCount("new_session_notice") == 1 }
        rule.onNodeWithTag("new_session_notice").assertTextContains("device check", substring = true)
    }

    @Test
    fun cancelClosesTheDialogAndSendsNothing() {
        val calls = Calls()
        showNewSession(calls)

        rule.onNodeWithTag("new_session_cancel").performClick()

        rule.onNodeWithTag("new_session_start").assertDoesNotExist()
        assertTrue(calls.sessions.isEmpty())
    }

    // ---- one session ------------------------------------------------------------------------------------------

    private fun showBar(calls: Calls, detail: SessionDetail = detail(), onRemoved: () -> Unit = {}) {
        rule.setContent { AhpTheme { SessionManageBar(detail, uri, actions(calls), onRemoved) } }
    }

    @Test
    fun markReadAndArchiveSayWhatTheyWillDoAndSendTheOppositeOfTheCurrentState() {
        val unread = Calls()
        showBar(unread, detail(read = false, archived = false))
        rule.onNodeWithTag("toggle_read").assertTextEquals("Mark read").performClick()
        rule.onNodeWithTag("toggle_archive").assertTextEquals("Archive").performClick()
        rule.waitUntil(5_000) { unread.read.isNotEmpty() && unread.archived.isNotEmpty() }
        assertEquals(listOf(uri to true), unread.read)
        assertEquals(listOf(uri to true), unread.archived)
    }

    @Test
    fun aReadArchivedSessionOffersTheWayBack() {
        val calls = Calls()
        showBar(calls, detail(read = true, archived = true))

        rule.onNodeWithTag("toggle_read").assertTextEquals("Mark unread").performClick()
        rule.onNodeWithTag("toggle_archive").assertTextEquals("Restore").performClick()

        rule.waitUntil(5_000) { calls.read.isNotEmpty() && calls.archived.isNotEmpty() }
        assertEquals(listOf(uri to false), calls.read)
        assertEquals(listOf(uri to false), calls.archived)
    }

    @Test
    fun aNewChatIsOnlyOfferedOnceTheSessionIsReady() {
        val starting = Calls()
        showBar(starting, detail(phase = SessionPhase.CREATING))
        rule.onNodeWithTag("new_chat").assertIsNotEnabled()
    }

    @Test
    fun aReadySessionStartsANewChat() {
        val calls = Calls()
        showBar(calls)

        rule.onNodeWithTag("new_chat").assertIsEnabled().performClick()

        rule.waitUntil(5_000) { calls.chats.isNotEmpty() }
        assertEquals(listOf(uri), calls.chats)
    }

    @Test
    fun aRefusedActionShowsTheReasonInWords() {
        val calls = Calls().apply { result = ManageResult.Refused(-32009, "not yours") }
        showBar(calls)

        rule.onNodeWithTag("toggle_archive").performClick()

        rule.waitUntil(5_000) { rule.onAllNodesCount("manage_notice") == 1 }
        rule.onNodeWithTag("manage_notice").assertTextContains("The host refused it: not yours")
    }

    @Test
    fun removingASessionAsksFirstAndSendsNothingUntilConfirmed() {
        val calls = Calls()
        val removed = mutableListOf<Boolean>()
        showBar(calls, onRemoved = { removed += true })

        rule.onNodeWithTag("remove_session").performClick()
        rule.onNodeWithTag("remove_dialog_text").assertTextContains("cannot be undone", substring = true).assertTextContains("Fix the build", substring = true)
        assertTrue(calls.disposedSessions.isEmpty())

        rule.onNodeWithTag("remove_cancel").performClick()
        rule.onNodeWithTag("remove_dialog_text").assertDoesNotExist()
        assertTrue(calls.disposedSessions.isEmpty() && removed.isEmpty())

        rule.onNodeWithTag("remove_session").performClick()
        rule.onNodeWithTag("remove_confirm").performClick()
        rule.waitUntil(5_000) { calls.disposedSessions.isNotEmpty() }
        assertEquals(listOf(uri), calls.disposedSessions)
        rule.waitUntil(5_000) { removed.isNotEmpty() }
    }

    @Test
    fun aSessionThatCouldNotBeRemovedStaysAndSaysWhy() {
        val calls = Calls().apply { result = ManageResult.NotConfirmed }
        val removed = mutableListOf<Boolean>()
        showBar(calls, onRemoved = { removed += true })

        rule.onNodeWithTag("remove_session").performClick()
        rule.onNodeWithTag("remove_confirm").performClick()

        rule.waitUntil(5_000) { rule.onAllNodesCount("manage_notice") == 1 }
        rule.onNodeWithTag("manage_notice").assertTextContains("device check", substring = true)
        assertTrue("it is still there, so the screen does not leave it", removed.isEmpty())
    }

    @Test
    fun removingAChatAsksFirstAndSendsNothingUntilConfirmed() {
        val calls = Calls()
        rule.setContent { AhpTheme { RemoveChatButton("Side chat", uri, "ahp-chat:/c2", actions(calls)) } }

        rule.onNodeWithTag("remove_chat").performClick()
        rule.onNodeWithTag("remove_dialog_text").assertTextContains("Side chat", substring = true)
        assertTrue(calls.disposedChats.isEmpty())
        rule.onNodeWithTag("remove_cancel").performClick()
        assertTrue(calls.disposedChats.isEmpty())

        rule.onNodeWithTag("remove_chat").performClick()
        rule.onNodeWithTag("remove_confirm").performClick()

        rule.waitUntil(5_000) { calls.disposedChats.isNotEmpty() }
        assertEquals(listOf(uri to "ahp-chat:/c2"), calls.disposedChats)
    }

    // ---- the screens ------------------------------------------------------------------------------------------

    private fun state(lifecycle: String, extra: String = ""): SessionState = Ahp.json.decodeFromString(
        SessionState.serializer(),
        """{"provider":"copilotcli","title":"Fix the build","status":1,"lifecycle":"$lifecycle","activeClients":[],
            "chats":[{"resource":"ahp-chat://default/x","title":"Main chat","status":1,"modifiedAt":"2026-10-26T10:29:00.000Z"}]$extra}""",
    )

    private fun showDetail(state: SessionState, manage: ManageActionsUi?) =
        rule.setContent { AhpTheme { SessionDetailScreen(state, null, ConnState.Online, now, {}, manage = manage, sessionUri = uri) } }

    @Test
    fun aReadOnlyDetailHasNoManageControls() {
        showDetail(state("ready"), manage = null)

        rule.onNodeWithTag("manage_bar").assertDoesNotExist()
        rule.onNodeWithTag("remove_chat").assertDoesNotExist()
    }

    @Test
    fun aDetailThatMayBeManagedShowsTheBarAndARemoveButtonForEachChat() {
        showDetail(state("ready"), manage = actions(Calls()))

        rule.onNodeWithTag("manage_bar").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("remove_chat").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aSessionStillStartingSaysSo() {
        showDetail(state("creating"), manage = actions(Calls()))

        rule.onNodeWithTag("session_starting").assertTextEquals("Starting on the host…")
        rule.onNodeWithTag("new_chat").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun aSessionThatFailedToStartSaysSoWithTheHostsReason() {
        showDetail(state("failed", ""","creationError":{"errorType":"E","message":"no licence"}"""), manage = null)

        rule.onNodeWithTag("session_failed").assertTextContains("The host could not start this session.", substring = true).assertTextContains("no licence", substring = true)
    }

    @Test
    fun theListOffersANewSessionOnlyWhenItMayBeManaged() {
        val host = TunnelInfo("t-online", "usw2", "dev-box", 6, 1, listOf(31546))
        var manage by mutableStateOf<ManageActionsUi?>(null)
        rule.setContent {
            AhpTheme {
                SessionsScreen(host, ConnState.Online, emptyList(), now, false, rememberLazyListState(), {}, {}, {}, manage = manage, settingsExpanded = true)
            }
        }

        rule.onNodeWithTag("new_session").assertDoesNotExist()
        rule.onNodeWithTag("management_toggle").assertIsNotEnabled()

        manage = actions(Calls())
        rule.waitForIdle()
        rule.onNodeWithTag("new_session").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
