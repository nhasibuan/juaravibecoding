package com.example.data

data class LocalModelInfo(
    val name: String,
    val modelId: String,
    val modelFile: String,
    val runtimeType: String, // "aicore", "litert-lm", "cloud"
    val description: String,
    val url: String = "",
    val sizeInBytes: Long = 0,
    val minDeviceMemoryInGb: Int = 0,
    val llmSupportThinking: Boolean = false,
    val llmSupportImage: Boolean = false,
    val llmSupportAudio: Boolean = false,
    val accelerators: String = "cpu,gpu"
)

object ModelsRegistry {
    val allowedModels = listOf(
        LocalModelInfo(
            name = "Gemma 4 E2B (Gemini Nano via AICore)",
            modelId = "aicore-gemma-4-e2b",
            modelFile = "system-managed",
            runtimeType = "aicore",
            description = "Gemini Nano available using Android AICore, optimized for your device. The recommended path for production applications.",
            sizeInBytes = 0,
            minDeviceMemoryInGb = 6,
            llmSupportImage = true,
            accelerators = "npu"
        ),
        LocalModelInfo(
            name = "Gemma-4-E2B-it",
            modelId = "litert-community/gemma-4-E2B-it-litert-lm",
            modelFile = "gemma4_2b_v09_obfus_fix_all_modalities_thinking.litertlm",
            runtimeType = "litert-lm",
            description = "A variant of Gemma 4 E2B ready for deployment on Android using LiteRT-LM. It supports multi-modality input, with up to 32K context length.",
            url = "https://dl.google.com/google-ai-edge-gallery/android/gemma4/20260325/gemma4_2b_v09_obfus_fix_all_modalities_thinking.litertlm",
            sizeInBytes = 2538766336,
            minDeviceMemoryInGb = 8,
            llmSupportThinking = true,
            llmSupportImage = true,
            llmSupportAudio = true,
            accelerators = "gpu,cpu"
        ),
        LocalModelInfo(
            name = "Gemma 4 E4B (Gemini Nano via AICore)",
            modelId = "aicore-gemma-4-e4b",
            modelFile = "system-managed",
            runtimeType = "aicore",
            description = "Gemini Nano available using Android AICore, optimized for your device. The recommended path for production applications.",
            sizeInBytes = 0,
            minDeviceMemoryInGb = 6,
            llmSupportImage = true,
            accelerators = "npu"
        ),
        LocalModelInfo(
            name = "Gemma-4-E4B-it",
            modelId = "litert-community/gemma-4-E4B-it-litert-lm",
            modelFile = "gemma4_4b_v09_obfus_fix_all_modalities_thinking.litertlm",
            runtimeType = "litert-lm",
            description = "A variant of Gemma 4 E4B ready for deployment on Android using LiteRT-LM. It supports multi-modality input, with up to 32K context length.",
            url = "https://dl.google.com/google-ai-edge-gallery/android/gemma4/20260325/gemma4_4b_v09_obfus_fix_all_modalities_thinking.litertlm",
            sizeInBytes = 3609411584,
            minDeviceMemoryInGb = 12,
            llmSupportThinking = true,
            llmSupportImage = true,
            llmSupportAudio = true,
            accelerators = "gpu,cpu"
        ),
        LocalModelInfo(
            name = "Gemma-3n-E2B-it",
            modelId = "google/gemma-3n-E2B-it-litert-lm",
            modelFile = "gemma-3n-E2B-it-int4.litertlm",
            runtimeType = "litert-lm",
            description = "A variant of Gemma 3n E2B ready for deployment on Android using LiteRT-LM. It supports text, vision, and audio input, with 4096 context length.",
            url = "https://dl.google.com/google-ai-edge-gallery/android/gemma3n/20260218/gemma-3n-E2B-it-int4.litertlm",
            sizeInBytes = 3655827456,
            minDeviceMemoryInGb = 8,
            llmSupportImage = true,
            llmSupportAudio = true,
            accelerators = "cpu,gpu"
        ),
        LocalModelInfo(
            name = "Gemma-3n-E4B-it",
            modelId = "google/gemma-3n-E4B-it-litert-lm",
            modelFile = "gemma-3n-E4B-it-int4.litertlm",
            runtimeType = "litert-lm",
            description = "A variant of Gemma 3n E4B ready for deployment on Android using LiteRT-LM. It supports text, vision, and audio input, with 4096 context length.",
            url = "https://dl.google.com/google-ai-edge-gallery/android/gemma3n/20260218/gemma-3n-E4B-it-int4.litertlm",
            sizeInBytes = 4919541760,
            minDeviceMemoryInGb = 12,
            llmSupportImage = true,
            llmSupportAudio = true,
            accelerators = "cpu,gpu"
        ),
        LocalModelInfo(
            name = "Gemma3-1B-IT",
            modelId = "litert-community/Gemma3-1B-IT",
            modelFile = "gemma3-1b-it-int4.litertlm",
            runtimeType = "litert-lm",
            description = "A variant of google/Gemma-3-1B-IT with 4-bit quantization ready for deployment on Android using LiteRT-LM.",
            url = "https://dl.google.com/google-ai-edge-gallery/android/gemma3-1b-it/20260217/gemma3-1b-it-int4.litertlm",
            sizeInBytes = 584417280,
            minDeviceMemoryInGb = 6,
            accelerators = "gpu,cpu"
        ),
        LocalModelInfo(
            name = "Qwen2.5-1.5B-Instruct",
            modelId = "litert-community/Qwen2.5-1.5B-Instruct",
            modelFile = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            runtimeType = "litert-lm",
            description = "A variant of Qwen/Qwen2.5-1.5B-Instruct ready for deployment on Android using LiteRT-LM.",
            sizeInBytes = 1597931520,
            minDeviceMemoryInGb = 6,
            accelerators = "gpu,cpu"
        ),
        LocalModelInfo(
            name = "DeepSeek-R1-Distill-Qwen-1.5B",
            modelId = "litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
            modelFile = "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
            runtimeType = "litert-lm",
            description = "A variant of deepseek-ai/DeepSeek-R1-Distill-Qwen-1.5B ready for deployment on Android using LiteRT-LM with high-fidelity reasoning paths.",
            sizeInBytes = 1833451520,
            minDeviceMemoryInGb = 6,
            llmSupportThinking = true,
            accelerators = "gpu,cpu"
        ),
        LocalModelInfo(
            name = "TinyGarden-270M",
            modelId = "litert-community/functiongemma-270m-ft-tiny-garden",
            modelFile = "tiny_garden.litertlm",
            runtimeType = "litert-lm",
            description = "Fine-tuned Function Gemma 270M model for Tiny Garden.",
            url = "https://dl.google.com/google-ai-edge-gallery/android/tiny-garden/20260225/tinygarden.litertlm",
            sizeInBytes = 288964608,
            minDeviceMemoryInGb = 6,
            accelerators = "cpu"
        ),
        LocalModelInfo(
            name = "MobileActions-270M",
            modelId = "litert-community/functiongemma-270m-ft-mobile-actions",
            modelFile = "mobile_actions.litertlm",
            runtimeType = "litert-lm",
            description = "Fine-tuned Function Gemma 270M model for Mobile Actions.",
            url = "https://dl.google.com/google-ai-edge-gallery/android/mobile-actions/20260218/mobile_actions.litertlm",
            sizeInBytes = 288964608,
            minDeviceMemoryInGb = 6,
            accelerators = "cpu"
        )
    )

    fun getModelById(id: String): LocalModelInfo {
        return allowedModels.firstOrNull { it.modelId == id } ?: allowedModels[1]
    }
}
