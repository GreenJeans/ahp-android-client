package dev.ahpclient.data

import android.content.SharedPreferences
import androidx.core.content.edit
import dev.ahpclient.state.TextScale
import dev.ahpclient.state.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the app looks: the text size in chats, the colours, and whether the screen stays on while an agent works. Plain
 * preferences, not secrets; read at once so the first frame already has them.
 */
class UiSettings(private val prefs: SharedPreferences) {
    private val _textScale = MutableStateFlow(TextScale.clamp(prefs.getFloat(TEXT_SCALE, TextScale.DEFAULT)))
    private val _theme = MutableStateFlow(ThemeMode.of(prefs.getString(THEME, null)))
    private val _keepAwake = MutableStateFlow(prefs.getBoolean(KEEP_AWAKE, false))

    val textScale: StateFlow<Float> = _textScale.asStateFlow()
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()
    val keepAwake: StateFlow<Boolean> = _keepAwake.asStateFlow()

    init {
        // Links used to open in a browser the person chose; the setting is gone, so its stored package is not left behind.
        if (prefs.contains(BROWSER)) prefs.edit { remove(BROWSER) }
    }

    fun setTextScale(scale: Float) {
        val clamped = TextScale.round(scale)
        _textScale.value = clamped
        prefs.edit { putFloat(TEXT_SCALE, clamped) }
    }

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
        prefs.edit { putString(THEME, mode.name) }
    }

    fun setKeepAwake(on: Boolean) {
        _keepAwake.value = on
        prefs.edit { putBoolean(KEEP_AWAKE, on) }
    }

    private companion object {
        const val TEXT_SCALE = "text_scale"
        const val THEME = "theme"
        const val KEEP_AWAKE = "keep_awake"
        const val BROWSER = "browser"
    }
}