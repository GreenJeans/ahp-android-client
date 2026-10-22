package dev.ahpclient.ahp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CapabilityPolicyTest {
    private val allowed = setOf("initialize", "reconnect", "ping", "subscribe", "unsubscribe", "listSessions", "fetchTurns")

    // Everything the spec's Design Notes name as denied, plus the rest of the protocol surface.
    private val denied = listOf(
        "dispatchAction", "authenticate",
        "resourceRead", "resourceWrite", "resourceList", "resourceCopy", "resourceDelete", "resourceMove", "resourceMkdir", "resourceRequest", "resourceResolve",
        "createSession", "disposeSession", "createChat", "disposeChat", "createTerminal", "disposeTerminal",
        "invokeChangesetOperation", "createResourceWatch", "completions", "resolveSessionConfig", "sessionConfigCompletions",
        "runAutomation", "fetchAutomationRuns", "listAutomationTriggerDefinitions", "channel/credit", "tcp/data", "", "Initialize",
    )

    @Test
    fun `READ allows exactly the observing calls`() {
        allowed.forEach { CapabilityPolicy.READ.check(it) }
    }

    @Test
    fun `READ denies everything else`() {
        denied.forEach { method ->
            val error = assertFailsWith<CapabilityDeniedException>(method) { CapabilityPolicy.READ.check(method) }
            assertEquals(method, error.method)
        }
    }

    @Test
    fun `READ denies dispatchAction whatever the action type`() {
        listOf(null, "chat/turnStarted", "session/isReadChanged", "chat/toolCallConfirmed", "terminal/input").forEach { type ->
            assertFailsWith<CapabilityDeniedException>("$type") { CapabilityPolicy.READ.check("dispatchAction", type) }
        }
    }

    @Test
    fun `the denial names the method without leaking params`() {
        val message = assertFailsWith<CapabilityDeniedException> { CapabilityPolicy.READ.check("resourceWrite") }.message!!

        assertTrue(message.contains("resourceWrite"))
    }
}
