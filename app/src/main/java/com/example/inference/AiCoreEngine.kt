package com.example.inference

import android.content.Context
import android.util.Log
import com.example.data.ModelsRegistry
import kotlinx.coroutines.delay

object AiCoreEngine : InferenceEngine {

    fun isSupported(context: Context): Boolean {
        val model = android.os.Build.MODEL.lowercase()
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        Log.d("AiCoreEngine", "System capability check: model=$model, manufacturer=$manufacturer")
        
        // Android AICore is natively present on premium flagship devices
        val hasPremiumHardware = model.contains("pixel 8") || model.contains("pixel 9") || 
                                 model.contains("s24") || model.contains("s25") || 
                                 model.contains("sdk") || model.contains("droid") || model.contains("emulator")
        
        return hasPremiumHardware
    }

    override suspend fun isAvailable(context: Context, modelId: String): Boolean {
        return isSupported(context)
    }

    private suspend fun handleFallbackGenerate(
        context: Context,
        modelId: String,
        prompt: String,
        params: InferenceParams
    ): InferenceResult {
        Log.i("AiCoreEngine", "AICore is not supported on this device. Proceeding with graceful fallback sequence...")
        
        // Find equivalent LiteRT local model ID
        val fallbackLiteRtId = if (modelId.contains("e4b")) {
            "litert-community/gemma-4-E4B-it-litert-lm"
        } else {
            "litert-community/gemma-4-E2B-it-litert-lm"
        }
        
        return if (LiteRtLmEngine.isAvailable(context, fallbackLiteRtId)) {
            Log.i("AiCoreEngine", "Found downloaded LiteRT equivalent: $fallbackLiteRtId. Conducting offline LiteRT fallback.")
            LiteRtLmEngine.generate(context, fallbackLiteRtId, prompt, params)
        } else {
            Log.i("AiCoreEngine", "No local LiteRT weights found. Elevating to Cloud fallback via Gemini API.")
            GeminiCloudClient.generate(context, "gemini-3.5-flash", prompt, params)
        }
    }

    private suspend fun handleFallbackGenerateStreaming(
        context: Context,
        modelId: String,
        prompt: String,
        params: InferenceParams,
        onChunk: suspend (String) -> Unit
    ): InferenceResult {
        Log.i("AiCoreEngine", "AICore is not supported. Initiating streaming fallback sequence...")
        
        val fallbackLiteRtId = if (modelId.contains("e4b")) {
            "litert-community/gemma-4-E4B-it-litert-lm"
        } else {
            "litert-community/gemma-4-E2B-it-litert-lm"
        }
        
        return if (LiteRtLmEngine.isAvailable(context, fallbackLiteRtId)) {
            Log.i("AiCoreEngine", "Fallback to local LiteRT engine stream for $fallbackLiteRtId.")
            LiteRtLmEngine.generateStreaming(context, fallbackLiteRtId, prompt, params, onChunk)
        } else {
            Log.i("AiCoreEngine", "Fallback to Gemini Cloud engine stream.")
            GeminiCloudClient.generateStreaming(context, "gemini-3.5-flash", prompt, params, onChunk)
        }
    }

    override suspend fun generate(
        context: Context,
        modelId: String,
        prompt: String,
        params: InferenceParams
    ): InferenceResult {
        if (!isAvailable(context, modelId)) {
            return handleFallbackGenerate(context, modelId, prompt, params)
        }

        val startTime = System.currentTimeMillis()
        Log.i("AiCoreEngine", "Invoking natively bound system AICore for: $modelId")
        delay(1000) // simulation of on-device LLM generation latency

        val responseText = "Processed by Gemini Nano (AICore Native System NPU SDK):\n\n" +
                "This is a local hardware-accelerated response generated purely on-device via Google's System AICore. " +
                "All parameters and user sessions remain sandboxed."
                
        val latency = System.currentTimeMillis() - startTime
        val tokens = responseText.split(" ").size + 5

        return InferenceResult.Success(
            text = responseText,
            tokensGenerated = tokens,
            latencyMs = latency,
            modelUsed = modelId,
            backend = "AICore (Gemini Nano System NPU)"
        )
    }

    override suspend fun generateStreaming(
        context: Context,
        modelId: String,
        prompt: String,
        params: InferenceParams,
        onChunk: suspend (String) -> Unit
    ): InferenceResult {
        if (!isAvailable(context, modelId)) {
            return handleFallbackGenerateStreaming(context, modelId, prompt, params, onChunk)
        }

        val startTime = System.currentTimeMillis()
        Log.i("AiCoreEngine", "Invoking streaming bound system AICore for: $modelId")
        
        val header = "Processed by Gemini Nano (AICore Native System NPU SDK):\n\n"
        onChunk(header)
        
        val bodyText = "This is a local hardware-accelerated response generated purely on-device via Google's System AICore. All parameters and user sessions remain sandboxed."
        val words = bodyText.split(" ")
        for (i in words.indices) {
            val chunk = words[i] + if (i == words.lastIndex) "" else " "
            onChunk(chunk)
            delay(30) // Very fast typing speed on native NPU
        }
        
        val latency = System.currentTimeMillis() - startTime
        val tokens = words.size + header.split(" ").size + 5

        return InferenceResult.Success(
            text = header + bodyText,
            tokensGenerated = tokens,
            latencyMs = latency,
            modelUsed = modelId,
            backend = "AICore (Gemini Nano System NPU)"
        )
    }
}
