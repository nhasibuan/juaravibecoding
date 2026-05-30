package com.example.ui

import android.app.Application
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

    private val _downloadedModels = MutableStateFlow<Set<String>>(setOf("gemma-2b-it")) // Gemma downloaded on first run by default for demo
    val downloadedModels: StateFlow<Set<String>> = _downloadedModels

    private val downloadJobs = mutableMapOf<String, Job>()

    init {
        val database = AppDatabase.getDatabase(application)
        repository = GatewayRepository(database)
        ProxyServerManager.initialize(repository)

        // Standard setup: start proxy on port defined in database (fallback to 8080)
        viewModelScope.launch {
            val s = repository.getSettings()
            ProxyServerManager.startServer(s.port)
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
                        it.endpoint.contains(query, ignoreCase = true) ||
                        it.modelUsed.contains(query, ignoreCase = true) ||
                        it.requestSnippet.contains(query, ignoreCase = true) ||
                        it.responseSnippet.contains(query, ignoreCase = true)
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    fun startServer() {
        viewModelScope.launch {
            val port = settingsState.value.port
            ProxyServerManager.startServer(port)
        }
    }

    fun stopServer() {
        ProxyServerManager.stopServer()
    }

    fun updatePort(newPort: Int) {
        viewModelScope.launch {
            val current = settingsState.value
            val next = current.copy(port = newPort)
            repository.updateSettings(next)
            ProxyServerManager.rebootServer(newPort)
        }
    }

    fun updateApiKey(apiKey: String) {
        viewModelScope.launch {
            val current = settingsState.value
            val next = current.copy(geminiApiKey = apiKey.trim())
            repository.updateSettings(next)
        }
    }

    fun updateHardwareConfigs(npu: Boolean, bypassGpu: Boolean) {
        viewModelScope.launch {
            val current = settingsState.value
            val next = current.copy(enableNpuBackend = npu, bypassGpu = bypassGpu)
            repository.updateSettings(next)
        }
    }

    fun updateQuery(query: String) {
        _searchQuery.value = query
    }

    fun clearLogs() {
        viewModelScope.launch {
            repository.clearLogs()
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
                    delay(300) // simulated speed increments
                }
                // complete
                _downloadedModels.update { it + modelId }
                _downloadProgresses.update { it.toMutableMap().apply { remove(modelId) } }
                downloadJobs.remove(modelId)
            } catch (e: Exception) {
                // handle cancel or fail
            }
        }
        downloadJobs[modelId] = job
    }

    fun deleteModelWeight(modelId: String) {
        _downloadedModels.update { it - modelId }
        _downloadProgresses.update { it.toMutableMap().apply { remove(modelId) } }
        downloadJobs[modelId]?.cancel()
        downloadJobs.remove(modelId)
    }

    override fun onCleared() {
        super.onCleared()
        ProxyServerManager.stopServer()
    }
}
