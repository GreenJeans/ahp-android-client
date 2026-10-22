package dev.ahpclient.tunnel

import java.security.MessageDigest
import java.util.Base64

object ConnectionToken {
    /**
     * Port of `deriveConnectionToken` in VS Code `tunnelAgentHostConnector.ts`: base64url(SHA-256(tunnelId)) without
     * padding, prefixed with `a` when it would start with `-`. Protocol v5 tunnels expect it as `/?tkn=<token>`.
     *
     * It is derived from the public tunnel id, so it is not a secret: real access control is the Dev Tunnels
     * `connect` token on the relay.
     */
    fun derive(tunnelId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(tunnelId.toByteArray(Charsets.UTF_8))
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return if (token.startsWith("-")) "a$token" else token
    }
}
