package dev.ahpclient.security

import com.microsoft.agenthostprotocol.Ahp
import com.microsoft.agenthostprotocol.generated.SessionSummary
import dev.ahpclient.platform.IOException
import dev.ahpclient.platform.INSTANT_EPOCH
import dev.ahpclient.platform.Instant
import dev.ahpclient.platform.instantNow
import dev.ahpclient.platform.parseInstant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The host's session list kept encrypted on the phone, so a cold start has something to show while the connection is made. It is the
 * same kind of blob as the token in [TokenVault], through the same [SecureBox] and [BlobStore] seams, but sealed for its own purpose
 * ([AAD]) and kept in a store of its own: a token blob can never be opened as a list, nor a list as a token.
 *
 * The list is for display only and is held for one host, named by the tunnel id it was saved under. At most [MAX_ITEMS] sessions are
 * kept (the most recently changed) and at most [MAX_BYTES] of encoded content. Anything that cannot be read back (damaged, sealed for
 * something else, from another version, or saved for another host) is removed and reads as absent; it is never an error to the caller.
 * The key belongs to the vault as well, so this class never destroys it.
 */
class SessionListCache(private val box: SecureBox, private val store: BlobStore) {
    private val lock = Mutex()

    /** The list saved for [tunnelId], or null when there is none, it is for another host, or it could not be read. Never throws, apart from cancellation. */
    suspend fun load(tunnelId: String): List<SessionSummary>? = lock.withLock {
        val blob = try {
            store.read()
        } catch (_: IOException) {
            // A transient disk error says nothing about the blob, so it is left alone.
            return@withLock null
        } ?: return@withLock null
        val items = try {
            decode(box.open(blob, AAD), tunnelId)
        } catch (_: VaultCryptoException) {
            null
        }
        if (items == null) discard()
        items
    }

    /**
     * Replaces what is kept with [items] for [tunnelId], cut to the [MAX_ITEMS] most recently changed and to [MAX_BYTES].
     *
     * @throws IOException when the store cannot be written.
     * @throws VaultCryptoException when the list cannot be sealed. The key is left as it is.
     */
    suspend fun save(tunnelId: String, items: List<SessionSummary>) = lock.withLock {
        val plain = encode(tunnelId, items)
        try {
            store.write(box.seal(plain, AAD))
        } finally {
            plain.fill(0)
        }
    }

    /** Removes the saved list. Sign-out calls this beside the vault wipe, so that no session title stays on the phone. */
    suspend fun clear() = lock.withLock { store.delete() }

    private suspend fun discard() {
        try {
            store.delete()
        } catch (_: IOException) {
            // Unreadable either way: the next load finds it again and tries again.
        }
    }

    private fun encode(tunnelId: String, items: List<SessionSummary>): ByteArray {
        val newest = items.sortedByDescending { it.modifiedAt }.take(MAX_ITEMS)
        val encoded = ArrayList<JsonElement>()
        var bytes = ENVELOPE_BYTES + tunnelId.encodeToByteArray().size
        for (item in newest) {
            val element = Ahp.json.encodeToJsonElement(SessionSummary.serializer(), item)
            // Compact JSON, so its text is what ends up in the blob; one more byte for the comma.
            bytes += element.toString().encodeToByteArray().size + 1
            if (bytes > MAX_BYTES) break
            encoded += element
        }
        val json = buildJsonObject {
            put("v", VERSION)
            put("tunnelId", tunnelId)
            put("savedAt", instantNow().toString())
            put("items", JsonArray(encoded))
        }
        return json.toString().encodeToByteArray()
    }

    /** Null when [bytes] is not a list of this version for [tunnelId]. */
    private fun decode(bytes: ByteArray, tunnelId: String): List<SessionSummary>? {
        try {
            val root = Ahp.json.parseToJsonElement(bytes.decodeToString()).jsonObject
            val version = (root["v"] as? JsonPrimitive)?.intOrNull
            if (version != VERSION || root.string("tunnelId") != tunnelId) return null
            val items = root["items"]?.jsonArray ?: return null
            return items.map { Ahp.json.decodeFromJsonElement(SessionSummary.serializer(), it) }
        } catch (_: IllegalArgumentException) {
            return null
        } finally {
            bytes.fill(0)
        }
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    companion object {
        val AAD: ByteArray = "dev.ahpclient/session-list-v1".encodeToByteArray()

        /** More sessions than this are cut to the most recently changed. */
        const val MAX_ITEMS = 500

        /** The most encoded content kept; once over it, the oldest sessions are dropped. */
        const val MAX_BYTES = 2 * 1024 * 1024

        private const val VERSION = 1

        /** Room for the version, the time and the braces around the list. */
        private const val ENVELOPE_BYTES = 128
    }
}
