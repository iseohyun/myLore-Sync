package com.iseohyun.mylore.sync.data.local.dao

import androidx.room.*
import com.iseohyun.mylore.sync.data.local.entity.NotificationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: NotificationEntity): Long

    @Query("SELECT * FROM pending_notifications WHERE isSynced = 0 ORDER BY postedAt ASC LIMIT :limit")
    suspend fun getPendingNotifications(limit: Int = 100): List<NotificationEntity>

    @Query("SELECT * FROM pending_notifications ORDER BY postedAt DESC LIMIT 300")
    fun getAllNotificationsFlow(): Flow<List<NotificationEntity>>

    @Query("UPDATE pending_notifications SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<Long>)

    @Query("DELETE FROM pending_notifications WHERE isSynced = 1")
    suspend fun deleteSyncedNotifications()

    @Query("SELECT COUNT(*) FROM pending_notifications WHERE isSynced = 0")
    fun getPendingCountFlow(): Flow<Int>
}
