package com.iseohyun.mylore.sync.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 헬스 GPS 개별 좌표 포인트 엔티티
 */
@Entity(
    tableName = "health_gps_points",
    indices = [
        Index(value = ["date"]),
        Index(value = ["timestamp"]),
        Index(value = ["isStayPoint"])
    ]
)
data class HealthGpsPointEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val date: String, // yyyy-MM-dd
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val isStayPoint: Boolean = false, // 이동이 거의 없는 체류 지점 여부
    val stayDurationMinutes: Long = 0L, // 체류 시간 (분)
    val stayStartTime: Long = 0L,
    val stayEndTime: Long = 0L,
    val sourceFileName: String = ""
)

/**
 * 일자별 GPS 이동 요약 엔티티 (빠른 달력/리스트 조회용)
 */
@Entity(
    tableName = "health_gps_day_summaries",
    indices = [
        Index(value = ["date"], unique = true),
        Index(value = ["startTime"])
    ]
)
data class HealthGpsDaySummaryEntity(
    @PrimaryKey
    val date: String, // yyyy-MM-dd
    val pointCount: Int,
    val startTime: Long,
    val endTime: Long,
    val totalDistanceMeters: Double = 0.0,
    val stayCount: Int = 0,
    val totalStayDurationMinutes: Long = 0L,
    val centerLat: Double = 0.0,
    val centerLng: Double = 0.0,
    val updatedAt: Long = System.currentTimeMillis()
)
