package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GatewayLogDao {
    @Query("SELECT * FROM gateway_logs ORDER BY timestamp DESC LIMIT 100")
    fun getLatestLogsFlow(): Flow<List<GatewayLog>>

    @Insert
    suspend fun insertLog(log: GatewayLog)

    @Query("DELETE FROM gateway_logs")
    suspend fun clearLogs()
}
