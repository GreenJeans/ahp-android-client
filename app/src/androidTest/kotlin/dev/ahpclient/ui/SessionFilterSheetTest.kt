package dev.ahpclient.ui

import android.os.Build
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.SessionSummary
import dev.ahpclient.app.HostRef
import dev.ahpclient.direct.DirectHost
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.HostSessionEntry
import dev.ahpclient.state.SessionFilter
import dev.ahpclient.state.SessionGrouping
import dev.ahpclient.state.SessionListModel
import dev.ahpclient.state.SessionOrdering
import dev.ahpclient.tunnel.TunnelInfo
import dev.ahpclient.ui.theme.AhpTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionFilterSheetTest {
    @get:Rule
    val rule = createComposeRule()

    private val host = TunnelInfo("t-online", "usw2", "dev-box", 6, 1, listOf(31546))
    private val laptop = DirectHost("direct:2", "Laptop", "ws://192.168.1.20:9187")
    private val now = Instant.parse("2026-10-26T10:30:00Z")

    private fun summary(uri: String, title: String, status: Int, modified: String, extra: String = ""): SessionSummary =
        Ahp.json.decodeFromString(
            SessionSummary.serializer(),
            """{"resource":"$uri","provider":"copilotcli","title":"$title","status":$status,"createdAt":"2026-10-20T10:00:00.000Z","modifiedAt":"$modified"$extra}""",
        )

    private val waiting = summary("ahp-session:/w", "Needs answer", 56, "2026-10-26T10:00:00.000Z", ""","project":{"uri":"file:///w","displayName":"repo-w"}""")
    private val working = summary("ahp-session:/k", "Working", 40, "2026-10-26T10:25:00.000Z", ""","activity":"Running tests"""")
    private val external = summary("ahp-session:/ext", "External CLI run", 1, "2026-10-26T10:15:00.000Z", ""","_meta":{"vscode.external":true}""")

    private fun SemanticsNodeInteraction.assertTouchTargetAtLeast(minimum: Dp): SemanticsNodeInteraction {
        val bounds = fetchSemanticsNode().touchBoundsInRoot
        val minimumPx = with(rule.density) { minimum.toPx() }
        assertTrue("touch target is ${bounds.width} x ${bounds.height} px, smaller than $minimum ($minimumPx px)", bounds.height + 0.5f >= minimumPx && bounds.width + 0.5f >= minimumPx)
        return this
    }

    @Test
    fun filterButtonOpensFilterSheetAndUpdatesGroupingAndVisibility() {
        rule.setContent {
            var showArchived by remember { mutableStateOf(false) }
            var query by remember { mutableStateOf("") }
            var filter by remember { mutableStateOf(SessionFilter.DEFAULT) }
            AhpTheme {
                DrawerContent(
                    host = HostRef.Tunnel(host),
                    connection = ConnState.Online,
                    sessions = listOf(waiting, working, external),
                    now = now,
                    selected = null,
                    showArchived = showArchived,
                    onToggleArchived = { showArchived = !showArchived },
                    query = query,
                    onQueryChange = { query = it },
                    listState = rememberLazyListState(),
                    manage = null,
                    onOpenHost = {},
                    onOpenSession = {},
                    onOpenSettings = {},
                    onSessionCreated = {},
                    filter = filter,
                    onFilterChange = { filter = it },
                )
            }
        }

        // External session is hidden by default
        rule.onAllNodesWithTag("session_row").assertCountEquals(2)
        rule.onNodeWithTag("session_filter").assertTouchTargetAtLeast(48.dp).performClick()
        rule.onNodeWithTag("session_filter_sheet").assertIsDisplayed()

        // Enable external sessions and Workspace grouping
        rule.onNodeWithTag("group_workspace").performClick()
        rule.onNodeWithTag("filter_show_external").performScrollTo().performClick()
        rule.onNodeWithTag("session_filter_badge", useUnmergedTree = true).assertTextEquals("2")

        // Reset filters brings everything back to default
        rule.onNodeWithTag("reset_filters").assertTouchTargetAtLeast(48.dp).performClick()
        rule.onAllNodesWithTag("session_row").assertCountEquals(2)
    }

    @Test
    fun multiHostEntriesShowHostBadgeOnRowsAndRouteTapWithHostId() {
        val opened = mutableListOf<Pair<String?, String>>()
        val h1 = HostRef.Tunnel(host)
        val h2 = HostRef.Direct(laptop)
        val entries = listOf(
            HostSessionEntry(h1, waiting),
            HostSessionEntry(h2, working),
        )
        rule.setContent {
            AhpTheme {
                DrawerContent(
                    host = h1,
                    connection = ConnState.Online,
                    sessions = listOf(waiting),
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
                    entries = entries,
                    connectedHosts = listOf(h1, h2),
                    onOpenSessionOnHost = { hostId, uri -> opened += hostId to uri },
                )
            }
        }

        rule.onNodeWithTag("drawer_host_name", useUnmergedTree = true).assertTextEquals("dev-box + 1 more")
        rule.onAllNodesWithTag("row_host", useUnmergedTree = true).assertCountEquals(2)
        rule.onAllNodesWithTag("session_row")[1].performClick()
        assertEquals(listOf(h2.id to working.resource), opened)
    }

    @Test
    fun sessionFilterSheetPassesAccessibilityInBothThemes() {
        assumeTrue("Compose accessibility checks need API 34 or newer", Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        rule.enableAccessibilityChecks()

        val h1 = HostRef.Tunnel(host)
        val h2 = HostRef.Direct(laptop)
        val entries = listOf(
            HostSessionEntry(h1, waiting),
            HostSessionEntry(h2, working),
        )
        val filter = SessionFilter(
            ordering = SessionOrdering.CREATED,
            grouping = SessionGrouping.WORKSPACE,
        )
        val model = SessionListModel.fromEntries(entries, filter, now)
        var dark by mutableStateOf(false)
        rule.setContent {
            AhpTheme(dark) {
                SessionFilterSheet(
                    model = model,
                    filter = filter,
                    onFilterChange = {},
                    onReset = {},
                    onDismiss = {},
                )
            }
        }
        listOf(false, true).forEach { theme ->
            dark = theme
            rule.waitForIdle()
            rule.onRoot().tryPerformAccessibilityChecks()
        }
    }
}
