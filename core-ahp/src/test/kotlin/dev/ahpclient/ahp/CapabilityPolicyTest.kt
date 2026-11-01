package dev.ahpclient.ahp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CapabilityPolicyTest {
    private val allowed = setOf("initialize", "reconnect", "ping", "subscribe", "unsubscribe", "listSessions", "fetchTurns", "resourceRead")

    private val interactActions = setOf(
        "chat/turnStarted", "chat/pendingMessageSet", "chat/pendingMessageRemoved", "chat/queuedMessagesReordered",
        "chat/inputAnswerChanged", "chat/inputCompleted", "chat/toolCallConfirmed", "chat/toolCallResultConfirmed",
        "chat/turnCancelled", "chat/turnResume", "chat/truncated", "chat/configChanged",
    )

    // Everything the spec's Design Notes name as denied, plus the rest of the protocol surface.
    private val denied = listOf(
        "dispatchAction", "authenticate",
        "resourceWrite", "resourceList", "resourceCopy", "resourceDelete", "resourceMove", "resourceMkdir", "resourceRequest", "resourceResolve",
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
    fun `READ cannot dispatch and INTERACT can`() {
        assertEquals(false, CapabilityPolicy.READ.canDispatch)
        assertEquals(true, CapabilityPolicy.INTERACT.canDispatch)
    }

    @Test
    fun `INTERACT allows every READ method`() {
        allowed.forEach { CapabilityPolicy.INTERACT.check(it) }
    }

    @Test
    fun `INTERACT allows exactly the nine interaction action types`() {
        interactActions.forEach { CapabilityPolicy.INTERACT.check("dispatchAction", it) }
    }

    @Test
    fun `only INTERACT and MANAGE may set a chat's own setting, and READ never`() {
        CapabilityPolicy.INTERACT.check("dispatchAction", "chat/configChanged")
        CapabilityPolicy.MANAGE.check("dispatchAction", "chat/configChanged")
        assertFailsWith<CapabilityDeniedException> { CapabilityPolicy.READ.check("dispatchAction", "chat/configChanged") }
        // The neighbours are not allowed: the session's own setting, and a made-up one.
        assertFailsWith<CapabilityDeniedException> { CapabilityPolicy.INTERACT.check("dispatchAction", "session/configChanged") }
        assertFailsWith<CapabilityDeniedException> { CapabilityPolicy.MANAGE.check("dispatchAction", "chat/configChanged ") }
    }

    @Test
    fun `INTERACT denies every other action type and a missing one`() {
        val notAllowed = listOf(
            null, "", "chat/turnStarted ", "Chat/turnStarted",
            "terminal/input", "terminal/resized", "terminal/claimed",
            "session/activeClientSet", "session/titleChanged", "session/isReadChanged", "session/isArchivedChanged", "changeset/filesReviewChanged",
            "session/mcpServerStarted", "session/mcpServerStopped", "session/customizationToggled",
            "chat/titleChanged", "chat/responsePart", "chat/toolCallStart", "chat/turnComplete",
            "root/activeSessionsChanged",
        )
        notAllowed.forEach { type ->
            val error = assertFailsWith<CapabilityDeniedException>("$type") { CapabilityPolicy.INTERACT.check("dispatchAction", type) }
            assertEquals("dispatchAction", error.method)
        }
    }

    @Test
    fun `INTERACT denies every method that is neither READ nor dispatchAction`() {
        denied.filter { it != "dispatchAction" }.forEach { method ->
            assertFailsWith<CapabilityDeniedException>(method) { CapabilityPolicy.INTERACT.check(method) }
            // An allowed action type must not smuggle a denied method through.
            assertFailsWith<CapabilityDeniedException>(method) { CapabilityPolicy.INTERACT.check(method, "chat/turnStarted") }
        }
    }

    @Test
    fun `the denial names the method without leaking params`() {
        val message = assertFailsWith<CapabilityDeniedException> { CapabilityPolicy.READ.check("resourceWrite") }.message!!

        assertTrue(message.contains("resourceWrite"))
    }

    private val manageCommands = setOf("createSession", "createChat", "disposeSession", "disposeChat", "invokeChangesetOperation")

    private val manageQueries = setOf("resolveSessionConfig")

    @Test
    fun `only MANAGE can ask what settings a new session would have, and nothing else of that kind`() {
        assertEquals(manageQueries, CapabilityPolicy.MANAGE_QUERIES)
        manageQueries.forEach { query ->
            assertFailsWith<CapabilityDeniedException>(query) { CapabilityPolicy.READ.check(query) }
            assertFailsWith<CapabilityDeniedException>(query) { CapabilityPolicy.INTERACT.check(query) }
            CapabilityPolicy.MANAGE.check(query)
        }
        // The neighbouring question, completing a setting as you type, is not allowed.
        assertFailsWith<CapabilityDeniedException> { CapabilityPolicy.MANAGE.check("sessionConfigCompletions") }
    }

    private val manageActions = setOf(
        "session/isReadChanged",
        "session/isArchivedChanged",
        "session/titleChanged",
        "chat/isReadChanged",
        "chat/isArchivedChanged",
        "session/mcpServerStartRequested",
        "session/mcpServerStopRequested",
        "session/mcpServerBackgroundRequested",
        "changeset/filesReviewChanged",
    )

    @Test
    fun `only MANAGE can create or remove sessions and chats`() {
        assertEquals(false, CapabilityPolicy.READ.canManage)
        assertEquals(false, CapabilityPolicy.INTERACT.canManage)
        assertEquals(true, CapabilityPolicy.MANAGE.canManage)
        assertEquals(true, CapabilityPolicy.MANAGE.canDispatch)
        manageCommands.forEach { command ->
            assertFailsWith<CapabilityDeniedException>(command) { CapabilityPolicy.READ.check(command) }
            assertFailsWith<CapabilityDeniedException>(command) { CapabilityPolicy.INTERACT.check(command) }
            CapabilityPolicy.MANAGE.check(command)
        }
    }

    @Test
    fun `MANAGE allows every READ method`() {
        allowed.forEach { CapabilityPolicy.MANAGE.check(it) }
    }

    @Test
    fun `MANAGE allows the nine interaction actions and exactly three more`() {
        (interactActions + manageActions).forEach { CapabilityPolicy.MANAGE.check("dispatchAction", it) }
        assertEquals(manageActions, CapabilityPolicy.MANAGE_ACTIONS)
        assertEquals(manageCommands, CapabilityPolicy.MANAGE_COMMANDS)
    }

    @Test
    fun `INTERACT and READ still refuse the management actions`() {
        manageActions.forEach { type ->
            assertFailsWith<CapabilityDeniedException>(type) { CapabilityPolicy.INTERACT.check("dispatchAction", type) }
            assertFailsWith<CapabilityDeniedException>(type) { CapabilityPolicy.READ.check("dispatchAction", type) }
        }
    }

    @Test
    fun `MANAGE denies every other action type and a missing one`() {
        val notAllowed = listOf(
            null, "", "session/isReadChanged ", "Session/isReadChanged",
            "terminal/input", "terminal/resized", "terminal/claimed",
            "session/activeClientSet", "session/chatAdded", "session/chatRemoved", "session/workingDirectorySet",
            "session/mcpServerStarted", "session/mcpServerStopped", "session/customizationToggled",
            "chat/titleChanged", "chat/responsePart", "chat/toolCallStart", "chat/turnComplete",
            "root/activeSessionsChanged",
        )
        notAllowed.forEach { type ->
            assertFailsWith<CapabilityDeniedException>("$type") { CapabilityPolicy.MANAGE.check("dispatchAction", type) }
        }
    }

    @Test
    fun `MANAGE denies every other method`() {
        denied.filter { it != "dispatchAction" && it !in manageCommands && it !in manageQueries }.forEach { method ->
            assertFailsWith<CapabilityDeniedException>(method) { CapabilityPolicy.MANAGE.check(method) }
            assertFailsWith<CapabilityDeniedException>(method) { CapabilityPolicy.MANAGE.check(method, "session/isReadChanged") }
        }
    }
}
