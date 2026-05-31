package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelDownloadStateDao {
    @Query("SELECT * FROM model_download_states")
    fun getAllDownloadStatesFlow(): Flow<List<ModelDownloadState>>

    @Query("SELECT * FROM model_download_states WHERE modelId = :modelId LIMIT 1")
    suspend fun getDownloadState(modelId: String): ModelDownloadState?

    @Query("SELECT * FROM model_download_states WHERE modelId = :modelId LIMIT 1")
    fun getDownloadStateFlow(modelId: String): Flow<ModelDownloadState?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(state: ModelDownloadState)

    @Query("DELETE FROM model_download_states WHERE modelId = :modelId")
    suspend fun deleteDownloadState(modelId: String)

    @Query("DELETE FROM model_download_states")
    suspend fun deleteAllDownloadStates()
}
