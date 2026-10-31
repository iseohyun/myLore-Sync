package com.iseohyun.mylore.sync.collector

import android.content.Context
import android.net.Uri
import android.util.Log
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.local.entity.SmsCacheEntity
import com.iseohyun.mylore.sync.data.remote.dto.SyncSmsItem
import java.io.BufferedReader
import java.io.InputStreamReader

class SmsAndMmsCollector(private val context: Context) {

    companion object {
        private const val TAG = "SmsAndMmsCollector"
        private const val MMS_ID_OFFSET = 1_000_000_000L
        private const val RCS_CHAT_ID_OFFSET = 2_000_000_000L
        private const val RCS_FT_ID_OFFSET = 3_000_000_000L
    }

    /**
     * 로컬 Room DB의 `sms_cache`를 활용하여 기존 문자는 즉시 표시하고,
     * 가장 마지막에 캐시된 시각(`maxCachedTs`) 이후의 신규 문자(SMS/MMS/RCS)만 증분 조회하여 추가합니다.
     */
    suspend fun refreshSmsCache(db: AppDatabase, lastSmsWatermarkTs: Long) {
        val dao = db.syncCacheDao()
        val maxCachedTs = dao.getMaxSmsTs() ?: 0L
        val newItems = collectSmsSince(maxCachedTs)
        if (newItems.isNotEmpty()) {
            val entities = newItems.map { item ->
                SmsCacheEntity(
                    id = item.id,
                    address = item.address,
                    body = item.body,
                    ts = item.ts,
                    type = item.type,
                    isSynced = lastSmsWatermarkTs > 0L && item.ts <= lastSmsWatermarkTs
                )
            }
            dao.insertSmsList(entities)
        }
        if (lastSmsWatermarkTs > 0L) {
            dao.markSmsSyncedUpTo(lastSmsWatermarkTs)
        }
    }

    /**
     * 안드로이드 MMS의 sub(제목) 컬럼이 ISO-8859-1(Latin-1) 바이트로 캐스팅되어 저장된 경우 UTF-8로 복원합니다.
     */
    private fun decodeMmsSubject(raw: String): String {
        if (raw.isBlank()) return ""
        val trimmed = raw.trim()
        return try {
            val bytes = trimmed.toByteArray(Charsets.ISO_8859_1)
            val utf8 = String(bytes, Charsets.UTF_8).trim()
            if (utf8.any { it in '\uAC00'..'\uD7A3' }) utf8 else trimmed
        } catch (_: Exception) {
            trimmed
        }
    }

    /**
     * sinceTs 이후의 SMS, MMS, RCS(채팅+)를 모두 수집하여 시간 순(ASC)으로 반환합니다.
     */
    fun collectSmsSince(sinceTs: Long): List<SyncSmsItem> {
        val resultList = mutableListOf<SyncSmsItem>()

        // 1. SMS 수집 (content://sms)
        collectSms(sinceTs, resultList)

        // 2. MMS 수집 (content://mms, content://mms/part)
        collectMms(sinceTs, resultList)

        // 3. 삼성 메시지 채팅+(RCS) 수집 (content://im/chat, content://im/ft)
        collectRcsChat(sinceTs, resultList)
        collectRcsFileTransfer(sinceTs, resultList)

        return resultList.sortedBy { it.ts }
    }

    private fun collectSms(sinceTs: Long, resultList: MutableList<SyncSmsItem>) {
        val uri = Uri.parse("content://sms")
        val projection = arrayOf("_id", "address", "body", "date", "type")
        val selection = "date > ?"
        val selectionArgs = arrayOf(sinceTs.toString())
        val sortOrder = "date ASC"

        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idIdx = cursor.getColumnIndex("_id")
                val addrIdx = cursor.getColumnIndex("address")
                val bodyIdx = cursor.getColumnIndex("body")
                val dateIdx = cursor.getColumnIndex("date")
                val typeIdx = cursor.getColumnIndex("type")

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val address = cursor.getString(addrIdx) ?: ""
                    val body = cursor.getString(bodyIdx) ?: ""
                    val date = cursor.getLong(dateIdx)
                    val typeInt = cursor.getInt(typeIdx)
                    val typeStr = if (typeInt == 2) "sent" else "inbox"

                    resultList.add(
                        SyncSmsItem(
                            id = id,
                            address = address,
                            body = body,
                            ts = date,
                            type = typeStr
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying SMS content resolver", e)
        }
    }

    private fun collectMms(sinceTs: Long, resultList: MutableList<SyncSmsItem>) {
        val uri = Uri.parse("content://mms")
        val projection = arrayOf("_id", "date", "msg_box", "sub")
        // MMS의 date 컬럼은 밀리초가 아닌 '초' 단위로 저장됨
        val sinceSec = sinceTs / 1000L
        val selection = "date > ?"
        val selectionArgs = arrayOf(sinceSec.toString())
        val sortOrder = "date ASC"

        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idIdx = cursor.getColumnIndex("_id")
                val dateIdx = cursor.getColumnIndex("date")
                val boxIdx = cursor.getColumnIndex("msg_box")
                val subIdx = cursor.getColumnIndex("sub")

                while (cursor.moveToNext()) {
                    val mmsId = cursor.getLong(idIdx)
                    val dateSec = cursor.getLong(dateIdx)
                    val dateMs = dateSec * 1000L
                    val box = cursor.getInt(boxIdx)
                    val typeStr = if (box == 2) "sent" else "inbox"
                    val rawSubject = decodeMmsSubject(cursor.getString(subIdx) ?: "")

                    val address = getMmsAddress(mmsId, box)
                    val (textBody, attachmentNotes) = getMmsParts(mmsId)

                    val fullBody = buildString {
                        if (
                            rawSubject.isNotBlank() &&
                            rawSubject != "제목없음" &&
                            rawSubject != "NoSubject" &&
                            !rawSubject.contains("ì œëª©ì—†ì Œ") &&
                            !rawSubject.startsWith("null")
                        ) {
                            append("[$rawSubject]\n")
                        }
                        if (attachmentNotes.isNotBlank()) {
                            append("$attachmentNotes\n")
                        }
                        append(textBody)
                    }.trim()

                    resultList.add(
                        SyncSmsItem(
                            id = MMS_ID_OFFSET + mmsId,
                            address = address,
                            body = fullBody.ifBlank { "[MMS 미디어 수신]" },
                            ts = dateMs,
                            type = typeStr
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MMS content resolver", e)
        }
    }

    private fun getMmsAddress(mmsId: Long, msgBox: Int): String {
        val uri = Uri.parse("content://mms/$mmsId/addr")
        // type 137 = FROM (발신자), type 151 = TO (수신자)
        val targetType = if (msgBox == 1) 137 else 151

        try {
            context.contentResolver.query(uri, arrayOf("address", "type"), null, null, null)?.use { cursor ->
                val addrCol = cursor.getColumnIndex("address")
                val typeCol = cursor.getColumnIndex("type")
                var fallbackAddr = ""

                while (cursor.moveToNext()) {
                    val type = cursor.getInt(typeCol)
                    val addr = cursor.getString(addrCol) ?: ""
                    if (addr.isNotBlank() && addr != "insert-address-token") {
                        if (type == targetType) {
                            return addr
                        }
                        if (fallbackAddr.isBlank()) {
                            fallbackAddr = addr
                        }
                    }
                }
                return fallbackAddr
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying MMS address for $mmsId", e)
        }
        return ""
    }

    private fun getMmsParts(mmsId: Long): Pair<String, String> {
        val uri = Uri.parse("content://mms/part")
        val projection = arrayOf("_id", "mid", "ct", "text", "name")
        val selection = "mid = ?"
        val selectionArgs = arrayOf(mmsId.toString())

        val textBuilder = StringBuilder()
        val attachmentBuilder = StringBuilder()

        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex("_id")
                val ctCol = cursor.getColumnIndex("ct")
                val textCol = cursor.getColumnIndex("text")
                val nameCol = cursor.getColumnIndex("name")

                while (cursor.moveToNext()) {
                    val partId = cursor.getLong(idCol)
                    val contentType = cursor.getString(ctCol) ?: ""
                    val name = cursor.getString(nameCol) ?: ""

                    if (contentType.startsWith("text/")) {
                        val partText = cursor.getString(textCol)
                        if (!partText.isNullOrBlank()) {
                            textBuilder.append(partText)
                        } else {
                            // 일부 제조사 단말은 text 컬럼이 비어있고 InputStream으로 저장됨
                            val streamText = readPartText(partId)
                            if (streamText.isNotBlank()) {
                                textBuilder.append(streamText)
                            }
                        }
                    } else if (contentType.startsWith("image/")) {
                        attachmentBuilder.append("[사진 첨부: ${name.ifBlank { "이미지" }}] ")
                    } else if (contentType.startsWith("video/")) {
                        attachmentBuilder.append("[동영상 첨부: ${name.ifBlank { "비디오" }}] ")
                    } else if (contentType.startsWith("audio/")) {
                        attachmentBuilder.append("[음성 첨부: ${name.ifBlank { "오디오" }}] ")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying MMS parts for $mmsId", e)
        }

        return Pair(textBuilder.toString().trim(), attachmentBuilder.toString().trim())
    }

    private fun readPartText(partId: Long): String {
        val partUri = Uri.parse("content://mms/part/$partId")
        return try {
            context.contentResolver.openInputStream(partUri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, "UTF-8")).use { reader ->
                    reader.readText()
                }
            } ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 삼성 메시지 앱 채팅+(RCS) 텍스트 및 리치카드 메시지 수집 (content://im/chat)
     */
    private fun collectRcsChat(sinceTs: Long, resultList: MutableList<SyncSmsItem>) {
        val uri = Uri.parse("content://im/chat")
        val selection = "inserted_timestamp > ?"
        val selectionArgs = arrayOf(sinceTs.toString())
        val sortOrder = "inserted_timestamp ASC"

        try {
            context.contentResolver.query(uri, null, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idIdx = cursor.getColumnIndex("_id")
                val remoteUriIdx = cursor.getColumnIndex("remote_uri")
                val bodyIdx = cursor.getColumnIndex("body")
                val tsIdx = cursor.getColumnIndex("inserted_timestamp")
                val dirIdx = cursor.getColumnIndex("direction")

                while (cursor.moveToNext()) {
                    val id = if (idIdx >= 0) cursor.getLong(idIdx) else continue
                    val remoteUri = if (remoteUriIdx >= 0) cursor.getString(remoteUriIdx) ?: "" else ""
                    val rawBody = if (bodyIdx >= 0) cursor.getString(bodyIdx) ?: "" else ""
                    val ts = if (tsIdx >= 0) cursor.getLong(tsIdx) else 0L
                    val dir = if (dirIdx >= 0) cursor.getInt(dirIdx) else 0

                    val address = remoteUri.removePrefix("tel:").removePrefix("+82").let {
                        if (it.startsWith("10") && it.length == 10) "0$it" else it
                    }.trim()
                    if (address.isBlank() || rawBody.isBlank()) continue

                    resultList.add(
                        SyncSmsItem(
                            id = RCS_CHAT_ID_OFFSET + id,
                            address = address,
                            body = rawBody.trim(),
                            ts = ts,
                            type = if (dir == 1) "sent" else "inbox"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "RCS chat provider not available or skipped: ${e.message}")
        }
    }

    /**
     * 삼성 메시지 앱 채팅+(RCS) 사진/파일 전송 내역 수집 (content://im/ft)
     */
    private fun collectRcsFileTransfer(sinceTs: Long, resultList: MutableList<SyncSmsItem>) {
        val uri = Uri.parse("content://im/ft")
        val selection = "inserted_timestamp > ?"
        val selectionArgs = arrayOf(sinceTs.toString())
        val sortOrder = "inserted_timestamp ASC"

        try {
            context.contentResolver.query(uri, null, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idIdx = cursor.getColumnIndex("_id")
                val remoteUriIdx = cursor.getColumnIndex("remote_uri")
                val fileNameIdx = cursor.getColumnIndex("file_name")
                val contentTypeIdx = cursor.getColumnIndex("content_type")
                val tsIdx = cursor.getColumnIndex("inserted_timestamp")
                val dirIdx = cursor.getColumnIndex("direction")

                while (cursor.moveToNext()) {
                    val id = if (idIdx >= 0) cursor.getLong(idIdx) else continue
                    val remoteUri = if (remoteUriIdx >= 0) cursor.getString(remoteUriIdx) ?: "" else ""
                    val fileName = if (fileNameIdx >= 0) cursor.getString(fileNameIdx) ?: "" else ""
                    val contentType = if (contentTypeIdx >= 0) cursor.getString(contentTypeIdx) ?: "" else ""
                    val ts = if (tsIdx >= 0) cursor.getLong(tsIdx) else 0L
                    val dir = if (dirIdx >= 0) cursor.getInt(dirIdx) else 0

                    val address = remoteUri.removePrefix("tel:").removePrefix("+82").let {
                        if (it.startsWith("10") && it.length == 10) "0$it" else it
                    }.trim()
                    if (address.isBlank()) continue

                    val label = when {
                        contentType.startsWith("image/") -> "[채팅+ 사진 첨부: ${fileName.ifBlank { "이미지" }}]"
                        contentType.startsWith("video/") -> "[채팅+ 동영상 첨부: ${fileName.ifBlank { "비디오" }}]"
                        contentType.startsWith("audio/") -> "[채팅+ 음성 첨부: ${fileName.ifBlank { "오디오" }}]"
                        else -> "[채팅+ 파일 첨부: ${fileName.ifBlank { "파일" }}]"
                    }

                    resultList.add(
                        SyncSmsItem(
                            id = RCS_FT_ID_OFFSET + id,
                            address = address,
                            body = label,
                            ts = ts,
                            type = if (dir == 1) "sent" else "inbox"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "RCS FT provider not available or skipped: ${e.message}")
        }
    }
}
