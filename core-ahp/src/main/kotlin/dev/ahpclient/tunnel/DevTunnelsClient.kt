package dev.ahpclient.tunnel

import dev.ahpclient.auth.Secret
import dev.ahpclient.http.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

private const val API_VERSION = "2023-09-27-preview"

/**
 * Plain-HTTP client for the Dev Tunnels management API, mirroring the calls VS Code makes through
 * `@microsoft/dev-tunnels-management` (`tunnelAgentHostService.ts`). No SDK and no SSH relay needed.
 *
 * Auth is the GitHub user token from [dev.ahpclient.auth.GitHubDeviceAuth], sent as `github <token>`.
 */
class DevTunnelsClient(
    private val http: OkHttpClient = OkHttpClient(),
    private val serviceUrl: String = "https://global.rel.tunnels.api.visualstudio.com",
    private val userAgent: String = "ahp-android-client/0.1",
) {
    /** Agent-host-capable tunnels (launcher protocol 5+) owned by the signed-in account. */
    suspend fun listAgentHostTunnels(token: Secret): List<TunnelInfo> {
        val url = serviceUrl.toHttpUrl().newBuilder()
            .encodedPath("/tunnels")
            .addQueryParameter("includePorts", "true")
            .addQueryParameter("tokenScopes", "connect")
            .addQueryParameter("labels", TUNNEL_LAUNCHER_LABEL)
            .addQueryParameter("allLabels", "true")
            .addQueryParameter("global", "true")
            .addQueryParameter("api-version", API_VERSION)
            .build()
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
        val url = clusterUrl(serviceUrl.toHttpUrl(), tunnel.clusterId).newBuilder()
            .addPathSegment("tunnels")
            .addPathSegment(tunnel.tunnelId)
            .addQueryParameter("includePorts", "true")
            .addQueryParameter("tokenScopes", "connect")
            .addQueryParameter("api-version", API_VERSION)
            .build()
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

    private suspend fun get(url: HttpUrl, token: Secret, tunnelId: String?): JsonObject {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "github ${token.reveal()}")
            .header("Accept", "application/json")
            .header("User-Agent", userAgent)
            .get()
            .build()
        http.newCall(request).await().use { response ->
            when (response.code) {
                in 200..299 -> Unit
                401 -> throw DevTunnelsException.AuthExpired()
                403 -> throw DevTunnelsException.Forbidden()
                404 -> throw if (tunnelId != null) DevTunnelsException.TunnelNotFound(tunnelId) else DevTunnelsException.Http(404)
                else -> throw DevTunnelsException.Http(response.code)
            }
            val text = response.body.string()
            return try {
                Json.parseToJsonElement(text).jsonObject
            } catch (e: IllegalArgumentException) {
                throw DevTunnelsException.Malformed("body is not a JSON object", e)
            }
        }
    }

    internal companion object {
        /**
         * Cluster-specific service URL: the leading `global` label of the host becomes the cluster id
         * (`global.rel.tunnels...` to `usw2.rel.tunnels...`). Any other host, such as a local test server, is used as given.
         */
        fun clusterUrl(base: HttpUrl, clusterId: String): HttpUrl =
            if (base.host.startsWith("global.")) {
                base.newBuilder().host(clusterId + base.host.substring(base.host.indexOf('.'))).build()
            } else {
                base
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
