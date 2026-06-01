package com.example.inference

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.data.AppDatabase
import com.example.data.GatewayRepository
import com.example.data.ModelDownloadState

class ModelDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val notificationId = 1002
    private val channelId = "model_downloader_channel"

    override suspend fun doWork(): Result {
        // Run as a foreground service if possible, to guarantee system resources and shield from OS killings
        try {
            setForeground(createForegroundInfo())
        } catch (e: Exception) {
            Log.w("ModelDownloadWorker", "Unable to run as foreground worker: ${e.localizedMessage}")
        }
        
        val modelId = inputData.getString("modelId")
        if (modelId == null) {
            Log.e("ModelDownloadWorker", "No modelId specified for download worker")
            return Result.failure()
        }
        
        val db = AppDatabase.getDatabase(applicationContext)
        val repository = GatewayRepository(db)

        Log.i("ModelDownloadWorker", "Starting background worker download for: $modelId")
        
        try {
            ModelDownloadManager.downloadModel(applicationContext, modelId).collect { state ->
                when (state) {
                    is DownloadState.Initializing -> {
                        repository.updateDownloadState(
                            ModelDownloadState(
                                modelId = modelId,
                                progress = 0,
                                status = "INITIALIZING"
                            )
                        )
                    }
                    is DownloadState.Progress -> {
                        repository.updateDownloadState(
                            ModelDownloadState(
                                modelId = modelId,
                                progress = state.percent,
                                status = "DOWNLOADING",
                                downloadedBytes = state.downloadedBytes,
                                totalBytes = state.totalBytes
                            )
                        )
                    }
                    is DownloadState.Verifying -> {
                        repository.updateDownloadState(
                            ModelDownloadState(
                                modelId = modelId,
                                progress = 99,
                                status = "VERIFYING"
                            )
                        )
                    }
                    is DownloadState.Completed -> {
                        repository.updateDownloadState(
                            ModelDownloadState(
                                modelId = modelId,
                                progress = 100,
                                status = "COMPLETED"
                            )
                        )
                    }
                    is DownloadState.Error -> {
                        Log.e("ModelDownloadWorker", "Download error in worker: ${state.message}")
                        repository.updateDownloadState(
                            ModelDownloadState(
                                modelId = modelId,
                                progress = 0,
                                status = "FAILED",
                                errorMessage = state.message
                            )
                        )
                    }
                }
            }
            return Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.i("ModelDownloadWorker", "Download worker cancelled for: $modelId")
            repository.updateDownloadState(
                ModelDownloadState(
                    modelId = modelId,
                    progress = 0,
                    status = "PAUSED"
                )
            )
            throw e
        } catch (e: Exception) {
            Log.e("ModelDownloadWorker", "Unhandled exception in download worker: ${e.localizedMessage}", e)
            repository.updateDownloadState(
                ModelDownloadState(
                    modelId = modelId,
                    progress = 0,
                    status = "FAILED",
                    errorMessage = e.localizedMessage
                )
            )
            return Result.failure()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Model Downloader"
            val desc = "Background model downloading service"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = desc
            }
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val title = "Downloading Intelligence Model"
        val desc = "Retrieving optimized local LLM kernels safely in the background..."
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(title)
            .setContentText(desc)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

        return ForegroundInfo(notificationId, notification)
    }
}
