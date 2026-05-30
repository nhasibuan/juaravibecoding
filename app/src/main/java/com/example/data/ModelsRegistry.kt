package com.example.data

data class ModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val sizeGb: Double,
    val isLocal: Boolean,
    val downloadUrl: String
)

object ModelsRegistry {
    val cloudModels = listOf(
        ModelInfo(
            id = "gemini-3.5-flash",
            name = "Gemini 3.5 Flash",
            description = "Google’s fast, highly scalable multimodal model (recommended default)",
            sizeGb = 0.0,
            isLocal = false,
            downloadUrl = ""
        ),
        ModelInfo(
            id = "gemini-3.1-pro-preview",
            name = "Gemini 3.1 Pro (Preview)",
            description = "Premier model for complex reasoning, multi-turn dialogue, and coding tasks",
            sizeGb = 0.0,
            isLocal = false,
            downloadUrl = ""
        )
    )

    val localModels = listOf(
        ModelInfo(
            id = "gemma-2b-it",
            name = "Gemma 2B IT (LiteRT)",
            description = "Google's ultra-lightweight open model tuned for local chat, optimized for low RAM consumption.",
            sizeGb = 1.35,
            isLocal = true,
            downloadUrl = "https://huggingface.co/google/gemma-1.1-2b-it-tflite/resolve/main/gemma-1.1-2b-it-cpu-int4.bin"
        ),
        ModelInfo(
            id = "llama-3.2-1b-it",
            name = "Llama 3.2 1B IT (LiteRT)",
            description = "Meta's highly performance-distilled 1B instruction model compiled for LiteRT GPU/CPU.",
            sizeGb = 1.18,
            isLocal = true,
            downloadUrl = "https://huggingface.co/meta-llama/Llama-3.2-1B-Instruct/resolve/main/Llama-3.2-1B-Instruct-cpu-int8.bin"
        ),
        ModelInfo(
            id = "deepseek-r1-dist-qwen-1.5b",
            name = "DeepSeek R1 Qwen 1.5B (LiteRT)",
            description = "Synthesized reasoning model optimized for mathematical reasoning and thinking loops.",
            sizeGb = 1.62,
            isLocal = true,
            downloadUrl = "https://huggingface.co/deepseek-ai/DeepSeek-R1-Distill-Qwen-1.5B/resolve/main/DeepSeek-R1-Qwen-1.5B-cpu-int8.bin"
        )
    )

    val allModels = cloudModels + localModels
}
