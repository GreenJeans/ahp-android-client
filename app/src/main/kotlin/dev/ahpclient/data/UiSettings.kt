package dev.ahpclient.data

import android.content.SharedPreferences
import androidx.core.content.edit
import dev.ahpclient.state.GoogleAiConfig
import dev.ahpclient.state.QuickReplies
import dev.ahpclient.state.QuickReplyMode
import dev.ahpclient.state.SessionFilter
import dev.ahpclient.state.SessionGrouping
import dev.ahpclient.state.SessionOrdering
import dev.ahpclient.state.SessionShowScope
import dev.ahpclient.state.TextScale
import dev.ahpclient.state.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How the app looks: the text size in chats, the colours, whether the screen stays on while an agent works, quick-reply
 * suggestion mode, and the saved session-list filter choices. Plain preferences, not secrets; read at once so the first frame
 * already has them.
 */
class UiSettings(private val prefs: SharedPreferences) {
    private val _textScale = MutableStateFlow(TextScale.clamp(prefs.getFloat(TEXT_SCALE, TextScale.DEFAULT)))
    private val _theme = MutableStateFlow(ThemeMode.of(prefs.getString(THEME, null)))
    private val _keepAwake = MutableStateFlow(prefs.getBoolean(KEEP_AWAKE, false))
    private val _quickReplyMode = MutableStateFlow(QuickReplyMode.of(prefs.getString(QUICK_REPLY_MODE, null)))
    private val _googleAiConfig = MutableStateFlow(
        GoogleAiConfig(
            enabled = prefs.getBoolean(GOOGLE_AI_ENABLED, false),
            modelName = prefs.getString(GOOGLE_AI_MODEL, null)?.takeIf { it.isNotBlank() } ?: QuickReplies.DEFAULT_GOOGLE_MODEL,
            systemPrompt = prefs.getString(GOOGLE_AI_SYSTEM_PROMPT, null)?.takeIf { it.isNotBlank() } ?: QuickReplies.DEFAULT_SYSTEM_PROMPT,
            hasApiKey = prefs.getBoolean(GOOGLE_AI_HAS_KEY, false),
        ),
    )
    private val _sessionFilter = MutableStateFlow(
        SessionFilter(
            ordering = SessionOrdering.of(prefs.getString(FILTER_ORDERING, null)),
            grouping = SessionGrouping.of(prefs.getString(FILTER_GROUPING, null)),
            showScope = SessionShowScope.of(prefs.getString(FILTER_SHOW_SCOPE, null)),
            showExternal = prefs.getBoolean(FILTER_SHOW_EXTERNAL, SessionFilter.DEFAULT.showExternal),
            showDone = prefs.getBoolean(FILTER_SHOW_DONE, SessionFilter.DEFAULT.showDone),
        ),
    )

    val textScale: StateFlow<Float> = _textScale.asStateFlow()
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()
    val keepAwake: StateFlow<Boolean> = _keepAwake.asStateFlow()
    val quickReplyMode: StateFlow<QuickReplyMode> = _quickReplyMode.asStateFlow()
    val googleAiConfig: StateFlow<GoogleAiConfig> = _googleAiConfig.asStateFlow()
    val sessionFilter: StateFlow<SessionFilter> = _sessionFilter.asStateFlow()

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

    fun setQuickReplyMode(mode: QuickReplyMode) {
        _quickReplyMode.value = mode
        prefs.edit { putString(QUICK_REPLY_MODE, mode.name) }
    }

    fun setGoogleAiConfig(config: GoogleAiConfig) {
        val normalized = config.copy(
            modelName = config.modelName.trim().ifEmpty { QuickReplies.DEFAULT_GOOGLE_MODEL },
            systemPrompt = config.systemPrompt.trim().ifEmpty { QuickReplies.DEFAULT_SYSTEM_PROMPT },
        )
        _googleAiConfig.value = normalized
        prefs.edit {
            putBoolean(GOOGLE_AI_ENABLED, normalized.enabled)
            putString(GOOGLE_AI_MODEL, normalized.modelName)
            putString(GOOGLE_AI_SYSTEM_PROMPT, normalized.systemPrompt)
            putBoolean(GOOGLE_AI_HAS_KEY, normalized.hasApiKey)
        }
    }

    fun setSessionFilter(filter: SessionFilter) {
        _sessionFilter.value = filter
        prefs.edit {
            putString(FILTER_ORDERING, filter.ordering.name)
            putString(FILTER_GROUPING, filter.grouping.name)
            putString(FILTER_SHOW_SCOPE, filter.showScope.name)
            putBoolean(FILTER_SHOW_EXTERNAL, filter.showExternal)
            putBoolean(FILTER_SHOW_DONE, filter.showDone)
        }
    }

    fun resetSessionFilter() {
        setSessionFilter(SessionFilter.DEFAULT)
    }

    private companion object {
        const val TEXT_SCALE = "text_scale"
        const val THEME = "theme"
        const val KEEP_AWAKE = "keep_awake"
        const val QUICK_REPLY_MODE = "quick_reply_mode"
        const val GOOGLE_AI_ENABLED = "google_ai_enabled"
        const val GOOGLE_AI_MODEL = "google_ai_model"
        const val GOOGLE_AI_SYSTEM_PROMPT = "google_ai_system_prompt"
        const val GOOGLE_AI_HAS_KEY = "google_ai_has_key"
        const val BROWSER = "browser"
        const val FILTER_ORDERING = "filter_ordering"
        const val FILTER_GROUPING = "filter_grouping"
        const val FILTER_SHOW_SCOPE = "filter_show_scope"
        const val FILTER_SHOW_EXTERNAL = "filter_show_external"
        const val FILTER_SHOW_DONE = "filter_show_done"
    }
}
