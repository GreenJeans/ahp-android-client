package dev.ahpclient.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dev.ahpclient.app.MonitorSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** Whether to keep watching in the background. Off until the user turns it on. Not a secret. */
class PrefsMonitorSettings(private val dataStore: DataStore<Preferences>) : MonitorSettings {
    // A damaged preferences file reads as "off": the safe default, since monitoring costs battery.
    override val enabled: Flow<Boolean> = dataStore.data.map { it[KEY] ?: false }.catch { emit(false) }

    override suspend fun setEnabled(on: Boolean) {
        dataStore.edit { it[KEY] = on }
    }

    private companion object {
        val KEY = booleanPreferencesKey("monitor_in_background")
    }
}
