package com.iseohyun.mylore.sync.collector

import android.os.Environment
import android.util.Log
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.local.entity.RecordingCacheEntity
import com.iseohyun.mylore.sync.data.prefs.AppPreferences
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

data class CallRecordingInfo(
    val file: File,
    val fileHash: String,
    val phoneNumber: String,
    val contactName: String,
    val recordedAt: Long,
    val isSynced: Boolean = false
)

class CallRecordingCollector {

    companion object {
        private const val TAG = "CallRecordCollector"
        private val AUDIO_EXTENSIONS = setOf("m4a", "amr", "mp3", "wav", "3gp", "aac", "ogg", "flac", "mp4")

        /**
         * 통화/음성 녹음 탐색을 위한 기본 후보(참고) 폴더 리스트입니다.
         * 하드코딩된 직접 호출 대신 이 후보 리스트를 기준으로 앱 초기화 시 실제 존재 여부를 검증하여
         * 존재하는 폴더만 활성 조회 폴더(activeDirectories)로 지정합니다.
         */
        val DEFAULT_CANDIDATE_PATHS = listOf(
            "Recordings/Call",
            "Recordings/TPhoneCallRecords",
            "recordings/TPhoneCallRecords",
            "recordings/Call",
            "Recordings/Voice Recorder",
            "Recordings/Sounds",
            "TPhoneCallRecords",
            "Call",
            "Voice Recorder",
            "Sounds",
            "Music/TPhoneCallRecords"
        )

        @Volatile
        private var activeDirectories: List<File> = emptyList()

        /**
         * 애플리케이션 초기 실행 시(또는 설정 갱신 시) 후보 폴더 리스트를 순회하여
         * 기기에 실제 존재하는 폴더만 기본 조회 폴더(Active Scan Directories)로 지정하고 유지합니다.
         */
        suspend fun initializeActiveDirectories(prefs: AppPreferences? = null): List<File> {
            val candidatePaths = prefs?.getOrInitCandidateRecordingDirs(DEFAULT_CANDIDATE_PATHS)
                ?: DEFAULT_CANDIDATE_PATHS.toSet()

            val verifiedDirs = probeExistingCandidateDirs(candidatePaths)
            activeDirectories = verifiedDirs
            prefs?.saveActiveRecordingDirs(verifiedDirs.map { it.absolutePath }.toSet())
            Log.i(TAG, "Active recording directories initialized (${verifiedDirs.size}): ${verifiedDirs.map { it.absolutePath }}")
            return verifiedDirs
        }

        /**
         * 현재 지정된 기본 조회 폴더 목록을 반환합니다.
         * 아직 초기화되지 않았거나 비어 있으면 후보 리스트에서 즉시 존재 여부를 확인해 지정합니다.
         */
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
                if (!dir.exists() || !dir.isDirectory) return
                // Android 내장 스토리지(FUSE/sdcardfs)는 대소문자를 구분하지 않으므로
                // canonicalPath 및 소문자 경로 기준으로 중복 스캔을 방지합니다.
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

            // 내장 저장공간의 Recordings / recordings 루트 직하위에 존재하는 하위 폴더(예: TPhoneCallRecords 등)도 자동 탐색
            for (recRootName in listOf("Recordings", "recordings")) {
                val recRoot = File(root, recRootName)
                if (recRoot.exists() && recRoot.isDirectory) {
                    recRoot.listFiles { f -> f.isDirectory }?.forEach { subDir ->
                        registerIfValid(subDir)
                    }
                }
            }

            return deduplicated.values.toList()
        }

        /**
         * 통화 녹음 파일인지 일반 음성 녹음(Voice Recorder, 워치 음성메모 등)인지 판별합니다.
         */
        fun isCallRecording(rec: RecordingCacheEntity): Boolean {
            val pathLower = rec.filePath.lowercase(Locale.ROOT)
            val parentLower = rec.parentFolderName.lowercase(Locale.ROOT)
            val nameLower = rec.fileName.lowercase(Locale.ROOT)

            if (parentLower.contains("voice recorder") ||
                parentLower.contains("sounds") ||
                parentLower.contains("watch") ||
                rec.contactName == "Voice Recorder" ||
                rec.contactName == "Watch Voice Memo"
            ) {
                return false
            }

            if (pathLower.contains("/recordings/call") ||
                pathLower.contains("tphonecallrecords") ||
                parentLower.equals("tphonecallrecords", ignoreCase = true) ||
                parentLower == "call" ||
                nameLower.startsWith("#") ||
                nameLower.contains("통화 녹음") ||
                nameLower.contains("통화녹음") ||
                rec.phoneNumber.isNotBlank()
            ) {
                return true
            }

            return false
        }

        private fun normalizePhoneTail(raw: String): String {
            val digits = raw.filter { it.isDigit() }
            return if (digits.length >= 8) digits.takeLast(8) else digits
        }

        /**
         * 통화 기록(CallLog)과 단말 내 통화 녹음 파일을 하나의 논리적 이벤트로 매칭합니다.
         * - 전화번호 끝 8자리가 일치(또는 녹음 파일명에 번호가 없는 경우)하고,
         *   녹음 시각이 [통화 시작 - 30초 ~ 통화 종료 + 90초] 이내인 가장 가까운 통화 녹음을 반환합니다.
         */
        fun findMatchingCallRecording(
            call: com.iseohyun.mylore.sync.data.remote.dto.SyncCallLogItem,
            callRecordings: List<RecordingCacheEntity>
        ): RecordingCacheEntity? {
            if (call.durationSec <= 0L) return null
            val callStartMs = call.ts
            val callEndMs = call.ts + (call.durationSec * 1000L)
            val callPhoneTail = normalizePhoneTail(call.number)

            return callRecordings.filter { rec ->
                val recPhoneTail = normalizePhoneTail(rec.phoneNumber)
                val phoneMatches = callPhoneTail.isNotBlank() &&
                    recPhoneTail.isNotBlank() &&
                    callPhoneTail == recPhoneTail

                if (phoneMatches) {
                    rec.recordedAt in (callStartMs - 30_000L)..(callEndMs + 90_000L)
                } else if (recPhoneTail.isBlank()) {
                    rec.recordedAt in (callStartMs - 15_000L)..(callEndMs + 60_000L)
                } else {
                    false
                }
            }.minByOrNull { kotlin.math.abs(it.recordedAt - callStartMs) }
        }
    }

    /**
     * Room DB의 `recording_cache`를 활용하여 기존 파일의 SHA-256 재계산을 완전히 생략하고
     * 기본 조회 폴더(activeDirectories) 내 새로 추가되거나 변경된 녹음 파일만 증분 스캔합니다.
     *
     * 중요: 녹음 파일은 여러 폴더(Call, TPhoneCallRecords 등)에 분산 저장되므로
     * 단순 시각(`recordedAt <= lastRecordingTs`)으로 완료 처리하지 않고,
     * 오직 파일 해시(`SHA-256`) 기반 서버 대조 및 업로드 성공 여부로만 `isSynced`를 관리합니다.
     */
    suspend fun refreshRecordingsCache(
        db: AppDatabase,
        @Suppress("UNUSED_PARAMETER") lastRecordingTs: Long = 0L,
        prefs: AppPreferences? = null
    ): List<RecordingCacheEntity> {
        val dao = db.syncCacheDao()

        // 과거 워터마크 비교 버그로 인해 미전송 파일(예: TPhoneCallRecords)이 isSynced=true로 잘못 마킹된 경우 1회 리셋
        if (prefs != null && prefs.consumeRecordingHashOnlyResetNeeded()) {
            dao.resetAllRecordingsSyncedState()
        }

        val scanDirs = if (prefs != null) {
            initializeActiveDirectories(prefs)
        } else {
            getActiveDirectories()
        }

        val existingMap = dao.getAllRecordings().associateBy { it.filePath }

        val discoveredFiles = mutableListOf<File>()
        for (dir in scanDirs) {
            collectAudioFilesRecursively(dir, discoveredFiles)
        }

        if (discoveredFiles.isEmpty()) {
            dao.clearAllRecordings()
            return emptyList()
        }

        val existingPaths = mutableListOf<String>()
        val newlyScanned = mutableListOf<RecordingCacheEntity>()

        for (file in discoveredFiles) {
            val path = file.absolutePath
            existingPaths.add(path)
            val length = file.length()
            val lastMod = file.lastModified()
            val logicalFolder = resolveLogicalFolderName(file)

            val cached = existingMap[path]
            if (cached != null && cached.fileSize == length && cached.lastModified == lastMod && cached.fileHash.isNotBlank()) {
                val (phone, name, time) = parseRecordingMeta(file)
                // 폴더명이나 파일명 메타데이터(이름/전화번호/녹음시각)가 보정되었으면 해시 재계산 없이 메타만 업데이트
                if (cached.parentFolderName != logicalFolder ||
                    cached.phoneNumber != phone ||
                    cached.contactName != name ||
                    cached.recordedAt != time
                ) {
                    newlyScanned.add(
                        cached.copy(
                            parentFolderName = logicalFolder,
                            phoneNumber = phone,
                            contactName = name,
                            recordedAt = time
                        )
                    )
                }
                continue
            }

            try {
                val hash = computeSha256(file)
                val (phone, name, time) = parseRecordingMeta(file)
                val isSynced = cached?.isSynced == true
                val syncedAt = if (isSynced) {
                    cached?.syncedAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
                } else 0L
                newlyScanned.add(
                    RecordingCacheEntity(
                        filePath = path,
                        fileName = file.name,
                        parentFolderName = logicalFolder,
                        fileSize = length,
                        lastModified = lastMod,
                        fileHash = hash,
                        phoneNumber = phone,
                        contactName = name,
                        recordedAt = time,
                        isSynced = isSynced,
                        syncedAt = syncedAt
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error processing recording file: ${file.name}", e)
            }
        }

        if (newlyScanned.isNotEmpty()) {
            dao.upsertRecordings(newlyScanned)
        }
        dao.removeDeletedRecordings(existingPaths)
        dao.backfillMissingRecordingSyncedAt(System.currentTimeMillis())

        return dao.getAllRecordings()
    }

    /**
     * 기본 조회 폴더(activeDirectories)들을 탐색하여 유효한 음성 녹음 파일 목록을 최신순(내림차순)으로 반환합니다.
     */
    fun scanCallRecordings(): List<CallRecordingInfo> {
        val results = mutableListOf<CallRecordingInfo>()
        for (dir in getActiveDirectories()) {
            scanDirectoryRecursively(dir, results)
        }
        return results.sortedByDescending { it.recordedAt }
    }

    private fun resolveLogicalFolderName(file: File): String {
        val pathLower = file.absolutePath.lowercase(Locale.ROOT)
        return when {
            pathLower.contains("tphonecallrecords") -> "TPhoneCallRecords"
            pathLower.contains("voice recorder") -> "Voice Recorder"
            else -> file.parentFile?.name ?: ""
        }
    }

    private fun collectAudioFilesRecursively(dir: File, outFiles: MutableList<File>) {
        if (!dir.exists() || !dir.isDirectory) return
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                collectAudioFilesRecursively(file, outFiles)
            } else if (file.isFile && AUDIO_EXTENSIONS.contains(file.extension.lowercase(Locale.ROOT))) {
                outFiles.add(file)
            }
        }
    }

    private fun scanDirectoryRecursively(dir: File, results: MutableList<CallRecordingInfo>) {
        if (!dir.exists() || !dir.isDirectory) return
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                scanDirectoryRecursively(file, results)
            } else if (file.isFile && AUDIO_EXTENSIONS.contains(file.extension.lowercase(Locale.ROOT))) {
                try {
                    val hash = computeSha256(file)
                    val (phone, name, time) = parseRecordingMeta(file)
                    results.add(
                        CallRecordingInfo(
                            file = file,
                            fileHash = hash,
                            phoneNumber = phone,
                            contactName = name,
                            recordedAt = time
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing recording file: ${file.name}", e)
                }
            }
        }
    }

    private fun parseRecordingMeta(file: File): Triple<String, String, Long> {
        val nameWithoutExt = file.nameWithoutExtension
        var phone = ""
        var name = ""
        var timestamp = file.lastModified()

        // 1. T전화/에이닷 전화 포맷:
        //    - #이름_전화번호_YYYYMMDDHHMMSS (예: #거제해양윤주권차장님_01066067048_20261023122434)
        //    - 이름_전화번호_YYYYMMDDHHMMSS 또는 전화번호_YYYYMMDDHHMMSS
        val tPhone14DigitRegex = Regex("""(20\d{12})""")
        val tPhoneDateMatch = tPhone14DigitRegex.find(nameWithoutExt)
        if (nameWithoutExt.startsWith("#") || tPhoneDateMatch != null) {
            val trimmed = nameWithoutExt.removePrefix("#")
            val parts = trimmed.split("_").filter { it.isNotBlank() }
            if (tPhoneDateMatch != null) {
                try {
                    val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.KOREA)
                    timestamp = sdf.parse(tPhoneDateMatch.groupValues[1])?.time ?: timestamp
                } catch (_: Exception) {}
            }
            for (part in parts) {
                val digits = part.filter { it.isDigit() }
                if (tPhoneDateMatch != null && part.contains(tPhoneDateMatch.groupValues[1])) {
                    continue
                } else if (digits.startsWith("010") || digits.startsWith("02") || digits.length in 8..12) {
                    if (phone.isEmpty()) phone = digits
                } else if (part !in listOf("통화 녹음", "Call", "통화녹음", "TPhoneCallRecords") && name.isEmpty()) {
                    name = part
                }
            }
            if (nameWithoutExt.startsWith("#") || phone.isNotEmpty() || name.isNotEmpty()) {
                return Triple(phone, name, timestamp)
            }
        }

        // 2. 워치 음성 메모 포맷: 음성 001_W_YYYYMMDD_HHMMSS (예: 음성 001_W_20250404_140623)
        val watchRegex = Regex("""_W_(\d{8})_(\d{6})""")
        val watchMatch = watchRegex.find(nameWithoutExt)
        if (watchMatch != null) {
            val dateStr = watchMatch.groupValues[1] + watchMatch.groupValues[2]
            try {
                val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.KOREA)
                timestamp = sdf.parse(dateStr)?.time ?: timestamp
            } catch (_: Exception) {}
            name = "Watch Voice Memo"
            return Triple(phone, name, timestamp)
        }

        // 3. 삼성 기본 음성 녹음 포맷: 음성 YYMMDD_HHMMSS (예: 음성 260503_212528)
        val voiceRegex = Regex("""음성\s*(\d{6})_(\d{6})""")
        val voiceMatch = voiceRegex.find(nameWithoutExt)
        if (voiceMatch != null) {
            val dateStr = voiceMatch.groupValues[1] + voiceMatch.groupValues[2]
            try {
                val sdf = SimpleDateFormat("yyMMddHHmmss", Locale.KOREA)
                timestamp = sdf.parse(dateStr)?.time ?: timestamp
            } catch (_: Exception) {}
            name = "Voice Recorder"
            return Triple(phone, name, timestamp)
        }

        // 4. 삼성 일반 통화 녹음 포맷: 통화 녹음_이름_전화번호_YYMMDD_HHMMSS 또는 YYYYMMDD_HHMMSS
        val dateTimePairRegex = Regex("""(\d{6,8})[_-](\d{6})$""")
        val dtMatch = dateTimePairRegex.find(nameWithoutExt)
        if (dtMatch != null) {
            val dPart = dtMatch.groupValues[1]
            val tPart = dtMatch.groupValues[2]
            try {
                val pattern = if (dPart.length == 6) "yyMMddHHmmss" else "yyyyMMddHHmmss"
                val sdf = SimpleDateFormat(pattern, Locale.KOREA)
                timestamp = sdf.parse(dPart + tPart)?.time ?: timestamp
            } catch (_: Exception) {}
        }

        val parts = nameWithoutExt.split("_")
        for (part in parts) {
            val digits = part.filter { it.isDigit() }
            if (dtMatch != null && (part == dtMatch.groupValues[1] || part == dtMatch.groupValues[2])) {
                continue
            } else if (digits.startsWith("010") || digits.length in 9..11) {
                phone = digits
            } else if (dtMatch == null && part.length in 6..8 && part.all { it.isDigit() }) {
                try {
                    val pattern = if (part.length == 6) "yyMMdd" else "yyyyMMdd"
                    val sdf = SimpleDateFormat(pattern, Locale.KOREA)
                    timestamp = sdf.parse(part)?.time ?: timestamp
                } catch (_: Exception) {}
            } else if (part !in listOf("통화 녹음", "Call", "통화녹음") && part.length in 1..20) {
                if (name.isEmpty()) name = part
            }
        }

        return Triple(phone, name, timestamp)
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
