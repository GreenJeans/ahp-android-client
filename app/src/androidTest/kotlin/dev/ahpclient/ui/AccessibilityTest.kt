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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.dp
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ahpclient.app.AppState
import dev.ahpclient.app.HostEntry
import dev.ahpclient.app.HostList
import dev.ahpclient.app.SignInStep
import dev.ahpclient.state.ConnState
import dev.ahpclient.tunnel.TunnelInfo
import dev.ahpclient.ui.theme.AhpTheme
import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.SessionState
import com.microsoft.agenthostprotocol.generated.SessionSummary
import java.time.Instant
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

    @Test
    fun hostScreens() {
        val entries = listOf(HostEntry(host, isLastUsed = true), HostEntry(TunnelInfo("t-off", "usw2", "old-laptop", 5, 0, listOf(31546)), isLastUsed = false))
        val lists = listOf(HostList.Loading, HostList.Loaded(entries), HostList.Empty, HostList.Failed("Could not reach the Dev Tunnels service."))
        checkEach(*lists.map { list -> @Composable { HostsScreen(AppState.Hosts(list, now.plusSeconds(3600)), {}, {}, {}) } }.toTypedArray())
    }

    @Test
    fun sessionListInEveryConnectionState() {
        val states = listOf(ConnState.Online, ConnState.Connecting, ConnState.Reconnecting(2, null), ConnState.Failed(IllegalStateException("no running agent host")))
        checkEach(
            *states.map { connection ->
                @Composable {
                    SessionsScreen(host, connection, sessions, now, true, rememberLazyListState(), {}, {}, {}, monitorEnabled = true, notificationsAllowed = false)
                }
            }.toTypedArray(),
        )
    }

    @Test
    fun sessionDetailWithAQuestionAndWithout() {
        checkEach(
            { SessionDetailScreen(detail(), null, ConnState.Online, now, {}) },
            { SessionDetailScreen(null, null, ConnState.Online, now, {}) },
            { SessionDetailScreen(null, IllegalStateException("x"), ConnState.Reconnecting(1, null), now, {}) },
        )
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
