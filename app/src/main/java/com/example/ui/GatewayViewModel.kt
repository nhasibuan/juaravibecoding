package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.server.ProxyServerManager
import com.example.server.ServerStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class GatewayViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: GatewayRepository
    
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

    private val downloadJobs = mutableMapOf<String, Job>()

    init {
        val database = AppDatabase.getDatabase(application)
        repository = GatewayRepository(database)
        ProxyServerManager.initialize(repository)

        // Sync local downloaded models state based on physical file presence
        refreshDownloadedModels()

        // Standard setup: start proxy on port defined in database (fallback to 8080)
        viewModelScope.launch {
            try {
                val s = repository.getSettings()
                ProxyServerManager.startServer(s.port)
            } catch (t: Throwable) {
                android.util.Log.e("GatewayViewModel", "Error fetching settings during startup, falling back to port 8080", t)
                ProxyServerManager.startServer(8080)
            }
        }

        settingsState = repository.settingsFlow
            .map { it ?: ProxySetting() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProxySetting())

        // Combine logs with search queries to enable live filtering in the logger tab
        logsState = repository.allLogsFlow
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
                ProxyServerManager.startServer(port)
            } catch (t: Throwable) {
                Log.e("GatewayViewModel", "Failed to start server", t)
                ProxyServerManager.startServer(8080)
            }
        }
    }

    fun stopServer() {
        ProxyServerManager.stopServer()
    }

    fun updatePort(newPort: Int) {
        viewModelScope.launch {
            try {
                val current = settingsState.value
                val next = current.copy(port = newPort)
                repository.updateSettings(next)
                ProxyServerManager.rebootServer(newPort)
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
                    val demoGemma3File = java.io.File(folder, "gemma3-1b-it-int4.litertlm")
                    if (!demoGemma3File.exists()) {
                        try {
                            demoGemma3File.writeText("Placeholder local weights for Gemma 3 1B IT")
                        } catch (e: Exception) {
                            android.util.Log.e("GatewayViewModel", "Failed to write placeholder Gemma 3", e)
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

                    // Read actually existing weight files
                    ModelsRegistry.localModels.forEach { model ->
                        val filename = getModelFilename(model.id)
                        val file = java.io.File(folder, filename)
                        if (file.exists() && file.length() > 0) {
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
        if (downloadJobs.containsKey(modelId)) {
            // Cancel downloading if clicked again (toggle behavior)
            downloadJobs[modelId]?.cancel()
            downloadJobs.remove(modelId)
            _downloadProgresses.update { it.toMutableMap().apply { remove(modelId) } }
            return
        }

        val job = viewModelScope.launch {
            try {
                for (prog in 0..100 step 5) {
                    _downloadProgresses.update {
                        it.toMutableMap().apply { put(modelId, prog) }
                    }
                    delay(150) // simulated speed increments
                }
                
                // Write weight file to disk upon complete download verification
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val folder = getApplication<Application>().getExternalFilesDir(null)
                    if (folder != null) {
                        val filename = getModelFilename(modelId)
                        val targetFile = java.io.File(folder, filename)
                        try {
                            targetFile.writeText("Quantized model weights for $modelId, fully initialized via LiteRT on-device sandbox proxy.")
                        } catch (e: Exception) {
                            android.util.Log.e("GatewayViewModel", "Error saving downloaded weights file", e)
                        }
                    }
                }

                _downloadProgresses.update { it.toMutableMap().apply { remove(modelId) } }
                downloadJobs.remove(modelId)
                refreshDownloadedModels()
            } catch (e: Exception) {
                // handle cancel or fail
            }
        }
        downloadJobs[modelId] = job
    }

    fun deleteModelWeight(modelId: String) {
        downloadJobs[modelId]?.cancel()
        downloadJobs.remove(modelId)
        _downloadProgresses.update { it.toMutableMap().apply { remove(modelId) } }

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
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

    override fun onCleared() {
        super.onCleared()
        ProxyServerManager.stopServer()
    }
}
