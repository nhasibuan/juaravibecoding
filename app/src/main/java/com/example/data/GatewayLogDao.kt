package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface GatewayLogDao {
    @Query("SELECT * FROM gateway_logs ORDER BY timestamp DESC")
    fun getAllLogsFlow(): Flow<List<GatewayLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: GatewayLog)

    @Query("DELETE FROM gateway_logs")
    suspend fun clearLogs()

    @Query("SELECT COUNT(*) FROM gateway_logs")
    suspend fun getLogCount(): Int
}
