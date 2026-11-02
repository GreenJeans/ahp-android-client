package dev.ahpclient.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CoroutineScope

fun PrefsLastHostStore.Companion.dataStoreFor(file: File, scope: CoroutineScope): DataStore<Preferences> =
    dataStoreForPath(file.absolutePath, scope)
