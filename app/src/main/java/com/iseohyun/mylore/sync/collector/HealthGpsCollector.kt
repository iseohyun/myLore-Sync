package com.iseohyun.mylore.sync.collector

import android.content.Context
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.util.Log
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.local.entity.HealthGpsDaySummaryEntity
import com.iseohyun.mylore.sync.data.local.entity.HealthGpsPointEntity
import com.iseohyun.mylore.sync.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

/**
 * 지도 경로의 한 구간. 운전 구간([isDriving])과 그 외(도보·체류 등)를 다른 색으로 그리기 위해 나눕니다.
 * 이웃한 구간은 경계 포인트 하나를 공유하므로 선이 끊기지 않습니다.
 */
data class GpsRouteSegment(
    val points: List<HealthGpsPointEntity>,
    val isDriving: Boolean
)

/**
 * 휴대폰 통합 GPS 데이터 수집기
 *
 * 수집 대상 (휴대폰이 보유한 모든 GPS 소스):
 * 1) OS LocationManager (GPS / Network / Passive / Fused 제공자의 캐시 및 실시간 패시브 위치)
 * 2) 사진(JPG/HEIC/WEBP/DNG) EXIF GPS 메타데이터 및 동영상(MP4/MOV) ISO-6709 위치 태그 (Camera, Pictures, Screenshots 등)
 * 3) 삼성 헬스 운동 경로 JSON (*.location_data.json, *.location_data_internal.json)
 * 4) 삼성 헬스 운동 요약 및 날씨 CSV (com.samsung.shealth.exercise.*.csv, exercise.weather.*.csv 등 위경도 포함 CSV)
 * 5) GPX / KML / Google Timeline(Records.json, Timeline.json) 등 내장 저장소 위치 기록 파일
 */
class HealthGpsCollector {

    companion object {
        private const val TAG = "HealthGpsCollector"
        private const val ONE_DAY_MILLIS = 24 * 60 * 60 * 1000L

        // 체류 판정 임계치: 반경 45m 이내에서 10분 이상 머무름
        private const val STAY_DISTANCE_THRESHOLD_METERS = 45.0
        private const val STAY_TIME_THRESHOLD_MILLIS = 10 * 60 * 1000L

        // 동시 실행 방지 플래그 (앱 시작 시 중복 트리거 방지)
        private val isSyncing = AtomicBoolean(false)

        // 신규 다중 소스(사진 EXIF + 헬스 CSV + OS 위치) 최초 1회 강제 스캔 여부
        private val hasRunMultiSourceScanThisProcess = AtomicBoolean(false)

        /**
         * `health_gps_points` / `health_gps_day_summaries` 쓰기 직렬화용 잠금.
         * 날짜 단위 "읽기 → 삭제 → 재삽입" 갱신이 여러 코루틴(패시브/외출 리스너, 동기화 서비스 스냅샷,
         * 전체 재스캔)에서 겹치면 한쪽이 삭제 직후의 빈 상태를 읽어 하루치를 덮어써 지우므로,
         * 이 테이블을 고치는 모든 경로는 반드시 이 잠금 안에서 읽고 써야 합니다.
         */
        val gpsWriteMutex = Mutex()

        /** 파일에서 다시 파싱할 수 없는 실시간 수집 포인트(패시브/지도앱/외출/Wi-Fi/OS)인지 여부 */
        fun isRealtimeSource(sourceFileName: String): Boolean {
            return sourceFileName == "passive_gps" ||
                sourceFileName == "map_gps" ||
                sourceFileName == "outing_gps" ||
                sourceFileName.startsWith("wifi_") ||
                sourceFileName.startsWith("os_")
        }

        /** 과거 날짜의 체류·이동거리 1회 재계산 작업의 완료 기록 키. 체류 판정이나 거리 계산 규칙을 바꾸면 접미사(v2)를 올립니다. */
        private const val DAY_RECALC_TASK_KEY = "gps_day_recalc_v2"

        // ===== GPS 잡음 필터 기준값 =====
        /** 이 오차 반경(m)을 넘는 위치(기지국 기준 등)는 저장하지 않습니다. */
        const val MAX_ACCEPTABLE_ACCURACY_METERS = 100.0

        /** 이 속도(m/s, 시속 약 200km)를 넘는 점프는 실제 이동이 아닌 잡음으로 봅니다. */
        const val MAX_PLAUSIBLE_SPEED_MPS = 55.6

        /** 속도·뾰족점 판정을 적용하는 최대 시간 간격. 이보다 오래 떨어진 두 점은 판정 없이 받아들입니다. */
        const val NOISE_CHECK_MAX_GAP_MILLIS = 10 * 60 * 1000L

        /** 거리 합산 시 "제자리 흔들림"으로 보는 최소 이동 거리(m): 실시간 수집 포인트 / 파일에서 읽은 운동 경로 */
        private const val MIN_MOVE_REALTIME_METERS = 30.0
        private const val MIN_MOVE_TRACK_METERS = 10.0

        /** 갔다가 바로 되돌아오는 뾰족점 판정: (가는 거리 + 돌아오는 거리) > 직선거리 × 배수 + 여유(m) */
        private const val SPIKE_DETOUR_RATIO = 3.0
        private const val SPIKE_MIN_METERS = 150.0

        /**
         * 두 지점 사이의 이동이 물리적으로 그럴듯한지 판정합니다.
         * 시간 간격이 [NOISE_CHECK_MAX_GAP_MILLIS] 이내인데 속도가 [MAX_PLAUSIBLE_SPEED_MPS]를 넘으면 false.
         */
        fun isPlausibleMove(distanceMeters: Double, fromTs: Long, toTs: Long): Boolean {
            val dtMs = kotlin.math.abs(toTs - fromTs)
            if (dtMs >= NOISE_CHECK_MAX_GAP_MILLIS) return true
            if (dtMs <= 0L) return false
            return distanceMeters / (dtMs / 1000.0) <= MAX_PLAUSIBLE_SPEED_MPS
        }

        /** 속도 판정으로 잡음 처리할 최소 점프 거리(m). 같은 시각에 몇 m 차이로 찍힌 중복 포인트를 잡음으로 오인하지 않기 위함 */
        private const val NOISE_MIN_JUMP_METERS = 50.0

        // 체류 구간 묶기 기준
        /** 두 체류의 중심이 이 거리(m) 이내면 같은 장소로 봅니다. */
        private const val SAME_PLACE_RADIUS_METERS = 100.0

        /** 두 체류 사이의 포인트가 모두 이 거리(m) 이내면 "실제로 나갔다 온 것이 아님"으로 봅니다. */
        private const val STAY_GAP_EXCURSION_METERS = 150.0

        /** 사이에 기록이 없어도 같은 체류로 이어 붙이는 최대 공백 */
        private const val STAY_MERGE_MAX_BLIND_GAP_MILLIS = 30 * 60 * 1000L

        /**
         * 시간순 포인트에서 잡음이 아닌 포인트의 인덱스만 고릅니다. (원본은 지우지 않고 분석·표시에서만 제외)
         * - 직전에 남긴 포인트 대비 비현실적인 속도로 튄 포인트
         * - 멀리 튀었다가 다음 포인트에서 바로 되돌아오는 뾰족점 (GPS 경로 사이에 낀 네트워크 위치)
         */
        fun noiseFreeIndices(sortedPoints: List<HealthGpsPointEntity>): List<Int> {
            if (sortedPoints.size < 3) return sortedPoints.indices.toList()
            val kept = ArrayList<Int>(sortedPoints.size)
            kept.add(0)
            for (i in 1 until sortedPoints.size) {
                val anchor = sortedPoints[kept.last()]
                val p = sortedPoints[i]
                val dist = calculateDistanceMeters(anchor.latitude, anchor.longitude, p.latitude, p.longitude)

                if (dist >= NOISE_MIN_JUMP_METERS && !isPlausibleMove(dist, anchor.timestamp, p.timestamp)) continue

                if (dist >= SPIKE_MIN_METERS && i + 1 < sortedPoints.size) {
                    val next = sortedPoints[i + 1]
                    if (next.timestamp - p.timestamp < NOISE_CHECK_MAX_GAP_MILLIS) {
                        val back = calculateDistanceMeters(p.latitude, p.longitude, next.latitude, next.longitude)
                        val direct = calculateDistanceMeters(anchor.latitude, anchor.longitude, next.latitude, next.longitude)
                        if (dist + back > SPIKE_DETOUR_RATIO * direct + SPIKE_MIN_METERS) continue
                    }
                }
                kept.add(i)
            }
            return kept
        }

        /** 지도 경로 표시용: 잡음 포인트를 뺀 목록 */
        fun removeNoisePoints(sortedPoints: List<HealthGpsPointEntity>): List<HealthGpsPointEntity> {
            return noiseFreeIndices(sortedPoints).map { sortedPoints[it] }
        }

        // ===== 운전 구간 판별 기준 =====
        /** 두 포인트 사이 속도가 이 값(m/s, 시속 20km) 이상이면 운전 후보로 봅니다. */
        private const val DRIVING_MIN_SPEED_MPS = 5.56

        /** 운전 구간 사이의 짧은 저속 구간(신호 대기 등)은 이 시간 이하면 운전으로 이어 붙입니다. */
        private const val DRIVING_BRIDGE_MAX_MILLIS = 3 * 60 * 1000L

        /** 이보다 짧거나(시간·거리) 평균 속도가 낮은 운전 후보는 잡음으로 보고 운전에서 제외합니다. */
        private const val DRIVING_MIN_DURATION_MILLIS = 60 * 1000L
        private const val DRIVING_MIN_DISTANCE_METERS = 300.0
        private const val DRIVING_MIN_AVG_SPEED_MPS = 4.17 // 시속 15km

        /**
         * 잡음을 뺀 시간순 포인트([removeNoisePoints] 결과)를 운전 구간과 그 외 구간으로 나눕니다.
         *
         * 1) 포인트 사이 속도가 시속 20km 이상인 간격을 운전 후보로 표시
         * 2) 운전 후보 사이에 낀 3분 이하의 저속 간격은 운전으로 연결 (신호 대기·정체)
         * 3) 1분·300m 미만이거나 평균 시속 15km 미만인 운전 후보는 제외
         */
        fun splitRouteByDriving(cleanPoints: List<HealthGpsPointEntity>): List<GpsRouteSegment> {
            val n = cleanPoints.size
            if (n < 2) return emptyList()

            fun edgeDistance(i: Int) = calculateDistanceMeters(
                cleanPoints[i].latitude, cleanPoints[i].longitude,
                cleanPoints[i + 1].latitude, cleanPoints[i + 1].longitude
            )

            // 간격 i = 포인트 i → i+1
            val isDrivingEdge = BooleanArray(n - 1) { i ->
                val dtMs = cleanPoints[i + 1].timestamp - cleanPoints[i].timestamp
                dtMs in 1..NOISE_CHECK_MAX_GAP_MILLIS &&
                    edgeDistance(i) / (dtMs / 1000.0) >= DRIVING_MIN_SPEED_MPS
            }

            /** 같은 값이 이어지는 간격 묶음: (첫 간격, 마지막 간격) */
            fun runs(): List<IntRange> {
                val out = ArrayList<IntRange>()
                var start = 0
                for (i in 1..isDrivingEdge.size) {
                    if (i == isDrivingEdge.size || isDrivingEdge[i] != isDrivingEdge[start]) {
                        out.add(start until i)
                        start = i
                    }
                }
                return out
            }

            fun runDurationMs(run: IntRange) = cleanPoints[run.last + 1].timestamp - cleanPoints[run.first].timestamp

            // 2) 운전 사이에 낀 짧은 저속 구간 연결
            val initialRuns = runs()
            for ((idx, run) in initialRuns.withIndex()) {
                val isBetweenDriving = idx > 0 && idx < initialRuns.size - 1
                if (!isDrivingEdge[run.first] && isBetweenDriving && runDurationMs(run) <= DRIVING_BRIDGE_MAX_MILLIS) {
                    for (i in run) isDrivingEdge[i] = true
                }
            }

            // 3) 너무 짧거나 느린 운전 후보 제외
            for (run in runs()) {
                if (!isDrivingEdge[run.first]) continue
                val durationMs = runDurationMs(run)
                val distance = run.sumOf { edgeDistance(it) }
                val avgSpeed = if (durationMs > 0) distance / (durationMs / 1000.0) else 0.0
                if (durationMs < DRIVING_MIN_DURATION_MILLIS ||
                    distance < DRIVING_MIN_DISTANCE_METERS ||
                    avgSpeed < DRIVING_MIN_AVG_SPEED_MPS
                ) {
                    for (i in run) isDrivingEdge[i] = false
                }
            }

            return runs().map { run ->
                GpsRouteSegment(
                    points = cleanPoints.subList(run.first, run.last + 2),
                    isDriving = isDrivingEdge[run.first]
                )
            }
        }

        private val EXIF_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "heic", "heif", "webp", "dng")
        private val VIDEO_LOCATION_EXTENSIONS = setOf("mp4", "mov", "3gp")

        /**
         * 두 위경도 좌표 간 거리 계산 (Haversine 공식, 미터 단위)
         */
        fun calculateDistanceMeters(
            lat1: Double, lon1: Double,
            lat2: Double, lon2: Double
        ): Double {
            val r = 6371000.0 // 지구 반경 (m)
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                    sin(dLon / 2).pow(2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return r * c
        }

        fun formatDurationText(minutes: Long): String {
            if (minutes < 60) {
                return "${minutes}분 체류"
            }
            val hours = minutes / 60
            val rem = minutes % 60
            return if (rem > 0) "${hours}시간 ${rem}분 체류" else "${hours}시간 체류"
        }
    }

    /**
     * 마지막 수집 시점으로부터 24시간이 지났거나 날짜가 바뀌었는지 여부
     * (앱 실행 후 다중 소스 스캔이 아직 한 번도 수행되지 않았다면 즉시 true 반환)
     */
    suspend fun shouldUpdateGps(prefs: AppPreferences): Boolean {
        if (!hasRunMultiSourceScanThisProcess.get()) return true

        val lastTs = prefs.lastGpsCollectTs.first()
        if (lastTs <= 0L) return true

        val now = System.currentTimeMillis()
        if (now - lastTs >= ONE_DAY_MILLIS) return true

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
        val lastDate = sdf.format(Date(lastTs))
        val today = sdf.format(Date(now))
        return lastDate != today
    }

    /**
     * 휴대폰의 모든 GPS 데이터를 수집하고 로컬 DB에 날짜별로 병합·가공·저장합니다.
     */
    suspend fun syncGpsDataIfNeeded(
        db: AppDatabase,
        prefs: AppPreferences,
        force: Boolean = false,
        context: Context? = null
    ): Int = withContext(Dispatchers.IO) {
        recalculateDaysOnceIfNeeded(db, prefs)

        if (!force && !shouldUpdateGps(prefs)) {
            Log.d(TAG, "GPS 데이터 수집 주기 미도래 (최근 24시간 이내 수집됨)")
            return@withContext 0
        }

        if (!isSyncing.compareAndSet(false, true)) {
            Log.d(TAG, "GPS 데이터 수집이 이미 진행 중입니다.")
            return@withContext 0
        }

        Log.i(TAG, "휴대폰 통합 GPS 데이터 수집 시작 (force=$force)")
        var processedCount = 0

        try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
            val pointsByDate = HashMap<String, ArrayList<HealthGpsPointEntity>>()
            val dao = db.healthGpsDao()

            // 0) DB에 이미 적재된 실시간(패시브/Wi-Fi/지도앱/외출) 포인트는 여기서 미리 읽어 두지 않습니다.
            //    파싱이 끝난 뒤 날짜별로 쓰는 시점에 잠금 안에서 최신 상태를 다시 읽어 병합해야,
            //    스캔 도중 새로 들어온 포인트를 오래된 목록으로 덮어써 지우지 않습니다.

            // 1) OS LocationManager (GPS / Network / Passive / Fused) 현재 캐시된 위치 즉시 수집
            if (context != null) {
                try {
                    val snapshots = LocationWifiCollector(context).collectCurrentSnapshot()
                    for (snap in snapshots) {
                        val lat = snap.latitude ?: continue
                        val lon = snap.longitude ?: continue
                        if (!isValidLatLon(lat, lon)) continue
                        // 오차 반경이 큰 위치(기지국 기준 등)는 저장하지 않음
                        val accuracy = snap.accuracyM ?: 0.0
                        if (accuracy > MAX_ACCEPTABLE_ACCURACY_METERS) continue
                        val ts = if (snap.ts > 0L) snap.ts else System.currentTimeMillis()
                        val dStr = dateFormat.format(Date(ts))
                        val src = if (snap.wifiSsid.isNotBlank()) "wifi_${snap.wifiSsid}" else "passive_gps"
                        pointsByDate.getOrPut(dStr) { ArrayList() }.add(
                            HealthGpsPointEntity(
                                date = dStr,
                                timestamp = ts,
                                latitude = lat,
                                longitude = lon,
                                altitude = 0.0,
                                sourceFileName = src,
                                accuracyMeters = accuracy
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "OS LocationManager 스냅샷 수집 실패", e)
                }
            }

            // 2) 삼성 헬스 JSON / GPX / KML / Google Timeline 파일 파싱
            val locationFiles = findCandidateGpsFiles()
            Log.i(TAG, "탐색된 대상 GPS 트랙 파일 수: ${locationFiles.size}")
            for (file in locationFiles) {
                try {
                    val name = file.name.lowercase(Locale.ROOT)
                    when {
                        name.endsWith(".json") -> parseJsonLocationFileStreaming(file, dateFormat, pointsByDate)
                        name.endsWith(".gpx") -> parseGpxLocationFileStreaming(file, dateFormat, pointsByDate)
                        name.endsWith(".kml") -> parseKmlLocationFileStreaming(file, dateFormat, pointsByDate)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "파일 파싱 실패: ${file.name}", e)
                }
            }

            // 3) 삼성 헬스 CSV 파일 내 운동 시작/종료/날씨 위경도 파싱
            val csvFiles = findSamsungHealthCsvFiles()
            Log.i(TAG, "탐색된 삼성 헬스 CSV 파일 수: ${csvFiles.size}")
            for (csvFile in csvFiles) {
                try {
                    parseSamsungHealthCsvForGps(csvFile, dateFormat, pointsByDate)
                } catch (e: Exception) {
                    Log.w(TAG, "삼성 헬스 CSV 파싱 실패: ${csvFile.name}", e)
                }
            }

            // 4) 휴대폰 사진(EXIF) 및 동영상(ISO-6709) 내장 GPS 좌표 수집
            val mediaGpsCount = collectPhotoAndVideoExifGps(dateFormat, pointsByDate)
            Log.i(TAG, "사진/동영상 미디어에서 추출된 GPS 포인트 수: $mediaGpsCount")

            var summaryCount = 0

            // 날짜별로 중복 제거, 시간순 정렬, 체류 구간 감지, 청크 단위 DB 적재
            for ((dateStr, rawPoints) in pointsByDate) {
                if (rawPoints.isEmpty()) continue

                // 날짜 하나의 "최신 실시간 포인트 읽기 → 병합 → 삭제 → 재삽입 → 요약"을
                // 잠금 + 단일 트랜잭션으로 묶어, 다른 코루틴이 중간의 빈 상태를 보지 못하게 합니다.
                val writtenCount = gpsWriteMutex.withLock {
                    db.withTransaction {
                        val liveRealtimePoints = dao.getPointsForDate(dateStr).filter { isRealtimeSource(it.sourceFileName) }
                        val deduplicated = deduplicatePointsForDay((rawPoints + liveRealtimePoints).sortedBy { it.timestamp })
                        if (deduplicated.isEmpty()) return@withTransaction 0

                        val processedPoints = detectStayPointsAndEnrich(deduplicated)

                        dao.deletePointsForDate(dateStr)
                        // SQLite 쿼리 파라미터 한도 초과 방지를 위한 300개 단위 청크 삽입
                        processedPoints.chunked(300).forEach { chunk ->
                            dao.insertPoints(chunk)
                        }

                        // 요약 계산
                        val totalDistance = calculateTotalTrackDistance(processedPoints)
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

                if (writtenCount > 0) {
                    summaryCount++
                    processedCount += writtenCount
                }
                rawPoints.clear() // 메모리 회수
            }

            hasRunMultiSourceScanThisProcess.set(true)
            prefs.updateLastGpsCollectTs(System.currentTimeMillis())
            Log.i(TAG, "휴대폰 통합 GPS 데이터 수집 완료: 총 ${summaryCount}일, $processedCount 개 포인트")
        } catch (e: Exception) {
            Log.e(TAG, "GPS 데이터 수집 중 오류 발생", e)
        } finally {
            isSyncing.set(false)
        }

        processedCount
    }

    /**
     * 체류 판정·거리 계산 규칙이 바뀌기 전에 저장된 날짜들을 새 규칙으로 1회 다시 계산합니다.
     * 포인트의 좌표·시각은 그대로 두고 체류 표시와 날짜별 요약(이동거리·체류 수·체류 시간)만 갱신하며,
     * 끝까지 성공했을 때만 완료로 기록합니다.
     */
    private suspend fun recalculateDaysOnceIfNeeded(db: AppDatabase, prefs: AppPreferences) {
        if (prefs.isOneTimeTaskDone(DAY_RECALC_TASK_KEY)) return
        try {
            val dao = db.healthGpsDao()
            var updatedDays = 0
            for (dateStr in dao.getAllAvailableDates()) {
                gpsWriteMutex.withLock {
                    db.withTransaction {
                        // 잠금 안에서 최신 포인트를 다시 읽어, 그 사이 들어온 포인트를 덮어쓰지 않게 함
                        val points = dao.getPointsForDate(dateStr)
                        if (points.isEmpty()) return@withTransaction
                        val processed = detectStayPointsAndEnrich(points)

                        dao.deletePointsForDate(dateStr)
                        processed.chunked(300).forEach { chunk -> dao.insertPoints(chunk) }

                        val stayPoints = processed.filter { it.isStayPoint }
                        val previous = dao.getDaySummary(dateStr)
                        dao.insertDaySummaries(
                            listOf(
                                HealthGpsDaySummaryEntity(
                                    date = dateStr,
                                    pointCount = processed.size,
                                    startTime = processed.first().timestamp,
                                    endTime = processed.last().timestamp,
                                    totalDistanceMeters = calculateTotalTrackDistance(processed),
                                    stayCount = stayPoints.size,
                                    totalStayDurationMinutes = stayPoints.sumOf { it.stayDurationMinutes },
                                    centerLat = previous?.centerLat ?: processed.map { it.latitude }.average(),
                                    centerLng = previous?.centerLng ?: processed.map { it.longitude }.average(),
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        )
                        updatedDays++
                    }
                }
            }
            prefs.markOneTimeTaskDone(DAY_RECALC_TASK_KEY)
            Log.i(TAG, "과거 날짜 체류·이동거리 재계산 완료: ${updatedDays}일")
        } catch (e: Exception) {
            Log.w(TAG, "과거 날짜 재계산 실패 (다음 실행 때 재시도)", e)
        }
    }

    private fun isValidLatLon(lat: Double, lon: Double): Boolean {
        if (lat.isNaN() || lon.isNaN()) return false
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return false
        if (abs(lat) < 0.0001 && abs(lon) < 0.0001) return false
        return true
    }

    /**
     * 같은 날짜 내에서 여러 소스(JSON, CSV, EXIF, 패시브)가 겹칠 때
     * 4초 이내이고 10m 이내인 완전히 동일한 중복 점은 하나로 병합합니다.
     */
    private fun deduplicatePointsForDay(sortedPoints: List<HealthGpsPointEntity>): List<HealthGpsPointEntity> {
        if (sortedPoints.size <= 1) return sortedPoints
        val out = ArrayList<HealthGpsPointEntity>(sortedPoints.size)
        for (pt in sortedPoints) {
            val prev = out.lastOrNull()
            if (prev != null) {
                val timeDiff = abs(pt.timestamp - prev.timestamp)
                val dist = calculateDistanceMeters(prev.latitude, prev.longitude, pt.latitude, pt.longitude)
                if (timeDiff < 4000L && dist < 10.0) {
                    continue
                }
            }
            out.add(pt)
        }
        return out
    }

    /**
     * 삼성 헬스 및 GPX / KML 대상 파일 고속 탐색 (중복 폴더 및 대용량 비위치 폴더 제외)
     */
    private fun findCandidateGpsFiles(): List<File> {
        val extStorage = Environment.getExternalStorageDirectory()
        val result = mutableListOf<File>()
        val seenExerciseUuids = HashSet<String>()

        // 1. 삼성 헬스 다운로드 폴더 내의 백업 폴더들 탐색 (운동 폴더만 타깃 스캔)
        val shealthParent = File(extStorage, "Download/Samsung Health")
        if (shealthParent.exists() && shealthParent.isDirectory) {
            val exportDirs = shealthParent.listFiles { f ->
                f.isDirectory && f.name.startsWith("samsunghealth_")
            }?.sortedByDescending { it.name } ?: emptyList()

            val internalCandidates = mutableListOf<Pair<String, File>>()

            for (exportDir in exportDirs) {
                val exerciseDir = File(exportDir, "jsons/com.samsung.shealth.exercise")
                if (exerciseDir.exists() && exerciseDir.isDirectory) {
                    exerciseDir.walkTopDown().maxDepth(3).forEach { file ->
                        if (file.isFile) {
                            val name = file.name.lowercase(Locale.ROOT)
                            val uuid = file.name.substringBefore(".")
                            if (name.endsWith(".location_data.json") && !name.contains("location_data_internal")) {
                                if (seenExerciseUuids.add(uuid)) {
                                    result.add(file)
                                }
                            } else if (name.endsWith(".location_data_internal.json")) {
                                internalCandidates.add(uuid to file)
                            }
                        }
                    }
                }
            }

            // 표준 location_data.json이 없는 운동 UUID만 internal 파일로 보완
            for ((uuid, file) in internalCandidates) {
                if (seenExerciseUuids.add(uuid)) {
                    result.add(file)
                }
            }
        }

        // 2. 단독 GPX / KML / Timeline 폴더 탐색
        val standaloneDirs = listOf(
            File(extStorage, "Download/GPX"),
            File(extStorage, "Download/GPS"),
            File(extStorage, "Download/Location History"),
            File(extStorage, "Download/Takeout"),
            File(extStorage, "Documents/GPX"),
            File(extStorage, "Documents/GPS"),
            File(extStorage, "Samsung/Health")
        )
        for (dir in standaloneDirs) {
            if (dir.exists() && dir.isDirectory) {
                dir.walkTopDown().maxDepth(3).forEach { file ->
                    if (file.isFile) {
                        val n = file.name.lowercase(Locale.ROOT)
                        if (n.endsWith(".gpx") || n.endsWith(".kml") || n.endsWith(".json")) {
                            result.add(file)
                        }
                    }
                }
            }
        }

        // Download 및 Documents 루트에 바로 있는 gpx / kml / Timeline.json / Records.json 확인
        for (rootName in listOf("Download", "Documents")) {
            val dir = File(extStorage, rootName)
            dir.listFiles { f ->
                if (!f.isFile) return@listFiles false
                val n = f.name.lowercase(Locale.ROOT)
                n.endsWith(".gpx") || n.endsWith(".kml") ||
                    n == "timeline.json" || n == "records.json" || n.contains("location_history")
            }?.forEach {
                result.add(it)
            }
        }

        return result
    }

    /**
     * 삼성 헬스 백업 폴더 내 위경도가 포함된 CSV 파일 목록 탐색
     */
    private fun findSamsungHealthCsvFiles(): List<File> {
        val extStorage = Environment.getExternalStorageDirectory()
        val shealthParent = File(extStorage, "Download/Samsung Health")
        if (!shealthParent.exists() || !shealthParent.isDirectory) return emptyList()

        val result = mutableListOf<File>()
        val exportDirs = shealthParent.listFiles { f ->
            f.isDirectory && f.name.startsWith("samsunghealth_")
        }?.sortedByDescending { it.name } ?: emptyList()

        for (exportDir in exportDirs) {
            exportDir.listFiles { f ->
                f.isFile && f.name.endsWith(".csv", ignoreCase = true) && (
                    f.name.contains("exercise", ignoreCase = true) ||
                        f.name.contains("location", ignoreCase = true) ||
                        f.name.contains("weather", ignoreCase = true)
                    )
            }?.forEach { csv ->
                result.add(csv)
            }
        }
        return result
    }

    /**
     * 삼성 헬스 CSV 파일에서 위경도(start_latitude/start_longitude 또는 latitude/longitude) 및 시각을 추출합니다.
     */
    private fun parseSamsungHealthCsvForGps(
        file: File,
        dateFormat: SimpleDateFormat,
        outMap: HashMap<String, ArrayList<HealthGpsPointEntity>>
    ) {
        val utcFormats = listOf(
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") },
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        )

        file.bufferedReader().useLines { lines ->
            val iterator = lines.iterator()
            var headerCols: List<String>? = null

            while (iterator.hasNext()) {
                val line = iterator.next().trim()
                if (line.isEmpty()) continue

                if (headerCols == null) {
                    // 삼성 헬스 CSV 첫 줄은 메타데이터(예: com.samsung.shealth.exercise,250,14)이고 둘째 줄이 컬럼명
                    if (line.contains("latitude", ignoreCase = true) && line.contains("longitude", ignoreCase = true)) {
                        headerCols = splitCsvLine(line).map { it.trim().lowercase(Locale.ROOT) }
                    }
                    continue
                }

                val cols = splitCsvLine(line)
                if (cols.size < 3) continue

                fun getCol(vararg candidates: String): String? {
                    for (cand in candidates) {
                        val idx = headerCols.indexOfFirst { it == cand || it.endsWith(".$cand") }
                        if (idx in cols.indices) {
                            val v = cols[idx].trim()
                            if (v.isNotEmpty()) return v
                        }
                    }
                    return null
                }

                val latStr = getCol("start_latitude", "latitude", "mean_latitude")
                val lonStr = getCol("start_longitude", "longitude", "mean_longitude")
                val lat = latStr?.toDoubleOrNull()
                val lon = lonStr?.toDoubleOrNull()

                if (lat != null && lon != null && isValidLatLon(lat, lon)) {
                    val timeRaw = getCol("start_time", "create_time", "update_time")
                    val ts = parseSamsungHealthTime(timeRaw, utcFormats) ?: file.lastModified()
                    val dateStr = dateFormat.format(Date(ts))
                    outMap.getOrPut(dateStr) { ArrayList() }.add(
                        HealthGpsPointEntity(
                            date = dateStr,
                            timestamp = ts,
                            latitude = lat,
                            longitude = lon,
                            altitude = getCol("altitude", "mean_altitude")?.toDoubleOrNull() ?: 0.0,
                            sourceFileName = "shealth_csv_${file.name}"
                        )
                    )
                }
            }
        }
    }

    private fun parseSamsungHealthTime(
        rawTime: String?,
        utcFormats: List<SimpleDateFormat>
    ): Long? {
        if (rawTime.isNullOrBlank()) return null
        rawTime.toLongOrNull()?.let { if (it > 1_000_000_000_000L) return it }

        for (fmt in utcFormats) {
            try {
                val parsed = fmt.parse(rawTime)
                if (parsed != null) {
                    return parsed.time
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun splitCsvLine(line: String): List<String> {
        val result = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when (ch) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (inQuotes) sb.append(ch) else {
                    result.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(ch)
            }
        }
        result.add(sb.toString())
        return result
    }

    /**
     * 휴대폰 내 사진(EXIF) 파일에서 GPS 위경도 및 촬영 시각을 고속 추출합니다.
     */
    private fun collectPhotoAndVideoExifGps(
        dateFormat: SimpleDateFormat,
        outMap: HashMap<String, ArrayList<HealthGpsPointEntity>>
    ): Int {
        val extStorage = Environment.getExternalStorageDirectory()
        val candidateDirs = LinkedHashSet<File>()

        // PhotoMediaCollector의 활성 사진 폴더 + DCIM/Pictures/KakaoTalkDownload
        PhotoMediaCollector.getActiveDirectories().forEach { candidateDirs.add(it) }
        listOf(
            File(extStorage, "DCIM/Camera"),
            File(extStorage, "Pictures"),
            File(extStorage, "KakaoTalkDownload")
        ).forEach { if (it.exists() && it.isDirectory) candidateDirs.add(it) }

        val seenPaths = HashSet<String>()
        var extractedCount = 0

        val exifDateFormats = listOf(
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        )
        val fileNameDateRegex = Regex("""(20\d{2})(\d{2})(\d{2})[_-](\d{2})(\d{2})(\d{2})""")

        for (dir in candidateDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            dir.walkTopDown()
                .onEnter { d -> !d.name.startsWith(".") }
                .maxDepth(2)
                .forEach { file ->
                    if (!file.isFile || file.name.startsWith(".")) return@forEach
                    val ext = file.extension.lowercase(Locale.ROOT)
                    if (ext !in EXIF_IMAGE_EXTENSIONS) return@forEach

                    val absPath = file.absolutePath
                    if (!seenPaths.add(absPath)) return@forEach

                    try {
                        val exif = ExifInterface(absPath)
                        val latLong = FloatArray(2)
                        if (exif.getLatLong(latLong)) {
                            val lat = latLong[0].toDouble()
                            val lon = latLong[1].toDouble()
                            if (isValidLatLon(lat, lon)) {
                                val ts = extractMediaTimestamp(
                                    exif = exif,
                                    file = file,
                                    exifDateFormats = exifDateFormats,
                                    fileNameDateRegex = fileNameDateRegex
                                )
                                val alt = exif.getAltitude(0.0)
                                val dateStr = dateFormat.format(Date(ts))
                                outMap.getOrPut(dateStr) { ArrayList() }.add(
                                    HealthGpsPointEntity(
                                        date = dateStr,
                                        timestamp = ts,
                                        latitude = lat,
                                        longitude = lon,
                                        altitude = if (alt.isNaN()) 0.0 else alt,
                                        sourceFileName = "photo_exif_${file.name}"
                                    )
                                )
                                extractedCount++
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
        }

        return extractedCount
    }

    private fun extractMediaTimestamp(
        exif: ExifInterface,
        file: File,
        exifDateFormats: List<SimpleDateFormat>,
        fileNameDateRegex: Regex
    ): Long {
        val rawDate = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
        if (!rawDate.isNullOrBlank()) {
            for (fmt in exifDateFormats) {
                try {
                    val d = fmt.parse(rawDate.trim())
                    if (d != null && d.time > 946684800000L) { // 2000-01-01 이후
                        return d.time
                    }
                } catch (_: Exception) {
                }
            }
        }
        return extractTimestampFromFileNameOrModified(file, exifDateFormats[2], fileNameDateRegex)
    }

    private fun extractTimestampFromFileNameOrModified(
        file: File,
        compactFormat: SimpleDateFormat,
        fileNameDateRegex: Regex
    ): Long {
        val match = fileNameDateRegex.find(file.name)
        if (match != null) {
            val compact = "${match.groupValues[1]}${match.groupValues[2]}${match.groupValues[3]}_${match.groupValues[4]}${match.groupValues[5]}${match.groupValues[6]}"
            try {
                val parsed = compactFormat.parse(compact)
                if (parsed != null && parsed.time > 946684800000L) {
                    return parsed.time
                }
            } catch (_: Exception) {
            }
        }
        return file.lastModified()
    }

    /**
     * 스트리밍 방식 JSON 파서 (삼성 헬스 location_data.json 및 Google Timeline/Records.json 지원)
     */
    private fun parseJsonLocationFileStreaming(
        file: File,
        dateFormat: SimpleDateFormat,
        outMap: HashMap<String, ArrayList<HealthGpsPointEntity>>
    ) {
        BufferedReader(FileReader(file), 16384).use { br ->
            val reader = JsonReader(br)
            reader.isLenient = true

            when (reader.peek()) {
                JsonToken.BEGIN_ARRAY -> {
                    parsePointsArray(reader, file.name, file.lastModified(), dateFormat, outMap)
                }
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        if ((name == "location_data" || name == "points" || name == "items" || name == "locations" || name == "semanticSegments") &&
                            reader.peek() == JsonToken.BEGIN_ARRAY
                        ) {
                            parsePointsArray(reader, file.name, file.lastModified(), dateFormat, outMap)
                        } else {
                            reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                else -> reader.skipValue()
            }
        }
    }

    private fun parsePointsArray(
        reader: JsonReader,
        fileName: String,
        fileLastModified: Long,
        dateFormat: SimpleDateFormat,
        outMap: HashMap<String, ArrayList<HealthGpsPointEntity>>
    ) {
        reader.beginArray()

        var lastKeptTs = 0L
        var lastLat = 0.0
        var lastLng = 0.0

        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                continue
            }

            reader.beginObject()
            var lat: Double? = null
            var lon: Double? = null
            var alt = 0.0
            var ts: Long? = null

            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "latitude", "lat" -> {
                        lat = if (reader.peek() == JsonToken.NUMBER) reader.nextDouble() else { reader.skipValue(); null }
                    }
                    "longitude", "lon", "lng" -> {
                        lon = if (reader.peek() == JsonToken.NUMBER) reader.nextDouble() else { reader.skipValue(); null }
                    }
                    "latitudeE7" -> {
                        lat = if (reader.peek() == JsonToken.NUMBER) reader.nextLong() / 1e7 else { reader.skipValue(); null }
                    }
                    "longitudeE7" -> {
                        lon = if (reader.peek() == JsonToken.NUMBER) reader.nextLong() / 1e7 else { reader.skipValue(); null }
                    }
                    "altitude", "alt" -> {
                        alt = if (reader.peek() == JsonToken.NUMBER) reader.nextDouble() else { reader.skipValue(); 0.0 }
                    }
                    "start_time", "timestamp", "time", "ts", "timestampMs" -> {
                        ts = when (reader.peek()) {
                            JsonToken.NUMBER -> reader.nextLong()
                            JsonToken.STRING -> reader.nextString().toLongOrNull()
                            else -> { reader.skipValue(); null }
                        }
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            if (lat != null && lon != null && isValidLatLon(lat, lon)) {
                val pointTs = ts ?: fileLastModified
                val dist = if (lastLat != 0.0) calculateDistanceMeters(lastLat, lastLng, lat, lon) else 999.0
                val timeDiff = pointTs - lastKeptTs

                // 다운샘플링: 최소 4초 경과 또는 15m 이상 이동한 경우만 보관
                if (lastKeptTs == 0L || timeDiff >= 4000L || dist >= 15.0) {
                    val dateStr = dateFormat.format(Date(pointTs))
                    val list = outMap.getOrPut(dateStr) { ArrayList() }
                    list.add(
                        HealthGpsPointEntity(
                            date = dateStr,
                            timestamp = pointTs,
                            latitude = lat,
                            longitude = lon,
                            altitude = alt,
                            sourceFileName = fileName
                        )
                    )
                    lastKeptTs = pointTs
                    lastLat = lat
                    lastLng = lon
                }
            }
        }

        reader.endArray()
    }

    /**
     * GPX 파일 스트리밍 파싱 (라인 단위로 읽어 메모리 초과 방지)
     */
    private fun parseGpxLocationFileStreaming(
        file: File,
        dateFormat: SimpleDateFormat,
        outMap: HashMap<String, ArrayList<HealthGpsPointEntity>>
    ) {
        val trkptRegex = Regex("""<(?:trkpt|wpt)\s+lat="([^"]+)"\s+lon="([^"]+)"""")
        val eleRegex = Regex("""<ele>([^<]+)<\/ele>""")
        val timeRegex = Regex("""<time>([^<]+)<\/time>""")
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

        var currentLat: Double? = null
        var currentLon: Double? = null
        var currentAlt = 0.0
        var currentTs: Long? = null
        var inTrkpt = false

        var lastKeptTs = 0L
        var lastLat = 0.0
        var lastLng = 0.0

        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                val trkptMatch = trkptRegex.find(line)
                if (trkptMatch != null) {
                    currentLat = trkptMatch.groupValues[1].toDoubleOrNull()
                    currentLon = trkptMatch.groupValues[2].toDoubleOrNull()
                    currentAlt = 0.0
                    currentTs = null
                    inTrkpt = true
                }
                if (inTrkpt) {
                    eleRegex.find(line)?.let {
                        currentAlt = it.groupValues[1].toDoubleOrNull() ?: 0.0
                    }
                    timeRegex.find(line)?.let {
                        val timeStr = it.groupValues[1]
                        currentTs = try {
                            isoFormat.parse(timeStr.replace("Z", "").substringBefore("."))?.time
                        } catch (_: Exception) { null }
                    }
                    if (line.contains("</trkpt>") || line.contains("</wpt>") || line.contains("/>")) {
                        inTrkpt = false
                        val lat = currentLat
                        val lon = currentLon
                        if (lat != null && lon != null && isValidLatLon(lat, lon)) {
                            val pointTs = currentTs ?: file.lastModified()
                            val dist = if (lastLat != 0.0) calculateDistanceMeters(lastLat, lastLng, lat, lon) else 999.0
                            val timeDiff = pointTs - lastKeptTs

                            if (lastKeptTs == 0L || timeDiff >= 4000L || dist >= 15.0) {
                                val dateStr = dateFormat.format(Date(pointTs))
                                val list = outMap.getOrPut(dateStr) { ArrayList() }
                                list.add(
                                    HealthGpsPointEntity(
                                        date = dateStr,
                                        timestamp = pointTs,
                                        latitude = lat,
                                        longitude = lon,
                                        altitude = currentAlt,
                                        sourceFileName = file.name
                                    )
                                )
                                lastKeptTs = pointTs
                                lastLat = lat
                                lastLng = lon
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * KML 파일 스트리밍 파싱 (<coordinates>lon,lat,alt</coordinates>)
     */
    private fun parseKmlLocationFileStreaming(
        file: File,
        dateFormat: SimpleDateFormat,
        outMap: HashMap<String, ArrayList<HealthGpsPointEntity>>
    ) {
        val coordRegex = Regex("""(-?\d+\.\d+),(-?\d+\.\d+)(?:,(-?\d+(?:\.\d+)?))?""")
        val pointTs = file.lastModified()
        val dateStr = dateFormat.format(Date(pointTs))

        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.contains("<coordinates>") || line.contains("<gx:coord>")) {
                    for (match in coordRegex.findAll(line)) {
                        val lon = match.groupValues[1].toDoubleOrNull()
                        val lat = match.groupValues[2].toDoubleOrNull()
                        val alt = match.groupValues.getOrNull(3)?.toDoubleOrNull() ?: 0.0
                        if (lat != null && lon != null && isValidLatLon(lat, lon)) {
                            outMap.getOrPut(dateStr) { ArrayList() }.add(
                                HealthGpsPointEntity(
                                    date = dateStr,
                                    timestamp = pointTs,
                                    latitude = lat,
                                    longitude = lon,
                                    altitude = alt,
                                    sourceFileName = file.name
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 이동이 거의 없는 시간(체류) 구간을 찾아 대표 포인트 하나에 체류 정보를 기록합니다.
     *
     * 1) 잡음 포인트를 뺀 목록에서, 기준점 반경 45m 안에 10분 이상 머문 구간을 체류로 판정
     * 2) 연달아 나온 체류가 같은 장소이고 그 사이에 실제로 나갔다 온 기록이 없으면 하나로 묶음
     *    (흔들린 포인트 하나 때문에 "집" 체류가 여러 개로 쪼개지는 것을 방지)
     */
    fun detectStayPointsAndEnrich(
        sortedPoints: List<HealthGpsPointEntity>
    ): List<HealthGpsPointEntity> {
        if (sortedPoints.size < 2) return sortedPoints

        val result = ArrayList<HealthGpsPointEntity>(sortedPoints.map {
            it.copy(isStayPoint = false, stayDurationMinutes = 0L, stayStartTime = 0L, stayEndTime = 0L)
        })

        // 잡음을 뺀 포인트만으로 판정 (clean[k] = result 안의 실제 인덱스)
        val clean = noiseFreeIndices(result)
        val n = clean.size
        fun pt(k: Int) = result[clean[k]]
        fun distBetween(a: HealthGpsPointEntity, b: HealthGpsPointEntity) =
            calculateDistanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)

        // 1) 체류 구간 후보: clean 인덱스 범위 [first, last]
        val stays = ArrayList<IntArray>()
        var i = 0
        while (i < n) {
            val anchor = pt(i)
            var j = i + 1
            while (j < n && distBetween(anchor, pt(j)) <= STAY_DISTANCE_THRESHOLD_METERS) {
                j++
            }

            val stayDuration = if (j > i + 1) pt(j - 1).timestamp - anchor.timestamp else 0L
            if (stayDuration >= STAY_TIME_THRESHOLD_MILLIS) {
                stays.add(intArrayOf(i, j - 1))
                i = j // 체류 구간 이후로 건너뜀
            } else {
                // 선형 진행: 최소 1칸 또는 5칸씩 신속하게 전진
                i = max(i + 1, min(i + 5, j))
            }
        }

        // 2) 같은 장소의 연속 체류 묶기
        val merged = ArrayList<IntArray>()
        for (stay in stays) {
            val prev = merged.lastOrNull()
            if (prev != null && isSameContinuousStay(prev, stay, ::pt, ::distBetween)) {
                prev[1] = stay[1]
            } else {
                merged.add(stay)
            }
        }

        // 3) 체류마다 가운데 포인트 하나에 정보 기록
        for (stay in merged) {
            val startTs = pt(stay[0]).timestamp
            val endTs = pt(stay[1]).timestamp
            val midRealIndex = clean[(stay[0] + stay[1]) / 2]
            result[midRealIndex] = result[midRealIndex].copy(
                isStayPoint = true,
                stayDurationMinutes = (endTs - startTs) / (60 * 1000L),
                stayStartTime = startTs,
                stayEndTime = endTs
            )
        }

        return result
    }

    /**
     * 앞 체류([prev])와 뒤 체류([next])가 "같은 곳에 계속 머문 것"인지 판정합니다.
     * - 두 체류의 기준점이 [SAME_PLACE_RADIUS_METERS] 이내이고
     * - 그 사이의 포인트가 모두 [STAY_GAP_EXCURSION_METERS] 이내이며 (실제로 나갔다 온 기록이 없음)
     * - 사이에 기록이 거의 없는 공백이면 [STAY_MERGE_MAX_BLIND_GAP_MILLIS] 이하일 때만 (기록 없이 외출했을 가능성 배제)
     */
    private fun isSameContinuousStay(
        prev: IntArray,
        next: IntArray,
        pt: (Int) -> HealthGpsPointEntity,
        distBetween: (HealthGpsPointEntity, HealthGpsPointEntity) -> Double
    ): Boolean {
        val place = pt(prev[0])
        if (distBetween(place, pt(next[0])) > SAME_PLACE_RADIUS_METERS) return false

        val gapPointCount = next[0] - prev[1] - 1
        for (k in prev[1] + 1 until next[0]) {
            if (distBetween(place, pt(k)) > STAY_GAP_EXCURSION_METERS) return false
        }

        val gapMillis = pt(next[0]).timestamp - pt(prev[1]).timestamp
        if (gapMillis <= STAY_MERGE_MAX_BLIND_GAP_MILLIS) return true
        // 긴 공백은 그 사이에도 기록이 꾸준히 있었을 때만 같은 체류로 인정
        return gapPointCount >= gapMillis / STAY_MERGE_MAX_BLIND_GAP_MILLIS
    }

    /**
     * 시간순 포인트의 이동 거리 합(m)을 잡음을 걸러 가며 계산합니다.
     *
     * 기준점(anchor)에서 충분히 벗어난 포인트가 나올 때만 거리를 더하고 기준점을 옮깁니다.
     * 1) 제자리 흔들림: 기준점에서 오차 반경(또는 최소 이동 거리) 안이면 더하지 않음
     * 2) 비현실적 속도: 짧은 시간에 시속 약 200km를 넘는 점프는 건너뜀
     * 3) 뾰족점: 멀리 튀었다가 다음 포인트에서 바로 되돌아오면 건너뜀 (GPS와 기지국 위치가 번갈아 들어올 때 발생)
     */
    fun calculateTotalTrackDistance(points: List<HealthGpsPointEntity>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        var anchor = points[0]

        for (i in 1 until points.size) {
            val p = points[i]
            val dist = calculateDistanceMeters(anchor.latitude, anchor.longitude, p.latitude, p.longitude)

            val isRealtimePair = isRealtimeSource(p.sourceFileName) || isRealtimeSource(anchor.sourceFileName)
            val minMove = maxOf(
                if (isRealtimePair) MIN_MOVE_REALTIME_METERS else MIN_MOVE_TRACK_METERS,
                p.accuracyMeters,
                anchor.accuracyMeters
            )
            if (dist < minMove) continue
            if (!isPlausibleMove(dist, anchor.timestamp, p.timestamp)) continue

            if (dist >= SPIKE_MIN_METERS && i + 1 < points.size) {
                val next = points[i + 1]
                if (next.timestamp - p.timestamp < NOISE_CHECK_MAX_GAP_MILLIS) {
                    val back = calculateDistanceMeters(p.latitude, p.longitude, next.latitude, next.longitude)
                    val direct = calculateDistanceMeters(anchor.latitude, anchor.longitude, next.latitude, next.longitude)
                    if (dist + back > SPIKE_DETOUR_RATIO * direct + SPIKE_MIN_METERS) continue
                }
            }

            total += dist
            anchor = p
        }
        return total
    }
}
