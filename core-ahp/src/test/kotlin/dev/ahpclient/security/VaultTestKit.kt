package dev.ahpclient.security

import java.io.IOException

/** A reversible stand-in for Keystore AES-GCM that can lose its key or reject a blob, so vault rules can be tested on the JVM. */
internal class FakeBox : SecureBox {
    var keyExists = true
    var failNextSeals = 0
    var destroyed = 0
    var lastAad: ByteArray? = null

    override fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
        if (failNextSeals > 0) {
            failNextSeals--
            throw VaultCryptoException("key unusable")
        }
        keyExists = true
        lastAad = aad
        return MAGIC + plain.reversedArray()
    }

    override fun open(blob: ByteArray, aad: ByteArray): ByteArray {
        if (!keyExists) throw VaultCryptoException("no key")
        if (blob.size < MAGIC.size || !blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw VaultCryptoException("bad blob")
        if (!aad.contentEquals(lastAad)) throw VaultCryptoException("aad mismatch")
        return blob.copyOfRange(MAGIC.size, blob.size).reversedArray()
    }

    override fun destroyKey() {
        keyExists = false
        destroyed++
    }

    private companion object {
        val MAGIC = byteArrayOf(0x42, 0x58)
    }
}

internal class FakeStore : BlobStore {
    var blob: ByteArray? = null
    var readFailure: IOException? = null
    var deleteFailure: IOException? = null
    var deletes = 0

    override suspend fun read(): ByteArray? = readFailure?.let { throw it } ?: blob

    override suspend fun write(blob: ByteArray) {
        this.blob = blob
    }

    override suspend fun delete() {
        deleteFailure?.let { throw it }
        blob = null
        deletes++
    }
}
