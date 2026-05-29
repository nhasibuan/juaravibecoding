package com.example.data

/**
 * The runtime that a [LocalModelInfo] dispatches to. The router (`ModelRouter`)
 * uses this to decide which subsystem services a request, and the `/v1/models`
 * endpoint uses it to compute availability.
 */
enum class RuntimeType {
    /** Google Gemini cloud REST API via OkHttp. */
    CLOUD,

    /** On-device LiteRT-LM Kotlin SDK (`com.google.ai.edge.litertlm:litertlm-android`). */
    LITERT_LM,

    /** Android AICore (Gemini Nano) — registered but not yet implemented. */
    AICORE
}

data class LocalModelInfo(
    val name: String,
    val modelId: String,
    val modelFile: String,
    val runtimeType: RuntimeType,
    val description: String,
    val url: String = "",
    val sizeInBytes: Long = 0,
    val minDeviceMemoryInGb: Int = 0,
    val llmSupportThinking: Boolean = false,
    val llmSupportImage: Boolean = false,
    val llmSupportAudio: Boolean = false,
    val accelerators: String = "cpu,gpu",

    // --- Routing fields (added in PR #2 — Workstream B) ---

    /**
     * For [RuntimeType.CLOUD] only: the upstream Gemini model id used when
     * building the `generateContent` URL. Lets `/v1/models` advertise an id
     * that maps 1:1 to a real cloud target instead of being coerced by a
     * substring heuristic.
     */
    val cloudUpstreamId: String? = null,

    /**
     * Optional list of alternative client-facing ids that should resolve to
     * this entry. Useful for accepting common OpenAI ids without re-routing
     * them to "unknown model". Keys are matched verbatim.
     */
    val openAiAliases: List<String> = emptyList(),

    /** Surfaced in `/v1/models` as `x_experimental: true`. UI may show a badge. */
    val experimental: Boolean = false
) {
    val targetFilePath: String
        get() {
            if (runtimeType != RuntimeType.LITERT_LM) return "system-managed"
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

    fun getResolvedTargetFile(context: android.content.Context): java.io.File {
        if (runtimeType != RuntimeType.LITERT_LM) return java.io.File("system-managed")

        val preferredPath = targetFilePath
        val preferredFile = java.io.File(preferredPath)
        try {
            // Check if the preferred path is available and readable to the app
            if (preferredFile.exists() && preferredFile.canRead()) {
                return preferredFile
            }
        } catch (e: Throwable) {
            // SecurityException due to Scoped Storage on API 30+
        }

        // Fallback to our own app's files directory which is always accessible and writable
        val extDir = context.getExternalFilesDir(null)
        if (extDir != null) {
            val subPath = when {
                modelId == "litert-community/gemma-4-E2B-it-litert-lm" -> "Gemma_4_E2B_it/20260325/gemma4_2b_v09_obfus_fix_all_modalities_thinking.litertlm"
                modelId == "litert-community/gemma-4-E4B-it-litert-lm" -> "Gemma_4_E4B_it/20260325/gemma4_4b_v09_obfus_fix_all_modalities_thinking.litertlm"
                url.isNotEmpty() && url.contains("/android/") -> {
                    val parts = url.split("/android/")
                    if (parts.size > 1) parts[1] else modelFile
                }
                else -> modelFile
            }
            return java.io.File(extDir, subPath)
        }
        return java.io.File(context.filesDir, modelFile)
    }
}

object ModelsRegistry {

    val allowedModels = listOf(
        // ----- Cloud Gemini (CLOUD) -----------------------------------------
        // These are advertised in /v1/models AND honored by dispatch — the
        // substring-`pro` heuristic in ProxyServerManager is now gone. Each
        // client-facing id maps 1:1 to `cloudUpstreamId`.
        LocalModelInfo(
            name = "Gemini 2.5 Flash",
            modelId = "gemini-2.5-flash",
            modelFile = "system-managed",
            runtimeType = RuntimeType.CLOUD,
            description = "Google Gemini 2.5 Flash via the Generative Language API. Default cloud target.",
            cloudUpstreamId = "gemini-2.5-flash",
            // Common OpenAI ids that clients tend to send by default — alias
            // them to Flash so existing integrations don't break with PR #2's
            // strict routing.
            openAiAliases = listOf("gpt-4o-mini", "gpt-3.5-turbo")
        ),
        LocalModelInfo(
            name = "Gemini 2.5 Pro",
            modelId = "gemini-2.5-pro",
            modelFile = "system-managed",
            runtimeType = RuntimeType.CLOUD,
            description = "Google Gemini 2.5 Pro via the Generative Language API.",
            cloudUpstreamId = "gemini-2.5-pro"
        ),
        LocalModelInfo(
            name = "Gemini 1.5 Flash",
            modelId = "gemini-1.5-flash",
            modelFile = "system-managed",
            runtimeType = RuntimeType.CLOUD,
            description = "Google Gemini 1.5 Flash via the Generative Language API.",
            cloudUpstreamId = "gemini-1.5-flash"
        ),
        LocalModelInfo(
            name = "Gemini 1.5 Pro",
            modelId = "gemini-1.5-pro",
            modelFile = "system-managed",
            runtimeType = RuntimeType.CLOUD,
            description = "Google Gemini 1.5 Pro via the Generative Language API.",
            cloudUpstreamId = "gemini-1.5-pro"
        ),

        // ----- AICore (Gemini Nano) — registered, not yet wired -------------
        LocalModelInfo(
            name = "Gemma 4 E2B (Gemini Nano via AICore)",
            modelId = "aicore-gemma-4-e2b",
            modelFile = "system-managed",
            runtimeType = RuntimeType.AICORE,
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
            runtimeType = RuntimeType.AICORE,
            description = "Gemini Nano available using Android AICore, optimized for your device. The recommended path for production applications.",
            sizeInBytes = 0,
            minDeviceMemoryInGb = 6,
            llmSupportImage = true,
            accelerators = "npu"
        ),

        // ----- LiteRT-LM (LITERT_LM) ----------------------------------------
        LocalModelInfo(
            name = "Gemma-4-E2B-it",
            modelId = "litert-community/gemma-4-E2B-it-litert-lm",
            modelFile = "gemma4_2b_v09_obfus_fix_all_modalities_thinking.litertlm",
            runtimeType = RuntimeType.LITERT_LM,
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
            name = "Gemma-4-E4B-it",
            modelId = "litert-community/gemma-4-E4B-it-litert-lm",
            modelFile = "gemma4_4b_v09_obfus_fix_all_modalities_thinking.litertlm",
            runtimeType = RuntimeType.LITERT_LM,
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
            runtimeType = RuntimeType.LITERT_LM,
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
            runtimeType = RuntimeType.LITERT_LM,
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
            runtimeType = RuntimeType.LITERT_LM,
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
            runtimeType = RuntimeType.LITERT_LM,
            description = "A variant of Qwen/Qwen2.5-1.5B-Instruct ready for deployment on Android using LiteRT-LM.",
            url = "https://dl.google.com/google-ai-edge-gallery/android/qwen2.5/20260210/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeInBytes = 1597931520,
            minDeviceMemoryInGb = 6,
            accelerators = "gpu,cpu"
        ),
        LocalModelInfo(
            name = "DeepSeek-R1-Distill-Qwen-1.5B",
            modelId = "litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
            modelFile = "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
            runtimeType = RuntimeType.LITERT_LM,
            description = "A variant of deepseek-ai/DeepSeek-R1-Distill-Qwen-1.5B ready for deployment on Android using LiteRT-LM with high-fidelity reasoning paths.",
            url = "https://dl.google.com/google-ai-edge-gallery/android/deepseek/20260220/DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
            sizeInBytes = 1833451520,
            minDeviceMemoryInGb = 6,
            llmSupportThinking = true,
            accelerators = "gpu,cpu"
        ),
        LocalModelInfo(
            name = "TinyGarden-270M",
            modelId = "litert-community/functiongemma-270m-ft-tiny-garden",
            modelFile = "tiny_garden.litertlm",
            runtimeType = RuntimeType.LITERT_LM,
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
            runtimeType = RuntimeType.LITERT_LM,
            description = "Fine-tuned Function Gemma 270M model for Mobile Actions.",
            url = "https://dl.google.com/google-ai-edge-gallery/android/mobile-actions/20260218/mobile_actions.litertlm",
            sizeInBytes = 288964608,
            minDeviceMemoryInGb = 6,
            accelerators = "cpu"
        )
    )

    /**
     * Resolves a client-facing model id to its registry entry, honoring
     * [LocalModelInfo.openAiAliases] but never silently substituting a
     * different model. Returns null when the id is unknown — callers turn
     * that into a `400 model_not_found`.
     */
    fun findStrict(id: String): LocalModelInfo? {
        if (id.isEmpty()) return null
        // Primary: exact match on modelId.
        val direct = allowedModels.firstOrNull { it.modelId == id }
        if (direct != null) return direct
        // Secondary: alias match. Aliases are matched verbatim (case-sensitive)
        // to keep behavior predictable.
        return allowedModels.firstOrNull { id in it.openAiAliases }
    }

    /**
     * Legacy lookup. Kept so any code outside [ProxyServerManager] that still
     * imports it surfaces a deprecation warning, and *throws* on unknown ids
     * instead of silently returning `allowedModels[1]` — that silent fallback
     * was a major source of the routing incoherence (see plan.md §1.2).
     *
     * New code MUST call [findStrict] and handle null explicitly, or go
     * through `ModelRouter.resolve(...)`.
     */
    @Deprecated(
        message = "Silent fallbacks are gone. Use findStrict(id) and handle null, or route via ModelRouter.resolve(...).",
        replaceWith = ReplaceWith("findStrict(id)")
    )
    fun getModelById(id: String): LocalModelInfo {
        return findStrict(id)
            ?: throw NoSuchElementException(
                "Model id '$id' is not in ModelsRegistry.allowedModels. " +
                        "Use findStrict(id) to handle unknown ids, or call ModelRouter.resolve(...)."
            )
    }
}
