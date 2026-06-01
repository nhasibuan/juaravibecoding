package com.example.server

import com.example.data.ModelsRegistry
import com.example.data.ModelBackend
import com.example.inference.InferenceEngine
import com.example.inference.LiteRtLmEngine
import com.example.inference.AiCoreEngine
import com.example.inference.GeminiCloudClient

object ModelRouter {

    fun getEngineForModel(modelId: String): InferenceEngine {
        val resolved = resolveModelId(modelId)
        val modelInfo = ModelsRegistry.allModels.firstOrNull { it.id.equals(resolved, ignoreCase = true) }
        return when (modelInfo?.backend) {
            ModelBackend.LOCAL_AICORE -> AiCoreEngine
            ModelBackend.LOCAL_LITERT -> LiteRtLmEngine
            ModelBackend.CLOUD -> GeminiCloudClient
            null -> {
                // For dynamic pass-through or unrecognized models
                if (resolved.contains("aicore", ignoreCase = true)) {
                    AiCoreEngine
                } else if (isLocalModel(resolved)) {
                    LiteRtLmEngine
                } else {
                    GeminiCloudClient
                }
            }
        }
    }

    fun isLocalModel(modelId: String): Boolean {
        val resolved = resolveModelId(modelId)
        val modelInfo = ModelsRegistry.allModels.firstOrNull { it.id.equals(resolved, ignoreCase = true) }
        return modelInfo?.isLocal ?: false
    }

    fun resolveModelId(modelId: String): String {
        val normalized = modelId.lowercase().trim()
        
        // Exact match in registry first
        ModelsRegistry.allModels.forEach {
            if (it.id.equals(normalized, ignoreCase = true)) {
                return it.id
            }
        }

        // Mappings from OpenAI standard aliases to Gemini
        if (normalized.contains("gpt-3.5") || normalized.contains("gpt-4o-mini")) {
            return "gemini-3.5-flash"
        }
        if (normalized.contains("gpt-4") || normalized.contains("claude-3")) {
            return "gemini-1.5-pro"
        }
        
        // Backward-compatible mappings for legacy/nonexistent model designations
        if (normalized == "gemma-2b-it" || normalized.contains("gemma3-1b") || normalized.contains("gemma-2b") || normalized.contains("gemma3")) {
            return "litert-community/Gemma3-1B-IT"
        }
        if (normalized == "llama-3.2-1b-it" || normalized.contains("llama-3.2-1b")) {
            return "litert-community/Gemma3-1B-IT"
        }
        if (normalized.contains("deepseek-r1-dist-qwen-1.5b") || normalized.contains("deepseek")) {
            return "litert-community/DeepSeek-R1-Distill-Qwen-1.5B"
        }
        if (normalized.contains("gemma-4-e2b")) {
            return "litert-community/gemma-4-E2B-it-litert-lm"
        }
        if (normalized.contains("gemma-4-e4b")) {
            return "litert-community/gemma-4-E4B-it-litert-lm"
        }
        
        // Pass through any other Gemini/Google models directly
        if (normalized.contains("gemini-") || normalized.contains("google/")) {
            return normalized
        }
        
        // Default fallback to fast flash
        return "gemini-3.5-flash"
    }

    fun mapToRealCloudModelId(modelId: String): String {
        val normalized = modelId.lowercase().trim()
        if (normalized == "gemini-3.5-flash" || normalized.contains("3.5-flash")) {
            return "gemini-3.5-flash"
        }
        if (normalized == "gemini-1.5-pro" || normalized == "gemini-3.1-pro-preview" || normalized.contains("1.5-pro") || normalized.contains("3.1-pro") || normalized.contains("3.1")) {
            return "gemini-1.5-pro"
        }
        return modelId
    }

    fun getDisplayName(modelId: String): String {
        val resolved = resolveModelId(modelId)
        return ModelsRegistry.allModels.firstOrNull { it.id == resolved }?.name ?: resolved
    }
}
