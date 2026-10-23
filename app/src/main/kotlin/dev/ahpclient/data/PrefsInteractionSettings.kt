package dev.ahpclient.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dev.ahpclient.app.InteractionSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** Whether the user has allowed this app to send messages, answers and approvals as them. Off until they turn it on. Not a secret. */
class PrefsInteractionSettings(private val dataStore: DataStore<Preferences>) : InteractionSettings {
    // A damaged preferences file reads as "off": the permission is never granted by accident.
    override val enabled: Flow<Boolean> = dataStore.data.map { it[KEY] ?: false }.catch { emit(false) }

    override suspend fun setEnabled(on: Boolean) {
        dataStore.edit { it[KEY] = on }
    }

    private companion object {
        val KEY = booleanPreferencesKey("allow_interaction")
    }
}
