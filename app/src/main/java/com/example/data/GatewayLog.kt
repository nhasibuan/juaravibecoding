package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "gateway_logs")
data class GatewayLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val method: String,
    val endpoint: String,
    val requestSnippet: String = "",
    val responseSnippet: String = "",
    val statusCode: Int,
    val latencyMs: Long,
    val modelUsed: String,
    val errorMessage: String? = null
)
