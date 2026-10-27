package dev.ahpclient.state

import dev.ahpclient.ahp.AhpConnection
import dev.ahpclient.ahp.CapabilityPolicy
import dev.ahpclient.auth.Secret
import dev.ahpclient.direct.DirectHost
import dev.ahpclient.direct.HostAddress
import dev.ahpclient.direct.HostAddressCheck
import dev.ahpclient.tunnel.SocketOpener
import dev.ahpclient.tunnel.UpgradeRejectedException
import java.io.IOException
import kotlinx.coroutines.CoroutineScope

/** A direct host cannot be connected to as saved: its address is no longer allowed, or it needs a token that is not there. Retrying cannot help. */
class DirectHostException(message: String) : IOException(message)

/**
 * Connects to a host by its address, the way `ahpd` and `code agent host` are reached: one WebSocket to [host], the connection token
 * presented as `Authorization: Bearer` (never in the address, where it would end up in logs and history), then AHP under [policy]. No
 * GitHub token and no Dev Tunnels call are involved.
 *
 * The address is checked again on every attempt ([HostAddress.check]), so a saved address that the rules refuse is never opened. [token]
 * is asked for on every attempt and may be null for a host that takes none. [policy] is asked for on every attempt so a changed
 * permission applies to the next connection; it defaults to read-only and a higher tier is something the caller grants on purpose.
 */
class DirectHostConnector(
    private val opener: SocketOpener,
    private val host: DirectHost,
    private val token: suspend () -> Secret?,
    private val policy: () -> CapabilityPolicy = { CapabilityPolicy.READ },
) : HostConnector {
    override suspend fun connect(clientId: String, initialServerSeq: Long, scope: CoroutineScope): AhpConnection {
        val checked = HostAddress.check(host.url)
        val url = (checked as? HostAddressCheck.Ok)?.url ?: throw DirectHostException((checked as HostAddressCheck.Refused).reason)
        val secret = token()?.takeIf { it.reveal().isNotBlank() }
        val headers = if (secret != null) mapOf("Authorization" to "Bearer ${secret.reveal()}") else emptyMap()
        val socket = try {
            opener.open(url, headers)
        } catch (e: UpgradeRejectedException) {
            // Said in the person's words: the host's own refusal is all the status code is. Retrying with the same token cannot help.
            throw when (e.httpCode) {
                401, 403 -> DirectHostException(if (secret == null) "The host wants a connection token and none is saved for it." else "The host refused the connection token.")
                404 -> DirectHostException("The host answered, but not at that address. Check the address.")
                else -> e
            }
        }
        return AhpConnection(socket, scope, policy(), clientId, initialServerSeq)
    }
}
