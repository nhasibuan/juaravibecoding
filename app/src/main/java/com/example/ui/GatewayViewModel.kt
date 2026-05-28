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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class GatewayViewModel(
    application: Application,
    private val repository: GatewayRepository,
    private val serverManager: ProxyServerManager
) : AndroidViewModel(application) {

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

    private val _auditLogFileInfo = MutableStateFlow("gateway_audit.log (Size: 0 Bytes - Ready)")
    val auditLogFileInfo = _auditLogFileInfo.asStateFlow()

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
        // Asynchronously update file info initially and on any new Room DB logs received
        viewModelScope.launch {
            logsState.collect {
                updateAuditLogFileInfo()
            }
        }

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

    fun applySettings(portText: String, apiKeyText: String, activeModelId: String, provider: String, geminiApiKeyText: String, bypassGpu: Boolean, enableNpuBackend: Boolean) {
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
                        bypassGpu = bypassGpu,
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
                // Always get the resolved path for writing (which directs specifically to app storage space)
                val targetFile = model.getResolvedTargetFile(getApplication(), forWriting = true)
                // Create intermediate directories
                targetFile.parentFile?.mkdirs()

                var currentUrl = urlString
                    .replace("/blob/main/", "/resolve/main/")
                    .replace("/blob/master/", "/resolve/master/")
                var redirectCount = 0
                val maxRedirects = 5
                var activeConnection: java.net.HttpURLConnection? = null

                while (redirectCount < maxRedirects) {
                    val url = java.net.URL(currentUrl)
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    conn.instanceFollowRedirects = true
                    // Add User-Agent header which is strictly required by Hugging Face resolve/CDN queries
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    conn.connect()
                    
                    val responseCode = conn.responseCode
                    if (responseCode == java.net.HttpURLConnection.HTTP_MOVED_TEMP ||
                        responseCode == java.net.HttpURLConnection.HTTP_MOVED_PERM ||
                        responseCode == 301 || responseCode == 302 || responseCode == 303 ||
                        responseCode == 307 || responseCode == 308) {
                        
                        var newUrl = conn.getHeaderField("Location")
                        if (newUrl != null && newUrl.isNotEmpty()) {
                            // Resolve relative redirects safely
                            if (newUrl.startsWith("/")) {
                                val base = java.net.URL(currentUrl)
                                newUrl = "${base.protocol}://${base.host}${if (base.port != -1) ":${base.port}" else ""}$newUrl"
                            } else if (!newUrl.startsWith("http://") && !newUrl.startsWith("https://")) {
                                val base = java.net.URL(currentUrl)
                                val basePath = base.path.substringBeforeLast("/", "")
                                newUrl = "${base.protocol}://${base.host}${if (base.port != -1) ":${base.port}" else ""}$basePath/$newUrl"
                            }
                            currentUrl = newUrl
                            conn.disconnect()
                            redirectCount++
                            continue
                        }
                    }
                    activeConnection = conn
                    break
                }

                connection = activeConnection

                if (connection == null) {
                    throw java.io.IOException("Failed to establish connection")
                }

                if (connection.responseCode != java.net.HttpURLConnection.HTTP_OK) {
                    throw java.io.IOException("HTTP ${connection.responseCode} ${connection.responseMessage}")
                }

                val fileLength = connection.contentLengthLong
                inputStream = connection.inputStream
                outputStream = java.io.FileOutputStream(targetFile)

                val data = ByteArray(8192)
                var total: Long = 0
                var count: Int
                
                var lastEmittedPct = 0f
                var lastEmitTime = 0L

                while (inputStream.read(data).also { count = it } != -1) {
                    total += count
                    if (fileLength > 0) {
                        val progress = total.toFloat() / fileLength
                        val currTime = System.currentTimeMillis()
                        // Throttle state-flow progress updates to avoid flooding main thread recompositions & ANR/crashes
                        if (progress - lastEmittedPct >= 0.01f || currTime - lastEmitTime >= 300L) {
                            _downloadProgress.value = _downloadProgress.value + (modelId to progress)
                            lastEmittedPct = progress
                            lastEmitTime = currTime
                        }
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

    fun updateAuditLogFileInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(getApplication<Application>().filesDir, "gateway_audit.log")
            val info = if (!file.exists()) {
                "gateway_audit.log (Size: 0 Bytes - Ready)"
            } else {
                val sizeInBytes = file.length()
                when {
                    sizeInBytes < 1024 -> "gateway_audit.log (Size: $sizeInBytes Bytes)"
                    sizeInBytes < 1024 * 1024 -> String.format(Locale.US, "gateway_audit.log (Size: %.2f KB)", sizeInBytes / 1024.0)
                    else -> String.format(Locale.US, "gateway_audit.log (Size: %.2f MB)", sizeInBytes / (1024.0 * 1024.0))
                }
            }
            _auditLogFileInfo.value = info
        }
    }

    fun forceSyncAuditLogFile() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val logs = logsState.value
                    val file = File(getApplication<Application>().filesDir, "gateway_audit.log")
                    if (file.exists()) {
                        file.delete()
                    }
                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                    val fileContent = StringBuilder()
                    // Loop backwards or forwards depending on order. Room logs: timestamp DESC limit 100.
                    // We reverse it to append chronologically (top oldest, bottom newest).
                    for (log in logs.reversed()) {
                        val formattedTime = sdf.format(Date(log.timestamp))
                        val authStr = if (log.isAuthorized) "AUTH_OK" else "AUTH_FAIL"
                        val logLine = String.format(
                            "[%s] [%s] %s %s - Status: %d - Client: %s - Model: %s - Duration: %d ms - Response: %s\n",
                            formattedTime, authStr, log.method, log.path, log.status, log.clientIp, log.requestModel, log.durationMs, log.responsePreview
                        )
                        fileContent.append(logLine)
                    }
                    FileOutputStream(file).use { fos ->
                        fos.write(fileContent.toString().toByteArray(Charsets.UTF_8))
                    }
                }
                updateAuditLogFileInfo()
            } catch (e: Exception) {
                Log.e("GatewayViewModel", "Failed to rebuild audit log file", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Stop server thread loop safely to prevent leaks
        serverManager.stopServerSync()
    }
}

class GatewayViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GatewayViewModel::class.java)) {
            val database = AppDatabase.getDatabase(application)
            val repository = GatewayRepository(
                database.proxySettingDao(),
                database.gatewayLogDao(),
                application
            )
            val serverManager = ProxyServerManager(application, repository)
            @Suppress("UNCHECKED_CAST")
            return GatewayViewModel(application, repository, serverManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
