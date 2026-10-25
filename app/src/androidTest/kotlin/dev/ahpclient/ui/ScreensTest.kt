package dev.ahpclient.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ahpclient.app.AppState
import dev.ahpclient.app.HostEntry
import dev.ahpclient.app.HostList
import dev.ahpclient.app.SignInStep
import dev.ahpclient.state.ConnState
import dev.ahpclient.tunnel.TunnelInfo
import dev.ahpclient.ui.theme.AhpTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Screens are pure functions of state, so these tests check what is shown and what each control does. */
@RunWith(AndroidJUnit4::class)
class ScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val expires = Instant.parse("2026-10-22T12:15:00Z")
    private val online = TunnelInfo("t-online", "usw2", "dev-box", 6, 1, listOf(31546))
    private val offline = TunnelInfo("t-offline", "usw2", "old-laptop", 5, 0, listOf(31546))

    private fun signedOut(step: SignInStep, notice: String? = null) = AppState.SignedOut(step, notice)

    // Material 3 buttons draw 40 dp tall but extend their touch target to 48 dp, so the guideline is checked on touch bounds.
    private fun SemanticsNodeInteraction.assertTouchTargetAtLeast(minimum: Dp): SemanticsNodeInteraction {
        val bounds = fetchSemanticsNode().touchBoundsInRoot
        val minimumPx = with(rule.density) { minimum.toPx() }
        assertTrue("touch target is ${bounds.width} x ${bounds.height} px, smaller than $minimum ($minimumPx px)", bounds.height + 0.5f >= minimumPx && bounds.width + 0.5f >= minimumPx)
        return this
    }

    private fun showSignIn(
        state: AppState.SignedOut,
        onSignIn: () -> Unit = {},
        onCancel: () -> Unit = {},
        onOpen: (String) -> Unit = {},
        onCopy: (String) -> Unit = {},
    ) = rule.setContent { AhpTheme { SignInScreen(state, onSignIn, onCancel, onOpen, onCopy) } }

    private val browsers = listOf(BrowserOption("com.android.chrome", "Chrome"), BrowserOption("org.mozilla.firefox", "Firefox"))

    private fun showHosts(
        list: HostList?,
        expiresAt: Instant? = null,
        onRefresh: () -> Unit = {},
        onConnect: (TunnelInfo) -> Unit = {},
        onSignOut: () -> Unit = {},
        renews: Boolean = false,
        connected: TunnelInfo? = null,
        connection: ConnState? = null,
        onDisconnect: () -> Unit = {},
        browser: String? = null,
        onBrowser: (String?) -> Unit = {},
        onBack: () -> Unit = {},
    ) = rule.setContent {
        AhpTheme {
            HostScreen(list, connected, connection, onBack, onRefresh, onConnect, onDisconnect, onSignOut, browsers, browser, onBrowser, tokenExpiresAt = expiresAt, renews = renews)
        }
    }

    // ---- sign in ----------------------------------------------------------------------------------------------

    @Test
    fun idleOffersSignInAndTheButtonWorks() {
        var taps = 0
        showSignIn(signedOut(SignInStep.Idle), onSignIn = { taps++ })

        rule.onNodeWithTag("sign_in_button").assertIsDisplayed().assertHasClickAction().assertTouchTargetAtLeast(48.dp).performClick()

        assertEquals(1, taps)
        rule.onNodeWithText("Use the GitHub account", substring = true).assertIsDisplayed()
    }

    @Test
    fun afterSigningOutTheUserIsOfferedAWayToRemoveTheGrantOnGitHub() {
        val opened = mutableListOf<String>()
        showSignIn(AppState.SignedOut(SignInStep.Idle, null, offerRevoke = true), onOpen = { opened += it })

        rule.onNodeWithTag("revoke_notice").assertIsDisplayed()
        rule.onNodeWithTag("revoke_access").assertIsDisplayed().assertTouchTargetAtLeast(48.dp).performClick()

        assertEquals(listOf("https://github.com/settings/applications"), opened)
        rule.onNodeWithTag("sign_in_button").assertIsDisplayed()
    }

    @Test
    fun theRevokeOfferIsAbsentUnlessTheUserSignedOut() {
        showSignIn(signedOut(SignInStep.Idle, notice = "Your GitHub sign-in expired. Sign in again."))

        rule.onAllNodesWithTag("revoke_access").assertCountEquals(0)
        rule.onAllNodesWithTag("revoke_notice").assertCountEquals(0)
    }

    @Test
    fun aNoticeExplainsWhyTheUserIsBackHere() {
        showSignIn(signedOut(SignInStep.Idle, notice = "GitHub no longer accepts this sign-in. Sign in again."))

        rule.onNodeWithTag("notice").assertIsDisplayed().assertTextContains("no longer accepts", substring = true)
    }

    @Test
    fun theCodeIsShownBigAndReadAloudOneCharacterAtATime() {
        showSignIn(signedOut(SignInStep.AwaitingApproval("ABCD-1234", "https://github.com/login/device", expires)))

        rule.onNodeWithTag("user_code").assertIsDisplayed().assertTextContains("ABCD-1234").assertContentDescriptionEquals("Code A B C D dash 1 2 3 4")
        rule.onNodeWithTag("waiting").assertTextContains("Waiting for approval", substring = true)
    }

    @Test
    fun copyOpenAndCancelAllWork() {
        var copied: String? = null
        var opened: String? = null
        var cancelled = 0
        showSignIn(
            signedOut(SignInStep.AwaitingApproval("ABCD-1234", "https://github.com/login/device", expires)),
            onCancel = { cancelled++ },
            onOpen = { opened = it },
            onCopy = { copied = it },
        )

        rule.onNodeWithTag("copy_code").assertTouchTargetAtLeast(48.dp).performClick()
        rule.onNodeWithTag("open_github").assertTouchTargetAtLeast(48.dp).performClick()
        rule.onNodeWithTag("cancel_sign_in").assertTouchTargetAtLeast(48.dp).performClick()

        assertEquals("ABCD-1234", copied)
        assertEquals("https://github.com/login/device", opened)
        assertEquals(1, cancelled)
    }

    @Test
    fun anAddressThatIsNotGitHubIsNeverOfferedAsAButton() {
        showSignIn(signedOut(SignInStep.AwaitingApproval("ABCD-1234", "https://github.com.evil.example/login/device", expires)))

        rule.onNodeWithTag("open_github").assertDoesNotExist()
        rule.onNodeWithText("https://github.com.evil.example/login/device", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("copy_code").assertIsDisplayed()
    }

    @Test
    fun aFailureShowsTheReasonAndAllowsTryingAgain() {
        var taps = 0
        showSignIn(signedOut(SignInStep.Failed("The code expired before it was approved. Start again.")), onSignIn = { taps++ })

        rule.onNodeWithTag("sign_in_error").assertIsDisplayed().assertTextContains("expired", substring = true)
        rule.onNodeWithText("Try again").performClick()

        assertEquals(1, taps)
    }

    @Test
    fun requestingACodeShowsProgressWithAWordLabel() {
        showSignIn(signedOut(SignInStep.RequestingCode))

        rule.onNodeWithText("Asking GitHub for a code", substring = true).assertIsDisplayed()
    }

    // ---- hosts ------------------------------------------------------------------------------------------------

    @Test
    fun hostsShowNameStatusProtocolAndLastUsedInWords() {
        showHosts(HostList.Loaded(listOf(HostEntry(online, isLastUsed = true), HostEntry(offline, isLastUsed = false))))

        rule.onNodeWithText("dev-box").assertIsDisplayed()
        rule.onNodeWithText("Online · v6 · last used").assertIsDisplayed()
        rule.onNodeWithText("old-laptop").assertIsDisplayed()
        rule.onNodeWithText("Offline · v5").assertIsDisplayed()
        rule.onNodeWithText("VS Code is not connected", substring = true).assertIsDisplayed()
    }

    @Test
    fun tappingAHostConnectsToThatHost() {
        var connected: TunnelInfo? = null
        showHosts(HostList.Loaded(listOf(HostEntry(online, false), HostEntry(offline, false))), onConnect = { connected = it })

        rule.onNodeWithTag("host_t-offline").assertHasClickAction().assertTouchTargetAtLeast(56.dp).performClick()

        assertEquals(offline, connected)
    }

    @Test
    fun theFooterStaysVisibleBelowTheList() {
        showHosts(HostList.Loaded(listOf(HostEntry(online, false), HostEntry(offline, false))), expiresAt = Instant.parse("2026-10-23T04:00:00Z"))

        rule.onNodeWithTag("token_expiry").assertIsDisplayed().assertTextContains("Signed in until", substring = true)
    }

    @Test
    fun aSignInThatRenewsItselfSaysSoInsteadOfNamingTheHourItsTokenRunsOut() {
        showHosts(HostList.Loaded(listOf(HostEntry(online, false))), expiresAt = Instant.parse("2026-10-23T04:00:00Z"), renews = true)

        rule.onNodeWithTag("token_expiry").assertIsDisplayed().assertTextContains("Renews itself", substring = true)
        rule.onAllNodesWithText("Signed in until", substring = true).assertCountEquals(0)
    }

    @Test
    fun anEmptyListExplainsHowToTurnOnRemoteTunnelAccess() {
        var refreshed = 0
        showHosts(HostList.Empty, onRefresh = { refreshed++ })

        rule.onNodeWithTag("hosts_empty").assertIsDisplayed()
        rule.onNodeWithText("blue A", substring = true).assertIsDisplayed()
        rule.onAllNodesWithText("Refresh").assertCountEquals(1) // the one in the explanation; the bar's is an icon with a description
        rule.onNodeWithTag("refresh_hosts").assertContentDescriptionEquals("Refresh").performClick()

        assertEquals(1, refreshed)
    }

    @Test
    fun aFailureShowsTheReasonAndRetries() {
        var refreshed = 0
        showHosts(HostList.Failed("Could not reach the Dev Tunnels service."), onRefresh = { refreshed++ })

        rule.onNodeWithTag("hosts_error").assertIsDisplayed().assertTextContains("Could not reach", substring = true)
        rule.onNodeWithText("Try again").performClick()

        assertEquals(1, refreshed)
    }

    @Test
    fun loadingShowsAWordLabel() {
        showHosts(HostList.Loading)

        rule.onNodeWithTag("hosts_loading").assertIsDisplayed()
        rule.onNodeWithText("Looking for hosts", substring = true).assertIsDisplayed()
    }

    @Test
    fun signOutIsAlwaysAvailableAndAsksFirstSoItIsNotPressedByAccident() {
        var signedOut = 0
        showHosts(HostList.Loading, onSignOut = { signedOut++ })

        rule.onNodeWithTag("sign_out").assertIsDisplayed().assertTouchTargetAtLeast(48.dp).performClick()
        assertEquals(0, signedOut)
        rule.onNodeWithTag("sign_out_cancel").performClick()
        rule.onNodeWithTag("sign_out_dialog").assertDoesNotExist()
        assertEquals(0, signedOut)

        rule.onNodeWithTag("sign_out").performClick()
        rule.onNodeWithTag("sign_out_confirm").assertTouchTargetAtLeast(48.dp).performClick()
        assertEquals(1, signedOut)
    }

    @Test
    fun whileConnectedTheHostIsShownWithItsStatusAndAWayToSwitch() {
        var switched = 0
        showHosts(null, connected = online, connection = ConnState.Online, onDisconnect = { switched++ })

        rule.onNodeWithTag("connected_host").assertIsDisplayed()
        rule.onNodeWithText("dev-box").assertIsDisplayed()
        rule.onNodeWithTag("disconnect").assertTouchTargetAtLeast(48.dp).performClick()
        assertEquals(1, switched)
        rule.onAllNodesWithTag("refresh_hosts").assertCountEquals(0)
        rule.onAllNodesWithTag("host_t-online").assertCountEquals(0)
    }

    @Test
    fun theBackArrowLeavesTheHostScreen() {
        var backs = 0
        showHosts(HostList.Loading, onBack = { backs++ })

        rule.onNodeWithTag("back").performClick()

        assertEquals(1, backs)
    }

    // ---- browser ----------------------------------------------------------------------------------------------

    @Test
    fun theBrowserRowNamesTheSystemDefaultUntilOneIsChosen() {
        showHosts(HostList.Loading)

        rule.onNodeWithTag("browser_label", useUnmergedTree = true).assertTextContains("System default")
    }

    @Test
    fun theBrowserRowNamesTheChosenBrowser() {
        showHosts(HostList.Loading, browser = "org.mozilla.firefox")

        rule.onNodeWithTag("browser_label", useUnmergedTree = true).assertTextContains("Firefox")
    }

    @Test
    fun aChosenBrowserThatIsNoLongerInstalledFallsBackToTheSystemDefaultInTheRow() {
        showHosts(HostList.Loading, browser = "org.removed.browser")

        rule.onNodeWithTag("browser_label", useUnmergedTree = true).assertTextContains("System default")
    }

    @Test
    fun thePickerOffersTheSystemDefaultAndEachInstalledBrowserAndReportsThePick() {
        val picks = mutableListOf<String?>()
        showHosts(HostList.Loading, onBrowser = { picks += it })

        rule.onNodeWithTag("browser_row").performClick()
        rule.onNodeWithTag("browser_dialog").assertIsDisplayed()
        rule.onNodeWithTag("browser_default").assertIsDisplayed()
        rule.onAllNodesWithTag("browser_option").assertCountEquals(2)
        rule.onNodeWithText("Firefox").assertTouchTargetAtLeast(48.dp).performClick()

        assertEquals(listOf<String?>("org.mozilla.firefox"), picks)
        rule.onNodeWithTag("browser_dialog").assertDoesNotExist()
    }

    @Test
    fun choosingTheSystemDefaultReportsNull() {
        val picks = mutableListOf<String?>("sentinel")
        showHosts(HostList.Loading, browser = "com.android.chrome", onBrowser = { picks += it })

        rule.onNodeWithTag("browser_row").performClick()
        rule.onNodeWithTag("browser_default").performClick()

        assertEquals(listOf<String?>("sentinel", null), picks)
    }

    @Test
    fun cancellingThePickerChangesNothing() {
        val picks = mutableListOf<String?>()
        showHosts(HostList.Loading, onBrowser = { picks += it })

        rule.onNodeWithTag("browser_row").performClick()
        rule.onNodeWithTag("browser_cancel").performClick()

        assertTrue(picks.isEmpty())
        rule.onNodeWithTag("browser_dialog").assertDoesNotExist()
    }

    // ---- connected --------------------------------------------------------------------------------------------

    @Test
    fun connectionProblemsAreSpelledOut() {
        val states = listOf(
            ConnState.Connecting to "Connecting",
            ConnState.Reconnecting(2, null) to "Reconnecting (attempt 2)",
            ConnState.Failed(IllegalStateException("The tunnel lists no running agent host")) to "no running agent host",
        )
        states.forEach { (state, words) ->
            assertTrue("$state -> ${describe(state)}", describe(state).contains(words))
        }
    }
}
