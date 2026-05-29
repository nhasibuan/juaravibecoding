package com.example.ui

import android.app.Application
import android.util.Log
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GatewayViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val repository = GatewayRepository(
        database.proxySettingDao(),
        database.gatewayLogDao()
    )

    private val serverManager = ProxyServerManager(application, repository)

    // Reactive states from Room
    val settingsState: StateFlow<ProxySetting?> = repository.settingsFlow
        .catch { e -> Log.e("GatewayViewModel", "Error in settingsFlow", e) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val logsState: StateFlow<List<GatewayLog>> = repository.latestLogsFlow
        .catch { e -> Log.e("GatewayViewModel", "Error in latestLogsFlow", e) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Server-specific properties linked dynamically
    val isServerRunning = serverManager.isServerRunning
    val serverPort = serverManager.serverPort

    private val _serverIp = MutableStateFlow("127.0.0.1")
    val serverIp = _serverIp.asStateFlow()

    // Temporary user input inputs to allow typing before clicking apply
    val portInput = MutableStateFlow("8080")
    val apiKeyInput = MutableStateFlow("")
    val geminiApiKeyInput = MutableStateFlow("")

    init {
        viewModelScope.launch {
            try {
                // Read initial database state off the main thread
                val dbSettings = withContext(Dispatchers.IO) {
                    repository.getSettingsDirect() ?: ProxySetting().also {
                        repository.updateSettings(it)
                    }
                }

                portInput.value = dbSettings.port.toString()
                apiKeyInput.value = dbSettings.proxyApiKey
                geminiApiKeyInput.value = dbSettings.geminiApiKey

                // Update IP address on a background thread safely
                val localIp = withContext(Dispatchers.IO) {
                    serverManager.getLocalIp()
                }
                _serverIp.value = localIp

                // Auto-start server with saved configs as a useful onboarding feature (offloaded)
                withContext(Dispatchers.IO) {
                    serverManager.startServer(dbSettings.port)
                }
            } catch (e: Throwable) {
                Log.e("GatewayViewModel", "Error initializing GatewayViewModel settings and auto-start", e)
            }
        }
    }

    fun refreshIp() {
        viewModelScope.launch {
            try {
                _serverIp.value = withContext(Dispatchers.IO) {
                    serverManager.getLocalIp()
                }
            } catch (e: Throwable) {
                Log.e("GatewayViewModel", "Failed to refresh IP address", e)
            }
        }
    }

    fun toggleServer() {
        viewModelScope.launch {
            try {
                if (isServerRunning.value) {
                    withContext(Dispatchers.IO) {
                        serverManager.stopServer()
                    }
                } else {
                    val currentPort = portInput.value.toIntOrNull() ?: 8080
                    withContext(Dispatchers.IO) {
                        val currentSettings = repository.getSettingsDirect() ?: ProxySetting()
                        val updated = currentSettings.copy(port = currentPort)
                        repository.updateSettings(updated)
                        serverManager.startServer(currentPort)
                    }
                }
            } catch (e: Throwable) {
                Log.e("GatewayViewModel", "Error toggling server status", e)
            }
        }
    }

    fun applySettings(
        portText: String,
        apiKeyText: String,
        activeModelId: String,
        provider: String,
        geminiApiKeyText: String,
        enableNpuBackend: Boolean = false
    ) {
        viewModelScope.launch {
            try {
                val validatedPort = portText.toIntOrNull() ?: 8080
                val wasRunning = isServerRunning.value

                withContext(Dispatchers.IO) {
                    // 1. Persist to Room
                    val currentSettings = repository.getSettingsDirect() ?: ProxySetting()
                    val updated = currentSettings.copy(
                        port = validatedPort,
                        proxyApiKey = apiKeyText.trim(),
                        activeModelId = activeModelId,
                        targetProvider = provider,
                        geminiApiKey = geminiApiKeyText.trim(),
                        enableNpuBackend = enableNpuBackend
                    )
                    repository.updateSettings(updated)

                    // 2. If running and port changes, reboot server using the unified atomic transaction call
                    if (wasRunning && validatedPort != serverPort.value) {
                        serverManager.rebootServer(validatedPort)
                    }
                }
            } catch (e: Throwable) {
                Log.e("GatewayViewModel", "Error applying gateway configurations", e)
            }
        }
    }

    fun changeActiveModel(modelId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val currentSettings = repository.getSettingsDirect() ?: ProxySetting()
                    val updated = currentSettings.copy(activeModelId = modelId)
                    repository.updateSettings(updated)
                }
            } catch (e: Throwable) {
                Log.e("GatewayViewModel", "Error changing active model in settings", e)
            }
        }
    }

    // Model Download Progress & Status Management
    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

    private val _downloadStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val downloadStatus: StateFlow<Map<String, String>> = _downloadStatus.asStateFlow()

    fun downloadModel(model: com.example.data.LocalModelInfo) {
        val modelId = model.modelId
        val urlString = model.url
        if (urlString.isEmpty()) {
            _downloadStatus.value = _downloadStatus.value + (modelId to "failed: No download URL")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _downloadStatus.value = _downloadStatus.value + (modelId to "downloading")
            _downloadProgress.value = _downloadProgress.value + (modelId to 0f)

            var connection: java.net.HttpURLConnection? = null
            var inputStream: java.io.InputStream? = null
            var outputStream: java.io.FileOutputStream? = null

            try {
                val targetFile = model.getResolvedTargetFile(getApplication())
                // Create intermediate directories
                targetFile.parentFile?.mkdirs()

                val url = java.net.URL(urlString)
                connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.connect()

                if (connection.responseCode != java.net.HttpURLConnection.HTTP_OK) {
                    throw java.io.IOException("HTTP ${connection.responseCode} ${connection.responseMessage}")
                }

                val fileLength = connection.contentLengthLong
                inputStream = connection.inputStream
                outputStream = java.io.FileOutputStream(targetFile)

                val data = ByteArray(8192)
                var total: Long = 0
                var count: Int

                while (inputStream.read(data).also { count = it } != -1) {
                    total += count
                    if (fileLength > 0) {
                        val progress = total.toFloat() / fileLength
                        _downloadProgress.value = _downloadProgress.value + (modelId to progress)
                    }
                    outputStream.write(data, 0, count)
                }

                outputStream.flush()
                _downloadStatus.value = _downloadStatus.value + (modelId to "completed")
                _downloadProgress.value = _downloadProgress.value + (modelId to 1.0f)

                // Select the downloaded model and mark/indicate it as currently active
                withContext(Dispatchers.Main) {
                    changeActiveModel(modelId)
                }

            } catch (e: Throwable) {
                Log.e("GatewayViewModel", "Error downloading model $modelId", e)
                _downloadStatus.value = _downloadStatus.value + (modelId to "failed: ${e.message}")
            } finally {
                try {
                    outputStream?.close()
                    inputStream?.close()
                } catch (e: Exception) {}
                connection?.disconnect()
            }
        }
    }

    fun changeProvider(provider: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val currentSettings = repository.getSettingsDirect() ?: ProxySetting()
                    val updated = currentSettings.copy(targetProvider = provider)
                    repository.updateSettings(updated)
                }
            } catch (e: Throwable) {
                Log.e("GatewayViewModel", "Error changing active provider strategy", e)
            }
        }
    }

    fun clearLogHistory() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.clearLogs()
                }
            } catch (e: Throwable) {
                Log.e("GatewayViewModel", "Error clearing traffic log history", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Stop server thread loop safely to prevent leaks
        serverManager.stopServer()
    }
}
