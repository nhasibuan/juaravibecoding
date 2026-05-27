package com.example.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GatewayRepository(
    private val proxySettingDao: ProxySettingDao,
    private val gatewayLogDao: GatewayLogDao,
    private val context: Context? = null
) {
    val settingsFlow: Flow<ProxySetting?> = proxySettingDao.getSettingsFlow()
    val latestLogsFlow: Flow<List<GatewayLog>> = gatewayLogDao.getLatestLogsFlow()

    suspend fun getSettingsDirect(): ProxySetting? {
        return proxySettingDao.getSettingsDirect()
    }

    suspend fun updateSettings(setting: ProxySetting) {
        proxySettingDao.insertOrUpdate(setting)
    }

    suspend fun insertLog(log: GatewayLog) {
        gatewayLogDao.insertLog(log)
        appendToFileLog(log)
    }

    suspend fun clearLogs() {
        gatewayLogDao.clearLogs()
        val activeContext = context
        if (activeContext != null) {
            try {
                val file = File(activeContext.filesDir, "gateway_audit.log")
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                Log.e("GatewayRepository", "Failed to delete physical gateway_audit.log file", e)
            }
        }
    }

    private fun appendToFileLog(log: GatewayLog) {
        val activeContext = context ?: return
        try {
            val file = File(activeContext.filesDir, "gateway_audit.log")
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            val formattedTime = sdf.format(Date(log.timestamp))
            val authStr = if (log.isAuthorized) "AUTH_OK" else "AUTH_FAIL"
            
            // Clean structured audit line format
            val logLine = String.format(
                "[%s] [%s] %s %s - Status: %d - Client: %s - Model: %s - Duration: %d ms - Response: %s\n",
                formattedTime, authStr, log.method, log.path, log.status, log.clientIp, log.requestModel, log.durationMs, log.responsePreview
            )
            
            FileOutputStream(file, true).use { fos ->
                fos.write(logLine.toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.e("GatewayRepository", "Failed to append to physical gateway_audit.log file", e)
        }
    }
}

