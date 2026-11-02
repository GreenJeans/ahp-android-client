package dev.ahpclient.data

import android.content.SharedPreferences
import androidx.core.content.edit

class SharedPrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getFloat(key: String, defaultValue: Float): Float = prefs.getFloat(key, defaultValue)
    override fun putFloat(key: String, value: Float) = prefs.edit { putFloat(key, value) }
    override fun getString(key: String, defaultValue: String?): String? = prefs.getString(key, defaultValue)
    override fun putString(key: String, value: String) = prefs.edit { putString(key, value) }
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = prefs.getBoolean(key, defaultValue)
    override fun putBoolean(key: String, value: Boolean) = prefs.edit { putBoolean(key, value) }
    override fun contains(key: String): Boolean = prefs.contains(key)
    override fun remove(key: String) = prefs.edit { remove(key) }
}

fun UiSettings(prefs: SharedPreferences): UiSettings = UiSettings(SharedPrefsKeyValueStore(prefs))
