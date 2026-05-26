package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "proxy_settings")
data class ProxySetting(
    @PrimaryKey val id: Int = 1, // Only 1 settings row
    val port: Int = 8080,
    val proxyApiKey: String = "",
    val activeModelId: String = "litert-community/gemma-4-E2B-it-litert-lm",
    val targetProvider: String = "CLOUD_GEMINI", // "CLOUD_GEMINI", "LOCAL_VAL"
    val geminiApiKey: String = "",
    val bypassGpu: Boolean = false,
    val enableNpuBackend: Boolean = false
)
