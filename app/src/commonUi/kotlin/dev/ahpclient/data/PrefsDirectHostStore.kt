package dev.ahpclient.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.ahpclient.direct.DirectHost
import dev.ahpclient.direct.DirectHostCodec
import dev.ahpclient.direct.DirectHostStore
import kotlinx.coroutines.flow.first

/** The hosts added by address, in ordinary preferences: their names and addresses are not secret, and their tokens are not here. */
class PrefsDirectHostStore(private val dataStore: DataStore<Preferences>) : DirectHostStore {
    override suspend fun load(): List<DirectHost> = DirectHostCodec.decode(dataStore.data.first()[KEY])

    override suspend fun save(hosts: List<DirectHost>) {
        dataStore.edit { it[KEY] = DirectHostCodec.encode(hosts) }
    }

    private companion object {
        val KEY = stringPreferencesKey("direct_hosts_v1")
    }
}
