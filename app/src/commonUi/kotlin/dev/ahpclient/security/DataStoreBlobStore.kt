package dev.ahpclient.security

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import okio.Path.Companion.toPath

/**
 * Keeps the vault's one blob in a Preferences DataStore, as Base64 text. The blob is already encrypted; this class only
 * persists it. A text value that is not valid Base64 reads back as empty bytes, which the vault then treats as unreadable.
 */
@OptIn(ExperimentalEncodingApi::class)
class DataStoreBlobStore(private val dataStore: DataStore<Preferences>) : BlobStore {
    override suspend fun read(): ByteArray? {
        val text = dataStore.data.first()[KEY] ?: return null
        return try {
            Base64.Default.decode(text)
        } catch (_: IllegalArgumentException) {
            ByteArray(0)
        }
    }

    override suspend fun write(blob: ByteArray) {
        dataStore.edit { it[KEY] = Base64.Default.encode(blob) }
    }

    override suspend fun delete() {
        dataStore.edit { it.remove(KEY) }
    }

    companion object {
        private val KEY = stringPreferencesKey("token_v1")

        /** A damaged file is replaced with an empty one rather than crashing the app; the user just signs in again. */
        fun dataStoreForPath(path: String, scope: CoroutineScope): DataStore<Preferences> = PreferenceDataStoreFactory.createWithPath(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { path.toPath() },
        )
    }
}
