package com.example.data

import kotlinx.coroutines.flow.Flow

class GatewayRepository(
    private val proxySettingDao: ProxySettingDao,
    private val gatewayLogDao: GatewayLogDao
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
    }

    suspend fun clearLogs() {
        gatewayLogDao.clearLogs()
    }
}
