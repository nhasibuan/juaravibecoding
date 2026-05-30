package com.example.server

import com.example.data.ModelsRegistry

object ModelRouter {

    fun isLocalModel(modelId: String): Boolean {
        val resolved = resolveModelId(modelId)
        return ModelsRegistry.localModels.any { it.id.equals(resolved, ignoreCase = true) }
    }

    fun resolveModelId(modelId: String): String {
        val normalized = modelId.lowercase().trim()
        
        // Mappings from OpenAI standard aliases to Gemini
        if (normalized.contains("gpt-3.5") || normalized.contains("gpt-4o-mini")) {
            return "gemini-3.5-flash"
        }
        if (normalized.contains("gpt-4") || normalized.contains("claude-3")) {
            return "gemini-3.1-pro-preview"
        }
        
        // Match specific existing model registrations
        ModelsRegistry.allModels.forEach {
            if (it.id.equals(normalized, ignoreCase = true)) {
                return it.id
            }
        }
        
        // Default fallback to fast flash
        return "gemini-3.5-flash"
    }

    fun getDisplayName(modelId: String): String {
        val resolved = resolveModelId(modelId)
        return ModelsRegistry.allModels.firstOrNull { it.id == resolved }?.name ?: resolved
    }
}
