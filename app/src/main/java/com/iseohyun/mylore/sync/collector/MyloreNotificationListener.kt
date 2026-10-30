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

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return

        val pkgName = sbn.packageName ?: return
        val extras = sbn.notification?.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""

        if (title.isBlank() && text.isBlank()) return

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
