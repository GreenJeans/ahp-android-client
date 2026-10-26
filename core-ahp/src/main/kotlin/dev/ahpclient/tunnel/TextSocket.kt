package dev.ahpclient.tunnel

import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** A WebSocket reduced to what the gateway handshake and the AHP connection need: text frames in and out. */
interface TextSocket {
    /**
     * The next text frame. Frames that arrive before the first call are buffered, and dropped only when the socket is overloaded.
     * Throws [SocketClosedException] once closed, or [ClientOverloadedException] when a frame or the buffered frames passed a limit.
     */
    suspend fun receive(): String

    /** Queues a text frame; false when the socket is already closed. */
    fun send(text: String): Boolean

    fun close(code: Int = NORMAL_CLOSURE, reason: String = "client closing")

    companion object {
        const val NORMAL_CLOSURE = 1000
        const val GOING_AWAY = 1001
        const val POLICY_VIOLATION = 1008
    }
}

/** Opens a [TextSocket]; a seam so tests can use a real local server and Android can swap OkHttp settings. */
fun interface SocketOpener {
    suspend fun open(url: String, headers: Map<String, String>): TextSocket
}

class SocketClosedException(val code: Int?, val reason: String?, cause: Throwable? = null) :
    IOException("Socket closed (code=${code ?: "none"}, reason=${reason?.takeIf { it.isNotEmpty() } ?: "(empty)"})", cause) {
    /** A close the peer meant: the stream just ends instead of failing. */
    val isClean: Boolean get() = code == TextSocket.NORMAL_CLOSURE || code == TextSocket.GOING_AWAY
}

/**
 * The host sent more than this client can hold: one frame too big, too many frames waiting for the reducers, or too many
 * notifications waiting to be applied. The connection is dropped rather than let the heap grow until the process dies, and the
 * next one resumes, which replays what was missed. The message names the limit that was hit.
 */
class ClientOverloadedException(message: String) : IOException(message)

/** The HTTP upgrade itself was refused, for example 401 (expired connect token) or 404 (tunnel gone). */
class UpgradeRejectedException(val httpCode: Int) : IOException("WebSocket upgrade rejected (HTTP $httpCode)")

/** Frames until the socket closes. A clean close completes the flow; any other close or failure throws. */
val TextSocket.incoming: Flow<String>
    get() = flow {
        try {
            while (true) emit(receive())
        } catch (e: SocketClosedException) {
            if (!e.isClean) throw e
        }
    }
