package dev.ahpclient.tunnel

import dev.ahpclient.platform.base64UrlEncodeNoPad
import dev.ahpclient.platform.sha256

object ConnectionToken {
    /**
     * Port of `deriveConnectionToken` in VS Code `tunnelAgentHostConnector.ts`: base64url(SHA-256(tunnelId)) without
     * padding, prefixed with `a` when it would start with `-`. Protocol v5 tunnels expect it as `/?tkn=<token>`.
     *
     * It is derived from the public tunnel id, so it is not a secret: real access control is the Dev Tunnels
     * `connect` token on the relay.
     */
    fun derive(tunnelId: String): String {
        val digest = sha256(tunnelId.encodeToByteArray())
        val token = base64UrlEncodeNoPad(digest)
        return if (token.startsWith("-")) "a$token" else token
    }
}
