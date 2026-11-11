package dev.ahpclient.tunnel

import com.microsoft.agenthostprotocol.generated.Implementation
import com.microsoft.agenthostprotocol.generated.ResponsePartToolCall
import com.microsoft.agenthostprotocol.generated.ToolCallStateCompleted
import com.microsoft.agenthostprotocol.generated.ToolCallStatePendingConfirmation
import com.microsoft.agenthostprotocol.generated.TurnState
import dev.ahpclient.ahp.CapabilityPolicy
import dev.ahpclient.direct.DirectHost
import dev.ahpclient.state.ChangesetDiffs
import dev.ahpclient.state.ChangesetView
import dev.ahpclient.state.ChatInteractor
import dev.ahpclient.state.ConnState
import dev.ahpclient.state.DiffResult
import dev.ahpclient.state.DirectHostConnector
import dev.ahpclient.state.DispatchOutcome
import dev.ahpclient.state.HostSession
import dev.ahpclient.state.InteractResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DemoAhpHostTest {

    @Test
    fun `demo workspace connects, loads sessions, transcripts, diffs, and handles tool approval`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val failFallback = SocketOpener { url, _ -> error("Unexpected network call to $url") }
            val opener = DemoSocketOpener(failFallback)
            val demoDirectHost = DirectHost(
                id = "${DirectHost.ID_PREFIX}demo",
                name = DemoAhpHost.NAME,
                url = DemoAhpHost.URL,
            )
            val connector = DirectHostConnector(
                opener = opener,
                host = demoDirectHost,
                token = { null },
                policy = { CapabilityPolicy.MANAGE },
            )
            val session = HostSession(
                connector = connector,
                scope = scope,
                clientInfo = Implementation(name = "ahp-test-client", version = "0.2.0"),
            )
            session.start()

            val conn = withTimeout(5_000) {
                session.connection.first { it !is ConnState.Stopped && it !is ConnState.Connecting }
            }
            assertEquals(ConnState.Online, conn, "Expected Online but got $conn")

            val listed = withTimeout(5_000) {
                session.root.sessions.first { it.isNotEmpty() }
            }
            assertEquals(3, listed.size)
            assertEquals(DemoAhpHost.PRIMARY_SESSION_URI, listed.first().resource)

            val sessionStore = session.openSession(DemoAhpHost.PRIMARY_SESSION_URI)
            val sessionState = withTimeout(5_000) {
                sessionStore.state.first { it != null }!!
            }
            assertEquals("ahp-chat://default/main", sessionState.defaultChat)
            assertEquals(3, sessionState.chats.size)

            // Verify main chat transcript
            val mainChat = session.openChat("ahp-chat://default/main")
            val mainChatState = withTimeout(5_000) {
                mainChat.state.first { it != null }!!
            }
            assertEquals("Main chat", mainChatState.title)
            assertEquals(1, mainChatState.turns.size)

            // Verify forked chat waiting on tool approval in activeTurn
            val forkChat = session.openChat("ahp-chat://default/fork-mig")
            val forkBefore = withTimeout(5_000) {
                forkChat.state.first { it != null }!!
            }
            val pendingTurn = forkBefore.activeTurn
            assertNotNull(pendingTurn, "Expected activeTurn in fork-mig")
            val pendingToolCall = pendingTurn.responseParts
                .filterIsInstance<ResponsePartToolCall>()
                .firstOrNull()?.value?.toolCall
            assertNotNull(pendingToolCall, "Expected pending tool call in fork-mig")
            assertTrue(pendingToolCall is ToolCallStatePendingConfirmation)

            // Approve tool call via ChatInteractor and verify state updates to completed
            val interactor = ChatInteractor(
                chatUri = "ahp-chat://default/fork-mig",
                chat = { forkChat.state.value },
                canInteract = { session.canInteract.value },
                dispatch = session::dispatch,
                gate = { true },
            )
            val decideResult = interactor.approve("tc-approve-mig")
            assertEquals(InteractResult.Sent(DispatchOutcome.Accepted), decideResult)

            val forkAfter = withTimeout(5_000) {
                forkChat.state.first { state ->
                    state?.turns?.lastOrNull()?.responseParts
                        ?.filterIsInstance<ResponsePartToolCall>()
                        ?.firstOrNull()?.value?.toolCall is ToolCallStateCompleted
                }!!
            }
            assertEquals(TurnState.COMPLETE, forkAfter.turns.last().state)

            // Verify changeset + file diff loading via resourceRead
            val changesetStore = session.openChangeset("ahp-changeset:/demo-uncommitted")
            val changesetState = withTimeout(5_000) {
                changesetStore.state.first { it != null }!!
            }
            val view = ChangesetView.from(changesetState)
            assertEquals(2, view.rows.size)

            val diffs = ChangesetDiffs(
                files = { changesetStore.state.value?.files.orEmpty() },
                read = session::readResource,
            )
            val diffResult = diffs.load(view.rows.first().id)
            assertTrue(diffResult is DiffResult.Ready, "Expected DiffResult.Ready but got $diffResult")
            assertTrue(diffResult.diff.hunks.isNotEmpty())

            session.stop()
        } finally {
            scope.cancel()
        }
    }
}
