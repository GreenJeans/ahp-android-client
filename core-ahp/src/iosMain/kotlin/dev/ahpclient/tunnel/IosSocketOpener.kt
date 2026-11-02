package dev.ahpclient.tunnel

import dev.ahpclient.platform.AtomicBoolean
import dev.ahpclient.platform.AtomicLong
import dev.ahpclient.platform.IOException
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.Foundation.NSData
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionWebSocketMessage
import platform.Foundation.NSURLSessionWebSocketMessageTypeData
import platform.Foundation.NSURLSessionWebSocketMessageTypeString
import platform.Foundation.NSURLSessionWebSocketTask
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.setValue

private const val MAX_FRAME_CHARS = 48_000_000L
private const val MAX_QUEUED_CHARS = 64_000_000L

/**
 * iOS `NSURLSessionWebSocketTask`-backed [SocketOpener] with 45-second keep-alive pings (H3 in `docs/hardening-spec.md`)
 * and bounded frame/queue limits matching `OkHttpSocketOpener` on Android.
 */
class IosSocketOpener(
    private val pingIntervalMs: Long = 45_000L,
    private val maxFrameChars: Long = MAX_FRAME_CHARS,
    private val maxQueuedChars: Long = MAX_QUEUED_CHARS,
) : SocketOpener {

    private val session = NSURLSession.sessionWithConfiguration(
        NSURLSessionConfiguration.ephemeralSessionConfiguration(),
    )

    override suspend fun open(url: String, headers: Map<String, String>): TextSocket {
        val nsUrl = NSURL.URLWithString(url) ?: throw IOException("Invalid WebSocket URL: $url")
        val request = NSMutableURLRequest.requestWithURL(nsUrl).apply {
            headers.forEach { (k, v) -> setValue(v, forHTTPHeaderField = k) }
        }
        val task = session.webSocketTaskWithRequest(request)
        task.maximumMessageSize = maxFrameChars
        val socket = IosTextSocket(task, pingIntervalMs, maxFrameChars, maxQueuedChars)
        socket.start()
        return socket
    }
}

internal class IosTextSocket(
    private val task: NSURLSessionWebSocketTask,
    private val pingIntervalMs: Long,
    private val maxFrameChars: Long,
    private val maxQueuedChars: Long,
) : TextSocket {
    private val frames = Channel<String>(Channel.UNLIMITED)
    private val queued = AtomicLong(0L)
    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pingJob: Job? = null

    @Volatile
    private var overload: ClientOverloadedException? = null

    @Volatile
    private var closeCode: Int? = null

    @Volatile
    private var closeReason: String? = null

    fun start() {
        task.resume()
        receiveNext()
        if (pingIntervalMs > 0L) {
            pingJob = scope.launch {
                while (isActive && !closed.get()) {
                    delay(pingIntervalMs)
                    if (closed.get()) break
                    task.sendPingWithPongReceiveHandler { error ->
                        if (error != null && !closed.get()) {
                            fail(IOException(error.localizedDescription))
                        }
                    }
                }
            }
        }
    }

    private fun receiveNext() {
        if (closed.get()) return
        task.receiveMessageWithCompletionHandler { message, error ->
            if (error != null) {
                val code = task.closeCode.toInt().takeIf { it != 0 }
                val reason = task.closeReason?.let {
                    NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString()
                }
                if (code != null) {
                    remoteClosing(code, reason.orEmpty())
                } else {
                    fail(IOException(error.localizedDescription))
                }
                return@receiveMessageWithCompletionHandler
            }
            if (message != null) {
                val text = when (message.type) {
                    NSURLSessionWebSocketMessageTypeString -> message.string
                    NSURLSessionWebSocketMessageTypeData -> message.data?.let {
                        NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString()
                    }
                    else -> null
                }
                if (text != null) {
                    deliver(text)
                }
            }
            if (!closed.get() && overload == null) {
                receiveNext()
            }
        }
    }

    private fun deliver(text: String) {
        if (overload != null) return
        if (text.length > maxFrameChars) {
            overloaded("a frame of ${text.length} characters is over the limit of $maxFrameChars")
            return
        }
        val waiting = queued.incrementAndGetWithDelta(text.length.toLong())
        if (waiting > maxQueuedChars) {
            overloaded("$waiting characters are waiting to be read, over the limit of $maxQueuedChars")
            return
        }
        frames.trySend(text)
    }

    private fun overloaded(what: String) {
        overload = ClientOverloadedException("Too much received from the host: $what")
        closed.set(true)
        pingJob?.cancel()
        frames.close()
        while (frames.tryReceive().isSuccess) continue
        queued.set(0L)
        val reasonData: NSData? = ("client overloaded" as NSString).dataUsingEncoding(NSUTF8StringEncoding)
        task.cancelWithCloseCode(TextSocket.POLICY_VIOLATION.toLong(), reason = reasonData)
    }

    private fun remoteClosing(code: Int, reason: String) {
        closeCode = code
        closeReason = reason
        closed.set(true)
        pingJob?.cancel()
        frames.close()
    }

    private fun fail(cause: Throwable) {
        closed.set(true)
        pingJob?.cancel()
        frames.close(SocketClosedException(null, cause.message, cause))
    }

    override suspend fun receive(): String {
        val text = try {
            frames.receive()
        } catch (_: ClosedReceiveChannelException) {
            throw overload ?: SocketClosedException(closeCode, closeReason)
        }
        overload?.let { throw it }
        queued.incrementAndGetWithDelta(-text.length.toLong())
        return text
    }

    override fun send(text: String): Boolean {
        if (closed.get() || overload != null) return false
        val msg = NSURLSessionWebSocketMessage(text)
        task.sendMessage(msg) { error ->
            if (error != null && !closed.get()) {
                fail(IOException(error.localizedDescription))
            }
        }
        return true
    }

    override fun close(code: Int, reason: String) {
        if (closed.getAndSet(true)) return
        pingJob?.cancel()
        val reasonData: NSData? = (reason as NSString).dataUsingEncoding(NSUTF8StringEncoding)
        task.cancelWithCloseCode(code.toLong(), reason = reasonData)
        frames.close()
    }
}

private fun AtomicLong.incrementAndGetWithDelta(delta: Long): Long {
    while (true) {
        val cur = get()
        val next = cur + delta
        if (compareAndSet(cur, next)) return next
    }
}

actual fun defaultSocketOpener(): SocketOpener = IosSocketOpener()
