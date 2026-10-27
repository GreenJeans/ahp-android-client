package dev.ahpclient.security

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps one already-encrypted blob as a file of its own, replaced whole so that a crash mid-write leaves the old one or the new one, never
 * half of either. A plain file rather than a DataStore because the whole point of removing it is that nothing is left behind.
 */
class FileBlobStore(file: File) : BlobStore {
    private val atomic = AtomicFile(file)

    override suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            atomic.readFully()
        } catch (_: FileNotFoundException) {
            null
        }
    }

    override suspend fun write(blob: ByteArray) = withContext(Dispatchers.IO) {
        val out = atomic.startWrite()
        try {
            out.write(blob)
            atomic.finishWrite(out)
        } catch (e: IOException) {
            atomic.failWrite(out)
            throw e
        }
    }

    override suspend fun delete() = withContext(Dispatchers.IO) { atomic.delete() }
}

/**
 * The production session list cache: the Keystore key the vault uses, sealing a file in the app's no-backup directory. Create it once per
 * process and hand the same instance to everything that reads, writes or wipes the list.
 */
object AndroidSessionListCache {
    fun create(context: Context): SessionListCache =
        SessionListCache(KeystoreSecureBox(), FileBlobStore(File(context.applicationContext.noBackupFilesDir, "session_list.bin")))
}
