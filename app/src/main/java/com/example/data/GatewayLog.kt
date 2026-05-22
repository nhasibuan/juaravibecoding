package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "gateway_logs")
data class GatewayLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val method: String,
    val path: String,
    val requestModel: String,
    val clientIp: String,
    val status: Int,
    val durationMs: Long,
    val responsePreview: String,
    val isAuthorized: Boolean
)
