package dev.ahpclient.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a key that lives in the Android Keystore and never leaves it. The key needs no user authentication,
 * because background monitoring must be able to read the token while the phone is locked.
 *
 * Blob layout: `[format 1 byte][IV 12 bytes][ciphertext + 16 byte tag]`. A fresh random IV is generated for every seal.
 * `AndroidKeyStore` raises a range of exceptions when a key is lost or unusable (`KeyPermanentlyInvalidatedException`,
 * `UnrecoverableKeyException`, `ProviderException`, ...); all of them become [VaultCryptoException], which the vault
 * treats as "sign in again".
 *
 * Do not use `androidx.security:security-crypto`: it is deprecated.
 */
class KeystoreSecureBox(private val alias: String = DEFAULT_ALIAS) : SecureBox {
    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    override fun seal(plain: ByteArray, aad: ByteArray): ByteArray = guard("seal") {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, existingOrNewKey())
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plain)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected IV length ${iv.size}" }
        byteArrayOf(FORMAT) + iv + ciphertext
    }

    override fun open(blob: ByteArray, aad: ByteArray): ByteArray = guard("open") {
        if (blob.size < MIN_BLOB_BYTES) throw VaultCryptoException("stored blob is too short")
        if (blob[0] != FORMAT) throw VaultCryptoException("unsupported blob format ${blob[0]}")
        val key = existingKey() ?: throw VaultCryptoException("the encryption key is missing")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 1, IV_BYTES))
        cipher.updateAAD(aad)
        cipher.doFinal(blob, 1 + IV_BYTES, blob.size - 1 - IV_BYTES)
    }

    @Synchronized
    override fun destroyKey() {
        try {
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        } catch (_: GeneralSecurityException) {
            // Nothing more can be done: an entry that cannot be deleted also cannot be used to read the blob we just dropped.
        }
    }

    @Synchronized
    private fun existingOrNewKey(): SecretKey = existingKey() ?: generateKey()

    private fun existingKey(): SecretKey? = keyStore.getKey(alias, null) as? SecretKey

    private fun generateKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .setUserAuthenticationRequired(false)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply { init(spec) }.generateKey()
    }

    private inline fun <T> guard(operation: String, block: () -> T): T = try {
        block()
    } catch (e: VaultCryptoException) {
        throw e
    } catch (e: GeneralSecurityException) {
        throw VaultCryptoException("$operation failed: ${e.javaClass.simpleName}", e)
    } catch (e: ProviderException) {
        throw VaultCryptoException("$operation failed: ${e.javaClass.simpleName}", e)
    } catch (e: IllegalStateException) {
        throw VaultCryptoException("$operation failed: ${e.message}", e)
    }

    companion object {
        const val DEFAULT_ALIAS = "ahp-android-client.token-key.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT: Byte = 1
        private const val KEY_BITS = 256
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val MIN_BLOB_BYTES = 1 + IV_BYTES + TAG_BITS / 8
    }
}
