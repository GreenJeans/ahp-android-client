package dev.ahpclient.state

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * When quick-reply suggestions are offered in the composer after an agent turn finishes.
 */
enum class QuickReplyMode(val label: String) {
    /** Automatically suggests quick replies when an agent turn completes. */
    AUTO("Auto"),

    /** Shows a button in the composer to generate quick replies on demand. */
    ON_DEMAND("On demand"),

    /** Quick-reply suggestions are hidden. */
    OFF("Off"),
    ;

    companion object {
        fun of(name: String?): QuickReplyMode = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/**
 * Where a set of quick-reply suggestions came from.
 */
enum class QuickReplySource(val label: String) {
    /** Instant local extraction from the structure and questions of the agent's message. */
    HEURISTIC("Smart extract"),

    /** On-device Gemini Nano via Android AICore / ML Kit GenAI Prompt API. */
    GEMINI_NANO("Gemini Nano"),

    /** Cloud AI provider (e.g. Google Gemini API with user API key). */
    CLOUD_AI("Google AI"),
}

/**
 * One tappable quick reply chip. Tapping fills the composer with [prompt] so the user can edit or choose how to send it.
 */
data class QuickReplyChip(
    val label: String,
    val prompt: String,
)

/**
 * Quick-reply suggestions for a completed turn identified by [turnKey].
 */
data class QuickReplySuggestion(
    val turnKey: String,
    val chips: List<QuickReplyChip>,
    val source: QuickReplySource,
    val summary: String? = null,
)

/**
 * Extracted context from the latest finished turn in a [Transcript], used by both heuristic extraction and AI providers.
 */
data class QuickReplyContext(
    val turnKey: String,
    val userText: String?,
    val agentText: String,
    val outcome: TurnOutcome,
    val failedTools: List<String> = emptyList(),
)

/**
 * Configuration for the PowerToys-style Google AI quick-reply provider.
 * The API key itself is stored separately in the keystore-backed vault; [hasApiKey] indicates whether one is saved.
 */
data class GoogleAiConfig(
    val enabled: Boolean = false,
    val modelName: String = QuickReplies.DEFAULT_GOOGLE_MODEL,
    val systemPrompt: String = QuickReplies.DEFAULT_SYSTEM_PROMPT,
    val hasApiKey: Boolean = false,
) {
    /** Whether the Google AI provider is both enabled and has a saved API key. */
    val isActive: Boolean get() = enabled && hasApiKey && modelName.isNotBlank()
}

/**
 * Asynchronous provider (such as on-device Gemini Nano or Google Gemini API) that inspects [QuickReplyContext] and returns
 * a [QuickReplySuggestion], or null when unavailable so the caller can keep the heuristic fallback.
 */
fun interface QuickReplyProvider {
    suspend fun suggest(context: QuickReplyContext): QuickReplySuggestion?
}

/**
 * Extracts turn context, builds heuristic quick-reply suggestions, formats AI prompts, and parses structured AI responses.
 */
object QuickReplies {
    const val MAX_LABEL_CHARS = 36
    const val MAX_PROMPT_CHARS = 600
    const val MAX_SUMMARY_CHARS = 160
    const val MAX_CHIPS = 4
    private const val PROMPT_HEAD_CHARS = 400
    private const val PROMPT_TAIL_CHARS = 1_600

    const val DEFAULT_GOOGLE_MODEL = "gemini-2.5-flash"
    val RECOMMENDED_GOOGLE_MODELS = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro")

    const val DEFAULT_SYSTEM_PROMPT =
        "You are a mobile coding assistant helper. Given the user's last prompt and the coding agent's response, " +
            "propose 3 to 4 concise, actionable replies the user might want to send next. " +
            "If the agent asked a question or offered options, prioritize answering or choosing among them. " +
            "Respond ONLY with valid JSON matching: " +
            "{\"tldr\":\"Optional 1-sentence summary if the agent message is long\",\"replies\":[{\"label\":\"2-4 word button label\",\"prompt\":\"Full message to populate in the chat box\"}]}"

    private val NUMBERED_ITEM_REGEX = Regex("""^\s*(?:(?:Option|Choice|Approach|Step)\s+)?([1-6]|[A-D])[.):\-]\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val BULLET_ITEM_REGEX = Regex("""^\s*[-*•]\s+(.+)$""")
    private val MARKDOWN_DECORATION_REGEX = Regex("""[*_`~]+""")
    private val CODE_BLOCK_REGEX = Regex("""```[\s\S]*?```""")
    private val LINK_MD_REGEX = Regex("""\[([^\]]+)]\([^)]+\)""")
    private val HEADING_PREFIX_REGEX = Regex("""^\s*#{1,6}\s+""")
    private val YES_NO_QUESTION_START = Regex(
        """^(?:would you like|should i|shall i|do you want|can i|may i|are you ready|ready for me to|want me to|is it okay|does this look)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val OR_ALTERNATIVE_REGEX = Regex(
        """\b(?:should i|would you like to|do you prefer|prefer to)\s+([^?,;]{3,40}?)\s+or\s+([^?.!]{3,40})\??$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Extracts the [QuickReplyContext] for the latest turn in [transcript], or null if the chat is empty, currently running,
     * waiting on a tool approval or structured question form, or has nothing to reply to.
     */
    fun extractContext(transcript: Transcript?): QuickReplyContext? {
        if (transcript == null || transcript.running || transcript.waitingOnYou || transcript.items.isEmpty()) return null
        val items = transcript.items
        val lastUserIdx = items.indexOfLast { it is TranscriptItem.User }
        val userItem = if (lastUserIdx >= 0) items[lastUserIdx] as TranscriptItem.User else null
        val turnItems = if (lastUserIdx >= 0) items.subList(lastUserIdx + 1, items.size) else items
        if (turnItems.isEmpty()) return null

        // If there is an unanswered structured question form, QuestionForm handles it directly.
        if (turnItems.any { it is TranscriptItem.Question && !it.answered }) return null

        val turnEnd = turnItems.lastOrNull { it is TranscriptItem.TurnEnd } as? TranscriptItem.TurnEnd
        val outcome = turnEnd?.outcome ?: TurnOutcome.DONE
        val markdownParts = turnItems.filterIsInstance<TranscriptItem.Markdown>().map { it.text.trim() }.filter { it.isNotEmpty() }
        val errorNotices = turnItems.filterIsInstance<TranscriptItem.Notice>().filter { it.isError && it.text.isNotBlank() }.map { it.text.trim() }
        val failedTools = turnItems.filterIsInstance<TranscriptItem.Tool>()
            .filter { it.state == ToolState.FAILED }
            .map { it.name.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val agentText = (markdownParts + errorNotices).joinToString("\n\n").trim()
        if (agentText.isEmpty() && outcome == TurnOutcome.DONE && failedTools.isEmpty()) return null

        val turnKey = transcript.lastTurnId
            ?: turnEnd?.turnId
            ?: userItem?.turnId
            ?: turnItems.last().key

        return QuickReplyContext(
            turnKey = turnKey,
            userText = userItem?.text?.trim()?.takeIf { it.isNotEmpty() },
            agentText = agentText,
            outcome = outcome,
            failedTools = failedTools,
        )
    }

    /**
     * Builds instant, zero-latency quick-reply suggestions from [context] using structural and linguistic heuristics.
     */
    fun heuristic(context: QuickReplyContext): QuickReplySuggestion {
        val cleanText = stripCodeBlocks(context.agentText)
        val summary = extractHeuristicSummary(cleanText)

        if (context.outcome == TurnOutcome.FAILED || (context.failedTools.isNotEmpty() && cleanText.isBlank())) {
            val toolNote = context.failedTools.firstOrNull()?.let { " in $it" }.orEmpty()
            return QuickReplySuggestion(
                turnKey = context.turnKey,
                source = QuickReplySource.HEURISTIC,
                summary = summary,
                chips = listOf(
                    QuickReplyChip("Fix the error", "Please investigate the failure$toolNote and fix the error."),
                    QuickReplyChip("Explain failure", "Why did that fail$toolNote, and what are the options to resolve it?"),
                    QuickReplyChip("Try another way", "Let's try an alternative approach to work around this failure."),
                ),
            )
        }

        if (context.outcome == TurnOutcome.STOPPED) {
            return QuickReplySuggestion(
                turnKey = context.turnKey,
                source = QuickReplySource.HEURISTIC,
                summary = summary,
                chips = listOf(
                    QuickReplyChip("Continue", "Please continue from where you left off."),
                    QuickReplyChip("Change approach", "Instead of that approach, let's "),
                    QuickReplyChip("Summarize progress", "Briefly summarize what has been completed so far."),
                ),
            )
        }

        val optionChips = extractNumberedOrBulletedOptions(cleanText)
        if (optionChips.size >= 2) {
            return QuickReplySuggestion(
                turnKey = context.turnKey,
                source = QuickReplySource.HEURISTIC,
                summary = summary,
                chips = optionChips.take(MAX_CHIPS),
            )
        }

        val questionChips = extractQuestionReplies(cleanText)
        if (questionChips.isNotEmpty()) {
            return QuickReplySuggestion(
                turnKey = context.turnKey,
                source = QuickReplySource.HEURISTIC,
                summary = summary,
                chips = questionChips.take(MAX_CHIPS),
            )
        }

        val followUpChips = buildCompletionFollowUps(cleanText)
        return QuickReplySuggestion(
            turnKey = context.turnKey,
            source = QuickReplySource.HEURISTIC,
            summary = summary,
            chips = followUpChips.take(MAX_CHIPS),
        )
    }

    /**
     * Formats the system instruction and user prompt for an AI model (such as on-device Gemini Nano or Google Gemini API).
     */
    fun buildPrompt(
        context: QuickReplyContext,
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    ): Pair<String, String> {
        val clippedAgent = clipMiddle(context.agentText.trim(), PROMPT_HEAD_CHARS, PROMPT_TAIL_CHARS)
        val body = buildString {
            context.userText?.let { user ->
                append("User asked:\n")
                append(user.take(400))
                append("\n\n")
            }
            if (context.outcome != TurnOutcome.DONE) {
                append("Turn outcome: ${context.outcome.name}\n")
            }
            if (context.failedTools.isNotEmpty()) {
                append("Failed tools: ${context.failedTools.joinToString(", ")}\n")
            }
            append("Agent response:\n")
            append(clippedAgent)
        }
        return systemPrompt.trim().ifEmpty { DEFAULT_SYSTEM_PROMPT } to body
    }

    /**
     * Parses a model's output into a [QuickReplySuggestion]. Supports both JSON (`{"tldr":..., "replies":[{"label":..., "prompt":...}]}`)
     * and line-delimited `Label | Prompt` output. Returns null when no usable chips could be extracted.
     */
    fun parseAiResponse(turnKey: String, raw: String, source: QuickReplySource): QuickReplySuggestion? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        parseJsonSuggestion(turnKey, trimmed, source)?.let { return it }
        return parseLineSuggestion(turnKey, trimmed, source)
    }

    /**
     * Builds the JSON request body for the Google Gemini `generateContent` REST endpoint (`v1beta/models/{model}:generateContent`).
     */
    fun buildGeminiApiRequestJson(
        context: QuickReplyContext,
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    ): String {
        val (effectiveSystem, userPrompt) = buildPrompt(context, systemPrompt)
        val payload = buildJsonObject {
            put(
                "systemInstruction",
                buildJsonObject {
                    put(
                        "parts",
                        buildJsonArray {
                            add(buildJsonObject { put("text", effectiveSystem) })
                        },
                    )
                },
            )
            put(
                "contents",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "parts",
                                buildJsonArray {
                                    add(buildJsonObject { put("text", userPrompt) })
                                },
                            )
                        },
                    )
                },
            )
            put(
                "generationConfig",
                buildJsonObject {
                    put("temperature", 0.2)
                    put("maxOutputTokens", 350)
                    put("responseMimeType", "application/json")
                },
            )
        }
        return payload.toString()
    }

    /**
     * Parses the JSON response body returned by the Google Gemini `generateContent` REST endpoint and extracts a [QuickReplySuggestion].
     */
    fun parseGeminiApiResponseJson(turnKey: String, responseBody: String): QuickReplySuggestion? {
        if (responseBody.isBlank()) return null
        return try {
            val root = Json.parseToJsonElement(responseBody).jsonObject
            val candidates = root["candidates"] as? JsonArray ?: return null
            val firstCandidate = candidates.firstOrNull() as? JsonObject ?: return null
            val content = firstCandidate["content"] as? JsonObject ?: return null
            val parts = content["parts"] as? JsonArray ?: return null
            val combinedText = parts.mapNotNull { part ->
                (part as? JsonObject)?.string("text")
            }.joinToString("\n").trim()
            if (combinedText.isEmpty()) null else parseAiResponse(turnKey, combinedText, QuickReplySource.CLOUD_AI)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseJsonSuggestion(turnKey: String, text: String, source: QuickReplySource): QuickReplySuggestion? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val candidate = text.substring(start, end + 1)
        return try {
            val root = Json.parseToJsonElement(candidate).jsonObject
            val tldr = root.string("tldr")
                ?.let(::cleanInlineMarkdown)
                ?.takeIf { it.isNotBlank() }
                ?.let { truncateEllipsis(it, MAX_SUMMARY_CHARS) }
            val repliesArray = root["replies"] as? JsonArray ?: root["options"] as? JsonArray ?: return null
            val chips = repliesArray.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val rawLabel = obj.string("label") ?: obj.string("title") ?: return@mapNotNull null
                val rawPrompt = obj.string("prompt") ?: obj.string("text") ?: rawLabel
                makeChip(rawLabel, rawPrompt)
            }.distinctBy { it.label.lowercase() }.take(MAX_CHIPS)
            if (chips.isEmpty()) null else QuickReplySuggestion(turnKey = turnKey, chips = chips, source = source, summary = tldr)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseLineSuggestion(turnKey: String, text: String, source: QuickReplySource): QuickReplySuggestion? {
        val chips = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("```") && !it.startsWith("{") && !it.startsWith("}") }
            .mapNotNull { line ->
                val cleaned = line.replace(Regex("""^\s*(?:[-*•]|\d+[.):])\s*"""), "").trim()
                val pipeIdx = cleaned.indexOf('|')
                if (pipeIdx > 0) {
                    val label = cleaned.substring(0, pipeIdx).trim()
                    val prompt = cleaned.substring(pipeIdx + 1).trim()
                    makeChip(label, prompt)
                } else {
                    null
                }
            }
            .distinctBy { it.label.lowercase() }
            .take(MAX_CHIPS)
            .toList()
        return if (chips.isEmpty()) null else QuickReplySuggestion(turnKey = turnKey, chips = chips, source = source)
    }

    private fun extractNumberedOrBulletedOptions(cleanText: String): List<QuickReplyChip> {
        val tail = if (cleanText.length > 1_600) cleanText.takeLast(1_600) else cleanText
        val lines = tail.lines()

        // Walk backwards from the bottom to find the last contiguous or near-contiguous group of numbered items.
        val numbered = ArrayList<Pair<String, String>>()
        for (line in lines) {
            val match = NUMBERED_ITEM_REGEX.find(line) ?: continue
            val marker = match.groupValues[1]
            val body = cleanInlineMarkdown(match.groupValues[2]).trim()
            if (body.length in 3..180) {
                numbered += marker to body
            }
        }
        if (numbered.size in 2..6) {
            val recent = numbered.takeLast(MAX_CHIPS)
            return recent.mapNotNull { (marker, body) ->
                val shortTitle = summarizeItemTitle(body)
                val label = truncateEllipsis("$marker. $shortTitle", MAX_LABEL_CHARS)
                val prompt = "Let's go with option $marker: $body".take(MAX_PROMPT_CHARS)
                QuickReplyChip(label = label, prompt = prompt)
            }
        }

        // Check if the tail has a question or prompt heading followed by 2..4 short bullet items.
        val lastHeadingOrQuestionIdx = lines.indexOfLast { line ->
            val t = line.trim()
            t.endsWith("?") || t.endsWith(":") && (
                t.contains("option", ignoreCase = true) ||
                    t.contains("next", ignoreCase = true) ||
                    t.contains("would you", ignoreCase = true) ||
                    t.contains("choose", ignoreCase = true)
                )
        }
        if (lastHeadingOrQuestionIdx >= 0) {
            val bullets = lines.subList(lastHeadingOrQuestionIdx + 1, lines.size).mapNotNull { line ->
                val m = BULLET_ITEM_REGEX.find(line) ?: return@mapNotNull null
                cleanInlineMarkdown(m.groupValues[1]).trim().takeIf { it.length in 3..140 }
            }
            if (bullets.size in 2..MAX_CHIPS) {
                return bullets.map { item ->
                    val shortTitle = summarizeItemTitle(item)
                    QuickReplyChip(
                        label = truncateEllipsis(shortTitle, MAX_LABEL_CHARS),
                        prompt = item.take(MAX_PROMPT_CHARS),
                    )
                }
            }
        }

        return emptyList()
    }

    private fun extractQuestionReplies(cleanText: String): List<QuickReplyChip> {
        val paragraphs = cleanText.split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotEmpty() }
        val lastParagraph = paragraphs.lastOrNull() ?: return emptyList()
        val lastSentence = lastParagraph.split(Regex("""(?<=[.!?;])\s+""")).lastOrNull()?.trim() ?: lastParagraph
        if (!lastSentence.endsWith("?")) return emptyList()

        val cleanQuestion = cleanInlineMarkdown(lastSentence)

        // Check for "Should I X or Y?"
        OR_ALTERNATIVE_REGEX.find(cleanQuestion)?.let { match ->
            val firstOpt = summarizeItemTitle(match.groupValues[1])
            val secondOpt = summarizeItemTitle(match.groupValues[2])
            if (firstOpt.isNotBlank() && secondOpt.isNotBlank()) {
                return listOf(
                    QuickReplyChip(truncateEllipsis(firstOpt.replaceFirstChar { it.uppercase() }, MAX_LABEL_CHARS), "Let's go with: ${match.groupValues[1].trim()}"),
                    QuickReplyChip(truncateEllipsis(secondOpt.replaceFirstChar { it.uppercase() }, MAX_LABEL_CHARS), "Let's go with: ${match.groupValues[2].trim()}"),
                    QuickReplyChip("Explain trade-offs", "What are the trade-offs between those options?"),
                )
            }
        }

        val isYesNo = YES_NO_QUESTION_START.containsMatchIn(cleanQuestion) ||
            cleanQuestion.contains("should i", ignoreCase = true) ||
            cleanQuestion.contains("would you like", ignoreCase = true) ||
            cleanQuestion.contains("shall i", ignoreCase = true) ||
            cleanQuestion.contains("do you want", ignoreCase = true)

        if (isYesNo) {
            return listOf(
                QuickReplyChip("Yes, proceed", "Yes, please go ahead and do that."),
                QuickReplyChip("Yes, and run tests", "Yes, please proceed and run the tests to verify."),
                QuickReplyChip("Hold on, adjust…", "Before doing that, let's adjust: "),
            )
        }

        return listOf(
            QuickReplyChip("Yes, go ahead", "Yes, please go ahead with your recommendation."),
            QuickReplyChip("Recommend one", "Which approach do you recommend and why?"),
            QuickReplyChip("Let's adjust…", "Here is what I'd like to do instead: "),
        )
    }

    private fun buildCompletionFollowUps(cleanText: String): List<QuickReplyChip> {
        val mentionsTests = cleanText.contains("test", ignoreCase = true)
        val mentionsCommit = cleanText.contains("commit", ignoreCase = true) || cleanText.contains("branch", ignoreCase = true)
        return buildList {
            add(QuickReplyChip("Looks good, continue", "Looks good! Please continue with the next step."))
            if (!mentionsTests) {
                add(QuickReplyChip("Run tests", "Please run the unit tests to verify these changes."))
            } else {
                add(QuickReplyChip("Check edge cases", "Are there any edge cases we should add test coverage for?"))
            }
            if (!mentionsCommit) {
                add(QuickReplyChip("Commit changes", "Looks good. Please stage and commit these changes."))
            }
            add(QuickReplyChip("Summarize briefly", "Give me a brief 2-sentence summary of the key changes."))
        }
    }

    private fun extractHeuristicSummary(cleanText: String): String? {
        // Only show a TL;DR summary when the agent's message is long enough to warrant one (~500+ chars).
        if (cleanText.length < 500) return null
        val paragraphs = cleanText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("-") && !it.startsWith("*") && !it.startsWith("|") }
            .map(::cleanInlineMarkdown)
            .filter { it.length >= 25 }
            .toList()
        if (paragraphs.isEmpty()) return null
        val lastQuestion = paragraphs.lastOrNull { it.endsWith("?") }
        val candidate = lastQuestion ?: paragraphs.first()
        val firstSentence = candidate.split(Regex("""(?<=[.!?])\s+""")).firstOrNull()?.trim() ?: candidate
        return truncateEllipsis(firstSentence, MAX_SUMMARY_CHARS)
    }

    private fun summarizeItemTitle(body: String): String {
        // If the option starts with "Title: description" or "Title — description" or "Title - description", use the title part for the chip label.
        val colonIdx = body.indexOf(':')
        if (colonIdx in 3..34) return body.substring(0, colonIdx).trim()
        val dashMatch = Regex("""^(.{3,34}?)\s+(?:—|--|-)\s+""").find(body)
        if (dashMatch != null) return dashMatch.groupValues[1].trim()
        return body
    }

    private fun makeChip(rawLabel: String, rawPrompt: String): QuickReplyChip? {
        val cleanLabel = cleanInlineMarkdown(rawLabel).replace('\n', ' ').trim()
        val cleanPrompt = rawPrompt.trim()
        if (cleanLabel.isEmpty() || cleanPrompt.isEmpty()) return null
        return QuickReplyChip(
            label = truncateEllipsis(cleanLabel, MAX_LABEL_CHARS),
            prompt = cleanPrompt.take(MAX_PROMPT_CHARS),
        )
    }

    private fun stripCodeBlocks(markdown: String): String =
        CODE_BLOCK_REGEX.replace(markdown, "").trim()

    private fun cleanInlineMarkdown(text: String): String =
        HEADING_PREFIX_REGEX.replace(text, "")
            .let { LINK_MD_REGEX.replace(it, "$1") }
            .let { MARKDOWN_DECORATION_REGEX.replace(it, "") }
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun clipMiddle(text: String, headChars: Int, tailChars: Int): String {
        val limit = headChars + tailChars
        if (text.length <= limit) return text
        return text.take(headChars).trimEnd() + "\n\n[...]\n\n" + text.takeLast(tailChars).trimStart()
    }

    private fun truncateEllipsis(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars - 1).trimEnd() + "\u2026"
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
