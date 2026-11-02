package dev.ahpclient.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File
import kotlinx.coroutines.CoroutineScope

fun DataStoreBlobStore.Companion.dataStoreFor(file: File, scope: CoroutineScope): DataStore<Preferences> =
    dataStoreForPath(file.absolutePath, scope)

/**
 * The production vault: Keystore key, DataStore file in app-private storage. Create it once per process, because
 * DataStore allows only one active instance per file.
 */
object AndroidTokenVault {
    fun create(context: Context, scope: CoroutineScope): TokenVault {
        val file = context.applicationContext.preferencesDataStoreFile("ahp_tokens")
        return TokenVault(KeystoreSecureBox(), DataStoreBlobStore(DataStoreBlobStore.dataStoreFor(file, scope)))
    }
}
