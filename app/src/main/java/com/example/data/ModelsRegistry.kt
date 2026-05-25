package com.example.data

// LiteRT-LM SDK API structures for default model configuration
data class EngineConfig(
    val modelPath: String,
    val backend: Any? = null
)

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
    val accelerators: String = "cpu,gpu",
    val cloudUpstreamId: String? = null,
    val openAiAliases: List<String> = emptyList(),
    val experimental: Boolean = false,
    val preferredBackend: String = "cpu"
) {
    val targetFilePath: String
        get() {
            if (runtimeType != "litert-lm") return "system-managed"
            if (modelId == "litert-community/gemma-4-E2B-it-litert-lm") {
                return "/sdcard/Android/data/com.google.ai.edge.gallery/files/Gemma_4_E2B_it/20260325/gemma4_2b_v09_obfus_fix_all_modalities_thinking.litertlm"
            }
            if (modelId == "litert-community/gemma-4-E4B-it-litert-lm") {
                return "/sdcard/Android/data/com.google.ai.edge.gallery/files/Gemma_4_E4B_it/20260325/gemma4_4b_v09_obfus_fix_all_modalities_thinking.litertlm"
            }
            val uriStr = url
            if (uriStr.isNotEmpty()) {
                val parts = uriStr.split("/android/")
                if (parts.size > 1) {
                    return "/sdcard/Android/data/com.google.ai.edge.gallery/files/" + parts[1]
                }
            }
            return "/sdcard/Android/data/com.google.ai.edge.gallery/files/$modelFile"
        }

    fun getResolvedTargetFile(context: android.content.Context, forWriting: Boolean = false): java.io.File {
        if (runtimeType != "litert-lm") return java.io.File("system-managed")
        
        val extDir = context.getExternalFilesDir(null)
        val subPath = when {
            modelId == "litert-community/gemma-4-E2B-it-litert-lm" -> "Gemma_4_E2B_it/20260325/gemma4_2b_v09_obfus_fix_all_modalities_thinking.litertlm"
            modelId == "litert-community/gemma-4-E4B-it-litert-lm" -> "Gemma_4_E4B_it/20260325/gemma4_4b_v09_obfus_fix_all_modalities_thinking.litertlm"
            url.isNotEmpty() && url.contains("/android/") -> {
                val parts = url.split("/android/")
                if (parts.size > 1) parts[1] else modelFile
            }
            else -> modelFile
        }
        val localFile = if (extDir != null) java.io.File(extDir, subPath) else java.io.File(context.filesDir, modelFile)

        if (forWriting) {
            return localFile
        }

        val existsOption = try { localFile.exists() && localFile.length() > 0 } catch (tf: Throwable) { false }
        if (existsOption) {
            return localFile
        }

        val preferredPath = targetFilePath
        val preferredFile = java.io.File(preferredPath)
        try {
            if (preferredFile.exists() && preferredFile.canRead()) {
                return preferredFile
            }
        } catch (e: Throwable) {
            // SecurityException
        }

        return localFile
    }
}

object ModelsRegistry {
    const val DEFAULT_LITERT_MODEL_PATH = "/sdcard/Android/data/com.google.ai.edge.gallery/files/Gemma_4_E2B_it/20260325/gemma4_2b_v09_obfus_fix_all_modalities_thinking.litertlm"

    val defaultEngineConfig = EngineConfig(modelPath = DEFAULT_LITERT_MODEL_PATH)

    val allowedModels = listOf(
        // Promoting Cloud Models to standard registry entries first
        LocalModelInfo(
            name = "Gemini 2.5 Flash",
            modelId = "gemini-2.5-flash",
            modelFile = "system-managed",
            runtimeType = "cloud",
            cloudUpstreamId = "gemini-2.5-flash",
            openAiAliases = listOf("gpt-4o-mini", "gpt-3.5-turbo"),
            description = "Google Gemini 2.5 Flash model hosted on Google Cloud API, recommended for swift cloud-based text and code generation.",
            accelerators = "cloud"
        ),
        LocalModelInfo(
            name = "Gemini 2.5 Pro",
            modelId = "gemini-2.5-pro",
            modelFile = "system-managed",
            runtimeType = "cloud",
            cloudUpstreamId = "gemini-2.5-pro",
            description = "Google Gemini 2.5 Pro model hosted on Google Cloud API, recommended for hyper-complex analytical and multi-step reasoning tasks.",
            accelerators = "cloud"
        ),
        LocalModelInfo(
            name = "Gemini 1.5 Flash",
            modelId = "gemini-1.5-flash",
            modelFile = "system-managed",
            runtimeType = "cloud",
            cloudUpstreamId = "gemini-1.5-flash",
            description = "Google Gemini 1.5 Flash model hosted on Google Cloud API.",
            accelerators = "cloud"
        ),
        LocalModelInfo(
            name = "Gemini 1.5 Pro",
            modelId = "gemini-1.5-pro",
            modelFile = "system-managed",
            runtimeType = "cloud",
            cloudUpstreamId = "gemini-1.5-pro",
            description = "Google Gemini 1.5 Pro model hosted on Google Cloud API.",
            accelerators = "cloud"
        ),

        // AICore Models
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

        // LiteRT-LM Local Models
        LocalModelInfo(
            name = "Gemma-4-E2B-it",
            modelId = "litert-community/gemma-4-E2B-it-litert-lm",
            modelFile = "gemma4_2b_v09_obfus_fix_all_modalities_thinking.litertlm",
            runtimeType = "litert-lm",
            description = "A variant of Gemma 4 E2B ready for deployment on Android using LiteRT-LM. It supports multi-modality input, with up to 32K context length.",
            url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/blob/main/gemma-4-E2B-it.litertlm",
            sizeInBytes = 2538766336,
            minDeviceMemoryInGb = 8,
            llmSupportThinking = true,
            llmSupportImage = true,
            llmSupportAudio = true,
            accelerators = "gpu,cpu",
            preferredBackend = "gpu"
        ),
        LocalModelInfo(
            name = "Gemma-4-E4B-it",
            modelId = "litert-community/gemma-4-E4B-it-litert-lm",
            modelFile = "gemma4_4b_v09_obfus_fix_all_modalities_thinking.litertlm",
            runtimeType = "litert-lm",
            description = "A variant of Gemma 4 E4B ready for deployment on Android using LiteRT-LM. It supports multi-modality input, with up to 32K context length.",
            url = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            sizeInBytes = 3609411584,
            minDeviceMemoryInGb = 12,
            llmSupportThinking = true,
            llmSupportImage = true,
            llmSupportAudio = true,
            accelerators = "gpu,cpu",
            preferredBackend = "gpu"
        ),
        LocalModelInfo(
            name = "Gemma-3n-E2B-it",
            modelId = "google/gemma-3n-E2B-it-litert-lm",
            modelFile = "gemma-3n-E2B-it-int4.litertlm",
            runtimeType = "litert-lm",
            description = "A variant of Gemma 3n E2B ready for deployment on Android using LiteRT-LM. It supports text, vision, and audio input, with 4096 context length.",
            url = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/blob/main/gemma-3n-E2B-it-int4.litertlm",
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
            url = "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm/resolve/main/gemma-3n-E4B-it-int4.litertlm",
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
            url = "https://huggingface.co/litert-community/Gemma3-1B-IT/blob/main/gemma3-1b-it-int4.litertlm",
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
            url = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct-litert-lm/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
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
            url = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B-litert-lm/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
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
            url = "https://huggingface.co/litert-community/functiongemma-270m-ft-tiny-garden-litert-lm/resolve/main/tiny_garden.litertlm",
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
            url = "https://huggingface.co/litert-community/functiongemma-270m-ft-mobile-actions-litert-lm/resolve/main/mobile_actions.litertlm",
            sizeInBytes = 288964608,
            minDeviceMemoryInGb = 6,
            accelerators = "cpu"
        )
    )

    fun findStrict(id: String): LocalModelInfo? {
        val lowerId = id.trim().lowercase()
        return allowedModels.firstOrNull { 
            it.modelId.lowercase() == lowerId || 
            it.openAiAliases.any { alias -> alias.lowercase() == lowerId }
        }
    }

    @Deprecated("Use findStrict instead", ReplaceWith("findStrict(id)"))
    fun getModelById(id: String): LocalModelInfo {
        return findStrict(id) ?: allowedModels[4] // falls back to standard allowed gemma-4 local
    }
}
