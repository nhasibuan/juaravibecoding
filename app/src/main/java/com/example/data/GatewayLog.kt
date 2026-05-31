package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "gateway_logs")
data class GatewayLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val method: String? = null,
    val endpoint: String? = null,
    val requestSnippet: String? = null,
    val responseSnippet: String? = null,
    val statusCode: Int = 0,
    val latencyMs: Long = 0,
    val modelUsed: String? = null,
    val errorMessage: String? = null,
    val tokensCount: Int = 0
)
