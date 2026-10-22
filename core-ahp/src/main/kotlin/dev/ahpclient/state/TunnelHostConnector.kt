package dev.ahpclient.state

import dev.ahpclient.ahp.AhpConnection
import dev.ahpclient.ahp.CapabilityPolicy
import dev.ahpclient.auth.Secret
import dev.ahpclient.tunnel.DevTunnelsClient
import dev.ahpclient.tunnel.TunnelGateway
import dev.ahpclient.tunnel.TunnelInfo
import kotlinx.coroutines.CoroutineScope

/**
 * Connects to a Remote Tunnel Access host the way `vscode.dev/agents` does: a fresh connect token from the Dev Tunnels
 * service, the gateway handshake, then AHP under the read-only [CapabilityPolicy.READ].
 *
 * [accessToken] is called on every attempt so a refreshed sign-in is picked up without rebuilding the session.
 */
class TunnelHostConnector(
    private val devTunnels: DevTunnelsClient,
    private val gateway: TunnelGateway,
    private val tunnel: TunnelInfo,
    private val accessToken: suspend () -> Secret,
    private val instanceId: String? = null,
) : HostConnector {
    override suspend fun connect(clientId: String, initialServerSeq: Long, scope: CoroutineScope): AhpConnection {
        val target = devTunnels.resolveConnectTarget(accessToken(), tunnel)
        val opened = gateway.open(target, instanceId)
        return AhpConnection(opened.socket, scope, CapabilityPolicy.READ, clientId, initialServerSeq)
    }
}
