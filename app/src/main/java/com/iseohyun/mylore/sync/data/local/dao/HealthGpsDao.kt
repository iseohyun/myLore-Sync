package com.iseohyun.mylore.sync.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.iseohyun.mylore.sync.data.local.entity.GpsPlaceEntity
import com.iseohyun.mylore.sync.data.local.entity.HealthGpsDaySummaryEntity
import com.iseohyun.mylore.sync.data.local.entity.HealthGpsPointEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HealthGpsDao {

    @Query("SELECT * FROM health_gps_day_summaries ORDER BY date DESC")
    fun getAllDaySummariesFlow(): Flow<List<HealthGpsDaySummaryEntity>>

    @Query("SELECT * FROM health_gps_day_summaries ORDER BY date DESC")
    suspend fun getAllDaySummaries(): List<HealthGpsDaySummaryEntity>

    @Query("SELECT * FROM health_gps_day_summaries WHERE date = :date LIMIT 1")
    suspend fun getDaySummary(date: String): HealthGpsDaySummaryEntity?

    @Query("SELECT * FROM health_gps_points WHERE date = :date ORDER BY timestamp ASC")
    fun getPointsForDateFlow(date: String): Flow<List<HealthGpsPointEntity>>

    @Query("SELECT * FROM health_gps_points WHERE date = :date ORDER BY timestamp ASC")
    suspend fun getPointsForDate(date: String): List<HealthGpsPointEntity>

    /**
     * 아직 PC로 올리지 않은 실시간 수집 포인트(패시브/지도앱/외출/Wi-Fi/OS)를 id 오름차순으로 조회합니다.
     * 삼성 헬스 경로·사진 EXIF처럼 원본 파일로 따로 전송되는 포인트는 제외합니다.
     * (id는 AUTOINCREMENT라 새 포인트일수록 항상 크고, 날짜 단위 재삽입 때도 기존 id가 유지됩니다.)
     */
    @Query(
        "SELECT * FROM health_gps_points WHERE id > :afterId AND (" +
            "sourceFileName IN ('passive_gps', 'map_gps', 'outing_gps') " +
            "OR substr(sourceFileName, 1, 5) = 'wifi_' " +
            "OR substr(sourceFileName, 1, 3) = 'os_'" +
            ") ORDER BY id ASC LIMIT :limit"
    )
    suspend fun getRealtimePointsAfterId(afterId: Long, limit: Int): List<HealthGpsPointEntity>

    @Query("SELECT DISTINCT date FROM health_gps_points ORDER BY date DESC")
    suspend fun getAllAvailableDates(): List<String>

    @Query("SELECT date FROM health_gps_day_summaries WHERE date < :targetDate ORDER BY date DESC LIMIT 1")
    suspend fun getPreviousAvailableDate(targetDate: String): String?

    @Query("SELECT date FROM health_gps_day_summaries WHERE date > :targetDate ORDER BY date ASC LIMIT 1")
    suspend fun getNextAvailableDate(targetDate: String): String?

    @Query("SELECT date FROM health_gps_day_summaries ORDER BY date DESC LIMIT 1")
    suspend fun getLatestAvailableDate(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoints(points: List<HealthGpsPointEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDaySummaries(summaries: List<HealthGpsDaySummaryEntity>)

    @Query("DELETE FROM health_gps_points WHERE date = :date")
    suspend fun deletePointsForDate(date: String)

    @Query("DELETE FROM health_gps_day_summaries WHERE date = :date")
    suspend fun deleteSummaryForDate(date: String)

    @Query("DELETE FROM health_gps_points")
    suspend fun clearAllPoints()

    @Query("DELETE FROM health_gps_day_summaries")
    suspend fun clearAllSummaries()

    // ==================== 사용자가 이름 붙인 장소 ====================
    @Query("SELECT * FROM gps_places ORDER BY createdAt ASC")
    fun getAllPlacesFlow(): Flow<List<GpsPlaceEntity>>

    /** 새 장소 추가 또는 같은 id의 장소 이름 변경 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlace(place: GpsPlaceEntity): Long

    @Query("DELETE FROM gps_places WHERE id = :id")
    suspend fun deletePlace(id: Long)
}
