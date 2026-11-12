package com.iseohyun.mylore.sync.collector

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.local.entity.HealthGpsDaySummaryEntity
import com.iseohyun.mylore.sync.data.local.entity.HealthGpsPointEntity
import com.iseohyun.mylore.sync.data.prefs.AppPreferences
import com.iseohyun.mylore.sync.data.remote.dto.SyncLocationLogItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

sealed class GpsProcessState {
    object Idle : GpsProcessState()
    data class Processing(val message: String, val current: Int = 0, val total: Int = 0) : GpsProcessState()
    data class Finished(val daysCount: Int, val pointsCount: Int) : GpsProcessState()
    data class Error(val errorMsg: String) : GpsProcessState()
}

/**
 * GPS 백그라운드 적재 및 분석 프로세서
 *
 * 역할:
 * 1. UI 스레드를 절대 차단하지 않고, 전용 코루틴 스코프에서 GPS 정보 비동기 적재
 * 2. LocationWifiCollector에서 들어오는 패시브 GPS / Wi-Fi 거점 스냅샷을 실시간으로 분석·적재
 * 3. 휴대폰의 모든 GPS 소스(사진/동영상 EXIF, 삼성 헬스 JSON & CSV, GPX/KML, OS LocationManager) 백그라운드 파싱 및 체류 지점 알고리즘 수행
 * 4. UI가 관찰할 수 있는 StateFlow를 제공하여 로딩 중에도 어플 전체가 부드럽게 작동
 */
object GpsBackgroundProcessor {

    private const val TAG = "GpsBackgroundProcessor"

    /** 같은 순간의 위치로 보고 정밀도를 비교하는 시간 범위 */
    private const val SAME_MOMENT_WINDOW_MS = 10_000L
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isSyncingFiles = AtomicBoolean(false)

    private val _processState = MutableStateFlow<GpsProcessState>(GpsProcessState.Idle)
    val processState: StateFlow<GpsProcessState> = _processState.asStateFlow()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)

    /**
     * 패시브 GPS / Wi-Fi 스냅샷이 수신될 때 비동기로 로컬 DB에 분석·적재합니다.
     * 개선된 알고리즘을 적용하여 15m 미만의 지터(잡음)는 필터링하고 체류 구간 및 당일 요약을 갱신합니다.
     */
    fun enqueueLocationSnapshot(db: AppDatabase, items: List<SyncLocationLogItem>) {
        if (items.isEmpty()) return

        scope.launch {
            try {
                for (item in items) {
                    val lat = item.latitude ?: continue
                    val lon = item.longitude ?: continue
                    if (lat == 0.0 && lon == 0.0) continue
                    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue

                    val itemTs = if (item.ts > 0L) item.ts else System.currentTimeMillis()
                    val dateStr = dateFormat.format(Date(itemTs))

                    val totalPoints = storeSnapshotPoint(db, item, lat, lon, itemTs, dateStr) ?: continue

                    Log.d(TAG, "패시브 위치 스냅샷 성공적 적재: $dateStr, $lat, $lon (총 ${totalPoints}pt)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "위치 스냅샷 적재 중 오류", e)
            }
        }
    }

    /**
     * 스냅샷 1건을 당일 포인트에 병합해 저장합니다. 잡음으로 걸러지면 null, 저장되면 당일 총 포인트 수를 반환합니다.
     *
     * 날짜 단위 "읽기 → 삭제 → 재삽입"이 다른 코루틴과 겹치면 삭제 직후의 빈 상태를 읽은 쪽이
     * 하루치를 덮어써 지우게 되므로, 공용 잠금([HealthGpsCollector.gpsWriteMutex])과 단일 트랜잭션 안에서 수행합니다.
     */
    private suspend fun storeSnapshotPoint(
        db: AppDatabase,
        item: SyncLocationLogItem,
        lat: Double,
        lon: Double,
        itemTs: Long,
        dateStr: String
    ): Int? {
        val dao = db.healthGpsDao()
        return HealthGpsCollector.gpsWriteMutex.withLock {
            db.withTransaction {
                // [잡음 필터 1] 오차 반경이 큰 위치(기지국 기준 등)는 저장하지 않음. 0은 "알 수 없음"이라 통과
                val accuracy = item.accuracyM ?: 0.0
                if (accuracy > HealthGpsCollector.MAX_ACCEPTABLE_ACCURACY_METERS) {
                    return@withTransaction null
                }

                // 당일 기존 포인트 로드
                val existing = dao.getPointsForDate(dateStr).toMutableList()

                // [잡음 필터 2] 같은 시각대(±10초)에 더 정밀한 위치가 이미 있으면 새 위치를 버리고,
                // 새 위치가 더 정밀하면 그 시각대의 덜 정밀한 실시간 포인트를 대체
                if (accuracy > 0.0) {
                    val sameMoment = existing.filter {
                        HealthGpsCollector.isRealtimeSource(it.sourceFileName) &&
                            it.accuracyMeters > 0.0 &&
                            kotlin.math.abs(it.timestamp - itemTs) <= SAME_MOMENT_WINDOW_MS
                    }
                    if (sameMoment.any { it.accuracyMeters <= accuracy }) {
                        return@withTransaction null
                    }
                    existing.removeAll(sameMoment)
                }

                // [잡음 필터 3] 직전 포인트 대비 비현실적인 속도의 점프는 버림.
                // 직전 포인트 자체가 잡음일 수 있으므로, 그 앞 포인트 기준으로는 그럴듯하면 받아들임
                val earlier = existing.filter { it.timestamp <= itemTs }.sortedByDescending { it.timestamp }.take(2)
                if (earlier.isNotEmpty()) {
                    val plausibleFromAny = earlier.any { prev ->
                        HealthGpsCollector.isPlausibleMove(
                            HealthGpsCollector.calculateDistanceMeters(prev.latitude, prev.longitude, lat, lon),
                            prev.timestamp,
                            itemTs
                        )
                    }
                    if (!plausibleFromAny) {
                        return@withTransaction null
                    }
                }
                val nearestPoint = existing.minByOrNull { kotlin.math.abs(it.timestamp - itemTs) }

                val distFromNearest = if (nearestPoint != null) {
                    HealthGpsCollector.calculateDistanceMeters(
                        nearestPoint.latitude, nearestPoint.longitude,
                        lat, lon
                    )
                } else {
                    9999.0
                }
                val timeDiffMs = if (nearestPoint != null) kotlin.math.abs(itemTs - nearestPoint.timestamp) else 999999L

                // 지도 앱(네이버/구글/카카오맵) 동반 수신일 때는 5초/10m 정밀도를 허용하고, 일반 패시브/외출 모드일 때는 15초/12m 잡음 억제
                val isMapPiggyback = item.eventType == "gps_map_piggyback"
                val minDistThreshold = if (isMapPiggyback) 8.0 else 12.0
                val minTimeThreshold = if (isMapPiggyback) 4_000L else 15_000L

                if (distFromNearest < minDistThreshold && timeDiffMs < minTimeThreshold) {
                    return@withTransaction null
                }

                val source = when {
                    item.wifiSsid.isNotBlank() -> "wifi_${item.wifiSsid}"
                    isMapPiggyback -> "map_gps"
                    item.eventType == "gps_outing" || item.eventType == "outing_start" -> "outing_gps"
                    else -> "passive_gps"
                }
                val newPoint = HealthGpsPointEntity(
                    date = dateStr,
                    timestamp = itemTs,
                    latitude = lat,
                    longitude = lon,
                    altitude = 0.0,
                    isStayPoint = false,
                    stayDurationMinutes = 0L,
                    sourceFileName = source,
                    accuracyMeters = accuracy
                )
                existing.add(newPoint)

                val sortedPoints = existing.sortedBy { it.timestamp }
                val processedPoints: List<HealthGpsPointEntity> =
                    HealthGpsCollector().detectStayPointsAndEnrich(sortedPoints)

                // DB 갱신
                dao.deletePointsForDate(dateStr)
                processedPoints.chunked(300).forEach { chunk: List<HealthGpsPointEntity> ->
                    dao.insertPoints(chunk)
                }

                // 당일 요약 갱신
                val totalDistance = HealthGpsCollector().calculateTotalTrackDistance(processedPoints)
                val stayPoints = processedPoints.filter { it.isStayPoint }
                val totalStayDuration = stayPoints.sumOf { it.stayDurationMinutes }

                var sumLat = 0.0
                var sumLng = 0.0
                for (p in processedPoints) {
                    sumLat += p.latitude
                    sumLng += p.longitude
                }
                val centerLat = if (processedPoints.isNotEmpty()) sumLat / processedPoints.size else 0.0
                val centerLng = if (processedPoints.isNotEmpty()) sumLng / processedPoints.size else 0.0

                dao.insertDaySummaries(
                    listOf(
                        HealthGpsDaySummaryEntity(
                            date = dateStr,
                            pointCount = processedPoints.size,
                            startTime = processedPoints.first().timestamp,
                            endTime = processedPoints.last().timestamp,
                            totalDistanceMeters = totalDistance,
                            stayCount = stayPoints.size,
                            totalStayDurationMinutes = totalStayDuration,
                            centerLat = if (centerLat.isNaN()) 0.0 else centerLat,
                            centerLng = if (centerLng.isNaN()) 0.0 else centerLng,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                )

                processedPoints.size
            }
        }
    }

    /**
     * 휴대폰 전체 GPS 소스(사진/영상 EXIF, 삼성 헬스 JSON/CSV, GPX, OS LocationManager) 동기화를
     * 완전한 백그라운드 프로세스로 실행합니다.
     */
    fun startHealthGpsSync(
        db: AppDatabase,
        prefs: AppPreferences,
        force: Boolean = false,
        context: Context? = null,
        onComplete: ((days: Int, points: Int) -> Unit)? = null
    ) {
        if (context != null) {
            LocationWifiCollector.ensurePassiveListenerRegistered(context)
        }

        if (!isSyncingFiles.compareAndSet(false, true)) {
            Log.d(TAG, "GPS 통합 동기화가 이미 진행 중입니다.")
            return
        }

        scope.launch {
            _processState.value = GpsProcessState.Processing("휴대폰 전체 GPS 데이터 수집 시작...", 0, 0)
            try {
                val collector = HealthGpsCollector()
                val count = collector.syncGpsDataIfNeeded(db, prefs, force = force, context = context)
                val allDays = db.healthGpsDao().getAllAvailableDates().size
                _processState.value = GpsProcessState.Finished(allDays, count)
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(allDays, count)
                }
            } catch (e: Exception) {
                Log.e(TAG, "GPS 동기화 중 오류", e)
                _processState.value = GpsProcessState.Error(e.message ?: "동기화 실패")
            } finally {
                isSyncingFiles.set(false)
            }
        }
    }
}
