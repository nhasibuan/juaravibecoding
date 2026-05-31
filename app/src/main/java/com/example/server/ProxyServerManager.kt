package com.example.server

import android.content.Context
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.util.Log
import com.example.data.GatewayRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

object ProxyServerManager {
    private var activeServer: HttpGatewayServer? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var statusJob: Job? = null

    private val _status = MutableStateFlow(ServerStatus.STOPPED)
    val status: StateFlow<ServerStatus> = _status

    private val _activePort = MutableStateFlow(8080)
    val activePort: StateFlow<Int> = _activePort

    private val _errorCount = MutableStateFlow(0)
    val errorCount: StateFlow<Int> = _errorCount

    private var repository: GatewayRepository? = null
    private var context: Context? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    fun initialize(repo: GatewayRepository, ctx: Context) {
        this.repository = repo
        this.context = ctx.applicationContext
        LogUtility.initialize(ctx)
    }

    fun startServer(cpuPort: Int) {
        val ctx = context
        val repo = repository
        if (ctx == null || repo == null) {
            Log.e("ProxyServerManager", "Cannot start server: not initialized.")
            _status.value = ServerStatus.ERROR
            return
        }

        if (_status.value == ServerStatus.RUNNING) {
            stopServer()
        }

        _activePort.value = cpuPort

        // Acquire partial wake and high performance WiFi locks to keep server always-on
        try {
            val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AiProxyGateway::WakeLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            val wifiManager = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null) {
                wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AiProxyGateway::WifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            LogUtility.logMessage("ProxyServerManager", "Acquired WakeLock and WifiLock successfully.")
        } catch (e: Throwable) {
            LogUtility.logError("ProxyServerManagerLocks", e)
        }

        try {
            val server = HttpGatewayServer(ctx, repo, cpuPort)
            activeServer = server
            
            statusJob?.cancel()
            statusJob = scope.launch {
                // Collect states from the active secure HTTP server instance
                launch {
                    server.status.collect { serverStatus ->
                        _status.value = serverStatus
                    }
                }
                launch {
                    server.errorCount.collect { errs ->
                        _errorCount.value = errs
                    }
                }
            }
            
            server.start()
        } catch (t: Throwable) {
            LogUtility.logError("ProxyServerManagerInit", t)
            _status.value = ServerStatus.ERROR
            _errorCount.value += 1
        }
    }

    fun stopServer() {
        LogUtility.logMessage("ProxyServerManager", "Initiating stop of active server container...")
        statusJob?.cancel()
        statusJob = null

        activeServer?.stop()
        activeServer = null
        _status.value = ServerStatus.STOPPED

        // Release keeps-awake locks
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
            wifiLock = null
            LogUtility.logMessage("ProxyServerManager", "Released WakeLock and WifiLock.")
        } catch (e: Throwable) {
            LogUtility.logError("ProxyServerManagerLocksRelease", e)
        }
    }

    suspend fun rebootServer(newPort: Int) = withContext(Dispatchers.IO) {
        stopServer()
        try {
            delay(150)
        } catch (e: Exception) {}
        withContext(Dispatchers.Main) {
            startServer(newPort)
        }
    }
}
