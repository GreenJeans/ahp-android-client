package dev.ahpclient.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.dp
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ahpclient.app.AppState
import dev.ahpclient.app.HostEntry
import dev.ahpclient.app.HostList
import dev.ahpclient.app.SignInStep
import dev.ahpclient.state.ActivityState
import dev.ahpclient.state.AgentChoice
import dev.ahpclient.state.ChatGoneException
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.DispatchOutcome
import dev.ahpclient.state.InputRequestView
import dev.ahpclient.state.InteractResult
import dev.ahpclient.state.ManageResult
import dev.ahpclient.state.ModelOption
import dev.ahpclient.state.ToolState
import dev.ahpclient.state.Transcript
import dev.ahpclient.state.TranscriptItem
import dev.ahpclient.state.TurnOutcome
import dev.ahpclient.tunnel.TunnelInfo
import dev.ahpclient.ui.chat.ChatActionsUi
import dev.ahpclient.ui.chat.ChatPane
import dev.ahpclient.ui.chat.ChatScreen
import dev.ahpclient.state.FinishedWatch
import dev.ahpclient.ui.theme.AhpTheme
import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.SessionState
import com.microsoft.agenthostprotocol.generated.SessionSummary
import com.microsoft.agenthostprotocol.generated.ToolCallState
import java.time.Instant
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the Accessibility Test Framework over every screen as rendered: touch target size, text contrast against the
 * actual background, missing labels and duplicate descriptions. A failure here is a real accessibility defect; it
 * complements, and does not replace, a pass with TalkBack on a device.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityTest {
    @get:Rule
    val rule = createComposeRule()

    private val host = TunnelInfo("t-online", "usw2", "dev-box", 6, 1, listOf(31546))
    private val now = Instant.parse("2026-10-26T10:30:00Z")

    private fun summary(uri: String, title: String, status: Int, extra: String = ""): SessionSummary = Ahp.json.decodeFromString(
        SessionSummary.serializer(),
        """{"resource":"$uri","provider":"copilotcli","title":"$title","status":$status,"createdAt":"2026-10-20T10:00:00.000Z","modifiedAt":"2026-10-26T10:00:00.000Z"$extra}""",
    )

    private val sessions = listOf(
        summary("ahp-session:/w", "Needs answer", 56, ""","project":{"uri":"file:///w","displayName":"repo-w"}"""),
        summary("ahp-session:/k", "Working", 40, ""","activity":"Running tests""""),
        summary("ahp-session:/e", "Failed run", 2),
        summary("ahp-session:/u", "Unread one", 1),
        summary("ahp-session:/a", "Old one", 97),
    )

    private fun detail(): SessionState = Ahp.json.decodeFromString(
        SessionState.serializer(),
        """{"provider":"copilotcli","title":"Fix the build","status":56,"lifecycle":"ready","activeClients":[],"activity":"Waiting on you",
            "project":{"uri":"file:///work/repo","displayName":"repo"},"workingDirectories":["file:///work/repo"],
            "chats":[{"resource":"ahp-chat://default/x","title":"Main chat","status":56,"modifiedAt":"2026-10-26T10:29:00.000Z"}],
            "inputNeeded":[{"kind":"chatInput","id":"r1","chat":"c","request":{"id":"q","message":"Which branch?","questions":[
                {"kind":"single-select","id":"a","message":"Which branch?","options":[{"id":"m","label":"main","recommended":true},{"id":"d","label":"develop"}]}]}}]}""",
    )

    @Before
    fun turnOnTheChecker() {
        // The checks are silently skipped below API 34, which would turn every test here into a false pass.
        assumeTrue("Compose accessibility checks need API 34 or newer", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        // Without this, tryPerformAccessibilityChecks() quietly does nothing; the canary test below proves it now bites.
        rule.enableAccessibilityChecks()
    }

    private fun check() = rule.onRoot().tryPerformAccessibilityChecks()

    @Test
    fun signInScreens() {
        val states = listOf(
            AppState.SignedOut(SignInStep.Idle, null),
            AppState.SignedOut(SignInStep.Idle, "Your GitHub sign-in expired. Sign in again."),
            AppState.SignedOut(SignInStep.AwaitingApproval("ABCD-1234", "https://github.com/login/device", now), null),
            AppState.SignedOut(SignInStep.Failed("The code expired before it was approved. Start again."), null),
        )
        checkEach(*states.map { state -> @Composable { SignInScreen(state, {}, {}, {}, {}) } }.toTypedArray())
    }

    private val browsers = listOf(BrowserOption("com.android.chrome", "Chrome"), BrowserOption("org.mozilla.firefox", "Firefox"))

    @Composable
    private fun HostScreenFor(list: HostList? = null, connected: TunnelInfo? = null, connection: ConnState? = null, browser: String? = null, renews: Boolean = false) =
        HostScreen(
            list, connected, connection, onBack = {}, onRefresh = {}, onConnect = {}, onDisconnect = {}, onSignOut = {},
            browsers = browsers, browser = browser, onBrowser = {}, tokenExpiresAt = now.plusSeconds(3600), renews = renews,
        )

    @Composable
    private fun DrawerFor(
        connected: TunnelInfo? = host,
        connection: ConnState? = ConnState.Online,
        list: List<SessionSummary> = sessions,
        showArchived: Boolean = false,
        query: String = "",
        manage: ManageActionsUi? = null,
        watched: Set<String> = emptySet(),
    ) = DrawerContent(
        connected, connection, list, now, selected = null, showArchived = showArchived, onToggleArchived = {}, query = query, onQueryChange = {},
        listState = rememberLazyListState(), manage = manage, onOpenHost = {}, onOpenSession = {}, onOpenSettings = {}, onSessionCreated = {}, watched = watched,
    )

    @Test
    fun hostScreens() {
        val entries = listOf(HostEntry(host, isLastUsed = true), HostEntry(TunnelInfo("t-off", "usw2", "old-laptop", 5, 0, listOf(31546)), isLastUsed = false))
        val lists = listOf(HostList.Loading, HostList.Loaded(entries), HostList.Empty, HostList.Failed("Could not reach the Dev Tunnels service."))
        checkEach(
            *lists.map { list -> @Composable { HostScreenFor(list) } }.toTypedArray(),
            { HostScreenFor(connected = host, connection = ConnState.Online, browser = "org.mozilla.firefox", renews = true) },
            { HostScreenFor(connected = host, connection = ConnState.Reconnecting(2, null)) },
        )
    }

    @Test
    fun emptyMainPage() {
        checkEach(
            { MainEmpty(connected = false, connection = null, manage = null, onConnect = {}, onOpenSessions = {}, onSessionCreated = {}) },
            { MainEmpty(connected = true, connection = ConnState.Online, manage = null, onConnect = {}, onOpenSessions = {}, onSessionCreated = {}) },
            { MainEmpty(connected = true, connection = ConnState.Online, manage = manageActions(), onConnect = {}, onOpenSessions = {}, onSessionCreated = {}) },
        )
    }

    @Test
    fun sessionListInEveryConnectionState() {
        val states = listOf(ConnState.Online, ConnState.Connecting, ConnState.Reconnecting(2, null), ConnState.Failed(IllegalStateException("no running agent host")))
        checkEach(*states.map { connection -> @Composable { DrawerFor(connection = connection, showArchived = true) } }.toTypedArray())
    }

    @Test
    fun sessionListSearchedAndEmpty() {
        checkEach(
            { DrawerFor(query = "no such session") },
            { DrawerFor(list = emptyList()) },
            { DrawerFor(connected = null, connection = null, list = emptyList()) },
            { DrawerFor(manage = manageActions()) },
            { DrawerFor(watched = setOf("ahp-session:/k", "ahp-session:/u")) },
        )
    }

    @Test
    fun settingsInEveryState() {
        checkEach(
            { SettingsScreen({}) },
            { SettingsScreen({}, monitorEnabled = true, notificationsAllowed = false) },
            { SettingsScreen({}, monitorEnabled = true, interactionAllowed = true, managementAllowed = true) },
            { SettingsScreen({}, interactionSupported = false) },
            { SettingsScreen({}, monitorEnabled = true, finishedAll = true) },
            { SettingsScreen({}, monitorEnabled = false, finishedAll = true) },
        )
    }

    @Test
    fun sessionDetailWithAQuestionAndWithout() {
        checkEach(
            { SessionScreen(detail(), null, ConnState.Online, now, {}) },
            { SessionScreen(null, null, ConnState.Online, now, {}) },
            { SessionScreen(null, IllegalStateException("x"), ConnState.Reconnecting(1, null), now, {}) },
        )
    }

    private val twoChatSession: SessionState = Ahp.json.decodeFromString(
        SessionState.serializer(),
        """{"provider":"copilotcli","title":"Fix the build","status":56,"lifecycle":"ready","activeClients":[],"defaultChat":"ahp-chat://default/x",
            "project":{"uri":"file:///work/repo","displayName":"repo"},"workingDirectories":["file:///work/repo"],
            "changesets":[{"label":"Uncommitted","uriTemplate":"ahp-changeset:/c1","changeKind":"uncommitted"}],
            "chats":[{"resource":"ahp-chat://default/x","title":"Main chat","status":1,"modifiedAt":"2026-10-26T10:29:00.000Z"},
                     {"resource":"ahp-chat://default/y","title":"Side chat waiting","status":56,"modifiedAt":"2026-10-26T10:28:00.000Z"}]}""",
    )

    private val planAgent = AgentChoice("file:///a/plan.agent.md", "plan", "Plan first, then act")

    private fun chatActions(running: Boolean = false, agents: List<AgentChoice> = emptyList(), agentInUse: String? = null) = ChatActionsUi(
        running = running,
        send = { _, _, _ -> InteractResult.Sent(DispatchOutcome.Accepted) },
        steer = { _, _, _ -> InteractResult.Sent(DispatchOutcome.Accepted) },
        cancel = { InteractResult.Sent(DispatchOutcome.Accepted) },
        approve = { _, _ -> InteractResult.Sent(DispatchOutcome.Accepted) },
        deny = { _, _ -> InteractResult.Sent(DispatchOutcome.Accepted) },
        approveResult = { InteractResult.Sent(DispatchOutcome.Accepted) },
        denyResult = { InteractResult.Sent(DispatchOutcome.Accepted) },
        answer = { _, _ -> InteractResult.Sent(DispatchOutcome.Accepted) },
        saveDraft = { _, _, _ -> InteractResult.Sent(DispatchOutcome.Accepted) },
        decline = { InteractResult.Sent(DispatchOutcome.Accepted) },
        dismiss = { InteractResult.Sent(DispatchOutcome.Accepted) },
        models = listOf(ModelOption("auto", "Auto", emptyList())),
        agents = agents,
        agentInUse = agentInUse,
    )

    @Test
    fun theAgentChipAndItsPickerAreAccessible() {
        rule.setContent { AhpTheme { ChatPane(everyRow, null, false, null, ConnState.Online, {}, {}, actions = chatActions(agents = listOf(planAgent), agentInUse = planAgent.uri)) } }
        check()

        rule.onNodeWithTag("agent_button").performClick()
        rule.waitForIdle()
        check()
    }

    @Test
    fun sessionScreenWithTabsAndAChat() {
        checkEach(
            {
                SessionScreen(twoChatSession, null, ConnState.Online, now, {}, shownChat = "ahp-chat://default/x") {
                    ChatPane(everyRow, null, false, null, ConnState.Online, {}, {})
                }
            },
            {
                SessionScreen(twoChatSession, null, ConnState.Online, now, {}, shownChat = "ahp-chat://default/y") {
                    ChatPane(everyRow, null, false, null, ConnState.Reconnecting(1, null), {}, {}, actions = chatActions())
                }
            },
            {
                SessionScreen(twoChatSession, null, ConnState.Online, now, {}, shownChat = "ahp-chat://default/x") {
                    ChatPane(everyRow, null, false, null, ConnState.Online, {}, {}, actions = chatActions(running = true))
                }
            },
        )
    }

    @Test
    fun sessionScreenWithItsMenuOpen() {
        rule.setContent { AhpTheme { SessionScreen(twoChatSession, null, ConnState.Online, now, {}, shownChat = "ahp-chat://default/y", manage = manageActions(), sessionUri = "ahp-session:/s") {} } }
        rule.onNodeWithTag("session_menu").performClick()
        rule.waitForIdle()

        check()
    }

    @Test
    fun sessionScreenWithItsMenuOpenAndTheAlertItemInEveryState() {
        var watch by mutableStateOf(FinishedWatch())
        rule.setContent {
            AhpTheme {
                SessionScreen(
                    twoChatSession, null, ConnState.Online, now, {}, shownChat = "ahp-chat://default/y", sessionUri = "ahp-session:/s",
                    alerts = FinishedAlertsUi(watch, monitoring = true),
                ) {}
            }
        }
        rule.onNodeWithTag("session_menu").performClick()
        // The menu stays open while only the choice changes, so each way the alert item can look is checked.
        listOf(FinishedWatch(), FinishedWatch(sessions = setOf("ahp-session:/s")), FinishedWatch(all = true)).forEach {
            watch = it
            rule.waitForIdle()
            check()
        }
    }

    @Test
    fun sessionScreenWithItsDetailsSheetOpen() {
        rule.setContent { AhpTheme { SessionScreen(detail(), null, ConnState.Online, now, {}, shownChat = "ahp-chat://default/x") {} } }
        rule.onNodeWithTag("session_menu").performClick()
        rule.onNodeWithTag("menu_details").performClick()
        rule.waitForIdle()

        check()
    }

    @Test
    fun sessionScreenWithAnUnreadableAndAnArchivedSession() {
        val archived: SessionState = Ahp.json.decodeFromString(
            SessionState.serializer(),
            """{"provider":"copilotcli","title":"Old one","status":97,"lifecycle":"ready","activeClients":[],"chats":[]}""",
        )
        val starting: SessionState = Ahp.json.decodeFromString(
            SessionState.serializer(),
            """{"provider":"copilotcli","title":"New one","status":1,"lifecycle":"creating","activeClients":[],"chats":[]}""",
        )
        checkEach(
            { SessionScreen(archived, null, ConnState.Online, now, {}) },
            { SessionScreen(starting, null, ConnState.Online, now, {}) },
        )
    }

    @Test
    fun hostScreenWithTheBrowserPickerOpen() {
        val list = HostList.Loaded(listOf(HostEntry(host, isLastUsed = true)))
        rule.setContent { AhpTheme { HostScreenFor(list) } }
        rule.onNodeWithTag("browser_row").performClick()
        rule.waitForIdle()

        check()
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

    private val everyRow = Transcript(
        title = "Fix the build",
        state = ActivityState.NEEDS_INPUT,
        activity = "Waiting on you",
        hasOlder = true,
        running = true,
        items = listOf(
            TranscriptItem.User("u1", "Please fix the failing build."),
            TranscriptItem.Reasoning("r1", "thinking", live = true),
            TranscriptItem.Reasoning("r0", "thought", live = false),
            TranscriptItem.Tool("t1", "Run in Terminal", "List the files", ToolState.WAITING_FOR_APPROVAL),
            TranscriptItem.Tool("t2", "Read file", "Open the build script", ToolState.RUNNING),
            TranscriptItem.Tool("t3", "Edit file", null, ToolState.FAILED),
            TranscriptItem.Tool("t4", "Search", "Find the setting", ToolState.DONE),
            TranscriptItem.Tool("t5", "Fetch page", null, ToolState.SIGN_IN_REQUIRED),
            TranscriptItem.Notice("n1", "Context was compacted"),
            TranscriptItem.Notice("n2", "Ended with an error", isError = true),
            TranscriptItem.Markdown("m0", "All **done**. The build now passes."),
            TranscriptItem.TurnEnd("e1", TurnOutcome.DONE, 64_000),
            TranscriptItem.TurnEnd("e2", TurnOutcome.STOPPED, null),
            TranscriptItem.TurnEnd("e3", TurnOutcome.FAILED, 4_000),
            TranscriptItem.Question("q1", InputRequestView("Question", "Which branch?", emptyList()), answered = false),
            TranscriptItem.Other("o1", "Content this app cannot show"),
        ),
    )

    @Test
    fun chatScreenInEveryState() {
        val plain = Transcript("Fix the build", ActivityState.IN_PROGRESS, null, listOf(TranscriptItem.User("u1", "Hello")), hasOlder = false)
        checkEach(
            { ChatScreen(everyRow, null, false, null, ConnState.Online, {}, {}, {}) },
            { ChatScreen(everyRow, null, true, null, ConnState.Online, {}, {}, {}) },
            { ChatScreen(everyRow, null, false, java.io.IOException("offline"), ConnState.Online, {}, {}, {}) },
            { ChatScreen(plain, null, false, null, ConnState.Reconnecting(2, null), {}, {}, {}) },
            { ChatScreen(Transcript("Chat", ActivityState.IDLE, null, emptyList(), false), null, false, null, ConnState.Online, {}, {}, {}) },
            { ChatScreen(null, null, false, null, ConnState.Online, {}, {}, {}) },
            { ChatScreen(null, ChatGoneException("ahp-chat:/x"), false, null, ConnState.Online, {}, {}, {}) },
            { ChatScreen(null, IllegalStateException("x"), false, null, ConnState.Online, {}, {}, {}) },
        )
    }

    @Test
    fun chatScreenOnceMarkdownHasRendered() {
        val withMarkdown = Transcript(
            "Fix the build", ActivityState.IN_PROGRESS, null,
            listOf(TranscriptItem.Markdown("m1", "# Plan\n\nRun the **build** and read [the docs](https://example.com/docs).\n\n- one\n- two\n\n```kotlin\nval x = 1\n```")),
            hasOlder = false,
        )
        rule.setContent { AhpTheme { ChatScreen(withMarkdown, null, false, null, ConnState.Online, {}, {}, {}) } }
        rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodesWithText("Plan").fetchSemanticsNodes().isNotEmpty() }

        check()
    }

    @Test
    fun chatScreenWithEveryBlockOpen() {
        val common = """"toolCallId":"c1","toolName":"run_in_terminal","displayName":"Run in Terminal""""
        val content = listOf(
            """{"type":"text","text":"file one\nfile two"}""",
            """{"type":"terminal","resource":"ahp-terminal:/a","title":"Build","isPty":false,"result":{"exitCode":2,"preview":"error: no such file","truncated":true}}""",
            """{"type":"terminal","resource":"ahp-terminal:/b","title":"Server","isPty":true}""",
            """{"type":"fileEdit","before":{"uri":"file:///work/repo/a.txt","content":{"uri":"ahp-content:/1"}},"after":{"uri":"file:///work/repo/a.txt","content":{"uri":"ahp-content:/2"}},"diff":{"added":12,"removed":3}}""",
            """{"type":"subagent","resource":"ahp-chat:/s","title":"Explore","agentName":"explore","description":"Look around the repo"}""",
            """{"type":"embeddedResource","contentType":"image/png","data":"AAAA"}""",
            """{"type":"hologram","x":1}""",
        ).joinToString(",")
        val done = Ahp.json.decodeFromString(
            ToolCallState.serializer(),
            """{"status":"completed",$common,"invocationMessage":"Run it","toolInput":${JsonPrimitive("""{"command":"ls -la","timeout":30}""")},"confirmed":"not-needed","success":false,"pastTenseMessage":"Ran it","content":[$content],"error":{"message":"the build failed"}}""",
        )
        val waiting = Ahp.json.decodeFromString(
            ToolCallState.serializer(),
            """{"status":"pending-confirmation",$common,"invocationMessage":"Run it","toolInput":"{}","confirmationTitle":"Run this command?","options":[]}""",
        )
        val open = Transcript(
            "Fix the build", ActivityState.NEEDS_INPUT, null, hasOlder = false,
            items = listOf(
                TranscriptItem.Reasoning("r1", "I compared the two build scripts.", live = false),
                TranscriptItem.Tool("t1", "Run in Terminal", "List the files", ToolState.FAILED, done),
                TranscriptItem.Tool("t2", "Run in Terminal", "Apply the fix", ToolState.WAITING_FOR_APPROVAL, waiting),
            ),
        )
        rule.setContent { AhpTheme { ChatScreen(open, null, false, null, ConnState.Online, {}, {}, {}, expanded = setOf("r1", "t1", "t2"), onToggle = {}) } }
        rule.waitUntil(timeoutMillis = 10_000) { rule.onAllNodesWithText("compared the two build scripts", substring = true).fetchSemanticsNodes().isNotEmpty() }

        check()
    }

    @Test
    fun chatScreenWithEveryBlockClosed() {
        val closed = Transcript(
            "Fix the build", ActivityState.IN_PROGRESS, null, hasOlder = false,
            items = listOf(
                TranscriptItem.Reasoning("r1", "I compared the two build scripts.", live = true),
                TranscriptItem.Tool("t1", "Run in Terminal", "List the files", ToolState.DONE, Ahp.json.decodeFromString(
                    ToolCallState.serializer(),
                    """{"status":"completed","toolCallId":"c1","toolName":"x","displayName":"Run in Terminal","invocationMessage":"Run it","toolInput":"{}","confirmed":"not-needed","success":true,"pastTenseMessage":"Ran it"}""",
                )),
            ),
        )
        rule.setContent { AhpTheme { ChatScreen(closed, null, false, null, ConnState.Online, {}, {}, {}) } }
        rule.waitForIdle()

        check()
    }

    @Test
    fun chatScreenWithFoldedAndOpenRunsOfStepsAndAComposerWithSteer() {
        val steps = Transcript(
            "Fix the build", ActivityState.IN_PROGRESS, null, hasOlder = false,
            items = listOf(
                TranscriptItem.User("u1", "Fix it"),
                TranscriptItem.Tool("t1", "Read file", "a.txt", ToolState.DONE),
                TranscriptItem.Tool("t2", "Run in Terminal", "dotnet build", ToolState.FAILED),
                TranscriptItem.Markdown("m1", "The build failed."),
                TranscriptItem.Reasoning("r1", "Look at the log.", live = false),
                TranscriptItem.Tool("t3", "Read file", "log.txt", ToolState.DONE),
                TranscriptItem.Notice("n1", "Context was compacted"),
                TranscriptItem.Tool("t4", "Edit", "b.txt", ToolState.RUNNING),
                TranscriptItem.Tool("t5", "Run in Terminal", "dotnet build", ToolState.RUNNING),
            ),
        )
        rule.setContent {
            AhpTheme { ChatScreen(steps, null, false, null, ConnState.Online, {}, {}, {}, expanded = setOf("steps:r1"), onToggle = {}, actions = chatActions(running = true)) }
        }
        rule.onNodeWithTag("composer_input").performTextInput("Also lint")
        rule.waitForIdle()

        check()
    }

    /** A check that can never fail proves nothing, so this feeds it a screen with a defect and expects it to object. */
    @Test
    fun theCheckerItselfCatchesADefect() {
        rule.setContent { Box(Modifier.size(16.dp).clickable { }) }

        val failed = runCatching { check() }.isFailure

        assertTrue("a 16 dp clickable with no label should fail the accessibility checks", failed)
    }

    /** One `setContent` per test is all Compose allows, so the screen is swapped through state and checked after each swap. */
    private fun checkEach(vararg screens: @Composable () -> Unit) {
        var current by mutableStateOf<@Composable () -> Unit>({})
        rule.setContent { AhpTheme { current() } }
        screens.forEach {
            current = it
            rule.waitForIdle()
            check()
        }
    }
}
