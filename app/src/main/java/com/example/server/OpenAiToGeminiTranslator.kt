package com.example.server

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object OpenAiToGeminiTranslator {

    data class LocalEngineRequest(
        val systemInstruction: String?,
        val history: List<com.example.inference.LiteRtLmEngine.HistoryTurn>,
        val latestUserText: String,
        val maxTokens: Int,
        val temperature: Float,
        val topK: Int,
        val topP: Float
    )

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

            val geminiRole = if (role == "assistant") "model" else "user"

            val partObj = JSONObject().put("text", contentText)
            val partsArr = JSONArray().put(partObj)

            val contentItem = JSONObject()
                .put("role", geminiRole)
                .put("parts", partsArr)

            CONTENTS.put(contentItem)
        }

        val geminiRoot = JSONObject().put("contents", CONTENTS)

        if (systemInstructionText.isNotEmpty()) {
            val systemPart = JSONObject().put("text", systemInstructionText)
            val systemParts = JSONArray().put(systemPart)
            val sysInstructionObj = JSONObject().put("parts", systemParts)
            geminiRoot.put("systemInstruction", sysInstructionObj)
        }

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

    /**
     * Extracts the last user message text and entire session parameters from the OpenAI request payload.
     */
    fun extractLocalEngineRequest(openAiJson: String): LocalEngineRequest {
        val openAiObj = JSONObject(openAiJson)
        val messages = openAiObj.optJSONArray("messages") ?: JSONArray()

        val systemList = mutableListOf<String>()
        val dialogTurns = mutableListOf<Pair<String, String>>()

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
                if (contentText.isNotEmpty()) {
                    systemList.add(contentText)
                }
                continue
            }

            dialogTurns.add(Pair(role, contentText))
        }

        val systemInstruction = if (systemList.isNotEmpty()) systemList.joinToString("\n") else null

        var latestUserText = ""
        val history = mutableListOf<com.example.inference.LiteRtLmEngine.HistoryTurn>()

        var lastUserIndex = -1
        for (i in dialogTurns.indices.reversed()) {
            if (dialogTurns[i].first == "user") {
                lastUserIndex = i
                latestUserText = dialogTurns[i].second
                break
            }
        }

        for (i in dialogTurns.indices) {
            if (i == lastUserIndex) {
                continue
            }
            val (role, text) = dialogTurns[i]
            val mappedRole = if (role == "assistant") {
                com.example.inference.LiteRtLmEngine.HistoryRole.ASSISTANT
            } else {
                com.example.inference.LiteRtLmEngine.HistoryRole.USER
            }
            history.add(com.example.inference.LiteRtLmEngine.HistoryTurn(mappedRole, text))
        }

        if (lastUserIndex == -1) {
            latestUserText = "Hello!"
        }

        val maxTokens = if (openAiObj.has("max_tokens")) {
            openAiObj.optInt("max_tokens", 2048)
        } else if (openAiObj.has("max_completion_tokens")) {
            openAiObj.optInt("max_completion_tokens", 2048)
        } else {
            2048
        }

        val temperature = openAiObj.optDouble("temperature", 1.0).toFloat()
        val topP = openAiObj.optDouble("top_p", 1.0).toFloat()
        val topK = openAiObj.optInt("top_k", 64)

        return LocalEngineRequest(
            systemInstruction = systemInstruction,
            history = history,
            latestUserText = latestUserText,
            maxTokens = maxTokens,
            temperature = temperature,
            topK = topK,
            topP = topP
        )
    }

    /**
     * Wrap successful LiteRT-LM runtime inference outcome into strict OpenAI chat completion layout.
     */
    fun wrapLocalSuccess(res: com.example.inference.LiteRtLmEngine.Result.Ok, openAiModel: String): String {
        val chatCmplId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
        val createdSeconds = System.currentTimeMillis() / 1000

        val textWithThought = if (!res.thought.isNullOrBlank()) {
            "<think>\n${res.thought}\n</think>\n${res.text}"
        } else {
            res.text
        }

        val selectionObj = JSONObject()
            .put("index", 0)
            .put("message", JSONObject().put("role", "assistant").put("content", textWithThought))
            .put("finish_reason", "stop")

        val choicesArr = JSONArray().put(selectionObj)

        val promptEst = 16
        val compEst = textWithThought.length / 4

        val usageObj = JSONObject()
            .put("prompt_tokens", if (promptEst > 0) promptEst else 16)
            .put("completion_tokens", if (compEst > 0) compEst else 1)
            .put("total_tokens", promptEst + compEst)

        val cacheStatus = if (res.kvCacheReused) "cache=hit" else "cache=miss"

        val out = JSONObject()
            .put("id", chatCmplId)
            .put("object", "chat.completion")
            .put("created", createdSeconds)
            .put("model", openAiModel)
            .put("choices", choicesArr)
            .put("usage", usageObj)
            .put("system_fingerprint", "litertlm:${res.backendUsed}:${res.latencyMs}ms:$cacheStatus")

        return out.toString()
    }

    /**
     * Map inference error categories to matching HTTP response codes and clean error descriptors.
     */
    fun wrapLocalError(res: com.example.inference.LiteRtLmEngine.Result.Err, openAiModel: String): Pair<Int, String> {
        val httpCode = when (res) {
            is com.example.inference.LiteRtLmEngine.Result.Err.IncompleteWeights -> 400
            is com.example.inference.LiteRtLmEngine.Result.Err.LoadError -> 500
            is com.example.inference.LiteRtLmEngine.Result.Err.ExecutionError -> 500
        }

        val errObj = JSONObject()
            .put("message", res.message)
            .put("type", res.type)
            .put("param", JSONObject.NULL)
            .put("code", httpCode)

        val root = JSONObject().put("error", errObj)
        return Pair(httpCode, root.toString())
    }

    fun streamingFirstDelta(
        chatCmplId: String,
        openAiModel: String,
        backendUsed: String,
        latencyMs: Long,
        kvCacheReused: Boolean
    ): String {
        val cacheStatus = if (kvCacheReused) "cache=hit" else "cache=miss"
        val delta = JSONObject().put("role", "assistant").put("content", "")
        val choice = JSONObject().put("index", 0).put("delta", delta).put("finish_reason", JSONObject.NULL)
        val choices = JSONArray().put(choice)
        return JSONObject()
            .put("id", chatCmplId)
            .put("object", "chat.completion.chunk")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", openAiModel)
            .put("choices", choices)
            .put("system_fingerprint", "litertlm:$backendUsed:${latencyMs}ms:$cacheStatus")
            .toString()
    }

    fun streamingContentDelta(
        chatCmplId: String,
        openAiModel: String,
        deltaText: String,
        backendUsed: String,
        latencyMs: Long,
        kvCacheReused: Boolean
    ): String {
        val cacheStatus = if (kvCacheReused) "cache=hit" else "cache=miss"
        val delta = JSONObject().put("content", deltaText)
        val choice = JSONObject().put("index", 0).put("delta", delta).put("finish_reason", JSONObject.NULL)
        val choices = JSONArray().put(choice)
        return JSONObject()
            .put("id", chatCmplId)
            .put("object", "chat.completion.chunk")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", openAiModel)
            .put("choices", choices)
            .put("system_fingerprint", "litertlm:$backendUsed:${latencyMs}ms:$cacheStatus")
            .toString()
    }

    fun streamingFinish(
        chatCmplId: String,
        openAiModel: String,
        backendUsed: String,
        latencyMs: Long,
        kvCacheReused: Boolean
    ): String {
        val cacheStatus = if (kvCacheReused) "cache=hit" else "cache=miss"
        val delta = JSONObject()
        val choice = JSONObject().put("index", 0).put("delta", delta).put("finish_reason", "stop")
        val choices = JSONArray().put(choice)
        return JSONObject()
            .put("id", chatCmplId)
            .put("object", "chat.completion.chunk")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", openAiModel)
            .put("choices", choices)
            .put("system_fingerprint", "litertlm:$backendUsed:${latencyMs}ms:$cacheStatus")
            .toString()
    }

    fun streamingError(
        chatCmplId: String,
        openAiModel: String,
        errorMsg: String,
        backendUsed: String,
        latencyMs: Long,
        kvCacheReused: Boolean
    ): String {
        val cacheStatus = if (kvCacheReused) "cache=hit" else "cache=miss"
        val delta = JSONObject().put("content", "[ERROR: $errorMsg]")
        val choice = JSONObject().put("index", 0).put("delta", delta).put("finish_reason", "error")
        val choices = JSONArray().put(choice)
        return JSONObject()
            .put("id", chatCmplId)
            .put("object", "chat.completion.chunk")
            .put("created", System.currentTimeMillis() / 1000)
            .put("model", openAiModel)
            .put("choices", choices)
            .put("system_fingerprint", "litertlm:$backendUsed:${latencyMs}ms:$cacheStatus")
            .toString()
    }
}
