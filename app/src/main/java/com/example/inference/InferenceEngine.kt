package com.example.inference

import android.content.Context

interface InferenceEngine {
    
    suspend fun isAvailable(context: Context, modelId: String): Boolean
    
    suspend fun generate(
        context: Context,
        modelId: String,
        prompt: String,
        params: InferenceParams
    ): InferenceResult
    
    suspend fun generateStreaming(
        context: Context,
        modelId: String,
        prompt: String,
        params: InferenceParams,
        onChunk: suspend (String) -> Unit
    ): InferenceResult
}

data class InferenceParams(
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val apiKey: String = "",
    val enableNpuBackend: Boolean = false,
    val bypassGpu: Boolean = true,
    val preferredBackend: String = "AUTO"
)

sealed class InferenceResult {
    data class Success(
        val text: String,
        val tokensGenerated: Int,
        val latencyMs: Long,
        val modelUsed: String,
        val backend: String = "CPU"
    ) : InferenceResult()

    sealed class Error : InferenceResult() {
        abstract val message: String
        data class LoadError(override val message: String) : Error()
        data class ExecutionError(override val message: String) : Error()
        data class IncompleteWeights(override val message: String) : Error()
        data class Unauthorized(override val message: String) : Error()
    }
}
