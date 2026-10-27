package dev.ahpclient.security

import dev.ahpclient.auth.Secret
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The connection tokens of the direct hosts, encrypted at rest: one blob holding every host's token by its id, sealed for this purpose
 * alone ([AAD]) through the same [SecureBox] and [BlobStore] seams as the GitHub sign-in. Give it a box and a store of its own: the
 * GitHub vault destroys its key at sign-out, and a direct host's token must outlive that.
 *
 * A blob that cannot be opened (the key is gone, the blob is damaged or was sealed for something else) is removed with its key and reads
 * as no tokens; the hosts stay in the list and ask for their token again. Nothing here ever returns or logs a token except through [Secret].
 */
class DirectHostVault(private val box: SecureBox, private val store: BlobStore) {
    private val lock = Mutex()

    /** The token saved for [hostId], or null when there is none or the vault could not be read. */
    suspend fun get(hostId: String): Secret? = lock.withLock { readAll()[hostId]?.let(::Secret) }

    /**
     * Saves [token] for [hostId], replacing an earlier one. A blank token removes it instead (a host that asks for none keeps nothing).
     *
     * @throws IOException when the store cannot be written.
     * @throws VaultCryptoException when the tokens cannot be sealed even with a new key.
     */
    suspend fun put(hostId: String, token: Secret) = lock.withLock {
        val all = readAll().toMutableMap()
        if (token.reveal().isBlank()) all.remove(hostId) else all[hostId] = token.reveal()
        writeAll(all)
    }

    /** Forgets the token of [hostId]. Safe when there is none. */
    suspend fun remove(hostId: String) = lock.withLock {
        val all = readAll().toMutableMap()
        if (all.remove(hostId) != null) writeAll(all)
    }

    /** Whether a token is saved for [hostId]. */
    suspend fun has(hostId: String): Boolean = lock.withLock { hostId in readAll() }

    private suspend fun readAll(): Map<String, String> {
        val blob = store.read() ?: return emptyMap()
        return try {
            val plain = box.open(blob, AAD)
            try {
                decode(plain)
            } finally {
                plain.fill(0)
            }
        } catch (_: VaultCryptoException) {
            discard()
        } catch (_: IllegalArgumentException) {
            discard()
        }
    }

    private suspend fun discard(): Map<String, String> {
        store.delete()
        box.destroyKey()
        return emptyMap()
    }

    private suspend fun writeAll(tokens: Map<String, String>) {
        if (tokens.isEmpty()) {
            store.delete()
            box.destroyKey()
            return
        }
        val plain = encode(tokens)
        try {
            val blob = try {
                box.seal(plain, AAD)
            } catch (_: VaultCryptoException) {
                // An unusable key must not stop a token being saved: replace it once, then let a second failure surface.
                box.destroyKey()
                box.seal(plain, AAD)
            }
            store.write(blob)
        } finally {
            plain.fill(0)
        }
    }

    private fun encode(tokens: Map<String, String>): ByteArray {
        val json = buildJsonObject {
            put("v", VERSION)
            put("tokens", buildJsonObject { tokens.forEach { (id, token) -> put(id, token) } })
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    private fun decode(plain: ByteArray): Map<String, String> {
        val root = Json.parseToJsonElement(plain.toString(Charsets.UTF_8)) as? JsonObject ?: throw IllegalArgumentException("not an object")
        require((root["v"] as? JsonPrimitive)?.contentOrNull == VERSION.toString()) { "unknown version" }
        val tokens = root["tokens"] as? JsonObject ?: throw IllegalArgumentException("no tokens")
        return tokens.mapNotNull { (id, value) -> (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let { id to it } }.toMap()
    }

    companion object {
        val AAD: ByteArray = "dev.ahpclient/direct-host-tokens/v1".toByteArray(Charsets.UTF_8)
        private const val VERSION = 1
    }
}
