package com.iseohyun.mylore.sync.data.local.dao

import androidx.room.*
import com.iseohyun.mylore.sync.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncCacheDao {

    // ==================== 1. 카카오톡 대화방 캐시 ====================
    @Query("SELECT * FROM kakao_room_cache ORDER BY isOnDevice DESC, lastMessageTime DESC")
    fun getAllKakaoRoomsFlow(): Flow<List<KakaoRoomCacheEntity>>

    @Query("SELECT * FROM kakao_room_cache")
    suspend fun getAllKakaoRooms(): List<KakaoRoomCacheEntity>

    @Query("SELECT * FROM kakao_room_cache WHERE roomName = :roomName LIMIT 1")
    suspend fun getKakaoRoom(roomName: String): KakaoRoomCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertKakaoRooms(rooms: List<KakaoRoomCacheEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertKakaoRoom(room: KakaoRoomCacheEntity)

    @Query("UPDATE kakao_room_cache SET isOnDevice = 0 WHERE roomName NOT IN (:activeRoomNames)")
    suspend fun markInactiveKakaoRoomsDeleted(activeRoomNames: List<String>)

    @Query("UPDATE kakao_room_cache SET isOnDevice = 0")
    suspend fun markAllKakaoRoomsDeletedOnDevice()

    // ==================== 2. 문자(SMS/MMS/RCS) 캐시 ====================
    @Query("SELECT * FROM sms_cache ORDER BY ts DESC")
    fun getAllSmsFlow(): Flow<List<SmsCacheEntity>>

    @Query("SELECT MAX(ts) FROM sms_cache")
    suspend fun getMaxSmsTs(): Long?

    @Query("SELECT COUNT(*) FROM sms_cache")
    suspend fun getSmsCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSmsList(items: List<SmsCacheEntity>)

    @Query("UPDATE sms_cache SET isSynced = 1 WHERE ts <= :watermarkTs AND isSynced = 0")
    suspend fun markSmsSyncedUpTo(watermarkTs: Long)

    @Query("UPDATE sms_cache SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markSmsSyncedByIds(ids: List<Long>)

    @Query("SELECT * FROM sms_cache WHERE isSynced = 0 ORDER BY ts ASC LIMIT :limit")
    suspend fun getUnsyncedSms(limit: Int = 500): List<SmsCacheEntity>

    @Query("SELECT COUNT(*) FROM sms_cache WHERE isSynced = 0")
    suspend fun getUnsyncedSmsCount(): Int

    @Query("SELECT MIN(ts) FROM sms_cache WHERE isSynced = 0")
    suspend fun getOldestUnsyncedSmsTs(): Long?

    // ==================== 3. 통화 이력(CallLog) 캐시 ====================
    @Query("SELECT * FROM call_log_cache ORDER BY ts DESC")
    fun getAllCallLogsFlow(): Flow<List<CallLogCacheEntity>>

    @Query("SELECT MAX(ts) FROM call_log_cache")
    suspend fun getMaxCallLogTs(): Long?

    @Query("SELECT COUNT(*) FROM call_log_cache")
    suspend fun getCallLogCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallLogs(items: List<CallLogCacheEntity>)

    @Query("UPDATE call_log_cache SET isSynced = 1 WHERE ts <= :watermarkTs AND isSynced = 0")
    suspend fun markCallLogsSyncedUpTo(watermarkTs: Long)

    @Query("UPDATE call_log_cache SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markCallLogsSyncedByIds(ids: List<Long>)

    @Query("SELECT * FROM call_log_cache WHERE isSynced = 0 ORDER BY ts ASC LIMIT :limit")
    suspend fun getUnsyncedCallLogs(limit: Int = 500): List<CallLogCacheEntity>

    @Query("SELECT COUNT(*) FROM call_log_cache WHERE isSynced = 0")
    suspend fun getUnsyncedCallLogCount(): Int

    @Query("SELECT MIN(ts) FROM call_log_cache WHERE isSynced = 0")
    suspend fun getOldestUnsyncedCallTs(): Long?

    // ==================== 4. 통화/음성 녹음 파일 캐시 ====================
    @Query("SELECT * FROM recording_cache ORDER BY recordedAt DESC")
    fun getAllRecordingsFlow(): Flow<List<RecordingCacheEntity>>

    @Query("SELECT * FROM recording_cache")
    suspend fun getAllRecordings(): List<RecordingCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecordings(items: List<RecordingCacheEntity>)

    @Query("DELETE FROM recording_cache WHERE filePath NOT IN (:existingPaths)")
    suspend fun removeDeletedRecordings(existingPaths: List<String>)

    @Query("DELETE FROM recording_cache")
    suspend fun clearAllRecordings()

    @Query("UPDATE recording_cache SET isSynced = 1 WHERE fileHash IN (:hashes)")
    suspend fun markRecordingsSyncedByHashes(hashes: List<String>)

    @Query("UPDATE recording_cache SET isSynced = 1 WHERE recordedAt <= :watermarkTs AND isSynced = 0")
    suspend fun markRecordingsSyncedUpTo(watermarkTs: Long)

    @Query("SELECT MIN(recordedAt) FROM recording_cache WHERE isSynced = 0")
    suspend fun getOldestUnsyncedRecordingTs(): Long?

    // ==================== 5. 삼성 헬스 데이터 캐시 ====================
    @Query("SELECT * FROM health_sync_cache ORDER BY isOnDevice DESC, lastDataTime DESC")
    fun getAllHealthRecordsFlow(): Flow<List<HealthSyncCacheEntity>>

    @Query("SELECT * FROM health_sync_cache")
    suspend fun getAllHealthRecords(): List<HealthSyncCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHealthRecords(items: List<HealthSyncCacheEntity>)

    @Query("UPDATE health_sync_cache SET isOnDevice = 0 WHERE folderName NOT IN (:activeFolderNames)")
    suspend fun markInactiveHealthFoldersDeleted(activeFolderNames: List<String>)

    @Query("UPDATE health_sync_cache SET isOnDevice = 0")
    suspend fun markAllHealthFoldersDeletedOnDevice()
}
