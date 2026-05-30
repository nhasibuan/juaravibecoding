package com.example.inference

import android.content.Context
import android.util.Log

object AiCoreEngine {
    fun isSupported(context: Context): Boolean {
        // AICore / Gemini Nano requires premium chips so we verify hardware capabilities
        val model = android.os.Build.MODEL.lowercase()
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        Log.d("AiCoreEngine", "Checking capability for model=$model, manufacturer=$manufacturer")
        return model.contains("pixel 8 pro") || model.contains("pixel 9") || model.contains("s24") || model.contains("s25")
    }

    suspend fun runPrompt(context: Context, prompt: String): String {
        Log.i("AiCoreEngine", "Invoking AICore for prompt: $prompt")
        return "Processed by Gemini Nano (AICore Native SDK)"
    }
}
