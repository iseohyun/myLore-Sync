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
import com.iseohyun.mylore.sync.data.local.entity.HealthSyncCacheEntity
import com.iseohyun.mylore.sync.data.local.entity.KakaoRoomCacheEntity
import com.iseohyun.mylore.sync.data.prefs.AppPreferences
import com.iseohyun.mylore.sync.data.remote.ApiClient
import com.iseohyun.mylore.sync.data.remote.MyLoreApiService
import com.iseohyun.mylore.sync.data.remote.dto.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RealtimeSyncForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wifiLock: WifiManager.WifiLock? = null
    private var syncJob: Job? = null
    private var lastReconcileAtMs: Long = 0L

    companion object {
        private const val CHANNEL_ID = "mylore_sync_channel"
        private const val NOTIFICATION_ID = 1001
        private const val IDLE_RECONCILE_INTERVAL_MS = 60_000L

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
            val cacheDao = db.syncCacheDao()
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())

            while (isActive) {
                try {
                    val ip = prefs.pcIp.first()
                    val port = prefs.pcPort.first()
                    val token = prefs.pairingToken.first()

                    if (ip.isNotBlank() && token.isNotBlank()) {
                        val api = ApiClient.createService(ip, port)
                        var didTransmitDataInThisCycle = false

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
                                val textBytes = kInfo.file.length()
                                val totalMediaBytes = kInfo.mediaFiles.sumOf { it.length() }
                                val totalBytes = textBytes + totalMediaBytes

                                // 1-0. 서버에 이미 존재하는 미디어 해시 사전 대조 (`check-hashes`)
                                val mediaWithHashes = kInfo.mediaFiles.mapNotNull { file ->
                                    val hash = KakaoChatFolderWatcher.calculateFileHash(file)
                                    if (hash != null) Pair(file, hash) else null
                                }
                                val allHashes = mediaWithHashes.map { it.second }
                                val missingHashes = if (allHashes.isNotEmpty()) {
                                    try {
                                        val checkRes = api.checkHashes(token, CheckHashesRequest(allHashes))
                                        if (checkRes.isSuccessful && checkRes.body() != null) {
                                            checkRes.body()!!.missingHashes.toSet()
                                        } else {
                                            allHashes.toSet()
                                        }
                                    } catch (_: Exception) {
                                        allHashes.toSet()
                                    }
                                } else emptySet()

                                var syncedBytes = mediaWithHashes
                                    .filter { !missingHashes.contains(it.second) }
                                    .sumOf { it.first.length() }
                                val nowTs = System.currentTimeMillis()
                                prefs.updateRoomSyncProgress(roomName, syncedBytes, totalBytes, nowTs)

                                var mediaUploadSuccessCount = mediaWithHashes.size - missingHashes.size

                                // 1-1. 서버에 없는 신규 미디어 파일들만 업로드
                                for ((mediaFile, hash) in mediaWithHashes) {
                                    if (!missingHashes.contains(hash)) continue
                                    if (!mediaFile.exists() || !mediaFile.isFile) continue
                                    try {
                                        val takenAtStr = isoFormat.format(Date(mediaFile.lastModified()))
                                        val takenReq = takenAtStr.toRequestBody("text/plain".toMediaTypeOrNull())
                                        val sourceReq = "kakao_export".toRequestBody("text/plain".toMediaTypeOrNull())
                                        val mBody = mediaFile.asRequestBody("application/octet-stream".toMediaTypeOrNull())
                                        val mPart = MultipartBody.Part.createFormData("file", mediaFile.name, mBody)
                                        val res = api.uploadMediaFile(token, takenReq, sourceReq, mPart, folderReq)
                                        if (res.isSuccessful && res.body()?.ok == true) {
                                            didTransmitDataInThisCycle = true
                                            syncedBytes += mediaFile.length()
                                            mediaUploadSuccessCount++
                                            prefs.updateRoomSyncProgress(roomName, syncedBytes, totalBytes, System.currentTimeMillis())
                                        }
                                    } catch (e: Exception) {
                                        Log.w("SyncService", "Failed to upload kakao media file: ${mediaFile.name}", e)
                                    }
                                }

                                // 1-2. 대화 텍스트(`KakaoTalkChats.txt`) 및 폴더 순번 전송
                                val fBody = kInfo.file.asRequestBody("text/plain".toMediaTypeOrNull())
                                val part = MultipartBody.Part.createFormData("file", kInfo.file.name, fBody)
                                val hashReq = kInfo.fileHash.toRequestBody("text/plain".toMediaTypeOrNull())
                                val modReq = kInfo.modifiedAt.toString().toRequestBody("text/plain".toMediaTypeOrNull())
                                val idxReq = folderIdx.toString().toRequestBody("text/plain".toMediaTypeOrNull())
                                val totalReq = totalFolders.toString().toRequestBody("text/plain".toMediaTypeOrNull())

                                try {
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
                                        didTransmitDataInThisCycle = true
                                        syncedBytes += textBytes
                                        if (mediaUploadSuccessCount >= mediaWithHashes.size) {
                                            syncedBytes = totalBytes
                                        }
                                        val sharedAt = System.currentTimeMillis()
                                        prefs.updateRoomSyncProgress(roomName, syncedBytes, totalBytes, sharedAt)
                                        prefs.updateLastKakaoTs(sharedAt)

                                        var isDeleted = false
                                        if (uploadRes.body()?.verifiedForCleanup == true && mediaUploadSuccessCount >= mediaWithHashes.size) {
                                            val parentFolder = kInfo.folder
                                            if (parentFolder.exists() &&
                                                (parentFolder.name.startsWith("KakaoTalk_Chats_") || parentFolder.parentFile?.name == "Chats")
                                            ) {
                                                isDeleted = parentFolder.deleteRecursively()
                                                Log.i("SyncService", "Deleted fully synced Kakao folder: ${parentFolder.name} ($isDeleted)")
                                            }
                                        }

                                        // 전송 후 삭제되더라도 대화방명(상대), 마지막 대화, 마지막 대화 시각, 공유 시각을 로컬 캐시에 영구 보존
                                        val percent = if (totalBytes > 0L) ((syncedBytes.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100) else 100
                                        cacheDao.upsertKakaoRoom(
                                            KakaoRoomCacheEntity(
                                                roomName = roomName,
                                                folderName = kInfo.folderName,
                                                filePath = kInfo.file.absolutePath,
                                                fileLastModified = kInfo.modifiedAt,
                                                lastMessage = kInfo.lastMessage,
                                                lastMessageTime = kInfo.lastMessageTime,
                                                lastSharedTime = sharedAt,
                                                totalBytes = totalBytes,
                                                textBytes = textBytes,
                                                mediaBytes = totalMediaBytes,
                                                mediaCount = kInfo.mediaFiles.size,
                                                syncedBytes = syncedBytes,
                                                percent = percent,
                                                isOnDevice = !isDeleted
                                            )
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.w("SyncService", "Failed to upload kakao chat file for ${kInfo.folderName}", e)
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
                                    didTransmitDataInThisCycle = true
                                    val ackIds = res.body()!!.ackIds
                                    db.notificationDao().markAsSynced(ackIds)
                                    prefs.updateLastNotiTs(entity.postedAt)
                                }
                            }
                        }

                        // 3. 문자(SMS/MMS) 워터마크 이후 증분 동기화
                        val lastSmsTs = prefs.lastSmsTs.first()
                        val smsCollector = SmsAndMmsCollector(applicationContext)
                        val smsList = smsCollector.collectSmsSince(lastSmsTs)
                        if (smsList.isNotEmpty()) {
                            val res = api.syncSms(token, SyncSmsRequest(smsList))
                            if (res.isSuccessful && res.body()?.ok == true) {
                                didTransmitDataInThisCycle = true
                                val maxTs = smsList.maxOf { it.ts }
                                prefs.updateLastSmsTs(maxTs)
                                smsCollector.refreshSmsCache(db, maxTs)
                            }
                        }

                        // 4. 통화기록(CallLog) 워터마크 이후 증분 동기화
                        val lastCallTs = prefs.lastCallTs.first()
                        val callCollector = CallLogCollector(applicationContext)
                        val callLogs = callCollector.collectCallLogsSince(lastCallTs)
                        if (callLogs.isNotEmpty()) {
                            val res = api.syncCallLogs(token, SyncCallLogsRequest(callLogs))
                            if (res.isSuccessful && res.body()?.ok == true) {
                                didTransmitDataInThisCycle = true
                                val maxTs = callLogs.maxOf { it.ts }
                                prefs.updateLastCallTs(maxTs)
                                callCollector.refreshCallLogCache(db, maxTs)
                            }
                        }

                        // 5. 연락처(Contacts) 워터마크 이후 증분 동기화
                        val lastContactTs = prefs.lastContactTs.first()
                        val contactList = ContactsCollector(applicationContext).collectContactsSince(lastContactTs)
                        if (contactList.isNotEmpty()) {
                            val res = api.syncContacts(token, SyncContactsRequest(contactList))
                            if (res.isSuccessful && res.body()?.ok == true) {
                                didTransmitDataInThisCycle = true
                                val maxTs = contactList.maxOf { it.updatedTs }
                                prefs.updateLastContactTs(maxTs)
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
                                    didTransmitDataInThisCycle = true
                                    val maxTs = usageList.maxOf { it.lastTimeUsed }
                                    if (maxTs > 0) prefs.updateLastUsageTs(maxTs)
                                }
                            }
                        }

                        // 7. 통화녹음 및 음성녹음(Call & Voice Recordings) 동기화 (캐시 기반으로 불필요한 SHA-256 재계산 방지)
                        val lastRecTs = prefs.lastRecordingTs.first()
                        val cachedRecordings = CallRecordingCollector().refreshRecordingsCache(db, lastRecTs)
                        val unsyncedRecs = cachedRecordings.filter { !it.isSynced }
                        if (unsyncedRecs.isNotEmpty()) {
                            val recordingHashes = unsyncedRecs.map { it.fileHash }
                            val checkRes = api.checkHashes(token, CheckHashesRequest(recordingHashes))
                            if (checkRes.isSuccessful && checkRes.body() != null) {
                                val missing = checkRes.body()!!.missingHashes.toSet()
                                // 서버에 이미 존재하는 해시는 즉시 전송 완료로 마킹
                                val alreadyOnServer = recordingHashes.filter { !missing.contains(it) }
                                if (alreadyOnServer.isNotEmpty()) {
                                    cacheDao.markRecordingsSyncedByHashes(alreadyOnServer)
                                }

                                for (rec in unsyncedRecs) {
                                    if (missing.contains(rec.fileHash)) {
                                        val recFile = File(rec.filePath)
                                        if (!recFile.exists()) continue
                                        val fBody = recFile.asRequestBody("audio/*".toMediaTypeOrNull())
                                        val part = MultipartBody.Part.createFormData("file", recFile.name, fBody)
                                        val hashReq = rec.fileHash.toRequestBody("text/plain".toMediaTypeOrNull())
                                        val phoneReq = rec.phoneNumber.toRequestBody("text/plain".toMediaTypeOrNull())
                                        val nameReq = rec.contactName.toRequestBody("text/plain".toMediaTypeOrNull())
                                        val timeReq = rec.recordedAt.toString().toRequestBody("text/plain".toMediaTypeOrNull())

                                        val upRes = api.uploadCallRecordingFile(token, hashReq, phoneReq, nameReq, timeReq, part)
                                        if (upRes.isSuccessful && upRes.body()?.ok == true) {
                                            didTransmitDataInThisCycle = true
                                            prefs.updateLastRecordingTs(rec.recordedAt)
                                            cacheDao.markRecordingsSyncedByHashes(listOf(rec.fileHash))
                                        }
                                    }
                                }
                            }
                        }

                        // 8. 유휴 시점(모든 대기 정보 전송 완료 후) 서버와 모바일 간 전송 목록 싱크 정합성 맞추기 (Reconciliation)
                        val now = System.currentTimeMillis()
                        if (didTransmitDataInThisCycle || (now - lastReconcileAtMs >= IDLE_RECONCILE_INTERVAL_MS)) {
                            lastReconcileAtMs = now
                            reconcileWithServerWhenIdle(api, token, prefs, db)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SyncService", "Error during sync loop execution", e)
                }

                delay(5000)
            }
        }
    }

    /**
     * 모든 대기 정보 전송을 마친 유휴(Idle) 시점에 서버(`POST /api/v1/sync/reconcile` 또는 `GET /api/v1/sync/manifest`)와
     * 모바일 로컬 캐시 간의 전송 이력(카톡 대화방별 마지막 대화 시각, 헬스 최신 파일 종류/시각, 녹음 해시, 문자/통화/알림 상태)을 상호 동기화합니다.
     */
    private suspend fun reconcileWithServerWhenIdle(
        api: MyLoreApiService,
        token: String,
        prefs: AppPreferences,
        db: AppDatabase
    ) {
        try {
            val cacheDao = db.syncCacheDao()
            val localKakao = cacheDao.getAllKakaoRooms().map {
                ServerKakaoRoomInfo(
                    roomName = it.roomName,
                    folderName = it.folderName,
                    lastMessage = it.lastMessage,
                    lastMessageTs = it.lastMessageTime,
                    lastSharedTs = it.lastSharedTime,
                    totalBytes = it.totalBytes,
                    syncedBytes = it.syncedBytes
                )
            }
            val localHealth = cacheDao.getAllHealthRecords().map {
                ServerHealthRecordInfo(
                    folderName = it.folderName,
                    latestRecordType = it.latestRecordType,
                    lastDataTs = it.lastDataTime,
                    lastSharedTs = it.lastSharedTime,
                    csvCount = it.csvCount,
                    jsonCount = it.jsonCount,
                    totalSizeKb = it.totalSizeKb
                )
            }
            val localRecHashes = cacheDao.getAllRecordings().map { it.fileHash }

            val req = SyncReconcileRequest(
                lastKakaoTs = prefs.lastKakaoTs.first(),
                lastSmsTs = prefs.lastSmsTs.first(),
                lastCallTs = prefs.lastCallTs.first(),
                lastNotiTs = prefs.lastNotiTs.first(),
                lastRecordingTs = prefs.lastRecordingTs.first(),
                lastHealthTs = prefs.lastHealthTs.first(),
                localKakaoRooms = localKakao,
                localHealthRecords = localHealth,
                localRecordingHashes = localRecHashes
            )

            val manifest: SyncManifestResponse? = try {
                val res = api.reconcileSyncLists(token, req)
                if (res.isSuccessful && res.body()?.ok == true) {
                    res.body()
                } else {
                    val getRes = api.getSyncManifest(token)
                    if (getRes.isSuccessful && getRes.body()?.ok == true) getRes.body() else null
                }
            } catch (_: Exception) {
                null
            }

            if (manifest != null) {
                // 1) 서버가 보유한 카톡 대화방 목록 및 마지막 대화 시각 병합
                if (manifest.kakaoRooms.isNotEmpty()) {
                    val existingMap = cacheDao.getAllKakaoRooms().associateBy { it.roomName }
                    val mergedRooms = manifest.kakaoRooms.mapNotNull { sRoom ->
                        if (sRoom.roomName.isBlank()) return@mapNotNull null
                        val local = existingMap[sRoom.roomName]
                        if (local != null) {
                            local.copy(
                                lastMessage = sRoom.lastMessage.ifBlank { local.lastMessage },
                                lastMessageTime = maxOf(local.lastMessageTime, sRoom.lastMessageTs),
                                lastSharedTime = maxOf(local.lastSharedTime, sRoom.lastSharedTs),
                                syncedBytes = maxOf(local.syncedBytes, sRoom.syncedBytes)
                            )
                        } else {
                            KakaoRoomCacheEntity(
                                roomName = sRoom.roomName,
                                folderName = sRoom.folderName,
                                lastMessage = sRoom.lastMessage,
                                lastMessageTime = sRoom.lastMessageTs,
                                lastSharedTime = sRoom.lastSharedTs,
                                totalBytes = sRoom.totalBytes,
                                syncedBytes = sRoom.syncedBytes,
                                percent = 100,
                                isOnDevice = false
                            )
                        }
                    }
                    if (mergedRooms.isNotEmpty()) {
                        cacheDao.upsertKakaoRooms(mergedRooms)
                    }
                }

                // 2) 서버가 보유한 헬스 데이터 최신 종류 및 마지막 시각 병합
                if (manifest.healthRecords.isNotEmpty()) {
                    val existingHealthMap = cacheDao.getAllHealthRecords().associateBy { it.folderName }
                    val mergedHealth = manifest.healthRecords.mapNotNull { sHealth ->
                        if (sHealth.folderName.isBlank()) return@mapNotNull null
                        val local = existingHealthMap[sHealth.folderName]
                        if (local != null) {
                            local.copy(
                                latestRecordType = sHealth.latestRecordType.ifBlank { local.latestRecordType },
                                lastDataTime = maxOf(local.lastDataTime, sHealth.lastDataTs),
                                lastSharedTime = maxOf(local.lastSharedTime, sHealth.lastSharedTs),
                                isSynced = true
                            )
                        } else {
                            HealthSyncCacheEntity(
                                folderName = sHealth.folderName,
                                latestRecordType = sHealth.latestRecordType,
                                lastDataTime = sHealth.lastDataTs,
                                lastSharedTime = sHealth.lastSharedTs,
                                csvCount = sHealth.csvCount,
                                jsonCount = sHealth.jsonCount,
                                totalSizeKb = sHealth.totalSizeKb,
                                isSynced = true,
                                isOnDevice = false
                            )
                        }
                    }
                    if (mergedHealth.isNotEmpty()) {
                        cacheDao.upsertHealthRecords(mergedHealth)
                    }
                }

                // 3) 녹음/문자/통화/알림 전송 완료 상태 정합성 반영
                if (manifest.syncedRecordingHashes.isNotEmpty()) {
                    cacheDao.markRecordingsSyncedByHashes(manifest.syncedRecordingHashes)
                }
                if (manifest.lastSmsTs > 0L) {
                    prefs.updateLastSmsTs(manifest.lastSmsTs)
                    cacheDao.markSmsSyncedUpTo(manifest.lastSmsTs)
                }
                if (manifest.syncedSmsIds.isNotEmpty()) {
                    cacheDao.markSmsSyncedByIds(manifest.syncedSmsIds)
                }
                if (manifest.lastCallTs > 0L) {
                    prefs.updateLastCallTs(manifest.lastCallTs)
                    cacheDao.markCallLogsSyncedUpTo(manifest.lastCallTs)
                }
                if (manifest.syncedCallIds.isNotEmpty()) {
                    cacheDao.markCallLogsSyncedByIds(manifest.syncedCallIds)
                }
                if (manifest.syncedNotiIds.isNotEmpty()) {
                    db.notificationDao().markAsSynced(manifest.syncedNotiIds)
                }
            }
        } catch (e: Exception) {
            Log.d("SyncService", "Idle reconciliation skipped or failed: ${e.message}")
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
