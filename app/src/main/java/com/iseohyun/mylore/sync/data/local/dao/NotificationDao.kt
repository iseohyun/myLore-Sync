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

    @Query("SELECT * FROM pending_notifications ORDER BY postedAt DESC LIMIT 500")
    fun getAllNotificationsFlow(): Flow<List<NotificationEntity>>

    @Query(
        "UPDATE pending_notifications SET isSynced = 1, " +
            "syncedAt = CASE WHEN syncedAt > 0 THEN syncedAt ELSE :syncedAt END " +
            "WHERE id IN (:ids)"
    )
    suspend fun markAsSynced(ids: List<Long>, syncedAt: Long = System.currentTimeMillis())

    @Query(
        "UPDATE pending_notifications SET isSynced = 1, " +
            "syncedAt = CASE WHEN syncedAt > 0 THEN syncedAt ELSE :verifiedAt END " +
            "WHERE postedAt <= :watermarkTs AND (isSynced = 0 OR syncedAt <= 0)"
    )
    suspend fun markNotificationsSyncedUpTo(
        watermarkTs: Long,
        verifiedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE pending_notifications SET syncedAt = :verifiedAt WHERE isSynced = 1 AND syncedAt <= 0")
    suspend fun backfillMissingNotificationSyncedAt(verifiedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM pending_notifications WHERE isSynced = 1")
    suspend fun deleteSyncedNotifications()

    @Query("SELECT COUNT(*) FROM pending_notifications WHERE isSynced = 0")
    fun getPendingCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_notifications WHERE isSynced = 0")
    suspend fun getPendingCount(): Int
}
