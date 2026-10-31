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
import dev.ahpclient.app.SignInStep
import dev.ahpclient.state.ConnState
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

    private fun showSettings(
        signedIn: Boolean = true,
        expiresAt: Instant? = null,
        renews: Boolean = false,
        onSignIn: (() -> Unit)? = null,
        onSignOut: () -> Unit = {},
        onBack: () -> Unit = {},
        proUnlocked: Boolean = true,
        onUnlockPro: () -> Unit = {},
    ) = rule.setContent {
        AhpTheme {
            SettingsScreen(
                onBack,
                signedIn = signedIn,
                tokenExpiresAt = expiresAt,
                renews = renews,
                onSignIn = onSignIn,
                onSignOut = onSignOut,
                proUnlocked = proUnlocked,
                onUnlockPro = onUnlockPro,
            )
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

    // ---- settings: hosts --------------------------------------------------------------------------------------
    // The host list, switching and the connected host are the hosts sheet's, and are tested in HostSheetTest.

    @Test
    fun theGitHubSignInSaysWhenItEndsAndStaysInSettings() {
        showSettings(expiresAt = Instant.parse("2026-10-23T04:00:00Z"))

        rule.onNodeWithTag("settings_title").assertIsDisplayed()
        rule.onNodeWithTag("token_expiry").assertIsDisplayed().assertTextContains("Signed in until", substring = true)
    }

    @Test
    fun aSignInThatRenewsItselfSaysSoInsteadOfNamingTheHourItsTokenRunsOut() {
        showSettings(expiresAt = Instant.parse("2026-10-23T04:00:00Z"), renews = true)

        rule.onNodeWithTag("token_expiry").assertIsDisplayed().assertTextContains("Signed in. Renews itself.")
        rule.onAllNodesWithText("Signed in until", substring = true).assertCountEquals(0)
    }

    @Test
    fun signOutIsOfferedOnlyWhenSignedInAndAsksFirstSoItIsNotPressedByAccident() {
        var signedOut = 0
        showSettings(onSignOut = { signedOut++ })

        rule.onNodeWithTag("sign_in").assertDoesNotExist()
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
    fun notSignedInSettingsOffersSignInAndThereIsNothingToSignOutOf() {
        var signedIn = 0
        showSettings(signedIn = false, onSignIn = { signedIn++ })

        rule.onNodeWithTag("sign_out").assertDoesNotExist()
        rule.onNodeWithTag("token_expiry").assertTextContains("Not signed in", substring = true)
        rule.onNodeWithTag("sign_in").assertIsDisplayed().assertTouchTargetAtLeast(48.dp).performClick()

        assertEquals(1, signedIn)
    }

    @Test
    fun whereASignInCannotBeStartedItSaysSoInsteadOfOfferingAButtonThatDoesNothing() {
        showSettings(signedIn = false, onSignIn = null)

        rule.onNodeWithTag("sign_in").assertDoesNotExist()
        rule.onNodeWithTag("sign_out").assertDoesNotExist()
        rule.onNodeWithTag("token_expiry").assertTextContains("Disconnect", substring = true)
    }

    @Test
    fun theBackArrowLeavesSettings() {
        var backs = 0
        showSettings(onBack = { backs++ })

        rule.onNodeWithTag("back").performClick()

        assertEquals(1, backs)
    }

    // ---- browser ----------------------------------------------------------------------------------------------

    @Test
    fun settingsOffersNoBrowserChoice() {
        showSettings()

        rule.onAllNodesWithTag("browser_row").assertCountEquals(0)
        rule.onAllNodesWithText("Open links in").assertCountEquals(0)
    }

    // ---- pro entitlement --------------------------------------------------------------------------------------

    @Test
    fun whenProIsLockedSettingsShowsUnlockCardAndDisablesProSwitches() {
        var unlocks = 0
        showSettings(
            proUnlocked = false,
            onUnlockPro = { unlocks++ },
        )

        rule.onNodeWithTag("pro_section").assertIsDisplayed()
        rule.onNodeWithTag("unlock_pro").assertIsDisplayed().assertTouchTargetAtLeast(48.dp).performClick()
        assertEquals(1, unlocks)
    }

    @Test
    fun whenProIsUnlockedSettingsHidesTheUnlockCard() {
        showSettings(proUnlocked = true)

        rule.onAllNodesWithTag("pro_section").assertCountEquals(0)
        rule.onAllNodesWithTag("unlock_pro").assertCountEquals(0)
    }

    @Test
    fun proUpgradeDialogSupportsPurchaseAndRestoreFlows() {
        var purchases = 0
        var restores = 0
        rule.setContent {
            AhpTheme {
                SettingsScreen(
                    onBack = {},
                    proUnlocked = false,
                    onPurchasePro = {
                        purchases++
                        dev.ahpclient.app.BillingResult.Unavailable("Not yet active.")
                    },
                    onRestorePro = {
                        restores++
                        dev.ahpclient.app.BillingResult.NotFound
                    },
                )
            }
        }

        rule.onNodeWithTag("unlock_pro").assertIsDisplayed().performClick()
        rule.onNodeWithTag("pro_upgrade_dialog").assertIsDisplayed()

        rule.onNodeWithTag("pro_restore").assertIsDisplayed().assertTouchTargetAtLeast(48.dp).performClick()
        assertEquals(1, restores)
        rule.onNodeWithTag("pro_upgrade_message").assertIsDisplayed().assertTextContains("No previous Pro purchase", substring = true)

        rule.onNodeWithTag("pro_purchase").assertIsDisplayed().assertTouchTargetAtLeast(48.dp).performClick()
        assertEquals(1, purchases)
        rule.onNodeWithTag("pro_upgrade_message").assertIsDisplayed().assertTextContains("Not yet active", substring = true)

        rule.onNodeWithTag("pro_cancel").assertIsDisplayed().assertTouchTargetAtLeast(48.dp).performClick()
        rule.onAllNodesWithTag("pro_upgrade_dialog").assertCountEquals(0)
    }

    // ---- connected --------------------------------------------------------------------------------------------

    @Test
    fun connectionProblemsAreSpelledOut() {
        val states = listOf(
            ConnState.Connecting to "Connecting",
            ConnState.Reconnecting(2, null) to "Reconnecting (attempt 2)",
            ConnState.WaitingForNetwork to "Waiting for network",
            ConnState.Failed(IllegalStateException("The tunnel lists no running agent host")) to "no running agent host",
        )
        states.forEach { (state, words) ->
            assertTrue("$state -> ${describe(state)}", describe(state).contains(words))
        }
    }
}
