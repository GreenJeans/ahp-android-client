package dev.ahpclient.tunnel

import java.util.concurrent.TimeUnit
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

/** OkHttp-backed [SocketOpener]. The ping interval keeps the relay from dropping an idle monitoring socket. */
class OkHttpSocketOpener(
    private val client: OkHttpClient = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build(),
) : SocketOpener {
    override suspend fun open(url: String, headers: Map<String, String>): TextSocket =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }.build()
            val socket = OkHttpTextSocket()
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

internal class OkHttpTextSocket : TextSocket {
    // Unbounded on purpose: AHP deltas are small, and dropping a frame would corrupt the state mirror.
    private val frames = Channel<String>(Channel.UNLIMITED)

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
        frames.trySend(text)
    }

    fun remoteClosing(code: Int, reason: String) {
        closeCode = code
        closeReason = reason
        frames.close()
    }

    fun fail(cause: Throwable) {
        frames.close(SocketClosedException(null, cause.message, cause))
    }

    override suspend fun receive(): String = try {
        frames.receive()
    } catch (_: ClosedReceiveChannelException) {
        throw SocketClosedException(closeCode, closeReason)
    }

    override fun send(text: String): Boolean = webSocket?.send(text) ?: false

    override fun close(code: Int, reason: String) {
        webSocket?.close(code, reason)
        frames.close()
    }
}
