package com.example.data

import kotlinx.coroutines.flow.Flow

class GatewayRepository(private val db: AppDatabase) {
    val settingsFlow: Flow<ProxySetting?> = db.proxySettingDao().getSettingsFlow()
    val allLogsFlow: Flow<List<GatewayLog>> = db.gatewayLogDao().getAllLogsFlow()

    suspend fun getSettings(): ProxySetting {
        return db.proxySettingDao().getSettings() ?: ProxySetting().also {
            db.proxySettingDao().insertOrUpdate(it)
        }
    }

    suspend fun updateSettings(settings: ProxySetting) {
        db.proxySettingDao().insertOrUpdate(settings)
    }

    suspend fun insertLog(log: GatewayLog) {
        db.gatewayLogDao().insertLog(log)
    }

    suspend fun clearLogs() {
        db.gatewayLogDao().clearLogs()
    }
}
