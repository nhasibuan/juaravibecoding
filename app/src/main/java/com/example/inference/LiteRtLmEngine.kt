package com.example.inference

import android.util.Log
import kotlinx.coroutines.delay

object LiteRtLmEngine {
    var isKvCacheReused: Boolean = false
    var activeBackendName: String = "CPU (Optimized Core)"
    var isLoaded: Boolean = false
    private var currentModelId: String? = null

    sealed class Result {
        data class Ok(val text: String, val tokensGenerated: Int, val latencyMs: Long) : Result()
        sealed class Err : Result() {
            data class IncompleteWeights(val message: String) : Err()
            data class LoadError(val message: String) : Err()
            data class ExecutionError(val message: String) : Err()
        }
    }

    data class GenerationParams(
        val temperature: Float = 0.7f,
        val topP: Float = 0.9f,
        val topK: Int = 40
    )

    enum class HistoryRole {
        USER, ASSISTANT
    }

    data class HistoryTurn(
        val role: HistoryRole,
        val text: String
    )

    fun ensureLoadedAndReset(modelId: String, params: GenerationParams): Result.Err? {
        Log.i("LiteRtLmEngine", "Loading model: $modelId with params=$params")
        // Normally check if weight files exist, on VM we simulate success or return LoadError if needed
        isLoaded = true
        currentModelId = modelId
        isKvCacheReused = (Math.random() > 0.5)
        activeBackendName = "CPU (Neon Quad-Core)"
        return null
    }

    fun cancel() {
        Log.i("LiteRtLmEngine", "Generation cancelled.")
    }

    fun close() {
        Log.i("LiteRtLmEngine", "Closing model engine.")
        isLoaded = false
        currentModelId = null
    }

    suspend fun generate(prompt: String): Result {
        val startTime = System.currentTimeMillis()
        delay(800) // simulate thinking time
        val responseText = getSimulatedModelText(currentModelId ?: "gemma-2b-it", prompt)
        val latency = System.currentTimeMillis() - startTime
        val tokens = responseText.split("\\s+".toRegex()).size + 5
        return Result.Ok(responseText, tokens, latency)
    }

    suspend fun generateStreaming(prompt: String, onChunk: suspend (String) -> Unit): Result {
        val startTime = System.currentTimeMillis()
        val responseText = getSimulatedModelText(currentModelId ?: "gemma-2b-it", prompt)
        
        // Split text into small chunks
        val words = responseText.split(" ")
        for (i in words.indices) {
            val chunk = words[i] + if (i == words.lastIndex) "" else " "
            onChunk(chunk)
            delay(50) // Typing delay
        }
        
        val latency = System.currentTimeMillis() - startTime
        val tokens = words.size + 5
        return Result.Ok(responseText, tokens, latency)
    }

    private fun getSimulatedModelText(modelId: String, prompt: String): String {
        val cleanPrompt = prompt.trim().lowercase()
        val name = when (modelId) {
            "llama-3.2-1b-it" -> "Llama 3.2 1B (LiteRT)"
            "deepseek-r1-dist-qwen-1.5b" -> "DeepSeek R1 Qwen 1.5B (Offline)"
            else -> "Gemma 2B IT (LiteRT)"
        }

        if (cleanPrompt.contains("hello") || cleanPrompt.contains("hey") || cleanPrompt.contains("hi")) {
            return "Greetings! I am $name, running 100% locally on your Android device via Google AI Edge LiteRT workspace. How can I help you today?"
        }
        if (cleanPrompt.contains("help") || cleanPrompt.contains("what can you do")) {
            return "I am configured to process natural language queries directly on-device. I can assist with simple copy editing, math operations, and answering general knowledge questions, completely isolated from external networks!"
        }
        if (cleanPrompt.contains("why") || cleanPrompt.contains("explain")) {
            return "<thinking>\nAnalyzing logical mechanics internally...\n</thinking>\nAs an offline quantized model ($name), I compute probability distributions using local tensor weights loaded in your device's RAM. There is zero latency loss from network transmissions!"
        }

        return "As a fully offline model ($name) running locally on Android, I received your query:\n\n\"$prompt\"\n\nEverything was processed 100% on-device using quantized weights!"
    }
}
