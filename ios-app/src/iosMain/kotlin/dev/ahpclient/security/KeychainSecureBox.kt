@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.ahpclient.security

import dev.ahpclient.platform.IOException
import dev.ahpclient.platform.sha256
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecRandomDefault
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

/**
 * iOS Keychain-backed [SecureBox] implementation for `TokenVault`, `DirectHostVault`, and `SessionListCache`.
 *
 * Stores a 256-bit random master key in the iOS Keychain under [kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
 * (device-bound, never synced to iCloud Backup or iCloud Keychain) and seals/opens blobs with authenticated
 * encryption (16-byte random IV + SHA-256 CTR keystream + 32-byte HMAC-SHA256 authentication tag).
 */
class KeychainSecureBox(
    private val keyAccount: String = DEFAULT_KEY_ACCOUNT,
    private val serviceName: String = DEFAULT_SERVICE_NAME,
) : SecureBox {

    override fun seal(plaintext: ByteArray): ByteArray {
        val masterKey = loadOrCreateKey()
        try {
            val iv = randomBytes(IV_SIZE)
            val encKey = hmacSha256(masterKey, "enc".encodeToByteArray() + iv)
            val macKey = hmacSha256(masterKey, "mac".encodeToByteArray() + iv)
            val ciphertext = xorKeystream(plaintext, encKey, iv)
            val tag = hmacSha256(macKey, byteArrayOf(VERSION) + iv + ciphertext)
            val out = ByteArray(1 + IV_SIZE + ciphertext.size + TAG_SIZE)
            out[0] = VERSION
            iv.copyInto(out, destinationOffset = 1)
            ciphertext.copyInto(out, destinationOffset = 1 + IV_SIZE)
            tag.copyInto(out, destinationOffset = 1 + IV_SIZE + ciphertext.size)
            return out
        } finally {
            masterKey.fill(0)
        }
    }

    override fun open(blob: ByteArray): ByteArray {
        if (blob.size < 1 + IV_SIZE + TAG_SIZE) {
            throw CorruptBlobException("Encrypted blob is too short (${blob.size} bytes)")
        }
        if (blob[0] != VERSION) {
            throw CorruptBlobException("Unsupported encrypted blob version: ${blob[0]}")
        }
        val masterKey = loadExistingKey() ?: throw KeyInvalidatedException("Keychain key not found for $keyAccount")
        try {
            val iv = blob.copyOfRange(1, 1 + IV_SIZE)
            val cipherLen = blob.size - 1 - IV_SIZE - TAG_SIZE
            val ciphertext = blob.copyOfRange(1 + IV_SIZE, 1 + IV_SIZE + cipherLen)
            val tag = blob.copyOfRange(1 + IV_SIZE + cipherLen, blob.size)
            val encKey = hmacSha256(masterKey, "enc".encodeToByteArray() + iv)
            val macKey = hmacSha256(masterKey, "mac".encodeToByteArray() + iv)
            val expectedTag = hmacSha256(macKey, byteArrayOf(VERSION) + iv + ciphertext)
            if (!constantTimeEquals(tag, expectedTag)) {
                throw CorruptBlobException("Encrypted blob authentication tag mismatch")
            }
            return xorKeystream(ciphertext, encKey, iv)
        } finally {
            masterKey.fill(0)
        }
    }

    override fun destroyKey() {
        val query = CFDictionaryCreateMutable(null, 4, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        try {
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(query, kSecAttrService, kotlinx.cinterop.objcPtr(serviceName))
            CFDictionaryAddValue(query, kSecAttrAccount, kotlinx.cinterop.objcPtr(keyAccount))
            SecItemDelete(query)
        } finally {
            CFRelease(query)
        }
    }

    private fun loadOrCreateKey(): ByteArray {
        loadExistingKey()?.let { return it }
        val freshKey = randomBytes(KEY_SIZE)
        val nsData = freshKey.toNSData()
        val attrs = CFDictionaryCreateMutable(null, 6, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        try {
            CFDictionaryAddValue(attrs, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(attrs, kSecAttrService, kotlinx.cinterop.objcPtr(serviceName))
            CFDictionaryAddValue(attrs, kSecAttrAccount, kotlinx.cinterop.objcPtr(keyAccount))
            CFDictionaryAddValue(attrs, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
            CFDictionaryAddValue(attrs, kSecValueData, kotlinx.cinterop.objcPtr(nsData))
            val status = SecItemAdd(attrs, null)
            if (status == errSecSuccess) {
                return freshKey
            }
            // If another thread created it concurrently, read it back.
            loadExistingKey()?.let {
                freshKey.fill(0)
                return it
            }
            freshKey.fill(0)
            throw KeyInvalidatedException("SecItemAdd failed with OSStatus $status")
        } finally {
            CFRelease(attrs)
        }
    }

    private fun loadExistingKey(): ByteArray? = memScoped {
        val query = CFDictionaryCreateMutable(null, 6, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        try {
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(query, kSecAttrService, kotlinx.cinterop.objcPtr(serviceName))
            CFDictionaryAddValue(query, kSecAttrAccount, kotlinx.cinterop.objcPtr(keyAccount))
            CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
            val resultRef = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, resultRef.ptr)
            if (status == errSecItemNotFound) return null
            if (status != errSecSuccess) {
                throw KeyInvalidatedException("SecItemCopyMatching failed with OSStatus $status")
            }
            val data = kotlinx.cinterop.interpretObjCPointerOrNull<NSData>(resultRef.value?.rawValue ?: return null) ?: return null
            val bytes = data.toByteArray()
            if (bytes.size != KEY_SIZE) {
                throw KeyInvalidatedException("Unexpected Keychain key length: ${bytes.size}")
            }
            bytes
        } finally {
            CFRelease(query)
        }
    }

    private fun randomBytes(size: Int): ByteArray {
        val out = ByteArray(size)
        out.usePinned { pinned ->
            val status = SecRandomCopyBytes(kSecRandomDefault, size.toULong(), pinned.addressOf(0))
            if (status != errSecSuccess) {
                throw KeyInvalidatedException("SecRandomCopyBytes failed with status $status")
            }
        }
        return out
    }

    private fun xorKeystream(input: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val out = ByteArray(input.size)
        var counter = 0
        var offset = 0
        val counterBytes = ByteArray(4)
        while (offset < input.size) {
            counterBytes[0] = ((counter ushr 24) and 0xFF).toByte()
            counterBytes[1] = ((counter ushr 16) and 0xFF).toByte()
            counterBytes[2] = ((counter ushr 8) and 0xFF).toByte()
            counterBytes[3] = (counter and 0xFF).toByte()
            val block = hmacSha256(key, iv + counterBytes)
            val take = minOf(block.size, input.size - offset)
            for (i in 0 until take) {
                out[offset + i] = (input[offset + i].toInt() xor block[i].toInt()).toByte()
            }
            offset += take
            counter++
        }
        return out
    }

    private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val blockSize = 64
        val k = if (key.size > blockSize) sha256(key) else key
        val ipad = ByteArray(blockSize) { i -> ((if (i < k.size) k[i].toInt() else 0) xor 0x36).toByte() }
        val opad = ByteArray(blockSize) { i -> ((if (i < k.size) k[i].toInt() else 0) xor 0x5c).toByte() }
        val inner = sha256(ipad + message)
        return sha256(opad + inner)
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }

    companion object {
        const val DEFAULT_SERVICE_NAME = "com.greenjeans.ahpclient.keys"
        const val DEFAULT_KEY_ACCOUNT = "ahp-ios-client.master-key.v1"
        private const val VERSION: Byte = 1
        private const val KEY_SIZE = 32
        private const val IV_SIZE = 16
        private const val TAG_SIZE = 32
    }
}

/**
 * Atomic file-backed [BlobStore] on iOS inside the app's no-backup Application Support directory.
 */
class IosFileBlobStore(private val filePath: String) : BlobStore {
    override suspend fun read(): ByteArray? {
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(filePath)) return null
        val data = NSData.dataWithContentsOfFile(filePath) ?: throw IOException("Could not read $filePath")
        return data.toByteArray()
    }

    override suspend fun write(blob: ByteArray) {
        val data = blob.toNSData()
        if (!data.writeToFile(filePath, atomically = true)) {
            throw IOException("Could not atomically write $filePath")
        }
    }

    override suspend fun delete() {
        val manager = NSFileManager.defaultManager
        if (manager.fileExistsAtPath(filePath)) {
            manager.removeItemAtPath(filePath, error = null)
        }
    }

    companion object {
        fun noBackupFilePath(fileName: String): String {
            val paths = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
            val baseDir = (paths.firstOrNull() as? String) ?: ""
            val noBackupDir = "$baseDir/no_backup"
            val manager = NSFileManager.defaultManager
            if (!manager.fileExistsAtPath(noBackupDir)) {
                manager.createDirectoryAtPath(noBackupDir, withIntermediateDirectories = true, attributes = null, error = null)
                val url = NSURL.fileURLWithPath(noBackupDir)
                url.setResourceValue(true, forKey = NSURLIsExcludedFromBackupKey, error = null)
            }
            return "$noBackupDir/$fileName"
        }
    }
}

internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
        }
    }

internal fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val out = ByteArray(len)
    val ptr = bytes ?: return ByteArray(0)
    out.usePinned { pinned ->
        memcpy(pinned.addressOf(0), ptr, len.toULong())
    }
    return out
}
