package dev.ahpclient.security

import dev.ahpclient.auth.GitHubToken
import dev.ahpclient.auth.Secret
import dev.ahpclient.platform.DateTimeException
import dev.ahpclient.platform.INSTANT_EPOCH
import dev.ahpclient.platform.Instant
import dev.ahpclient.platform.instantNow
import dev.ahpclient.platform.parseInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** The stored token bytes are not something this version can read. Carries no secret material. */
class TokenCodecException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The bytes that get encrypted. Versioned so a future format change can tell old blobs apart instead of misreading them. */
object TokenCodec {
    private const val VERSION = 1

    fun encode(token: GitHubToken): ByteArray {
        val json = buildJsonObject {
            put("v", VERSION)
            put("access", token.access.reveal())
            token.refresh?.let { put("refresh", it.reveal()) }
            token.expiresAt?.let { put("expiresAt", it.toString()) }
        }
        return json.toString().encodeToByteArray()
    }

    fun decode(bytes: ByteArray): GitHubToken {
        try {
            val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            val version = (root["v"] as? JsonPrimitive)?.intOrNull
            if (version != VERSION) throw TokenCodecException("unsupported token format version ${version ?: "(none)"}")
            val access = root.string("access") ?: throw TokenCodecException("stored token has no access token")
            return GitHubToken(
                access = Secret(access),
                refresh = root.string("refresh")?.let(::Secret),
                expiresAt = root.string("expiresAt")?.let(::parseInstant),
            )
        } catch (e: TokenCodecException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw TokenCodecException("stored token is not valid JSON", e)
        } catch (e: DateTimeException) {
            throw TokenCodecException("stored token has an invalid expiry", e)
        }
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
