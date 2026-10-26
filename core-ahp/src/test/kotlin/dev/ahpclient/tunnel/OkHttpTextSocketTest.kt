package dev.ahpclient.tunnel

import dev.ahpclient.state.fixture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/** How much the socket will hold: characters waiting are counted, one frame is capped, and past either the socket fails at once. */
@OptIn(ExperimentalCoroutinesApi::class)
class OkHttpTextSocketTest {
    private val frame = "12345678"

    @Test
    fun `characters are counted when queued and given back when read`() = runTest {
        val socket = OkHttpTextSocket(maxFrameChars = 10, maxQueuedChars = 25)
        repeat(3) { socket.deliver(frame) }
        assertEquals(frame, socket.receive())
        socket.deliver(frame)
        assertEquals(frame, socket.receive())
        assertEquals(frame, socket.receive())
        repeat(2) { socket.deliver(frame) }

        // 48 characters have gone through a limit of 25: the count follows what is waiting, not what has passed.
        repeat(3) { assertEquals(frame, socket.receive()) }
    }

    @Test
    fun `queued characters over the limit fail the socket and drop what was queued`() = runTest {
        val socket = OkHttpTextSocket(maxFrameChars = 10, maxQueuedChars = 25)
        repeat(4) { socket.deliver(frame) }

        val failure = assertFailsWith<ClientOverloadedException> { socket.receive() }

        assertTrue(failure.message!!.contains("25"), "names the limit: ${failure.message}")
    }

    @Test
    fun `a single frame over the limit is refused before it is queued`() = runTest {
        val socket = OkHttpTextSocket(maxFrameChars = 10, maxQueuedChars = 1_000)
        socket.deliver("ok")
        socket.deliver("x".repeat(11))

        val failure = assertFailsWith<ClientOverloadedException> { socket.receive() }

        assertTrue(failure.message!!.contains("11") && failure.message!!.contains("10"), "names the frame and the limit: ${failure.message}")
    }

    @Test
    fun `a frame exactly at the limits is accepted`() = runTest {
        val socket = OkHttpTextSocket(maxFrameChars = 10, maxQueuedChars = 20)
        socket.deliver("x".repeat(10))
        socket.deliver("y".repeat(10))

        assertEquals("x".repeat(10), socket.receive())
        assertEquals("y".repeat(10), socket.receive())
    }

    @Test
    fun `a reader already waiting is woken by the overload`() = runTest {
        val socket = OkHttpTextSocket(maxFrameChars = 10, maxQueuedChars = 1_000)
        val waiting = async { runCatching { socket.receive() } }
        runCurrent()

        socket.deliver("x".repeat(11))

        assertTrue(waiting.await().exceptionOrNull() is ClientOverloadedException)
    }

    @Test
    fun `once overloaded the socket keeps failing and queues nothing more`() = runTest {
        val socket = OkHttpTextSocket(maxFrameChars = 10, maxQueuedChars = 1_000)
        socket.deliver("x".repeat(11))
        socket.deliver("late")

        assertFailsWith<ClientOverloadedException> { socket.receive() }
        assertFailsWith<ClientOverloadedException> { socket.receive() }
    }

    @Test
    fun `an oversized frame closes the connection with 1008 and receive throws`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val host = Host { it.send("x".repeat(50)) }
            server.enqueue(MockResponse().withWebSocketUpgrade(host))
            server.start()

            val socket = OkHttpSocketOpener(maxFrameChars = 20).open(wsUrl(server), emptyMap())

            assertEquals(TextSocket.POLICY_VIOLATION, host.closeCode())
            assertFailsWith<ClientOverloadedException> { withTimeout(10_000) { socket.receive() } }
        }
    }

    @Test
    fun `too many characters waiting closes with 1008 and the failure is not held up by what was queued`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val host = Host { webSocket -> repeat(5) { webSocket.send("x".repeat(10)) } }
            server.enqueue(MockResponse().withWebSocketUpgrade(host))
            server.start()

            val socket = OkHttpSocketOpener(maxFrameChars = 20, maxQueuedChars = 25).open(wsUrl(server), emptyMap())

            // Nothing has been read, so the third frame is the one that goes over.
            assertEquals(TextSocket.POLICY_VIOLATION, host.closeCode())
            assertFailsWith<ClientOverloadedException> { withTimeout(10_000) { socket.receive() } }
        }
    }

    @Test
    fun `normal traffic passes untouched at the default limits`() = runBlocking<Unit> {
        val big = """{"jsonrpc":"2.0","id":3,"result":{"items":[],"pad":"${"p".repeat(300_000)}"}}"""
        val snapshot = fixture("live/chat-subscribe-result.json")
        MockWebServer().use { server ->
            val host = Host { webSocket ->
                webSocket.send(big)
                webSocket.send(snapshot)
            }
            server.enqueue(MockResponse().withWebSocketUpgrade(host))
            server.start()

            val socket = OkHttpSocketOpener().open(wsUrl(server), emptyMap())
            val first = withTimeout(10_000) { socket.receive() }
            val second = withTimeout(10_000) { socket.receive() }
            socket.close()

            assertEquals(big, first)
            assertEquals(snapshot, second)
        }
    }

    private fun wsUrl(server: MockWebServer) = server.url("/").toString().replaceFirst("http", "ws")

    /** The host's side: [onOpen] sends the frames, and the close code the client ends with is kept for the test to read. */
    private class Host(private val onOpen: (WebSocket) -> Unit) : WebSocketListener() {
        private val closes = LinkedBlockingQueue<Int>()

        override fun onOpen(webSocket: WebSocket, response: Response) = onOpen(webSocket)

        // Without answering the client's close, the server's queue never drains and closing the server times out.
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closes.add(code)
            webSocket.close(code, null)
        }

        fun closeCode(): Int? = closes.poll(10, TimeUnit.SECONDS)
    }
}
