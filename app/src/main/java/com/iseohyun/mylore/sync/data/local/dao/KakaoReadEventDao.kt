package com.iseohyun.mylore.sync.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.iseohyun.mylore.sync.data.local.entity.KakaoReadEventEntity

@Dao
interface KakaoReadEventDao {

    @Insert
    suspend fun insert(event: KakaoReadEventEntity): Long

    /** 먼저 쌓인 것부터 [limit]개 */
    @Query("SELECT * FROM pending_kakao_read_events ORDER BY id ASC LIMIT :limit")
    suspend fun getOldest(limit: Int): List<KakaoReadEventEntity>

    @Query("DELETE FROM pending_kakao_read_events WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM pending_kakao_read_events")
    suspend fun getPendingCount(): Int
}
