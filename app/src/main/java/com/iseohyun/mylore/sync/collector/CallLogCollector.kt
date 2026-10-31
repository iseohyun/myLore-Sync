package com.iseohyun.mylore.sync.collector

import android.content.Context
import android.provider.CallLog
import android.util.Log
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.local.entity.CallLogCacheEntity
import com.iseohyun.mylore.sync.data.remote.dto.SyncCallLogItem

class CallLogCollector(private val context: Context) {

    /**
     * 로컬 Room DB의 `call_log_cache`를 활용하여 기존 통화 이력은 즉시 표시하고,
     * 가장 마지막에 캐시된 시각(`maxCachedTs`) 이후의 신규 통화만 증분 조회하여 추가합니다.
     */
    suspend fun refreshCallLogCache(db: AppDatabase, lastCallWatermarkTs: Long) {
        val dao = db.syncCacheDao()
        val maxCachedTs = dao.getMaxCallLogTs() ?: 0L
        val newLogs = collectCallLogsSince(maxCachedTs)
        if (newLogs.isNotEmpty()) {
            val entities = newLogs.map { item ->
                CallLogCacheEntity(
                    id = item.id,
                    number = item.number,
                    name = item.name,
                    callType = item.callType,
                    durationSec = item.durationSec,
                    ts = item.ts,
                    isSynced = lastCallWatermarkTs > 0L && item.ts <= lastCallWatermarkTs
                )
            }
            dao.insertCallLogs(entities)
        }
        if (lastCallWatermarkTs > 0L) {
            dao.markCallLogsSyncedUpTo(lastCallWatermarkTs)
        }
    }

    fun collectCallLogsSince(sinceTs: Long): List<SyncCallLogItem> {
        val callLogs = mutableListOf<SyncCallLogItem>()
        val uri = CallLog.Calls.CONTENT_URI
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DURATION,
            CallLog.Calls.DATE
        )
        val selection = "${CallLog.Calls.DATE} > ?"
        val selectionArgs = arrayOf(sinceTs.toString())
        val sortOrder = "${CallLog.Calls.DATE} ASC"

        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(CallLog.Calls._ID)
                val numberIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val durationIdx = cursor.getColumnIndex(CallLog.Calls.DURATION)
                val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val number = cursor.getString(numberIdx) ?: ""
                    val name = cursor.getString(nameIdx) ?: ""
                    val typeInt = cursor.getInt(typeIdx)
                    val duration = cursor.getLong(durationIdx)
                    val date = cursor.getLong(dateIdx)

                    val typeStr = when (typeInt) {
                        CallLog.Calls.INCOMING_TYPE -> "incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                        CallLog.Calls.MISSED_TYPE -> "missed"
                        CallLog.Calls.REJECTED_TYPE -> "rejected"
                        else -> "unknown"
                    }

                    callLogs.add(
                        SyncCallLogItem(
                            id = id,
                            number = number,
                            name = name,
                            callType = typeStr,
                            durationSec = duration,
                            ts = date
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("CallLogCollector", "Error querying CallLog content resolver", e)
        }
        return callLogs
    }
}
