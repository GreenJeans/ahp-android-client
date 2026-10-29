package dev.ahpclient.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ahpclient.app.AppState
import dev.ahpclient.app.DirectEntry
import dev.ahpclient.app.SignInStep
import dev.ahpclient.direct.DirectHost
import dev.ahpclient.ui.theme.AhpTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings' hosts added by address: listed with their address, added with a token, removed after asking, and usable with no sign-in.
 * Choosing one of them to connect to is the hosts sheet's (HostSheetTest).
 */
@RunWith(AndroidJUnit4::class)
class DirectHostsUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val laptop = DirectHost("direct:11111111-aaaa", "Laptop", "ws://192.168.1.20:9187")
    private val server = DirectHost("direct:22222222-bbbb", "Server", "wss://agents.example.com")

    private fun show(
        direct: List<DirectEntry> = listOf(DirectEntry(laptop, isLastUsed = true), DirectEntry(server, isLastUsed = false)),
        onAddHost: (suspend (String, String, String) -> String?)? = { _, _, _ -> null },
        onRemoveHost: (DirectHost) -> Unit = {},
        signedIn: Boolean = false,
        onSignIn: (() -> Unit)? = {},
        onSignOut: () -> Unit = {},
        connectedId: String? = null,
    ) = rule.setContent {
        AhpTheme {
            SettingsScreen(
                onBack = {}, signedIn = signedIn, onSignIn = onSignIn, onSignOut = onSignOut,
                direct = direct, onAddHost = onAddHost, onRemoveHost = onRemoveHost, connectedId = connectedId,
            )
        }
    }

    @Test
    fun addedHostsAreListedWithTheirAddressAndWhetherItIsEncrypted() {
        show()

        rule.onNodeWithText("Laptop").assertIsDisplayed()
        rule.onNodeWithText("192.168.1.20:9187 · not encrypted · last used").assertIsDisplayed()
        rule.onNodeWithText("Server").assertIsDisplayed()
        rule.onNodeWithText("agents.example.com").assertIsDisplayed()
    }

    @Test
    fun anAddedHostIsManagedHereNotConnectedToSoOnlyItsRemoveButtonActs() {
        show()

        rule.onNodeWithTag("direct_${server.id}").assertHasNoClickAction()
        rule.onNodeWithTag("remove_${server.id}").assertHasClickAction()
    }

    @Test
    fun withNoHostsAddedSettingsSaysSoAndStillOffersToAddOne() {
        show(direct = emptyList())

        rule.onNodeWithTag("no_direct_hosts").assertIsDisplayed()
        rule.onNodeWithTag("add_host").assertIsDisplayed()
    }

    @Test
    fun withoutAWayToAddThereIsNoAddButton() {
        show(onAddHost = null)

        rule.onNodeWithTag("add_host").assertDoesNotExist()
    }

    @Test
    fun removingAsksFirstAndNamesWhatIsForgotten() {
        val removed = mutableListOf<DirectHost>()
        show(onRemoveHost = { removed += it })

        rule.onNodeWithTag("remove_${laptop.id}").performClick()
        rule.onNodeWithTag("remove_host_dialog").assertIsDisplayed()
        rule.onNodeWithText("its token", substring = true).assertIsDisplayed()
        assertTrue(removed.isEmpty())

        rule.onNodeWithTag("remove_host_cancel").performClick()
        rule.onNodeWithTag("remove_host_dialog").assertDoesNotExist()
        assertTrue(removed.isEmpty())

        rule.onNodeWithTag("remove_${laptop.id}").performClick()
        rule.onNodeWithTag("remove_host_confirm").performClick()
        assertEquals(listOf(laptop), removed)
    }

    @Test
    fun addingTakesAnAddressNameAndTokenAndClosesWhenItWorked() {
        val added = mutableListOf<Triple<String, String, String>>()
        show(onAddHost = { name, address, token -> added += Triple(name, address, token); null })

        rule.onNodeWithTag("add_host").performClick()
        rule.onNodeWithTag("add_host_confirm").assertIsNotEnabled()
        rule.onNodeWithTag("add_host_address").performTextInput("192.168.1.30:9187")
        rule.onNodeWithTag("add_host_name").performTextInput("Desk")
        rule.onNodeWithTag("add_host_token").performTextInput("s3cret")
        rule.onNodeWithTag("add_host_confirm").performClick()
        rule.waitForIdle()

        assertEquals(listOf(Triple("Desk", "192.168.1.30:9187", "s3cret")), added)
        rule.onNodeWithTag("add_host_dialog").assertDoesNotExist()
    }

    @Test
    fun theTokenIsNotShownWhileItIsTyped() {
        show()

        rule.onNodeWithTag("add_host").performClick()
        rule.onNodeWithTag("add_host_token").performTextInput("s3cret")

        val field = rule.onNodeWithTag("add_host_token")
        field.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        val shown = field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertFalse("the field shows what was typed: $shown", shown.contains("s3cret"))
    }

    @Test
    fun aRefusedAddressStaysOpenWithTheReason() {
        show(onAddHost = { _, _, _ -> "ws:// is not encrypted, so it is only allowed to this phone or a private network address." })

        rule.onNodeWithTag("add_host").performClick()
        rule.onNodeWithTag("add_host_address").performTextInput("8.8.8.8")
        rule.onNodeWithTag("add_host_confirm").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("add_host_dialog").assertIsDisplayed()
        rule.onNodeWithTag("add_host_error").assertIsDisplayed()
    }

    @Test
    fun withoutASignInSettingsOffersOneAndTheAddedHostsAreStillThere() {
        var signedIn = 0
        show(onSignIn = { signedIn++ })

        rule.onNodeWithTag("sign_out").assertDoesNotExist()
        rule.onNodeWithTag("sign_in").performClick()
        assertEquals(1, signedIn)
        rule.onNodeWithText("Laptop").assertIsDisplayed()
    }

    @Test
    fun signedInTheAddedHostsAreListedBesideTheSignOut() {
        show(signedIn = true)

        rule.onNodeWithText("Hosts you added").assertIsDisplayed()
        rule.onNodeWithText("Laptop").assertIsDisplayed()
        rule.onNodeWithTag("sign_out").assertExists()
        rule.onNodeWithTag("sign_in").assertDoesNotExist()
    }

    @Test
    fun theSignOutQuestionNamesOnlyTheGitHubSignIn() {
        var signedOut = 0
        show(signedIn = true, onSignOut = { signedOut++ })

        rule.onNodeWithTag("sign_out").performClick()
        rule.onNodeWithTag("sign_out_dialog").assertIsDisplayed()
        rule.onNodeWithText("GitHub sign-in", substring = true).assertIsDisplayed()
        rule.onNodeWithText("token", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("sign_out_confirm").performClick()

        assertEquals(1, signedOut)
    }

    @Test
    fun theSignOutQuestionSaysThatTheConnectedHostIsLeftToo() {
        show(signedIn = true, connectedId = laptop.id)

        rule.onNodeWithTag("sign_out").performClick()

        rule.onNodeWithText("connected now", substring = true).assertIsDisplayed()
        rule.onNodeWithText("sign-in screen", substring = true).assertIsDisplayed()
    }

    @Test
    fun removingTheConnectedHostSaysItsConnectionEndsAndRemovingAnotherDoesNot() {
        show(connectedId = laptop.id)

        rule.onNodeWithText("192.168.1.20:9187 · not encrypted · connected · last used").assertIsDisplayed()
        rule.onNodeWithTag("remove_${laptop.id}").performClick()
        rule.onNodeWithText("connection ends too", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("remove_host_cancel").performClick()

        rule.onNodeWithTag("remove_${server.id}").performClick()
        rule.onNodeWithTag("remove_host_dialog").assertIsDisplayed()
        rule.onNodeWithText("connection ends too", substring = true).assertDoesNotExist()
    }

    @Test
    fun theSignInScreenOffersAHostByAddressAndSkipsTheSignIn() {
        var used = 0
        rule.setContent { AhpTheme { SignInScreen(AppState.SignedOut(SignInStep.Idle, null), {}, {}, {}, {}, onUseDirect = { used++ }) } }

        rule.onNodeWithTag("use_direct_hosts").assertHasClickAction().performClick()

        assertEquals(1, used)
    }

    @Test
    fun theSignInScreenHasNoSuchButtonWhenNotOffered() {
        rule.setContent { AhpTheme { SignInScreen(AppState.SignedOut(SignInStep.Idle, null), {}, {}, {}, {}) } }

        rule.onNodeWithTag("use_direct_hosts").assertDoesNotExist()
    }
}
