package dev.ahpclient.ui

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.TerminalState
import dev.ahpclient.app.HostRef
import dev.ahpclient.state.ChannelGoneException
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.HostTerminalEntry
import dev.ahpclient.tunnel.TunnelInfo
import dev.ahpclient.ui.theme.AhpTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The host's own terminals in the side menu (P8, read only): a Terminals button only when the host lists some, a list of them with
 * each one's title and state, and a tap that watches one, with Back out of it. The wiring to the controller is in `AppRoot`, which
 * needs a live host; here the screens are held and what is handed over is recorded.
 */
@RunWith(AndroidJUnit4::class)
class HostTerminalsUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val host = HostRef.Tunnel(TunnelInfo("t-online", "usw2", "dev-box", 6, 1, listOf(31546)))
    private val now = Instant.parse("2026-10-26T10:30:00Z")

    private val devServer = HostTerminalEntry("ahp-terminal:/t1", "dev server", running = true, status = "Running")
    private val tests = HostTerminalEntry("ahp-terminal:/t2", "tests", running = false, status = "Exited with code 2")

    private fun terminalState(title: String = "dev server"): TerminalState = Ahp.json.decodeFromString(
        TerminalState.serializer(),
        """{"title":"$title","cwd":"file:///work/app","content":[{"type":"unclassified","value":"listening on 3000\n"}],"lifecycle":{"status":"running"},"claim":{"kind":"client","clientId":"x"}}""",
    )

    private fun SemanticsNodeInteraction.assertTouchTargetAtLeast(minimum: Dp): SemanticsNodeInteraction {
        val bounds = fetchSemanticsNode().touchBoundsInRoot
        val minimumPx = with(rule.density) { minimum.toPx() }
        assertTrue("touch target is ${bounds.width} x ${bounds.height} px, smaller than $minimum ($minimumPx px)", bounds.height + 0.5f >= minimumPx && bounds.width + 0.5f >= minimumPx)
        return this
    }

    private fun drawer(terminals: List<HostTerminalEntry>, connectedHost: HostRef? = host, onOpenTerminal: (String) -> Unit = {}) {
        rule.setContent {
            AhpTheme {
                DrawerContent(
                    host = connectedHost,
                    connection = ConnState.Online,
                    sessions = emptyList(),
                    now = now,
                    selected = null,
                    showArchived = false,
                    onToggleArchived = {},
                    query = "",
                    onQueryChange = {},
                    listState = rememberLazyListState(),
                    manage = null,
                    onOpenHost = {},
                    onOpenSession = {},
                    onOpenSettings = {},
                    onSessionCreated = {},
                    terminals = terminals,
                    onOpenTerminal = onOpenTerminal,
                )
            }
        }
    }

    @Test
    fun noTerminalsButtonWhenTheHostListsNone() {
        drawer(emptyList())

        rule.onNodeWithTag("drawer_terminals").assertDoesNotExist()
    }

    @Test
    fun noTerminalsButtonWithoutAConnectedHost() {
        drawer(listOf(devServer), connectedHost = null)

        rule.onNodeWithTag("drawer_terminals").assertDoesNotExist()
    }

    @Test
    fun theTerminalsButtonSaysHowManyAndOpensTheListWithTitleAndState() {
        drawer(listOf(devServer, tests))

        rule.onNodeWithTag("drawer_terminals")
            .assertContentDescriptionEquals("Terminals, 2 on the host")
            .assertTouchTargetAtLeast(48.dp)
            .performClick()

        rule.onNodeWithTag("host_terminals_sheet").assertIsDisplayed()
        val rows = rule.onAllNodesWithTag("host_terminal_row")
        rows.assertCountEquals(2)
        rows[0].assertContentDescriptionEquals("dev server, Running").assertTouchTargetAtLeast(48.dp)
        rows[1].assertContentDescriptionEquals("tests, Exited with code 2").assertTouchTargetAtLeast(48.dp)
        rule.onAllNodesWithTag("host_terminal_status", useUnmergedTree = true)[1].assertTextEquals("Exited with code 2")
    }

    @Test
    fun tappingATerminalHandsOverItsAddressAndClosesTheList() {
        val opened = mutableListOf<String>()
        drawer(listOf(devServer, tests), onOpenTerminal = { opened += it })

        rule.onNodeWithTag("drawer_terminals").performClick()
        rule.onAllNodesWithTag("host_terminal_row")[1].performClick()
        rule.waitForIdle()

        assertEquals(listOf(tests.uri), opened)
        rule.onNodeWithTag("host_terminals_sheet").assertDoesNotExist()
    }

    @Test
    fun aTerminalOpenedFromTheListIsWatchedReadOnlyAndBackReturnsToTheMenu() {
        var watching by mutableStateOf<String?>(null)
        rule.setContent {
            AhpTheme {
                // As AppRoot holds it: the host terminal is a screen over the page, closed by its own Back or by system Back.
                OverlayBackHandlers(
                    screens = OverlayScreens(terminal = watching != null),
                    onCloseChat = {},
                    onCloseCanvas = {},
                    onCloseTerminal = { watching = null },
                    onCloseChangeset = {},
                    onCloseDiff = {},
                )
                val shown = watching
                if (shown != null) {
                    TerminalScreen(terminalState(), null, ConnState.Online, onBack = { watching = null }, fromHost = true)
                } else {
                    DrawerContent(
                        host = host, connection = ConnState.Online, sessions = emptyList(), now = now, selected = null,
                        showArchived = false, onToggleArchived = {}, query = "", onQueryChange = {}, listState = rememberLazyListState(),
                        manage = null, onOpenHost = {}, onOpenSession = {}, onOpenSettings = {}, onSessionCreated = {},
                        terminals = listOf(devServer), onOpenTerminal = { watching = it },
                    )
                }
            }
        }

        rule.onNodeWithTag("drawer_terminals").performClick()
        rule.onAllNodesWithTag("host_terminal_row")[0].performClick()
        rule.waitForIdle()

        rule.onNodeWithTag("terminal_title").assertTextEquals("dev server")
        rule.onNodeWithTag("terminal_status").assertTextEquals("Running · /work/app")
        rule.onNodeWithTag("terminal_note").assertTextContains("Read only", substring = true)
        rule.onNodeWithTag("back").assertContentDescriptionEquals("Back").assertTouchTargetAtLeast(48.dp)

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()

        assertEquals(null, watching)
        rule.onNodeWithTag("drawer_terminals").assertIsDisplayed()
    }

    @Test
    fun aHostTerminalThatIsGoneDoesNotPointAtAToolResult() {
        rule.setContent { AhpTheme { TerminalScreen(null, ChannelGoneException("ahp-terminal:/t1"), ConnState.Online, {}, fromHost = true) } }

        rule.onNodeWithTag("terminal_gone").assertTextEquals("This terminal is no longer on the host.")
    }

    @Test
    fun theTerminalsListPassesAccessibilityInBothThemes() {
        assumeTrue("Compose accessibility checks need API 34 or newer", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        rule.enableAccessibilityChecks()
        var dark by mutableStateOf(false)
        rule.setContent { AhpTheme(dark) { HostTerminalsSheet(listOf(devServer, tests), onOpen = {}, onDismiss = {}) } }

        listOf(false, true).forEach { theme ->
            dark = theme
            rule.waitForIdle()
            rule.onRoot().tryPerformAccessibilityChecks()
        }
    }
}
