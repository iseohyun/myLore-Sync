package com.iseohyun.mylore.sync.collector

import android.os.Environment
import android.util.Log
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.local.entity.KakaoRoomCacheEntity
import com.iseohyun.mylore.sync.data.prefs.AppPreferences
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class KakaoChatFileInfo(
    val folder: File,
    val folderName: String,
    val roomKey: String,
    val file: File,
    val mediaFiles: List<File>,
    val fileHash: String,
    val modifiedAt: Long,
    val lastMessage: String = "",
    val lastMessageTime: Long = 0L
)

data class KakaoRoomSummary(
    val roomName: String,
    val folderName: String,
    val file: File?,
    val lastMessage: String,
    val lastMessageTime: Long,
    val lastSharedTime: Long,
    val totalBytes: Long = 0L,
    val textBytes: Long = 0L,
    val mediaBytes: Long = 0L,
    val mediaCount: Int = 0,
    val syncedBytes: Long = 0L,
    val percent: Int = 0,
    val isOnDevice: Boolean = true
)

class KakaoChatFolderWatcher {

    companion object {
        fun formatFileSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
            val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
            return if (digitGroups == 0) {
                "$bytes B"
            } else {
                String.format(Locale.US, "%.1f %s", value, units[digitGroups])
            }
        }

        fun calculateFileHash(file: File): String? {
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { inputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                Log.e("KakaoChatWatcher", "Error hashing file: ${file.name}", e)
                null
            }
        }
    }

    private val targetFolder = File(
        Environment.getExternalStorageDirectory(),
        "Documents/KakaoTalk/Chats"
    )

    fun scanBackupFiles(): List<KakaoChatFileInfo> {
        val results = mutableListOf<KakaoChatFileInfo>()

        if (!targetFolder.exists() || !targetFolder.isDirectory) {
            Log.d("KakaoChatWatcher", "KakaoTalk Chats folder does not exist: ${targetFolder.absolutePath}")
            return results
        }

        val subFolders = targetFolder.listFiles { f -> f.isDirectory } ?: return results

        for (folder in subFolders) {
            val allFiles = folder.listFiles { f -> f.isFile } ?: continue
            val txtFiles = allFiles.filter {
                it.name.endsWith(".txt", ignoreCase = true) || it.name.endsWith(".csv", ignoreCase = true)
            }
            val mediaFiles = allFiles.filter {
                !it.name.endsWith(".txt", ignoreCase = true) && !it.name.endsWith(".csv", ignoreCase = true)
            }
            for (txtFile in txtFiles) {
                val hash = calculateFileHash(txtFile) ?: continue
                val roomKey = extractRoomKey(txtFile, folder.name)
                val (lastLine, lastMsgTs) = readLastLineFast(txtFile)
                results.add(
                    KakaoChatFileInfo(
                        folder = folder,
                        folderName = folder.name,
                        roomKey = roomKey,
                        file = txtFile,
                        mediaFiles = mediaFiles,
                        fileHash = hash,
                        modifiedAt = txtFile.lastModified(),
                        lastMessage = lastLine,
                        lastMessageTime = lastMsgTs
                    )
                )
            }
        }

        return results.sortedBy { it.modifiedAt }
    }

    /**
     * 로컬 Room DB 캐시를 활용한 초고속 증분 갱신:
     * 1) 변경되지 않은 대화방 폴더는 파일 전체 재파싱을 생략하고 캐시를 재사용합니다.
     * 2) 이미 전송 완료되어 삭제된 폴더도 캐시에 `isOnDevice = false`로 유지하여
     *    "대화방명(상대)"과 "가장 마지막 대화 시각"을 즉시 표시합니다.
     */
    suspend fun refreshKakaoRoomCache(
        db: AppDatabase,
        prefs: AppPreferences? = null
    ): List<KakaoRoomCacheEntity> {
        val dao = db.syncCacheDao()
        val existingList = dao.getAllKakaoRooms()
        val existingMap = existingList.associateBy { it.roomName }

        if (!targetFolder.exists() || !targetFolder.isDirectory) {
            dao.markAllKakaoRoomsDeletedOnDevice()
            return dao.getAllKakaoRooms()
        }

        val subFolders = targetFolder.listFiles { f -> f.isDirectory }
        if (subFolders.isNullOrEmpty()) {
            dao.markAllKakaoRoomsDeletedOnDevice()
            return dao.getAllKakaoRooms()
        }

        // 동일 대화방의 내보내기 폴더가 여러 개면 가장 최신 폴더만 선택
        val latestFolderByRoom = mutableMapOf<String, Triple<File, File, List<File>>>()
        for (folder in subFolders) {
            val allFiles = folder.listFiles { f -> f.isFile } ?: emptyArray()
            val txtFile = allFiles.filter {
                it.name.endsWith(".txt", ignoreCase = true) || it.name.endsWith(".csv", ignoreCase = true)
            }.maxByOrNull { it.lastModified() } ?: continue

            val mediaFiles = allFiles.filter {
                !it.name.endsWith(".txt", ignoreCase = true) && !it.name.endsWith(".csv", ignoreCase = true)
            }
            val roomKey = extractRoomKey(txtFile, folder.name)
            val prev = latestFolderByRoom[roomKey]
            if (prev == null || txtFile.lastModified() > prev.second.lastModified()) {
                latestFolderByRoom[roomKey] = Triple(folder, txtFile, mediaFiles)
            }
        }

        val toUpsert = mutableListOf<KakaoRoomCacheEntity>()
        val activeRoomNames = latestFolderByRoom.keys.toList()

        for ((roomKey, triple) in latestFolderByRoom) {
            val (folder, txtFile, mediaFiles) = triple
            val textBytes = txtFile.length()
            val mediaBytes = mediaFiles.sumOf { it.length() }
            val totalBytes = textBytes + mediaBytes
            val mediaCount = mediaFiles.size
            val fileLastMod = txtFile.lastModified()

            val cached = existingMap[roomKey]

            var syncedBytes = cached?.syncedBytes ?: 0L
            var lastSharedTime = cached?.lastSharedTime ?: 0L
            if (prefs != null) {
                val syncState = prefs.getRoomSyncProgress(roomKey)
                if (syncState.syncedBytes > 0L || syncState.lastSharedTs > 0L) {
                    syncedBytes = maxOf(syncedBytes, syncState.syncedBytes)
                    lastSharedTime = maxOf(lastSharedTime, syncState.lastSharedTs)
                }
            }

            // 파일 수정 시각과 전체 크기가 동일하면 무거운 텍스트 파싱 생략
            val (lastMsg, lastMsgTime) = if (
                cached != null &&
                cached.fileLastModified == fileLastMod &&
                cached.totalBytes == totalBytes &&
                cached.lastMessage.isNotBlank()
            ) {
                Pair(cached.lastMessage, cached.lastMessageTime)
            } else {
                val (line, ts) = readLastLineFast(txtFile)
                Pair(line.ifBlank { "대화 내용 없음" }, ts)
            }

            val percent = when {
                totalBytes <= 0L -> 100
                syncedBytes >= totalBytes -> 100
                syncedBytes > 0L -> ((syncedBytes.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)
                else -> 0
            }

            toUpsert.add(
                KakaoRoomCacheEntity(
                    roomName = roomKey,
                    folderName = folder.name,
                    filePath = txtFile.absolutePath,
                    fileLastModified = fileLastMod,
                    lastMessage = lastMsg,
                    lastMessageTime = lastMsgTime,
                    lastSharedTime = lastSharedTime,
                    totalBytes = totalBytes,
                    textBytes = textBytes,
                    mediaBytes = mediaBytes,
                    mediaCount = mediaCount,
                    syncedBytes = syncedBytes,
                    percent = percent,
                    isOnDevice = true
                )
            )
        }

        if (toUpsert.isNotEmpty()) {
            dao.upsertKakaoRooms(toUpsert)
        }
        if (activeRoomNames.isNotEmpty()) {
            dao.markInactiveKakaoRoomsDeleted(activeRoomNames)
        } else {
            dao.markAllKakaoRoomsDeletedOnDevice()
        }

        return dao.getAllKakaoRooms()
    }

    /**
     * RandomAccessFile을 이용해 파일 끝부분(최대 16KB)만 읽어 O(1) 속도로
     * 마지막 대화 내용과 실제 마지막 대화 시각을 추출합니다.
     */
    fun readLastLineFast(file: File): Pair<String, Long> {
        val fallbackTs = file.lastModified()
        return try {
            val length = file.length()
            if (length <= 0L) return Pair("", fallbackTs)

            val readSize = minOf(length, 16_384L).toInt()
            val buffer = ByteArray(readSize)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(length - readSize)
                raf.readFully(buffer)
            }

            val tailText = String(buffer, Charsets.UTF_8)
            val lines = tailText.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("---------------") }
                .toList()

            val lastLine = lines.lastOrNull() ?: return Pair("", fallbackTs)

            // 뒤에서부터 날짜/시각 패턴이 포함된 줄 탐색
            var parsedTs = 0L
            for (i in lines.indices.reversed()) {
                val ts = parseKakaoLineTimestamp(lines[i])
                if (ts > 0L) {
                    parsedTs = ts
                    break
                }
            }

            Pair(lastLine, if (parsedTs > 0L) parsedTs else fallbackTs)
        } catch (_: Exception) {
            Pair("", fallbackTs)
        }
    }

    /**
     * 안드로이드/PC 카카오톡 내보내기 줄에서 마지막 대화 시각(ms)을 파싱합니다.
     * 예: "2026년 10월 28일 오후 9:12, 홍길동 : 안녕하세요"
     * 예: "2026. 10. 28. 오후 9:12, 홍길동 : 안녕하세요"
     */
    private fun parseKakaoLineTimestamp(line: String): Long {
        try {
            // 패턴 1: 2026년 10월 28일 오후 9:12
            val krRegex = Regex("""(\d{4})년\s*(\d{1,2})월\s*(\d{1,2})일\s*(오전|오후)\s*(\d{1,2}):(\d{2})""")
            val krMatch = krRegex.find(line)
            if (krMatch != null) {
                val year = krMatch.groupValues[1].toInt()
                val month = krMatch.groupValues[2].toInt() - 1
                val day = krMatch.groupValues[3].toInt()
                val amPm = krMatch.groupValues[4]
                var hour = krMatch.groupValues[5].toInt()
                val minute = krMatch.groupValues[6].toInt()
                if (amPm == "오후" && hour < 12) hour += 12
                if (amPm == "오전" && hour == 12) hour = 0

                val cal = Calendar.getInstance()
                cal.set(year, month, day, hour, minute, 0)
                cal.set(Calendar.MILLISECOND, 0)
                return cal.timeInMillis
            }

            // 패턴 2: 2026. 10. 28. 오후 9:12 또는 2026. 10. 28. 21:12
            val dotRegex = Regex("""(\d{4})\.\s*(\d{1,2})\.\s*(\d{1,2})\.\s*(?:(오전|오후)\s*)?(\d{1,2}):(\d{2})""")
            val dotMatch = dotRegex.find(line)
            if (dotMatch != null) {
                val year = dotMatch.groupValues[1].toInt()
                val month = dotMatch.groupValues[2].toInt() - 1
                val day = dotMatch.groupValues[3].toInt()
                val amPm = dotMatch.groupValues[4]
                var hour = dotMatch.groupValues[5].toInt()
                val minute = dotMatch.groupValues[6].toInt()
                if (amPm == "오후" && hour < 12) hour += 12
                if (amPm == "오전" && hour == 12) hour = 0

                val cal = Calendar.getInstance()
                cal.set(year, month, day, hour, minute, 0)
                cal.set(Calendar.MILLISECOND, 0)
                return cal.timeInMillis
            }

            // 패턴 3: 2026-10-28 21:12:34
            val isoRegex = Regex("""(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}(?::\d{2})?)""")
            val isoMatch = isoRegex.find(line)
            if (isoMatch != null) {
                val str = isoMatch.groupValues[1]
                val pattern = if (str.count { it == ':' } == 2) "yyyy-MM-dd HH:mm:ss" else "yyyy-MM-dd HH:mm"
                val sdf = SimpleDateFormat(pattern, Locale.KOREA)
                return sdf.parse(str)?.time ?: 0L
            }
        } catch (_: Exception) {}
        return 0L
    }

    private fun extractRoomKey(file: File, fallback: String): String {
        return try {
            file.bufferedReader().use { reader ->
                val firstLine = reader.readLine() ?: return fallback
                // 1) "{방이름} 님과 카카오톡 대화"
                val matchWithNim = Regex("""^(.*?)\s*(?:님과\s*카카오톡\s*대화)""").find(firstLine)
                if (matchWithNim != null) {
                    return matchWithNim.groupValues[1].trim()
                }
                // 2) "{방이름} 카카오톡 대화"
                val matchGeneral = Regex("""^(.*?)\s*카카오톡\s*대화""").find(firstLine)
                if (matchGeneral != null) {
                    return matchGeneral.groupValues[1].trim()
                }
                // 3) "[방이름]" 형태
                val matchBracket = Regex("""^\[(.*?)\]""").find(firstLine)
                if (matchBracket != null) {
                    return matchBracket.groupValues[1].trim()
                }
                fallback
            }
        } catch (_: Exception) {
            fallback
        }
    }
}
