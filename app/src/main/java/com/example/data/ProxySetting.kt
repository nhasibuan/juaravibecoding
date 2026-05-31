package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "proxy_settings")
data class ProxySetting(
    @PrimaryKey val id: Int = 1,
    val port: Int = 8080,
    val geminiApiKey: String = "",
    val enableNpuBackend: Boolean = false,
    val bypassGpu: Boolean = true,
    val gatewayAuthToken: String = "",
    val preferredBackend: String = "AUTO"
)
