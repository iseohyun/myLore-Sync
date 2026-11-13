package com.iseohyun.mylore.sync.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 사용자가 이름을 붙인 장소 (예: "집", "정관아쿠아드림파크").
 *
 * GPS 화면의 체류 표시는 이 장소의 중심에서 [radiusMeters] 안에 있으면 같은 장소로 보고 이름을 보여 줍니다.
 * 이름은 사용자가 직접 입력하거나 주소 조회 후보 중에서 골라 승인한 것만 저장합니다.
 */
@Entity(tableName = "gps_places")
data class GpsPlaceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double = DEFAULT_RADIUS_METERS,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val DEFAULT_RADIUS_METERS = 100.0
    }
}
