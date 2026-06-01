package com.example.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.server.ProxyServerManager
import com.example.server.ServerStatus
import com.example.server.LogUtility
import com.example.server.GatewayForegroundService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class GatewayViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = GatewayRepository(AppDatabase.getDatabase(application))
    
    // Server state fields bound to ProxyServerManager
    val serverStatus: StateFlow<ServerStatus> = ProxyServerManager.status
    val activePort: StateFlow<Int> = ProxyServerManager.activePort
    val errorCount: StateFlow<Int> = ProxyServerManager.errorCount

    // Settings
    val settingsState: StateFlow<ProxySetting>

    // Logs filtering state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    val logsState: StateFlow<List<GatewayLog>>

    // Local model download state tracking
    private val _downloadProgresses = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadProgresses: StateFlow<Map<String, Int>> = _downloadProgresses

    private val _downloadedModels = MutableStateFlow<Set<String>>(emptySet())
    val downloadedModels: StateFlow<Set<String>> = _downloadedModels

    val persistedDownloadStates: StateFlow<List<ModelDownloadState>> = repository.downloadStatesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val downloadJobs = mutableMapOf<String, Job>()

    init {
        LogUtility.initialize(application)
        ProxyServerManager.initialize(repository, application)

        // Sync local downloaded models state based on physical file presence
        refreshDownloadedModels()

        // Sync download progresses with Room table (for WorkManager compatibility)
        viewModelScope.launch {
            repository.downloadStatesFlow.collect { states ->
                val progresses = states.associate { state ->
                    val progressValue = when (state.status) {
                        "DOWNLOADING" -> state.progress
                        "VERIFYING" -> 99
                        "INITIALIZING" -> 0
                        else -> -1
                    }
                    state.modelId to progressValue
                }.filter { it.value >= 0 }
                _downloadProgresses.update { progresses }
                refreshDownloadedModels()
            }
        }

        // Standard setup: start proxy on port defined in database (fallback to 8080)
        viewModelScope.launch {
            try {
                val s = repository.getSettings()
                GatewayForegroundService.startService(application, s.port)
            } catch (t: Throwable) {
                LogUtility.logError("GatewayViewModel", t)
                GatewayForegroundService.startService(application, 8080)
            }
        }

        settingsState = repository.settingsFlow
            .catch { t ->
                LogUtility.logError("GatewayViewModel_settingsFlow", t)
                emit(ProxySetting())
            }
            .map { it ?: ProxySetting() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProxySetting())

        // Combine logs with search queries to enable live filtering in the logger tab
        logsState = repository.allLogsFlow
            .catch { t ->
                LogUtility.logError("GatewayViewModel_allLogsFlow", t)
                emit(emptyList())
            }
            .combine(_searchQuery) { logs, query ->
                if (query.isBlank()) {
                    logs
                } else {
                    logs.filter {
                        (it.endpoint ?: "").contains(query, ignoreCase = true) ||
                        (it.modelUsed ?: "").contains(query, ignoreCase = true) ||
                        (it.requestSnippet ?: "").contains(query, ignoreCase = true) ||
                        (it.responseSnippet ?: "").contains(query, ignoreCase = true)
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    fun startServer() {
        viewModelScope.launch {
            try {
                val port = settingsState.value.port
                GatewayForegroundService.startService(getApplication(), port)
            } catch (t: Throwable) {
                Log.e("GatewayViewModel", "Failed to start server", t)
                GatewayForegroundService.startService(getApplication(), 8080)
            }
        }
    }

    fun stopServer() {
        GatewayForegroundService.stopService(getApplication())
    }

    fun updatePort(newPort: Int) {
        val sanitizedPort = if (newPort in 1024..65535) newPort else 8080
        viewModelScope.launch {
            try {
                val current = settingsState.value
                val next = current.copy(port = sanitizedPort)
                repository.updateSettings(next)
                GatewayForegroundService.stopService(getApplication())
                delay(200)
                GatewayForegroundService.startService(getApplication(), sanitizedPort)
            } catch (t: Throwable) {
                Log.e("GatewayViewModel", "Failed to update port", t)
            }
        }
    }

    fun updateApiKey(apiKey: String) {
        viewModelScope.launch {
            try {
                val current = settingsState.value
                val next = current.copy(geminiApiKey = apiKey.trim())
                repository.updateSettings(next)
            } catch (t: Throwable) {
                Log.e("GatewayViewModel", "Failed to update API key", t)
            }
        }
    }

    fun updateGatewayAuthToken(token: String) {
        viewModelScope.launch {
            try {
                val current = settingsState.value
                val sToken = token.trim()
                val nextToken = if (sToken.isEmpty()) {
                    "gateway_" + java.util.UUID.randomUUID().toString().replace("-", "")
                } else {
                    sToken
                }
                val next = current.copy(gatewayAuthToken = nextToken)
                repository.updateSettings(next)
            } catch (t: Throwable) {
                Log.e("GatewayViewModel", "Failed to update gateway Auth token", t)
            }
        }
    }

    fun updateExposeToLan(expose: Boolean) {
        viewModelScope.launch {
            try {
                val current = settingsState.value
                val next = current.copy(exposeToLan = expose)
                repository.updateSettings(next)
                // Restart server if running to apply host bind change
                if (serverStatus.value == ServerStatus.RUNNING) {
                    GatewayForegroundService.stopService(getApplication())
                    delay(250)
                    GatewayForegroundService.startService(getApplication(), current.port)
                }
            } catch (t: Throwable) {
                Log.e("GatewayViewModel", "Failed to update expose to LAN setting", t)
            }
        }
    }

    fun updateHardwareConfigs(npu: Boolean, bypassGpu: Boolean) {
        viewModelScope.launch {
            try {
                val current = settingsState.value
                val next = current.copy(enableNpuBackend = npu, bypassGpu = bypassGpu)
                repository.updateSettings(next)
            } catch (t: Throwable) {
                Log.e("GatewayViewModel", "Failed to update hardware configurations", t)
            }
        }
    }

    fun updatePreferredBackend(backend: String) {
        viewModelScope.launch {
            try {
                val current = settingsState.value
                val next = current.copy(preferredBackend = backend)
                repository.updateSettings(next)
            } catch (t: Throwable) {
                Log.e("GatewayViewModel", "Failed to update preferred backend", t)
            }
        }
    }

    fun updateQuery(query: String) {
        _searchQuery.value = query
    }

    fun clearLogs() {
        viewModelScope.launch {
            try {
                repository.clearLogs()
            } catch (t: Throwable) {
                Log.e("GatewayViewModel", "Failed to clear logs", t)
            }
        }
    }

    fun exportLogsToUri(context: Context, onSuccess: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val list = logsState.value
                val csv = StringBuilder("ID,Timestamp,Method,Endpoint,StatusCode,LatencyMs,ModelUsed,TokensCount,ErrorMessage\n")
                list.forEach { log ->
                    val timestampStr = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(log.timestamp))
                    val cleanedError = log.errorMessage?.replace(",", ";")?.replace("\n", " ") ?: ""
                    csv.append("${log.id},\"$timestampStr\",\"${log.method}\",\"${log.endpoint}\",${log.statusCode},${log.latencyMs},\"${log.modelUsed}\",${log.tokensCount},\"$cleanedError\"\n")
                }
                val outputFile = java.io.File(context.cacheDir, "gateway_logs_export.csv")
                outputFile.writeText(csv.toString())
                onSuccess(outputFile.absolutePath)
            } catch (t: Throwable) {
                Log.e("GatewayViewModel", "Error exporting database logs", t)
            }
        }
    }

    fun getModelFilename(modelId: String): String {
        return when (modelId) {
            "litert-community/gemma-4-E2B-it-litert-lm" -> "gemma4_2b_v09_obfus_fix_all_modalities_thinking.litertlm"
            "litert-community/gemma-4-E4B-it-litert-lm" -> "gemma4_4b_v09_obfus_fix_all_modalities_thinking.litertlm"
            "google/gemma-3n-E2B-it-litert-lm" -> "gemma-3n-E2B-it-int4.litertlm"
            "google/gemma-3n-E4B-it-litert-lm" -> "gemma-3n-E4B-it-int4.litertlm"
            "litert-community/Gemma3-1B-IT" -> "gemma3-1b-it-int4.litertlm"
            "litert-community/Qwen2.5-1.5B-Instruct" -> "qwen2.5-1.5b-instruct.litertlm"
            "litert-community/DeepSeek-R1-Distill-Qwen-1.5B" -> "deepseek-r1-distill-qwen-1.5b.litertlm"
            "litert-community/functiongemma-270m-ft-tiny-garden" -> "tinygarden.litertlm"
            "litert-community/functiongemma-270m-ft-mobile-actions" -> "mobile_actions.litertlm"
            else -> modelId.substringAfterLast("/").lowercase() + ".litertlm"
        }
    }

    fun refreshDownloadedModels() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val folder = getApplication<Application>().getExternalFilesDir(null)
                val downloaded = mutableSetOf<String>()
                if (folder != null) {
                    if (!folder.exists()) {
                        folder.mkdirs()
                    }
                    // Populate default demo/requested weights on first run so the requested files exist
                    if (com.example.BuildConfig.DEMO_MODE) {
                        val demoGemma3File = java.io.File(folder, "gemma3-1b-it-int4.litertlm")
                        if (!demoGemma3File.exists()) {
                            try {
                                demoGemma3File.writeText("Placeholder local weights for Gemma 3 1B IT")
                            } catch (e: Exception) {
                                android.util.Log.e("GatewayViewModel", "Failed to write placeholder Gemma 3", e)
                            }
                        }
                        if (demoGemma3File.exists()) {
                            try {
                                com.example.inference.ModelDownloadManager.markModelVerified(
                                    getApplication(),
                                    "litert-community/Gemma3-1B-IT",
                                    "placeholder-hash",
                                    demoGemma3File.length(),
                                    "VERIFIED"
                                )
                            } catch (e: Exception) {
                                android.util.Log.e("GatewayViewModel", "Failed to mark placeholder Gemma 3 verified", e)
                            }
                        }

                        val demoGemma4File = java.io.File(folder, "gemma4_2b_v09_obfus_fix_all_modalities_thinking.litertlm")
                        if (!demoGemma4File.exists()) {
                            try {
                                demoGemma4File.writeText("Placeholder local weights for Gemma 4 2B IT (Obfuscated Fix)")
                            } catch (e: Exception) {
                                android.util.Log.e("GatewayViewModel", "Failed to write placeholder Gemma 4", e)
                            }
                        }
                        if (demoGemma4File.exists()) {
                            try {
                                com.example.inference.ModelDownloadManager.markModelVerified(
                                    getApplication(),
                                    "litert-community/gemma-4-E2B-it-litert-lm",
                                    "placeholder-hash",
                                    demoGemma4File.length(),
                                    "VERIFIED"
                                )
                            } catch (e: Exception) {
                                android.util.Log.e("GatewayViewModel", "Failed to mark placeholder Gemma 4 verified", e)
                            }
                        }
                    }

                    // Read actually existing verified weight files
                    ModelsRegistry.localModels.forEach { model ->
                        if (com.example.inference.ModelDownloadManager.isModelDownloaded(getApplication(), model.id)) {
                            downloaded.add(model.id)
                        }
                    }
                }
                _downloadedModels.value = downloaded
            } catch (t: Throwable) {
                android.util.Log.e("GatewayViewModel", "Error refreshing downloaded models data", t)
            }
        }
    }

    fun triggerModelDownload(modelId: String) {
        viewModelScope.launch {
            try {
                val dbState = repository.getDownloadState(modelId)
                val isDownloading = dbState != null && (
                    dbState.status == "DOWNLOADING" || 
                    dbState.status == "INITIALIZING" || 
                    dbState.status == "VERIFYING"
                )

                if (isDownloading) {
                    // Cancel/Pause downloading
                    com.example.inference.ModelDownloadManager.requestCancel(modelId)
                    try {
                        androidx.work.WorkManager.getInstance(getApplication()).cancelUniqueWork("download_$modelId")
                    } catch (e: Exception) {
                        Log.e("GatewayViewModel", "Failed to cancel unique work download_$modelId", e)
                    }
                    repository.updateDownloadState(ModelDownloadState(modelId = modelId, progress = 0, status = "PAUSED"))
                    _downloadProgresses.update { it.toMutableMap().apply { remove(modelId) } }
                } else {
                    // Start downloading
                    _downloadProgresses.update { it.toMutableMap().apply { put(modelId, 0) } }
                    repository.updateDownloadState(ModelDownloadState(modelId = modelId, progress = 0, status = "INITIALIZING"))
                    
                    val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.example.inference.ModelDownloadWorker>()
                        .setInputData(androidx.work.workDataOf("modelId" to modelId))
                        .addTag("download_$modelId")
                        .build()
                    
                    androidx.work.WorkManager.getInstance(getApplication()).enqueueUniqueWork(
                        "download_$modelId",
                        androidx.work.ExistingWorkPolicy.REPLACE,
                        workRequest
                    )
                }
            } catch (e: Exception) {
                Log.e("GatewayViewModel", "Error in triggerModelDownload for $modelId", e)
            }
        }
    }

    fun deleteModelWeight(modelId: String) {
        com.example.inference.ModelDownloadManager.requestCancel(modelId)
        try {
            androidx.work.WorkManager.getInstance(getApplication()).cancelUniqueWork("download_$modelId")
        } catch (e: Exception) {
            Log.e("GatewayViewModel", "Failed to cancel work on delete for $modelId", e)
        }
        _downloadProgresses.update { it.toMutableMap().apply { remove(modelId) } }

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Delete download state from Room
                repository.deleteDownloadState(modelId)
                
                // Remove verification entry from manifest
                com.example.inference.ModelDownloadManager.removeModelVerification(getApplication(), modelId)

                // Remove actual physical file
                val folder = getApplication<Application>().getExternalFilesDir(null)
                if (folder != null) {
                    val filename = getModelFilename(modelId)
                    val targetFile = java.io.File(folder, filename)
                    if (targetFile.exists()) {
                        try {
                            targetFile.delete()
                        } catch (e: Exception) {
                            android.util.Log.e("GatewayViewModel", "Failed to delete weight file", e)
                        }
                    }
                }
                refreshDownloadedModels()
            } catch (t: Throwable) {
                android.util.Log.e("GatewayViewModel", "Error executing delete model weight routine", t)
            }
        }
    }


}
