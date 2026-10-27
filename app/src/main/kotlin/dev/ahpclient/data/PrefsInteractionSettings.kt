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
class PrefsInteractionSettings(
    private val dataStore: DataStore<Preferences>,
    private val key: Preferences.Key<Boolean> = KEY,
) : InteractionSettings {
    // A damaged preferences file reads as "off": the permission is never granted by accident.
    override val enabled: Flow<Boolean> = dataStore.data.map { it[key] ?: false }.catch { emit(false) }

    override suspend fun setEnabled(on: Boolean) {
        dataStore.edit { it[key] = on }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(key) }
    }

    companion object {
        val KEY = booleanPreferencesKey("allow_interaction")

        /** The second choice: also create, archive and remove sessions and chats. Separate from [KEY] so each is its own decision. */
        val MANAGEMENT_KEY = booleanPreferencesKey("allow_management")

        /** The two choices of one direct host, kept under keys of its own: a direct host never inherits what was allowed for another. */
        fun interactionKeyFor(hostId: String) = booleanPreferencesKey("allow_interaction.$hostId")

        fun managementKeyFor(hostId: String) = booleanPreferencesKey("allow_management.$hostId")
    }
}
