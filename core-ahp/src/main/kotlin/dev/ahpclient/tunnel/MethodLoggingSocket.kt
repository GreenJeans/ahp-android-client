package dev.ahpclient.tunnel

import dev.ahpclient.ahp.CapabilityPolicy
import dev.ahpclient.ahp.CapabilityDeniedException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Wraps a [SocketOpener] so every outbound frame leaves one line in [log]: the JSON-RPC method name and nothing else.
 * Never the parameters, the URL or the headers, so the line is safe to read out of a log. It exists to prove, on a real
 * device, that only read-tier calls reach the host: a method outside [CapabilityPolicy.READ] is marked `NOT-READ`.
 * This is a check made independently of the policy that is meant to prevent it.
 */
class MethodLoggingSocketOpener(
    private val delegate: SocketOpener,
    private val log: (String) -> Unit,
) : SocketOpener {
    override suspend fun open(url: String, headers: Map<String, String>): TextSocket = MethodLoggingSocket(delegate.open(url, headers), log)
}

internal class MethodLoggingSocket(private val inner: TextSocket, private val log: (String) -> Unit) : TextSocket by inner {
    override fun send(text: String): Boolean {
        log(describeOutbound(text))
        return inner.send(text)
    }
}

/** The log line for one outbound frame. Public so a test can state exactly what may appear. */
fun describeOutbound(frame: String): String {
    val parsed = try {
        Json.parseToJsonElement(frame) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    } ?: return "unparseable frame"
    val method = (parsed["method"] as? JsonPrimitive)?.contentOrNull
    return when {
        method != null -> if (isRead(method)) method else "NOT-READ $method"
        // The gateway handshake answers the inventory with {"instanceId": ...}, which has no method.
        parsed["instanceId"] != null && parsed.size == 1 -> "gateway.select"
        else -> "NOT-READ frame without a method"
    }
}

private fun isRead(method: String): Boolean = try {
    CapabilityPolicy.READ.check(method)
    true
} catch (_: CapabilityDeniedException) {
    false
}
