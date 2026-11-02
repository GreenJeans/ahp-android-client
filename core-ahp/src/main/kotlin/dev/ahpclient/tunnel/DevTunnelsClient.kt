package dev.ahpclient.tunnel

import dev.ahpclient.auth.Secret
import dev.ahpclient.http.HttpTransport
import dev.ahpclient.http.defaultHttpTransport
import dev.ahpclient.platform.parseUri
import dev.ahpclient.platform.urlEncodeUtf8
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

private const val API_VERSION = "2023-09-27-preview"

/**
 * Plain-HTTP client for the Dev Tunnels management API, mirroring the calls VS Code makes through
 * `@microsoft/dev-tunnels-management` (`tunnelAgentHostService.ts`). No SDK and no SSH relay needed.
 *
 * Auth is the GitHub user token from [dev.ahpclient.auth.GitHubDeviceAuth], sent as `github <token>`.
 */
class DevTunnelsClient(
    private val transport: HttpTransport = defaultHttpTransport(),
    private val serviceUrl: String = "https://global.rel.tunnels.api.visualstudio.com",
    private val userAgent: String = "ahp-android-client/0.1",
) {
    /** Agent-host-capable tunnels (launcher protocol 5+) owned by the signed-in account. */
    suspend fun listAgentHostTunnels(token: Secret): List<TunnelInfo> {
        val url = buildUrl(
            base = serviceUrl,
            pathSegments = listOf("tunnels"),
            query = listOf(
                "includePorts" to "true",
                "tokenScopes" to "connect",
                "labels" to TUNNEL_LAUNCHER_LABEL,
                "allLabels" to "true",
                "global" to "true",
                "api-version" to API_VERSION,
            ),
        )
        val body = get(url, token, tunnelId = null)
        return try {
            // Global listing groups tunnels per region: { value: [ { value: [tunnel, ...] }, ... ] }
            body.array("value").flatMap { region -> region.jsonObject.array("value") }
                .mapNotNull { parseTunnel(it.jsonObject) }
                .filter { it.protocolVersion >= MIN_PROTOCOL_VERSION }
        } catch (e: IllegalArgumentException) {
            throw DevTunnelsException.Malformed("tunnel list has an unexpected shape", e)
        }
    }

    /**
     * Fetches the tunnel's web endpoint and a fresh `connect` token. Call this on every (re)connect: the token is
     * short-lived and is never persisted.
     */
    suspend fun resolveConnectTarget(token: Secret, tunnel: TunnelInfo): ConnectTarget {
        val url = buildUrl(
            base = clusterUrlString(serviceUrl, tunnel.clusterId),
            pathSegments = listOf("tunnels", tunnel.tunnelId),
            query = listOf(
                "includePorts" to "true",
                "tokenScopes" to "connect",
                "api-version" to API_VERSION,
            ),
        )
        val body = get(url, token, tunnelId = tunnel.tunnelId)
        return try {
            val info = parseTunnel(body) ?: throw DevTunnelsException.Malformed("tunnel response has no tunnelId or clusterId")
            val format = body.array("endpoints").firstNotNullOfOrNull { it.jsonObject.string("portUriFormat") }
                ?: throw DevTunnelsException.Malformed("tunnel has no web endpoint (portUriFormat); the host may not publish web forwarding")
            val connect = body["accessTokens"]?.jsonObject?.string("connect")
                ?: throw DevTunnelsException.Malformed("service returned no connect-scope token")
            ConnectTarget(info, format, Secret(connect))
        } catch (e: IllegalArgumentException) {
            throw DevTunnelsException.Malformed("tunnel response has an unexpected shape", e)
        }
    }

    private suspend fun get(url: String, token: Secret, tunnelId: String?): JsonObject {
        val response = transport.get(
            url = url,
            headers = mapOf(
                "Authorization" to "github ${token.reveal()}",
                "Accept" to "application/json",
                "User-Agent" to userAgent,
            ),
        )
        when (response.code) {
            in 200..299 -> Unit
            401 -> throw DevTunnelsException.AuthExpired()
            403 -> throw DevTunnelsException.Forbidden()
            404 -> throw if (tunnelId != null) DevTunnelsException.TunnelNotFound(tunnelId) else DevTunnelsException.Http(404)
            else -> throw DevTunnelsException.Http(response.code)
        }
        return try {
            Json.parseToJsonElement(response.body).jsonObject
        } catch (e: IllegalArgumentException) {
            throw DevTunnelsException.Malformed("body is not a JSON object", e)
        }
    }

    internal companion object {
        /**
         * Cluster-specific service URL: the leading `global` label of the host becomes the cluster id
         * (`global.rel.tunnels...` to `usw2.rel.tunnels...`). Any other host, such as a local test server, is used as given.
         */
        fun clusterUrlString(baseUrl: String, clusterId: String): String {
            val parsed = parseUri(baseUrl) ?: return baseUrl
            val host = parsed.host ?: return baseUrl
            if (!host.startsWith("global.")) return baseUrl
            val newHost = clusterId + host.substring(host.indexOf('.'))
            return baseUrl.replaceFirst(host, newHost)
        }

        private fun buildUrl(base: String, pathSegments: List<String>, query: List<Pair<String, String>>): String {
            val trimmedBase = base.trimEnd('/')
            val path = pathSegments.joinToString("/") { urlEncodeUtf8(it).replace("+", "%20") }
            val queryStr = query.joinToString("&") { (k, v) -> "${urlEncodeUtf8(k)}=${urlEncodeUtf8(v)}" }
            return if (queryStr.isEmpty()) "$trimmedBase/$path" else "$trimmedBase/$path?$queryStr"
        }

        fun parseTunnel(tunnel: JsonObject): TunnelInfo? {
            val tunnelId = tunnel.string("tunnelId") ?: return null
            val clusterId = tunnel.string("clusterId") ?: return null
            val labels = (tunnel["labels"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val tags = TunnelTags(labels)
            // hostConnectionCount is a number on older service versions and { current: n } on newer ones.
            val status = tunnel["status"] as? JsonObject
            val count = when (val raw = status?.get("hostConnectionCount")) {
                is JsonPrimitive -> raw.intOrNull
                is JsonObject -> (raw["current"] as? JsonPrimitive)?.intOrNull
                else -> null
            } ?: 0
            val ports = (tunnel["ports"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("portNumber")?.let { n -> (n as? JsonPrimitive)?.intOrNull } }
            return TunnelInfo(
                tunnelId = tunnelId,
                clusterId = clusterId,
                name = listOfNotNull(tags.name, tunnel.string("name"), tunnel.string("description")).firstOrNull { it.isNotBlank() } ?: tunnelId,
                protocolVersion = tags.protocolVersion,
                hostConnectionCount = count,
                ports = ports,
            )
        }

        private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.array(name: String): JsonArray =
            this[name] as? JsonArray ?: throw DevTunnelsException.Malformed("missing array '$name'")

        private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
    }
}
