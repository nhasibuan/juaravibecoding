package com.example.data

import kotlinx.coroutines.flow.Flow

class GatewayRepository(private val db: AppDatabase) {
    val settingsFlow: Flow<ProxySetting?> = db.proxySettingDao().getSettingsFlow()
    val allLogsFlow: Flow<List<GatewayLog>> = db.gatewayLogDao().getAllLogsFlow()
    val downloadStatesFlow: Flow<List<ModelDownloadState>> = db.modelDownloadStateDao().getAllDownloadStatesFlow()

    fun getDownloadStateFlow(modelId: String): Flow<ModelDownloadState?> {
        return db.modelDownloadStateDao().getDownloadStateFlow(modelId)
    }

    suspend fun getDownloadState(modelId: String): ModelDownloadState? {
        return db.modelDownloadStateDao().getDownloadState(modelId)
    }

    suspend fun updateDownloadState(state: ModelDownloadState) {
        db.modelDownloadStateDao().insertOrUpdate(state)
    }

    suspend fun deleteDownloadState(modelId: String) {
        db.modelDownloadStateDao().deleteDownloadState(modelId)
    }

    suspend fun getSettings(): ProxySetting {
        val current = db.proxySettingDao().getSettings() ?: ProxySetting()
        if (current.gatewayAuthToken.isEmpty()) {
            val secureToken = "gateway_" + java.util.UUID.randomUUID().toString().take(8)
            val secured = current.copy(gatewayAuthToken = secureToken)
            db.proxySettingDao().insertOrUpdate(secured)
            return secured
        }
        return current
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
