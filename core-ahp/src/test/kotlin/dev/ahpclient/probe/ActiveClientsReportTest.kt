package dev.ahpclient.probe

import dev.ahpclient.state.fixture
import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.ListSessionsResult
import com.microsoft.agenthostprotocol.generated.SessionState
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActiveClientsReportTest {
    private fun state(activeClients: String): SessionState = Ahp.json.decodeFromString(
        SessionState.serializer(),
        """{"provider":"copilotcli","title":"t","status":1,"lifecycle":"ready","activeClients":$activeClients,"chats":[]}""",
    )

    private val sessions = Ahp.json.decodeFromString(ListSessionsResult.serializer(), fixture("live/list-sessions.json")).items

    @Test
    fun `no active clients means this client is not listed`() {
        val report = ActiveClientsReport.from(state("[]"), "me")

        assertEquals(ActiveClientsReport(0, emptyList(), selfListed = false), report)
    }

    @Test
    fun `other clients are counted and named, and this client stays unlisted`() {
        val report = ActiveClientsReport.from(
            state("""[{"clientId":"vscode-1","displayName":"VS Code","tools":[]},{"clientId":"other","displayName":"","tools":[]}]"""),
            "me",
        )

        assertEquals(2, report.count)
        assertEquals(listOf("VS Code", "unnamed"), report.names)
        assertFalse(report.selfListed)
    }

    @Test
    fun `this client being listed is detected by client id`() {
        val report = ActiveClientsReport.from(state("""[{"clientId":"vscode-1","tools":[]},{"clientId":"me","displayName":"probe","tools":[]}]"""), "me")

        assertTrue(report.selfListed)
    }

    @Test
    fun `an exact resource wins over a title match`() {
        val byResource = sessions.first { it.title == "Session C" }

        assertEquals(byResource, ActiveClientsReport.pick(sessions, byResource.resource))
    }

    @Test
    fun `a title match is case-insensitive and picks the most recently modified`() {
        val picked = ActiveClientsReport.pick(sessions, "session")!!

        assertEquals(sessions.maxOf { it.modifiedAt }, picked.modifiedAt)
        assertEquals("Session F (synthetic: error)", picked.title)
        assertEquals("Session B", ActiveClientsReport.pick(sessions, "SESSION b")!!.title)
    }

    @Test
    fun `no match gives null`() {
        assertNull(ActiveClientsReport.pick(sessions, "nothing like this"))
    }
}

class ProbeArgsTest {
    @Test
    fun `the session option is parsed`() {
        assertEquals("greeting", ProbeArgs.parse(arrayOf("--session", "greeting")).session)
        assertNull(ProbeArgs.parse(emptyArray()).session)
    }

    @Test
    fun `options combine`() {
        val parsed = ProbeArgs.parse(arrayOf("--tunnel", "box", "--watch", "5", "--session", "x", "--capture", "a.jsonl"))

        assertEquals("box", parsed.tunnel)
        assertEquals(5, parsed.watchSeconds)
        assertEquals("x", parsed.session)
        assertEquals(Paths.get("a.jsonl"), parsed.capture)
    }

    @Test
    fun `a missing value or unknown option is rejected with the usage line`() {
        assertTrue(assertFailsWith<IllegalArgumentException> { ProbeArgs.parse(arrayOf("--session")) }.message!!.contains("usage: probe"))
        assertTrue(assertFailsWith<IllegalArgumentException> { ProbeArgs.parse(arrayOf("--bogus")) }.message!!.contains("unknown option"))
    }
}
