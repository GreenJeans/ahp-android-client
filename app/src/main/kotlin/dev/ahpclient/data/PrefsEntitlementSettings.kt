package dev.ahpclient.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dev.ahpclient.app.EntitlementSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Persists the local cache of whether the Pro unlock entitlement is active.
 * A damaged preferences file reads as `false` (Free read-only tier) until verified again.
 */
class PrefsEntitlementSettings(
    private val dataStore: DataStore<Preferences>,
    private val defaultUnlocked: Boolean = false,
) : EntitlementSettings {
    override val unlocked: Flow<Boolean> = dataStore.data.map { it[KEY] ?: defaultUnlocked }.catch { emit(false) }

    override suspend fun setUnlocked(unlocked: Boolean) {
        dataStore.edit { it[KEY] = unlocked }
    }

    companion object {
        val KEY = booleanPreferencesKey("pro_unlocked")
    }
}
