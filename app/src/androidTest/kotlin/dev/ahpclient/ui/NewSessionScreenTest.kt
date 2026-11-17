package dev.ahpclient.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ahpclient.state.AgentChoice
import dev.ahpclient.state.AgentPick
import dev.ahpclient.state.ConfigCompletions
import dev.ahpclient.state.ConfigField
import dev.ahpclient.state.ConfigOffer
import dev.ahpclient.state.ConfigOption
import dev.ahpclient.state.ConfigPick
import dev.ahpclient.state.ManageResult
import dev.ahpclient.state.ModelOption
import dev.ahpclient.state.SessionConfigForm
import dev.ahpclient.ui.theme.AhpTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The new-session page: where the agent works as chips at the top, the task and how it is sent in the message box, and one send that
 * starts the session and hands the task to its chat.
 */
@RunWith(AndroidJUnit4::class)
class NewSessionScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private class Calls {
        val sessions = mutableListOf<Pair<String, String?>>()
        val sessionPicks = mutableListOf<Map<String, ConfigPick>>()
        val asked = mutableListOf<Triple<String, String?, Map<String, ConfigPick>>>()
        var offer: suspend (provider: String, folder: String?, picks: Map<String, ConfigPick>) -> ConfigOffer = { _, _, _ -> ConfigOffer.NoSettings }
        val completionAsks = mutableListOf<Pair<String, String>>()
        var complete: (property: String, query: String) -> ConfigCompletions = { _, _ -> ConfigCompletions.SeedsOnly }
        var result: ManageResult = ManageResult.Done()
        var created: String? = "ahp-session:/new"
        val started = mutableListOf<FirstMessage>()
        var backs = 0
    }

    private val sonnet = ModelOption("sonnet", "Claude Sonnet 5.5", emptyList())
    private val haiku = ModelOption("haiku", "Claude Haiku 5.5", emptyList())
    private val reviewer = AgentChoice("file:///work/a/.github/agents/reviewer.agent.md", "Reviewer", "Reviews changes")

    private fun actions(
        calls: Calls,
        agents: List<AgentOption> = listOf(AgentOption("copilotcli", "Copilot"), AgentOption("claude", "Claude")),
        folders: List<String> = listOf("file:///work/a", "file:///work/b"),
        models: List<ModelOption> = emptyList(),
        customAgents: List<AgentChoice> = emptyList(),
        hosts: List<HostManageTarget> = emptyList(),
    ) = ManageActionsUi(
        agents = agents,
        folders = folders,
        setRead = { _, _ -> calls.result },
        setArchived = { _, _ -> calls.result },
        newChat = { calls.result },
        newSession = { p, f, picks ->
            calls.sessions += p to f
            calls.sessionPicks += picks
            if (calls.result is ManageResult.Done) ManageResult.Done(calls.created) else calls.result
        },
        disposeSession = { calls.result },
        disposeChat = { _, _ -> calls.result },
        sessionSettings = { p, f, picks -> calls.asked += Triple(p, f, picks); calls.offer(p, f, picks) },
        settingCompletions = { _, _, _, property, query -> calls.completionAsks += property to query; calls.complete(property, query) },
        models = { models },
        customAgents = customAgents,
        hosts = hosts,
    )

    private fun show(calls: Calls, manage: ManageActionsUi = actions(calls)) {
        rule.setContent { AhpTheme { NewSessionScreen(manage, onBack = { calls.backs++ }, onStarted = { calls.started += it }) } }
        rule.waitForIdle()
    }

    private fun count(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    private fun type(text: String) = rule.onNodeWithTag("new_session_input").performTextInput(text)

    private fun start() {
        rule.onNodeWithTag("new_session_start").assertIsEnabled().performClick()
    }

    private fun pickHarness(provider: String) {
        rule.onNodeWithTag("pick_harness").performClick()
        rule.onNodeWithTag("agent_$provider").performClick()
        rule.waitForIdle()
    }

    private fun pickFolder(tag: String) {
        rule.onNodeWithTag("pick_folder").performClick()
        rule.onNodeWithTag(tag).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun openSetting(id: String): SemanticsNodeInteraction {
        rule.waitUntil(5_000) { count("pick_setting_$id") == 1 }
        return rule.onNodeWithTag("pick_setting_$id").performClick()
    }

    // ---- starting -------------------------------------------------------------------------------------------------

    @Test
    fun itOpensOnTheFirstAgentAndTheMostRecentFolderAndOneSendStartsTheSessionWithTheTask() {
        val calls = Calls()
        show(calls)

        rule.onNodeWithTag("pick_harness").assertContentDescriptionContains("Agent harness: Copilot")
        rule.onNodeWithTag("pick_folder").assertContentDescriptionContains("Folder: ", substring = true)
        type("Fix the build")
        start()

        rule.waitUntil(5_000) { calls.started.isNotEmpty() }
        assertEquals(listOf<Pair<String, String?>>("copilotcli" to "file:///work/a"), calls.sessions)
        val first = calls.started.single()
        assertEquals("ahp-session:/new", first.sessionUri)
        assertEquals("Fix the build", first.text)
        assertEquals(AgentPick.Keep, first.agent)
        assertEquals(null, first.hostId)
    }

    @Test
    fun nothingStartsWithoutATask() {
        val calls = Calls()
        show(calls)

        rule.onNodeWithTag("new_session_start").assertIsNotEnabled()
        type("   ")
        rule.onNodeWithTag("new_session_start").assertIsNotEnabled()
        type("Go")
        rule.onNodeWithTag("new_session_start").assertIsEnabled()
    }

    @Test
    fun theHarnessAndFolderPickedAreTheOnesSent() {
        val calls = Calls()
        show(calls)

        pickHarness("claude")
        pickFolder("folder_file:///work/b")
        rule.onNodeWithTag("pick_harness").assertContentDescriptionContains("Agent harness: Claude")
        type("Go")
        start()

        rule.waitUntil(5_000) { calls.sessions.isNotEmpty() }
        assertEquals(listOf<Pair<String, String?>>("claude" to "file:///work/b"), calls.sessions)
    }

    @Test
    fun noFolderCanBePickedAndThenNoneIsSent() {
        val calls = Calls()
        show(calls)

        pickFolder("folder_none")
        rule.onNodeWithTag("pick_folder").assertContentDescriptionContains("Folder: No folder")
        type("Go")
        start()

        rule.waitUntil(5_000) { calls.sessions.isNotEmpty() }
        assertEquals(listOf<Pair<String, String?>>("copilotcli" to null), calls.sessions)
    }

    @Test
    fun aHostWithNoFoldersStartsWithNoFolderAndSaysSoInTheList() {
        val calls = Calls()
        show(calls, actions(calls, folders = emptyList()))

        rule.onNodeWithTag("pick_folder").assertContentDescriptionContains("Folder: No folder").performClick()
        rule.onNodeWithTag("no_folders").assertTextContains("no folders to pick", substring = true)
        rule.onNodeWithTag("folder_none").assertIsSelected().performClick()
        type("Go")
        start()

        rule.waitUntil(5_000) { calls.sessions.isNotEmpty() }
        assertEquals(listOf<Pair<String, String?>>("copilotcli" to null), calls.sessions)
    }

    @Test
    fun withNoAgentsItSaysSoAndCannotStart() {
        val calls = Calls()
        show(calls, actions(calls, agents = emptyList()))

        rule.onNodeWithTag("no_agents").assertIsDisplayed()
        type("Go")
        rule.onNodeWithTag("new_session_start").assertIsNotEnabled()
    }

    @Test
    fun aRefusedStartStaysWithTheReasonAndTheTask() {
        val calls = Calls().apply { result = ManageResult.Refused(-32003, "already exists") }
        show(calls)

        type("Fix the build")
        start()

        rule.waitUntil(5_000) { count("new_session_notice") == 1 }
        rule.onNodeWithTag("new_session_notice").assertTextContains("The host refused it: already exists")
        rule.onNodeWithTag("new_session_input").assertTextContains("Fix the build")
        assertTrue(calls.started.isEmpty())
    }

    @Test
    fun aStartThatFailedTheDeviceCheckSaysSo() {
        val calls = Calls().apply { result = ManageResult.NotConfirmed }
        show(calls)

        type("Go")
        start()

        rule.waitUntil(5_000) { count("new_session_notice") == 1 }
        rule.onNodeWithTag("new_session_notice").assertTextContains("device check", substring = true)
        assertTrue(calls.started.isEmpty())
    }

    @Test
    fun theCloseButtonGoesBackAndStartsNothing() {
        val calls = Calls()
        show(calls)

        rule.onNodeWithTag("new_session_close").performClick()

        assertEquals(1, calls.backs)
        assertTrue(calls.sessions.isEmpty())
    }

    // ---- the host's settings ----------------------------------------------------------------------------------------

    private val quick = ConfigOption(ConfigPick.Text("low"), "Quick")
    private val thorough = ConfigOption(ConfigPick.Text("high"), "Thorough")

    /** A host that offers isolation (worktree by default), a thinking level, and mode and approvals, with the choices so far in it. */
    private fun formFor(
        picks: Map<String, ConfigPick>,
        required: Boolean = false,
        omitted: Int = 0,
        title: String = "Thinking",
        description: String? = "How hard to think",
    ): ConfigOffer = ConfigOffer.Form(
        SessionConfigForm(
            listOf(
                ConfigField("thinking", title, description, listOf(quick, thorough), required, picks["thinking"] ?: if (required) null else ConfigPick.Text("low")),
                ConfigField(
                    "isolation", "Isolation", null,
                    listOf(ConfigOption(ConfigPick.Text("folder"), "Folder"), ConfigOption(ConfigPick.Text("worktree"), "Worktree")),
                    false, picks["isolation"] ?: ConfigPick.Text("worktree"),
                ),
                ConfigField(
                    "mode", "Mode", null,
                    listOf(ConfigOption(ConfigPick.Text("interactive"), "Interactive"), ConfigOption(ConfigPick.Text("plan"), "Plan")),
                    false, picks["mode"] ?: ConfigPick.Text("interactive"),
                ),
                ConfigField(
                    "autoApprove", "Approvals", null,
                    listOf(ConfigOption(ConfigPick.Text("default"), "Manual"), ConfigOption(ConfigPick.Text("autoApprove"), "Allow all")),
                    false, picks["autoApprove"] ?: ConfigPick.Text("default"),
                ),
            ),
            omitted,
            emptyMap(),
        ),
    )

    private fun Calls.hostOffers(required: Boolean = false, omitted: Int = 0) {
        offer = { _, _, picks -> formFor(picks, required, omitted) }
    }

    @Test
    fun theHostIsAskedForTheAgentAndFolderOnScreenAndItsSettingsBecomeChips() {
        val calls = Calls().apply { hostOffers() }
        show(calls)

        rule.waitUntil(5_000) { count("pick_setting_isolation") == 1 }
        assertEquals(Triple("copilotcli", "file:///work/a", emptyMap<String, ConfigPick>()), calls.asked.first())
        rule.onNodeWithTag("pick_setting_isolation").assertContentDescriptionContains("Isolation: Worktree")
        rule.onNodeWithTag("pick_setting_thinking").assertContentDescriptionContains("Thinking: Quick")
        // Mode and approvals are not setup chips: they sit in the message box.
        assertEquals(0, count("pick_setting_mode"))
        assertEquals(0, count("pick_setting_autoApprove"))
        rule.onNodeWithTag("new_session_mode").assertContentDescriptionContains("Mode and approvals: Interactive · Manual")
    }

    @Test
    fun aSettingShowsItsTitleDescriptionAndChoicesAndAPickAsksAgainAndIsSent() {
        val calls = Calls().apply { hostOffers() }
        show(calls)

        openSetting("thinking")
        rule.onNodeWithTag("setting_title_thinking").assertTextEquals("Thinking")
        rule.onNodeWithTag("setting_description_thinking").assertTextEquals("How hard to think")
        rule.onNodeWithTag("setting_thinking_0").assertIsSelected()
        rule.onNodeWithTag("setting_thinking_1").assertIsNotSelected().performClick()
        rule.waitUntil(5_000) { calls.asked.any { it.third == mapOf("thinking" to ConfigPick.Text("high")) } }
        rule.waitForIdle()

        rule.onNodeWithTag("pick_setting_thinking").assertContentDescriptionContains("Thinking: Thorough")
        type("Go")
        start()
        rule.waitUntil(5_000) { calls.sessions.isNotEmpty() }
        assertEquals(mapOf<String, ConfigPick>("thinking" to ConfigPick.Text("high")), calls.sessionPicks.single())
    }

    @Test
    fun branchVersusWorktreeIsAChoiceAndIsSent() {
        val calls = Calls().apply { hostOffers() }
        show(calls)

        openSetting("isolation")
        rule.onNodeWithTag("setting_isolation_0").performClick()
        rule.waitUntil(5_000) { calls.asked.any { it.third["isolation"] == ConfigPick.Text("folder") } }
        rule.waitForIdle()

        rule.onNodeWithTag("pick_setting_isolation").assertContentDescriptionContains("Isolation: Folder")
        type("Go")
        start()
        rule.waitUntil(5_000) { calls.sessions.isNotEmpty() }
        assertEquals(mapOf<String, ConfigPick>("isolation" to ConfigPick.Text("folder")), calls.sessionPicks.single())
    }

    @Test
    fun modeAndApprovalsArePickedInTheMessageBoxAndAllowAllIsAWarning() {
        val calls = Calls().apply { hostOffers() }
        show(calls)
        rule.waitUntil(5_000) { count("new_session_mode") == 1 }

        rule.onNodeWithTag("new_session_mode").performClick()
        rule.onNodeWithTag("setting_mode_1").performClick()
        rule.waitUntil(5_000) { calls.asked.any { it.third["mode"] == ConfigPick.Text("plan") } }
        rule.waitForIdle()
        rule.onNodeWithTag("setting_autoApprove_1").performClick()
        rule.waitUntil(5_000) { calls.asked.any { it.third["autoApprove"] == ConfigPick.Text("autoApprove") } }
        rule.waitForIdle()
        rule.onNodeWithTag("allow_all_warning").assertIsDisplayed()
        rule.onNodeWithTag("chat_sheet_done").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("new_session_mode").assertContentDescriptionContains("Plan · Allow all", substring = true)
            .assertContentDescriptionContains("Tool calls run without asking", substring = true)
        type("Go")
        start()
        rule.waitUntil(5_000) { calls.sessions.isNotEmpty() }
        assertEquals(mapOf("mode" to ConfigPick.Text("plan"), "autoApprove" to ConfigPick.Text("autoApprove")), calls.sessionPicks.single())
    }

    @Test
    fun changingTheFolderDropsTheChoicesAndAsksAgain() {
        val calls = Calls().apply { hostOffers() }
        show(calls)
        openSetting("thinking")
        rule.onNodeWithTag("setting_thinking_1").performClick()
        rule.waitUntil(5_000) { calls.asked.size >= 2 }

        pickFolder("folder_file:///work/b")
        rule.waitUntil(5_000) { calls.asked.last().second == "file:///work/b" }
        rule.waitForIdle()

        assertEquals(emptyMap<String, ConfigPick>(), calls.asked.last().third)
        rule.onNodeWithTag("pick_setting_thinking").assertContentDescriptionContains("Thinking: Quick")
    }

    @Test
    fun changingTheHarnessDropsTheChoicesAndAsksAgain() {
        val calls = Calls().apply { hostOffers() }
        show(calls)
        openSetting("thinking")
        rule.onNodeWithTag("setting_thinking_1").performClick()
        rule.waitUntil(5_000) { calls.asked.size >= 2 }

        pickHarness("claude")
        rule.waitUntil(5_000) { calls.asked.last().first == "claude" }
        rule.waitForIdle()

        assertEquals(emptyMap<String, ConfigPick>(), calls.asked.last().third)
    }

    @Test
    fun aRequiredSettingWithNoValueBlocksStartUntilChosenAndIsNamed() {
        val calls = Calls().apply { hostOffers(required = true) }
        show(calls)
        type("Go")

        rule.waitUntil(5_000) { count("settings_missing") == 1 }
        rule.onNodeWithTag("settings_missing").assertTextContains("Thinking", substring = true)
        rule.onNodeWithTag("new_session_start").assertIsNotEnabled()

        openSetting("thinking")
        rule.onNodeWithTag("setting_title_thinking").assertTextEquals("Thinking (required)")
        rule.onNodeWithTag("setting_thinking_0").performClick()
        rule.waitUntil(5_000) { count("settings_missing") == 0 }
        rule.onNodeWithTag("new_session_start").assertIsEnabled()
    }

    @Test
    fun startWaitsWhileTheHostIsBeingAsked() {
        val calls = Calls()
        val answer = CompletableDeferred<ConfigOffer>()
        calls.offer = { _, _, _ -> answer.await() }
        show(calls)
        type("Go")

        rule.waitUntil(5_000) { count("settings_loading") == 1 }
        rule.onNodeWithTag("new_session_start").assertIsNotEnabled()

        answer.complete(ConfigOffer.NoSettings)
        rule.waitUntil(5_000) { count("settings_loading") == 0 }
        rule.onNodeWithTag("new_session_start").assertIsEnabled()
    }

    @Test
    fun aHostThatCannotBeAskedSaysSoAndStillStartsWithItsOwnSettings() {
        val calls = Calls().apply { offer = { _, _, _ -> ConfigOffer.Unavailable("The host refused it: no such method") } }
        show(calls)

        rule.waitUntil(5_000) { count("settings_note") == 1 }
        rule.onNodeWithTag("settings_note").assertTextContains("no such method", substring = true).assertTextContains("host will use its own", substring = true)
        type("Go")
        start()
        rule.waitUntil(5_000) { calls.sessions.isNotEmpty() }
        assertEquals(emptyMap<String, ConfigPick>(), calls.sessionPicks.single())
    }

    @Test
    fun aChoiceTheHostNoLongerOffersIsDropped() {
        val calls = Calls().apply { offer = { _, _, picks -> if (picks.isEmpty()) formFor(picks) else ConfigOffer.NoSettings } }
        show(calls)
        openSetting("thinking")

        rule.onNodeWithTag("setting_thinking_1").performClick()
        rule.waitUntil(5_000) { calls.asked.size >= 3 }
        rule.waitForIdle()

        type("Go")
        start()
        rule.waitUntil(5_000) { calls.sessions.isNotEmpty() }
        assertEquals(emptyMap<String, ConfigPick>(), calls.sessionPicks.single())
    }

    /** A host with a starting branch too long to list: it seeds "main", and holds whatever the person chose. */
    private fun Calls.hostOffersBranch() {
        offer = { _, _, picks ->
            ConfigOffer.Form(
                SessionConfigForm(
                    listOf(ConfigField("branch", "Branch", null, listOf(ConfigOption(ConfigPick.Text("main"), "main")), false, picks["branch"] ?: ConfigPick.Text("main"), dynamic = true)),
                    0,
                    emptyMap(),
                ),
            )
        }
    }

    @Test
    fun theStartingBranchIsSearchedAndOnlyAHostReturnedValueCanBeChosen() {
        val calls = Calls().apply {
            hostOffersBranch()
            complete = { _, query -> ConfigCompletions.Items(if (query == "fea") listOf(ConfigOption(ConfigPick.Text("feature-x"), "Feature X")) else emptyList()) }
        }
        show(calls)

        openSetting("branch")
        rule.onNodeWithTag("setting_branch_0").assertIsSelected()
        rule.onNodeWithTag("setting_search_branch").performTextInput("fea")
        rule.waitUntil(5_000) { count("setting_branch_found_0") == 1 }
        rule.onNodeWithTag("setting_branch_found_0").assertTextContains("Feature X", substring = true).performClick()
        rule.waitUntil(5_000) { calls.asked.any { it.third["branch"] == ConfigPick.Text("feature-x") } }
        rule.waitForIdle()

        rule.onNodeWithTag("pick_setting_branch").assertContentDescriptionContains("Branch: feature-x")
        type("Go")
        start()
        rule.waitUntil(5_000) { calls.sessions.isNotEmpty() }
        assertEquals(mapOf<String, ConfigPick>("branch" to ConfigPick.Text("feature-x")), calls.sessionPicks.single())
        assertEquals("branch" to "fea", calls.completionAsks.last())
    }

    @Test
    fun aBranchSearchThatFailsIsSaidInWords() {
        val calls = Calls().apply { hostOffersBranch(); complete = { _, _ -> ConfigCompletions.Unavailable("The host did not answer.") } }
        show(calls)
        openSetting("branch")

        rule.onNodeWithTag("setting_search_branch").performTextInput("zzz")
        rule.waitUntil(5_000) { count("setting_search_problem_branch") == 1 }
        rule.onNodeWithTag("setting_search_problem_branch").assertTextContains("The host did not answer.", substring = true)
    }

    @Test
    fun settingsThisAppCannotShowAreCounted() {
        val calls = Calls().apply { hostOffers(omitted = 2) }
        show(calls)
        rule.waitUntil(5_000) { count("settings_omitted") == 1 }
        rule.onNodeWithTag("settings_omitted").assertTextContains("2 more settings this app cannot show", substring = true)
    }

    @Test
    fun theHostsWordsAreShownAsTextAndTheRowsAreBigEnoughToTap() {
        val hostile = "<b>bold</b> [link](https://evil.example)"
        val calls = Calls().apply { offer = { _, _, picks -> formFor(picks, title = hostile, description = hostile) } }
        show(calls)

        openSetting("thinking")
        rule.onNodeWithTag("setting_title_thinking").assertTextEquals(hostile)
        rule.onNodeWithTag("setting_description_thinking").assertTextEquals(hostile)
        rule.onNodeWithTag("setting_thinking_0").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun theTaskAndTheChoicesSurviveRotation() {
        val calls = Calls().apply { hostOffers() }
        val restoration = StateRestorationTester(rule)
        restoration.setContent { AhpTheme { NewSessionScreen(actions(calls), onBack = {}, onStarted = { calls.started += it }) } }
        openSetting("thinking")
        rule.onNodeWithTag("setting_thinking_1").performClick()
        rule.waitUntil(5_000) { calls.asked.size >= 2 }
        rule.waitForIdle()
        type("Keep me")

        restoration.emulateSavedInstanceStateRestore()
        rule.waitUntil(5_000) { count("pick_setting_thinking") == 1 }
        rule.waitForIdle()

        rule.onNodeWithTag("pick_setting_thinking").assertContentDescriptionContains("Thinking: Thorough")
        rule.onNodeWithTag("new_session_input").assertTextContains("Keep me")
        start()
        rule.waitUntil(5_000) { calls.sessions.isNotEmpty() }
        assertEquals(mapOf<String, ConfigPick>("thinking" to ConfigPick.Text("high")), calls.sessionPicks.single())
    }

    // ---- folders ----------------------------------------------------------------------------------------------------

    @Test
    fun onlyTheMostRecentFoldersAreListedAtFirstAndTheRestOnRequest() {
        val calls = Calls()
        show(calls, actions(calls, folders = (1..12).map { "file:///work/f$it" }))

        rule.onNodeWithTag("pick_folder").performClick()
        rule.onNodeWithTag("folder_file:///work/f8").assertExists()
        rule.onNodeWithTag("folder_file:///work/f9").assertDoesNotExist()
        rule.onNodeWithTag("folders_show_all").performScrollTo().assertTextEquals("Show 4 more folders").performClick()
        rule.onNodeWithTag("folder_file:///work/f12").performScrollTo().performClick()
        rule.waitForIdle()

        type("Go")
        start()
        rule.waitUntil(5_000) { calls.sessions.isNotEmpty() }
        assertEquals(listOf<Pair<String, String?>>("copilotcli" to "file:///work/f12"), calls.sessions)
    }

    // ---- the message box --------------------------------------------------------------------------------------------

    @Test
    fun theModelStartsOnTheHostsFirstAndAnotherCanBePickedForTheFirstMessage() {
        val calls = Calls()
        show(calls, actions(calls, models = listOf(sonnet, haiku)))

        rule.onNodeWithTag("new_session_model").assertContentDescriptionContains("Model: Claude Sonnet 5.5")
        rule.onNodeWithTag("new_session_model").performClick()
        rule.onAllNodesWithTag("model_option")[1].performClick()
        rule.onNodeWithTag("model_use").performClick()
        rule.onNodeWithTag("new_session_model").assertContentDescriptionContains("Model: Claude Haiku 5.5")
        type("Go")
        start()

        rule.waitUntil(5_000) { calls.started.isNotEmpty() }
        assertEquals("haiku", calls.started.single().model?.id)
    }

    @Test
    fun withNoModelsOrCustomAgentsThoseChipsAreNotShown() {
        val calls = Calls()
        show(calls)

        assertEquals(0, count("new_session_model"))
        assertEquals(0, count("new_session_agent"))
        assertEquals(0, count("new_session_mode"))
    }

    @Test
    fun aCustomAgentPickedGoesWithTheFirstMessage() {
        val calls = Calls()
        show(calls, actions(calls, customAgents = listOf(reviewer)))

        rule.onNodeWithTag("new_session_agent").assertContentDescriptionContains("Agent: Default agent").performClick()
        rule.onAllNodesWithTag("agent_option")[0].performClick()
        rule.onNodeWithTag("new_session_agent").assertContentDescriptionContains("Agent: Reviewer")
        type("Go")
        start()

        rule.waitUntil(5_000) { calls.started.isNotEmpty() }
        assertEquals(AgentPick.Use(reviewer.uri), calls.started.single().agent)
    }

    // ---- several hosts ----------------------------------------------------------------------------------------------

    @Test
    fun withSeveralHostsTheHostIsAChipAndTheSessionStartsOnTheOnePicked() {
        val calls = Calls()
        val host1 = mutableListOf<Pair<String, String?>>()
        val host2 = mutableListOf<Pair<String, String?>>()
        val manage = actions(
            calls,
            hosts = listOf(
                HostManageTarget("h1", "laptop-box", listOf(AgentOption("copilotcli", "Copilot")), listOf("file:///work/host1"), newSession = { p, f, _ -> host1 += p to f; ManageResult.Done("ahp-session:/h1") }),
                HostManageTarget("h2", "workstation-box", listOf(AgentOption("claude", "Claude")), listOf("file:///work/host2"), newSession = { p, f, _ -> host2 += p to f; ManageResult.Done("ahp-session:/h2") }),
            ),
        )
        show(calls, manage)

        rule.onNodeWithTag("pick_host").assertContentDescriptionContains("Host: laptop-box").performClick()
        rule.onNodeWithTag("host_h1").assertIsSelected()
        rule.onNodeWithTag("host_h2").assertIsNotSelected().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("pick_host").assertContentDescriptionContains("Host: workstation-box")
        rule.onNodeWithTag("pick_harness").assertContentDescriptionContains("Agent harness: Claude")
        type("Go")
        start()

        rule.waitUntil(5_000) { calls.started.isNotEmpty() }
        assertTrue(host1.isEmpty())
        assertEquals(listOf<Pair<String, String?>>("claude" to "file:///work/host2"), host2)
        assertEquals("h2", calls.started.single().hostId)
        assertEquals("ahp-session:/h2", calls.started.single().sessionUri)
    }

    @Test
    fun withOneHostThereIsNoHostChip() {
        val calls = Calls()
        show(calls)

        assertEquals(0, count("pick_host"))
    }
}
