package com.example.server

import com.example.inference.LiteRtLmEngine
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Translates between OpenAI Chat-Completions and:
 *   1. Google Gemini REST shape  (cloud path — translateRequest / translateResponse)
 *   2. LiteRT-LM Kotlin SDK      (local path — extractLocalEngineRequest / wrapLocalSuccess / wrapLocalError)
 *
 * The previous simulator (`solveSimplePrompt`, `generateSimulatedResponse`,
 * `generateLiteRtLmResponse`) was removed: it was a regex-based keyword matcher
 * that fabricated "tokens/second" metrics and pretended to be on-device
 * inference. Real LiteRT-LM execution is now in [LiteRtLmEngine], following
 * the canonical pattern from google-ai-edge/gallery.
 */
object OpenAiToGeminiTranslator {

    // ------------------------------------------------------------------------
    // Cloud path (unchanged)
    // ------------------------------------------------------------------------

    /**
     * Translates an OpenAI Chat Completion request JSON into a Google Gemini REST request JSON.
     */
    fun translateRequest(openAiJson: String): String {
        val openAiObj = JSONObject(openAiJson)
        val messages = openAiObj.optJSONArray("messages") ?: JSONArray()

        val CONTENTS = JSONArray()
        var systemInstructionText = ""

        for (i in 0 until messages.length()) {
            val msg = messages.getJSONObject(i)
            val role = msg.optString("role", "user")

            var contentText = ""
            val contentObj = msg.opt("content")
            if (contentObj is String) {
                contentText = contentObj
            } else if (contentObj is JSONArray) {
                val sb = StringBuilder()
                for (j in 0 until contentObj.length()) {
                    val subObj = contentObj.optJSONObject(j)
                    if (subObj != null) {
                        if (subObj.optString("type") == "text") {
                            sb.append(subObj.optString("text"))
                        }
                    }
                }
                contentText = sb.toString()
            }

            if (role == "system") {
                systemInstructionText = contentText
                continue
            }

            // Map OpenAI assistant role to Gemini model role
            val geminiRole = if (role == "assistant") "model" else "user"

            val partObj = JSONObject().put("text", contentText)
            val partsArr = JSONArray().put(partObj)

            val contentItem = JSONObject()
                .put("role", geminiRole)
                .put("parts", partsArr)

            CONTENTS.put(contentItem)
        }

        val geminiRoot = JSONObject().put("contents", CONTENTS)

        // Map system instruction if present
        if (systemInstructionText.isNotEmpty()) {
            val systemPart = JSONObject().put("text", systemInstructionText)
            val systemParts = JSONArray().put(systemPart)
            val sysInstructionObj = JSONObject().put("parts", systemParts)
            geminiRoot.put("systemInstruction", sysInstructionObj)
        }

        // Map generation configs
        val genConfig = JSONObject()
        if (openAiObj.has("temperature")) {
            genConfig.put("temperature", openAiObj.get("temperature"))
        } else {
            genConfig.put("temperature", 1.0)
        }
        if (openAiObj.has("max_tokens")) {
            genConfig.put("maxOutputTokens", openAiObj.get("max_tokens"))
        } else if (openAiObj.has("max_completion_tokens")) {
            genConfig.put("maxOutputTokens", openAiObj.get("max_completion_tokens"))
        } else {
            genConfig.put("maxOutputTokens", 2048)
        }
        if (openAiObj.has("top_p")) {
            genConfig.put("topP", openAiObj.get("top_p"))
        }

        geminiRoot.put("generationConfig", genConfig)

        return geminiRoot.toString()
    }

    /**
     * Translates a Google Gemini API response JSON back to an OpenAI Chat Completion response JSON.
     */
    fun translateResponse(geminiJson: String, openAiModel: String): String {
        val geminiObj = JSONObject(geminiJson)
        val candidates = geminiObj.optJSONArray("candidates")

        var textContent = "No response text found."
        var finishReason = "stop"

        if (candidates != null && candidates.length() > 0) {
            val firstCandidate = candidates.getJSONObject(0)
            val contentObj = firstCandidate.optJSONObject("content")
            if (contentObj != null) {
                val parts = contentObj.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    textContent = parts.getJSONObject(0).optString("text", "")
                }
            }
            val rawFinishReason = firstCandidate.optString("finishReason", "STOP")
            finishReason = when (rawFinishReason) {
                "STOP" -> "stop"
                "MAX_TOKENS" -> "length"
                "SAFETY" -> "content_filter"
                else -> "stop"
            }
        }

        // Parse Usage metadata
        val usageMetadata = geminiObj.optJSONObject("usageMetadata")
        val promptTokens = usageMetadata?.optInt("promptTokenCount", 0) ?: 0
        val completionTokens = usageMetadata?.optInt("candidatesTokenCount", 0) ?: 0
        val totalTokens = usageMetadata?.optInt("totalTokenCount", 0) ?: 0

        val chatCmplId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
        val createdSeconds = System.currentTimeMillis() / 1000

        val selectionObj = JSONObject()
            .put("index", 0)
            .put("message", JSONObject().put("role", "assistant").put("content", textContent))
            .put("finish_reason", finishReason)

        val choicesArr = JSONArray().put(selectionObj)

        val usageObj = JSONObject()
            .put("prompt_tokens", if (promptTokens > 0) promptTokens else 16)
            .put("completion_tokens", if (completionTokens > 0) completionTokens else textContent.length / 4)
            .put("total_tokens", if (totalTokens > 0) totalTokens else (16 + textContent.length / 4))

        val openAiResponse = JSONObject()
            .put("id", chatCmplId)
            .put("object", "chat.completion")
            .put("created", createdSeconds)
            .put("model", openAiModel)
            .put("choices", choicesArr)
            .put("usage", usageObj)

        return openAiResponse.toString()
    }

    // ------------------------------------------------------------------------
    // Local LiteRT-LM path
    // ------------------------------------------------------------------------

    data class LocalEngineRequest(
        val systemInstruction: String?,
        val history: List<LiteRtLmEngine.HistoryTurn>,
        val latestUserText: String,
        val maxTokens: Int,
        val temperature: Float,
        val topK: Int,
        val topP: Float
    )

    /**
     * Extracts a request shaped for the LiteRT-LM Kotlin SDK from an OpenAI
     * chat.completions JSON body.
     *
     * Mapping:
     *  - All `system` role messages are concatenated into a single system instruction.
     *  - All `assistant` and prior `user` messages become history turns.
     *  - The last `user` message is split out as the live prompt for `generate(...)`.
     *  - `max_tokens` / `max_completion_tokens` / `temperature` / `top_p` / `top_k`
     *    are extracted with sensible defaults.
     */
    fun extractLocalEngineRequest(openAiJson: String): LocalEngineRequest {
        val obj = JSONObject(openAiJson)
        val msgs = obj.optJSONArray("messages") ?: JSONArray()

        val systemPieces = mutableListOf<String>()
        val turns = mutableListOf<LiteRtLmEngine.HistoryTurn>()
        var lastUserText = ""

        // First pass: collect system + history (all turns except the trailing user one).
        // We need to know which user message is the LAST one to peel it off.
        val lastUserIdx = (0 until msgs.length()).lastOrNull { i ->
            msgs.optJSONObject(i)?.optString("role") == "user"
        }

        for (i in 0 until msgs.length()) {
            val msg = msgs.getJSONObject(i)
            val role = msg.optString("role", "user")
            val text = extractTextFromContent(msg.opt("content"))

            when (role) {
                "system" -> if (text.isNotEmpty()) systemPieces.add(text)
                "assistant" -> turns.add(
                    LiteRtLmEngine.HistoryTurn(LiteRtLmEngine.HistoryRole.ASSISTANT, text)
                )
                else /* user or anything else */ -> {
                    if (i == lastUserIdx) {
                        lastUserText = text
                    } else {
                        turns.add(LiteRtLmEngine.HistoryTurn(LiteRtLmEngine.HistoryRole.USER, text))
                    }
                }
            }
        }

        // Defensive: no trailing user message at all → use empty prompt; the
        // engine will still produce a continuation based on history.
        val systemText = if (systemPieces.isEmpty()) null else systemPieces.joinToString("\n\n")

        val maxTokens = (obj.opt("max_tokens") as? Number)?.toInt()
            ?: (obj.opt("max_completion_tokens") as? Number)?.toInt()
            ?: 1024
        val temperature = (obj.opt("temperature") as? Number)?.toFloat() ?: 1.0f
        val topP = (obj.opt("top_p") as? Number)?.toFloat() ?: 0.95f
        val topK = (obj.opt("top_k") as? Number)?.toInt() ?: 64

        return LocalEngineRequest(
            systemInstruction = systemText,
            history = turns,
            latestUserText = lastUserText,
            maxTokens = maxTokens,
            temperature = temperature,
            topK = topK,
            topP = topP
        )
    }

    /** Wraps a successful LiteRT-LM response in an OpenAI Chat Completion JSON. */
    fun wrapLocalSuccess(result: LiteRtLmEngine.Result.Ok, openAiModel: String): String {
        val chatCmplId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
        val createdSeconds = System.currentTimeMillis() / 1000

        // If the model exposes a separate "thought" channel (gemma-4 thinking,
        // DeepSeek R1, etc.), render it inside <think>...</think> ahead of the
        // final answer. This matches the convention OpenAI-compatible clients
        // recognize for reasoning models.
        val replyContent = if (!result.thinkingText.isNullOrBlank()) {
            "<think>\n${result.thinkingText}\n</think>\n${result.text}"
        } else {
            result.text
        }

        val choice = JSONObject()
            .put("index", 0)
            .put("message", JSONObject().put("role", "assistant").put("content", replyContent))
            .put("finish_reason", "stop")

        val usage = JSONObject()
            .put("prompt_tokens", result.promptTokens)
            .put("completion_tokens", result.completionTokens)
            .put("total_tokens", result.promptTokens + result.completionTokens)

        return JSONObject()
            .put("id", chatCmplId)
            .put("object", "chat.completion")
            .put("created", createdSeconds)
            .put("model", openAiModel)
            .put("choices", JSONArray().put(choice))
            .put("usage", usage)
            // Honest provenance: real backend used + measured latency. No fabricated metrics.
            .put("system_fingerprint", "litertlm:${result.backendUsed}:${result.totalLatencyMs}ms")
            .toString()
    }

    /**
     * Maps a LiteRT-LM error onto an OpenAI-shaped error envelope and an HTTP status code.
     */
    fun wrapLocalError(err: LiteRtLmEngine.Result.Err, openAiModel: String): Pair<Int, String> {
        val status = when (err.type) {
            "model_not_found" -> 400
            "engine_not_ready" -> 500
            "engine_load_failed" -> 500
            "conversation_init_failed" -> 500
            "inference_failed" -> 500
            else -> 500
        }
        val body = JSONObject()
            .put("error", JSONObject()
                .put("message", err.message)
                .put("type", err.type)
                .put("code", status))
            .put("model", openAiModel)
            .toString()
        return status to body
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private fun extractTextFromContent(content: Any?): String {
        return when (content) {
            is String -> content
            is JSONArray -> {
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    val item = content.optJSONObject(i) ?: continue
                    if (item.optString("type") == "text") {
                        sb.append(item.optString("text"))
                    }
                }
                sb.toString()
            }
            else -> ""
        }
    }
}
