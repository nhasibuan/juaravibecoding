package com.example.server

import com.example.data.ModelsRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object OpenAiToGeminiTranslator {

    class MultimodalParseException(message: String) : Exception(message)

    fun wrapLocalSuccess(text: String, tokensGenerated: Int, openAiModel: String): String {
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
        msg.put("content", text)
        
        choice.put("message", msg)
        choice.put("finish_reason", "stop")
        
        val choices = JSONArray()
        choices.put(choice)
        response.put("choices", choices)
        
        val usage = JSONObject()
        val estimatedPrompt = (text.length / 4) + 8
        usage.put("prompt_tokens", estimatedPrompt) 
        usage.put("completion_tokens", tokensGenerated)
        usage.put("total_tokens", estimatedPrompt + tokensGenerated)
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
        
        ModelsRegistry.allModels.forEach { model ->
            val m = JSONObject()
            m.put("id", model.id)
            m.put("object", "model")
            m.put("created", 1700000000)
            m.put("owned_by", if (model.isLocal) "local-edge" else "google-cloud")
            data.put(m)
        }

        root.put("data", data)
        return root.toString()
    }
}
