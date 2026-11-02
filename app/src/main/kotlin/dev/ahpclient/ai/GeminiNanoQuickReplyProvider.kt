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
import dev.ahpclient.state.AiProviderKind
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
 * Quick-reply generator supporting both:
 * 1. **External / Local AI Provider** (Google Gemini, OpenAI-compatible / Ollama / LM Studio / OpenRouter, or Anthropic)
 *    when enabled in settings. Surfaces explicit errors when the configured provider fails rather than silently masquerading
 *    as Gemini Nano.
 * 2. **On-device Gemini Nano** (`com.google.mlkit:genai-prompt`) through Android AICore when On-device AI is selected.
 */
class GeminiNanoQuickReplyProvider(
    private val scope: CoroutineScope,
    private val http: OkHttpClient? = null,
    private val ui: UiSettings? = null,
    private val apiKeyVault: DirectHostVault? = null,
    private val modelFactory: () -> GenerativeModel = { Generation.getClient() },
    private val baseUrl: String = DEFAULT_GEMINI_BASE_URL,
) : QuickReplyController {

    private val _status = MutableStateFlow(NanoStatus.CHECKING)
    override val status: StateFlow<NanoStatus> = _status.asStateFlow()

    private val cache = LruCache<String, QuickReplySuggestion>(24)

    @Volatile
    private var client: GenerativeModel? = null

    init {
        if (ui != null && apiKeyVault != null) {
            scope.launch(Dispatchers.IO) {
                val hasKey = runCatching { apiKeyVault.has(GOOGLE_AI_VAULT_KEY) }.getOrDefault(false)
                val current = ui.googleAiConfig.value
                if (current.hasApiKey != hasKey) {
                    val stillValid = !current.providerKind.requiresApiKey || hasKey
                    ui.setGoogleAiConfig(current.copy(hasApiKey = hasKey, enabled = current.enabled && stillValid))
                }
            }
        }
    }

    private fun getOrCreateClient(): GenerativeModel? {
        client?.let { return it }
        return runCatching { modelFactory() }.getOrNull()?.also { client = it }
    }

    /**
     * Saves the user's external AI provider configuration, sealing any new API key in [apiKeyVault].
     */
    override suspend fun saveGoogleAiConfig(
        enabled: Boolean,
        modelName: String,
        systemPrompt: String,
        newApiKey: String?,
        clearKey: Boolean,
        providerKind: AiProviderKind,
        endpointUrl: String,
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

        val cleanModel = modelName.trim().removePrefix("models/")
        val cleanBaseUrl = endpointUrl.trim().ifEmpty { providerKind.defaultBaseUrl }
        val cleanPrompt = systemPrompt.trim().ifEmpty { QuickReplies.DEFAULT_SYSTEM_PROMPT }
        val canEnable = cleanModel.isNotEmpty() && (!providerKind.requiresApiKey || hasKeyNow)
        settings.setGoogleAiConfig(
            GoogleAiConfig(
                enabled = enabled && canEnable,
                providerKind = providerKind,
                baseUrl = cleanBaseUrl,
                modelName = cleanModel,
                systemPrompt = cleanPrompt,
                hasApiKey = hasKeyNow,
            ),
        )
        synchronized(cache) { cache.evictAll() }
        true
    }

    /**
     * Fetches the live list of available model IDs from the given provider endpoint so the UI can present
     * relevant, up-to-date model chips instead of hardcoded names.
     */
    override suspend fun fetchAvailableModels(
        providerKind: AiProviderKind,
        endpointUrl: String,
        overrideApiKey: String?,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val client = http ?: return@withContext Result.failure(IllegalStateException("HTTP client unavailable."))
        val savedKey = apiKeyVault?.get(GOOGLE_AI_VAULT_KEY)?.reveal()?.trim()
        val apiKey = overrideApiKey?.trim()?.takeIf { it.isNotEmpty() } ?: savedKey
        if (providerKind.requiresApiKey && apiKey.isNullOrEmpty()) {
            return@withContext Result.failure(IllegalStateException("Enter an API key first to load models from ${providerKind.label}."))
        }

        val effectiveBase = endpointUrl.trim().ifEmpty { providerKind.defaultBaseUrl }.trimEnd('/')
        try {
            val request = when (providerKind) {
                AiProviderKind.GOOGLE_GEMINI -> {
                    Request.Builder()
                        .url("$effectiveBase/v1beta/models?pageSize=100")
                        .apply { if (!apiKey.isNullOrEmpty()) header("x-goog-api-key", apiKey) }
                        .get()
                        .build()
                }
                AiProviderKind.OPENAI_COMPATIBLE -> {
                    val modelsUrl = QuickReplies.resolveOpenAiEndpoint(effectiveBase, "/models")
                    Request.Builder()
                        .url(modelsUrl)
                        .apply { if (!apiKey.isNullOrEmpty()) header("Authorization", "Bearer $apiKey") }
                        .get()
                        .build()
                }
                AiProviderKind.ANTHROPIC -> {
                    Request.Builder()
                        .url("$effectiveBase/v1/models")
                        .apply { if (!apiKey.isNullOrEmpty()) header("x-api-key", apiKey) }
                        .header("anthropic-version", ANTHROPIC_VERSION)
                        .get()
                        .build()
                }
            }

            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException(QuickReplies.extractApiErrorMessage(response.code, body)),
                    )
                }
                val models = when (providerKind) {
                    AiProviderKind.GOOGLE_GEMINI -> QuickReplies.parseGeminiModelsJson(body)
                    AiProviderKind.OPENAI_COMPATIBLE, AiProviderKind.ANTHROPIC -> QuickReplies.parseOpenAiModelsJson(body)
                }
                if (models.isEmpty()) {
                    Result.failure(IllegalStateException("Connected, but the endpoint returned no models."))
                } else {
                    Result.success(models)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(IllegalStateException(e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * Queries AICore for the current on-device Gemini Nano feature status.
     */
    override suspend fun refreshStatus(): NanoStatus = withContext(Dispatchers.IO) {
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
    override fun triggerDownload() {
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
        val cacheKey = "${context.turnKey}|${config?.enabled == true}|${config?.providerKind}|${config?.effectiveBaseUrl}|${config?.modelName.orEmpty()}|${customSystemPrompt.hashCode()}"
        synchronized(cache) { cache.get(cacheKey) }?.let { return@withContext it }

        // When the user explicitly enabled the external AI provider, run ONLY that provider and surface any error directly.
        if (config != null && config.enabled) {
            val externalResult = suggestWithExternalProvider(context, config)
            if (externalResult.error == null) {
                synchronized(cache) { cache.put(cacheKey, externalResult) }
            }
            return@withContext externalResult
        }

        // Otherwise, use on-device Gemini Nano via Android AICore.
        val nanoSuggestion = suggestWithNano(context, customSystemPrompt)
        if (nanoSuggestion != null) {
            synchronized(cache) { cache.put(cacheKey, nanoSuggestion) }
        }
        nanoSuggestion
    }

    private suspend fun suggestWithExternalProvider(
        context: QuickReplyContext,
        config: GoogleAiConfig,
    ): QuickReplySuggestion {
        val label = config.displayLabel
        fun failWith(reason: String): QuickReplySuggestion =
            QuickReplies.heuristic(context).copy(
                providerLabel = label,
                error = "$label: $reason",
            )

        val client = http ?: return failWith("HTTP client unavailable.")
        val rawModel = config.modelName.trim().removePrefix("models/")
        if (rawModel.isEmpty()) {
            return failWith("No model name configured.")
        }
        val apiKey = apiKeyVault?.get(GOOGLE_AI_VAULT_KEY)?.reveal()?.trim()?.takeIf { it.isNotEmpty() }
        if (config.providerKind.requiresApiKey && apiKey == null) {
            return failWith("API key is missing.")
        }

        val effectiveBase = config.baseUrl.trim().ifEmpty {
            if (config.providerKind == AiProviderKind.GOOGLE_GEMINI) baseUrl else config.providerKind.defaultBaseUrl
        }.trimEnd('/')

        return try {
            val request = when (config.providerKind) {
                AiProviderKind.GOOGLE_GEMINI -> {
                    val encodedModel = URLEncoder.encode(rawModel, "UTF-8")
                    val url = "$effectiveBase/v1beta/models/$encodedModel:generateContent"
                    val bodyJson = QuickReplies.buildGeminiApiRequestJson(context, config.systemPrompt)
                    Request.Builder()
                        .url(url)
                        .apply { if (apiKey != null) header("x-goog-api-key", apiKey) }
                        .header("Content-Type", "application/json")
                        .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                }
                AiProviderKind.OPENAI_COMPATIBLE -> {
                    val url = QuickReplies.resolveOpenAiEndpoint(effectiveBase, "/chat/completions")
                    val bodyJson = QuickReplies.buildOpenAiChatRequestJson(context, rawModel, config.systemPrompt)
                    Request.Builder()
                        .url(url)
                        .apply { if (apiKey != null) header("Authorization", "Bearer $apiKey") }
                        .header("Content-Type", "application/json")
                        .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                }
                AiProviderKind.ANTHROPIC -> {
                    val url = "$effectiveBase/v1/messages"
                    val bodyJson = QuickReplies.buildAnthropicMessagesRequestJson(context, rawModel, config.systemPrompt)
                    Request.Builder()
                        .url(url)
                        .apply { if (apiKey != null) header("x-api-key", apiKey) }
                        .header("anthropic-version", ANTHROPIC_VERSION)
                        .header("Content-Type", "application/json")
                        .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                }
            }

            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    return failWith(QuickReplies.extractApiErrorMessage(response.code, body))
                }
                val parsed = when (config.providerKind) {
                    AiProviderKind.GOOGLE_GEMINI -> QuickReplies.parseGeminiApiResponseJson(context.turnKey, body, label)
                    AiProviderKind.OPENAI_COMPATIBLE -> QuickReplies.parseOpenAiChatResponseJson(context.turnKey, body, label)
                    AiProviderKind.ANTHROPIC -> QuickReplies.parseAnthropicMessagesResponseJson(context.turnKey, body, label)
                }
                parsed ?: failWith(QuickReplies.describeUnparsedResponse(body))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failWith(e.message ?: e.javaClass.simpleName)
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
        const val ANTHROPIC_VERSION = "2023-06-01"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
