package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "proxy_settings")
data class ProxySetting(
    @PrimaryKey val id: Int = 1, // Only 1 settings row
    val port: Int = 8080,
    val proxyApiKey: String = "",
    val activeModelId: String = "litert-community/gemma-4-E2B-it-litert-lm",
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
