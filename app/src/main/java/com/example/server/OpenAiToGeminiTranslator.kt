package com.example.server

import com.example.inference.LiteRtLmEngine
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object OpenAiToGeminiTranslator {

    class MultimodalParseException(message: String) : Exception(message)

    fun translateOpenAiMessages(messagesArray: JSONArray): List<LiteRtLmEngine.HistoryTurn> {
        val list = mutableListOf<LiteRtLmEngine.HistoryTurn>()
        for (i in 0 until messagesArray.length()) {
            val msg = messagesArray.getJSONObject(i)
            val roleStr = msg.optString("role", "user")
            val contentObj = msg.opt("content")
            
            val text = when (contentObj) {
                is JSONArray -> {
                    val sb = StringBuilder()
                    for (j in 0 until contentObj.length()) {
                        val piece = contentObj.getJSONObject(j)
                        if (piece.optString("type") == "text") {
                            sb.append(piece.optString("text"))
                        }
                    }
                    sb.toString()
                }
                else -> contentObj?.toString() ?: ""
            }

            val role = if (roleStr.equals("assistant", ignoreCase = true)) {
                LiteRtLmEngine.HistoryRole.ASSISTANT
            } else {
                LiteRtLmEngine.HistoryRole.USER
            }

            list.add(LiteRtLmEngine.HistoryTurn(role, text))
        }
        return list
    }

    fun wrapLocalSuccess(res: LiteRtLmEngine.Result.Ok, openAiModel: String): String {
        val created = System.currentTimeMillis() / 1000
        val uuid = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "")
        
        val response = JSONObject()
        response.put("id", uuid)
        response.put("object", "chat.completion")
        response.put("created", created)
        response.put("model", openAiModel)
        
        val choice = JSONObject()
        choice.put("index", 0)
        
        val msg = JSONObject()
        msg.put("role", "assistant")
        msg.put("content", res.text)
        
        choice.put("message", msg)
        choice.put("finish_reason", "stop")
        
        val choices = JSONArray()
        choices.put(choice)
        response.put("choices", choices)
        
        val usage = JSONObject()
        usage.put("prompt_tokens", (res.text.length / 4) + 10) // rough proxy estimation
        usage.put("completion_tokens", res.tokensGenerated)
        usage.put("total_tokens", (res.text.length / 4) + 10 + res.tokensGenerated)
        response.put("usage", usage)
        
        return response.toString()
    }

    fun wrapLocalSuccessChunk(text: String, openAiModel: String, finishReason: String? = null): String {
        val created = System.currentTimeMillis() / 1000
        val uuid = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "")
        
        val chunkObj = JSONObject()
        chunkObj.put("id", uuid)
        chunkObj.put("object", "chat.completion.chunk")
        chunkObj.put("created", created)
        chunkObj.put("model", openAiModel)
        
        val choice = JSONObject()
        choice.put("index", 0)
        
        val delta = JSONObject()
        if (text.isNotEmpty()) {
            delta.put("content", text)
        }
        
        choice.put("delta", delta)
        choice.put("finish_reason", finishReason ?: JSONObject.NULL)
        
        val choices = JSONArray()
        choices.put(choice)
        chunkObj.put("choices", choices)
        
        return "data: ${chunkObj}\n\n"
    }

    fun wrapLocalError(res: LiteRtLmEngine.Result.Err, openAiModel: String): Pair<Int, String> {
        val code = when (res) {
            is LiteRtLmEngine.Result.Err.IncompleteWeights -> 400
            is LiteRtLmEngine.Result.Err.LoadError -> 500
            is LiteRtLmEngine.Result.Err.ExecutionError -> 500
        }
        
        val errObj = JSONObject()
        val innerErr = JSONObject()
        innerErr.put("message", "Local LiteRT-LM engine error: " + when (res) {
            is LiteRtLmEngine.Result.Err.IncompleteWeights -> res.message
            is LiteRtLmEngine.Result.Err.LoadError -> res.message
            is LiteRtLmEngine.Result.Err.ExecutionError -> res.message
        })
        innerErr.put("type", "invalid_request_error")
        innerErr.put("param", JSONObject.NULL)
        innerErr.put("code", code)
        errObj.put("error", innerErr)
        
        return Pair(code, errObj.toString())
    }

    fun wrapStandardError(statusCode: Int, message: String): String {
        val errObj = JSONObject()
        val inner = JSONObject()
        inner.put("message", message)
        inner.put("type", "api_error")
        inner.put("param", JSONObject.NULL)
        inner.put("code", statusCode)
        errObj.put("error", inner)
        return errObj.toString()
    }

    fun wrapModelsResponse(): String {
        val root = JSONObject()
        root.put("object", "list")
        val data = JSONArray()
        
        // Add Gemini models
        val gemini35 = JSONObject()
        gemini35.put("id", "gemini-3.5-flash")
        gemini35.put("object", "model")
        gemini35.put("created", 1700000000)
        gemini35.put("owned_by", "google")
        data.put(gemini35)

        val gemini30Pro = JSONObject()
        gemini30Pro.put("id", "gemini-3.1-pro-preview")
        gemini30Pro.put("object", "model")
        gemini30Pro.put("created", 1700000000)
        gemini30Pro.put("owned_by", "google")
        data.put(gemini30Pro)

        // Add local LiteRT models
        val gemma2b = JSONObject()
        gemma2b.put("id", "gemma-2b-it")
        gemma2b.put("object", "model")
        gemma2b.put("created", 1700000000)
        gemma2b.put("owned_by", "google-local")
        data.put(gemma2b)

        val llama32 = JSONObject()
        llama32.put("id", "llama-3.2-1b-it")
        llama32.put("object", "model")
        llama32.put("created", 1700000000)
        llama32.put("owned_by", "meta-local")
        data.put(llama32)

        val deepseek = JSONObject()
        deepseek.put("id", "deepseek-r1-dist-qwen-1.5b")
        deepseek.put("object", "model")
        deepseek.put("created", 1700000000)
        deepseek.put("owned_by", "deepseek-local")
        data.put(deepseek)

        root.put("data", data)
        return root.toString()
    }
}
