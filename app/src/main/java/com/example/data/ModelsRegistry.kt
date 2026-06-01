package com.example.data

enum class ModelBackend {
    LOCAL_LITERT,
    LOCAL_AICORE,
    CLOUD
}

data class ModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val sizeGb: Double,
    val backend: ModelBackend,
    val downloadUrl: String,
    val sha256: String = "",
    val expectedBytes: Long = 0L
) {
    val isLocal: Boolean get() = backend != ModelBackend.CLOUD
}

object ModelsRegistry {
    val cloudModels = listOf(
        ModelInfo(
            id = "gemini-3.5-flash",
            name = "Gemini 3.5 Flash",
            description = "Google’s fast, highly scalable multimodal model (recommended default)",
            sizeGb = 0.0,
            backend = ModelBackend.CLOUD,
            downloadUrl = ""
        ),
        ModelInfo(
            id = "gemini-1.5-pro",
            name = "Gemini 1.5 Pro",
            description = "Premier model for complex reasoning, multi-turn dialogue, and coding tasks",
            sizeGb = 0.0,
            backend = ModelBackend.CLOUD,
            downloadUrl = ""
        )
    )

    val localModels = listOf(
        ModelInfo(
            id = "gemma-4-e2b-aicore",
            name = "Gemma 4 E2B (Gemini Nano via AICore)",
            description = "Gemini Nano available using Android AICore, optimized for your device. The recommended path for production applications.",
            sizeGb = 0.0,
            backend = ModelBackend.LOCAL_AICORE,
            downloadUrl = ""
        ),
        ModelInfo(
            id = "litert-community/gemma-4-E2B-it-litert-lm",
            name = "Gemma-4-E2B-it",
            description = "A variant of Gemma 4 E2B ready for deployment on Android using LiteRT-LM. It supports multi-modality input, with up to 32K context length.",
            sizeGb = 2.36,
            backend = ModelBackend.LOCAL_LITERT,
            downloadUrl = "https://dl.google.com/google-ai-edge-gallery/android/gemma4/20260325/gemma4_2b_v09_obfus_fix_all_modalities_thinking.litertlm"
        ),
        ModelInfo(
            id = "gemma-4-e4b-aicore",
            name = "Gemma 4 E4B (Gemini Nano via AICore)",
            description = "Gemini Nano available using Android AICore, optimized for your device. The recommended path for production applications.",
            sizeGb = 0.0,
            backend = ModelBackend.LOCAL_AICORE,
            downloadUrl = ""
        ),
        ModelInfo(
            id = "litert-community/gemma-4-E4B-it-litert-lm",
            name = "Gemma-4-E4B-it",
            description = "A variant of Gemma 4 E4B ready for deployment on Android using LiteRT-LM. It supports multi-modality input, with up to 32K context length.",
            sizeGb = 3.36,
            backend = ModelBackend.LOCAL_LITERT,
            downloadUrl = "https://dl.google.com/google-ai-edge-gallery/android/gemma4/20260325/gemma4_4b_v09_obfus_fix_all_modalities_thinking.litertlm"
        ),
        ModelInfo(
            id = "google/gemma-3n-E2B-it-litert-lm",
            name = "Gemma-3n-E2B-it",
            description = "A variant of Gemma 3n E2B ready for deployment on Android using LiteRT-LM. It supports text, vision, and audio input, with 4096 context length.",
            sizeGb = 3.40,
            backend = ModelBackend.LOCAL_LITERT,
            downloadUrl = "https://dl.google.com/google-ai-edge-gallery/android/gemma3n/20260218/gemma-3n-E2B-it-int4.litertlm"
        ),
        ModelInfo(
            id = "google/gemma-3n-E4B-it-litert-lm",
            name = "Gemma-3n-E4B-it",
            description = "A variant of Gemma 3n E4B ready for deployment on Android using LiteRT-LM. It supports text, vision, and audio input, with 4096 context length.",
            sizeGb = 4.58,
            backend = ModelBackend.LOCAL_LITERT,
            downloadUrl = "https://dl.google.com/google-ai-edge-gallery/android/gemma3n/20260218/gemma-3n-E4B-it-int4.litertlm"
        ),
        ModelInfo(
            id = "litert-community/Gemma3-1B-IT",
            name = "Gemma3-1B-IT",
            description = "A variant of google/Gemma-3-1B-IT with 4-bit quantization ready for deployment on Android using LiteRT-LM.",
            sizeGb = 0.54,
            backend = ModelBackend.LOCAL_LITERT,
            downloadUrl = "https://dl.google.com/google-ai-edge-gallery/android/gemma3-1b-it/20260217/gemma3-1b-it-int4.litertlm"
        ),
        ModelInfo(
            id = "litert-community/Qwen2.5-1.5B-Instruct",
            name = "Qwen2.5-1.5B-Instruct",
            description = "A variant of Qwen/Qwen2.5-1.5B-Instruct ready for deployment on Android using LiteRT-LM.",
            sizeGb = 1.49,
            backend = ModelBackend.LOCAL_LITERT,
            downloadUrl = ""
        ),
        ModelInfo(
            id = "litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
            name = "DeepSeek-R1-Distill-Qwen-1.5B",
            description = "A variant of deepseek-ai/DeepSeek-R1-Distill-Qwen-1.5B ready for deployment on Android using LiteRT-LM.",
            sizeGb = 1.71,
            backend = ModelBackend.LOCAL_LITERT,
            downloadUrl = ""
        ),
        ModelInfo(
            id = "litert-community/functiongemma-270m-ft-tiny-garden",
            name = "TinyGarden-270M",
            description = "Fine-tuned Function Gemma 270M model for Tiny Garden.",
            sizeGb = 0.27,
            backend = ModelBackend.LOCAL_LITERT,
            downloadUrl = "https://dl.google.com/google-ai-edge-gallery/android/tiny-garden/20260225/tinygarden.litertlm"
        ),
        ModelInfo(
            id = "litert-community/functiongemma-270m-ft-mobile-actions",
            name = "MobileActions-270M",
            description = "Fine-tuned Function Gemma 270M model for Mobile Actions.",
            sizeGb = 0.27,
            backend = ModelBackend.LOCAL_LITERT,
            downloadUrl = "https://dl.google.com/google-ai-edge-gallery/android/mobile-actions/20260218/mobile_actions.litertlm"
        )
    )

    val allModels = cloudModels + localModels
}
