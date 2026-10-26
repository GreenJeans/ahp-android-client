package dev.ahpclient.ahp

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.Implementation
import com.microsoft.agenthostprotocol.generated.SessionStatus
import com.microsoft.agenthostprotocol.generated.StateAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

@OptIn(ExperimentalCoroutinesApi::class)
class AhpConnectionTest {
    private val clientInfo = Implementation(name = "ahp-android-client", version = "0.1.0")

    private val initResult = """{"protocolVersion":"0.9.0","serverSeq":43087,"snapshots":[]}"""

    private fun TestScope.connection(
        socket: FakeSocket,
        timeoutMs: Long = 30_000,
        serverSeq: Long = 0,
        policy: CapabilityPolicy = CapabilityPolicy.READ,
    ) = AhpConnection(socket, backgroundScope, policy, clientId = "client-1", initialServerSeq = serverSeq, requestTimeoutMs = timeoutMs)

    private fun stateAction(json: String): StateAction = Ahp.json.decodeFromString(StateAction.serializer(), json)

    private val cancelTurn get() = stateAction("""{"type":"chat/turnCancelled","turnId":"turn-1","duration":5}""")

    private val renameSession get() = stateAction("""{"type":"session/titleChanged","title":"renamed"}""")

    private fun action(seq: Long, title: String = "t") =
        """{"jsonrpc":"2.0","method":"action","params":{"channel":"ahp-session:/abc","action":{"type":"session/titleChanged","title":"$title"},"serverSeq":$seq}}"""

    @Test
    fun `initialize offers both protocol versions and subscribes to the root channel`() = runTest {
        val socket = FakeSocket { method, id, _ -> if (method == "initialize") result(id, initResult) else null }
        val connection = connection(socket)

        val result = connection.initialize(clientInfo)

        val frame = socket.sent.single()
        assertEquals("2.0", frame["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals(1L, frame["id"]!!.jsonPrimitive.long)
        assertEquals("initialize", frame["method"]!!.jsonPrimitive.content)
        val params = frame["params"]!!.jsonObject
        assertEquals("ahp-root://", params["channel"]!!.jsonPrimitive.content)
        assertEquals(listOf("1.0.0", "0.9.0"), params["protocolVersions"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("client-1", params["clientId"]!!.jsonPrimitive.content)
        assertEquals("ahp-android-client", params["clientInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(listOf("ahp-root://"), params["initialSubscriptions"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("0.9.0", result.protocolVersion)
        assertEquals(43087L, connection.lastServerSeq)
    }

    @Test
    fun `listSessions sends paging params and decodes status as a bitset`() = runTest {
        val socket = FakeSocket { method, id, _ ->
            if (method == "listSessions") result(id, """{"items":[{"provider":"copilot","title":"Fix bug","status":24,"resource":"ahp-session:/a","createdAt":"2026-10-22T10:00:00.000Z","modifiedAt":"2026-10-22T10:05:00.000Z"}],"nextCursor":"c2"}""") else null
        }

        val page = connection(socket).listSessions(cursor = "c1", limit = 50)

        val params = socket.sent.single()["params"]!!.jsonObject
        assertEquals("ahp-root://", params["channel"]!!.jsonPrimitive.content)
        assertEquals("c1", params["cursor"]!!.jsonPrimitive.content)
        assertEquals(50L, params["limit"]!!.jsonPrimitive.long)
        assertEquals("c2", page.nextCursor)
        val status = page.items.single().status
        assertTrue(SessionStatus.INPUT_NEEDED in status)
        assertTrue(SessionStatus.IN_PROGRESS in status, "InputNeeded includes InProgress")
        assertFalse(SessionStatus.ERROR in status)
    }

    @Test
    fun `optional params are omitted when unset`() = runTest {
        val socket = FakeSocket { method, id, _ -> if (method == "listSessions") result(id, """{"items":[]}""") else null }

        connection(socket).listSessions()

        val params = socket.sent.single()["params"]!!.jsonObject
        assertEquals(setOf("channel"), params.keys)
    }

    @Test
    fun `subscribe and fetchTurns target the given channel`() = runTest {
        val socket = FakeSocket { method, id, _ -> if (method == "subscribe" || method == "fetchTurns") result(id, "{}") else null }
        val connection = connection(socket)

        connection.subscribe("ahp-session:/abc")
        connection.fetchTurns("ahp-chat:/abc", cursor = "older")

        assertEquals("ahp-session:/abc", socket.sent[0]["params"]!!.jsonObject["channel"]!!.jsonPrimitive.content)
        val fetch = socket.sent[1]["params"]!!.jsonObject
        assertEquals("ahp-chat:/abc", fetch["channel"]!!.jsonPrimitive.content)
        assertEquals("older", fetch["cursor"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unsubscribe is a notification without an id`() = runTest {
        val socket = FakeSocket()

        connection(socket).unsubscribe("ahp-session:/abc")

        val frame = socket.sent.single()
        assertEquals("unsubscribe", frame["method"]!!.jsonPrimitive.content)
        assertNull(frame["id"])
        assertEquals("ahp-session:/abc", frame["params"]!!.jsonObject["channel"]!!.jsonPrimitive.content)
    }

    @Test
    fun `dispatchAction is a notification carrying channel, the caller's clientSeq and the action`() = runTest {
        val socket = FakeSocket()
        val connection = connection(socket, policy = CapabilityPolicy.INTERACT)

        connection.dispatchAction("ahp-chat:/a", cancelTurn, clientSeq = 41)
        connection.dispatchAction("ahp-chat:/b", cancelTurn, clientSeq = 42)

        val frame = socket.sent[0]
        assertEquals("2.0", frame["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals("dispatchAction", frame["method"]!!.jsonPrimitive.content)
        assertNull(frame["id"], "a notification has no id")
        val params = frame["params"]!!.jsonObject
        assertEquals("ahp-chat:/a", params["channel"]!!.jsonPrimitive.content)
        assertEquals(41L, params["clientSeq"]!!.jsonPrimitive.long)
        val action = params["action"]!!.jsonObject
        assertEquals("chat/turnCancelled", action["type"]!!.jsonPrimitive.content)
        assertEquals("turn-1", action["turnId"]!!.jsonPrimitive.content)
        assertEquals(5L, action["duration"]!!.jsonPrimitive.long)
        assertEquals(42L, socket.sent[1]["params"]!!.jsonObject["clientSeq"]!!.jsonPrimitive.long)
    }

    private fun configChange(json: String) = stateAction(json)

    @Test
    fun `a chat's setting is sent as one allowed property and word, and only then`() = runTest {
        val socket = FakeSocket()
        val connection = connection(socket, policy = CapabilityPolicy.INTERACT)

        connection.dispatchAction("ahp-chat:/a", configChange("""{"type":"chat/configChanged","config":{"mode":"plan"}}"""), clientSeq = 7)

        val action = socket.sent.single()["params"]!!.jsonObject["action"]!!.jsonObject
        assertEquals("chat/configChanged", action["type"]!!.jsonPrimitive.content)
        assertEquals("plan", action["config"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a chat's setting outside the allowed properties and words is refused before anything is sent`() = runTest {
        val socket = FakeSocket()
        val connection = connection(socket, policy = CapabilityPolicy.INTERACT)

        listOf(
            """{"type":"chat/configChanged","config":{"permissions":{"allow":["rm -rf"]}}}""",
            """{"type":"chat/configChanged","config":{"sandboxEnabled":"off"}}""",
            """{"type":"chat/configChanged","config":{"mode":"plan","autoApprove":"autoApprove"}}""",
            """{"type":"chat/configChanged","config":{"autoApprove":"bypass"}}""",
            """{"type":"chat/configChanged","config":{"mode":"plan"},"replace":true}""",
            """{"type":"chat/configChanged","config":{}}""",
        ).forEach { json ->
            val error = assertFailsWith<CapabilityDeniedException>(json) { connection.dispatchAction("ahp-chat:/a", configChange(json), clientSeq = 1) }
            assertEquals("chat/configChanged", error.actionType)
        }
        assertTrue(socket.sent.isEmpty(), "nothing left the device")
    }

    @Test
    fun `the READ tier refuses a chat's setting too`() = runTest {
        val socket = FakeSocket()

        assertFailsWith<CapabilityDeniedException> {
            connection(socket).dispatchAction("ahp-chat:/a", configChange("""{"type":"chat/configChanged","config":{"mode":"plan"}}"""), clientSeq = 1)
        }

        assertTrue(socket.sent.isEmpty())
    }

    @Test
    fun `the READ tier refuses to dispatch and sends nothing`() = runTest {
        val socket = FakeSocket()

        val error = assertFailsWith<CapabilityDeniedException> { connection(socket).dispatchAction("ahp-chat:/a", cancelTurn, clientSeq = 1) }

        assertEquals("dispatchAction", error.method)
        assertTrue(socket.sent.isEmpty())
    }

    @Test
    fun `the INTERACT tier refuses an action type it does not list and sends nothing`() = runTest {
        val socket = FakeSocket()
        val connection = connection(socket, policy = CapabilityPolicy.INTERACT)

        val error = assertFailsWith<CapabilityDeniedException> { connection.dispatchAction("ahp-session:/a", renameSession, clientSeq = 1) }

        assertEquals("session/titleChanged", error.actionType)
        assertTrue(socket.sent.isEmpty())
    }

    @Test
    fun `MANAGE sends each management command as a request with the params the protocol defines`() = runTest {
        val socket = FakeSocket { method, id, _ -> if (method in CapabilityPolicy.MANAGE_COMMANDS) result(id, "null") else null }
        val connection = connection(socket, policy = CapabilityPolicy.MANAGE)

        connection.createSession("ahp-session:/new", provider = "copilotcli", workingDirectories = listOf("file:///work/repo"))
        connection.createChat("ahp-session:/new", "ahp-chat:/c1")
        connection.disposeChat("ahp-chat:/c1")
        connection.disposeSession("ahp-session:/new")

        assertEquals(listOf("createSession", "createChat", "disposeChat", "disposeSession"), socket.sent.map { it["method"]!!.jsonPrimitive.content })
        assertTrue(socket.sent.all { it["id"] != null }, "commands are requests, so the host's refusal can be read")
        val session = socket.sent[0]["params"]!!.jsonObject
        assertEquals("ahp-session:/new", session["channel"]!!.jsonPrimitive.content)
        assertEquals("copilotcli", session["provider"]!!.jsonPrimitive.content)
        assertEquals(listOf("file:///work/repo"), session["workingDirectories"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertNull(session["activeClient"], "the app never claims the active client role")
        assertNull(session["config"])
        val chat = socket.sent[1]["params"]!!.jsonObject
        assertEquals("ahp-session:/new", chat["channel"]!!.jsonPrimitive.content)
        assertEquals("ahp-chat:/c1", chat["chat"]!!.jsonPrimitive.content)
        assertNull(chat["initialMessage"])
        assertEquals("ahp-chat:/c1", socket.sent[2]["params"]!!.jsonObject["channel"]!!.jsonPrimitive.content)
        assertEquals("ahp-session:/new", socket.sent[3]["params"]!!.jsonObject["channel"]!!.jsonPrimitive.content)
    }

    @Test
    fun `resourceRead asks for text on the root channel and decodes the answer, at every tier`() = runTest {
        for (policy in listOf(CapabilityPolicy.READ, CapabilityPolicy.INTERACT, CapabilityPolicy.MANAGE)) {
            val socket = FakeSocket { method, id, _ -> if (method == "resourceRead") result(id, """{"data":"hello","encoding":"utf-8","contentType":"text/plain"}""") else null }
            val connection = connection(socket, policy = policy)

            val read = connection.resourceRead("ahp-content:/a")

            assertEquals("hello", read.data)
            assertEquals(com.microsoft.agenthostprotocol.generated.ContentEncoding.UTF8, read.encoding)
            val frame = socket.sent.single()
            assertEquals("resourceRead", frame["method"]!!.jsonPrimitive.content)
            assertTrue(frame["id"] != null, "a read is a request, so the host's refusal can be read")
            val params = frame["params"]!!.jsonObject
            assertEquals(setOf("channel", "uri", "encoding"), params.keys)
            assertEquals("ahp-root://", params["channel"]!!.jsonPrimitive.content)
            assertEquals("ahp-content:/a", params["uri"]!!.jsonPrimitive.content)
            assertEquals("utf-8", params["encoding"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `resolveSessionConfig asks the root channel for an agent and folder with the choices so far, and decodes the schema`() = runTest {
        val answer = """{"schema":{"type":"object","properties":{"thinking":{"type":"string","title":"Thinking","enum":["low","high"]}}},"values":{"thinking":"low"}}"""
        val socket = FakeSocket { method, id, _ -> if (method == "resolveSessionConfig") result(id, answer) else null }
        val connection = connection(socket, policy = CapabilityPolicy.MANAGE)

        val resolved = connection.resolveSessionConfig("claude", "file:///work/repo", mapOf("thinking" to kotlinx.serialization.json.JsonPrimitive("high")))

        assertEquals(listOf("thinking"), resolved.schema.properties.keys.toList())
        assertEquals("low", resolved.values.getValue("thinking").jsonPrimitive.content)
        val frame = socket.sent.single()
        assertEquals("resolveSessionConfig", frame["method"]!!.jsonPrimitive.content)
        assertTrue(frame["id"] != null, "a question is a request, so the answer and any refusal can be read")
        val params = frame["params"]!!.jsonObject
        assertEquals(setOf("channel", "provider", "workingDirectory", "config"), params.keys)
        assertEquals("ahp-root://", params["channel"]!!.jsonPrimitive.content)
        assertEquals("claude", params["provider"]!!.jsonPrimitive.content)
        assertEquals("file:///work/repo", params["workingDirectory"]!!.jsonPrimitive.content)
        assertEquals("high", params["config"]!!.jsonObject["thinking"]!!.jsonPrimitive.content)
    }

    @Test
    fun `resolveSessionConfig with no choices sends no config, and createSession carries the config it is given`() = runTest {
        val socket = FakeSocket { method, id, _ ->
            when (method) {
                "resolveSessionConfig" -> result(id, """{"schema":{"type":"object","properties":{}},"values":{}}""")
                "createSession" -> result(id, "null")
                else -> null
            }
        }
        val connection = connection(socket, policy = CapabilityPolicy.MANAGE)

        connection.resolveSessionConfig("claude", "file:///work/repo", null)
        connection.createSession("ahp-session:/new", "claude", listOf("file:///work/repo"), mapOf("thinking" to kotlinx.serialization.json.JsonPrimitive("high")))

        assertNull(socket.sent[0]["params"]!!.jsonObject["config"])
        assertEquals("high", socket.sent[1]["params"]!!.jsonObject["config"]!!.jsonObject["thinking"]!!.jsonPrimitive.content)
    }

    @Test
    fun `READ and INTERACT refuse to ask for a new session's settings and send nothing`() = runTest {
        for (policy in listOf(CapabilityPolicy.READ, CapabilityPolicy.INTERACT)) {
            val socket = FakeSocket()
            val connection = connection(socket, policy = policy)

            assertFailsWith<CapabilityDeniedException> { connection.resolveSessionConfig("claude", "file:///work/repo", null) }

            assertTrue(socket.sent.isEmpty())
        }
    }

    @Test
    fun `READ and INTERACT refuse every management command and send nothing`() = runTest {
        for (policy in listOf(CapabilityPolicy.READ, CapabilityPolicy.INTERACT)) {
            val socket = FakeSocket()
            val connection = connection(socket, policy = policy)

            assertFailsWith<CapabilityDeniedException> { connection.createSession("ahp-session:/new", null, null) }
            assertFailsWith<CapabilityDeniedException> { connection.createChat("ahp-session:/new", "ahp-chat:/c1") }
            assertFailsWith<CapabilityDeniedException> { connection.disposeChat("ahp-chat:/c1") }
            assertFailsWith<CapabilityDeniedException> { connection.disposeSession("ahp-session:/new") }

            assertTrue(socket.sent.isEmpty(), policy.name)
            assertEquals(false, connection.canManage)
        }
    }

    @Test
    fun `the host's refusal of a management command is an Rpc error`() = runTest {
        val socket = FakeSocket { method, id, _ -> if (method == "createSession") error(id, -32003, "already exists") else null }
        val connection = connection(socket, policy = CapabilityPolicy.MANAGE)

        val failure = assertFailsWith<AhpException.Rpc> { connection.createSession("ahp-session:/dup", null, null) }

        assertEquals(-32003, failure.code)
        assertEquals(true, connection.canManage)
    }

    @Test
    fun `MANAGE may mark a session read or archived and still refuses a rename`() = runTest {
        val socket = FakeSocket()
        val connection = connection(socket, policy = CapabilityPolicy.MANAGE)

        connection.dispatchAction("ahp-session:/a", stateAction("""{"type":"session/isReadChanged","isRead":true}"""), clientSeq = 1)
        connection.dispatchAction("ahp-session:/a", stateAction("""{"type":"session/isArchivedChanged","isArchived":true}"""), clientSeq = 2)
        assertFailsWith<CapabilityDeniedException> { connection.dispatchAction("ahp-session:/a", renameSession, clientSeq = 3) }

        assertEquals(listOf("session/isReadChanged", "session/isArchivedChanged"), socket.sent.map { it["params"]!!.jsonObject["action"]!!.jsonObject["type"]!!.jsonPrimitive.content })
    }

    @Test
    fun `dispatching on a closed socket fails instead of reporting success`() = runTest {
        val socket = FakeSocket()
        val connection = connection(socket, policy = CapabilityPolicy.INTERACT)
        socket.serverCloses()
        runCurrent()

        assertFailsWith<AhpException.ConnectionClosed> { connection.dispatchAction("ahp-chat:/a", cancelTurn, clientSeq = 1) }

        assertTrue(socket.sent.isEmpty())
    }

    @Test
    fun `an echo of our own action is decoded with its origin and a rejection with its reason`() = runTest {
        val socket = FakeSocket()
        val connection = connection(socket, policy = CapabilityPolicy.INTERACT)
        connection.dispatchAction("ahp-chat:/a", cancelTurn, clientSeq = 1)
        socket.serverSends(
            """{"jsonrpc":"2.0","method":"action","params":{"channel":"ahp-chat:/a","action":{"type":"chat/turnCancelled","turnId":"turn-1","duration":5},"serverSeq":7,"origin":{"clientId":"client-1","clientSeq":1}}}""",
        )
        socket.serverSends(
            """{"jsonrpc":"2.0","method":"action","params":{"channel":"ahp-chat:/a","action":{"type":"chat/turnCancelled","turnId":"turn-2","duration":5},"serverSeq":8,"origin":{"clientId":"client-1","clientSeq":2},"rejectionReason":"turn is not active"}}""",
        )

        val (accepted, rejected) = connection.notifications.take(2).toList().map { assertIs<ServerNotification.Action>(it).envelope }

        assertEquals("client-1", accepted.origin!!.clientId)
        assertEquals(1L, accepted.origin!!.clientSeq)
        assertNull(accepted.rejectionReason)
        assertEquals("turn is not active", rejected.rejectionReason)
    }

    @Test
    fun `a rejected action does not move the resume point`() = runTest {
        val socket = FakeSocket()
        val connection = connection(socket, serverSeq = 10)
        socket.serverSends(
            """{"jsonrpc":"2.0","method":"action","params":{"channel":"ahp-chat:/a","action":{"type":"chat/turnCancelled","turnId":"t","duration":5},"serverSeq":99,"origin":{"clientId":"client-1","clientSeq":1},"rejectionReason":"no"}}""",
        )
        socket.serverSends(action(12))

        connection.notifications.take(2).toList()

        assertEquals(12L, connection.lastServerSeq)
    }

    @Test
    fun `an INTERACT session sends only read methods and allowlisted actions`() = runTest {
        val socket = FakeSocket { method, id, _ ->
            when (method) {
                "initialize" -> result(id, initResult)
                "subscribe", "fetchTurns" -> result(id, "{}")
                else -> null
            }
        }
        val connection = connection(socket, policy = CapabilityPolicy.INTERACT)

        connection.initialize(clientInfo)
        connection.subscribe("ahp-chat:/a")
        connection.dispatchAction("ahp-chat:/a", cancelTurn, clientSeq = 1)
        runCatching { connection.dispatchAction("ahp-session:/a", renameSession, clientSeq = 2) }
        connection.unsubscribe("ahp-chat:/a")

        assertEquals(listOf("initialize", "subscribe", "dispatchAction", "unsubscribe"), socket.sentMethods)
        val actionTypes = socket.sent.filter { it["method"]!!.jsonPrimitive.content == "dispatchAction" }
            .map { it["params"]!!.jsonObject["action"]!!.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("chat/turnCancelled"), actionTypes)
    }

    @Test
    fun `action notifications are decoded and track the highest server sequence`() = runTest {
        val socket = FakeSocket()
        val connection = connection(socket, serverSeq = 10)
        socket.serverSends(action(42))
        socket.serverSends(action(40))

        val first = connection.notifications.take(2).toList()

        val envelope = assertIs<ServerNotification.Action>(first[0]).envelope
        assertEquals(42L, envelope.serverSeq)
        assertEquals("ahp-session:/abc", envelope.channel)
        assertEquals(42L, connection.lastServerSeq, "an older envelope never moves the sequence back")
    }

    @Test
    fun `other notifications are routed by method name`() = runTest {
        val socket = FakeSocket()
        val connection = connection(socket)
        socket.serverSends("""{"jsonrpc":"2.0","method":"root/sessionAdded","params":{"channel":"ahp-root://","summary":{"provider":"copilot","title":"x","status":1}}}""")

        val note = assertIs<ServerNotification.Named>(connection.notifications.first())

        assertEquals("root/sessionAdded", note.method)
        assertEquals("ahp-root://", note.params["channel"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an undecodable frame is reported and the loop keeps running`() = runTest {
        val socket = FakeSocket()
        val connection = connection(socket)
        socket.serverSends("not json")
        socket.serverSends("""{"jsonrpc":"2.0","method":"action","params":{"channel":"x"}}""")
        socket.serverSends("""{"jsonrpc":"2.0","method":"root/sessionRemoved","params":{"channel":"ahp-root://","session":"ahp-session:/a"}}""")

        val notes = connection.notifications.take(3).toList()

        assertNull(assertIs<ServerNotification.Undecodable>(notes[0]).method)
        assertEquals("action", assertIs<ServerNotification.Undecodable>(notes[1]).method)
        assertEquals("root/sessionRemoved", assertIs<ServerNotification.Named>(notes[2]).method)
    }

    @Test
    fun `an error response becomes an Rpc exception`() = runTest {
        val socket = FakeSocket { method, id, _ -> if (method == "subscribe") error(id, -32001, "no such session") else null }

        val failure = assertFailsWith<AhpException.Rpc> { connection(socket).subscribe("ahp-session:/gone") }

        assertEquals(-32001, failure.code)
        assertTrue(failure.message!!.contains("no such session"))
    }

    @Test
    fun `an unsupported protocol version carries what was offered and what the host supports`() = runTest {
        val socket = FakeSocket { method, id, _ ->
            if (method == "initialize") error(id, -32005, "unsupported", """{"supportedVersions":["2.0.0"]}""") else null
        }

        val failure = assertFailsWith<AhpException.UnsupportedProtocolVersion> { connection(socket).initialize(clientInfo) }

        assertEquals(listOf("1.0.0", "0.9.0"), failure.offered)
        assertEquals(listOf("2.0.0"), failure.supported)
    }

    @Test
    fun `a silent host times out`() = runTest {
        val connection = connection(FakeSocket(), timeoutMs = 5_000)

        assertFailsWith<AhpException.Timeout> { connection.ping() }
    }

    @Test
    fun `a socket that closes mid request fails the request and every later one`() = runTest {
        lateinit var socket: FakeSocket
        socket = FakeSocket { method, _, _ ->
            if (method == "ping") socket.serverCloses(1011)
            null
        }
        val connection = connection(socket)

        assertFailsWith<AhpException.ConnectionClosed> { connection.ping() }
        assertFailsWith<AhpException.ConnectionClosed> { connection.listSessions() }
    }

    @Test
    fun `notifications complete on a clean close and throw on an abnormal one`() = runTest {
        val clean = FakeSocket()
        val cleanConnection = connection(clean)
        clean.serverSends(action(1))
        clean.serverCloses(1000)
        assertEquals(1, cleanConnection.notifications.toList().size)

        val abnormal = FakeSocket()
        val abnormalConnection = connection(abnormal)
        abnormal.serverCloses(1011)
        assertFailsWith<AhpException.ConnectionClosed> { abnormalConnection.notifications.toList() }
    }

    @Test
    fun `a request the host sends is answered with method not found and nothing else`() = runTest {
        val socket = FakeSocket()
        connection(socket)
        socket.serverSends("""{"jsonrpc":"2.0","id":7,"method":"resourceRequest","params":{}}""")

        runCurrent() // advanceUntilIdle() ignores backgroundScope work, which is where the reader runs

        val reply = socket.sent.single()
        assertNull(reply["method"], "an error response is not a call")
        assertEquals(7L, reply["id"]!!.jsonPrimitive.long)
        assertEquals(-32601L, reply["error"]!!.jsonObject["code"]!!.jsonPrimitive.long)
    }

    @Test
    fun `a full session only ever sends read tier methods`() = runTest {
        val socket = FakeSocket { method, id, _ ->
            when (method) {
                "initialize" -> result(id, initResult)
                "listSessions" -> result(id, """{"items":[]}""")
                "reconnect" -> result(id, """{"type":"replay","actions":[],"missing":[]}""")
                "ping", "subscribe", "fetchTurns" -> result(id, "{}")
                else -> null
            }
        }
        val connection = connection(socket)

        connection.initialize(clientInfo)
        connection.listSessions()
        connection.subscribe("ahp-session:/a")
        connection.fetchTurns("ahp-chat:/a")
        connection.ping()
        connection.reconnect(listOf("ahp-root://"))
        connection.unsubscribe("ahp-session:/a")

        val readTier = setOf("initialize", "reconnect", "ping", "subscribe", "unsubscribe", "listSessions", "fetchTurns")
        assertEquals(7, socket.sentMethods.size)
        assertTrue(readTier.containsAll(socket.sentMethods), "sent: ${socket.sentMethods}")
    }

    @Test
    fun `reconnect resumes from the last seen sequence with the same client id`() = runTest {
        val socket = FakeSocket { method, id, _ -> if (method == "reconnect") result(id, """{"type":"replay","actions":[],"missing":[]}""") else null }
        val connection = connection(socket, serverSeq = 99)

        connection.reconnect(listOf("ahp-root://", "ahp-session:/a"))

        val params = socket.sent.single()["params"]!!.jsonObject
        assertEquals("client-1", params["clientId"]!!.jsonPrimitive.content)
        assertEquals(99L, params["lastSeenServerSeq"]!!.jsonPrimitive.long)
        assertEquals(listOf("ahp-root://", "ahp-session:/a"), params["subscriptions"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `a result of the wrong shape is a Protocol error, not a crash`() = runTest {
        val socket = FakeSocket { method, id, _ -> if (method == "listSessions") result(id, """{"items":"nope"}""") else null }

        assertFailsWith<AhpException.Protocol> { connection(socket).listSessions() }
    }
}
