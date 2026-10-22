package dev.ahpclient.security

import dev.ahpclient.auth.GitHubToken
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Encryption or decryption failed: the key is gone or unusable, or the blob is damaged or was sealed for something else. */
class VaultCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Authenticated encryption with a device-bound key. The Android implementation uses AES-256-GCM in the Keystore. */
interface SecureBox {
    /** @param aad authenticated but not encrypted: binds the blob to its purpose so it cannot be moved to another slot. */
    @Throws(VaultCryptoException::class)
    fun seal(plain: ByteArray, aad: ByteArray): ByteArray

    @Throws(VaultCryptoException::class)
    fun open(blob: ByteArray, aad: ByteArray): ByteArray

    /** Deletes the key, making every blob sealed with it permanently unreadable. Safe to call when there is no key. */
    fun destroyKey()
}

/** One opaque blob, persisted. An I/O failure is thrown as it is: a transient disk error must not destroy a good credential. */
interface BlobStore {
    suspend fun read(): ByteArray?

    suspend fun write(blob: ByteArray)

    suspend fun delete()
}

sealed interface VaultLoad {
    data class Present(val token: GitHubToken) : VaultLoad

    data object Absent : VaultLoad

    /** A token was stored but could not be read, so it has been wiped. The user must sign in again. [reason] names the failure, never the data. */
    data class Unreadable(val reason: String) : VaultLoad
}

/**
 * Stores the GitHub user token encrypted at rest. The token is JSON ([TokenCodec]) sealed by a [SecureBox] and kept as
 * one blob in a [BlobStore]. Anything that cannot be opened is treated as lost, not as an error to retry: the blob and
 * the key are wiped and the caller is told to sign in again.
 */
class TokenVault(private val box: SecureBox, private val store: BlobStore) {
    private val lock = Mutex()

    suspend fun save(token: GitHubToken) = lock.withLock {
        val plain = TokenCodec.encode(token)
        try {
            val blob = try {
                box.seal(plain, AAD)
            } catch (_: VaultCryptoException) {
                // An unusable key must not block signing in again: replace it once, then let a second failure surface.
                box.destroyKey()
                box.seal(plain, AAD)
            }
            store.write(blob)
        } finally {
            plain.fill(0)
        }
    }

    suspend fun load(): VaultLoad = lock.withLock {
        val blob = store.read() ?: return@withLock VaultLoad.Absent
        try {
            val plain = box.open(blob, AAD)
            try {
                VaultLoad.Present(TokenCodec.decode(plain))
            } finally {
                plain.fill(0)
            }
        } catch (e: VaultCryptoException) {
            discard("the stored token could not be decrypted (${e.message})")
        } catch (e: TokenCodecException) {
            discard("the stored token could not be read (${e.message})")
        }
    }

    /** Sign-out: the blob first, then the key, so a crash in between leaves only a harmless key. */
    suspend fun clear() = lock.withLock {
        store.delete()
        box.destroyKey()
    }

    private suspend fun discard(reason: String): VaultLoad.Unreadable {
        store.delete()
        box.destroyKey()
        return VaultLoad.Unreadable(reason)
    }

    companion object {
        val AAD: ByteArray = "dev.ahpclient/github-token/v1".toByteArray(Charsets.UTF_8)
    }
}
