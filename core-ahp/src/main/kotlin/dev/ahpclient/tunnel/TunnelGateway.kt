package dev.ahpclient.tunnel

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Path of the endpoint-selection route on the forwarded agent-host port (launcher protocol 6+). */
const val GATEWAY_SELECT_PATH = "/agent-host/select"

private const val CONNECT_AUTH_HEADER = "X-Tunnel-Authorization"

data class GatewayEndpoint(
    val type: String,
    val pid: Int,
    val instanceId: String,
    val quality: String?,
    val tunnelName: String?,
    val endpointKind: String,
    val endpointLabel: String,
)

data class GatewayInventory(
    val userDataPath: String,
    val endpoints: List<GatewayEndpoint>,
    val delegatedInstanceId: String?,
)

data class SelectedEndpoint(val type: String, val instanceId: String, val lifecycle: String)

/** An open agent-host socket: every further frame is AHP JSON-RPC. [selected] is null on protocol v5 (no gateway). */
class AgentHostConnection(val socket: TextSocket, val selected: SelectedEndpoint?)

sealed class GatewayException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Timeout(step: String) : GatewayException("Timed out waiting for $step")

    /** Nothing to select. The phone never asks the gateway to start a new host, because that spawns a process on the dev machine. */
    class NoEndpoints : GatewayException("The tunnel lists no running agent host; start VS Code with Remote Tunnel Access on")

    class Rejected(error: String) : GatewayException("Gateway rejected the selection: $error")

    class Malformed(detail: String, cause: Throwable? = null) : GatewayException("Unexpected gateway message: $detail", cause)
}

/**
 * Opens the agent-host WebSocket through a tunnel's public port URL (no SSH relay) and finishes the handshake that
 * `vscode.dev/agents` performs. Mirrors `TunnelAgentHostConnector.prepareSelection/completeSelection/connect` in
 * VS Code `src/vs/platform/agentHost/common/tunnelAgentHostConnector.ts`.
 */
class TunnelGateway(
    private val opener: SocketOpener = OkHttpSocketOpener(),
    private val stepTimeoutMs: Long = 30_000,
    private val agentHostPort: Int = AGENT_HOST_PORT,
) {
    /**
     * Protocol v6+: open `/agent-host/select`, read the inventory, send `{"instanceId": ...}`, read the acknowledgement.
     * Protocol v5: open the root route with the derived connection token. Either way the returned socket then speaks AHP.
     *
     * Instance choice: [instanceId], else the tunnel's delegated instance, else the first `editor`, else the first listed.
     */
    suspend fun open(target: ConnectTarget, instanceId: String? = null): AgentHostConnection {
        val headers = mapOf(CONNECT_AUTH_HEADER to "tunnel ${target.connectToken.reveal()}")
        if (!target.tunnel.usesGateway) {
            val path = "/?tkn=" + ConnectionToken.derive(target.tunnel.tunnelId)
            val socket = step("websocket open") { opener.open(agentHostUrl(target.portUriFormat, agentHostPort, path), headers) }
            return AgentHostConnection(socket, null)
        }

        val socket = step("gateway websocket open") { opener.open(agentHostUrl(target.portUriFormat, agentHostPort, GATEWAY_SELECT_PATH), headers) }
        try {
            val inventory = parseInventory(step("gateway inventory") { socket.receive() })
            val chosen = instanceId
                ?: inventory.delegatedInstanceId
                ?: inventory.endpoints.firstOrNull { it.type == "editor" }?.instanceId
                ?: inventory.endpoints.firstOrNull()?.instanceId
                ?: throw GatewayException.NoEndpoints()
            socket.send(buildJsonObject { put("instanceId", chosen) }.toString())
            val selected = parseAck(step("gateway selection acknowledgement") { socket.receive() })
            return AgentHostConnection(socket, selected)
        } catch (e: Throwable) {
            socket.close()
            throw e
        }
    }

    private suspend fun <T> step(name: String, block: suspend () -> T): T =
        try {
            withTimeout(stepTimeoutMs) { block() }
        } catch (_: TimeoutCancellationException) {
            throw GatewayException.Timeout(name)
        }

    internal companion object {
        /** `https://abc-{port}.usw2.devtunnels.ms/` with port 31546 and path `/x` becomes `wss://abc-31546.usw2.devtunnels.ms/x`. */
        fun agentHostUrl(portUriFormat: String, port: Int, path: String): String {
            if ("{port}" !in portUriFormat) throw GatewayException.Malformed("endpoint '$portUriFormat' has no {port} placeholder")
            val url = portUriFormat.replace("{port}", port.toString())
            val ws = when {
                url.startsWith("https://") -> "wss://" + url.removePrefix("https://")
                url.startsWith("http://") -> "ws://" + url.removePrefix("http://")
                else -> throw GatewayException.Malformed("endpoint '$portUriFormat' is not an http(s) URL")
            }
            return ws.trimEnd('/') + path
        }

        fun parseInventory(json: String): GatewayInventory {
            val root = parseObject(json, "inventory")
            val userDataPath = root.nonEmptyString("userDataPath") ?: bad("inventory has an invalid \"userDataPath\"")
            val endpoints = (root["endpoints"] as? JsonArray ?: bad("inventory has an invalid \"endpoints\""))
                .mapIndexed { index, element -> parseEndpoint(element, index) }
            val delegated = root["delegatedInstanceId"]?.let { root.nonEmptyString("delegatedInstanceId") ?: bad("inventory has an invalid \"delegatedInstanceId\"") }
            return GatewayInventory(userDataPath, endpoints, delegated)
        }

        fun parseAck(json: String): SelectedEndpoint {
            val root = parseObject(json, "selection acknowledgement")
            val ok = (root["ok"] as? JsonPrimitive)?.booleanOrNull ?: bad("selection acknowledgement is not a valid response")
            if (!ok) throw GatewayException.Rejected(root.string("error") ?: "Gateway selection failed")
            val selected = root["selected"] as? JsonObject
            val type = selected?.string("type")
            val id = selected?.nonEmptyString("instanceId")
            val lifecycle = selected?.string("lifecycle")
            if (selected == null || type !in ENDPOINT_TYPES || id == null || selected.string("role") != "primary" || lifecycle !in LIFECYCLES) {
                bad("selection acknowledgement has an invalid \"selected\" payload")
            }
            return SelectedEndpoint(type!!, id, lifecycle!!)
        }

        private val ENDPOINT_TYPES = setOf("editor", "standalone")
        private val LIFECYCLES = setOf("external", "managed")
        private val ENDPOINT_KINDS = setOf("tcp", "socket")

        private fun parseEndpoint(element: kotlinx.serialization.json.JsonElement, index: Int): GatewayEndpoint {
            val o = element as? JsonObject ?: bad("endpoint at index $index is not an object")
            val type = o.string("type")?.takeIf { it in ENDPOINT_TYPES } ?: bad("endpoint at index $index has an invalid \"type\"")
            val pidPrimitive = o["pid"] as? JsonPrimitive
            val pid = pidPrimitive?.takeIf { !it.isString }?.intOrNull ?: bad("endpoint at index $index has an invalid \"pid\"")
            val instanceId = o.nonEmptyString("instanceId") ?: bad("endpoint at index $index has an invalid \"instanceId\"")
            val kind = o.string("endpointKind")?.takeIf { it in ENDPOINT_KINDS } ?: bad("endpoint at index $index has an invalid \"endpointKind\"")
            val label = o.nonEmptyString("endpointLabel") ?: bad("endpoint at index $index has an invalid \"endpointLabel\"")
            return GatewayEndpoint(type, pid, instanceId, o.string("quality"), o.string("tunnelName"), kind, label)
        }

        private fun parseObject(json: String, what: String): JsonObject = try {
            Json.parseToJsonElement(json).jsonObject
        } catch (e: IllegalArgumentException) {
            throw GatewayException.Malformed("$what is not a JSON object", e)
        }

        private fun bad(detail: String): Nothing = throw GatewayException.Malformed(detail)

        private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun JsonObject.nonEmptyString(name: String): String? = string(name)?.takeIf { it.isNotEmpty() }
    }
}
