package com.iseohyun.mylore.sync.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
}
