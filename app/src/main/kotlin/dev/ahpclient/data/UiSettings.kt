package dev.ahpclient.data

import android.content.SharedPreferences
import androidx.core.content.edit
import dev.ahpclient.state.TextScale
import dev.ahpclient.state.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the app looks and where it opens links: the text size in chats, the colours, whether the screen stays on while an agent works,
 * and the browser links open in. Plain preferences, not secrets; read at once so the first frame already has them.
 */
class UiSettings(private val prefs: SharedPreferences) {
    private val _textScale = MutableStateFlow(TextScale.clamp(prefs.getFloat(TEXT_SCALE, TextScale.DEFAULT)))
    private val _theme = MutableStateFlow(ThemeMode.of(prefs.getString(THEME, null)))
    private val _keepAwake = MutableStateFlow(prefs.getBoolean(KEEP_AWAKE, false))
    private val _browser = MutableStateFlow(prefs.getString(BROWSER, null)?.takeIf { it.isNotBlank() })

    val textScale: StateFlow<Float> = _textScale.asStateFlow()
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()
    val keepAwake: StateFlow<Boolean> = _keepAwake.asStateFlow()

    /** The package of the browser links open in, or null for the system's choice. */
    val browser: StateFlow<String?> = _browser.asStateFlow()

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

    fun setBrowser(packageName: String?) {
        val value = packageName?.takeIf { it.isNotBlank() }
        _browser.value = value
        prefs.edit { if (value == null) remove(BROWSER) else putString(BROWSER, value) }
    }

    private companion object {
        const val TEXT_SCALE = "text_scale"
        const val THEME = "theme"
        const val KEEP_AWAKE = "keep_awake"
        const val BROWSER = "browser"
    }
}