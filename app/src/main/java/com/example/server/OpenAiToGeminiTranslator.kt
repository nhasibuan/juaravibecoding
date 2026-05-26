package com.example.server

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object OpenAiToGeminiTranslator {

    class MultimodalParseException(message: String) : Exception(message)

    data class LocalEngineRequest(
        val systemInstruction: String?,
        val history: List<com.example.inference.LiteRtLmEngine.HistoryTurn>,
        val latestUserText: String,
        val maxTokens: Int,
        val temperature: Float,
        val topK: Int,
        val topP: Float,
        val rejectedMultimodalReason: String? = null
    )

    data class ExtractedContentResult(
        val text: String,
        val detectedTypes: List<String>
    )

    fun validateDataUri(url: String, typePrefix: String) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            throw MultimodalParseException("HTTP/HTTPS URLs are not supported: $url")
        }
        if (!url.startsWith("data:")) {
            throw MultimodalParseException("Invalid URL scheme: only data: URIs are supported.")
        }
        val commaIndex = url.indexOf(",")
        if (commaIndex == -1 || !url.contains(";base64,")) {
            throw MultimodalParseException("Malformed data URI: missing base64 encoding prefix.")
        }
        val mimeAndBase64 = url.substring("data:".length, commaIndex)
        if (!mimeAndBase64.endsWith(";base64")) {
            throw MultimodalParseException("Malformed data URI: must use base64 encoding.")
        }
        val mimeType = mimeAndBase64.substring(0, mimeAndBase64.length - ";base64".length)
        val base64Data = url.substring(commaIndex + 1)
        if (mimeType.isEmpty() || base64Data.isEmpty()) {
            throw MultimodalParseException("Malformed data URI: empty mimeType or data.")
        }
        if (typePrefix.isNotEmpty() && !mimeType.startsWith("$typePrefix/")) {
            throw MultimodalParseException("Expected MIME type prefix '$typePrefix/', got: $mimeType")
        }
    }

    fun extractTextAndDetectMultimodal(contentObj: Any?): ExtractedContentResult {
        val sb = StringBuilder()
        val detected = mutableListOf<String>()
        if (contentObj is String) {
            sb.append(contentObj)
        } else if (contentObj is JSONArray) {
            for (j in 0 until contentObj.length()) {
                val subObj = contentObj.optJSONObject(j) ?: continue
                val type = subObj.optString("type")
                if (type == "text") {
                    sb.append(subObj.optString("text"))
                } else if (type == "image_url") {
                    val imageUrlObj = subObj.optJSONObject("image_url")
                    if (imageUrlObj == null) {
                        throw MultimodalParseException("Missing image_url container in block.")
                    }
                    val url = imageUrlObj.optString("url", "")
                    validateDataUri(url, "image")
                    detected.add("image_url")
                } else if (type == "input_audio") {
                    val inputAudioObj = subObj.optJSONObject("input_audio")
                    if (inputAudioObj == null) {
                        throw MultimodalParseException("Missing input_audio container in block.")
                    }
                    val format = inputAudioObj.optString("format", "")
                    if (format != "mp3" && format != "wav" && format != "ogg" && format != "flac") {
                        throw MultimodalParseException("Unsupported audio format: $format")
                    }
                    val base64Data = inputAudioObj.optString("data", "")
                    if (base64Data.isEmpty()) {
                        throw MultimodalParseException("Audio data is empty")
                    }
                    detected.add("input_audio")
                }
            }
        }
        return ExtractedContentResult(sb.toString(), detected)
    }

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
            val isSystem = (role == "system")

            val contentObj = msg.opt("content")
            
            val partsArr = JSONArray()
            var textOnly = ""

            if (contentObj is String) {
                if (contentObj.isNotEmpty()) {
                    partsArr.put(JSONObject().put("text", contentObj))
                    textOnly = contentObj
                }
            } else if (contentObj is JSONArray) {
                val sb = StringBuilder()
                for (j in 0 until contentObj.length()) {
                    val subObj = contentObj.optJSONObject(j) ?: continue
                    val type = subObj.optString("type")
                    if (type == "text") {
                        val txt = subObj.optString("text", "")
                        if (txt.isNotEmpty()) {
                            partsArr.put(JSONObject().put("text", txt))
                            sb.append(txt)
                        }
                    } else if (!isSystem) {
                        if (type == "image_url") {
                            val imageUrlObj = subObj.optJSONObject("image_url")
                            if (imageUrlObj == null) {
                                throw MultimodalParseException("Missing image_url container in block.")
                            }
                            val url = imageUrlObj.optString("url", "")
                            validateDataUri(url, "image")
                            
                            val commaIndex = url.indexOf(",")
                            val mimeAndBase64 = url.substring("data:".length, commaIndex)
                            val mimeType = mimeAndBase64.substring(0, mimeAndBase64.length - ";base64".length)
                            val base64Data = url.substring(commaIndex + 1)

                            val inlineData = JSONObject()
                                .put("mimeType", mimeType)
                                .put("data", base64Data)
                            partsArr.put(JSONObject().put("inlineData", inlineData))
                            
                        } else if (type == "input_audio") {
                            val inputAudioObj = subObj.optJSONObject("input_audio")
                            if (inputAudioObj == null) {
                                throw MultimodalParseException("Missing input_audio container in block.")
                            }
                            val format = inputAudioObj.optString("format", "")
                            if (format != "mp3" && format != "wav" && format != "ogg" && format != "flac") {
                                throw MultimodalParseException("Unsupported audio format: $format")
                            }
                            val base64Data = inputAudioObj.optString("data", "")
                            if (base64Data.isEmpty()) {
                                throw MultimodalParseException("Audio data is empty")
                            }

                            val mimeType = "audio/$format"
                            val inlineData = JSONObject()
                                .put("mimeType", mimeType)
                                .put("data", base64Data)
                            partsArr.put(JSONObject().put("inlineData", inlineData))
                        }
                    }
                }
                textOnly = sb.toString()
            }

            if (isSystem) {
                systemInstructionText = textOnly
                continue
            }

            if (partsArr.length() > 0) {
                val geminiRole = if (role == "assistant") "model" else "user"
                val contentItem = JSONObject()
                    .put("role", geminiRole)
                    .put("parts", partsArr)
                CONTENTS.put(contentItem)
            }
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
        val allDetected = mutableListOf<String>()

        for (i in 0 until messages.length()) {
            val msg = messages.getJSONObject(i)
            val role = msg.optString("role", "user")
            
            val contentObj = msg.opt("content")
            val extracted = extractTextAndDetectMultimodal(contentObj)
            val contentText = extracted.text

            if (role == "system") {
                if (contentText.isNotEmpty()) {
                    systemList.add(contentText)
                }
                continue
            } else {
                if (extracted.detectedTypes.isNotEmpty()) {
                    allDetected.addAll(extracted.detectedTypes)
                }
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

        val rejectedReason = if (allDetected.isNotEmpty()) {
            allDetected.distinct().joinToString(" and ")
        } else {
            null
        }

        return LocalEngineRequest(
            systemInstruction = systemInstruction,
            history = history,
            latestUserText = latestUserText,
            maxTokens = maxTokens,
            temperature = temperature,
            topK = topK,
            topP = topP,
            rejectedMultimodalReason = rejectedReason
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
