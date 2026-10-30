package com.iseohyun.mylore.sync.collector

import android.os.Environment
import android.util.Log
import com.iseohyun.mylore.sync.data.prefs.AppPreferences
import java.io.File
import java.security.MessageDigest
import java.util.Locale

data class KakaoChatFileInfo(
    val folder: File,
    val folderName: String,
    val roomKey: String,
    val file: File,
    val mediaFiles: List<File>,
    val fileHash: String,
    val modifiedAt: Long
)

data class KakaoRoomSummary(
    val roomName: String,
    val folderName: String,
    val file: File,
    val lastMessage: String,
    val lastMessageTime: Long,
    val lastSharedTime: Long,
    val totalBytes: Long = 0L,
    val textBytes: Long = 0L,
    val mediaBytes: Long = 0L,
    val mediaCount: Int = 0,
    val syncedBytes: Long = 0L,
    val percent: Int = 0
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
                results.add(
                    KakaoChatFileInfo(
                        folder = folder,
                        folderName = folder.name,
                        roomKey = roomKey,
                        file = txtFile,
                        mediaFiles = mediaFiles,
                        fileHash = hash,
                        modifiedAt = txtFile.lastModified()
                    )
                )
            }
        }

        return results.sortedBy { it.modifiedAt }
    }

    /**
     * UI 표시용: 모든 대화방의 요약 정보, 공유 대상 용량 및 실제 전송된 진행률(%)을 반환합니다.
     */
    suspend fun scanRoomSummaries(prefs: AppPreferences? = null): List<KakaoRoomSummary> {
        val summaries = mutableListOf<KakaoRoomSummary>()
        if (!targetFolder.exists() || !targetFolder.isDirectory) return summaries

        val subFolders = targetFolder.listFiles { f -> f.isDirectory } ?: return summaries

        for (folder in subFolders) {
            val allFiles = folder.listFiles { f -> f.isFile } ?: emptyArray()
            val txtFile = allFiles.filter { it.name.endsWith(".txt") || it.name.endsWith(".csv") }
                .maxByOrNull { it.lastModified() } ?: continue

            val mediaFiles = allFiles.filter {
                !it.name.endsWith(".txt", ignoreCase = true) && !it.name.endsWith(".csv", ignoreCase = true)
            }

            val roomKey = extractRoomKey(txtFile, folder.name)
            val (lastLine, _) = readLastLine(txtFile)

            val textBytes = txtFile.length()
            val mediaBytes = mediaFiles.sumOf { it.length() }
            val totalBytes = textBytes + mediaBytes
            val mediaCount = mediaFiles.size

            var syncedBytes = 0L
            var lastSharedTime = 0L

            if (prefs != null) {
                val syncState = prefs.getRoomSyncProgress(roomKey)
                syncedBytes = syncState.syncedBytes
                lastSharedTime = syncState.lastSharedTs
            }

            // 실제 서버 전송된 바이트를 기준으로만 % 계산 (가짜 100% 추정 로직 완전 제거)
            val percent = when {
                totalBytes <= 0L -> 100
                syncedBytes >= totalBytes -> 100
                syncedBytes > 0L -> ((syncedBytes.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)
                else -> 0
            }

            summaries.add(
                KakaoRoomSummary(
                    roomName = roomKey,
                    folderName = folder.name,
                    file = txtFile,
                    lastMessage = lastLine.ifBlank { "대화 내용 없음" },
                    lastMessageTime = txtFile.lastModified(),
                    lastSharedTime = lastSharedTime,
                    totalBytes = totalBytes,
                    textBytes = textBytes,
                    mediaBytes = mediaBytes,
                    mediaCount = mediaCount,
                    syncedBytes = syncedBytes,
                    percent = percent
                )
            )
        }

        return summaries.sortedByDescending { it.lastMessageTime }
    }

    private fun readLastLine(file: File): Pair<String, Long> {
        return try {
            file.bufferedReader().useLines { lines ->
                var last = ""
                for (line in lines) {
                    if (line.isNotBlank() && !line.startsWith("---------------")) {
                        last = line
                    }
                }
                Pair(last, file.lastModified())
            }
        } catch (_: Exception) {
            Pair("", file.lastModified())
        }
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
