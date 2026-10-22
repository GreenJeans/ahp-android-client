package dev.ahpclient.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

/**
 * Keeps the vault's one blob in a Preferences DataStore, as Base64 text. The blob is already encrypted; this class only
 * persists it. A text value that is not valid Base64 reads back as empty bytes, which the vault then treats as unreadable.
 */
class DataStoreBlobStore(private val dataStore: DataStore<Preferences>) : BlobStore {
    override suspend fun read(): ByteArray? {
        val text = dataStore.data.first()[KEY] ?: return null
        return try {
            Base64.getDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            ByteArray(0)
        }
    }

    override suspend fun write(blob: ByteArray) {
        dataStore.edit { it[KEY] = Base64.getEncoder().encodeToString(blob) }
    }

    override suspend fun delete() {
        dataStore.edit { it.remove(KEY) }
    }

    companion object {
        private val KEY = stringPreferencesKey("token_v1")

        /** A damaged file is replaced with an empty one rather than crashing the app; the user just signs in again. */
        fun dataStoreFor(file: File, scope: CoroutineScope): DataStore<Preferences> = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { file },
        )
    }
}

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
