package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProxySettingDao {
    @Query("SELECT * FROM proxy_settings WHERE id = 1 LIMIT 1")
    fun getSettingsFlow(): Flow<ProxySetting?>

    @Query("SELECT * FROM proxy_settings WHERE id = 1 LIMIT 1")
    suspend fun getSettings(): ProxySetting?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(setting: ProxySetting)
}
