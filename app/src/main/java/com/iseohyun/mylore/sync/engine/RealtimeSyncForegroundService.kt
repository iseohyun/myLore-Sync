package com.iseohyun.mylore.sync.engine

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.iseohyun.mylore.sync.MainActivity
import com.iseohyun.mylore.sync.collector.*
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.prefs.AppPreferences
import com.iseohyun.mylore.sync.data.remote.ApiClient
import com.iseohyun.mylore.sync.data.remote.dto.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RealtimeSyncForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wifiLock: WifiManager.WifiLock? = null
    private var syncJob: Job? = null

    companion object {
        private const val CHANNEL_ID = "mylore_sync_channel"
        private const val NOTIFICATION_ID = 1001

        fun startService(context: Context) {
            val intent = Intent(context, RealtimeSyncForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, RealtimeSyncForegroundService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWifiLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("myLore PC와 실시간 동기화 중...")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("SyncService", "startForeground failed (e.g. dataSync quota exhausted)", e)
        }

        startSyncLoop()

        return START_STICKY
    }

    private fun startSyncLoop() {
        syncJob?.cancel()
        syncJob = serviceScope.launch {
            val prefs = AppPreferences(applicationContext)
            val db = AppDatabase.getInstance(applicationContext)
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())

            while (isActive) {
                try {
                    val ip = prefs.pcIp.first()
                    val port = prefs.pcPort.first()
                    val token = prefs.pairingToken.first()

                    if (ip.isNotBlank() && token.isNotBlank()) {
                        val api = ApiClient.createService(ip, port)

                        // 0. 서비스별 전송 완료 시점(워터마크) 서버와 상호 공유·동기화
                        val wmReq = SyncWatermarksRequest(
                            lastKakaoTs = prefs.lastKakaoTs.first(),
                            lastSmsTs = prefs.lastSmsTs.first(),
                            lastCallTs = prefs.lastCallTs.first(),
                            lastNotiTs = prefs.lastNotiTs.first(),
                            lastRecordingTs = prefs.lastRecordingTs.first(),
                            lastHealthTs = prefs.lastHealthTs.first(),
                            lastContactTs = prefs.lastContactTs.first(),
                            lastUsageTs = prefs.lastUsageTs.first()
                        )
                        val wmRes = api.syncWatermarks(token, wmReq)
                        if (wmRes.isSuccessful && wmRes.body()?.ok == true) {
                            prefs.reconcileWatermarksWithServer(wmRes.body()!!)
                        }

                        // 1. 카카오톡(KakaoTalk) 내보내기 폴더 단위 동기화
                        // - 전체 폴더 개수를 먼저 서버에 전달(`batchInit`)
                        // - 폴더를 1개씩 순차 전송(폴더 내 이미지·동영상 원본 적재 + KakaoTalkChats.txt 전송)
                        // - 서버 전송 완료 확인(`verified_for_cleanup == true`) 시 즉시 해당 내보내기 폴더 삭제
                        val kakaoFolders = KakaoChatFolderWatcher().scanBackupFiles()
                        if (kakaoFolders.isNotEmpty()) {
                            val totalFolders = kakaoFolders.size
                            api.batchInit(
                                token,
                                BatchInitRequest(service = "kakaotalk", totalCount = totalFolders)
                            )

                            for ((index, kInfo) in kakaoFolders.withIndex()) {
                                val folderIdx = index + 1
                                val folderReq = kInfo.folderName.toRequestBody("text/plain".toMediaTypeOrNull())
                                val roomName = kInfo.roomKey.ifBlank { kInfo.folderName }
                                val totalBytes = kInfo.file.length() + kInfo.mediaFiles.sumOf { it.length() }
                                var syncedBytes = 0L

                                // 1-1. 폴더에 포함된 이미지·동영상 파일들을 수정 없이 그대로 `mobile/kakao/{folder_name}/`에 적재
                                for (mediaFile in kInfo.mediaFiles) {
                                    if (!mediaFile.exists() || !mediaFile.isFile) continue
                                    val takenAtStr = isoFormat.format(Date(mediaFile.lastModified()))
                                    val takenReq = takenAtStr.toRequestBody("text/plain".toMediaTypeOrNull())
                                    val sourceReq = "kakao_export".toRequestBody("text/plain".toMediaTypeOrNull())
                                    val mBody = mediaFile.asRequestBody("application/octet-stream".toMediaTypeOrNull())
                                    val mPart = MultipartBody.Part.createFormData("file", mediaFile.name, mBody)
                                    val res = api.uploadMediaFile(token, takenReq, sourceReq, mPart, folderReq)
                                    if (res.isSuccessful && res.body()?.ok == true) {
                                        syncedBytes += mediaFile.length()
                                        prefs.updateRoomSyncProgress(roomName, syncedBytes, totalBytes, System.currentTimeMillis())
                                    }
                                }

                                // 1-2. 대화 텍스트(`KakaoTalkChats.txt`) 및 폴더 순번(`folder_index` / `total_folders`) 전송
                                val fBody = kInfo.file.asRequestBody("text/plain".toMediaTypeOrNull())
                                val part = MultipartBody.Part.createFormData("file", kInfo.file.name, fBody)
                                val hashReq = kInfo.fileHash.toRequestBody("text/plain".toMediaTypeOrNull())
                                val modReq = kInfo.modifiedAt.toString().toRequestBody("text/plain".toMediaTypeOrNull())
                                val idxReq = folderIdx.toString().toRequestBody("text/plain".toMediaTypeOrNull())
                                val totalReq = totalFolders.toString().toRequestBody("text/plain".toMediaTypeOrNull())

                                val uploadRes = api.uploadKakaoChatFile(
                                    token,
                                    folderReq,
                                    hashReq,
                                    modReq,
                                    idxReq,
                                    totalReq,
                                    part
                                )
                                if (uploadRes.isSuccessful && uploadRes.body()?.ok == true) {
                                    syncedBytes = totalBytes
                                    prefs.updateRoomSyncProgress(roomName, totalBytes, totalBytes, System.currentTimeMillis())
                                    prefs.updateLastKakaoTs(System.currentTimeMillis())
                                    Log.d("SyncService", "Uploaded Kakao folder ($folderIdx/$totalFolders): ${kInfo.folderName}")
                                    if (uploadRes.body()?.verifiedForCleanup == true) {
                                        val parentFolder = kInfo.folder
                                        if (parentFolder.exists() &&
                                            (parentFolder.name.startsWith("KakaoTalk_Chats_") || parentFolder.parentFile?.name == "Chats")
                                        ) {
                                            val deleted = parentFolder.deleteRecursively()
                                            Log.i("SyncService", "Deleted synced Kakao folder: ${parentFolder.name} ($deleted)")
                                        }
                                    }
                                }
                            }
                        }

                        // 2. 알림(Notifications) 총 개수 선발송 후 1건씩 순차 전달
                        val pendingNotis = db.notificationDao().getPendingNotifications(limit = 300)
                        if (pendingNotis.isNotEmpty()) {
                            val totalCount = pendingNotis.size
                            api.batchInit(
                                token,
                                BatchInitRequest(service = "notification", totalCount = totalCount)
                            )

                            for ((index, entity) in pendingNotis.withIndex()) {
                                val currentIdx = index + 1
                                val item = SyncNotificationItem(
                                    id = entity.id,
                                    pkg = entity.packageName,
                                    title = entity.title,
                                    text = entity.text,
                                    subText = entity.subText,
                                    ts = entity.postedAt
                                )
                                val res = api.syncNotifications(
                                    token,
                                    SyncNotificationsRequest(
                                        items = listOf(item),
                                        currentIndex = currentIdx,
                                        totalCount = totalCount
                                    )
                                )
                                if (res.isSuccessful && res.body()?.ok == true) {
                                    val ackIds = res.body()!!.ackIds
                                    db.notificationDao().markAsSynced(ackIds)
                                    prefs.updateLastNotiTs(entity.postedAt)
                                }
                            }
                            Log.d("SyncService", "Synced $totalCount notifications 1-by-1")
                        }

                        // 3. 문자(SMS/MMS) 워터마크 이후 증분 동기화
                        val lastSmsTs = prefs.lastSmsTs.first()
                        val smsList = SmsAndMmsCollector(applicationContext).collectSmsSince(lastSmsTs)
                        if (smsList.isNotEmpty()) {
                            val res = api.syncSms(token, SyncSmsRequest(smsList))
                            if (res.isSuccessful && res.body()?.ok == true) {
                                val maxTs = smsList.maxOf { it.ts }
                                prefs.updateLastSmsTs(maxTs)
                                Log.d("SyncService", "Synced ${smsList.size} SMS/MMS items")
                            }
                        }

                        // 4. 통화기록(CallLog) 워터마크 이후 증분 동기화
                        val lastCallTs = prefs.lastCallTs.first()
                        val callLogs = CallLogCollector(applicationContext).collectCallLogsSince(lastCallTs)
                        if (callLogs.isNotEmpty()) {
                            val res = api.syncCallLogs(token, SyncCallLogsRequest(callLogs))
                            if (res.isSuccessful && res.body()?.ok == true) {
                                val maxTs = callLogs.maxOf { it.ts }
                                prefs.updateLastCallTs(maxTs)
                                Log.d("SyncService", "Synced ${callLogs.size} call logs")
                            }
                        }

                        // 5. 연락처(Contacts) 워터마크 이후 증분 동기화
                        val lastContactTs = prefs.lastContactTs.first()
                        val contactList = ContactsCollector(applicationContext).collectContactsSince(lastContactTs)
                        if (contactList.isNotEmpty()) {
                            val res = api.syncContacts(token, SyncContactsRequest(contactList))
                            if (res.isSuccessful && res.body()?.ok == true) {
                                val maxTs = contactList.maxOf { it.updatedTs }
                                prefs.updateLastContactTs(maxTs)
                                Log.d("SyncService", "Synced ${contactList.size} contacts")
                            }
                        }

                        // 6. 앱 사용량(App Usage) 워터마크 이후 증분 동기화
                        val lastUsageTs = prefs.lastUsageTs.first()
                        val appUsageCollector = AppUsageCollector(applicationContext)
                        if (appUsageCollector.hasUsagePermission()) {
                            val usageList = appUsageCollector.collectUsageStatsSince(lastUsageTs)
                            if (usageList.isNotEmpty()) {
                                val res = api.syncAppUsage(token, SyncAppUsageRequest(usageList))
                                if (res.isSuccessful && res.body()?.ok == true) {
                                    val maxTs = usageList.maxOf { it.lastTimeUsed }
                                    if (maxTs > 0) prefs.updateLastUsageTs(maxTs)
                                    Log.d("SyncService", "Synced ${usageList.size} app usage stats")
                                }
                            }
                        }

                        // 7. 통화녹음 및 음성녹음(Call & Voice Recordings) 동기화
                        val callRecordings = CallRecordingCollector().scanCallRecordings()
                        if (callRecordings.isNotEmpty()) {
                            val recordingHashes = callRecordings.map { it.fileHash }
                            val checkRes = api.checkHashes(token, CheckHashesRequest(recordingHashes))
                            if (checkRes.isSuccessful && checkRes.body() != null) {
                                val missing = checkRes.body()!!.missingHashes.toSet()
                                for (rec in callRecordings) {
                                    if (missing.contains(rec.fileHash)) {
                                        val fBody = rec.file.asRequestBody("audio/*".toMediaTypeOrNull())
                                        val part = MultipartBody.Part.createFormData("file", rec.file.name, fBody)
                                        val hashReq = rec.fileHash.toRequestBody("text/plain".toMediaTypeOrNull())
                                        val phoneReq = rec.phoneNumber.toRequestBody("text/plain".toMediaTypeOrNull())
                                        val nameReq = rec.contactName.toRequestBody("text/plain".toMediaTypeOrNull())
                                        val timeReq = rec.recordedAt.toString().toRequestBody("text/plain".toMediaTypeOrNull())

                                        val upRes = api.uploadCallRecordingFile(token, hashReq, phoneReq, nameReq, timeReq, part)
                                        if (upRes.isSuccessful && upRes.body()?.ok == true) {
                                            prefs.updateLastRecordingTs(rec.recordedAt)
                                            Log.d("SyncService", "Uploaded recording: ${rec.file.name}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SyncService", "Error during sync loop execution", e)
                }

                delay(5000)
            }
        }
    }

    private fun acquireWifiLock() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "myLoreSync:WifiLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.e("SyncService", "Error acquiring wifi lock", e)
        }
    }

    private fun releaseWifiLock() {
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (e: Exception) {
            Log.e("SyncService", "Error releasing wifi lock", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "myLore Sync Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "myLore PC와 데이터 실시간 동기화 상태"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("myLore Sync")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        syncJob?.cancel()
        serviceScope.cancel()
        releaseWifiLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
