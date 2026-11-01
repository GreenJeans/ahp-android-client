package dev.ahpclient.ai

import android.util.LruCache
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import dev.ahpclient.auth.Secret
import dev.ahpclient.data.UiSettings
import dev.ahpclient.security.DirectHostVault
import dev.ahpclient.state.GoogleAiConfig
import dev.ahpclient.state.QuickReplies
import dev.ahpclient.state.QuickReplyContext
import dev.ahpclient.state.QuickReplyProvider
import dev.ahpclient.state.QuickReplySource
import dev.ahpclient.state.QuickReplySuggestion
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Availability of the on-device Gemini Nano model via Android AICore / ML Kit GenAI Prompt API.
 */
enum class NanoStatus(val label: String) {
    CHECKING("Checking on-device model…"),
    AVAILABLE("Gemini Nano ready on-device"),
    DOWNLOADABLE("Gemini Nano available to download"),
    DOWNLOADING("Downloading Gemini Nano…"),
    UNAVAILABLE("Using smart extract (Gemini Nano not supported on this device)"),
}

/**
 * Quick-reply generator supporting both:
 * 1. **PowerToys-style Google AI Provider** (`generativelanguage.googleapis.com` with user-configured Model name, encrypted API key,
 *    and custom System Prompt) when enabled in settings.
 * 2. **On-device Gemini Nano** (`com.google.mlkit:genai-prompt`) through Android AICore as a zero-key local AI provider or fallback.
 *
 * Gracefully degrades to null when neither AI path produces chips, allowing the caller to keep instant heuristic quick replies.
 */
class GeminiNanoQuickReplyProvider(
    private val scope: CoroutineScope,
    private val http: OkHttpClient? = null,
    private val ui: UiSettings? = null,
    private val apiKeyVault: DirectHostVault? = null,
    private val modelFactory: () -> GenerativeModel = { Generation.getClient() },
    private val baseUrl: String = DEFAULT_GEMINI_BASE_URL,
) : QuickReplyProvider {

    private val _status = MutableStateFlow(NanoStatus.CHECKING)
    val status: StateFlow<NanoStatus> = _status.asStateFlow()

    private val cache = LruCache<String, QuickReplySuggestion>(24)

    @Volatile
    private var client: GenerativeModel? = null

    init {
        // Synchronize hasApiKey in UiSettings with the encrypted vault on startup.
        if (ui != null && apiKeyVault != null) {
            scope.launch(Dispatchers.IO) {
                val hasKey = runCatching { apiKeyVault.has(GOOGLE_AI_VAULT_KEY) }.getOrDefault(false)
                val current = ui.googleAiConfig.value
                if (current.hasApiKey != hasKey) {
                    ui.setGoogleAiConfig(current.copy(hasApiKey = hasKey, enabled = current.enabled && hasKey))
                }
            }
        }
    }

    private fun getOrCreateClient(): GenerativeModel? {
        client?.let { return it }
        return runCatching { modelFactory() }.getOrNull()?.also { client = it }
    }

    /**
     * Saves the user's PowerToys-style Google AI provider configuration, sealing any new API key in [apiKeyVault].
     *
     * @param enabled Whether the Google AI provider is enabled.
     * @param modelName The Gemini model ID (e.g. `gemini-2.5-flash` or `gemini-2.5-pro`).
     * @param systemPrompt Custom system instruction for generating quick replies.
     * @param newApiKey A new API key to store, or blank/null to keep the existing key unless [clearKey] is true.
     * @param clearKey When true, removes any saved API key from the encrypted vault.
     */
    suspend fun saveGoogleAiConfig(
        enabled: Boolean,
        modelName: String,
        systemPrompt: String,
        newApiKey: String? = null,
        clearKey: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        val settings = ui ?: return@withContext false
        val vault = apiKeyVault
        val trimmedKey = newApiKey?.trim().orEmpty()
        val hasKeyNow = if (vault != null) {
            try {
                when {
                    clearKey -> {
                        vault.remove(GOOGLE_AI_VAULT_KEY)
                        false
                    }
                    trimmedKey.isNotEmpty() -> {
                        vault.put(GOOGLE_AI_VAULT_KEY, Secret(trimmedKey))
                        true
                    }
                    else -> vault.has(GOOGLE_AI_VAULT_KEY)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withContext false
            }
        } else {
            !clearKey && (trimmedKey.isNotEmpty() || settings.googleAiConfig.value.hasApiKey)
        }

        val cleanModel = modelName.trim().removePrefix("models/").ifEmpty { QuickReplies.DEFAULT_GOOGLE_MODEL }
        val cleanPrompt = systemPrompt.trim().ifEmpty { QuickReplies.DEFAULT_SYSTEM_PROMPT }
        settings.setGoogleAiConfig(
            GoogleAiConfig(
                enabled = enabled && hasKeyNow,
                modelName = cleanModel,
                systemPrompt = cleanPrompt,
                hasApiKey = hasKeyNow,
            ),
        )
        synchronized(cache) { cache.evictAll() }
        true
    }

    /**
     * Queries AICore for the current on-device Gemini Nano feature status.
     */
    suspend fun refreshStatus(): NanoStatus = withContext(Dispatchers.IO) {
        val model = getOrCreateClient()
        if (model == null) {
            _status.value = NanoStatus.UNAVAILABLE
            return@withContext NanoStatus.UNAVAILABLE
        }
        val mapped = try {
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> NanoStatus.AVAILABLE
                FeatureStatus.DOWNLOADABLE -> NanoStatus.DOWNLOADABLE
                FeatureStatus.DOWNLOADING -> NanoStatus.DOWNLOADING
                else -> NanoStatus.UNAVAILABLE
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            NanoStatus.UNAVAILABLE
        }
        _status.value = mapped
        mapped
    }

    /**
     * Triggers an on-device model download when AICore reports [FeatureStatus.DOWNLOADABLE].
     */
    fun triggerDownload() {
        val model = getOrCreateClient() ?: return
        if (_status.value == NanoStatus.DOWNLOADING) return
        scope.launch(Dispatchers.IO) {
            try {
                _status.value = NanoStatus.DOWNLOADING
                model.download().collect { dl ->
                    when (dl) {
                        is DownloadStatus.DownloadCompleted -> _status.value = NanoStatus.AVAILABLE
                        is DownloadStatus.DownloadFailed -> _status.value = NanoStatus.DOWNLOADABLE
                        else -> _status.value = NanoStatus.DOWNLOADING
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                _status.value = NanoStatus.UNAVAILABLE
            }
        }
    }

    override suspend fun suggest(context: QuickReplyContext): QuickReplySuggestion? = withContext(Dispatchers.IO) {
        val config = ui?.googleAiConfig?.value
        val customSystemPrompt = config?.systemPrompt?.takeIf { it.isNotBlank() } ?: QuickReplies.DEFAULT_SYSTEM_PROMPT
        val cacheKey = "${context.turnKey}|${config?.isActive == true}|${config?.modelName.orEmpty()}|${customSystemPrompt.hashCode()}"
        synchronized(cache) { cache.get(cacheKey) }?.let { return@withContext it }

        // 1. Try cloud Google AI provider if enabled and configured with an API key.
        if (config != null && config.isActive) {
            suggestWithGoogleCloud(context, config)?.let { cloudSuggestion ->
                synchronized(cache) { cache.put(cacheKey, cloudSuggestion) }
                return@withContext cloudSuggestion
            }
        }

        // 2. Fall back to on-device Gemini Nano via Android AICore.
        val nanoSuggestion = suggestWithNano(context, customSystemPrompt)
        if (nanoSuggestion != null) {
            synchronized(cache) { cache.put(cacheKey, nanoSuggestion) }
        }
        nanoSuggestion
    }

    private suspend fun suggestWithGoogleCloud(
        context: QuickReplyContext,
        config: GoogleAiConfig,
    ): QuickReplySuggestion? {
        val client = http ?: return null
        val apiKey = apiKeyVault?.get(GOOGLE_AI_VAULT_KEY)?.reveal()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val rawModel = config.modelName.trim().removePrefix("models/").ifEmpty { QuickReplies.DEFAULT_GOOGLE_MODEL }
        val encodedModel = URLEncoder.encode(rawModel, "UTF-8")
        val url = "${baseUrl.trimEnd('/')}/v1beta/models/$encodedModel:generateContent"
        val bodyJson = QuickReplies.buildGeminiApiRequestJson(context, config.systemPrompt)

        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body.string()
                QuickReplies.parseGeminiApiResponseJson(context.turnKey, body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun suggestWithNano(
        context: QuickReplyContext,
        systemPrompt: String,
    ): QuickReplySuggestion? {
        val model = getOrCreateClient() ?: run {
            _status.value = NanoStatus.UNAVAILABLE
            return null
        }
        return try {
            val currentStatus = model.checkStatus()
            when (currentStatus) {
                FeatureStatus.AVAILABLE -> _status.value = NanoStatus.AVAILABLE
                FeatureStatus.DOWNLOADABLE -> {
                    _status.value = NanoStatus.DOWNLOADABLE
                    triggerDownload()
                    return null
                }
                FeatureStatus.DOWNLOADING -> {
                    _status.value = NanoStatus.DOWNLOADING
                    return null
                }
                else -> {
                    _status.value = NanoStatus.UNAVAILABLE
                    return null
                }
            }

            val (effectiveSystemPrompt, userPrompt) = QuickReplies.buildPrompt(context, systemPrompt)
            @Suppress("DEPRECATION")
            val supportsSystem = runCatching { model.isSystemPromptAvailable() }.getOrDefault(false)
            val requestBuilder = if (supportsSystem) {
                GenerateContentRequest.Builder(SystemInstruction(effectiveSystemPrompt), TextPart(userPrompt))
            } else {
                GenerateContentRequest.Builder(TextPart("$effectiveSystemPrompt\n\n$userPrompt"))
            }
            requestBuilder.temperature = 0.2f
            requestBuilder.maxOutputTokens = 256

            val response = model.generateContent(requestBuilder.build())
            val outputText = response.candidates.firstOrNull()?.text ?: return null
            QuickReplies.parseAiResponse(context.turnKey, outputText, QuickReplySource.GEMINI_NANO)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    fun close() {
        runCatching { client?.close() }
        client = null
    }

    companion object {
        const val GOOGLE_AI_VAULT_KEY = "google_ai_api_key"
        const val DEFAULT_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
