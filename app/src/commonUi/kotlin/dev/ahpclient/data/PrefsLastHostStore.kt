package dev.ahpclient.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.ahpclient.app.LastHostStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import okio.Path.Companion.toPath

/** The id of the last host the user opened. Not a secret, so it lives in an ordinary preferences file, apart from the token. */
class PrefsLastHostStore(private val dataStore: DataStore<Preferences>) : LastHostStore {
    override suspend fun get(): String? = dataStore.data.first()[KEY]

    override suspend fun set(tunnelId: String) {
        dataStore.edit { it[KEY] = tunnelId }
    }

    companion object {
        private val KEY = stringPreferencesKey("last_host_id")

        fun dataStoreForPath(path: String, scope: CoroutineScope): DataStore<Preferences> = PreferenceDataStoreFactory.createWithPath(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { path.toPath() },
        )
    }
}
