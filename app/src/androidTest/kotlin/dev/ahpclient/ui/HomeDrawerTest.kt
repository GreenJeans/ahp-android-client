package dev.ahpclient.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.microsoft.agenthostprotocol.generated.Implementation
import dev.ahpclient.app.AppController
import dev.ahpclient.app.AppState
import dev.ahpclient.app.DirectSessionFactory
import dev.ahpclient.app.HostSessionFactory
import dev.ahpclient.app.InMemoryEntitlementSettings
import dev.ahpclient.app.LastHostStore
import dev.ahpclient.app.ProEntitlement
import dev.ahpclient.auth.GitHubDeviceAuth
import dev.ahpclient.auth.GitHubToken
import dev.ahpclient.auth.Secret
import dev.ahpclient.data.UiSettings
import dev.ahpclient.direct.InMemoryDirectHostStore
import dev.ahpclient.security.BlobStore
import dev.ahpclient.security.SecureBox
import dev.ahpclient.security.TokenVault
import dev.ahpclient.state.HostConnector
import dev.ahpclient.state.HostSession
import dev.ahpclient.tunnel.DevTunnelsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertTrue
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ahpclient.app.DirectEntry
import dev.ahpclient.app.HostCatalog
import dev.ahpclient.app.HostEntry
import dev.ahpclient.app.HostList
import dev.ahpclient.direct.DirectHost
import dev.ahpclient.tunnel.TunnelInfo
import dev.ahpclient.ui.theme.AhpTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The side menu as the home page, and Back around it ([HomeDrawer], [rememberHomeMenu], [OnConnectionChange], [OfferHostsOnce]), driven
 * with a stand-in for the controller: the app's own wiring of them in `Shell` needs a live controller and is not part of this.
 * Back is the activity's own dispatcher, as the system's Back is.
 */
@RunWith(AndroidJUnit4::class)
class HomeDrawerTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** What the controller and the page state of `Shell` would hold, and what the screen did about Back. */
    private class Nav {
        var setSession: (String?) -> Unit = {}
        var setPage: (Boolean) -> Unit = {}
        var setOverlay: (Boolean) -> Unit = {}
        var setRequests: (Int) -> Unit = {}
        lateinit var menu: HomeMenu
        var session: () -> String? = { null }
        var leaves = 0
    }

    private val nav = Nav()

    private fun show(initialSession: String? = null, restoration: StateRestorationTester? = null, realLeave: Boolean = false) {
        val content = @Composable {
            AhpTheme {
                var session by rememberSaveable { mutableStateOf(initialSession) }
                var page by rememberSaveable { mutableStateOf(false) }
                var overlay by rememberSaveable { mutableStateOf(false) }
                var requests by rememberSaveable { mutableStateOf(0) }
                nav.setSession = { session = it }
                nav.setPage = { page = it }
                nav.setOverlay = { overlay = it }
                nav.setRequests = { requests = it }
                nav.session = { session }
                val menu = rememberHomeMenu(session, page)
                nav.menu = menu
                CloseMenuOnRequest(requests, menu)
                val context = LocalContext.current
                HomeDrawer(
                    menu = menu,
                    session = session,
                    pageOpen = page,
                    overlayOpen = overlay,
                    onClosePage = { page = false },
                    onLeaveApp = { nav.leaves++; if (realLeave) leaveApp(context) },
                    drawerContent = {
                        ModalDrawerSheet(Modifier.testTag("drawer")) {
                            Column {
                                Text("menu")
                                // The way Shell opens a session from the menu: close what can be closed, then the session.
                                Button(onClick = { menu.close(); session = "ahp-session:/a" }, modifier = Modifier.testTag("pick_a")) { Text("A") }
                                Button(onClick = { page = true; menu.close() }, modifier = Modifier.testTag("settings")) { Text("Settings") }
                            }
                        }
                    },
                    content = {
                        if (session != null) {
                            Text("session $session", Modifier.testTag("session_page"))
                        } else {
                            Surface(Modifier.testTag("home_background"), color = MaterialTheme.colorScheme.background) {}
                        }
                        if (page) Text("a page", Modifier.testTag("page"))
                    },
                )
            }
        }
        if (restoration != null) restoration.setContent(content) else rule.setContent(content)
    }

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun drawer(): DrawerValue {
        rule.waitForIdle()
        return rule.runOnIdle { nav.menu.drawer.currentValue }
    }

    private fun session(): String? = rule.runOnIdle { nav.session() }

    private fun setSession(uri: String?) {
        rule.runOnUiThread { nav.setSession(uri) }
        rule.waitForIdle()
    }

    // ---- held open ---------------------------------------------------------------------------------------------

    @Test
    fun withNoSessionTheMenuIsOpenFromTheStart() {
        show()

        assertEquals(DrawerValue.Open, drawer())
        rule.onNodeWithTag("drawer").assertIsDisplayed()
        rule.onNodeWithTag("home_background").assertExists()
    }

    @Test
    fun withNoSessionTheMenuCannotBeSwipedOrTappedShut() {
        show()

        rule.onNodeWithTag("drawer").performTouchInput { swipeLeft() }
        assertEquals(DrawerValue.Open, drawer())
        // The scrim is the strip to the right of the menu.
        rule.onRoot().performTouchInput { click(Offset(width - 4f, height / 2f)) }
        assertEquals(DrawerValue.Open, drawer())
        // Nor by asking it to close, as the menu's own buttons do.
        rule.runOnUiThread { nav.menu.close() }
        assertEquals(DrawerValue.Open, drawer())
        rule.onNodeWithTag("drawer").assertIsDisplayed()
    }

    @Test
    fun withNoSessionTheDismissActionsOfAssistiveServicesAreRefusedAndCounted() {
        show()
        val dismiss = SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)
        val scrim = hasClickAction() and hasContentDescription("Close navigation menu")
        assertEquals(0, rule.runOnIdle { nav.menu.refusals.intValue })

        // Material offers both while the menu is open; neither closes it, and each says so (HomeDrawer announces the count).
        rule.onNode(dismiss, useUnmergedTree = true).performSemanticsAction(SemanticsActions.Dismiss)
        rule.waitForIdle()
        assertEquals(1, rule.runOnIdle { nav.menu.refusals.intValue })
        rule.onNode(scrim, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(2, rule.runOnIdle { nav.menu.refusals.intValue })
        assertEquals(DrawerValue.Open, drawer())
    }

    @Test
    fun overASessionClosingTheMenuIsNotRefused() {
        show(initialSession = "ahp-session:/a")
        rule.runOnUiThread { nav.menu.open() }

        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss), useUnmergedTree = true).performSemanticsAction(SemanticsActions.Dismiss)

        assertEquals(DrawerValue.Closed, drawer())
        assertEquals(0, rule.runOnIdle { nav.menu.refusals.intValue })
    }

    @Test
    fun withASessionOpenTheMenuStartsClosedAndCanBeOpenedAndSwipedShut() {
        show(initialSession = "ahp-session:/a")

        assertEquals(DrawerValue.Closed, drawer())
        rule.runOnUiThread { nav.menu.open() }
        assertEquals(DrawerValue.Open, drawer())
        rule.onNodeWithTag("drawer").performTouchInput { swipeLeft() }
        assertEquals(DrawerValue.Closed, drawer())
    }

    // ---- following the session ---------------------------------------------------------------------------------

    @Test
    fun openingASessionFromTheMenuClosesTheMenu() {
        show()

        rule.onNodeWithTag("pick_a").performClick()

        assertEquals("ahp-session:/a", session())
        assertEquals(DrawerValue.Closed, drawer())
        rule.onNodeWithTag("session_page").assertIsDisplayed()
    }

    @Test
    fun aNotificationTapOpeningASessionWithTheMenuOpenClosesTheMenu() {
        show()

        setSession("ahp-session:/a")

        assertEquals(DrawerValue.Closed, drawer())
    }

    @Test
    fun aNotificationTapSwitchingSessionsWithTheMenuOpenOverOneClosesTheMenu() {
        show(initialSession = "ahp-session:/a")
        rule.runOnUiThread { nav.menu.open() }
        assertEquals(DrawerValue.Open, drawer())

        setSession("ahp-session:/b")

        assertEquals(DrawerValue.Closed, drawer())
        rule.onNodeWithTag("session_page").assertIsDisplayed()
    }

    @Test
    fun pickingTheSessionThatIsAlreadyOpenClosesTheMenuToo() {
        show(initialSession = "ahp-session:/a")
        rule.runOnUiThread { nav.menu.open() }
        assertEquals(DrawerValue.Open, drawer())

        rule.onNodeWithTag("pick_a").performClick()

        assertEquals(DrawerValue.Closed, drawer())
    }

    @Test
    fun aNotificationTapOnTheSessionThatIsAlreadyOpenClosesTheMenuOpenOverIt() {
        show(initialSession = "ahp-session:/a")
        rule.runOnUiThread { nav.menu.open() }
        assertEquals(DrawerValue.Open, drawer())

        // The session does not change, so only the request itself can close the menu.
        rule.runOnUiThread { nav.setRequests(1) }

        assertEquals("ahp-session:/a", session())
        assertEquals(DrawerValue.Closed, drawer())
    }

    @Test
    fun aNotificationTapWithNoSessionLeavesTheHomeMenuOpen() {
        show()

        rule.runOnUiThread { nav.setRequests(1) }

        assertEquals(DrawerValue.Open, drawer())
    }

    @Test
    fun whenTheSessionClosesTheMenuOpens() {
        show(initialSession = "ahp-session:/a")
        assertEquals(DrawerValue.Closed, drawer())

        setSession(null)

        assertEquals(DrawerValue.Open, drawer())
        rule.onNodeWithTag("drawer").assertIsDisplayed()
    }

    // ---- Back --------------------------------------------------------------------------------------------------

    @Test
    fun backFromASessionOpensTheMenuAndKeepsTheSession() {
        show(initialSession = "ahp-session:/a")

        back()

        assertEquals("ahp-session:/a", session())
        assertEquals(DrawerValue.Open, drawer())
        assertEquals(0, nav.leaves)
        // Swiping the menu back closed returns to the open session.
        rule.onNodeWithTag("drawer").performTouchInput { swipeLeft() }
        assertEquals(DrawerValue.Closed, drawer())
        assertEquals("ahp-session:/a", session())
        rule.onNodeWithTag("session_page").assertIsDisplayed()
    }

    @Test
    fun backFromTheMenuWithNoSessionLeavesTheAppAndKeepsTheMenuOpen() {
        show()

        back()

        assertEquals(1, nav.leaves)
        assertEquals(DrawerValue.Open, drawer())
        rule.onNodeWithTag("drawer").assertIsDisplayed()
        // And again: it is the same each time.
        back()
        assertEquals(2, nav.leaves)
        assertFalse(rule.activity.isFinishing)
    }

    @Test
    fun backWithTheMenuOverASessionClosesTheMenuAndKeepsTheSession() {
        show(initialSession = "ahp-session:/a")
        rule.runOnUiThread { nav.menu.open() }
        assertEquals(DrawerValue.Open, drawer())

        back()

        assertEquals(DrawerValue.Closed, drawer())
        assertEquals("ahp-session:/a", session())
        assertEquals(0, nav.leaves)
    }

    @Test
    fun backFromAPageOverTheHomeMenuReturnsToTheMenu() {
        show()

        rule.onNodeWithTag("settings").performClick()
        assertEquals(DrawerValue.Closed, drawer())
        rule.onNodeWithTag("page").assertIsDisplayed()

        back()

        rule.onNodeWithTag("page").assertDoesNotExist()
        assertEquals(DrawerValue.Open, drawer())
        assertEquals(0, nav.leaves)
    }

    @Test
    fun backFromAPageOverASessionReturnsToTheSessionNotTheMenu() {
        show(initialSession = "ahp-session:/a")
        rule.runOnUiThread { nav.menu.open() }
        rule.onNodeWithTag("settings").performClick()
        assertEquals(DrawerValue.Closed, drawer())
        rule.onNodeWithTag("page").assertIsDisplayed()

        back()

        rule.onNodeWithTag("page").assertDoesNotExist()
        assertEquals(DrawerValue.Closed, drawer())
        assertEquals("ahp-session:/a", session())
    }

    @Test
    fun aScreenOverTheSessionKeepsItsOwnBack() {
        show(initialSession = "ahp-session:/a")
        rule.runOnUiThread { nav.setOverlay(true) }
        rule.waitForIdle()

        back()

        // The screen over the session closes itself in Shell; the menu is not opened under it, nor the app left.
        assertEquals(DrawerValue.Closed, drawer())
        assertEquals(0, nav.leaves)
        assertEquals("ahp-session:/a", session())
    }

    @Test
    fun leavingTheAppPutsTheTaskInTheBackgroundAndKeepsTheActivity() {
        show(realLeave = true)

        back()

        // The activity is stopped, not finished: it is still there, with the menu as it was, for when the app is opened again.
        val deadline = System.currentTimeMillis() + 10_000
        while (rule.activityRule.scenario.state != Lifecycle.State.CREATED && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals(Lifecycle.State.CREATED, rule.activityRule.scenario.state)
        assertFalse(rule.activity.isFinishing)
        assertFalse(rule.activity.isDestroyed)
        assertEquals(DrawerValue.Open, nav.menu.drawer.currentValue)
    }

    // ---- rotation ----------------------------------------------------------------------------------------------

    @Test
    fun rotationKeepsTheMenuOpenOverASessionAndTheSession() {
        val restoration = StateRestorationTester(rule)
        show(initialSession = "ahp-session:/a", restoration = restoration)
        rule.runOnUiThread { nav.menu.open() }
        assertEquals(DrawerValue.Open, drawer())

        restoration.emulateSavedInstanceStateRestore()

        assertEquals("ahp-session:/a", session())
        assertEquals(DrawerValue.Open, drawer())
    }

    @Test
    fun rotationKeepsAClosedMenuClosedOverASession() {
        val restoration = StateRestorationTester(rule)
        show(restoration = restoration)
        rule.onNodeWithTag("pick_a").performClick()
        assertEquals(DrawerValue.Closed, drawer())

        restoration.emulateSavedInstanceStateRestore()

        assertEquals("ahp-session:/a", session())
        assertEquals(DrawerValue.Closed, drawer())
    }

    @Test
    fun rotationWhileTheMenuIsOpeningLeavesItOpen() {
        val restoration = StateRestorationTester(rule)
        show(initialSession = "ahp-session:/a", restoration = restoration)
        assertEquals(DrawerValue.Closed, drawer())

        // Removing the session starts the menu opening; the activity is recreated before it has settled.
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { nav.setSession(null) }
        rule.mainClock.advanceTimeBy(64)
        // It is on its way, not there: the drawer reports a settled value only.
        assertEquals(DrawerValue.Open, rule.runOnUiThread { nav.menu.drawer.targetValue })
        assertEquals(DrawerValue.Closed, rule.runOnUiThread { nav.menu.drawer.currentValue })
        restoration.emulateSavedInstanceStateRestore()
        rule.mainClock.autoAdvance = true

        assertEquals(null, session())
        assertEquals(DrawerValue.Open, drawer())
    }

    @Test
    fun rotationWithNoSessionKeepsTheMenuOpen() {
        val restoration = StateRestorationTester(rule)
        show(restoration = restoration)

        restoration.emulateSavedInstanceStateRestore()

        assertEquals(null, session())
        assertEquals(DrawerValue.Open, drawer())
        rule.onNodeWithTag("drawer").assertIsDisplayed()
    }

    // ---- a host connecting or going ----------------------------------------------------------------------------

    @Test
    fun aPageIsLeftWhenAHostConnectsOrGoesButNotWhenFirstComposed() {
        var connected by mutableStateOf(true)
        var left = 0
        rule.setContent { OnConnectionChange(connected) { left++ } }
        rule.waitForIdle()
        assertEquals(0, left)

        connected = false
        rule.waitForIdle()
        assertEquals(1, left)

        connected = true
        rule.waitForIdle()
        assertEquals(2, left)
    }

    @Test
    fun aRotationDoesNotRepeatTheConnectionChange() {
        var connected by mutableStateOf(false)
        var left = 0
        val restoration = StateRestorationTester(rule)
        restoration.setContent { OnConnectionChange(connected) { left++ } }
        connected = true
        rule.waitForIdle()
        assertEquals(1, left)

        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()

        assertEquals(1, left)
    }

    // ---- the hosts sheet on a cold start with no last host -----------------------------------------------------

    private val tunnel = TunnelInfo("t-online", "usw2", "dev-box", 6, 1, listOf(31546))
    private val direct = DirectHost("direct:11111111-aaaa", "Laptop", "ws://192.168.1.20:9187")

    /** How many times the sheet was offered for [catalog] as the first and only thing shown. */
    private fun offers(catalog: HostCatalog, connected: Boolean = false): Int {
        var offered = 0
        rule.setContent { OfferHostsOnce(connected, catalog) { offered++ } }
        rule.waitForIdle()
        return offered
    }

    @Test
    fun thereIsNoOfferWhileTheTunnelListLoads() {
        assertEquals(0, offers(HostCatalog(HostList.Loading, emptyList())))
    }

    @Test
    fun thereIsNoOfferWhenATunnelHostIsMarkedLastUsed() {
        assertEquals(0, offers(HostCatalog(HostList.Loaded(listOf(HostEntry(tunnel, isLastUsed = true))), emptyList())))
    }

    @Test
    fun thereIsNoOfferWhenTheLastHostIsOneAddedByAddress() {
        assertEquals(0, offers(HostCatalog(HostList.NotSignedIn, listOf(DirectEntry(direct, isLastUsed = true)))))
    }

    @Test
    fun thereIsNoOfferWhileAHostIsConnected() {
        assertEquals(0, offers(HostCatalog(HostList.Loaded(listOf(HostEntry(tunnel, isLastUsed = false))), emptyList()), connected = true))
    }

    @Test
    fun withNoLastHostALoadedListOffersTheSheetOnce() {
        assertEquals(1, offers(HostCatalog(HostList.Loaded(listOf(HostEntry(tunnel, isLastUsed = false))), listOf(DirectEntry(direct, isLastUsed = false)))))
    }

    @Test
    fun withNoLastHostAnEmptyListOffersTheSheetOnce() {
        assertEquals(1, offers(HostCatalog(HostList.Empty, emptyList())))
    }

    @Test
    fun withNoLastHostAFailedListOffersTheSheetOnce() {
        assertEquals(1, offers(HostCatalog(HostList.Failed("down"), emptyList())))
    }

    @Test
    fun thereIsNoOfferWhileAutoConnectIsPendingOnAFailedList() {
        var catalog by mutableStateOf(HostCatalog(HostList.Failed("down"), emptyList(), autoConnectPending = true))
        var connected by mutableStateOf(false)
        var offered = 0
        rule.setContent { OfferHostsOnce(connected, catalog) { offered++ } }
        rule.waitForIdle()
        assertEquals(0, offered)

        // The retry loads the list and connects to the last host: the sheet never opens.
        connected = true
        catalog = HostCatalog(HostList.Loaded(listOf(HostEntry(tunnel, isLastUsed = true))), emptyList(), autoConnectPending = false)
        rule.waitForIdle()
        assertEquals(0, offered)
    }

    @Test
    fun whenAPendingAutoConnectEndsWithNoLastHostTheSheetIsOfferedOnce() {
        var catalog by mutableStateOf(HostCatalog(HostList.Failed("down"), emptyList(), autoConnectPending = true))
        var offered = 0
        rule.setContent { OfferHostsOnce(false, catalog) { offered++ } }
        rule.waitForIdle()
        assertEquals(0, offered)

        catalog = HostCatalog(HostList.Loaded(listOf(HostEntry(tunnel, isLastUsed = false))), emptyList(), autoConnectPending = false)
        rule.waitForIdle()
        assertEquals(1, offered)
    }

    @Test
    fun withNoLastHostAndNoSignInTheHostsAddedByAddressOfferTheSheetOnce() {
        assertEquals(1, offers(HostCatalog(HostList.NotSignedIn, listOf(DirectEntry(direct, isLastUsed = false)))))
    }

    @Test
    fun theOfferWaitsForTheListAndIsNotRepeated() {
        var catalog by mutableStateOf(HostCatalog(HostList.Loading, emptyList()))
        var connected by mutableStateOf(false)
        var offered = 0
        rule.setContent { OfferHostsOnce(connected, catalog) { offered++ } }
        rule.waitForIdle()
        assertEquals(0, offered)

        catalog = HostCatalog(HostList.Empty, emptyList())
        rule.waitForIdle()
        assertEquals(1, offered)

        // A refresh, a reload and a return to the list are not a cold start.
        catalog = HostCatalog(HostList.Loading, emptyList())
        rule.waitForIdle()
        catalog = HostCatalog(HostList.Loaded(listOf(HostEntry(tunnel, isLastUsed = false))), emptyList())
        rule.waitForIdle()
        assertEquals(1, offered)
    }

    @Test
    fun afterAHostHasBeenConnectedThereIsNeverAnOffer() {
        var catalog by mutableStateOf(HostCatalog(HostList.Loading, emptyList()))
        var connected by mutableStateOf(true)
        var offered = 0
        rule.setContent { OfferHostsOnce(connected, catalog) { offered++ } }
        rule.waitForIdle()

        // Disconnect (or the connected host removed): the list has no last host, but the person has chosen before.
        connected = false
        catalog = HostCatalog(HostList.Empty, emptyList())
        rule.waitForIdle()

        assertEquals(0, offered)
    }

    @Test
    fun switchingHostsFromTheSheetInAppRootNeverFlashesNotConnectedAndClosesTheSheet() {
        val controller = buildAppController(signedIn = false)
        val ui = UiSettings(rule.activity.getSharedPreferences("test_ui_n42", Context.MODE_PRIVATE))
        val observedStates = mutableListOf<AppState>()
        var sawDisconnectedFrame = false
        rule.setContent {
            val current by controller.state.collectAsStateWithLifecycle()
            observedStates += current
            if (current !is AppState.Connected) sawDisconnectedFrame = true
            AppRoot(controller = controller, ui = ui, onOpenGitHub = {}, onCopyCode = {})
        }
        rule.waitForIdle()
        rule.onNodeWithTag("drawer_host_name", useUnmergedTree = true).assertTextEquals("Laptop")
        rule.onAllNodesWithTag("drawer_no_host").assertCountEquals(0)

        rule.onNodeWithTag("drawer_host").performClick()
        rule.onNodeWithTag("host_sheet").assertIsDisplayed()
        rule.onNodeWithTag("direct_${server.id}").assertIsDisplayed().performClick()

        rule.waitUntil(5_000) { rule.onAllNodesWithTag("host_sheet").fetchSemanticsNodes().isEmpty() }
        rule.waitForIdle()

        assertFalse("AppRoot must never render a disconnected frame during a host switch", sawDisconnectedFrame)
        assertTrue("Every observed controller state must stay Connected", observedStates.all { it is AppState.Connected })
        rule.onAllNodesWithTag("drawer_no_host").assertCountEquals(0)
        rule.onAllNodesWithText("Not connected").assertCountEquals(0)
        rule.onNodeWithTag("drawer_host_name", useUnmergedTree = true).assertTextEquals("Server")
    }

    private val server = DirectHost("direct:22222222-bbbb", "Server", "wss://agents.example.com")
    private var appScope: CoroutineScope? = null

    @After
    fun tearDownScope() {
        appScope?.cancel()
        appScope = null
    }

    private fun buildAppController(signedIn: Boolean = false): AppController {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        appScope = scope
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"value":[]}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val box = object : SecureBox {
            override fun seal(plain: ByteArray, aad: ByteArray): ByteArray = plain.copyOf()
            override fun open(blob: ByteArray, aad: ByteArray): ByteArray = blob.copyOf()
            override fun destroyKey() {}
        }
        val blobStore = object : BlobStore {
            var data: ByteArray? = null
            override suspend fun read(): ByteArray? = data
            override suspend fun write(blob: ByteArray) { data = blob }
            override suspend fun delete() { data = null }
        }
        val vault = TokenVault(box, blobStore)
        if (signedIn) {
            runBlocking { vault.save(GitHubToken(Secret("ghu_test"), Secret("ghr_test"), null)) }
        }
        val lastHost = object : LastHostStore {
            var saved: String? = null
            override suspend fun get(): String? = saved
            override suspend fun set(tunnelId: String) { saved = tunnelId }
        }
        val clientInfo = Implementation(name = "ahp-android-client-test", version = "0.1")
        val directStore = InMemoryDirectHostStore()
        runBlocking { directStore.save(listOf(direct, server)) }
        val controller = AppController(
            auth = GitHubDeviceAuth(http = http),
            vault = vault,
            devTunnels = DevTunnelsClient(http = http),
            lastHost = lastHost,
            sessions = HostSessionFactory { info, _ ->
                HostSession(HostConnector { _, _, _ -> awaitCancellation() }, scope, clientInfo, clientId = "client-${info.tunnelId}")
            },
            scope = scope,
            directStore = directStore,
            directSessions = DirectSessionFactory { host, _, _ ->
                HostSession(HostConnector { _, _, _ -> awaitCancellation() }, scope, clientInfo, clientId = "client-${host.id}")
            },
            pro = ProEntitlement(InMemoryEntitlementSettings(false)),
        )
        controller.start()
        rule.waitUntil(5_000) { controller.state.value is AppState.Hosts }
        controller.connect(direct)
        rule.waitUntil(5_000) { controller.state.value is AppState.Connected }
        return controller
    }

    @Test
    fun removingTheConnectedDirectHostFromSettingsInAppRootDisconnectsAndReturnsToTheChooseHostDrawer() {
        val controller = buildAppController(signedIn = false)
        val ui = UiSettings(rule.activity.getSharedPreferences("test_ui_n52", Context.MODE_PRIVATE))
        rule.setContent {
            AppRoot(controller = controller, ui = ui, onOpenGitHub = {}, onCopyCode = {})
        }
        rule.waitForIdle()

        rule.onNodeWithTag("settings").performClick()
        rule.onNodeWithTag("settings_title").assertIsDisplayed()

        rule.onNodeWithTag("remove_${direct.id}").performClick()
        rule.onNodeWithTag("remove_host_dialog").assertIsDisplayed()
        rule.onNodeWithText("connection ends too", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("remove_host_confirm").performClick()

        rule.waitUntil(5_000) { controller.state.value is AppState.Hosts }
        rule.waitForIdle()

        rule.onAllNodesWithTag("settings_title").assertCountEquals(0)
        rule.onNodeWithTag("drawer_no_host").assertIsDisplayed()
        rule.onNodeWithTag("choose_host").assertIsDisplayed()
        assertEquals(listOf(server), controller.hosts.value.direct.map { it.host })
    }

    @Test
    fun signingOutFromSettingsWhileConnectedInAppRootWarnsAndEndsTheConnectionWhileKeepingDirectHosts() {
        val controller = buildAppController(signedIn = true)
        val ui = UiSettings(rule.activity.getSharedPreferences("test_ui_n53", Context.MODE_PRIVATE))
        rule.setContent {
            AppRoot(controller = controller, ui = ui, onOpenGitHub = {}, onCopyCode = {})
        }
        rule.waitForIdle()

        rule.onNodeWithTag("settings").performClick()
        rule.onNodeWithTag("settings_title").assertIsDisplayed()

        rule.onNodeWithTag("sign_out").performClick()
        rule.onNodeWithTag("sign_out_dialog").assertIsDisplayed()
        rule.onNodeWithText("connected now", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("sign_out_confirm").performClick()

        rule.waitUntil(5_000) { controller.state.value is AppState.SignedOut }
        rule.waitForIdle()

        rule.onNodeWithTag("sign_in_button").assertIsDisplayed()
        rule.onNodeWithTag("use_direct_hosts").assertIsDisplayed()
        assertEquals(listOf(direct, server), controller.hosts.value.direct.map { it.host })
    }

    @Test
    fun aRotationDoesNotOfferTheSheetAgain() {
        val restoration = StateRestorationTester(rule)
        var offered = 0
        restoration.setContent { OfferHostsOnce(false, HostCatalog(HostList.Empty, emptyList())) { offered++ } }
        rule.waitForIdle()
        assertEquals(1, offered)

        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()

        assertEquals(1, offered)
    }
}
