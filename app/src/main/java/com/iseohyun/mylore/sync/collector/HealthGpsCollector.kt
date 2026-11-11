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
import kotlinx.coroutines.flow.first
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

            // 0) 기존 DB에 적재된 패시브/Wi-Fi/지도앱/외출 실시간 위치 포인트를 먼저 보존 로드
            val existingDates = dao.getAllAvailableDates()
            for (dStr in existingDates) {
                val existingPts = dao.getPointsForDate(dStr).filter {
                    it.sourceFileName == "passive_gps" ||
                        it.sourceFileName == "map_gps" ||
                        it.sourceFileName == "outing_gps" ||
                        it.sourceFileName.startsWith("wifi_") ||
                        it.sourceFileName.startsWith("os_")
                }
                if (existingPts.isNotEmpty()) {
                    pointsByDate.getOrPut(dStr) { ArrayList() }.addAll(existingPts)
                }
            }

            // 1) OS LocationManager (GPS / Network / Passive / Fused) 현재 캐시된 위치 즉시 수집
            if (context != null) {
                try {
                    val snapshots = LocationWifiCollector(context).collectCurrentSnapshot()
                    for (snap in snapshots) {
                        val lat = snap.latitude ?: continue
                        val lon = snap.longitude ?: continue
                        if (!isValidLatLon(lat, lon)) continue
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
                                sourceFileName = src
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

            val summaries = ArrayList<HealthGpsDaySummaryEntity>()

            // 날짜별로 중복 제거, 시간순 정렬, 체류 구간 감지, 청크 단위 DB 적재
            for ((dateStr, rawPoints) in pointsByDate) {
                if (rawPoints.isEmpty()) continue

                val deduplicated = deduplicatePointsForDay(rawPoints.sortedBy { it.timestamp })
                if (deduplicated.isEmpty()) continue

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

                summaries.add(
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

                processedCount += processedPoints.size
                rawPoints.clear() // 메모리 회수
            }

            if (summaries.isNotEmpty()) {
                dao.insertDaySummaries(summaries)
            }

            hasRunMultiSourceScanThisProcess.set(true)
            prefs.updateLastGpsCollectTs(System.currentTimeMillis())
            Log.i(TAG, "휴대폰 통합 GPS 데이터 수집 완료: 총 ${summaries.size}일, $processedCount 개 포인트")
        } catch (e: Exception) {
            Log.e(TAG, "GPS 데이터 수집 중 오류 발생", e)
        } finally {
            isSyncing.set(false)
        }

        processedCount
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
     * 이동이 거의 없는 시간(체류) 구간 감지 (선형 O(N) 탐색)
     */
    fun detectStayPointsAndEnrich(
        sortedPoints: List<HealthGpsPointEntity>
    ): List<HealthGpsPointEntity> {
        if (sortedPoints.size < 2) return sortedPoints

        val result = ArrayList<HealthGpsPointEntity>(sortedPoints.map {
            it.copy(isStayPoint = false, stayDurationMinutes = 0L, stayStartTime = 0L, stayEndTime = 0L)
        })
        var i = 0
        val n = result.size

        while (i < n) {
            val anchor = result[i]
            var j = i + 1

            while (j < n) {
                val candidate = result[j]
                val dist = calculateDistanceMeters(
                    anchor.latitude, anchor.longitude,
                    candidate.latitude, candidate.longitude
                )

                if (dist <= STAY_DISTANCE_THRESHOLD_METERS) {
                    j++
                } else {
                    break
                }
            }

            val stayDuration = if (j > i + 1) result[j - 1].timestamp - anchor.timestamp else 0L

            if (stayDuration >= STAY_TIME_THRESHOLD_MILLIS) {
                val durationMin = stayDuration / (60 * 1000L)
                val midIndex = (i + j - 1) / 2
                val midPoint = result[midIndex]

                result[midIndex] = midPoint.copy(
                    isStayPoint = true,
                    stayDurationMinutes = durationMin,
                    stayStartTime = anchor.timestamp,
                    stayEndTime = result[j - 1].timestamp
                )

                i = j // 체류 구간 이후로 건너뜀
            } else {
                // 선형 진행: 최소 1칸 또는 5칸씩 신속하게 전진
                i = max(i + 1, min(i + 5, j))
            }
        }

        return result
    }

    fun calculateTotalTrackDistance(points: List<HealthGpsPointEntity>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            total += calculateDistanceMeters(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
        }
        return total
    }
}
