package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.GatewayLog
import com.example.data.GatewayRepository
import com.example.data.ModelsRegistry
import com.example.data.ProxySetting
import com.example.server.ProxyServerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GatewayViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val repository = GatewayRepository(
        database.proxySettingDao(),
        database.gatewayLogDao()
    )

    private val serverManager = ProxyServerManager(application, repository)

    // Reactive states from Room
    val settingsState: StateFlow<ProxySetting?> = repository.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val logsState: StateFlow<List<GatewayLog>> = repository.latestLogsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Server-specific properties linked dynamically
    val isServerRunning = serverManager.isServerRunning
    val serverPort = serverManager.serverPort

    private val _serverIp = MutableStateFlow(serverManager.getLocalIp())
    val serverIp = _serverIp.asStateFlow()

    // Temporary user input inputs to allow typing before clicking apply
    val portInput = MutableStateFlow("8080")
    val apiKeyInput = MutableStateFlow("")

    init {
        viewModelScope.launch {
            // Read initial database state and synchronize matching fields
            val dbSettings = repository.getSettingsDirect() ?: ProxySetting()
            if (repository.getSettingsDirect() == null) {
                repository.updateSettings(dbSettings)
            }

            portInput.value = dbSettings.port.toString()
            apiKeyInput.value = dbSettings.proxyApiKey

            // Auto-start server with saved configs as a useful onboarding feature
            serverManager.startServer(dbSettings.port)
        }
    }

    fun refreshIp() {
        _serverIp.value = serverManager.getLocalIp()
    }

    fun toggleServer() {
        viewModelScope.launch {
            if (isServerRunning.value) {
                serverManager.stopServer()
            } else {
                val currentPort = portInput.value.toIntOrNull() ?: 8080
                // Update DB first
                val currentSettings = repository.getSettingsDirect() ?: ProxySetting()
                val updated = currentSettings.copy(port = currentPort)
                repository.updateSettings(updated)
                
                serverManager.startServer(currentPort)
            }
        }
    }

    fun applySettings(portText: String, apiKeyText: String, activeModelId: String, provider: String) {
        viewModelScope.launch {
            val validatedPort = portText.toIntOrNull() ?: 8080
            val wasRunning = isServerRunning.value

            // 1. If running and port changes, reboot server
            if (wasRunning && validatedPort != serverPort.value) {
                serverManager.stopServer()
            }

            // 2. Persist to Room
            val currentSettings = repository.getSettingsDirect() ?: ProxySetting()
            val updated = currentSettings.copy(
                port = validatedPort,
                proxyApiKey = apiKeyText.trim(),
                activeModelId = activeModelId,
                targetProvider = provider
            )
            repository.updateSettings(updated)

            // 3. Restart server on new port if it was running or start it up
            if (wasRunning && validatedPort != serverPort.value) {
                serverManager.startServer(validatedPort)
            }
        }
    }

    fun changeActiveModel(modelId: String) {
        viewModelScope.launch {
            val currentSettings = repository.getSettingsDirect() ?: ProxySetting()
            val updated = currentSettings.copy(activeModelId = modelId)
            repository.updateSettings(updated)
        }
    }

    fun changeProvider(provider: String) {
        viewModelScope.launch {
            val currentSettings = repository.getSettingsDirect() ?: ProxySetting()
            val updated = currentSettings.copy(targetProvider = provider)
            repository.updateSettings(updated)
        }
    }

    fun clearLogHistory() {
        viewModelScope.launch {
            repository.clearLogs()
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Stop server thread loop safely to prevent leaks
        serverManager.stopServer()
    }
}
