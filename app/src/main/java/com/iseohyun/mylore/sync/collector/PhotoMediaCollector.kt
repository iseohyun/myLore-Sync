package com.iseohyun.mylore.sync.collector

import android.os.Environment
import android.util.Log
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.local.dao.SyncCacheDao
import com.iseohyun.mylore.sync.data.local.entity.PhotoMediaCacheEntity
import com.iseohyun.mylore.sync.data.prefs.AppPreferences
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

class PhotoMediaCollector {

    companion object {
        private const val TAG = "PhotoMediaCollector"

        val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "dng"
        )
        val VIDEO_EXTENSIONS = setOf(
            "mp4", "mov", "mkv", "webm", "3gp", "avi", "m4v"
        )
        val SUPPORTED_EXTENSIONS = IMAGE_EXTENSIONS + VIDEO_EXTENSIONS

        /**
         * 기본 공유 대상 5대 미디어 폴더 카테고리
         */
        val TARGET_CATEGORIES = listOf(
            "Camera",
            "Pictures",
            "Screen recordings",
            "Screenshots",
            "Videocaptures"
        )

        /**
         * 단말 내장 저장소 기준 기본 후보(참고) 폴더 경로 목록
         * ("Camera", "Pictures", "Screen recordings", "Screenshots", "Videocaptures")
         */
        val DEFAULT_CANDIDATE_PATHS = listOf(
            "DCIM/Camera",
            "Camera",
            "Pictures",
            "DCIM/Screen recordings",
            "Pictures/Screen recordings",
            "Movies/Screen recordings",
            "Screen recordings",
            "DCIM/Screenshots",
            "Pictures/Screenshots",
            "Screenshots",
            "DCIM/Videocaptures",
            "Pictures/Videocaptures",
            "Movies/Videocaptures",
            "Videocaptures",
            "VideoCaptures"
        )

        @Volatile
        private var activeDirectories: List<File> = emptyList()

        /**
         * 애플리케이션 초기 실행 시 후보 폴더 목록 중 실제 기기에 존재하는 폴더를 확인하여
         * 기본 조회 폴더(activeDirectories)로 지정합니다.
         */
        suspend fun initializeActiveDirectories(prefs: AppPreferences? = null): List<File> {
            val candidatePaths = prefs?.getOrInitCandidatePhotoDirs(DEFAULT_CANDIDATE_PATHS)
                ?: DEFAULT_CANDIDATE_PATHS.toSet()

            val verifiedDirs = probeExistingCandidateDirs(candidatePaths)
            activeDirectories = verifiedDirs
            prefs?.saveActivePhotoDirs(verifiedDirs.map { it.absolutePath }.toSet())
            Log.i(TAG, "Active photo/media directories initialized (${verifiedDirs.size}): ${verifiedDirs.map { it.absolutePath }}")
            return verifiedDirs
        }

        fun getActiveDirectories(): List<File> {
            val current = activeDirectories.filter { it.exists() && it.isDirectory }
            if (current.isNotEmpty()) {
                return current
            }
            val probed = probeExistingCandidateDirs(DEFAULT_CANDIDATE_PATHS.toSet())
            activeDirectories = probed
            return probed
        }

        private fun probeExistingCandidateDirs(candidatePaths: Set<String>): List<File> {
            val root = Environment.getExternalStorageDirectory()
            val deduplicated = LinkedHashMap<String, File>()

            fun registerIfValid(dir: File) {
                if (!dir.exists() || !dir.isDirectory || dir.name.startsWith(".")) return
                val key = try {
                    dir.canonicalPath.lowercase(Locale.ROOT)
                } catch (_: Exception) {
                    dir.absolutePath.lowercase(Locale.ROOT)
                }
                deduplicated.putIfAbsent(key, dir)
            }

            for (rawPath in candidatePaths) {
                val trimmed = rawPath.trim()
                if (trimmed.isEmpty()) continue
                val dir = if (trimmed.startsWith("/")) File(trimmed) else File(root, trimmed)
                registerIfValid(dir)
            }

            // DCIM, Pictures, Movies 직하위에 존재하는 대상 폴더들도 대소문자 무관하게 자동 탐색
            val targetNamesLower = setOf(
                "camera",
                "pictures",
                "screen recordings",
                "screen_recordings",
                "screenshots",
                "videocaptures",
                "video captures"
            )
            for (parentName in listOf("DCIM", "Pictures", "Movies")) {
                val parentDir = File(root, parentName)
                if (parentDir.exists() && parentDir.isDirectory) {
                    if (parentName.equals("Pictures", ignoreCase = true)) {
                        registerIfValid(parentDir)
                    }
                    parentDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.forEach { sub ->
                        if (targetNamesLower.contains(sub.name.lowercase(Locale.ROOT))) {
                            registerIfValid(sub)
                        }
                    }
                }
            }

            return deduplicated.values.toList()
        }

        /**
         * 파일 경로를 기반으로 5대 카테고리("Camera", "Pictures", "Screen recordings", "Screenshots", "Videocaptures")로 분류합니다.
         */
        fun resolveFolderCategory(file: File): String {
            val pathLower = file.absolutePath.lowercase(Locale.ROOT)
            val parentLower = (file.parentFile?.name ?: "").lowercase(Locale.ROOT)

            return when {
                pathLower.contains("screen recordings") ||
                    pathLower.contains("screen_recordings") ||
                    parentLower.contains("screen recording") -> "Screen recordings"
                pathLower.contains("screenshots") ||
                    parentLower.contains("screenshot") -> "Screenshots"
                pathLower.contains("videocaptures") ||
                    pathLower.contains("video captures") ||
                    parentLower.contains("videocapture") -> "Videocaptures"
                pathLower.contains("/camera/") ||
                    parentLower == "camera" -> "Camera"
                else -> "Pictures"
            }
        }

        fun computeSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(16384)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * 5대 폴더(Camera, Pictures, Screen recordings, Screenshots, Videocaptures)를 증분 스캔하여
     * Room DB의 `photo_media_cache`를 즉시 갱신합니다.
     * - 초기 목록 로딩 속도를 위해 파일 SHA-256 계산은 전송/해시대조 시점에 지연(Lazy) 수행하며,
     *   이미 계산된 해시는 파일 크기 및 수정시각이 동일하면 그대로 재사용합니다.
     */
    suspend fun refreshPhotoMediaCache(
        db: AppDatabase,
        prefs: AppPreferences? = null
    ): List<PhotoMediaCacheEntity> {
        val dao = db.syncCacheDao()
        val scanDirs = if (prefs != null) {
            initializeActiveDirectories(prefs)
        } else {
            getActiveDirectories()
        }

        val existingMap = dao.getAllPhotoMedia().associateBy { it.filePath }
        val discoveredMap = LinkedHashMap<String, File>()

        for (dir in scanDirs) {
            collectMediaFilesRecursively(dir, discoveredMap)
        }

        if (discoveredMap.isEmpty()) {
            dao.clearAllPhotoMedia()
            return emptyList()
        }

        val existingPaths = ArrayList<String>(discoveredMap.size)
        val toUpsert = ArrayList<PhotoMediaCacheEntity>()
        val verifiedNow = System.currentTimeMillis()

        for ((_, file) in discoveredMap) {
            val path = file.absolutePath
            existingPaths.add(path)
            val length = file.length()
            if (length <= 0L) continue
            val lastMod = file.lastModified()
            val category = resolveFolderCategory(file)
            val ext = file.extension.lowercase(Locale.ROOT)
            val mediaType = if (VIDEO_EXTENSIONS.contains(ext)) "video" else "photo"

            val cached = existingMap[path]
            if (cached != null && cached.fileSize == length && cached.lastModified == lastMod) {
                if (cached.folderCategory != category || (cached.isSynced && cached.syncedAt <= 0L)) {
                    toUpsert.add(
                        cached.copy(
                            folderCategory = category,
                            syncedAt = if (cached.isSynced && cached.syncedAt <= 0L) verifiedNow else cached.syncedAt
                        )
                    )
                }
                continue
            }

            val takenAt = parseTakenTimestamp(file, lastMod)
            val isSynced = cached?.isSynced == true
            val syncedAt = if (isSynced) {
                cached?.syncedAt?.takeIf { it > 0L } ?: verifiedNow
            } else 0L

            toUpsert.add(
                PhotoMediaCacheEntity(
                    filePath = path,
                    fileName = file.name,
                    folderCategory = category,
                    mediaType = mediaType,
                    fileSize = length,
                    lastModified = lastMod,
                    takenAt = takenAt,
                    fileHash = "",
                    isSynced = isSynced,
                    syncedAt = syncedAt
                )
            )
        }

        if (toUpsert.isNotEmpty()) {
            for (chunk in toUpsert.chunked(500)) {
                dao.upsertPhotoMedia(chunk)
            }
        }
        if (existingPaths.size <= 900) {
            dao.removeDeletedPhotoMedia(existingPaths)
        } else {
            val activeSet = existingPaths.toHashSet()
            val deletedPaths = existingMap.keys.filter { !activeSet.contains(it) }
            if (deletedPaths.isNotEmpty()) {
                val remaining = existingMap.values.filter { activeSet.contains(it.filePath) } + toUpsert
                dao.clearAllPhotoMedia()
                for (chunk in remaining.distinctBy { it.filePath }.chunked(500)) {
                    dao.upsertPhotoMedia(chunk)
                }
            }
        }
        dao.backfillMissingPhotoMediaSyncedAt(verifiedNow)

        return dao.getAllPhotoMedia()
    }

    /**
     * 전송 직전 또는 서버 해시 대조 시 파일의 SHA-256 해시가 비어 있으면 계산하여 DB에 캐싱합니다.
     */
    suspend fun ensureFileHash(entity: PhotoMediaCacheEntity, dao: SyncCacheDao): String {
        if (entity.fileHash.isNotBlank()) return entity.fileHash
        val file = File(entity.filePath)
        if (!file.exists() || !file.isFile) return ""
        return try {
            val hash = computeSha256(file)
            if (hash.isNotBlank()) {
                dao.updatePhotoMediaHash(entity.filePath, hash)
            }
            hash
        } catch (e: Exception) {
            Log.w(TAG, "Failed to hash media file: ${entity.fileName}", e)
            ""
        }
    }

    private fun collectMediaFilesRecursively(dir: File, outMap: LinkedHashMap<String, File>) {
        if (!dir.exists() || !dir.isDirectory || dir.name.startsWith(".")) return
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.name.startsWith(".")) continue
            if (file.isDirectory) {
                collectMediaFilesRecursively(file, outMap)
            } else if (file.isFile && SUPPORTED_EXTENSIONS.contains(file.extension.lowercase(Locale.ROOT))) {
                val dedupKey = try {
                    file.canonicalPath.lowercase(Locale.ROOT)
                } catch (_: Exception) {
                    file.absolutePath.lowercase(Locale.ROOT)
                }
                outMap.putIfAbsent(dedupKey, file)
            }
        }
    }

    /**
     * 파일명(예: 20261031_142305, Screenshot_20261031-142305, Screen_Recording_20261031_142305)에서
     * 촬영/생성 시각을 추출하고, 패턴이 없으면 lastModified를 반환합니다.
     */
    private fun parseTakenTimestamp(file: File, fallbackMs: Long): Long {
        val name = file.nameWithoutExtension
        val regex = Regex("""(20\d{6})[_-](\d{6})""")
        val match = regex.find(name)
        if (match != null) {
            val dateTimeStr = match.groupValues[1] + match.groupValues[2]
            try {
                val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.KOREA)
                val parsed = sdf.parse(dateTimeStr)?.time
                if (parsed != null && parsed > 0L) {
                    return parsed
                }
            } catch (_: Exception) {}
        }
        return fallbackMs
    }
}
