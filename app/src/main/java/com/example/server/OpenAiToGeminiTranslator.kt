package com.example.server

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object OpenAiToGeminiTranslator {

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
            val content = msg.optString("content", "")

            if (role == "system") {
                systemInstructionText = content
                continue
            }

            // Map OpenAI assistant role to Gemini model role
            val geminiRole = if (role == "assistant") "model" else "user"

            val partObj = JSONObject().put("text", content)
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

    /**
     * Generates a simulated response matching a specified model.
     * Supports highly realistic thinking logs if the model is a thinking-distilled model like DeepSeek R1!
     */
    fun generateSimulatedResponse(openAiJson: String, openAiModel: String): String {
        val openAiObj = JSONObject(openAiJson)
        val messages = openAiObj.optJSONArray("messages") ?: JSONArray()
        var userPrompt = "Hello!"
        if (messages.length() > 0) {
            val lastMsg = messages.getJSONObject(messages.length() - 1)
            userPrompt = lastMsg.optString("content", "Hello!")
        }

        val chatCmplId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
        val createdSeconds = System.currentTimeMillis() / 1000

        // Build customized, intelligent, highly realistic response based on the prompt
        val hasThinking = openAiModel.contains("DeepSeek-R1", ignoreCase = true) || openAiModel.contains("gemma-4", ignoreCase = true)
        
        val replyText = if (hasThinking) {
            val thinkingSteps = """
                <think>
                1. User is asking: "$userPrompt"
                2. Analyzing model choice: Current model in use is local $openAiModel.
                3. Compiling the optimal response structure on-device.
                4. Accelerating inference via NPU/GPU pipelines... Done.
                </think>
                Hello! This is a real-time, high-fidelity local inference simulated from your Android Gateway proxy. Currently, you are using the on-device LiteRT-LM model signature for '$openAiModel'.

                You asked: "$userPrompt"

                This server gateway operates fully offline on your device, listening on your local WiFi IP address, translating OpenAI chat completions securely. When model weights are loaded under '/sdcard/Android/data/', this pipeline runs on local silicon; otherwise, it resolves local mock prompts beautifully.
            """.trimIndent()
            thinkingSteps
        } else {
            """
                Hello from your local Android AI Proxy Server Gateway! 

                Selected Local Model: $openAiModel
                Prompt processed: "$userPrompt"

                Your client completed a successful request to the proxy gateway. This demonstrates high-performance, low-latency, secure local networking!
            """.trimIndent()
        }

        val choiceObj = JSONObject()
            .put("index", 0)
            .put("message", JSONObject().put("role", "assistant").put("content", replyText))
            .put("finish_reason", "stop")

        val choicesArr = JSONArray().put(choiceObj)

        val usageObj = JSONObject()
            .put("prompt_tokens", userPrompt.length / 4 + 8)
            .put("completion_tokens", replyText.length / 4)
            .put("total_tokens", (userPrompt.length / 4 + 8) + replyText.length / 4)

        return JSONObject()
            .put("id", chatCmplId)
            .put("object", "chat.completion")
            .put("created", createdSeconds)
            .put("model", openAiModel)
            .put("choices", choicesArr)
            .put("usage", usageObj)
            .toString()
    }
}
