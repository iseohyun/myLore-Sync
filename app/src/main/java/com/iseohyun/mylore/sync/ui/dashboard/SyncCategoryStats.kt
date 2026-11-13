package com.iseohyun.mylore.sync.ui.dashboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 메인(상태) 화면의 "분류별 전송 현황" 막대 그래프용 통계.
 *
 * - [total] / [synced]: 폰이 보유한 전체 건수와 그중 PC 전송이 끝난 건수 (막대 = 전체 완성률)
 * - [recentSynced]: 선택한 기간([StatPeriod]) 안에 전송된 건수
 *
 * 분류마다 "전송 시각"을 기록하는 방식이 달라 기간 집계 기준이 조금씩 다릅니다.
 * 통화·문자·녹음·사진은 전송 시각(syncedAt), 카톡·헬스는 마지막 공유 시각을 쓰고,
 * 전송 시각을 따로 남기지 않는 연락처·알림·GPS는 항목 자체의 발생 시각으로 근사합니다.
 */
data class SyncCategoryStat(
    val key: String,
    val icon: String,
    val label: String,
    val total: Int,
    val synced: Int,
    val recentSynced: Int
) {
    /** 전체 완성률(0.0 ~ 1.0). 보유 건수가 없으면 보낼 것이 없으므로 완료(1.0)로 봅니다. */
    val completionRatio: Float
        get() = if (total <= 0) 1f else (synced.toFloat() / total).coerceIn(0f, 1f)
}

enum class StatPeriod(val label: String) {
    TODAY("오늘"),
    THIS_WEEK("이번주"),
    THIS_MONTH("이번달"),
    ALL("모두");

    /** 기간 시작 시각(ms). 이번주는 월요일 0시, 이번달은 1일 0시 기준이며 [ALL]은 0입니다. */
    fun startMillis(nowMs: Long = System.currentTimeMillis()): Long {
        if (this == ALL) return 0L
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        when (this) {
            THIS_WEEK -> {
                // 일요일(1)은 6일 전, 월요일(2)은 0일 전 ...
                val daysSinceMonday = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
                cal.add(Calendar.DAY_OF_YEAR, -daysSinceMonday)
            }
            THIS_MONTH -> cal.set(Calendar.DAY_OF_MONTH, 1)
            else -> Unit
        }
        return cal.timeInMillis
    }
}

/** `health_gps_points` 중 PC로 올리는 대상(실시간 수집분)만 고르는 조건. HealthGpsDao.getRealtimePointsAfterId와 동일 */
private const val GPS_REALTIME_FILTER =
    "(sourceFileName IN ('passive_gps', 'map_gps', 'outing_gps') " +
        "OR substr(sourceFileName, 1, 5) = 'wifi_' OR substr(sourceFileName, 1, 3) = 'os_')"

suspend fun loadSyncCategoryStats(
    context: Context,
    db: AppDatabase,
    prefs: AppPreferences,
    period: StatPeriod
): List<SyncCategoryStat> = withContext(Dispatchers.IO) {
    val since = period.startMillis()
    val sqlite = db.openHelper.readableDatabase

    fun count(sql: String, vararg args: Any): Int {
        return try {
            sqlite.query(sql, args).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        } catch (_: Exception) {
            0
        }
    }

    /** isSynced / syncedAt 컬럼을 가진 캐시 테이블 공통 집계 */
    fun cacheTableStat(key: String, icon: String, label: String, table: String): SyncCategoryStat {
        return SyncCategoryStat(
            key = key,
            icon = icon,
            label = label,
            total = count("SELECT COUNT(*) FROM $table"),
            synced = count("SELECT COUNT(*) FROM $table WHERE isSynced = 1"),
            recentSynced = count("SELECT COUNT(*) FROM $table WHERE isSynced = 1 AND syncedAt >= ?", since)
        )
    }

    val kakaoDone = "(isOnDevice = 0 OR percent >= 100)"
    val kakao = SyncCategoryStat(
        key = "kakao",
        icon = "💬",
        label = "카톡",
        total = count("SELECT COUNT(*) FROM kakao_room_cache"),
        synced = count("SELECT COUNT(*) FROM kakao_room_cache WHERE $kakaoDone"),
        recentSynced = count("SELECT COUNT(*) FROM kakao_room_cache WHERE $kakaoDone AND lastSharedTime >= ?", since)
    )

    val health = SyncCategoryStat(
        key = "health",
        icon = "🏃",
        label = "헬스",
        total = count("SELECT COUNT(*) FROM health_sync_cache"),
        synced = count("SELECT COUNT(*) FROM health_sync_cache WHERE isSynced = 1"),
        recentSynced = count("SELECT COUNT(*) FROM health_sync_cache WHERE isSynced = 1 AND lastSharedTime >= ?", since)
    )

    // 알림은 전송 확정 후 DB에서 삭제되므로, 수집 로그(sync_logs) 건수에서 아직 남은 대기 건수를 빼서 계산합니다.
    val notiPending = count("SELECT COUNT(*) FROM pending_notifications WHERE isSynced = 0")
    val notiCaptured = count("SELECT COUNT(*) FROM sync_logs WHERE type = 'NOTIFICATION'")
    val notiTotal = maxOf(notiCaptured, notiPending)
    val notiPendingRecent = count("SELECT COUNT(*) FROM pending_notifications WHERE isSynced = 0 AND postedAt >= ?", since)
    val notiCapturedRecent = count("SELECT COUNT(*) FROM sync_logs WHERE type = 'NOTIFICATION' AND timestamp >= ?", since)
    val notification = SyncCategoryStat(
        key = "notification",
        icon = "🔔",
        label = "알림",
        total = notiTotal,
        synced = notiTotal - notiPending,
        recentSynced = (notiCapturedRecent - notiPendingRecent).coerceAtLeast(0)
    )

    val lastUploadedGpsId = prefs.getLastUploadedGpsPointId()
    val gps = SyncCategoryStat(
        key = "gps",
        icon = "📍",
        label = "GPS",
        total = count("SELECT COUNT(*) FROM health_gps_points WHERE $GPS_REALTIME_FILTER"),
        synced = count("SELECT COUNT(*) FROM health_gps_points WHERE $GPS_REALTIME_FILTER AND id <= ?", lastUploadedGpsId),
        recentSynced = count(
            "SELECT COUNT(*) FROM health_gps_points WHERE $GPS_REALTIME_FILTER AND id <= ? AND timestamp >= ?",
            lastUploadedGpsId,
            since
        )
    )

    listOf(
        cacheTableStat("call", "📞", "통화", "call_log_cache"),
        cacheTableStat("sms", "✉️", "문자", "sms_cache"),
        kakao,
        cacheTableStat("recording", "🎙️", "녹음", "recording_cache"),
        cacheTableStat("photo", "🖼️", "사진", "photo_media_cache"),
        loadContactStat(context, prefs.lastContactTs.first(), since),
        notification,
        health,
        gps
    )
}

/**
 * 연락처는 로컬 캐시 테이블이 없으므로 기기 주소록을 직접 셉니다.
 * 전송 완료 지점(lastContactTs) 이전에 수정된 연락처를 전송된 것으로 봅니다.
 */
private fun loadContactStat(context: Context, lastContactTs: Long, since: Long): SyncCategoryStat {
    var total = 0
    var synced = 0
    var recent = 0

    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED
    if (granted) {
        val updatedCol = ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP

        fun countContacts(selection: String?, args: Array<String>?): Int {
            return try {
                context.contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(ContactsContract.Contacts._ID),
                    selection,
                    args,
                    null
                )?.use { it.count } ?: 0
            } catch (_: Exception) {
                0
            }
        }

        total = countContacts(null, null)
        if (lastContactTs > 0L) {
            synced = countContacts("$updatedCol <= ?", arrayOf(lastContactTs.toString()))
            recent = countContacts(
                "$updatedCol <= ? AND $updatedCol >= ?",
                arrayOf(lastContactTs.toString(), since.toString())
            )
        }
    }

    return SyncCategoryStat("contact", "📇", "연락처", total, synced, recent)
}
