package dev.ahpclient.ahp

import dev.ahpclient.tunnel.SocketClosedException
import dev.ahpclient.tunnel.TextSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** In-memory [TextSocket] that records what the client sends and lets a test script the host's side. */
class FakeSocket(
    private val autoReply: (method: String, id: Long?, params: JsonObject) -> String? = { _, _, _ -> null },
) : TextSocket {
    val sent = CopyOnWriteArrayList<JsonObject>()
    private val inbox = Channel<String>(Channel.UNLIMITED)

    @Volatile
    private var closeCode: Int? = null

    fun serverSends(text: String) {
        inbox.trySend(text)
    }

    fun serverCloses(code: Int = TextSocket.NORMAL_CLOSURE) {
        closeCode = code
        inbox.close()
    }

    /** Methods of every request and notification the client sent, in order (responses have no method). */
    val sentMethods: List<String> get() = sent.mapNotNull { it["method"]?.jsonPrimitive?.content }

    override suspend fun receive(): String = try {
        inbox.receive()
    } catch (_: ClosedReceiveChannelException) {
        throw SocketClosedException(closeCode, null)
    }

    override fun send(text: String): Boolean {
        if (closeCode != null) return false
        val frame = Json.parseToJsonElement(text).jsonObject
        sent += frame
        val method = frame["method"]?.jsonPrimitive?.content
        if (method != null) {
            val id = frame["id"]?.jsonPrimitive?.longOrNull
            autoReply(method, id, frame["params"] as? JsonObject ?: JsonObject(emptyMap()))?.let { inbox.trySend(it) }
        }
        return true
    }

    override fun close(code: Int, reason: String) = serverCloses(code)
}

fun result(id: Long?, body: String) = """{"jsonrpc":"2.0","id":$id,"result":$body}"""

fun error(id: Long?, code: Int, message: String, data: String? = null): String {
    val dataPart = data?.let { ""","data":$it""" }.orEmpty()
    return """{"jsonrpc":"2.0","id":$id,"error":{"code":$code,"message":"$message"$dataPart}}"""
}
