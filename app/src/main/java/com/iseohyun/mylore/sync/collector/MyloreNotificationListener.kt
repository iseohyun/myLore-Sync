package com.iseohyun.mylore.sync.collector

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.local.entity.KakaoReadEventEntity
import com.iseohyun.mylore.sync.data.local.entity.NotificationEntity
import com.iseohyun.mylore.sync.data.local.entity.SyncLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MyloreNotificationListener : NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 시스템에 알림 리스너로 연결되어 있는지. 끊긴 동안에는 떠 있는 알림 목록이 빈 목록으로 올 수 있습니다. */
    @Volatile
    private var isListenerConnected = false

    override fun onListenerConnected() {
        super.onListenerConnected()
        isListenerConnected = true
        LocationWifiCollector.ensurePassiveListenerRegistered(applicationContext)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isListenerConnected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        LocationWifiCollector.ensurePassiveListenerRegistered(applicationContext)
        sbn ?: return

        val pkgName = sbn.packageName ?: return
        if (pkgName == "com.iseohyun.mylore.sync") return

        val extras = sbn.notification?.extras ?: return

        val titleFields = readTitleFields(extras)
        val title = titleFields.title
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim() ?: ""
        val normalText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val text = if (bigText.length > normalText.length) bigText else normalText

        if (title.isBlank() && text.isBlank()) return

        if (pkgName == "com.android.systemui" || pkgName == "android") {
            val combined = "$title $text"
            if (combined.contains("충전 중") ||
                combined.contains("충전완료") ||
                combined.contains("충전 완료") ||
                combined.contains("무선 충전") ||
                combined.contains("고속 충전") ||
                combined.contains("초고속 충전")
            ) {
                return
            }
        }

        val postedAt = sbn.postTime

        Log.d(TAG, "Captured: [$pkgName] $title : $text")

        serviceScope.launch {
            try {
                val db = AppDatabase.getInstance(applicationContext)
                val entity = NotificationEntity(
                    packageName = pkgName,
                    title = title,
                    rawTitle = titleFields.rawTitle,
                    convTitle = titleFields.convTitle,
                    text = text,
                    subText = titleFields.subText,
                    postedAt = postedAt
                )
                db.notificationDao().insert(entity)

                db.syncLogDao().insert(
                    SyncLogEntity(
                        timestamp = System.currentTimeMillis(),
                        type = "NOTIFICATION",
                        message = "Captured notification from $pkgName: $title",
                        isSuccess = true
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error saving notification to DB", e)
            }
        }
    }

    /**
     * 카톡 알림이 읽혀서 사라졌으면 PC로 보낼 읽음 기록을 남깁니다. 안드로이드 8.0(API 26)부터 불립니다.
     *
     * - 알림을 눌렀거나(`REASON_CLICK`) 카톡이 스스로 지운 것(`REASON_APP_CANCEL`, `REASON_APP_CANCEL_ALL`)만 읽음으로 봅니다.
     *   손으로 밀어 지운 것은 보내지 않습니다.
     * - 새 메시지 때문에 알림이 바뀌면서 사라질 때도 있어서, 3초 뒤 같은 방(방 열쇠가 같은) 카톡 알림이
     *   떠 있으면 버립니다.
     */
    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: NotificationListenerService.RankingMap?,
        reason: Int
    ) {
        super.onNotificationRemoved(sbn, rankingMap, reason)
        sbn ?: return
        if (sbn.packageName != KakaoNotificationRules.KAKAO_PACKAGE) return
        if (!KakaoNotificationRules.isReadReason(reason)) return

        val extras = sbn.notification?.extras ?: return
        val removed = readTitleFields(extras)
        val roomKey = removed.roomKey
        // 방 열쇠가 없으면 어느 방을 읽었는지 PC가 알 수 없으므로 남기지 않음
        if (roomKey.isEmpty()) return

        val postedAt = sbn.postTime
        val removedAt = System.currentTimeMillis()

        serviceScope.launch {
            delay(KakaoNotificationRules.REPLACED_CHECK_DELAY_MS)

            val activeRoomKeys = activeKakaoRoomKeysOrNull()
            if (activeRoomKeys == null) {
                // 같은 방 알림이 다시 떴는지 확인할 수 없으면, 읽지 않은 방을 읽었다고 보내지 않도록 버림
                Log.w(TAG, "Kakao read event dropped: active notifications unavailable (reason=$reason)")
                return@launch
            }
            if (KakaoNotificationRules.hasSameRoom(roomKey, activeRoomKeys)) {
                Log.d(TAG, "Kakao notification replaced by a new one, not a read (reason=$reason)")
                return@launch
            }

            try {
                AppDatabase.getInstance(applicationContext).kakaoReadEventDao().insert(
                    KakaoReadEventEntity(
                        title = removed.title,
                        rawTitle = removed.rawTitle,
                        convTitle = removed.convTitle,
                        subText = removed.subText,
                        postedAt = postedAt,
                        removedAt = removedAt,
                        reason = reason
                    )
                )
                Log.d(TAG, "Kakao read event stored (reason=$reason)")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving Kakao read event to DB", e)
            }
        }
    }

    /** 지금 떠 있는 카톡 알림들의 방 열쇠. 알림 목록을 읽지 못하면 null */
    private fun activeKakaoRoomKeysOrNull(): List<String>? {
        // 연결이 끊기면 시스템이 오류 대신 빈 목록을 돌려줄 수 있어, 같은 방 알림이 없다고 잘못 볼 수 있음
        if (!isListenerConnected) return null
        return try {
            val active: Array<StatusBarNotification>? = activeNotifications
            active
                ?.filter { it.packageName == KakaoNotificationRules.KAKAO_PACKAGE }
                ?.mapNotNull { shown -> shown.notification?.extras?.let { readTitleFields(it).roomKey } }
        } catch (e: Exception) {
            // 알림 접근 권한이 풀렸거나 연결이 끊긴 경우 (SecurityException 등)
            Log.w(TAG, "Could not read active notifications", e)
            null
        }
    }

    private fun readTitleFields(extras: Bundle): NotificationTitleFields {
        return NotificationTitleFields(
            rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: "",
            convTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim() ?: "",
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""
        )
    }

    companion object {
        private const val TAG = "MyloreNotiListener"
    }
}
