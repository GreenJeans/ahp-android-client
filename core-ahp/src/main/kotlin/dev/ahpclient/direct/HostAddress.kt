package dev.ahpclient.direct

import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException

/**
 * A host the person added by its address, as `ahpd` and VS Code's `code agent host` are reached: a plain WebSocket and a connection
 * token, with no Dev Tunnel and no GitHub sign-in. [url] is always the normalised, checked address ([HostAddress.check]); the token is
 * not part of this value and is kept in the vault.
 */
data class DirectHost(val id: String, val name: String, val url: String) {
    companion object {
        /** Ids of direct hosts start with this, so they can never be mistaken for a tunnel id in the one "last host" setting. */
        const val ID_PREFIX = "direct:"

        fun isDirectId(id: String?): Boolean = id != null && id.startsWith(ID_PREFIX)
    }
}

sealed interface HostAddressCheck {
    /** [url] is what to connect to; [cleartext] is true for `ws://`, which the screen says out loud before the first connection. */
    data class Ok(val url: String, val host: String, val cleartext: Boolean) : HostAddressCheck

    /** [reason] is written for the person who typed the address. */
    data class Refused(val reason: String) : HostAddressCheck
}

/**
 * Decides which addresses a direct host may have. An address is accepted when it is
 * - `wss://`, to any named host or address, because the connection is then encrypted and the host is who its certificate says; or
 * - `ws://` to this phone's loopback, a private network address (10/8, 172.16/12, 192.168/16, 100.64/10 for the mesh VPNs, the
 *   emulator's 10.0.2.2 being in 10/8) or an IPv6 loopback or unique-local address, because the traffic never leaves the person's own networks.
 *
 * A `ws://` address to a name is refused, because a name cannot be known to be private without a lookup that could answer differently
 * later. The token goes in its own field: an address that carries one (a query, a fragment or credentials) is refused so it is never
 * stored or shown beside the address.
 *
 * The same check runs when a host is added and again each time it is connected to, so what was saved never decides on its own.
 */
object HostAddress {
    const val DEFAULT_PORT = 9187
    const val MAX_LENGTH = 2_048

    fun check(input: String): HostAddressCheck {
        val text = input.trim()
        if (text.isEmpty()) return refused("Enter the host's address, for example 192.168.1.20:$DEFAULT_PORT.")
        if (text.length > MAX_LENGTH) return refused("That address is too long.")
        if (text.any { it.code < 0x21 || it.code > 0x7e }) return refused("The address can only hold plain ASCII letters, digits and punctuation, with no spaces.")

        val hasScheme = "://" in text
        val withScheme = if (hasScheme) text else "ws://$text"
        val uri = try {
            URI(withScheme)
        } catch (_: URISyntaxException) {
            return refused("That is not a valid address.")
        }
        val scheme = when (uri.scheme?.lowercase()) {
            "ws", "http" -> "ws"
            "wss", "https" -> "wss"
            else -> return refused("The address must start with ws:// or wss://.")
        }
        // A host that java.net.URI cannot read as a server name (look-alike letters, an underscore) has no host at all here.
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return refused("That address has no usable host name.")
        if (uri.userInfo != null) return refused("Leave the credentials out of the address. The token has its own field.")
        if (uri.rawQuery != null || uri.rawFragment != null) return refused("Leave the ?token= part out of the address. The token has its own field.")
        val path = uri.rawPath.orEmpty().trimEnd('/')
        if (path.isNotEmpty() && !path.all { it.isLetterOrDigit() || it in "/-._~%" }) return refused("That address has an unusual path.")

        val cleartext = scheme == "ws"
        if (cleartext && !isPrivate(host)) {
            return refused(
                "ws:// is not encrypted, so it is only allowed to this phone or a private network address " +
                    "(10.x, 172.16-31.x, 192.168.x, 100.64-127.x, or localhost). Use wss:// for anything else, or the host's IP address.",
            )
        }
        // A bare "host" or "host:port" is how ahpd is usually written down, and its port is not the web's: 9187 unless one was given.
        val port = when {
            uri.port != -1 -> uri.port
            !hasScheme -> DEFAULT_PORT
            else -> -1
        }
        if (port != -1 && port !in 1..65_535) return refused("That port is not valid.")
        val shownHost = if (':' in host && !host.startsWith("[")) "[$host]" else host
        val url = buildString {
            append(scheme).append("://").append(shownHost)
            if (port != -1) append(':').append(port)
            append(path)
        }
        return HostAddressCheck.Ok(url, host, cleartext)
    }

    /** Whether [host] (an address, as `java.net.URI` hands it over) is this phone itself or on a private network. A name is private only if it is `localhost`. */
    fun isPrivate(host: String): Boolean {
        val bare = host.removePrefix("[").removeSuffix("]").lowercase()
        if (bare == "localhost") return true
        ipv4(bare)?.let { (a, b) -> return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127) }
        if (':' in bare && bare.all { it in "0123456789abcdef:." }) {
            // Only ever a literal here, so this never asks the network anything.
            val bytes = try {
                InetAddress.getByName(bare).address
            } catch (_: java.net.UnknownHostException) {
                return false
            }
            if (bytes.size != 16) return false
            val loopback = bytes.take(15).all { it == 0.toByte() } && bytes[15] == 1.toByte()
            val uniqueLocal = (bytes[0].toInt() and 0xFE) == 0xFC
            return loopback || uniqueLocal
        }
        return false
    }

    private fun ipv4(text: String): Pair<Int, Int>? {
        val parts = text.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || !it.all(Char::isDigit) }) return null
        val numbers = parts.map { it.toInt() }
        if (numbers.any { it > 255 }) return null
        return numbers[0] to numbers[1]
    }

    private fun refused(reason: String): HostAddressCheck = HostAddressCheck.Refused(reason)
}
