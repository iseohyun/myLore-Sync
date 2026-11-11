package com.iseohyun.mylore.sync.collector

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.local.entity.NotificationEntity
import com.iseohyun.mylore.sync.data.local.entity.SyncLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MyloreNotificationListener : NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onListenerConnected() {
        super.onListenerConnected()
        LocationWifiCollector.ensurePassiveListenerRegistered(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        LocationWifiCollector.ensurePassiveListenerRegistered(applicationContext)
        sbn ?: return

        val pkgName = sbn.packageName ?: return
        if (pkgName == "com.iseohyun.mylore.sync") return

        val extras = sbn.notification?.extras ?: return

        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val convTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim() ?: ""
        val title = if (convTitle.isNotBlank() && convTitle != rawTitle) {
            "$convTitle ($rawTitle)"
        } else {
            rawTitle
        }
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim() ?: ""
        val normalText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val text = if (bigText.length > normalText.length) bigText else normalText
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""

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

        Log.d("MyloreNotiListener", "Captured: [$pkgName] $title : $text")

        serviceScope.launch {
            try {
                val db = AppDatabase.getInstance(applicationContext)
                val entity = NotificationEntity(
                    packageName = pkgName,
                    title = title,
                    text = text,
                    subText = subText,
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
                Log.e("MyloreNotiListener", "Error saving notification to DB", e)
            }
        }
    }
}
