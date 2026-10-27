package dev.ahpclient.app

import com.microsoft.agenthostprotocol.generated.Implementation
import dev.ahpclient.state.HostConnector
import dev.ahpclient.state.HostSession
import dev.ahpclient.tunnel.TunnelInfo
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

class WatchdogDecisionTest {
    private val scope = CoroutineScope(Job())
    private val session = HostSession(
        connector = HostConnector { _, _, _ -> error("never connected") },
        scope = scope,
        clientInfo = Implementation(name = "test", version = "0"),
    )

    @AfterTest
    fun stopSession() {
        scope.cancel()
    }

    /** Each state, and whether the check warns about it when monitoring is on and the app is out of sight. A process started only for the check is still restoring. */
    private val warnedAbout = listOf(
        "restoring" to (AppState.Restoring to true),
        "signed out" to (AppState.SignedOut(SignInStep.Idle, null) to true),
        "host list" to (AppState.Hosts(HostList.Loading, null) to true),
        "connected" to (AppState.Connected(TunnelInfo("t", "usw2", "dev-box", 6, 1, emptyList()), session) to false),
    )

    @Test
    fun `with monitoring on and the app out of sight it warns unless this process has a live session`() {
        for ((name, case) in warnedAbout) {
            assertEquals(case.second, WatchdogDecision.shouldWarn(monitoringEnabled = true, state = case.first, appInFront = false), name)
        }
    }

    @Test
    fun `with the app in front it never warns, whatever the state`() {
        for ((name, case) in warnedAbout) {
            assertEquals(false, WatchdogDecision.shouldWarn(monitoringEnabled = true, state = case.first, appInFront = true), name)
        }
    }

    @Test
    fun `with monitoring off it never warns`() {
        for ((name, case) in warnedAbout) {
            assertEquals(false, WatchdogDecision.shouldWarn(monitoringEnabled = false, state = case.first, appInFront = false), name)
        }
    }
}
