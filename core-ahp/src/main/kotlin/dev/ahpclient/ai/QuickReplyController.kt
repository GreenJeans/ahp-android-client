package dev.ahpclient.ai

import dev.ahpclient.auth.Secret
import dev.ahpclient.data.UiSettings
import dev.ahpclient.http.HttpTransport
import dev.ahpclient.http.defaultHttpTransport
import dev.ahpclient.platform.platformSynchronized
import dev.ahpclient.platform.urlEncodeUtf8
import dev.ahpclient.security.DirectHostVault
import dev.ahpclient.state.AiProviderKind
import dev.ahpclient.state.GoogleAiConfig
import dev.ahpclient.state.QuickReplies
import dev.ahpclient.state.QuickReplyContext
import dev.ahpclient.state.QuickReplyProvider
import dev.ahpclient.state.QuickReplySuggestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Availability of the on-device Gemini Nano model via Android AICore / ML Kit GenAI Prompt API (or unavailable on iOS).
 */
enum class NanoStatus(val label: String) {
    CHECKING("Checking on-device model…"),
    AVAILABLE("Gemini Nano ready on-device"),
    DOWNLOADABLE("Gemini Nano available to download"),
    DOWNLOADING("Downloading Gemini Nano…"),
    UNAVAILABLE("Using smart extract (Gemini Nano not supported on this device)"),
}

/**
 * Multiplatform controller for quick-reply suggestions and AI provider settings.
 */
interface QuickReplyController : QuickReplyProvider {
    val status: StateFlow<NanoStatus>
    suspend fun refreshStatus(): NanoStatus
    fun triggerDownload()

    suspend fun saveGoogleAiConfig(
        enabled: Boolean,
        modelName: String,
        systemPrompt: String,
        newApiKey: String? = null,
        clearKey: Boolean = false,
        providerKind: AiProviderKind = AiProviderKind.GOOGLE_GEMINI,
        endpointUrl: String = providerKind.defaultBaseUrl,
    ): Boolean

    suspend fun fetchAvailableModels(
        providerKind: AiProviderKind,
        endpointUrl: String,
        overrideApiKey: String? = null,
    ): Result<List<String>>
}

/**
 * Multiplatform [QuickReplyController] supporting Google Gemini, OpenAI-compatible (Ollama, LM Studio, OpenRouter, OpenAI),
 * and Anthropic endpoints via [HttpTransport] (used directly on iOS).
 */
class CloudQuickReplyController(
    private val scope: CoroutineScope,
    private val transport: HttpTransport = defaultHttpTransport(),
    private val ui: UiSettings? = null,
    private val apiKeyVault: DirectHostVault? = null,
    private val baseUrl: String = DEFAULT_GEMINI_BASE_URL,
) : QuickReplyController {
    private val _status = MutableStateFlow(NanoStatus.UNAVAILABLE)
    override val status: StateFlow<NanoStatus> = _status.asStateFlow()

    private val cacheLock = Any()
    private val cache = LinkedHashMap<String, QuickReplySuggestion>()

    init {
        if (ui != null && apiKeyVault != null) {
            scope.launch(Dispatchers.Default) {
                val hasKey = runCatching { apiKeyVault.has(GOOGLE_AI_VAULT_KEY) }.getOrDefault(false)
                val current = ui.googleAiConfig.value
                if (current.hasApiKey != hasKey) {
                    val stillValid = !current.providerKind.requiresApiKey || hasKey
                    ui.setGoogleAiConfig(current.copy(hasApiKey = hasKey, enabled = current.enabled && stillValid))
                }
            }
        }
    }

    override suspend fun refreshStatus(): NanoStatus = NanoStatus.UNAVAILABLE

    override fun triggerDownload() = Unit

    override suspend fun saveGoogleAiConfig(
        enabled: Boolean,
        modelName: String,
        systemPrompt: String,
        newApiKey: String?,
        clearKey: Boolean,
        providerKind: AiProviderKind,
        endpointUrl: String,
    ): Boolean {
        val settings = ui ?: return false
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
                return false
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
        platformSynchronized(cacheLock) { cache.clear() }
        return true
    }

    override suspend fun fetchAvailableModels(
        providerKind: AiProviderKind,
        endpointUrl: String,
        overrideApiKey: String?,
    ): Result<List<String>> {
        val savedKey = apiKeyVault?.get(GOOGLE_AI_VAULT_KEY)?.reveal()?.trim()
        val apiKey = overrideApiKey?.trim()?.takeIf { it.isNotEmpty() } ?: savedKey
        if (providerKind.requiresApiKey && apiKey.isNullOrEmpty()) {
            return Result.failure(IllegalStateException("Enter an API key first to load models from ${providerKind.label}."))
        }

        val effectiveBase = endpointUrl.trim().ifEmpty { providerKind.defaultBaseUrl }.trimEnd('/')
        return try {
            val (url, headers) = when (providerKind) {
                AiProviderKind.GOOGLE_GEMINI -> {
                    val h = if (!apiKey.isNullOrEmpty()) mapOf("x-goog-api-key" to apiKey) else emptyMap()
                    "$effectiveBase/v1beta/models?pageSize=100" to h
                }
                AiProviderKind.OPENAI_COMPATIBLE -> {
                    val h = if (!apiKey.isNullOrEmpty()) mapOf("Authorization" to "Bearer $apiKey") else emptyMap()
                    QuickReplies.resolveOpenAiEndpoint(effectiveBase, "/models") to h
                }
                AiProviderKind.ANTHROPIC -> {
                    val h = buildMap {
                        if (!apiKey.isNullOrEmpty()) put("x-api-key", apiKey)
                        put("anthropic-version", ANTHROPIC_VERSION)
                    }
                    "$effectiveBase/v1/models" to h
                }
            }
            val response = transport.get(url, headers)
            if (response.code !in 200..299) {
                return Result.failure(IllegalStateException(QuickReplies.extractApiErrorMessage(response.code, response.body)))
            }
            val models = when (providerKind) {
                AiProviderKind.GOOGLE_GEMINI -> QuickReplies.parseGeminiModelsJson(response.body)
                AiProviderKind.OPENAI_COMPATIBLE, AiProviderKind.ANTHROPIC -> QuickReplies.parseOpenAiModelsJson(response.body)
            }
            if (models.isEmpty()) {
                Result.failure(IllegalStateException("Connected, but the endpoint returned no models."))
            } else {
                Result.success(models)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(IllegalStateException(e.message ?: e::class.simpleName ?: "Request failed"))
        }
    }

    override suspend fun suggest(context: QuickReplyContext): QuickReplySuggestion? {
        val config = ui?.googleAiConfig?.value ?: return null
        if (!config.enabled) return null
        val customSystemPrompt = config.systemPrompt.takeIf { it.isNotBlank() } ?: QuickReplies.DEFAULT_SYSTEM_PROMPT
        val cacheKey = "${context.turnKey}|${config.enabled}|${config.providerKind}|${config.effectiveBaseUrl}|${config.modelName}|${customSystemPrompt.hashCode()}"
        platformSynchronized(cacheLock) { cache[cacheKey] }?.let { return it }

        val externalResult = suggestWithExternalProvider(context, config)
        if (externalResult.error == null) {
            platformSynchronized(cacheLock) {
                if (cache.size >= 24) {
                    val firstKey = cache.keys.firstOrNull()
                    if (firstKey != null) cache.remove(firstKey)
                }
                cache[cacheKey] = externalResult
            }
        }
        return externalResult
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

        val rawModel = config.modelName.trim().removePrefix("models/")
        if (rawModel.isEmpty()) return failWith("No model name configured.")
        val apiKey = apiKeyVault?.get(GOOGLE_AI_VAULT_KEY)?.reveal()?.trim()?.takeIf { it.isNotEmpty() }
        if (config.providerKind.requiresApiKey && apiKey == null) return failWith("API key is missing.")

        val effectiveBase = config.baseUrl.trim().ifEmpty {
            if (config.providerKind == AiProviderKind.GOOGLE_GEMINI) baseUrl else config.providerKind.defaultBaseUrl
        }.trimEnd('/')

        return try {
            val (url, headers, bodyJson) = when (config.providerKind) {
                AiProviderKind.GOOGLE_GEMINI -> {
                    val encodedModel = urlEncodeUtf8(rawModel)
                    Triple(
                        "$effectiveBase/v1beta/models/$encodedModel:generateContent",
                        if (apiKey != null) mapOf("x-goog-api-key" to apiKey) else emptyMap(),
                        QuickReplies.buildGeminiApiRequestJson(context, config.systemPrompt),
                    )
                }
                AiProviderKind.OPENAI_COMPATIBLE -> {
                    Triple(
                        QuickReplies.resolveOpenAiEndpoint(effectiveBase, "/chat/completions"),
                        if (apiKey != null) mapOf("Authorization" to "Bearer $apiKey") else emptyMap(),
                        QuickReplies.buildOpenAiChatRequestJson(context, rawModel, config.systemPrompt),
                    )
                }
                AiProviderKind.ANTHROPIC -> {
                    Triple(
                        "$effectiveBase/v1/messages",
                        buildMap {
                            if (apiKey != null) put("x-api-key", apiKey)
                            put("anthropic-version", ANTHROPIC_VERSION)
                        },
                        QuickReplies.buildAnthropicMessagesRequestJson(context, rawModel, config.systemPrompt),
                    )
                }
            }
            val response = transport.postJson(url, headers, bodyJson)
            if (response.code !in 200..299) {
                return failWith(QuickReplies.extractApiErrorMessage(response.code, response.body))
            }
            val parsed = when (config.providerKind) {
                AiProviderKind.GOOGLE_GEMINI -> QuickReplies.parseGeminiApiResponseJson(context.turnKey, response.body, label)
                AiProviderKind.OPENAI_COMPATIBLE -> QuickReplies.parseOpenAiChatResponseJson(context.turnKey, response.body, label)
                AiProviderKind.ANTHROPIC -> QuickReplies.parseAnthropicMessagesResponseJson(context.turnKey, response.body, label)
            }
            parsed ?: failWith(QuickReplies.describeUnparsedResponse(response.body))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failWith(e.message ?: e::class.simpleName ?: "Request failed")
        }
    }

    companion object {
        const val GOOGLE_AI_VAULT_KEY = "google_ai_api_key"
        const val DEFAULT_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com"
        const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
