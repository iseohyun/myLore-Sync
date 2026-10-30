package com.iseohyun.mylore.sync.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.iseohyun.mylore.sync.data.local.entity.SyncLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncLogDao {

    @Insert
    suspend fun insert(log: SyncLogEntity)

    @Query("SELECT * FROM sync_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLogsFlow(limit: Int = 50): Flow<List<SyncLogEntity>>

    @Query("DELETE FROM sync_logs WHERE timestamp < :thresholdTs")
    suspend fun deleteOldLogs(thresholdTs: Long)
}
