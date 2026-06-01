package com.example.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GatewayRepository(private val db: AppDatabase) {
    val settingsFlow: Flow<ProxySetting?> = db.proxySettingDao().getSettingsFlow()
        .map { decryptSetting(it) }
    val allLogsFlow: Flow<List<GatewayLog>> = db.gatewayLogDao().getAllLogsFlow()
    val downloadStatesFlow: Flow<List<ModelDownloadState>> = db.modelDownloadStateDao().getAllDownloadStatesFlow()

    private fun decryptSetting(setting: ProxySetting?): ProxySetting? {
        if (setting == null) return null
        return setting.copy(
            geminiApiKey = CryptoUtility.decrypt(setting.geminiApiKey),
            gatewayAuthToken = CryptoUtility.decrypt(setting.gatewayAuthToken)
        )
    }

    private fun encryptSetting(setting: ProxySetting): ProxySetting {
        return setting.copy(
            geminiApiKey = CryptoUtility.encrypt(setting.geminiApiKey),
            gatewayAuthToken = CryptoUtility.encrypt(setting.gatewayAuthToken)
        )
    }

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
        val currentEncrypted = db.proxySettingDao().getSettings() ?: ProxySetting()
        val current = decryptSetting(currentEncrypted) ?: ProxySetting()
        if (current.gatewayAuthToken.isEmpty()) {
            val secureToken = "gateway_" + java.util.UUID.randomUUID().toString().replace("-", "")
            val secured = current.copy(gatewayAuthToken = secureToken)
            updateSettings(secured)
            return secured
        }
        return current
    }

    suspend fun updateSettings(settings: ProxySetting) {
        val encrypted = encryptSetting(settings)
        db.proxySettingDao().insertOrUpdate(encrypted)
    }

    suspend fun insertLog(log: GatewayLog) {
        db.gatewayLogDao().insertLog(log)
    }

    suspend fun clearLogs() {
        db.gatewayLogDao().clearLogs()
    }
}

