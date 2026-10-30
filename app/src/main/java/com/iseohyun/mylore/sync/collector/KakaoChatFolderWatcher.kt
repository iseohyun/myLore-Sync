package com.iseohyun.mylore.sync.collector

import android.os.Environment
import android.util.Log
import java.io.File
import java.security.MessageDigest

data class KakaoChatFileInfo(
    val folder: File,
    val folderName: String,
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
    val lastSharedTime: Long
)

class KakaoChatFolderWatcher {

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
                results.add(
                    KakaoChatFileInfo(
                        folder = folder,
                        folderName = folder.name,
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
     * UI 표시용: 모든 대화방(현재 및 과거 폴더)의 요약 정보 목록을 반환합니다.
     */
    fun scanRoomSummaries(): List<KakaoRoomSummary> {
        val summaries = mutableListOf<KakaoRoomSummary>()
        if (!targetFolder.exists() || !targetFolder.isDirectory) return summaries

        val subFolders = targetFolder.listFiles { f -> f.isDirectory } ?: return summaries
        for (folder in subFolders) {
            val txtFile = folder.listFiles { _, name -> name.endsWith(".txt") || name.endsWith(".csv") }?.maxByOrNull { it.lastModified() }
                ?: continue

            val roomKey = extractRoomKey(txtFile, folder.name)
            val (lastLine, _) = readLastLine(txtFile)

            summaries.add(
                KakaoRoomSummary(
                    roomName = roomKey,
                    folderName = folder.name,
                    file = txtFile,
                    lastMessage = lastLine.ifBlank { "대화 내용 없음" },
                    lastMessageTime = txtFile.lastModified(),
                    lastSharedTime = txtFile.lastModified() // PC 공유 시점
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
                // "{방이름} 님과 카카오톡 대화" 또는 "[방이름]" 파싱
                val match = Regex("""^(.*?)\s*(?:님과\s*카카오톡\s*대화)""").find(firstLine)
                match?.groupValues?.get(1)?.trim() ?: fallback
            }
        } catch (_: Exception) {
            fallback
        }
    }

    private fun calculateFileHash(file: File): String? {
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
