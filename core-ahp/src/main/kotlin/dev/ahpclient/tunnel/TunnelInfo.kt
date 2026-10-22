package dev.ahpclient.tunnel

import dev.ahpclient.auth.Secret

/** Label VS Code puts on every tunnel that can host an agent host. */
const val TUNNEL_LAUNCHER_LABEL = "vscode-server-launcher"

/** Well-known forwarded port of the agent host on tunnel machines. */
const val AGENT_HOST_PORT = 31546

/** Tunnels below this launcher protocol version are not agent-host capable. */
const val MIN_PROTOCOL_VERSION = 5

/** From this launcher protocol version the agent-host port also serves the endpoint-selection gateway. */
const val GATEWAY_MIN_PROTOCOL_VERSION = 6

private const val PROTOCOL_TAG_PREFIX = "protocolv"

/**
 * Port of `TunnelTags` in VS Code `src/vs/platform/agentHost/common/tunnelAgentHost.ts`: the display name is the
 * first label that is not the launcher label, does not start with `_` and is not a `protocolvN` tag.
 */
class TunnelTags(labels: List<String>) {
    val protocolVersion: Int
    val name: String?

    init {
        var version: Int? = null
        var displayName: String? = null
        for (label in labels) {
            if (label.startsWith(PROTOCOL_TAG_PREFIX)) {
                label.removePrefix(PROTOCOL_TAG_PREFIX).toIntOrNull()?.let { version = it }
            } else if (!label.startsWith("_") && label != TUNNEL_LAUNCHER_LABEL && displayName == null) {
                displayName = label
            }
        }
        protocolVersion = version ?: DEFAULT_PROTOCOL_VERSION
        name = displayName
    }

    private companion object {
        const val DEFAULT_PROTOCOL_VERSION = 2
    }
}

data class TunnelInfo(
    val tunnelId: String,
    val clusterId: String,
    val name: String,
    val protocolVersion: Int,
    val hostConnectionCount: Int,
    val ports: List<Int>,
) {
    /** True while a VS Code instance is connected to the tunnel (Remote Tunnel Access is on). */
    val isOnline: Boolean get() = hostConnectionCount > 0

    /** Protocol v6+ tunnels use the `/agent-host/select` gateway; v5 uses the derived connection token. */
    val usesGateway: Boolean get() = protocolVersion >= GATEWAY_MIN_PROTOCOL_VERSION
}

/** Everything needed to open the agent-host socket through the tunnel's public port URL. */
data class ConnectTarget(
    val tunnel: TunnelInfo,
    /** For example `https://abc123-{port}.usw2.devtunnels.ms/`. */
    val portUriFormat: String,
    /** Dev Tunnels `connect`-scope access token. Memory only: fetch a fresh one on every (re)connect. */
    val connectToken: Secret,
)

sealed class DevTunnelsException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AuthExpired : DevTunnelsException("Dev Tunnels rejected the token (HTTP 401); sign in again")

    class Forbidden : DevTunnelsException("Dev Tunnels refused access (HTTP 403); the account may need to authorize SSO")

    class TunnelNotFound(tunnelId: String) : DevTunnelsException("Tunnel '$tunnelId' no longer exists")

    class Http(val code: Int) : DevTunnelsException("Dev Tunnels returned HTTP $code")

    class Malformed(detail: String, cause: Throwable? = null) : DevTunnelsException("Unexpected Dev Tunnels response: $detail", cause)
}
