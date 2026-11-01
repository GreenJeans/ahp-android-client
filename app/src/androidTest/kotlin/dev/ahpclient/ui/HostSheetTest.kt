package dev.ahpclient.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ahpclient.app.DirectEntry
import dev.ahpclient.app.HostCatalog
import dev.ahpclient.app.HostEntry
import dev.ahpclient.app.HostList
import dev.ahpclient.app.HostRef
import dev.ahpclient.direct.DirectHost
import dev.ahpclient.state.ConnState
import dev.ahpclient.tunnel.TunnelInfo
import dev.ahpclient.ui.theme.AhpTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The host sheet: what it lists and in which order, what a tap does, adding a host, and what it says with no sign-in or a failed list. */
@RunWith(AndroidJUnit4::class)
class HostSheetTest {
    @get:Rule
    val rule = createComposeRule()

    private val devBox = TunnelInfo("t-dev", "usw2", "dev-box", 6, 1, listOf(31546))
    private val buildBox = TunnelInfo("t-build", "usw2", "build-box", 6, 1, listOf(31546))
    private val oldLaptop = TunnelInfo("t-off", "usw2", "old-laptop", 5, 0, listOf(31546))
    private val laptop = DirectHost("direct:11111111-aaaa", "Laptop", "ws://192.168.1.20:9187")
    private val server = DirectHost("direct:22222222-bbbb", "Server", "wss://agents.example.com")

    /** The offline host comes first and the online ones last, so that the order on screen is the sheet's own. */
    private val everything = HostCatalog(
        list = HostList.Loaded(listOf(HostEntry(oldLaptop, isLastUsed = false), HostEntry(devBox, isLastUsed = false), HostEntry(buildBox, isLastUsed = true))),
        direct = listOf(DirectEntry(laptop, isLastUsed = false), DirectEntry(server, isLastUsed = false)),
    )

    private var catalog by mutableStateOf(everything)

    // Material 3 buttons draw smaller than they touch, so the guideline is checked on touch bounds.
    private fun SemanticsNodeInteraction.assertTouchTargetAtLeast(minimum: Dp): SemanticsNodeInteraction {
        val bounds = fetchSemanticsNode().touchBoundsInRoot
        val minimumPx = with(rule.density) { minimum.toPx() }
        assertTrue("touch target is ${bounds.width} x ${bounds.height} px, smaller than $minimum ($minimumPx px)", bounds.height + 0.5f >= minimumPx && bounds.width + 0.5f >= minimumPx)
        return this
    }

    /** A tap that ends the sheet lets it slide away first, so it is waited for; and it reports once, not once for the tap and again for the slide. */
    private fun awaitDismissed(dismissed: () -> Int) {
        rule.waitUntil(5_000) { dismissed() >= 1 }
        rule.waitForIdle()
        assertEquals(1, dismissed())
    }

    private fun top(tag: String) =rule.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y

    private fun show(
        connected: HostRef? = null,
        connection: ConnState? = null,
        onDismiss: () -> Unit = {},
        onRefresh: () -> Unit = {},
        onSwitch: (HostRef) -> Unit = {},
        onDisconnect: () -> Unit = {},
        onManageHosts: () -> Unit = {},
        onAddHost: (suspend (String, String, String) -> String?)? = { _, _, _ -> null },
        onSignIn: (() -> Unit)? = {},
    ) = rule.setContent {
        AhpTheme {
            HostSheet(catalog, connected, connection, onDismiss, onRefresh, onSwitch, onDisconnect, onManageHosts, onAddHost = onAddHost, onSignIn = onSignIn)
        }
    }

    @Test
    fun theSheetIsNamedAndRefreshesTheHostsWhenItOpens() {
        var refreshed = 0
        show(onRefresh = { refreshed++ })

        rule.onNodeWithTag("host_sheet").assertIsDisplayed()
        rule.onNodeWithText("Hosts").assertIsDisplayed()
        rule.waitForIdle()
        assertEquals(1, refreshed)
    }

    @Test
    fun theRefreshIconInTheHeaderRefreshesAgain() {
        var refreshed = 0
        show(onRefresh = { refreshed++ })

        rule.onNodeWithTag("refresh_hosts").assertTouchTargetAtLeast(48.dp).performClick()

        assertEquals(2, refreshed)
    }

    @Test
    fun everyOtherHostIsListedAsOneListWithTunnelHostsOnlineThenAddedHostsThenOfflineOnes() {
        show()

        listOf("host_t-dev", "host_t-build", "direct_${laptop.id}", "direct_${server.id}", "host_t-off").forEach { rule.onNodeWithTag(it).assertIsDisplayed() }
        val order = listOf("host_t-dev", "host_t-build", "direct_${laptop.id}", "direct_${server.id}", "host_t-off").map(::top)
        assertEquals("tunnel online, direct, tunnel offline, each group in the catalog's order: $order", order.sorted(), order)
    }

    @Test
    fun theMarkersSayLastUsedOfflineAndNotEncrypted() {
        catalog = everything.copy(direct = listOf(DirectEntry(laptop, isLastUsed = true), DirectEntry(server, isLastUsed = false)))
        show()

        rule.onNodeWithText("Online · v6 · last used").assertIsDisplayed()
        rule.onNodeWithText("Online · v6").assertIsDisplayed()
        rule.onNodeWithText("Offline · v5").assertIsDisplayed()
        rule.onNodeWithText("192.168.1.20:9187 · not encrypted · last used").assertIsDisplayed()
        rule.onNodeWithText("agents.example.com").assertIsDisplayed()
    }

    @Test
    fun nothingIsConnectedSoThereIsNoConnectedCardAndNoOtherHostsHeading() {
        show()

        rule.onAllNodesWithTag("connected_host").assertCountEquals(0)
        rule.onAllNodesWithTag("disconnect").assertCountEquals(0)
        rule.onNodeWithText("Other hosts").assertDoesNotExist()
    }

    @Test
    fun theConnectedTunnelHostIsShownOnceAboveTheOthersAndIsNotAmongThem() {
        show(connected = HostRef.Tunnel(devBox), connection = ConnState.Online)

        rule.onNodeWithTag("connected_host").assertIsDisplayed()
        rule.onNodeWithTag("connection_status", useUnmergedTree = true).assertTextContains("Connected", substring = true)
        rule.onAllNodesWithTag("host_t-dev").assertCountEquals(0)
        rule.onAllNodesWithText("dev-box").assertCountEquals(1)
        rule.onNodeWithText("Other hosts").assertIsDisplayed()
        listOf("host_t-build", "direct_${laptop.id}", "direct_${server.id}", "host_t-off").forEach { rule.onNodeWithTag(it).assertIsDisplayed() }
        assertTrue(top("connected_host") < top("host_t-build"))
    }

    @Test
    fun theConnectedDirectHostIsNotAmongTheOthersEither() {
        show(connected = HostRef.Direct(laptop), connection = ConnState.Online)

        rule.onNodeWithTag("connected_host").assertIsDisplayed()
        rule.onAllNodesWithTag("direct_${laptop.id}").assertCountEquals(0)
        rule.onAllNodesWithText("Laptop").assertCountEquals(1)
        rule.onNodeWithTag("direct_${server.id}").assertIsDisplayed()
        rule.onNodeWithTag("host_t-dev").assertIsDisplayed()
    }

    @Test
    fun theConnectedHostsConnectionIsSaidInWords() {
        show(connected = HostRef.Tunnel(devBox), connection = ConnState.Reconnecting(2, null))

        rule.onNodeWithTag("connection_status", useUnmergedTree = true).assertTextContains("Reconnecting", substring = true)
    }

    @Test
    fun aConnectionThatIsNotUpIsAnnouncedWhenItChangesAndAnOnlineOneIsNot() {
        var connection by mutableStateOf<ConnState>(ConnState.Reconnecting(1, null))
        rule.setContent { AhpTheme { HostSheet(catalog, HostRef.Tunnel(devBox), connection, {}, {}, {}, {}, {}) } }

        rule.onNodeWithTag("connection_status", useUnmergedTree = true).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))

        connection = ConnState.Online
        rule.waitForIdle()

        rule.onNodeWithTag("connection_status", useUnmergedTree = true).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun withTheOnlyHostConnectedAndNoneAddedThereIsNoEmptyOtherHostsHeadingButALineSaysSo() {
        catalog = HostCatalog(HostList.Loaded(listOf(HostEntry(devBox, isLastUsed = true))))
        show(connected = HostRef.Tunnel(devBox), connection = ConnState.Online)

        rule.onNodeWithTag("connected_host").assertIsDisplayed()
        rule.onAllNodesWithText("Other hosts").assertCountEquals(0)
        rule.onNodeWithTag("no_other_hosts").assertIsDisplayed().assertTextEquals("No other hosts.")
        rule.onNodeWithTag("add_host").assertExists()
    }

    @Test
    fun theNoOtherHostsLineIsNotThereWithHostsListedOrWithNothingConnected() {
        show(connected = HostRef.Tunnel(devBox), connection = ConnState.Online)
        rule.onAllNodesWithTag("no_other_hosts").assertCountEquals(0)
    }

    @Test
    fun nothingConnectedAndNothingListedHasNoNoOtherHostsLine() {
        catalog = HostCatalog(HostList.Loaded(emptyList()))
        show()
        rule.onAllNodesWithTag("no_other_hosts").assertCountEquals(0)
    }

    @Test
    fun connectedToADirectHostWithNoSignInTheSheetSaysWhyNoTunnelHostsAreListed() {
        catalog = HostCatalog(HostList.NotSignedIn, direct = listOf(DirectEntry(laptop, isLastUsed = true)))
        show(connected = HostRef.Direct(laptop), connection = ConnState.Online, onSignIn = null)

        rule.onNodeWithText("Other hosts").assertIsDisplayed()
        rule.onNodeWithTag("sign_in_hint").assertIsDisplayed().assertTextContains("sign in from Settings", substring = true)
    }

    @Test
    fun connectedToADirectHostWithSignInOfferedShowsSignInRowWithoutHintOrNoOtherHostsLine() {
        var signedIn = 0
        catalog = HostCatalog(HostList.NotSignedIn, direct = listOf(DirectEntry(laptop, isLastUsed = true)))
        show(connected = HostRef.Direct(laptop), connection = ConnState.Online, onSignIn = { signedIn++ })

        rule.onAllNodesWithTag("sign_in_hint").assertCountEquals(0)
        rule.onAllNodesWithTag("no_other_hosts").assertCountEquals(0)
        rule.onNodeWithTag("sign_in_from_hosts").performScrollTo().assertTouchTargetAtLeast(48.dp).performClick()
        assertEquals(1, signedIn)
    }

    @Test
    fun connectedToADirectHostWithoutSignInKeepsSignInHidden() {
        catalog = HostCatalog(HostList.NotSignedIn, direct = listOf(DirectEntry(laptop, isLastUsed = true)))
        show(connected = HostRef.Direct(laptop), connection = ConnState.Online, onSignIn = null)
        rule.onAllNodesWithTag("sign_in_from_hosts").assertCountEquals(0)
    }

    @Test
    fun nothingConnectedAndNotSignedInHasTheSignInRowAndNoHint() {
        catalog = HostCatalog(HostList.NotSignedIn, direct = everything.direct)
        show()

        rule.onAllNodesWithTag("sign_in_hint").assertCountEquals(0)
        rule.onNodeWithTag("sign_in_from_hosts").assertExists()
    }

    @Test
    fun tappingATunnelHostSwitchesToItAndClosesTheSheet() {
        val switched = mutableListOf<HostRef>()
        var dismissed = 0
        show(connected = HostRef.Direct(laptop), connection = ConnState.Online, onSwitch = { switched += it }, onDismiss = { dismissed++ })

        rule.onNodeWithTag("host_t-build").performScrollTo().performClick()

        assertEquals(listOf<HostRef>(HostRef.Tunnel(buildBox)), switched)
        awaitDismissed { dismissed }
    }

    @Test
    fun tappingAnAddedHostSwitchesToItAndClosesTheSheet() {
        val switched = mutableListOf<HostRef>()
        var dismissed = 0
        show(connected = HostRef.Tunnel(devBox), connection = ConnState.Online, onSwitch = { switched += it }, onDismiss = { dismissed++ })

        rule.onNodeWithTag("direct_${server.id}").performScrollTo().performClick()

        assertEquals(listOf<HostRef>(HostRef.Direct(server)), switched)
        awaitDismissed { dismissed }
    }

    @Test
    fun disconnectLeavesTheConnectedHostAndClosesTheSheet() {
        var disconnected = 0
        var dismissed = 0
        show(connected = HostRef.Tunnel(devBox), connection = ConnState.Online, onDisconnect = { disconnected++ }, onDismiss = { dismissed++ })

        rule.onNodeWithTag("disconnect").assertTouchTargetAtLeast(48.dp).performClick()

        assertEquals(1, disconnected)
        awaitDismissed { dismissed }
    }

    @Test
    fun manageHostsIsAFooterLinkThatClosesTheSheet() {
        var managed = 0
        var dismissed = 0
        show(onManageHosts = { managed++ }, onDismiss = { dismissed++ })

        rule.onNodeWithTag("manage_hosts").performScrollTo().assertTouchTargetAtLeast(48.dp).performClick()

        assertEquals(1, managed)
        awaitDismissed { dismissed }
    }

    @Test
    fun addingAHostFromTheSheetListsItAtOnce() {
        val added = mutableListOf<Triple<String, String, String>>()
        val desk = DirectHost("direct:33333333-cccc", "Desk", "ws://192.168.1.30:9187")
        show(onAddHost = { name, address, token ->
            added += Triple(name, address, token)
            catalog = catalog.copy(direct = catalog.direct + DirectEntry(desk, isLastUsed = false))
            null
        })
        rule.onAllNodesWithTag("direct_${desk.id}").assertCountEquals(0)

        rule.onNodeWithTag("add_host").performScrollTo().performClick()
        rule.onNodeWithTag("add_host_confirm").assertIsNotEnabled()
        rule.onNodeWithTag("add_host_address").performTextInput("192.168.1.30:9187")
        rule.onNodeWithTag("add_host_name").performTextInput("Desk")
        rule.onNodeWithTag("add_host_token").performTextInput("s3cret")
        rule.onNodeWithTag("add_host_confirm").performClick()
        rule.waitForIdle()

        assertEquals(listOf(Triple("Desk", "192.168.1.30:9187", "s3cret")), added)
        rule.onNodeWithTag("add_host_dialog").assertDoesNotExist()
        rule.onNodeWithTag("direct_${desk.id}").assertIsDisplayed()
    }

    @Test
    fun aRefusedAddressKeepsTheDialogOpenWithTheReason() {
        show(onAddHost = { _, _, _ -> "That address is already added as “Laptop”." })

        rule.onNodeWithTag("add_host").performScrollTo().performClick()
        rule.onNodeWithTag("add_host_address").performTextInput("192.168.1.20:9187")
        rule.onNodeWithTag("add_host_confirm").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("add_host_dialog").assertIsDisplayed()
        rule.onNodeWithTag("add_host_error").assertIsDisplayed()
    }

    @Test
    fun withoutAWayToAddThereIsNoAddButton() {
        show(onAddHost = null)

        rule.onAllNodesWithTag("add_host").assertCountEquals(0)
    }

    @Test
    fun withoutASignInTheDirectHostsAreListedAndSignInIsOffered() {
        var signedIn = 0
        catalog = HostCatalog(HostList.NotSignedIn, direct = everything.direct)
        show(onSignIn = { signedIn++ })

        rule.onNodeWithTag("direct_${laptop.id}").assertIsDisplayed()
        rule.onNodeWithTag("direct_${server.id}").assertIsDisplayed()
        rule.onNodeWithText("Sign in with GitHub to see your VS Code hosts").assertIsDisplayed()
        rule.onNodeWithTag("sign_in_from_hosts").performScrollTo().assertTouchTargetAtLeast(48.dp).performClick()

        assertEquals(1, signedIn)
    }

    @Test
    fun signInIsNotOfferedWhereItCannotBeStarted() {
        catalog = HostCatalog(HostList.NotSignedIn, direct = everything.direct)
        show(connected = HostRef.Direct(laptop), connection = ConnState.Online, onSignIn = null)

        rule.onAllNodesWithTag("sign_in_from_hosts").assertCountEquals(0)
        rule.onNodeWithTag("direct_${server.id}").assertIsDisplayed()
    }

    @Test
    fun signedInThereIsNoSignInRow() {
        show()

        rule.onAllNodesWithTag("sign_in_from_hosts").assertCountEquals(0)
    }

    @Test
    fun aFailedTunnelListSaysWhyAndStillListsTheAddedHosts() {
        catalog = HostCatalog(HostList.Failed("Could not reach the Dev Tunnels service."), direct = everything.direct)
        show()

        rule.onNodeWithTag("hosts_error").assertIsDisplayed().assertTextContains("Dev Tunnels", substring = true)
        rule.onNodeWithTag("direct_${laptop.id}").assertIsDisplayed()
        rule.onNodeWithTag("direct_${server.id}").assertIsDisplayed()
        assertTrue(top("hosts_error") < top("direct_${laptop.id}"))
        rule.onAllNodesWithTag("host_t-dev").assertCountEquals(0)
    }

    @Test
    fun aListStillLoadingAndAnEmptyOneSaySoAndKeepTheAddedHosts() {
        catalog = HostCatalog(HostList.Loading, direct = everything.direct)
        show()

        rule.onNodeWithTag("hosts_loading").assertIsDisplayed()
        rule.onNodeWithTag("direct_${laptop.id}").assertIsDisplayed()

        catalog = HostCatalog(HostList.Empty, direct = everything.direct)
        rule.waitForIdle()

        rule.onAllNodesWithTag("hosts_loading").assertCountEquals(0)
        rule.onNodeWithTag("hosts_empty").assertIsDisplayed()
        rule.onNodeWithTag("direct_${laptop.id}").assertIsDisplayed()
    }
}
