package dev.ahpclient.ahp

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.SnapshotState
import com.microsoft.agenthostprotocol.generated.StateAction
import dev.ahpclient.state.chatSnapshotJson
import dev.ahpclient.state.turnJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Windowed delivery for chats (P9): the offer, joining fragments, counting and credit, the host's window, and a broken channel given up alone. */
@OptIn(ExperimentalCoroutinesApi::class)
class WindowedDeliveryTest {
    private val chat = "ahp-chat:/00000000-0000-4000-8000-0000000000w1"
    private val other = "ahp-chat:/00000000-0000-4000-8000-0000000000w2"

    private fun TestScope.connection(socket: FakeSocket, policy: CapabilityPolicy = CapabilityPolicy.READ) =
        AhpConnection(socket, backgroundScope, policy, clientId = "client-1")

    private fun accepted(window: Long = 4096, frame: Long = 1024, hostWindow: Long = 1_048_576, hostFrame: Long = 16_777_216) =
        """{"flowControl":{"clientReceive":{"windowBytes":$window,"maximumFrameBytes":$frame},"hostReceive":{"windowBytes":$hostWindow,"maximumFrameBytes":$hostFrame}}}"""

    private fun snapshotMessage(uri: String = chat, turns: Int = 1) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", "channel/snapshot")
        put("params", buildJsonObject {
            put("channel", uri)
            put("snapshot", Ahp.json.parseToJsonElement(chatSnapshotJson(uri, 7, (1..turns).map { turnJson("t$it", "question number $it") })))
        })
    }.toString()

    private fun frame(uri: String, data: String, final: Boolean) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", "channel/frame")
        put("params", buildJsonObject {
            put("channel", uri)
            put("data", data)
            if (final) put("final", true)
        })
    }.toString()

    /** [message] cut into fragments of [size] characters, as a host sends a message too large for one frame. */
    private fun fragments(message: String, size: Int, uri: String = chat): List<String> {
        val pieces = message.chunked(size)
        return pieces.mapIndexed { i, piece -> frame(uri, piece, final = i == pieces.lastIndex) }
    }

    private fun FakeSocket.credits(): List<Long> =
        sent.filter { it["method"]?.jsonPrimitive?.content == "channel/credit" }.map { it["params"]!!.jsonObject["consumedBytes"]!!.jsonPrimitive.long }

    // ---- the counters, on their own -----------------------------------------------------------------------------

    @Test
    fun `sizes are counted as UTF-16 bytes of the text as it came, and credit is due at three quarters of the window`() {
        val window = ChannelWindow(chat, ReceiveLimits(windowBytes = 400, maximumFrameBytes = 1024), ReceiveLimits(1_000, 1024))
        val text = "x".repeat(100) // 200 bytes
        assertEquals(text, window.receive(text, "action", JsonObject(emptyMap())))
        assertNull(window.creditDue(), "200 of 400 is not yet three quarters")
        window.receive("é😀", "action", JsonObject(emptyMap())) // 3 UTF-16 units: 6 bytes
        window.receive("y".repeat(47), "action", JsonObject(emptyMap())) // 94 bytes, 300 in all
        assertEquals(300L, window.creditDue())
        assertNull(window.creditDue(), "nothing new since")
    }

    @Test
    fun `fragments are joined in order and handed on only with the final one`() {
        val window = ChannelWindow(chat, ReceiveLimits(1_000_000, 1024), ReceiveLimits(1_000_000, 1024))
        val first = frame(chat, "{\"a\":", final = false)
        val last = frame(chat, "1}", final = true)
        assertNull(window.receive(first, "channel/frame", Ahp.json.parseToJsonElement(first).jsonObject["params"]!!.jsonObject))
        assertEquals("{\"a\":1}", window.receive(last, "channel/frame", Ahp.json.parseToJsonElement(last).jsonObject["params"]!!.jsonObject))
        assertEquals((first.length + last.length) * 2L, run {
            // Everything so far is uncredited: a window of one byte makes it due at once.
            ChannelWindow(chat, ReceiveLimits(1, 1024), ReceiveLimits(1, 1024)).also { w ->
                w.receive(first, "channel/frame", Ahp.json.parseToJsonElement(first).jsonObject["params"]!!.jsonObject)
                w.receive(last, "channel/frame", Ahp.json.parseToJsonElement(last).jsonObject["params"]!!.jsonObject)
            }.creditDue()
        })
    }

    @Test
    fun `an empty fragment and a message in the middle of a fragmented one break the channel`() {
        val window = ChannelWindow(chat, ReceiveLimits(1_000_000, 1024), ReceiveLimits(1_000_000, 1024))
        val empty = frame(chat, "x", final = false).replace("\"x\"", "\"\"")
        assertFailsWith<ChannelWindowException> { window.receive(empty, "channel/frame", Ahp.json.parseToJsonElement(empty).jsonObject["params"]!!.jsonObject) }

        val started = ChannelWindow(chat, ReceiveLimits(1_000_000, 1024), ReceiveLimits(1_000_000, 1024))
        val first = frame(chat, "{\"a\":", final = false)
        started.receive(first, "channel/frame", Ahp.json.parseToJsonElement(first).jsonObject["params"]!!.jsonObject)
        assertFailsWith<ChannelWindowException> { started.receive("{}", "action", JsonObject(emptyMap())) }
    }

    @Test
    fun `this app sends while the host's window has room, and credit opens it again`() {
        val window = ChannelWindow(chat, ReceiveLimits(1_000_000, 1024), ReceiveLimits(windowBytes = 100, maximumFrameBytes = 1024))
        assertTrue(window.trySend("a".repeat(60)), "an empty window takes a message that goes past it")
        assertFalse(window.trySend("b"), "120 of 100 outstanding: full")
        window.acceptCredit(120)
        assertTrue(window.trySend("c".repeat(30)), "all credited: 60 outstanding")
        window.acceptCredit(20)
        assertTrue(window.trySend("d"), "a smaller total than before is ignored, so 60, not 160, is outstanding")
        assertFalse(window.fitsFrame("e".repeat(513)))
        assertTrue(window.fitsFrame("e".repeat(512)))
    }

    // ---- on a connection ----------------------------------------------------------------------------------------

    @Test
    fun `a chat subscription offers windowed delivery, and a host that declines answers with the snapshot as before`() = runTest {
        val socket = FakeSocket { method, id, _ -> if (method == "subscribe") result(id, """{"snapshot":${chatSnapshotJson(chat, 3)}}""") else null }
        val connection = connection(socket)

        val subscription = assertIs<ChatSubscription.Ordinary>(connection.subscribeChat(chat))

        assertEquals(chat, subscription.snapshot!!.resource)
        val offer = socket.sent.single()["params"]!!.jsonObject["flowControl"]!!.jsonObject["receive"]!!.jsonObject
        assertEquals(ChannelWindow.CHAT_RECEIVE.windowBytes, offer["windowBytes"]!!.jsonPrimitive.long)
        assertEquals(ChannelWindow.CHAT_RECEIVE.maximumFrameBytes, offer["maximumFrameBytes"]!!.jsonPrimitive.long)
        assertFalse(connection.isWindowed(chat))
    }

    @Test
    fun `an accepted chat gets its snapshot as its own message, sent whole or in fragments, and credit as it goes`() = runTest {
        val message = snapshotMessage(turns = 30)
        val pieces = fragments(message, size = 200)
        val socket = FakeSocket { method, id, _ -> if (method == "subscribe") result(id, accepted(window = 4096)) else null }
        val connection = connection(socket)
        val received = async { connection.notifications.take(1).toList() }

        assertIs<ChatSubscription.Windowed>(connection.subscribeChat(chat))
        assertTrue(connection.isWindowed(chat))
        pieces.dropLast(1).forEach(socket::serverSends)
        runCurrent()
        assertFalse(received.isCompleted, "nothing is handed on before the final fragment")
        assertTrue(socket.credits().isNotEmpty(), "fragments are credited before the message is whole, or a message larger than the window never would be")

        socket.serverSends(pieces.last())
        runCurrent()
        val snapshot = assertIs<ServerNotification.ChannelSnapshot>(received.await().single()).snapshot
        assertEquals(chat, snapshot.resource)
        assertEquals(30, assertIs<SnapshotState.Chat>(snapshot.state).value.turns.size)
        val total = pieces.sumOf { it.length * 2L }
        assertTrue(socket.credits().last() <= total)
        assertTrue(socket.credits().zipWithNext().all { (a, b) -> b > a }, "consumedBytes is a running total")
    }

    @Test
    fun `actions on a windowed chat arrive as ordinary actions and count against the window`() = runTest {
        // A window this small makes credit due after every message, so the last credit is everything received.
        val socket = FakeSocket { method, id, _ -> if (method == "subscribe") result(id, accepted(window = 100)) else null }
        val connection = connection(socket)
        val received = async { connection.notifications.take(2).toList() }
        connection.subscribeChat(chat)

        val action = """{"jsonrpc":"2.0","method":"action","params":{"channel":"$chat","action":{"type":"chat/titleChanged","title":"Renamed"},"serverSeq":8}}"""
        socket.serverSends(snapshotMessage())
        socket.serverSends(action)
        runCurrent()

        val notes = received.await()
        assertIs<ServerNotification.ChannelSnapshot>(notes[0])
        assertEquals(chat, assertIs<ServerNotification.Action>(notes[1]).envelope.channel)
        assertEquals((snapshotMessage().length + action.length) * 2L, socket.credits().last(), "both were counted as the text that came")
    }

    @Test
    fun `a broken fragment gives up that chat alone, unsubscribed, and the connection carries on`() = runTest {
        val socket = FakeSocket { method, id, _ -> if (method == "subscribe") result(id, accepted()) else null }
        val connection = connection(socket)
        val received = async { connection.notifications.take(2).toList() }
        connection.subscribeChat(chat)
        connection.subscribeChat(other)

        socket.serverSends(fragments(snapshotMessage(), size = 200).first())
        socket.serverSends(snapshotMessage()) // a whole message in the middle of a fragmented one
        socket.serverSends(snapshotMessage(other))
        runCurrent()

        val notes = received.await()
        assertEquals(chat, assertIs<ServerNotification.ChannelBroken>(notes[0]).channel)
        assertEquals(other, assertIs<ServerNotification.ChannelSnapshot>(notes[1]).snapshot.resource)
        assertFalse(connection.isWindowed(chat))
        assertTrue(connection.isWindowed(other))
        assertEquals(listOf(chat), socket.sent.filter { it["method"]?.jsonPrimitive?.content == "unsubscribe" }.map { it["params"]!!.jsonObject["channel"]!!.jsonPrimitive.content })
    }

    @Test
    fun `limits above the offer are refused, and the chat is unsubscribed`() = runTest {
        val tooBig = ChannelWindow.CHAT_RECEIVE.windowBytes + 1
        val socket = FakeSocket { method, id, _ -> if (method == "subscribe") result(id, accepted(window = tooBig)) else null }
        val connection = connection(socket)

        assertFailsWith<AhpException.Protocol> { connection.subscribeChat(chat) }

        assertFalse(connection.isWindowed(chat))
        assertEquals("unsubscribe", socket.sentMethods.last())
    }

    @Test
    fun `what arrives for a chat after it is unsubscribed is ignored, and so is a second subscribe while its snapshot is coming`() = runTest {
        val socket = FakeSocket { method, id, _ -> if (method == "subscribe") result(id, accepted()) else null }
        val connection = connection(socket)
        connection.subscribeChat(chat)
        assertIs<ChatSubscription.Windowed>(connection.subscribeChat(chat))
        assertEquals(1, socket.sentMethods.count { it == "subscribe" }, "already windowed: not asked for again")

        connection.unsubscribe(chat)
        socket.serverSends(snapshotMessage())
        socket.serverSends(frame(chat, "{", final = false))
        socket.serverSends(snapshotMessage(other).replace(other, "ahp-chat:/elsewhere")) // keeps the reader busy past the ignored ones
        runCurrent()

        assertTrue(socket.credits().isEmpty())
        assertFalse(connection.isWindowed(chat))
    }

    @Test
    fun `an action sent on a windowed chat counts against the host's window, and waits for its credit`() = runTest {
        val socket = FakeSocket { method, id, _ -> if (method == "subscribe") result(id, accepted(hostWindow = 1200, hostFrame = 1024)) else null }
        val connection = connection(socket, CapabilityPolicy.INTERACT)
        connection.subscribeChat(chat)
        val cancel = Ahp.json.decodeFromString(StateAction.serializer(), """{"type":"chat/turnCancelled","turnId":"turn-1","duration":5}""")

        var sent = 0
        val error = assertFailsWith<AhpException.Protocol> {
            while (sent < 100) {
                connection.dispatchAction(chat, cancel, clientSeq = sent.toLong())
                sent++
            }
        }
        assertTrue(error.message!!.contains("not yet taken in"))
        assertTrue(sent in 1..10, "a few fit in a 1200-byte window, then it is full ($sent sent)")
        val sentBefore = socket.sentMethods.count { it == "dispatchAction" }
        assertEquals(sent, sentBefore, "the refused one was not sent")
        assertFalse(connection.awaitSendRoom(chat, timeoutMs = 1_000), "no credit came")

        val waiting = async { connection.awaitSendRoom(chat, timeoutMs = 60_000) }
        runCurrent()
        socket.serverSends("""{"jsonrpc":"2.0","method":"channel/credit","params":{"channel":"$chat","consumedBytes":100000}}""")
        runCurrent()
        assertTrue(waiting.await())
        connection.dispatchAction(chat, cancel, clientSeq = 100)
        assertTrue(connection.awaitSendRoom("ahp-session:/not-windowed", timeoutMs = 1), "a channel that is not windowed never waits")
    }

    @Test
    fun `an action too large for the host's frame is refused before it is sent`() = runTest {
        val socket = FakeSocket { method, id, _ -> if (method == "subscribe") result(id, accepted(hostFrame = 1024)) else null }
        val connection = connection(socket, CapabilityPolicy.INTERACT)
        connection.subscribeChat(chat)
        val big = Ahp.json.decodeFromString(StateAction.serializer(), """{"type":"chat/turnCancelled","turnId":"${"z".repeat(600)}","duration":5}""")

        val before = socket.sent.size
        assertFailsWith<AhpException.Protocol> { connection.dispatchAction(chat, big, clientSeq = 1) }
        assertEquals(before, socket.sent.size, "nothing was sent")
    }

    @Test
    fun `credit is sent on the read tier, being needed to read`() = runTest {
        val socket = FakeSocket { method, id, _ -> if (method == "subscribe") result(id, accepted(window = 1024)) else null }
        val connection = connection(socket, CapabilityPolicy.READ)
        connection.subscribeChat(chat)
        socket.serverSends(snapshotMessage(turns = 3))
        runCurrent()
        assertTrue(socket.credits().isNotEmpty())
    }

    @Test
    fun `a host's limits are read only when whole numbers at or above the protocol's floor`() {
        assertEquals(ReceiveLimits(10, 1024), ReceiveLimits.from(Ahp.json.parseToJsonElement("""{"windowBytes":10,"maximumFrameBytes":1024}""")))
        assertNull(ReceiveLimits.from(Ahp.json.parseToJsonElement("""{"windowBytes":10,"maximumFrameBytes":1023}""")))
        assertNull(ReceiveLimits.from(Ahp.json.parseToJsonElement("""{"windowBytes":"10","maximumFrameBytes":2048}""")))
        assertNull(ReceiveLimits.from(Ahp.json.parseToJsonElement("""{"windowBytes":0,"maximumFrameBytes":2048}""")))
        assertNull(ReceiveLimits.from(JsonPrimitive(3)))
    }
}
