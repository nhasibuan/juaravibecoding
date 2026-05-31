package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "model_download_states")
data class ModelDownloadState(
    @PrimaryKey val modelId: String,
    val progress: Int = 0,
    val status: String = "NOT_STARTED", // "NOT_STARTED", "DOWNLOADING", "PAUSED", "VERIFYING", "COMPLETED", "FAILED"
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val errorMessage: String? = null
)
