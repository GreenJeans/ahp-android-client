package dev.ahpclient.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickRepliesTest {

    @Test
    fun `QuickReplyMode of parses valid modes and defaults to AUTO`() {
        assertEquals(QuickReplyMode.AUTO, QuickReplyMode.of("AUTO"))
        assertEquals(QuickReplyMode.ON_DEMAND, QuickReplyMode.of("ON_DEMAND"))
        assertEquals(QuickReplyMode.OFF, QuickReplyMode.of("OFF"))
        assertEquals(QuickReplyMode.AUTO, QuickReplyMode.of(null))
        assertEquals(QuickReplyMode.AUTO, QuickReplyMode.of("UNKNOWN"))
    }

    @Test
    fun `extractContext returns null when running or waiting or empty`() {
        assertNull(QuickReplies.extractContext(null))

        val empty = Transcript(
            title = "Chat",
            state = ActivityState.IDLE,
            activity = null,
            items = emptyList(),
            hasOlder = false,
            running = false,
        )
        assertNull(QuickReplies.extractContext(empty))

        val running = empty.copy(
            running = true,
            items = listOf(TranscriptItem.User("t1/user", "hi"), TranscriptItem.Markdown("t1/m1", "working")),
        )
        assertNull(QuickReplies.extractContext(running))
    }

    @Test
    fun `extractContext extracts user text, agent markdown, failed tools, and outcome`() {
        val transcript = Transcript(
            title = "Chat",
            state = ActivityState.IDLE,
            activity = null,
            hasOlder = false,
            running = false,
            lastTurnId = "turn-42",
            items = listOf(
                TranscriptItem.User("turn-41/user", "earlier"),
                TranscriptItem.Markdown("turn-41/m", "done earlier"),
                TranscriptItem.TurnEnd("turn-41/end", TurnOutcome.DONE, 100L, "turn-41"),
                TranscriptItem.User("turn-42/user", "Refactor the parser"),
                TranscriptItem.Tool("turn-42/t1", "Run gradle", "test", ToolState.FAILED),
                TranscriptItem.Markdown("turn-42/m1", "I updated the parser."),
                TranscriptItem.Markdown("turn-42/m2", "Should I fix the failing test next?"),
                TranscriptItem.TurnEnd("turn-42/end", TurnOutcome.DONE, 500L, "turn-42"),
            ),
        )

        val ctx = QuickReplies.extractContext(transcript)
        assertNotNull(ctx)
        assertEquals("turn-42", ctx.turnKey)
        assertEquals("Refactor the parser", ctx.userText)
        assertTrue(ctx.agentText.contains("Should I fix the failing test next?"))
        assertEquals(TurnOutcome.DONE, ctx.outcome)
        assertEquals(listOf("Run gradle"), ctx.failedTools)
    }

    @Test
    fun `heuristic extracts numbered options from agent response`() {
        val ctx = QuickReplyContext(
            turnKey = "t1",
            userText = "What next?",
            agentText = """
                I have finished the initial setup. Here are our options:
                1. **Run unit tests**: Verify the new state store logic
                2. **Add UI components**: Wire the Compose screen
                3. **Update docs**: Document the protocol changes
            """.trimIndent(),
            outcome = TurnOutcome.DONE,
        )

        val suggestion = QuickReplies.heuristic(ctx)
        assertEquals(QuickReplySource.HEURISTIC, suggestion.source)
        assertEquals(3, suggestion.chips.size)
        assertEquals("1. Run unit tests", suggestion.chips[0].label)
        assertTrue(suggestion.chips[0].prompt.contains("option 1"))
        assertEquals("2. Add UI components", suggestion.chips[1].label)
        assertEquals("3. Update docs", suggestion.chips[2].label)
    }

    @Test
    fun `heuristic extracts bulleted options under a question heading`() {
        val ctx = QuickReplyContext(
            turnKey = "t2",
            userText = "How should we proceed?",
            agentText = """
                Would you like me to:
                - Migrate the existing database schema
                - Drop the legacy cache table
                - Keep both for backwards compatibility
            """.trimIndent(),
            outcome = TurnOutcome.DONE,
        )

        val suggestion = QuickReplies.heuristic(ctx)
        assertEquals(3, suggestion.chips.size)
        assertTrue(suggestion.chips[0].label.contains("Migrate"))
        assertTrue(suggestion.chips[1].label.contains("Drop"))
    }

    @Test
    fun `heuristic detects either-or question and yes-no question`() {
        val orCtx = QuickReplyContext(
            turnKey = "t3",
            userText = "Update storage",
            agentText = "Should I use SharedPreferences or DataStore?",
            outcome = TurnOutcome.DONE,
        )
        val orSuggestion = QuickReplies.heuristic(orCtx)
        assertEquals(3, orSuggestion.chips.size)
        assertEquals("Use SharedPreferences", orSuggestion.chips[0].label)
        assertEquals("DataStore", orSuggestion.chips[1].label)

        val ynCtx = QuickReplyContext(
            turnKey = "t4",
            userText = "Fix bug",
            agentText = "I found the root cause in HostSession. Would you like me to apply this fix now?",
            outcome = TurnOutcome.DONE,
        )
        val ynSuggestion = QuickReplies.heuristic(ynCtx)
        assertEquals("Yes, proceed", ynSuggestion.chips[0].label)
        assertEquals("Yes, and run tests", ynSuggestion.chips[1].label)
    }

    @Test
    fun `heuristic handles failed and stopped turns and long summary extraction`() {
        val failedCtx = QuickReplyContext(
            turnKey = "t5",
            userText = "Build app",
            agentText = "",
            outcome = TurnOutcome.FAILED,
            failedTools = listOf("Gradle build"),
        )
        val failedSuggestion = QuickReplies.heuristic(failedCtx)
        assertEquals("Fix the error", failedSuggestion.chips.first().label)
        assertTrue(failedSuggestion.chips.first().prompt.contains("Gradle build"))

        val stoppedCtx = QuickReplyContext(
            turnKey = "t6",
            userText = "Search",
            agentText = "Partial output",
            outcome = TurnOutcome.STOPPED,
        )
        val stoppedSuggestion = QuickReplies.heuristic(stoppedCtx)
        assertEquals("Continue", stoppedSuggestion.chips.first().label)

        val longText = "We refactored the entire authentication pipeline to use hardware-backed AES-GCM keys. " +
            "This ensures that tokens cannot be extracted from disk even if the application sandbox is compromised. ".repeat(4) +
            "\n\nEverything compiled cleanly and passed verification."
        val doneCtx = QuickReplyContext(
            turnKey = "t7",
            userText = "Refactor auth",
            agentText = longText,
            outcome = TurnOutcome.DONE,
        )
        val doneSuggestion = QuickReplies.heuristic(doneCtx)
        assertNull(doneSuggestion.summary)
        assertTrue(doneSuggestion.chips.isNotEmpty())
    }

    @Test
    fun `buildPrompt and parseAiResponse handle JSON and pipe-delimited formats`() {
        val ctx = QuickReplyContext(
            turnKey = "t8",
            userText = "Add caching",
            agentText = "A".repeat(2500),
            outcome = TurnOutcome.DONE,
        )
        val (sys, user) = QuickReplies.buildPrompt(ctx)
        assertTrue(sys.isNotBlank())
        assertTrue(user.contains("[...]"))

        val jsonRaw = """
            ```json
            {
              "tldr": "Added LRU caching to session store and asked whether to persist to disk.",
              "replies": [
                {"label": "Persist to disk", "prompt": "Yes, please persist the cache to disk as well."},
                {"label": "Keep in memory", "prompt": "In-memory caching is sufficient for now."},
                {"label": "Run benchmarks", "prompt": "Please run the benchmark suite to measure the speedup."}
              ]
            }
            ```
        """.trimIndent()
        val parsedJson = QuickReplies.parseAiResponse("t8", jsonRaw, QuickReplySource.GEMINI_NANO)
        assertNotNull(parsedJson)
        assertEquals(QuickReplySource.GEMINI_NANO, parsedJson.source)
        assertEquals("Added LRU caching to session store and asked whether to persist to disk.", parsedJson.summary)
        assertEquals(3, parsedJson.chips.size)
        assertEquals("Persist to disk", parsedJson.chips[0].label)

        val pipeRaw = """
            - Run unit tests | Please run the unit tests for the new cache.
            - Commit changes | Looks great, please commit the changes.
        """.trimIndent()
        val parsedPipe = QuickReplies.parseAiResponse("t8", pipeRaw, QuickReplySource.GEMINI_NANO)
        assertNotNull(parsedPipe)
        assertEquals(2, parsedPipe.chips.size)
        assertEquals("Run unit tests", parsedPipe.chips[0].label)

        assertNull(QuickReplies.parseAiResponse("t8", "", QuickReplySource.GEMINI_NANO))
        assertNull(QuickReplies.parseAiResponse("t8", "not json or pipe", QuickReplySource.GEMINI_NANO))
    }

    @Test
    fun `GoogleAiConfig isActive and Gemini REST JSON request and response parsing work`() {
        val inactive = GoogleAiConfig(enabled = true, modelName = "gemini-2.5-flash", hasApiKey = false)
        assertEquals(false, inactive.isActive)
        val active = inactive.copy(hasApiKey = true)
        assertEquals(true, active.isActive)

        val ctx = QuickReplyContext(
            turnKey = "t9",
            userText = "Optimize the query",
            agentText = "I added a composite index on (user_id, created_at). Should I also add a migration test?",
            outcome = TurnOutcome.DONE,
        )
        val reqJson = QuickReplies.buildGeminiApiRequestJson(ctx, "Custom system prompt")
        assertTrue(reqJson.contains("Custom system prompt"))
        assertTrue(reqJson.contains("Optimize the query"))
        assertTrue(reqJson.contains("responseMimeType"))

        val geminiRestResponse = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "{\"tldr\":\"Added composite index and asked about migration test.\",\"replies\":[{\"label\":\"Add migration test\",\"prompt\":\"Yes, please add a migration test.\"},{\"label\":\"Skip test\",\"prompt\":\"No migration test needed right now.\"}]}"
                      }
                    ],
                    "role": "model"
                  },
                  "finishReason": "STOP"
                }
              ]
            }
        """.trimIndent()
        val suggestion = QuickReplies.parseGeminiApiResponseJson("t9", geminiRestResponse)
        assertNotNull(suggestion)
        assertEquals(QuickReplySource.CLOUD_AI, suggestion.source)
        assertEquals("Added composite index and asked about migration test.", suggestion.summary)
        assertEquals(2, suggestion.chips.size)
        assertEquals("Add migration test", suggestion.chips[0].label)

        assertNull(QuickReplies.parseGeminiApiResponseJson("t9", ""))
        assertNull(QuickReplies.parseGeminiApiResponseJson("t9", """{"candidates":[]}"""))
        assertNull(QuickReplies.parseGeminiApiResponseJson("t9", "not-json"))
    }

    @Test
    fun `AiProviderKind and OpenAI and Anthropic and model listing and error parsers work`() {
        assertEquals(AiProviderKind.GOOGLE_GEMINI, AiProviderKind.of("GOOGLE_GEMINI"))
        assertEquals(AiProviderKind.OPENAI_COMPATIBLE, AiProviderKind.of("OPENAI_COMPATIBLE"))
        assertEquals(AiProviderKind.ANTHROPIC, AiProviderKind.of("ANTHROPIC"))
        assertEquals(AiProviderKind.GOOGLE_GEMINI, AiProviderKind.of("INVALID"))

        val localCfg = GoogleAiConfig(
            enabled = true,
            providerKind = AiProviderKind.OPENAI_COMPATIBLE,
            baseUrl = "http://192.168.1.50:11434/v1",
            modelName = "llama3.2",
            hasApiKey = false,
        )
        assertTrue(localCfg.isActive)
        assertEquals("http://192.168.1.50:11434/v1", localCfg.effectiveBaseUrl)
        assertTrue(localCfg.displayLabel.contains("llama3.2"))

        assertEquals(
            "http://192.168.1.50:11434/v1/chat/completions",
            QuickReplies.resolveOpenAiEndpoint("http://192.168.1.50:11434", "chat/completions"),
        )
        assertEquals(
            "https://api.openai.com/v1/models",
            QuickReplies.resolveOpenAiEndpoint("https://api.openai.com/v1", "models"),
        )

        val ctx = QuickReplyContext(
            turnKey = "t10",
            userText = "Run locally",
            agentText = "Should I run the benchmark suite or deploy to staging?",
            outcome = TurnOutcome.DONE,
        )
        val openAiReq = QuickReplies.buildOpenAiChatRequestJson(ctx, "llama3.2", "Custom prompt")
        assertTrue(openAiReq.contains("llama3.2"))
        assertTrue(openAiReq.contains("Custom prompt"))

        val openAiResp = """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": "{\"tldr\":\"Asked whether to benchmark or deploy.\",\"replies\":[{\"label\":\"Run benchmarks\",\"prompt\":\"Run the benchmark suite first.\"}]}"
                  }
                }
              ]
            }
        """.trimIndent()
        val parsedOpenAi = QuickReplies.parseOpenAiChatResponseJson("t10", openAiResp, "OpenAI / Local · llama3.2")
        assertNotNull(parsedOpenAi)
        assertEquals("OpenAI / Local · llama3.2", parsedOpenAi.providerLabel)
        assertEquals("Run benchmarks", parsedOpenAi.chips.first().label)
        assertNull(QuickReplies.parseOpenAiChatResponseJson("t10", """{"choices":[]}"""))

        val anthropicReq = QuickReplies.buildAnthropicMessagesRequestJson(ctx, "claude-sonnet-4-6", "System prompt")
        assertTrue(anthropicReq.contains("claude-sonnet-4-6"))
        val anthropicResp = """
            {
              "content": [
                {
                  "type": "text",
                  "text": "{\"tldr\":\"Asked about next step.\",\"replies\":[{\"label\":\"Deploy staging\",\"prompt\":\"Deploy to staging now.\"}]}"
                }
              ]
            }
        """.trimIndent()
        val parsedAnthropic = QuickReplies.parseAnthropicMessagesResponseJson("t10", anthropicResp, "Anthropic · claude-sonnet-4-6")
        assertNotNull(parsedAnthropic)
        assertEquals("Deploy staging", parsedAnthropic.chips.first().label)
        assertNull(QuickReplies.parseAnthropicMessagesResponseJson("t10", """{"content":[]}"""))

        val geminiModelsJson = """
            {
              "models": [
                {"name": "models/gemini-3.6-flash", "supportedGenerationMethods": ["generateContent"]},
                {"name": "models/text-embedding-004", "supportedGenerationMethods": ["embedContent"]}
              ]
            }
        """.trimIndent()
        assertEquals(listOf("gemini-3.6-flash"), QuickReplies.parseGeminiModelsJson(geminiModelsJson))

        val openAiModelsJson = """
            {
              "data": [
                {"id": "qwen2.5-coder:7b"},
                {"id": "llama3.2:latest"}
              ]
            }
        """.trimIndent()
        assertEquals(listOf("qwen2.5-coder:7b", "llama3.2:latest"), QuickReplies.parseOpenAiModelsJson(openAiModelsJson))

        val errMsg = QuickReplies.extractApiErrorMessage(404, """{"error":{"message":"models/gemini-3.6-flash is not found for API version v1beta"}}""")
        assertEquals("HTTP 404: models/gemini-3.6-flash is not found for API version v1beta", errMsg)
        assertEquals("HTTP 500", QuickReplies.extractApiErrorMessage(500, ""))

        // Gemini 2.5 / 3.x thinking part + JSON answer part
        val geminiWithThoughts = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "thought": true,
                        "text": "The user wants JSON like {replies: [...]}. Let me draft 2 options."
                      },
                      {
                        "text": "{\"replies\":[{\"label\":\"Run tests\",\"prompt\":\"Please run the unit tests.\"}]}"
                      }
                    ],
                    "role": "model"
                  },
                  "finishReason": "STOP"
                }
              ]
            }
        """.trimIndent()
        val parsedWithThoughts = QuickReplies.parseGeminiApiResponseJson("t11", geminiWithThoughts, "Google Gemini · gemini-3-flash")
        assertNotNull(parsedWithThoughts)
        assertEquals("Run tests", parsedWithThoughts.chips.first().label)

        // Truncated JSON recovery (e.g. if cut off mid-stream after emitting first complete chip object)
        val truncatedJson = """{"tldr":"Cut off response","replies":[{"label":"Approve plan","prompt":"Looks good, proceed with the plan."},{"label":"Wait"""""
        val recovered = QuickReplies.parseAiResponse("t11", truncatedJson, QuickReplySource.CLOUD_AI)
        assertNotNull(recovered)
        assertEquals("Approve plan", recovered.chips.first().label)

        // Top-level JSON array and string array support
        val topLevelArray = """[{"title":"Commit now","text":"Please commit these changes."}]"""
        val parsedArray = QuickReplies.parseAiResponse("t11", topLevelArray, QuickReplySource.CLOUD_AI)
        assertNotNull(parsedArray)
        assertEquals("Commit now", parsedArray.chips.first().label)

        val stringArray = """{"suggestions":["Yes, proceed with option 1","Run the test suite first"]}"""
        val parsedStringArray = QuickReplies.parseAiResponse("t11", stringArray, QuickReplySource.CLOUD_AI)
        assertNotNull(parsedStringArray)
        assertEquals(2, parsedStringArray.chips.size)

        // Bulleted fallback without pipe
        val bulletFallback = "- Yes, apply the migration\n- No, skip the migration for now"
        val parsedBullets = QuickReplies.parseAiResponse("t11", bulletFallback, QuickReplySource.CLOUD_AI)
        assertNotNull(parsedBullets)
        assertEquals(2, parsedBullets.chips.size)

        val maxTokensBody = """{"candidates":[{"finishReason":"MAX_TOKENS"}]}"""
        assertTrue(QuickReplies.describeUnparsedResponse(maxTokensBody).contains("MAX_TOKENS"))
    }
}
