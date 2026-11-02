package dev.ahpclient.tunnel

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** The longest single frame accepted, in characters. A chat snapshot is the biggest thing the host sends and is far below this. */
const val MAX_FRAME_CHARS = 48_000_000L

/** The most characters that may sit received and not yet read: about 64 MB for ASCII text, which Java keeps one byte to a character. */
const val MAX_QUEUED_CHARS = 64_000_000L

/**
 * OkHttp-backed [SocketOpener]. The ping interval keeps the relay from dropping an idle monitoring socket. A frame over
 * [maxFrameChars], or more than [maxQueuedChars] received and not yet read, ends the socket with [ClientOverloadedException].
 */
class OkHttpSocketOpener(
    private val client: OkHttpClient = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build(),
    private val maxFrameChars: Long = MAX_FRAME_CHARS,
    private val maxQueuedChars: Long = MAX_QUEUED_CHARS,
) : SocketOpener {
    override suspend fun open(url: String, headers: Map<String, String>): TextSocket =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }.build()
            val socket = OkHttpTextSocket(maxFrameChars, maxQueuedChars)
            val webSocket = client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        socket.attach(webSocket)
                        if (continuation.isActive) continuation.resume(socket)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) = socket.deliver(text)

                    // The relay forwards the host's frames as text; tolerate a binary frame carrying UTF-8 like VS Code does.
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) = socket.deliver(bytes.utf8())

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        socket.remoteClosing(code, reason)
                        webSocket.close(code, reason)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = socket.remoteClosing(code, reason)

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(response?.let { UpgradeRejectedException(it.code) } ?: t)
                        } else {
                            socket.fail(t)
                        }
                    }
                },
            )
            continuation.invokeOnCancellation { webSocket.cancel() }
        }
}

internal class OkHttpTextSocket(
    private val maxFrameChars: Long = MAX_FRAME_CHARS,
    private val maxQueuedChars: Long = MAX_QUEUED_CHARS,
) : TextSocket {
    // Never drops a frame, because that would corrupt the state mirror. It is bounded by the characters queued instead (see [queued]).
    private val frames = Channel<String>(Channel.UNLIMITED)

    /** Characters in [frames] that [receive] has not yet handed out. */
    private val queued = AtomicLong(0)

    /** Set once, by the thread that delivers frames, when a limit is hit. From then on [receive] throws it and nothing more is queued. */
    @Volatile
    private var overload: ClientOverloadedException? = null

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var closeCode: Int? = null

    @Volatile
    private var closeReason: String? = null

    fun attach(socket: WebSocket) {
        webSocket = socket
    }

    fun deliver(text: String) {
        if (overload != null) return
        if (text.length > maxFrameChars) return overloaded("a frame of ${text.length} characters is over the limit of $maxFrameChars")
        val waiting = queued.addAndGet(text.length.toLong())
        if (waiting > maxQueuedChars) return overloaded("$waiting characters are waiting to be read, over the limit of $maxQueuedChars")
        frames.trySend(text)
    }

    /** Refuses what is queued and what is coming: the frames are dropped, the reader is woken with the failure, and the peer is told why. */
    private fun overloaded(what: String) {
        overload = ClientOverloadedException("Too much received from the host: $what")
        frames.close()
        while (frames.tryReceive().isSuccess) continue
        queued.set(0)
        webSocket?.close(TextSocket.POLICY_VIOLATION, "client overloaded")
    }

    fun remoteClosing(code: Int, reason: String) {
        closeCode = code
        closeReason = reason
        frames.close()
    }

    fun fail(cause: Throwable) {
        frames.close(SocketClosedException(null, cause.message, cause))
    }

    override suspend fun receive(): String {
        val text = try {
            frames.receive()
        } catch (_: ClosedReceiveChannelException) {
            throw overload ?: SocketClosedException(closeCode, closeReason)
        }
        // A frame read just as the limit was hit is dropped with the rest: the failure must not wait behind what was already queued.
        overload?.let { throw it }
        queued.addAndGet(-text.length.toLong())
        return text
    }

    override fun send(text: String): Boolean = webSocket?.send(text) ?: false

    override fun close(code: Int, reason: String) {
        webSocket?.close(code, reason)
        frames.close()
    }
}

actual fun defaultSocketOpener(): SocketOpener = OkHttpSocketOpener()
