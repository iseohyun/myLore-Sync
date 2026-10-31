package com.iseohyun.mylore.sync.collector

import android.os.Environment
import android.util.Log
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.local.entity.RecordingCacheEntity
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
        private val AUDIO_EXTENSIONS = setOf("m4a", "amr", "mp3", "wav")
    }

    private fun getCandidateDirs(): List<File> = listOf(
        File(Environment.getExternalStorageDirectory(), "Recordings/Call"),
        File(Environment.getExternalStorageDirectory(), "Recordings/TPhoneCallRecords"),
        File(Environment.getExternalStorageDirectory(), "Recordings/Voice Recorder"),
        File(Environment.getExternalStorageDirectory(), "Recordings/Sounds"),
        File(Environment.getExternalStorageDirectory(), "Call"),
        File(Environment.getExternalStorageDirectory(), "Voice Recorder")
    )

    /**
     * Room DB의 `recording_cache`를 활용하여 기존 파일의 SHA-256 재계산을 완전히 생략하고
     * 새로 추가되거나 변경된 녹음 파일만 증분 스캔합니다.
     */
    suspend fun refreshRecordingsCache(
        db: AppDatabase,
        lastRecordingTs: Long = 0L
    ): List<RecordingCacheEntity> {
        val dao = db.syncCacheDao()
        val existingMap = dao.getAllRecordings().associateBy { it.filePath }

        val discoveredFiles = mutableListOf<File>()
        for (dir in getCandidateDirs()) {
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

            val cached = existingMap[path]
            if (cached != null && cached.fileSize == length && cached.lastModified == lastMod && cached.fileHash.isNotBlank()) {
                // 이미 캐시된 파일: 워터마크 기준으로 동기화 상태가 새로 완료되었으면 업데이트
                if (!cached.isSynced && lastRecordingTs > 0L && cached.recordedAt <= lastRecordingTs) {
                    newlyScanned.add(cached.copy(isSynced = true))
                }
                continue
            }

            try {
                val hash = computeSha256(file)
                val (phone, name, time) = parseRecordingMeta(file)
                val isSynced = cached?.isSynced == true || (lastRecordingTs > 0L && time <= lastRecordingTs)
                newlyScanned.add(
                    RecordingCacheEntity(
                        filePath = path,
                        fileName = file.name,
                        parentFolderName = file.parentFile?.name ?: "",
                        fileSize = length,
                        lastModified = lastMod,
                        fileHash = hash,
                        phoneNumber = phone,
                        contactName = name,
                        recordedAt = time,
                        isSynced = isSynced
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

        return dao.getAllRecordings()
    }

    /**
     * 통화 녹음 및 음성 메모 폴더들을 탐색하여 유효한 음성 녹음 파일 목록을 최신순(내림차순)으로 반환합니다.
     */
    fun scanCallRecordings(): List<CallRecordingInfo> {
        val results = mutableListOf<CallRecordingInfo>()
        for (dir in getCandidateDirs()) {
            scanDirectoryRecursively(dir, results)
        }
        return results.sortedByDescending { it.recordedAt }
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

        // 1. T전화 포맷: #이름_전화번호_YYYYMMDDHHMMSS (예: #거제해양윤주권차장님_01066067048_20261023122434)
        if (nameWithoutExt.startsWith("#")) {
            val trimmed = nameWithoutExt.removePrefix("#")
            val parts = trimmed.split("_")
            if (parts.size >= 3) {
                name = parts[0]
                phone = parts[1].filter { it.isDigit() }
                val datePart = parts[2]
                if (datePart.length >= 14 && datePart.all { it.isDigit() }) {
                    try {
                        val sdf = SimpleDateFormat("yyyyMMddHHmmss", Locale.KOREA)
                        timestamp = sdf.parse(datePart.substring(0, 14))?.time ?: timestamp
                    } catch (_: Exception) {}
                }
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

        // 4. 삼성 일반 통화 녹음 포맷: 통화 녹음_이름_전화번호_YYMMDD_HHMMSS 또는 통화 녹음_전화번호_YYMMDD_HHMMSS
        val parts = nameWithoutExt.split("_")
        for (part in parts) {
            val digits = part.filter { it.isDigit() }
            if (digits.startsWith("010") || digits.length in 9..11) {
                phone = digits
            } else if (part.length in 6..8 && part.all { it.isDigit() }) {
                try {
                    val pattern = if (part.length == 6) "yyMMdd" else "yyyyMMdd"
                    val sdf = SimpleDateFormat(pattern, Locale.KOREA)
                    timestamp = sdf.parse(part)?.time ?: timestamp
                } catch (_: Exception) {}
            } else if (part !in listOf("통화 녹음", "Call", "통화녹음") && part.length in 2..15) {
                name = part
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
