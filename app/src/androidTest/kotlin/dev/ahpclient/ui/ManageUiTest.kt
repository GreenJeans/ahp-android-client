package dev.ahpclient.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.unit.dp
import dev.ahpclient.state.ChatRow
import dev.ahpclient.state.ConfigField
import dev.ahpclient.state.ConfigOffer
import dev.ahpclient.state.ConfigOption
import dev.ahpclient.state.ConfigPick
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.ManageResult
import dev.ahpclient.state.SessionConfigForm
import dev.ahpclient.state.SessionDetail
import dev.ahpclient.state.SessionPhase
import dev.ahpclient.tunnel.TunnelInfo
import dev.ahpclient.state.FinishedWatch
import dev.ahpclient.ui.theme.AhpTheme
import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.SessionState
import com.microsoft.agenthostprotocol.generated.SessionSummary
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
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
        val sessionPicks = mutableListOf<Map<String, ConfigPick>>()
        val asked = mutableListOf<Triple<String, String, Map<String, ConfigPick>>>()
        var offer: suspend (provider: String, folder: String, picks: Map<String, ConfigPick>) -> ConfigOffer = { _, _, _ -> ConfigOffer.NoSettings }
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
        newSession = { p, f, picks ->
            calls.sessions += p to f
            calls.sessionPicks += picks
            if (calls.result is ManageResult.Done) ManageResult.Done(calls.created) else calls.result
        },
        disposeSession = { calls.disposedSessions += it; calls.result },
        disposeChat = { s, c -> calls.disposedChats += s to c; calls.result },
        sessionSettings = { p, f, picks -> calls.asked += Triple(p, f, picks); calls.offer(p, f, picks) },
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
        rule.onNodeWithTag("management_hint").assertTextContains("no starting, archiving or removing", substring = true)
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
        rule.onNodeWithTag("management_hint").assertTextContains("Act for me", substring = true)
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
        assertEquals(listOf<Map<String, ConfigPick>>(emptyMap()), calls.sessionPicks)
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

    // ---- a new session's settings -------------------------------------------------------------------------------------

    private val quick = ConfigOption(ConfigPick.Text("low"), "Quick")
    private val thorough = ConfigOption(ConfigPick.Text("high"), "Thorough")

    /** What a host that holds Quick and Off by default would answer, with the choices so far already in it. */
    private fun formFor(
        picks: Map<String, ConfigPick>,
        required: Boolean = false,
        omitted: Int = 0,
        title: String = "Thinking",
        description: String? = "How hard to think",
        labelOn: String = "On",
    ): ConfigOffer = ConfigOffer.Form(
        SessionConfigForm(
            listOf(
                ConfigField("thinking", title, description, listOf(quick, thorough), required, picks["thinking"] ?: if (required) null else ConfigPick.Text("low")),
                ConfigField(
                    "autoApprove",
                    "Approve tools automatically",
                    null,
                    listOf(ConfigOption(ConfigPick.Flag(true), labelOn), ConfigOption(ConfigPick.Flag(false), "Off")),
                    false,
                    picks["autoApprove"] ?: ConfigPick.Flag(false),
                ),
            ),
            omitted,
            emptyMap(),
        ),
    )

    private fun Calls.hostOffers(required: Boolean = false, omitted: Int = 0) {
        offer = { _, _, picks -> formFor(picks, required, omitted) }
    }

    private fun chooseClaudeInB() {
        rule.onNodeWithTag("agent_claude").performClick()
        rule.onNodeWithTag("folder_file:///work/b").performClick()
    }

    @Test
    fun aHostWithNoSettingsShowsNoSettingsAndStartsAsBefore() {
        val calls = Calls()
        showNewSession(calls)

        chooseClaudeInB()
        rule.waitForIdle()

        rule.onNodeWithTag("setting_title_thinking").assertDoesNotExist()
        rule.onNodeWithTag("new_session_start").assertIsEnabled().performClick()
        rule.waitUntil(5_000) { calls.sessions.isNotEmpty() }
        assertEquals(listOf<Map<String, ConfigPick>>(emptyMap()), calls.sessionPicks)
    }

    @Test
    fun theHostIsAskedForSettingsOnlyOnceAnAgentAndFolderAreChosen() {
        val calls = Calls().apply { hostOffers() }
        showNewSession(calls)
        rule.waitForIdle()
        assertEquals(emptyList<Any>(), calls.asked)

        rule.onNodeWithTag("agent_claude").performClick()
        rule.waitForIdle()
        assertEquals(emptyList<Any>(), calls.asked)

        rule.onNodeWithTag("folder_file:///work/b").performClick()
        rule.waitUntil(5_000) { calls.asked.isNotEmpty() }
        assertEquals(Triple("claude", "file:///work/b", emptyMap<String, ConfigPick>()), calls.asked.first())
    }

    @Test
    fun eachSettingShowsItsTitleItsDescriptionAndItsChoicesWithTheHostsOwnValueSelected() {
        val calls = Calls().apply { hostOffers() }
        showNewSession(calls)

        chooseClaudeInB()
        rule.waitUntil(5_000) { rule.onAllNodesCount("setting_title_thinking") == 1 }

        rule.onNodeWithTag("setting_title_thinking").assertTextEquals("Thinking")
        rule.onNodeWithTag("setting_description_thinking").assertTextEquals("How hard to think")
        rule.onNodeWithTag("setting_thinking_0").assertIsSelected()
        rule.onNodeWithTag("setting_thinking_1").assertIsNotSelected()
        rule.onNodeWithTag("setting_autoApprove_0").assertIsNotSelected()
        rule.onNodeWithTag("setting_autoApprove_1").assertIsSelected()
    }

    @Test
    fun aChoiceAsksTheHostAgainAndStartSendsExactlyTheChoicesMade() {
        val calls = Calls().apply { hostOffers() }
        showNewSession(calls)
        chooseClaudeInB()
        rule.waitUntil(5_000) { rule.onAllNodesCount("setting_title_thinking") == 1 }

        rule.onNodeWithTag("setting_thinking_1").performClick()
        rule.waitUntil(5_000) { calls.asked.size >= 2 }
        rule.waitForIdle()

        assertEquals(mapOf("thinking" to ConfigPick.Text("high")), calls.asked.last().third)
        rule.onNodeWithTag("setting_thinking_1").assertIsSelected()
        rule.onNodeWithTag("setting_thinking_0").assertIsNotSelected()
        rule.onNodeWithTag("new_session_start").assertIsEnabled().performClick()
        rule.waitUntil(5_000) { calls.sessions.isNotEmpty() }
        assertEquals(listOf(mapOf<String, ConfigPick>("thinking" to ConfigPick.Text("high"))), calls.sessionPicks)
    }

    @Test
    fun anOnOffSettingIsSentAsABooleanAndSeveralChoicesAreSentTogether() {
        val calls = Calls().apply { hostOffers() }
        showNewSession(calls)
        chooseClaudeInB()
        rule.waitUntil(5_000) { rule.onAllNodesCount("setting_title_thinking") == 1 }

        rule.onNodeWithTag("setting_autoApprove_0").performScrollTo().performClick()
        rule.waitUntil(5_000) { calls.asked.size >= 2 }
        rule.waitForIdle()
        rule.onNodeWithTag("setting_thinking_1").performScrollTo().performClick()
        rule.waitUntil(5_000) { calls.asked.size >= 3 }
        rule.waitForIdle()
        rule.onNodeWithTag("new_session_start").performClick()
        rule.waitUntil(5_000) { calls.sessions.isNotEmpty() }

        assertEquals(mapOf("autoApprove" to ConfigPick.Flag(true), "thinking" to ConfigPick.Text("high")), calls.sessionPicks.single())
    }

    @Test
    fun changingTheFolderDropsTheChoicesAndAsksAgain() {
        val calls = Calls().apply { hostOffers() }
        showNewSession(calls)
        chooseClaudeInB()
        rule.waitUntil(5_000) { rule.onAllNodesCount("setting_title_thinking") == 1 }
        rule.onNodeWithTag("setting_thinking_1").performClick()
        rule.waitUntil(5_000) { calls.asked.size >= 2 }

        rule.onNodeWithTag("folder_file:///work/a").performClick()
        rule.waitUntil(5_000) { calls.asked.last().second == "file:///work/a" }
        rule.waitForIdle()

        assertEquals(emptyMap<String, ConfigPick>(), calls.asked.last().third)
        rule.onNodeWithTag("setting_thinking_0").assertIsSelected()
        rule.onNodeWithTag("new_session_start").performClick()
        rule.waitUntil(5_000) { calls.sessions.isNotEmpty() }
        assertEquals(emptyMap<String, ConfigPick>(), calls.sessionPicks.single())
    }

    @Test
    fun aChoiceShowsAtOnceEvenWhileTheHostIsStillBeingAsked() {
        val calls = Calls()
        var hold: CompletableDeferred<Unit>? = null
        calls.offer = { _, _, picks ->
            hold?.await()
            formFor(picks)
        }
        showNewSession(calls)
        chooseClaudeInB()
        rule.waitUntil(5_000) { rule.onAllNodesCount("setting_title_thinking") == 1 }

        hold = CompletableDeferred()
        rule.onNodeWithTag("setting_thinking_1").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesCount("settings_loading") == 1 }

        rule.onNodeWithTag("setting_thinking_1").assertIsSelected()
        rule.onNodeWithTag("setting_thinking_0").assertIsNotSelected()
        rule.onNodeWithTag("new_session_start").assertIsNotEnabled()
        hold.complete(Unit)
    }

    @Test
    fun aRequiredSettingWithNoValueBlocksStartUntilChosenAndIsNamed() {
        val calls = Calls().apply { hostOffers(required = true) }
        showNewSession(calls)

        chooseClaudeInB()
        rule.waitUntil(5_000) { rule.onAllNodesCount("setting_title_thinking") == 1 }

        rule.onNodeWithTag("setting_title_thinking").assertTextEquals("Thinking (required)")
        rule.onNodeWithTag("settings_missing").assertTextContains("Thinking", substring = true)
        rule.onNodeWithTag("new_session_start").assertIsNotEnabled()

        rule.onNodeWithTag("setting_thinking_0").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesCount("settings_missing") == 0 }
        rule.onNodeWithTag("new_session_start").assertIsEnabled()
    }

    @Test
    fun startWaitsWhileTheHostIsBeingAsked() {
        val calls = Calls()
        val answer = CompletableDeferred<ConfigOffer>()
        calls.offer = { _, _, _ -> answer.await() }
        showNewSession(calls)

        chooseClaudeInB()

        rule.waitUntil(5_000) { rule.onAllNodesCount("settings_loading") == 1 }
        rule.onNodeWithTag("new_session_start").assertIsNotEnabled()

        answer.complete(ConfigOffer.NoSettings)
        rule.waitUntil(5_000) { rule.onAllNodesCount("settings_loading") == 0 }
        rule.onNodeWithTag("new_session_start").assertIsEnabled()
    }

    @Test
    fun aHostThatCannotBeAskedSaysSoAndStillStartsWithItsOwnSettingsWhenNothingWasChosen() {
        val calls = Calls().apply { offer = { _, _, _ -> ConfigOffer.Unavailable("The host refused it: no such method") } }
        showNewSession(calls)

        chooseClaudeInB()
        rule.waitUntil(5_000) { rule.onAllNodesCount("settings_note") == 1 }

        rule.onNodeWithTag("settings_note").assertTextContains("no such method", substring = true).assertTextContains("host will use its own", substring = true)
        rule.onNodeWithTag("new_session_start").assertIsEnabled().performClick()
        rule.waitUntil(5_000) { calls.sessions.isNotEmpty() }
        assertEquals(emptyMap<String, ConfigPick>(), calls.sessionPicks.single())
    }

    @Test
    fun ifTheHostCannotBeAskedAfterAChoiceStartIsOffUntilItCan() {
        val calls = Calls().apply { offer = { _, _, picks -> if (picks.isEmpty()) formFor(picks) else ConfigOffer.Unavailable("offline") } }
        showNewSession(calls)
        chooseClaudeInB()
        rule.waitUntil(5_000) { rule.onAllNodesCount("setting_title_thinking") == 1 }

        rule.onNodeWithTag("setting_thinking_1").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesCount("settings_note") == 1 }

        rule.onNodeWithTag("settings_note").assertTextContains("offline", substring = true)
        rule.onNodeWithTag("new_session_start").assertIsNotEnabled()
    }

    @Test
    fun aChoiceTheHostNoLongerOffersIsDropped() {
        val calls = Calls().apply { offer = { _, _, picks -> if (picks.isEmpty()) formFor(picks) else ConfigOffer.NoSettings } }
        showNewSession(calls)
        chooseClaudeInB()
        rule.waitUntil(5_000) { rule.onAllNodesCount("setting_title_thinking") == 1 }

        rule.onNodeWithTag("setting_thinking_1").performClick()
        rule.waitUntil(5_000) { calls.asked.size >= 3 }
        rule.waitForIdle()

        rule.onNodeWithTag("setting_thinking_0").assertIsSelected()
        rule.onNodeWithTag("new_session_start").performClick()
        rule.waitUntil(5_000) { calls.sessions.isNotEmpty() }
        assertEquals(emptyMap<String, ConfigPick>(), calls.sessionPicks.single())
    }

    @Test
    fun settingsThisAppCannotShowAreCounted() {
        val calls = Calls().apply { hostOffers(omitted = 2) }
        showNewSession(calls)

        chooseClaudeInB()
        rule.waitUntil(5_000) { rule.onAllNodesCount("settings_omitted") == 1 }

        rule.onNodeWithTag("settings_omitted").assertTextContains("2 more settings this app cannot show", substring = true)
    }

    @Test
    fun oneSettingLeftOutIsSaidInTheSingular() {
        val calls = Calls().apply { hostOffers(omitted = 1) }
        showNewSession(calls)

        chooseClaudeInB()
        rule.waitUntil(5_000) { rule.onAllNodesCount("settings_omitted") == 1 }

        rule.onNodeWithTag("settings_omitted").assertTextContains("1 more setting this app cannot show", substring = true)
    }

    @Test
    fun theHostsWordsAreShownAsTextAndTheRowsAreBigEnoughToTap() {
        val hostile = "<b>bold</b> [link](https://evil.example)"
        val calls = Calls().apply { offer = { _, _, picks -> formFor(picks, title = hostile, description = hostile, labelOn = hostile) } }
        showNewSession(calls)

        chooseClaudeInB()
        rule.waitUntil(5_000) { rule.onAllNodesCount("setting_title_thinking") == 1 }

        rule.onNodeWithTag("setting_title_thinking").assertTextEquals(hostile)
        rule.onNodeWithTag("setting_description_thinking").assertTextEquals(hostile)
        rule.onNodeWithTag("setting_autoApprove_0").assertTextContains(hostile, substring = true).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun changingTheAgentDropsTheChoicesAndAsksAgain() {
        val calls = Calls().apply { hostOffers() }
        showNewSession(calls)
        chooseClaudeInB()
        rule.waitUntil(5_000) { rule.onAllNodesCount("setting_title_thinking") == 1 }
        rule.onNodeWithTag("setting_thinking_1").performClick()
        rule.waitUntil(5_000) { calls.asked.size >= 2 }

        rule.onNodeWithTag("agent_copilotcli").performClick()
        rule.waitUntil(5_000) { calls.asked.last().first == "copilotcli" }
        rule.waitForIdle()

        assertEquals(emptyMap<String, ConfigPick>(), calls.asked.last().third)
        rule.onNodeWithTag("setting_thinking_0").assertIsSelected()
        rule.onNodeWithTag("new_session_start").performClick()
        rule.waitUntil(5_000) { calls.sessions.isNotEmpty() }
        assertEquals(emptyMap<String, ConfigPick>(), calls.sessionPicks.single())
    }

    @Test
    fun aChoiceForASettingTheHostStoppedListingIsDropped() {
        val calls = Calls().apply {
            offer = { _, _, picks ->
                val full = formFor(picks) as ConfigOffer.Form
                if (picks.isEmpty()) full else ConfigOffer.Form(SessionConfigForm(full.form.fields.filter { it.id != "thinking" }, 0, emptyMap()))
            }
        }
        showNewSession(calls)
        chooseClaudeInB()
        rule.waitUntil(5_000) { rule.onAllNodesCount("setting_title_thinking") == 1 }

        rule.onNodeWithTag("setting_thinking_1").performClick()
        rule.waitUntil(5_000) { calls.asked.size >= 3 }
        rule.waitForIdle()

        rule.onNodeWithTag("new_session_start").performClick()
        rule.waitUntil(5_000) { calls.sessions.isNotEmpty() }
        assertEquals(emptyMap<String, ConfigPick>(), calls.sessionPicks.single())
    }

    @Test
    fun theChoicesSurviveRotation() {
        val calls = Calls().apply { hostOffers() }
        val restoration = StateRestorationTester(rule)
        restoration.setContent { AhpTheme { NewSessionButton(actions(calls), {}) } }
        rule.onNodeWithTag("new_session").performClick()
        chooseClaudeInB()
        rule.waitUntil(5_000) { rule.onAllNodesCount("setting_title_thinking") == 1 }
        rule.onNodeWithTag("setting_thinking_1").performScrollTo().performClick()
        rule.waitUntil(5_000) { calls.asked.size >= 2 }
        rule.waitForIdle()
        rule.onNodeWithTag("setting_autoApprove_0").performScrollTo().performClick()
        rule.waitUntil(5_000) { calls.asked.size >= 3 }
        rule.waitForIdle()

        restoration.emulateSavedInstanceStateRestore()
        rule.waitUntil(5_000) { rule.onAllNodesCount("setting_title_thinking") == 1 }
        rule.waitForIdle()

        rule.onNodeWithTag("setting_thinking_1").assertIsSelected()
        rule.onNodeWithTag("setting_autoApprove_0").assertIsSelected()
        rule.onNodeWithTag("new_session_start").performClick()
        rule.waitUntil(5_000) { calls.sessions.isNotEmpty() }
        assertEquals(mapOf("thinking" to ConfigPick.Text("high"), "autoApprove" to ConfigPick.Flag(true)), calls.sessionPicks.single())
    }

    @Test
    fun cancelClosesTheDialogAndSendsNothing() {
        val calls = Calls()
        showNewSession(calls)

        rule.onNodeWithTag("new_session_cancel").performClick()

        rule.onNodeWithTag("new_session_start").assertDoesNotExist()
        assertTrue(calls.sessions.isEmpty())
    }

    @Test
    fun onlyTheMostRecentFoldersAreShownAtFirstAndTheRestOnRequest() {
        val calls = Calls()
        val folders = (1..12).map { "file:///work/f$it" }
        showNewSession(calls, actions(calls, folders = folders))

        rule.onNodeWithTag("folder_file:///work/f1").assertExists()
        rule.onNodeWithTag("folder_file:///work/f8").assertExists()
        rule.onNodeWithTag("folder_file:///work/f9").assertDoesNotExist()
        rule.onNodeWithTag("folders_show_all").performScrollTo().assertTextEquals("Show 4 more folders").performClick()

        rule.onNodeWithTag("folder_file:///work/f12").assertExists()
        rule.onNodeWithTag("folders_show_all").assertDoesNotExist()
    }

    @Test
    fun aFolderFromTheRestCanBePickedAndIsTheOneSent() {
        val calls = Calls().apply { created = "ahp-session:/new" }
        val folders = (1..12).map { "file:///work/f$it" }
        showNewSession(calls, actions(calls, folders = folders))

        rule.onNodeWithTag("folders_show_all").performScrollTo().performClick()
        rule.onNodeWithTag("agent_claude").performScrollTo().performClick()
        rule.onNodeWithTag("folder_file:///work/f11").performScrollTo().performClick()
        rule.onNodeWithTag("folder_file:///work/f11").assertIsSelected()
        rule.onNodeWithTag("new_session_start").assertIsEnabled().performClick()

        rule.waitUntil(5_000) { calls.sessions.isNotEmpty() }
        assertEquals(listOf("claude" to "file:///work/f11"), calls.sessions)
    }

    @Test
    fun withEightFoldersOrFewerThereIsNothingMoreToShow() {
        val calls = Calls()
        showNewSession(calls, actions(calls, folders = (1..8).map { "file:///work/f$it" }))

        rule.onNodeWithTag("folder_file:///work/f8").assertExists()
        rule.onNodeWithTag("folders_show_all").assertDoesNotExist()
    }

    // ---- one session ------------------------------------------------------------------------------------------

    private fun showBar(calls: Calls, detail: SessionDetail = detail(), onRemoved: () -> Unit = {}, shownChat: ChatRow? = null) {
        rule.setContent {
            var notice by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
            AhpTheme {
                androidx.compose.foundation.layout.Column {
                    SessionMenu(detail, uri, actions(calls), shownChat, onShowDetails = {}, onNotice = { notice = it }, onChatCreated = {}, onRemoved = onRemoved)
                    ManageNotice(notice, "manage_notice")
                }
            }
        }
    }

    private fun openMenu() {
        rule.onNodeWithTag("session_menu").performClick()
        rule.waitForIdle()
    }

    // ---- alert when done ---------------------------------------------------------------------------------------

    private fun showAlertMenu(alerts: FinishedAlertsUi?, detail: SessionDetail = detail(), manage: ManageActionsUi? = null) {
        rule.setContent {
            var notice by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
            AhpTheme {
                androidx.compose.foundation.layout.Column {
                    SessionMenu(detail, uri, manage, null, onShowDetails = {}, onNotice = { notice = it }, onChatCreated = {}, onRemoved = {}, alerts = alerts)
                    ManageNotice(notice, "manage_notice")
                }
            }
        }
    }

    private fun hasState(text: String) = SemanticsMatcher("state description is $text") { node ->
        node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.StateDescription) == text
    }

    @Test
    fun theMenuOffersAlertWhenDoneEvenWhenTheAppMayNotManageSessions() {
        showAlertMenu(FinishedAlertsUi(), manage = null)
        openMenu()

        rule.onNodeWithTag("toggle_alert").assertIsDisplayed().assertIsEnabled().assertTextEquals("Alert when done").assert(hasState("Off"))
        rule.onNodeWithTag("toggle_read").assertDoesNotExist()
    }

    @Test
    fun withoutTheChoiceGivenThereIsNoAlertItem() {
        showAlertMenu(null, manage = actions(Calls()))
        openMenu()

        rule.onNodeWithTag("menu_details").assertIsDisplayed()
        rule.onNodeWithTag("toggle_alert").assertDoesNotExist()
    }

    @Test
    fun choosingItAsksForTheSessionAndWarnsWhenMonitoringIsOff() {
        val chosen = mutableListOf<Pair<String, Boolean>>()
        showAlertMenu(FinishedAlertsUi(monitoring = false, onSession = { s, on -> chosen += s to on }))
        openMenu()

        rule.onNodeWithTag("toggle_alert").performClick()

        assertEquals(listOf(uri to true), chosen)
        rule.onNodeWithTag("manage_notice").assertTextContains("Monitor in background", substring = true)
    }

    @Test
    fun choosingItWithMonitoringOnSaysNothingMore() {
        val chosen = mutableListOf<Pair<String, Boolean>>()
        showAlertMenu(FinishedAlertsUi(monitoring = true, onSession = { s, on -> chosen += s to on }))
        openMenu()

        rule.onNodeWithTag("toggle_alert").performClick()

        assertEquals(listOf(uri to true), chosen)
        rule.onNodeWithTag("manage_notice").assertDoesNotExist()
    }

    @Test
    fun aSessionAlreadyChosenIsOnAndTheItemTurnsItOffWithoutAWarning() {
        val chosen = mutableListOf<Pair<String, Boolean>>()
        showAlertMenu(FinishedAlertsUi(FinishedWatch(sessions = setOf(uri)), monitoring = false, onSession = { s, on -> chosen += s to on }))
        openMenu()

        rule.onNodeWithTag("toggle_alert").assert(hasState("On")).performClick()

        assertEquals(listOf(uri to false), chosen)
        rule.onNodeWithTag("manage_notice").assertDoesNotExist()
    }

    @Test
    fun anotherSessionBeingChosenDoesNotMakeThisOneOn() {
        showAlertMenu(FinishedAlertsUi(FinishedWatch(sessions = setOf("ahp-session:/other"))))
        openMenu()

        rule.onNodeWithTag("toggle_alert").assert(hasState("Off"))
    }

    @Test
    fun whenEverySessionIsWatchedTheItemIsOnAndCannotBeChanged() {
        val chosen = mutableListOf<Pair<String, Boolean>>()
        showAlertMenu(FinishedAlertsUi(FinishedWatch(all = true), onSession = { s, on -> chosen += s to on }))
        openMenu()

        rule.onNodeWithTag("toggle_alert").assertIsNotEnabled().assert(hasState("On for every session")).performClick()

        assertEquals(emptyList<Pair<String, Boolean>>(), chosen)
    }

    @Test
    fun anArchivedSessionHasNoAlertItem() {
        showAlertMenu(FinishedAlertsUi(), detail = detail(archived = true))
        openMenu()

        rule.onNodeWithTag("menu_details").assertIsDisplayed()
        rule.onNodeWithTag("toggle_alert").assertDoesNotExist()
    }

    @Test
    fun markReadAndArchiveSayWhatTheyWillDoAndSendTheOppositeOfTheCurrentState() {
        val unread = Calls()
        showBar(unread, detail(read = false, archived = false))
        openMenu()
        rule.onNodeWithTag("toggle_read").assertTextEquals("Mark read").performClick()
        openMenu()
        rule.onNodeWithTag("toggle_archive").assertTextEquals("Archive").performClick()
        rule.onNodeWithTag("archive_confirm").performClick()
        rule.waitUntil(5_000) { unread.read.isNotEmpty() && unread.archived.isNotEmpty() }
        assertEquals(listOf(uri to true), unread.read)
        assertEquals(listOf(uri to true), unread.archived)
    }

    @Test
    fun archivingAsksFirstAndSaysWhatTheHostMayDoToTheWorkingCopy() {
        val calls = Calls()
        showBar(calls)

        openMenu()
        rule.onNodeWithTag("toggle_archive").performClick()
        rule.onNodeWithTag("archive_dialog_text")
            .assertTextContains("Fix the build", substring = true)
            .assertTextContains("working copy", substring = true)
            .assertTextContains("not bring the folder back", substring = true)
        assertTrue("nothing is sent before the answer", calls.archived.isEmpty())

        rule.onNodeWithTag("archive_cancel").performClick()
        rule.onNodeWithTag("archive_dialog_text").assertDoesNotExist()
        assertTrue(calls.archived.isEmpty())

        openMenu()
        rule.onNodeWithTag("toggle_archive").performClick()
        rule.onNodeWithTag("archive_confirm").performClick()
        rule.waitUntil(5_000) { calls.archived.isNotEmpty() }
        assertEquals(listOf(uri to true), calls.archived)
    }

    @Test
    fun restoringASessionNeedsNoQuestion() {
        val calls = Calls()
        showBar(calls, detail(archived = true))

        openMenu()
        rule.onNodeWithTag("toggle_archive").performClick()

        rule.waitUntil(5_000) { calls.archived.isNotEmpty() }
        rule.onNodeWithTag("archive_dialog_text").assertDoesNotExist()
        assertEquals(listOf(uri to false), calls.archived)
    }

    @Test
    fun aReadArchivedSessionOffersTheWayBack() {
        val calls = Calls()
        showBar(calls, detail(read = true, archived = true))

        openMenu()
        rule.onNodeWithTag("toggle_read").assertTextEquals("Mark unread").performClick()
        openMenu()
        rule.onNodeWithTag("toggle_archive").assertTextEquals("Restore").performClick()

        rule.waitUntil(5_000) { calls.read.isNotEmpty() && calls.archived.isNotEmpty() }
        assertEquals(listOf(uri to false), calls.read)
        assertEquals(listOf(uri to false), calls.archived)
    }

    @Test
    fun aNewChatIsOnlyOfferedOnceTheSessionIsReady() {
        val starting = Calls()
        showBar(starting, detail(phase = SessionPhase.CREATING))
        openMenu()
        rule.onNodeWithTag("new_chat").assertIsNotEnabled()
    }

    @Test
    fun aReadySessionStartsANewChat() {
        val calls = Calls()
        showBar(calls)

        openMenu()
        rule.onNodeWithTag("new_chat").assertIsEnabled().performClick()

        rule.waitUntil(5_000) { calls.chats.isNotEmpty() }
        assertEquals(listOf(uri), calls.chats)
    }

    @Test
    fun aRefusedActionShowsTheReasonInWords() {
        val calls = Calls().apply { result = ManageResult.Refused(-32009, "not yours") }
        showBar(calls)

        openMenu()
        rule.onNodeWithTag("toggle_read").performClick()

        rule.waitUntil(5_000) { rule.onAllNodesCount("manage_notice") == 1 }
        rule.onNodeWithTag("manage_notice").assertTextContains("The host refused it: not yours")
    }

    @Test
    fun removingASessionAsksFirstAndSendsNothingUntilConfirmed() {
        val calls = Calls()
        val removed = mutableListOf<Boolean>()
        showBar(calls, onRemoved = { removed += true })

        openMenu()
        rule.onNodeWithTag("remove_session").performClick()
        rule.onNodeWithTag("remove_dialog_text").assertTextContains("cannot be undone", substring = true).assertTextContains("Fix the build", substring = true)
        assertTrue(calls.disposedSessions.isEmpty())

        rule.onNodeWithTag("remove_cancel").performClick()
        rule.onNodeWithTag("remove_dialog_text").assertDoesNotExist()
        assertTrue(calls.disposedSessions.isEmpty() && removed.isEmpty())

        openMenu()
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

        openMenu()
        rule.onNodeWithTag("remove_session").performClick()
        rule.onNodeWithTag("remove_confirm").performClick()

        rule.waitUntil(5_000) { rule.onAllNodesCount("manage_notice") == 1 }
        rule.onNodeWithTag("manage_notice").assertTextContains("device check", substring = true)
        assertTrue("it is still there, so the screen does not leave it", removed.isEmpty())
    }

    @Test
    fun removingAChatAsksFirstAndSendsNothingUntilConfirmed() {
        val calls = Calls()
        val side = ChatRow(uri = "ahp-chat:/c2", title = "Side chat", state = dev.ahpclient.state.ActivityState.IDLE, activity = null, whenText = "", removable = true)
        showBar(calls, detail().copy(chats = listOf(side)), shownChat = side)

        openMenu()
        rule.onNodeWithTag("remove_chat").performClick()
        rule.onNodeWithTag("remove_dialog_text").assertTextContains("Side chat", substring = true)
        assertTrue(calls.disposedChats.isEmpty())
        rule.onNodeWithTag("remove_cancel").performClick()
        assertTrue(calls.disposedChats.isEmpty())

        openMenu()
        rule.onNodeWithTag("remove_chat").performClick()
        rule.onNodeWithTag("remove_confirm").performClick()

        rule.waitUntil(5_000) { calls.disposedChats.isNotEmpty() }
        assertEquals(listOf(uri to "ahp-chat:/c2"), calls.disposedChats)
    }

    @Test
    fun theMenuOffersToRemoveTheChatOnScreenOnlyWhenTheHostWouldAllowIt() {
        val main = ChatRow(uri = "ahp-chat:/c1", title = "Main", state = dev.ahpclient.state.ActivityState.IDLE, activity = null, whenText = "", removable = false, isMain = true)
        showBar(Calls(), detail().copy(chats = listOf(main)), shownChat = main)

        openMenu()

        rule.onNodeWithTag("remove_session").assertIsDisplayed()
        rule.onAllNodesWithTag("remove_chat").assertCountEquals(0)
    }

    @Test
    fun aNewChatThatWasStartedIsHandedBackSoTheScreenCanShowIt() {
        val calls = Calls().apply { result = ManageResult.Done("ahp-chat:/new") }
        val handed = mutableListOf<String>()
        rule.setContent {
            AhpTheme { SessionMenu(detail(), uri, actions(calls), null, onShowDetails = {}, onNotice = {}, onChatCreated = { handed += it }, onRemoved = {}) }
        }

        openMenu()
        rule.onNodeWithTag("new_chat").performClick()

        rule.waitUntil(5_000) { handed.isNotEmpty() }
        assertEquals(listOf("ahp-chat:/new"), handed)
    }

    // ---- the screens ------------------------------------------------------------------------------------------

    private fun state(lifecycle: String, extra: String = ""): SessionState = Ahp.json.decodeFromString(
        SessionState.serializer(),
        """{"provider":"copilotcli","title":"Fix the build","status":1,"lifecycle":"$lifecycle","activeClients":[],"defaultChat":"ahp-chat://default/x",
            "chats":[{"resource":"ahp-chat://default/x","title":"Main chat","status":1,"modifiedAt":"2026-10-26T10:29:00.000Z"},
                     {"resource":"ahp-chat://default/y","title":"Side chat","status":1,"modifiedAt":"2026-10-26T10:29:00.000Z"}]$extra}""",
    )

    private fun showDetail(state: SessionState, manage: ManageActionsUi?, shown: String = "ahp-chat://default/x") =
        rule.setContent { AhpTheme { SessionScreen(state, null, ConnState.Online, now, {}, shownChat = shown, manage = manage, sessionUri = uri) {} } }

    @Test
    fun aReadOnlySessionHasNoManageItemsInItsMenu() {
        showDetail(state("ready"), manage = null)

        openMenu()

        rule.onNodeWithTag("menu_details").assertIsDisplayed()
        rule.onNodeWithTag("toggle_read").assertDoesNotExist()
        rule.onNodeWithTag("toggle_archive").assertDoesNotExist()
        rule.onNodeWithTag("new_chat").assertDoesNotExist()
        rule.onNodeWithTag("remove_chat").assertDoesNotExist()
        rule.onNodeWithTag("remove_session").assertDoesNotExist()
    }

    @Test
    fun aSessionThatMayBeManagedHasTheItemsAndARemoveItemOnlyWhenASideChatIsOnScreen() {
        showDetail(state("ready"), manage = actions(Calls()), shown = "ahp-chat://default/y")

        openMenu()

        rule.onNodeWithTag("toggle_read").assertIsDisplayed()
        rule.onNodeWithTag("remove_session").assertIsDisplayed()
        rule.onNodeWithTag("remove_chat").assertIsDisplayed()
    }

    @Test
    fun theMainChatOnScreenHasNoRemoveItemBecauseTheHostWouldRefuseIt() {
        showDetail(state("ready"), manage = actions(Calls()), shown = "ahp-chat://default/x")

        openMenu()

        rule.onNodeWithTag("remove_session").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesCount("remove_chat"))
    }

    @Test
    fun aSessionsOnlyChatHasNoRemoveItem() {
        val only: SessionState = Ahp.json.decodeFromString(
            SessionState.serializer(),
            """{"provider":"copilotcli","title":"Fix the build","status":1,"lifecycle":"ready","activeClients":[],
                "chats":[{"resource":"ahp-chat://default/x","title":"Main chat","status":1,"modifiedAt":"2026-10-26T10:29:00.000Z"}]}""",
        )
        showDetail(only, manage = actions(Calls()))

        openMenu()

        rule.onNodeWithTag("remove_session").assertIsDisplayed()
        rule.onNodeWithTag("remove_chat").assertDoesNotExist()
    }

    @Test
    fun aSessionStillStartingSaysSoAndThatItMayBeWaitingForTheFirstMessage() {
        showDetail(state("creating"), manage = actions(Calls()))

        rule.onNodeWithTag("session_starting").assertTextContains("may wait for your first message", substring = true)
        openMenu()
        rule.onNodeWithTag("new_chat").assertIsNotEnabled()
    }

    @Test
    fun aSessionThatFailedToStartSaysSoWithTheHostsReason() {
        showDetail(state("failed", ""","creationError":{"errorType":"E","message":"no licence"}"""), manage = null)

        rule.onNodeWithTag("session_failed").assertTextContains("Could not start this session.", substring = true).assertTextContains("no licence", substring = true)
    }

    @Test
    fun theDrawerOffersANewSessionOnlyWhenItMayBeManaged() {
        val host = TunnelInfo("t-online", "usw2", "dev-box", 6, 1, listOf(31546))
        var manage by mutableStateOf<ManageActionsUi?>(null)
        rule.setContent {
            AhpTheme {
                Drawer(host, emptyList(), manage)
            }
        }

        rule.onNodeWithTag("new_session").assertDoesNotExist()

        manage = actions(Calls())
        rule.waitForIdle()
        rule.onNodeWithTag("new_session").assertIsDisplayed()
    }

    @Test
    fun theDrawerOffersNoNewSessionWhileNotConnectedEvenWhenManaging() {
        rule.setContent { AhpTheme { Drawer(null, emptyList(), actions(Calls())) } }

        rule.onNodeWithTag("drawer_no_host").assertIsDisplayed()
        rule.onNodeWithTag("new_session").assertDoesNotExist()
    }

    @Test
    fun theManagementSwitchIsOnTheSettingsScreenAndNeedsActingForMeFirst() {
        var allowed by mutableStateOf(false)
        rule.setContent { AhpTheme { SettingsScreen({}, interactionAllowed = allowed) } }

        rule.onNodeWithTag("management_toggle").assertIsNotEnabled()
        rule.onNodeWithTag("management_hint").assertTextContains("turn on", ignoreCase = true, substring = true)

        allowed = true
        rule.waitForIdle()
        rule.onNodeWithTag("management_toggle").assertIsEnabled()
    }

    @androidx.compose.runtime.Composable
    private fun Drawer(host: TunnelInfo?, sessions: List<SessionSummary>, manage: ManageActionsUi?) = DrawerContent(
        host, ConnState.Online, sessions, now, selected = null, showArchived = false, onToggleArchived = {}, query = "", onQueryChange = {},
        listState = rememberLazyListState(), manage = manage, onOpenHost = {}, onOpenSession = {}, onOpenSettings = {}, onSessionCreated = {},
    )

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    private fun listSummary(n: Int, title: String, status: Int): SessionSummary = Ahp.json.decodeFromString(
        SessionSummary.serializer(),
        """{"resource":"ahp-session:/$n","provider":"copilotcli","title":"$title","status":$status,"createdAt":"2026-10-20T10:00:00.000Z","modifiedAt":"2026-10-26T10:00:00.000Z"}""",
    )

    // A narrow phone with the long "Show archived (199)" chip: what hid the New session button on a real device.
    private fun showCrowdedList(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) {
        val host = TunnelInfo("t-online", "usw2", "dev-box", 6, 1, listOf(31546))
        val sessions = listOf(listSummary(0, "Visible one", 1), listSummary(1, "Visible two", 1)) + (2..200).map { listSummary(it, "Old $it", 97) }
        rule.setContent {
            AhpTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(width, height)) {
                    Drawer(host, sessions, actions(Calls()))
                }
            }
        }
    }

    @Test
    fun theNewSessionButtonStaysOnTheScreenOnANarrowPhoneWithALongArchivedChip() {
        showCrowdedList(width = 360.dp, height = 800.dp)

        rule.onNodeWithTag("archived_toggle").assertIsDisplayed()
        rule.onNodeWithTag("new_session").assertIsDisplayed()
        val right = rule.onNodeWithTag("new_session").getUnclippedBoundsInRoot().right
        assertTrue("the New session button ends at $right, past the 360 dp screen", right <= 360.dp)
    }

    @Test
    fun aLongListStillScrollsToItsRowsOnASmallScreen() {
        showCrowdedList(width = 360.dp, height = 480.dp)

        rule.onNodeWithTag("session_list").performScrollToNode(androidx.compose.ui.test.hasText("Visible two"))
        rule.onNodeWithText("Visible two").assertIsDisplayed()
        rule.onNodeWithTag("new_session").assertIsDisplayed()
    }
}
