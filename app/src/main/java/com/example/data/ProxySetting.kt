package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "proxy_settings")
data class ProxySetting(
    @PrimaryKey val id: Int = 1, // Only 1 settings row
    val port: Int = 8080,
    val proxyApiKey: String = "",
    val activeModelId: String = "litert-community/gemma-4-E2B-it-litert-lm",
    /**
     * Informational/UI default only as of plan.md §11 PR #8. Routing no
     * longer consults this field — each registered model picks its own
     * runtime via [LocalModelInfo.runtimeType], and any registered id is
     * dispatchable so long as its prerequisite resource (cloud key or
     * on-disk weights) is present. Kept on the entity so the UI can
     * remember the user's preferred default selection across sessions.
     */
    val targetProvider: String = "CLOUD_GEMINI", // "CLOUD_GEMINI" or "LOCAL_VAL"
    val geminiApiKey: String = "",
    /**
     * Opt-in flag for the LiteRT-LM NPU backend (added in v3 schema).
     *
     * NPU requires the app's `nativeLibraryDir` plus a vendor-shipped plug-in
     * that isn't reliably available across devices. When this is `false`
     * (the default) `LiteRtLmEngine.pickBackend` silently downgrades NPU
     * accelerators to CPU. Flipping it on lets the engine actually try
     * `Backend.NPU(...)`; if the device lacks the plug-in, the engine
     * surfaces a clean `engine_load_failed` error instead of crashing.
     */
    val enableNpuBackend: Boolean = false
)
