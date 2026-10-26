package dev.ahpclient.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.ahpclient.app.FinishedWatchSettings
import dev.ahpclient.state.FinishedWatch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** Which sessions to tell the user about when they finish: every one, or the ones picked. Off until the user chooses. Not a secret. */
class PrefsFinishedWatch(private val dataStore: DataStore<Preferences>) : FinishedWatchSettings {
    // A damaged preferences file reads as "none": no alerts rather than alerts nobody asked for.
    override val watch: Flow<FinishedWatch> =
        dataStore.data.map { FinishedWatch(it[ALL] ?: false, it[SESSIONS].orEmpty()) }.catch { emit(FinishedWatch()) }

    override suspend fun setAll(on: Boolean) {
        dataStore.edit { it[ALL] = on }
    }

    override suspend fun setSession(resource: String, on: Boolean) {
        if (resource.isBlank() || resource.length > MAX_URI) return
        dataStore.edit { prefs ->
            val chosen = prefs[SESSIONS].orEmpty()
            prefs[SESSIONS] = when {
                !on -> chosen - resource
                resource in chosen || chosen.size >= MAX_SESSIONS -> chosen
                else -> chosen + resource
            }
        }
    }

    companion object {
        /** More than anyone picks by hand; a bound so the file cannot grow without end. */
        const val MAX_SESSIONS = 200

        /** No session address is longer than this. */
        const val MAX_URI = 512

        private val ALL = booleanPreferencesKey("finished_watch_all")
        private val SESSIONS = stringSetPreferencesKey("finished_watch_sessions")
    }
}
