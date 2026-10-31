package com.iseohyun.mylore.sync.engine

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.iseohyun.mylore.sync.MainActivity
import com.iseohyun.mylore.sync.collector.*
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.local.entity.HealthSyncCacheEntity
import com.iseohyun.mylore.sync.data.local.entity.KakaoRoomCacheEntity
import com.iseohyun.mylore.sync.data.local.entity.NotificationEntity
import com.iseohyun.mylore.sync.data.local.entity.RecordingCacheEntity
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
    private var isSessionActive: Boolean = false

    companion object {
        private const val CHANNEL_ID = "mylore_sync_channel"
        private const val NOTIFICATION_ID = 1001
        private const val IDLE_RECONCILE_INTERVAL_MS = 60_000L
        private const val RECENT_CALL_GRACE_PERIOD_MS = 60_000L

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

    /**
     * 통화 기록(CallLog)과 통화 녹음 파일을 하나의 논리적 이벤트로 묶는 데이터 홀더
     */
    private data class PendingCallEvent(
        val callLog: SyncCallLogItem?,
        val callRecording: RecordingCacheEntity?,
        val eventTs: Long
    ) {
        val pendingBytes: Long
            get() = if (callRecording != null && !callRecording.isSynced) callRecording.fileSize else 0L
    }

    /**
     * 카카오톡 대화방 폴더별 전송 작업 단위 (`last_message_ts` 이후 신규 미디어만 필터링된 상태)
     */
    private data class PendingKakaoTask(
        val info: KakaoChatFileInfo,
        val roomName: String,
        val eligibleMediaFiles: List<File>,
        val textBytes: Long,
        val pendingMediaBytes: Long,
        val fullFolderBytes: Long,
        val alreadySkippedMediaBytes: Long
    ) {
        val pendingTotalBytes: Long
            get() = textBytes + pendingMediaBytes
    }

    /**
     * 단일 동기화 세션 내 6대 도메인 볼륨 개수 및 바이트 진행률 및 대기 큐 컨텍스트
     */
    private class SessionSyncContext(
        val sessionId: String,
        val api: MyLoreApiService,
        val token: String,
        val prefs: AppPreferences,
        val db: AppDatabase,
        val monitor: SyncConditionMonitor
    ) {
        val pendingNotis = mutableListOf<NotificationEntity>()
        val trackedNotiIds = mutableSetOf<Long>()

        val pendingSmsList = mutableListOf<SyncSmsItem>()
        val trackedSmsIds = mutableSetOf<Long>()

        val pendingCallEvents = mutableListOf<PendingCallEvent>()
        val pendingKakaoTasks = mutableListOf<PendingKakaoTask>()
        val pendingVoiceRecs = mutableListOf<RecordingCacheEntity>()
        val unsyncedHealthFolders = mutableListOf<Pair<File, HealthSyncCacheEntity>>()
        val pendingHealthFiles = mutableListOf<Pair<File, HealthSyncCacheEntity>>()

        var didTransmitData: Boolean = false

        var kakaoTotalCount = 0
        var kakaoSyncedCount = 0
        var kakaoTotalBytes = 0L
        var kakaoSyncedBytes = 0L

        var notiTotalCount = 0
        var notiSyncedCount = 0

        var smsTotalCount = 0
        var smsSyncedCount = 0

        var callTotalCount = 0
        var callSyncedCount = 0
        var callTotalBytes = 0L
        var callSyncedBytes = 0L

        var recTotalCount = 0
        var recSyncedCount = 0
        var recTotalBytes = 0L
        var recSyncedBytes = 0L

        var healthTotalCount = 0
        var healthSyncedCount = 0
        var healthTotalBytes = 0L
        var healthSyncedBytes = 0L

        val totalBytes: Long
            get() = kakaoTotalBytes + callTotalBytes + recTotalBytes + healthTotalBytes

        val syncedBytes: Long
            get() = (kakaoSyncedBytes + callSyncedBytes + recSyncedBytes + healthSyncedBytes)
                .coerceAtMost(totalBytes)

        val totalCount: Int
            get() = kakaoTotalCount + notiTotalCount + smsTotalCount + callTotalCount + recTotalCount + healthTotalCount

        val syncedCount: Int
            get() = (kakaoSyncedCount + notiSyncedCount + smsSyncedCount + callSyncedCount + recSyncedCount + healthSyncedCount)
                .coerceAtMost(totalCount)

        fun toPlanRequest(isReady: Boolean): SessionPlanRequest {
            return SessionPlanRequest(
                sessionId = sessionId,
                isReady = isReady,
                totalBytes = totalBytes,
                syncedBytes = syncedBytes,
                totalCount = totalCount,
                syncedCount = syncedCount,
                volumes = SessionVolumesDto(
                    kakao = VolumePlanItem(kakaoTotalCount, kakaoSyncedCount, kakaoTotalBytes, kakaoSyncedBytes),
                    notification = VolumePlanItem(notiTotalCount, notiSyncedCount, 0L, 0L),
                    sms = VolumePlanItem(smsTotalCount, smsSyncedCount, 0L, 0L),
                    call = VolumePlanItem(callTotalCount, callSyncedCount, callTotalBytes, callSyncedBytes),
                    recording = VolumePlanItem(recTotalCount, recSyncedCount, recTotalBytes, recSyncedBytes),
                    health = VolumePlanItem(healthTotalCount, healthSyncedCount, healthTotalBytes, healthSyncedBytes)
                )
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWifiLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()

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

    /**
     * 전송 도중 배터리 하한선(비충전 시 75%) 유지 여부를 확인합니다.
     */
    private fun canContinueSession(monitor: SyncConditionMonitor): Boolean {
        return monitor.isBatteryAllowedForSync(isActiveSession = isSessionActive)
    }

    private suspend fun publishSessionPlanSafely(ctx: SessionSyncContext, isReady: Boolean) {
        try {
            ctx.api.sendSessionPlan(ctx.token, ctx.toPlanRequest(isReady = isReady))
        } catch (e: Exception) {
            Log.d("SyncService", "sendSessionPlan skipped or unsupported: ${e.message}")
        }
    }

    private fun startSyncLoop() {
        syncJob?.cancel()
        syncJob = serviceScope.launch {
            val prefs = AppPreferences(applicationContext)
            val db = AppDatabase.getInstance(applicationContext)
            val monitor = SyncConditionMonitor(applicationContext)

            while (isActive) {
                try {
                    runSingleSyncCycle(prefs, db, monitor)
                } catch (e: Exception) {
                    Log.e("SyncService", "Error during sync loop execution", e)
                }
                delay(5000)
            }
        }
    }

    private suspend fun runSingleSyncCycle(
        prefs: AppPreferences,
        db: AppDatabase,
        monitor: SyncConditionMonitor
    ) {
        // [조건 1] 배터리 상태 확인 (시작: 충전 중 or >= 80%, 유지: 충전 중 or >= 75%)
        val batteryInfo = monitor.checkBatteryInfo()
        if (!batteryInfo.isBatteryOk(isActiveSession = isSessionActive)) {
            isSessionActive = false
            return
        }

        val ip = prefs.pcIp.first()
        val port = prefs.pcPort.first()
        val token = prefs.pairingToken.first()
        if (ip.isBlank() || token.isBlank()) {
            isSessionActive = false
            return
        }

        // [조건 2] 서버와의 연결 확인 및 워터마크 동기화
        val api = ApiClient.createService(ip, port)
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
        val wmRes = try {
            api.syncWatermarks(token, wmReq)
        } catch (_: Exception) {
            isSessionActive = false
            return
        }

        if (!wmRes.isSuccessful || wmRes.body()?.ok != true) {
            isSessionActive = false
            return
        }

        // 2대 조건(배터리 & 서버 연결) 모두 충족 ➔ 동기화 세션 활성화
        isSessionActive = true
        prefs.reconcileWatermarksWithServer(wmRes.body()!!)

        val sessionId = "sess_${System.currentTimeMillis()}"
        val ctx = SessionSyncContext(sessionId, api, token, prefs, db, monitor)

        // [Step 2-A] 로컬 캐시에 파악된 기존 데이터부터 즉시 선발송 (is_ready = false)
        sendInitialFastPlan(ctx)

        // [Step 2-B] 6대 카테고리 미전송 데이터 스캔 및 정밀 용량 산출 후 발송 (is_ready = true)
        prepareAllPendingQueuesAndSendPlan(ctx)

        // [Step 3] 가장 오래된 내용(Oldest-First)부터 전송 시작 (방안 A: 경량 텍스트 우선 ➔ 대용량 파일)
        syncPendingNotifications(ctx)
        syncPendingSms(ctx)
        syncPendingCallEvents(ctx)
        syncPendingKakaoTasks(ctx)
        syncPendingVoiceRecordings(ctx)
        syncPendingHealthFiles(ctx)
        syncContactsAndAppUsage(ctx)

        // [Step 4 & Step 5] 더 이상 보낼 데이터 없음 전달 ➔ 서버 Background Check & Client ACK
        val now = System.currentTimeMillis()
        if (canContinueSession(monitor) &&
            (ctx.didTransmitData || (now - lastReconcileAtMs >= IDLE_RECONCILE_INTERVAL_MS))
        ) {
            lastReconcileAtMs = now
            completeSyncAndHandleServerAck(api, token, sessionId, prefs, db)
        }
    }

    /**
     * [Step 2-A] 정밀 스캔 전 로컬 캐시 기반으로 파악된 크기 및 개수를 즉시 선발송 (`is_ready = false`)
     */
    private suspend fun sendInitialFastPlan(ctx: SessionSyncContext) {
        val cacheDao = ctx.db.syncCacheDao()
        val cachedKakaoInit = cacheDao.getAllKakaoRooms().filter { it.isOnDevice && it.percent < 100 }
        val cachedRecsInit = cacheDao.getAllRecordings().filter { !it.isSynced }
        val cachedCallRecsInit = cachedRecsInit.filter { CallRecordingCollector.isCallRecording(it) }
        val cachedVoiceRecsInit = cachedRecsInit.filter { !CallRecordingCollector.isCallRecording(it) }
        val cachedHealthInit = cacheDao.getAllHealthRecords().filter { it.isOnDevice && !it.isSynced }

        ctx.kakaoTotalCount = cachedKakaoInit.size
        ctx.kakaoTotalBytes = cachedKakaoInit.sumOf { (it.totalBytes - it.syncedBytes).coerceAtLeast(0L) }
        ctx.notiTotalCount = ctx.db.notificationDao().getPendingCount()
        ctx.smsTotalCount = cacheDao.getUnsyncedSmsCount()
        ctx.callTotalCount = maxOf(cacheDao.getUnsyncedCallLogCount(), cachedCallRecsInit.size)
        ctx.callTotalBytes = cachedCallRecsInit.sumOf { it.fileSize }
        ctx.recTotalCount = cachedVoiceRecsInit.size
        ctx.recTotalBytes = cachedVoiceRecsInit.sumOf { it.fileSize }
        ctx.healthTotalCount = cachedHealthInit.sumOf { it.csvCount + it.jsonCount }
        ctx.healthTotalBytes = cachedHealthInit.sumOf { it.totalSizeKb * 1024L }

        if (ctx.totalCount > 0) {
            publishSessionPlanSafely(ctx, isReady = false)
        }
    }

    /**
     * [Step 2-B] 6대 카테고리 미전송 데이터 수집 (각 카테고리 내 Oldest-First 정렬) 및 정밀 용량 산출
     */
    private suspend fun prepareAllPendingQueuesAndSendPlan(ctx: SessionSyncContext) {
        val cacheDao = ctx.db.syncCacheDao()
        val nowMs = System.currentTimeMillis()

        // 1) 알림 (Oldest-First: postedAt ASC, DB형이므로 바이트는 0, 건수만 카운트)
        val notis = ctx.db.notificationDao().getPendingNotifications(limit = 500)
        ctx.pendingNotis.addAll(notis)
        ctx.trackedNotiIds.addAll(notis.map { it.id })

        // 2) 문자 (Oldest-First: ts ASC, DB형이므로 바이트는 0, 건수만 카운트)
        val lastSmsTs = ctx.prefs.lastSmsTs.first()
        val smsCollector = SmsAndMmsCollector(applicationContext)
        smsCollector.refreshSmsCache(ctx.db, lastSmsTs)
        val unsyncedSmsEntities = cacheDao.getUnsyncedSms(limit = 500)
        val newSmsSinceWm = smsCollector.collectSmsSince(lastSmsTs)
        val combinedSmsMap = LinkedHashMap<Long, SyncSmsItem>()
        for (e in unsyncedSmsEntities) {
            combinedSmsMap[e.id] = SyncSmsItem(e.id, e.address, e.body, e.ts, e.type)
        }
        for (item in newSmsSinceWm) {
            combinedSmsMap[item.id] = item
        }
        val sortedSms = combinedSmsMap.values.sortedBy { it.ts }
        ctx.pendingSmsList.addAll(sortedSms)
        ctx.trackedSmsIds.addAll(sortedSms.map { it.id })

        // 3) 통화 기록 + 통화 녹음 통합 이벤트 (Oldest-First + 최신 통화 60초 지연)
        val lastCallTs = ctx.prefs.lastCallTs.first()
        val lastRecTs = ctx.prefs.lastRecordingTs.first()
        val callCollector = CallLogCollector(applicationContext)
        callCollector.refreshCallLogCache(ctx.db, lastCallTs)
        val allRecordings = CallRecordingCollector().refreshRecordingsCache(ctx.db, lastRecTs)

        val allCallRecordings = allRecordings.filter { CallRecordingCollector.isCallRecording(it) }
        val allVoiceRecordings = allRecordings.filter { !CallRecordingCollector.isCallRecording(it) }

        val unsyncedCallEntities = cacheDao.getUnsyncedCallLogs(limit = 500)
        val newCallsSinceWm = callCollector.collectCallLogsSince(lastCallTs)
        val combinedCallMap = LinkedHashMap<Long, SyncCallLogItem>()
        for (e in unsyncedCallEntities) {
            combinedCallMap[e.id] = SyncCallLogItem(e.id, e.number, e.name, e.callType, e.durationSec, e.ts)
        }
        for (item in newCallsSinceWm) {
            combinedCallMap[item.id] = item
        }

        val eligibleCallLogs = combinedCallMap.values
            .filter { call ->
                val callEndMs = call.ts + (call.durationSec * 1000L)
                (nowMs - callEndMs) >= RECENT_CALL_GRACE_PERIOD_MS
            }
            .sortedBy { it.ts }

        val matchedCallRecPaths = mutableSetOf<String>()
        for (call in eligibleCallLogs) {
            val matchedRec = CallRecordingCollector.findMatchingCallRecording(call, allCallRecordings)
            if (matchedRec != null) {
                matchedCallRecPaths.add(matchedRec.filePath)
            }
            ctx.pendingCallEvents.add(PendingCallEvent(call, matchedRec, call.ts))
        }

        for (callRec in allCallRecordings) {
            if (!callRec.isSynced &&
                !matchedCallRecPaths.contains(callRec.filePath) &&
                (nowMs - callRec.recordedAt) >= RECENT_CALL_GRACE_PERIOD_MS &&
                File(callRec.filePath).exists()
            ) {
                ctx.pendingCallEvents.add(PendingCallEvent(null, callRec, callRec.recordedAt))
            }
        }
        ctx.pendingCallEvents.sortBy { it.eventTs }

        // 4) 카카오톡 대화방 폴더 (Oldest-First + `last_message_ts` 이후 미디어만 선별)
        val kakaoWatcher = KakaoChatFolderWatcher()
        val scannedKakaoFolders = kakaoWatcher.scanBackupFiles(lazyHash = true).sortedBy { it.modifiedAt }
        val existingKakaoCacheMap = cacheDao.getAllKakaoRooms().associateBy { it.roomName }

        for (kInfo in scannedKakaoFolders) {
            val roomName = kInfo.roomKey.ifBlank { kInfo.folderName }
            val roomPref = ctx.prefs.getRoomSyncProgress(roomName)
            val cachedRoom = existingKakaoCacheMap[roomName]
            val knownServerMsgTs = maxOf(
                roomPref.lastSyncedMessageTs,
                if (cachedRoom != null && cachedRoom.lastSharedTime > 0L) cachedRoom.lastMessageTime else 0L
            )

            val allMediaBytes = kInfo.mediaFiles.sumOf { it.length() }
            val textBytes = kInfo.file.length()
            val fullBytes = textBytes + allMediaBytes

            val eligibleMedia = if (knownServerMsgTs > 0L) {
                kInfo.mediaFiles.filter { it.lastModified() > knownServerMsgTs }
            } else {
                kInfo.mediaFiles
            }
            val pendingMediaBytes = eligibleMedia.sumOf { it.length() }
            val skippedMediaBytes = (allMediaBytes - pendingMediaBytes).coerceAtLeast(0L)

            val hasNewMessagesOrMedia = knownServerMsgTs <= 0L ||
                kInfo.lastMessageTime > knownServerMsgTs ||
                eligibleMedia.isNotEmpty() ||
                kInfo.folder.exists()

            if (hasNewMessagesOrMedia) {
                ctx.pendingKakaoTasks.add(
                    PendingKakaoTask(
                        info = kInfo,
                        roomName = roomName,
                        eligibleMediaFiles = eligibleMedia,
                        textBytes = textBytes,
                        pendingMediaBytes = pendingMediaBytes,
                        fullFolderBytes = fullBytes,
                        alreadySkippedMediaBytes = skippedMediaBytes
                    )
                )
            }
        }

        // 5) 일반 음성 녹음 (통화 녹음 제외, Oldest-First: recordedAt ASC)
        ctx.pendingVoiceRecs.addAll(
            allVoiceRecordings
                .filter { !it.isSynced && File(it.filePath).exists() }
                .sortedBy { it.recordedAt }
        )

        // 6) 삼성 헬스 개별 파일 (.csv + .json, Oldest-First: lastModified ASC)
        val healthRoot = File(Environment.getExternalStorageDirectory(), "Download/Samsung Health")
        if (healthRoot.exists() && healthRoot.isDirectory) {
            val existingHealthMap = cacheDao.getAllHealthRecords().associateBy { it.folderName }
            val healthFolders = (healthRoot.listFiles { f -> f.isDirectory } ?: emptyArray())
                .sortedBy { it.lastModified() }

            for (folder in healthFolders) {
                val lastMod = folder.lastModified()
                val cached = existingHealthMap[folder.name]
                val entity = if (cached != null && cached.lastModified == lastMod && cached.totalSizeKb > 0L) {
                    cached.copy(isOnDevice = true)
                } else {
                    val csvFiles = folder.listFiles { _, name -> name.endsWith(".csv") } ?: emptyArray()
                    val jsons = File(folder, "jsons").walkTopDown().filter { it.isFile && it.extension == "json" }.count()
                    val totalSize = folder.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024
                    val latestCsvName = csvFiles.maxByOrNull { it.lastModified() }?.name ?: ""
                    val recordType = when {
                        latestCsvName.contains("exercise") -> "운동 세션 & 워치 GPS 경로"
                        latestCsvName.contains("pedometer") -> "걸음수 & 활동량 통계"
                        latestCsvName.contains("heart_rate") -> "심박수 & 생체 기록"
                        latestCsvName.contains("sleep") -> "수면 패턴 기록"
                        else -> "삼성 헬스 통합 백업 (운동/걸음/심박/GPS)"
                    }
                    HealthSyncCacheEntity(
                        folderName = folder.name,
                        folderPath = folder.absolutePath,
                        latestRecordType = recordType,
                        lastDataTime = lastMod,
                        lastSharedTime = cached?.lastSharedTime ?: 0L,
                        csvCount = csvFiles.size,
                        jsonCount = jsons,
                        totalSizeKb = totalSize,
                        lastModified = lastMod,
                        isSynced = cached?.isSynced ?: false,
                        isOnDevice = true
                    )
                }
                if (!entity.isSynced) {
                    ctx.unsyncedHealthFolders.add(Pair(folder, entity))
                } else if (cached == null || !cached.isOnDevice) {
                    cacheDao.upsertHealthRecords(listOf(entity))
                }
            }

            for ((folder, entity) in ctx.unsyncedHealthFolders) {
                val csvs = folder.listFiles { f ->
                    f.isFile && f.name.endsWith(".csv") && (
                        f.name.contains("exercise") ||
                        f.name.contains("pedometer") ||
                        f.name.contains("heart_rate") ||
                        f.name.contains("sleep") ||
                        f.name.contains("step")
                    )
                }?.toList() ?: emptyList()
                val locJsons = File(folder, "jsons")
                    .walkTopDown()
                    .filter { it.isFile && it.name.contains("location_data") && it.extension == "json" }
                    .take(50)
                    .toList()
                val targetFiles = (csvs + locJsons).ifEmpty {
                    (folder.listFiles { f -> f.isFile && f.name.endsWith(".csv") }?.take(10) ?: emptyList())
                }.sortedBy { it.lastModified() }

                for (tf in targetFiles) {
                    ctx.pendingHealthFiles.add(Pair(tf, entity))
                }
            }
        }

        // 정밀 산출된 6대 카테고리 볼륨 개수 및 총 용량으로 SessionPlan 확정 및 전송 (is_ready = true)
        ctx.kakaoTotalCount = ctx.pendingKakaoTasks.size
        ctx.kakaoSyncedCount = 0
        ctx.kakaoTotalBytes = ctx.pendingKakaoTasks.sumOf { it.pendingTotalBytes }
        ctx.kakaoSyncedBytes = 0L

        ctx.notiTotalCount = ctx.pendingNotis.size
        ctx.notiSyncedCount = 0

        ctx.smsTotalCount = ctx.pendingSmsList.size
        ctx.smsSyncedCount = 0

        ctx.callTotalCount = ctx.pendingCallEvents.size
        ctx.callSyncedCount = 0
        ctx.callTotalBytes = ctx.pendingCallEvents.sumOf { it.pendingBytes }
        ctx.callSyncedBytes = 0L

        ctx.recTotalCount = ctx.pendingVoiceRecs.size
        ctx.recSyncedCount = 0
        ctx.recTotalBytes = ctx.pendingVoiceRecs.sumOf { it.fileSize }
        ctx.recSyncedBytes = 0L

        ctx.healthTotalCount = ctx.pendingHealthFiles.size
        ctx.healthSyncedCount = 0
        ctx.healthTotalBytes = ctx.pendingHealthFiles.sumOf { it.first.length() }
        ctx.healthSyncedBytes = 0L

        if (ctx.totalCount > 0) {
            publishSessionPlanSafely(ctx, isReady = true)
        }
    }

    /**
     * 전송 도중(충전 중) 발생한 새 이벤트를 감지하여 전체 용량/개수(예: 30M/100M 15개 -> 30M/110M 16개)를
     * 동적으로 갱신하고 서버 프로그레스바에 즉시 반영합니다.
     */
    private suspend fun checkAndProcessMidSessionEvents(ctx: SessionSyncContext) {
        var planChanged = false

        val latestPendingNotis = ctx.db.notificationDao().getPendingNotifications(limit = 200)
        val newlyArrivedNotis = latestPendingNotis.filter { !ctx.trackedNotiIds.contains(it.id) }
        if (newlyArrivedNotis.isNotEmpty()) {
            for (n in newlyArrivedNotis) {
                ctx.trackedNotiIds.add(n.id)
                ctx.pendingNotis.add(n)
            }
            ctx.notiTotalCount += newlyArrivedNotis.size
            planChanged = true
        }

        val curSmsWm = ctx.prefs.lastSmsTs.first()
        val smsCollector = SmsAndMmsCollector(applicationContext)
        val freshSms = smsCollector.collectSmsSince(curSmsWm).filter { !ctx.trackedSmsIds.contains(it.id) }
        if (freshSms.isNotEmpty()) {
            for (s in freshSms) {
                ctx.trackedSmsIds.add(s.id)
                ctx.pendingSmsList.add(s)
            }
            ctx.smsTotalCount += freshSms.size
            planChanged = true
        }

        if (planChanged) {
            publishSessionPlanSafely(ctx, isReady = true)
        }
    }

    /**
     * [Step 3-1] 알림(Notification) 가장 오래된 것부터 순차 전송
     */
    private suspend fun syncPendingNotifications(ctx: SessionSyncContext) {
        if (ctx.pendingNotis.isEmpty() || !canContinueSession(ctx.monitor)) return

        ctx.api.batchInit(
            ctx.token,
            BatchInitRequest(service = "notification", totalCount = ctx.notiTotalCount)
        )
        var idx = 0
        while (idx < ctx.pendingNotis.size && canContinueSession(ctx.monitor)) {
            val entity = ctx.pendingNotis[idx]
            val currentIdx = idx + 1
            val item = SyncNotificationItem(
                id = entity.id,
                pkg = entity.packageName,
                title = entity.title,
                text = entity.text,
                subText = entity.subText,
                ts = entity.postedAt
            )
            val res = ctx.api.syncNotifications(
                ctx.token,
                SyncNotificationsRequest(
                    items = listOf(item),
                    currentIndex = currentIdx,
                    totalCount = ctx.notiTotalCount
                )
            )
            if (res.isSuccessful && res.body()?.ok == true) {
                ctx.didTransmitData = true
                val ackIds = res.body()!!.ackIds.ifEmpty { listOf(entity.id) }
                ctx.db.notificationDao().markAsSynced(ackIds)
                ctx.prefs.updateLastNotiTs(entity.postedAt)
                ctx.notiSyncedCount++
            }
            idx++
        }
    }

    /**
     * [Step 3-2] 문자(SMS/MMS/RCS) 가장 오래된 것부터 전송
     */
    private suspend fun syncPendingSms(ctx: SessionSyncContext) {
        if (ctx.pendingSmsList.isEmpty() || !canContinueSession(ctx.monitor)) return

        ctx.api.batchInit(
            ctx.token,
            BatchInitRequest(service = "sms", totalCount = ctx.pendingSmsList.size)
        )
        val res = ctx.api.syncSms(ctx.token, SyncSmsRequest(ctx.pendingSmsList))
        if (res.isSuccessful && res.body()?.ok == true) {
            ctx.didTransmitData = true
            val maxTs = ctx.pendingSmsList.maxOf { it.ts }
            ctx.prefs.updateLastSmsTs(maxTs)
            ctx.db.syncCacheDao().markSmsSyncedByIds(ctx.pendingSmsList.map { it.id })
            ctx.smsSyncedCount += ctx.pendingSmsList.size
        }
    }

    /**
     * [Step 3-3] 통화 기록 + 통화 녹음 단일 이벤트 전송 (녹음 존재 여부에 따라 완료 판정 분기)
     */
    private suspend fun syncPendingCallEvents(ctx: SessionSyncContext) {
        if (ctx.pendingCallEvents.isEmpty() || !canContinueSession(ctx.monitor)) return

        val cacheDao = ctx.db.syncCacheDao()
        ctx.api.batchInit(
            ctx.token,
            BatchInitRequest(
                service = "call",
                totalCount = ctx.pendingCallEvents.size,
                totalBytes = ctx.callTotalBytes,
                syncedBytes = ctx.callSyncedBytes
            )
        )

        val callRecHashes = ctx.pendingCallEvents.mapNotNull { it.callRecording?.fileHash }.distinct()
        val missingCallRecHashes = if (callRecHashes.isNotEmpty()) {
            try {
                val checkRes = ctx.api.checkHashes(ctx.token, CheckHashesRequest(callRecHashes))
                if (checkRes.isSuccessful && checkRes.body() != null) {
                    val missing = checkRes.body()!!.missingHashes.toSet()
                    val alreadyOnServer = callRecHashes.filter { !missing.contains(it) }
                    if (alreadyOnServer.isNotEmpty()) {
                        cacheDao.markRecordingsSyncedByHashes(alreadyOnServer)
                    }
                    missing
                } else {
                    callRecHashes.toSet()
                }
            } catch (_: Exception) {
                callRecHashes.toSet()
            }
        } else emptySet()

        for (event in ctx.pendingCallEvents) {
            if (!canContinueSession(ctx.monitor)) break

            val callLogSentOk = if (event.callLog != null) {
                try {
                    val cRes = ctx.api.syncCallLogs(ctx.token, SyncCallLogsRequest(listOf(event.callLog)))
                    cRes.isSuccessful && cRes.body()?.ok == true
                } catch (e: Exception) {
                    Log.w("SyncService", "Failed to sync call log id=${event.callLog.id}", e)
                    false
                }
            } else {
                true
            }

            val rec = event.callRecording
            val callRecSentOk = if (rec != null) {
                val recFile = File(rec.filePath)
                if (!recFile.exists()) {
                    true
                } else if (rec.isSynced || !missingCallRecHashes.contains(rec.fileHash)) {
                    cacheDao.markRecordingsSyncedByHashes(listOf(rec.fileHash))
                    ctx.callSyncedBytes += rec.fileSize
                    true
                } else {
                    try {
                        val fBody = recFile.asRequestBody("audio/*".toMediaTypeOrNull())
                        val part = MultipartBody.Part.createFormData("file", recFile.name, fBody)
                        val hashReq = rec.fileHash.toRequestBody("text/plain".toMediaTypeOrNull())
                        val phoneReq = rec.phoneNumber.toRequestBody("text/plain".toMediaTypeOrNull())
                        val nameReq = rec.contactName.toRequestBody("text/plain".toMediaTypeOrNull())
                        val timeReq = rec.recordedAt.toString().toRequestBody("text/plain".toMediaTypeOrNull())

                        val upRes = ctx.api.uploadCallRecordingFile(ctx.token, hashReq, phoneReq, nameReq, timeReq, part)
                        if (upRes.isSuccessful && upRes.body()?.ok == true) {
                            cacheDao.markRecordingsSyncedByHashes(listOf(rec.fileHash))
                            ctx.prefs.updateLastRecordingTs(rec.recordedAt)
                            ctx.callSyncedBytes += rec.fileSize
                            true
                        } else {
                            false
                        }
                    } catch (e: Exception) {
                        Log.w("SyncService", "Failed to upload matched call recording: ${rec.fileName}", e)
                        false
                    }
                }
            } else {
                true
            }

            if (callLogSentOk && callRecSentOk) {
                ctx.didTransmitData = true
                ctx.callSyncedCount++
                if (event.callLog != null) {
                    cacheDao.markCallLogsSyncedByIds(listOf(event.callLog.id))
                    ctx.prefs.updateLastCallTs(event.callLog.ts)
                }
                publishSessionPlanSafely(ctx, isReady = true)
            }

            checkAndProcessMidSessionEvents(ctx)
        }
    }

    /**
     * [Step 3-4] 카카오톡 대화방 폴더 (가장 오래된 것부터 + `last_message_ts` 이후 사진·동영상만 전송)
     */
    private suspend fun syncPendingKakaoTasks(ctx: SessionSyncContext) {
        if (ctx.pendingKakaoTasks.isEmpty() || !canContinueSession(ctx.monitor)) return

        val cacheDao = ctx.db.syncCacheDao()
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        val totalFolders = ctx.pendingKakaoTasks.size

        ctx.api.batchInit(
            ctx.token,
            BatchInitRequest(
                service = "kakaotalk",
                totalCount = totalFolders,
                totalBytes = ctx.kakaoTotalBytes,
                syncedBytes = ctx.kakaoSyncedBytes,
                isLazyUpdate = false
            )
        )

        for ((index, task) in ctx.pendingKakaoTasks.withIndex()) {
            if (!canContinueSession(ctx.monitor)) break

            val kInfo = task.info
            val roomName = task.roomName
            val folderIdx = index + 1
            val folderReq = kInfo.folderName.toRequestBody("text/plain".toMediaTypeOrNull())

            val mediaWithHashes = task.eligibleMediaFiles.mapNotNull { file ->
                val hash = KakaoChatFolderWatcher.calculateFileHash(file)
                if (hash != null) Pair(file, hash) else null
            }
            val allHashes = mediaWithHashes.map { it.second }
            val missingHashes = if (allHashes.isNotEmpty()) {
                try {
                    val checkRes = ctx.api.checkHashes(ctx.token, CheckHashesRequest(allHashes))
                    if (checkRes.isSuccessful && checkRes.body() != null) {
                        checkRes.body()!!.missingHashes.toSet()
                    } else {
                        allHashes.toSet()
                    }
                } catch (_: Exception) {
                    allHashes.toSet()
                }
            } else emptySet()

            val alreadyOnServerMediaBytes = mediaWithHashes
                .filter { !missingHashes.contains(it.second) }
                .sumOf { it.first.length() }

            var roomSyncedBytes = task.alreadySkippedMediaBytes + alreadyOnServerMediaBytes
            ctx.kakaoSyncedBytes += alreadyOnServerMediaBytes
            ctx.prefs.updateRoomSyncProgress(
                roomName = roomName,
                syncedBytes = roomSyncedBytes,
                totalBytes = task.fullFolderBytes,
                lastSharedTs = System.currentTimeMillis()
            )

            if (alreadyOnServerMediaBytes > 0L) {
                publishSessionPlanSafely(ctx, isReady = true)
            }

            var mediaUploadSuccessCount = mediaWithHashes.size - missingHashes.size

            for ((mediaFile, hash) in mediaWithHashes) {
                if (!canContinueSession(ctx.monitor)) break
                if (!missingHashes.contains(hash)) continue
                if (!mediaFile.exists() || !mediaFile.isFile) continue
                try {
                    val takenAtStr = isoFormat.format(Date(mediaFile.lastModified()))
                    val takenReq = takenAtStr.toRequestBody("text/plain".toMediaTypeOrNull())
                    val sourceReq = "kakao_export".toRequestBody("text/plain".toMediaTypeOrNull())
                    val mBody = mediaFile.asRequestBody("application/octet-stream".toMediaTypeOrNull())
                    val mPart = MultipartBody.Part.createFormData("file", mediaFile.name, mBody)
                    val res = ctx.api.uploadMediaFile(ctx.token, takenReq, sourceReq, mPart, folderReq)
                    if (res.isSuccessful && res.body()?.ok == true) {
                        ctx.didTransmitData = true
                        val fLen = mediaFile.length()
                        roomSyncedBytes += fLen
                        ctx.kakaoSyncedBytes += fLen
                        mediaUploadSuccessCount++
                        ctx.prefs.updateRoomSyncProgress(
                            roomName = roomName,
                            syncedBytes = roomSyncedBytes,
                            totalBytes = task.fullFolderBytes,
                            lastSharedTs = System.currentTimeMillis()
                        )
                        publishSessionPlanSafely(ctx, isReady = true)
                    }
                } catch (e: Exception) {
                    Log.w("SyncService", "Failed to upload kakao media file: ${mediaFile.name}", e)
                }
                checkAndProcessMidSessionEvents(ctx)
            }

            if (!canContinueSession(ctx.monitor)) break

            val lazyTxtHash = kInfo.fileHash.ifBlank {
                KakaoChatFolderWatcher.calculateFileHash(kInfo.file) ?: ""
            }
            val fBody = kInfo.file.asRequestBody("text/plain".toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("file", kInfo.file.name, fBody)
            val hashReq = lazyTxtHash.toRequestBody("text/plain".toMediaTypeOrNull())
            val modReq = kInfo.modifiedAt.toString().toRequestBody("text/plain".toMediaTypeOrNull())
            val idxReq = folderIdx.toString().toRequestBody("text/plain".toMediaTypeOrNull())
            val totalReq = totalFolders.toString().toRequestBody("text/plain".toMediaTypeOrNull())

            try {
                val uploadRes = ctx.api.uploadKakaoChatFile(
                    ctx.token,
                    folderReq,
                    hashReq,
                    modReq,
                    idxReq,
                    totalReq,
                    part
                )
                if (uploadRes.isSuccessful && uploadRes.body()?.ok == true) {
                    ctx.didTransmitData = true
                    roomSyncedBytes += task.textBytes
                    ctx.kakaoSyncedBytes += task.textBytes
                    ctx.kakaoSyncedCount++

                    if (mediaUploadSuccessCount >= mediaWithHashes.size) {
                        roomSyncedBytes = task.fullFolderBytes
                    }
                    val sharedAt = System.currentTimeMillis()
                    ctx.prefs.updateRoomSyncProgress(
                        roomName = roomName,
                        syncedBytes = roomSyncedBytes,
                        totalBytes = task.fullFolderBytes,
                        lastSharedTs = sharedAt,
                        lastSyncedMessageTs = kInfo.lastMessageTime
                    )
                    ctx.prefs.updateLastKakaoTs(sharedAt)
                    publishSessionPlanSafely(ctx, isReady = true)

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

                    val percent = if (task.fullFolderBytes > 0L) {
                        ((roomSyncedBytes.toDouble() / task.fullFolderBytes) * 100).toInt().coerceIn(0, 100)
                    } else 100

                    cacheDao.upsertKakaoRoom(
                        KakaoRoomCacheEntity(
                            roomName = roomName,
                            folderName = kInfo.folderName,
                            filePath = kInfo.file.absolutePath,
                            fileLastModified = kInfo.modifiedAt,
                            lastMessage = kInfo.lastMessage,
                            lastMessageTime = kInfo.lastMessageTime,
                            lastSharedTime = sharedAt,
                            totalBytes = task.fullFolderBytes,
                            textBytes = task.textBytes,
                            mediaBytes = task.fullFolderBytes - task.textBytes,
                            mediaCount = kInfo.mediaFiles.size,
                            syncedBytes = roomSyncedBytes,
                            percent = percent,
                            isOnDevice = !isDeleted
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w("SyncService", "Failed to upload kakao chat file for ${kInfo.folderName}", e)
            }

            checkAndProcessMidSessionEvents(ctx)
        }
    }

    /**
     * [Step 3-5] 일반 음성 녹음(Voice Recordings) 가장 오래된 것부터 순차 전송
     */
    private suspend fun syncPendingVoiceRecordings(ctx: SessionSyncContext) {
        if (ctx.pendingVoiceRecs.isEmpty() || !canContinueSession(ctx.monitor)) return

        val cacheDao = ctx.db.syncCacheDao()
        val recordingHashes = ctx.pendingVoiceRecs.map { it.fileHash }
        val checkRes = ctx.api.checkHashes(ctx.token, CheckHashesRequest(recordingHashes))
        if (!checkRes.isSuccessful || checkRes.body() == null) return

        val missing = checkRes.body()!!.missingHashes.toSet()
        val alreadyOnServer = ctx.pendingVoiceRecs.filter { !missing.contains(it.fileHash) }
        if (alreadyOnServer.isNotEmpty()) {
            cacheDao.markRecordingsSyncedByHashes(alreadyOnServer.map { it.fileHash })
            ctx.recSyncedCount += alreadyOnServer.size
            ctx.recSyncedBytes += alreadyOnServer.sumOf { it.fileSize }
            publishSessionPlanSafely(ctx, isReady = true)
        }

        val recsToUpload = ctx.pendingVoiceRecs.filter {
            missing.contains(it.fileHash) && File(it.filePath).exists()
        }
        if (recsToUpload.isEmpty()) return

        ctx.api.batchInit(
            ctx.token,
            BatchInitRequest(
                service = "recording",
                totalCount = recsToUpload.size,
                totalBytes = ctx.recTotalBytes,
                syncedBytes = ctx.recSyncedBytes
            )
        )
        for (rec in recsToUpload) {
            if (!canContinueSession(ctx.monitor)) break
            val recFile = File(rec.filePath)
            val fBody = recFile.asRequestBody("audio/*".toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("file", recFile.name, fBody)
            val hashReq = rec.fileHash.toRequestBody("text/plain".toMediaTypeOrNull())
            val phoneReq = rec.phoneNumber.toRequestBody("text/plain".toMediaTypeOrNull())
            val nameReq = rec.contactName.toRequestBody("text/plain".toMediaTypeOrNull())
            val timeReq = rec.recordedAt.toString().toRequestBody("text/plain".toMediaTypeOrNull())

            val upRes = ctx.api.uploadCallRecordingFile(ctx.token, hashReq, phoneReq, nameReq, timeReq, part)
            if (upRes.isSuccessful && upRes.body()?.ok == true) {
                ctx.didTransmitData = true
                ctx.prefs.updateLastRecordingTs(rec.recordedAt)
                cacheDao.markRecordingsSyncedByHashes(listOf(rec.fileHash))
                ctx.recSyncedCount++
                ctx.recSyncedBytes += rec.fileSize
                publishSessionPlanSafely(ctx, isReady = true)
            }
            checkAndProcessMidSessionEvents(ctx)
        }
    }

    /**
     * [Step 3-6] 삼성 헬스 & 워치 GPS 운동 데이터 (개별 파일 단위, 가장 오래된 것부터 전송)
     */
    private suspend fun syncPendingHealthFiles(ctx: SessionSyncContext) {
        if (ctx.unsyncedHealthFolders.isEmpty() || !canContinueSession(ctx.monitor)) return

        val cacheDao = ctx.db.syncCacheDao()
        if (ctx.pendingHealthFiles.isNotEmpty()) {
            ctx.api.batchInit(
                ctx.token,
                BatchInitRequest(
                    service = "health",
                    totalCount = ctx.pendingHealthFiles.size,
                    totalBytes = ctx.healthTotalBytes,
                    syncedBytes = ctx.healthSyncedBytes
                )
            )
            val syncedFolderNames = mutableSetOf<String>()
            var latestUploadedFileTs = 0L

            for ((file, entity) in ctx.pendingHealthFiles) {
                if (!canContinueSession(ctx.monitor)) break
                try {
                    val fLen = file.length()
                    val reqBody = file.asRequestBody("text/plain".toMediaTypeOrNull())
                    val hRes = ctx.api.uploadSamsungHealth(ctx.token, reqBody)
                    if (hRes.isSuccessful && hRes.body()?.ok == true) {
                        ctx.didTransmitData = true
                        syncedFolderNames.add(entity.folderName)
                        latestUploadedFileTs = maxOf(latestUploadedFileTs, file.lastModified())
                        ctx.healthSyncedCount++
                        ctx.healthSyncedBytes += fLen
                        publishSessionPlanSafely(ctx, isReady = true)
                    }
                } catch (e: Exception) {
                    Log.w("SyncService", "Failed to upload health file: ${file.name}", e)
                }
                checkAndProcessMidSessionEvents(ctx)
            }

            val sharedNow = System.currentTimeMillis()
            val updatedEntities = ctx.unsyncedHealthFolders.map { (_, entity) ->
                if (syncedFolderNames.contains(entity.folderName)) {
                    entity.copy(
                        isSynced = true,
                        lastSharedTime = sharedNow,
                        lastDataTime = maxOf(entity.lastDataTime, latestUploadedFileTs)
                    )
                } else {
                    entity
                }
            }
            cacheDao.upsertHealthRecords(updatedEntities)
            if (syncedFolderNames.isNotEmpty()) {
                ctx.prefs.updateLastHealthTs(maxOf(sharedNow, latestUploadedFileTs))
            }
        } else {
            val sharedNow = System.currentTimeMillis()
            cacheDao.upsertHealthRecords(
                ctx.unsyncedHealthFolders.map { (_, entity) ->
                    entity.copy(isSynced = true, lastSharedTime = sharedNow)
                }
            )
        }
    }

    /**
     * 연락처(Contacts) 및 앱 사용량(App Usage) 증분 동기화
     */
    private suspend fun syncContactsAndAppUsage(ctx: SessionSyncContext) {
        if (!canContinueSession(ctx.monitor)) return

        val lastContactTs = ctx.prefs.lastContactTs.first()
        val contactList = ContactsCollector(applicationContext).collectContactsSince(lastContactTs)
        if (contactList.isNotEmpty()) {
            val res = ctx.api.syncContacts(ctx.token, SyncContactsRequest(contactList))
            if (res.isSuccessful && res.body()?.ok == true) {
                ctx.didTransmitData = true
                ctx.prefs.updateLastContactTs(contactList.maxOf { it.updatedTs })
            }
        }

        val lastUsageTs = ctx.prefs.lastUsageTs.first()
        val appUsageCollector = AppUsageCollector(applicationContext)
        if (appUsageCollector.hasUsagePermission()) {
            val usageList = appUsageCollector.collectUsageStatsSince(lastUsageTs)
            if (usageList.isNotEmpty()) {
                val res = ctx.api.syncAppUsage(ctx.token, SyncAppUsageRequest(usageList))
                if (res.isSuccessful && res.body()?.ok == true) {
                    ctx.didTransmitData = true
                    val maxTs = usageList.maxOf { it.lastTimeUsed }
                    if (maxTs > 0) ctx.prefs.updateLastUsageTs(maxTs)
                }
            }
        }
    }

    /**
     * [Step 4 & Step 5]
     * 1) 클라이언트가 더 이상 보낼 데이터가 없음을 서버에 알립니다 (`POST /api/v1/sync/complete`).
     * 2) 서버는 Background Check를 수행하여 `client_ack == false`인 카톡 대화방(이름, 최신 대화 시각)과
     *    알림/문자/통화/녹음/헬스 최신 시각을 클라이언트에 전달합니다.
     *    (구 프로토콜에서 전송 후 삭제되어 클라이언트가 존재조차 모르는 대화방도 이 단계에서 복원됨)
     * 3) 클라이언트는 이를 로컬 DB 및 설정에 반영한 즉시 서버에 `POST /api/v1/sync/ack`를 전송하여
     *    서버가 DB에 `client_ack = true` 플래그를 기록하도록 합니다.
     */
    private suspend fun completeSyncAndHandleServerAck(
        api: MyLoreApiService,
        token: String,
        sessionId: String,
        prefs: AppPreferences,
        db: AppDatabase
    ) {
        val cacheDao = db.syncCacheDao()

        try {
            val completeReq = SyncCompleteRequest(
                sessionId = sessionId,
                clientLatest = ClientLatestTimestampsDto(
                    lastKakaoTs = prefs.lastKakaoTs.first(),
                    lastNotiTs = prefs.lastNotiTs.first(),
                    lastSmsTs = prefs.lastSmsTs.first(),
                    lastCallTs = prefs.lastCallTs.first(),
                    lastRecordingTs = prefs.lastRecordingTs.first(),
                    lastHealthTs = prefs.lastHealthTs.first()
                )
            )

            val completeRes = try {
                api.completeSyncSession(token, completeReq)
            } catch (_: Exception) {
                null
            }

            if (completeRes != null && completeRes.isSuccessful && completeRes.body()?.ok == true) {
                val body = completeRes.body()!!
                val ackedRooms = mutableListOf<AckedKakaoRoomItem>()
                val ackedDomains = mutableMapOf<String, Long>()

                val roomsFromServer = body.unackedKakaoRooms.ifEmpty { body.kakaoRooms }
                if (roomsFromServer.isNotEmpty()) {
                    val existingMap = cacheDao.getAllKakaoRooms().associateBy { it.roomName }
                    val mergedRooms = mutableListOf<KakaoRoomCacheEntity>()

                    for (sRoom in roomsFromServer) {
                        if (sRoom.roomName.isBlank()) continue
                        val local = existingMap[sRoom.roomName]
                        val mergedMsgTs = maxOf(local?.lastMessageTime ?: 0L, sRoom.lastMessageTs)
                        val mergedSharedTs = maxOf(local?.lastSharedTime ?: 0L, sRoom.lastSharedTs)
                        val mergedTotalBytes = maxOf(local?.totalBytes ?: 0L, sRoom.totalBytes)
                        val mergedSyncedBytes = maxOf(local?.syncedBytes ?: 0L, sRoom.syncedBytes)

                        prefs.updateRoomSyncedMessageTs(sRoom.roomName, mergedMsgTs)

                        if (local != null) {
                            mergedRooms.add(
                                local.copy(
                                    lastMessage = sRoom.lastMessage.ifBlank { local.lastMessage },
                                    lastMessageTime = mergedMsgTs,
                                    lastSharedTime = mergedSharedTs,
                                    totalBytes = mergedTotalBytes,
                                    syncedBytes = mergedSyncedBytes
                                )
                            )
                        } else {
                            mergedRooms.add(
                                KakaoRoomCacheEntity(
                                    roomName = sRoom.roomName,
                                    folderName = sRoom.folderName,
                                    lastMessage = sRoom.lastMessage,
                                    lastMessageTime = mergedMsgTs,
                                    lastSharedTime = mergedSharedTs,
                                    totalBytes = mergedTotalBytes,
                                    syncedBytes = mergedSyncedBytes,
                                    percent = 100,
                                    isOnDevice = false
                                )
                            )
                        }

                        ackedRooms.add(
                            AckedKakaoRoomItem(
                                roomName = sRoom.roomName,
                                lastMessageTs = sRoom.lastMessageTs
                            )
                        )
                    }

                    if (mergedRooms.isNotEmpty()) {
                        cacheDao.upsertKakaoRooms(mergedRooms)
                    }
                }

                val domains = body.unackedDomains
                if (domains != null) {
                    domains.notification?.let { info ->
                        if (info.latestTs > 0L) {
                            prefs.updateLastNotiTs(info.latestTs)
                            ackedDomains["notification"] = info.latestTs
                        }
                    }
                    domains.sms?.let { info ->
                        if (info.latestTs > 0L) {
                            prefs.updateLastSmsTs(info.latestTs)
                            cacheDao.markSmsSyncedUpTo(info.latestTs)
                            ackedDomains["sms"] = info.latestTs
                        }
                    }
                    domains.call?.let { info ->
                        if (info.latestTs > 0L) {
                            prefs.updateLastCallTs(info.latestTs)
                            cacheDao.markCallLogsSyncedUpTo(info.latestTs)
                            ackedDomains["call"] = info.latestTs
                        }
                    }
                    domains.recording?.let { info ->
                        if (info.syncedHashes.isNotEmpty()) {
                            cacheDao.markRecordingsSyncedByHashes(info.syncedHashes)
                        }
                        if (info.latestTs > 0L) {
                            prefs.updateLastRecordingTs(info.latestTs)
                            cacheDao.markRecordingsSyncedUpTo(info.latestTs)
                            ackedDomains["recording"] = info.latestTs
                        }
                    }
                    domains.health?.let { info ->
                        if (info.latestTs > 0L) {
                            prefs.updateLastHealthTs(info.latestTs)
                            val folderKey = info.folderName.ifBlank { "server_health_${info.latestTs}" }
                            val existingHealth = cacheDao.getAllHealthRecords().associateBy { it.folderName }[folderKey]
                            val mergedHealth = existingHealth?.copy(
                                latestRecordType = info.latestRecordType.ifBlank { existingHealth.latestRecordType },
                                lastDataTime = maxOf(existingHealth.lastDataTime, info.latestTs),
                                isSynced = true
                            ) ?: HealthSyncCacheEntity(
                                folderName = folderKey,
                                latestRecordType = info.latestRecordType.ifBlank { "삼성 헬스 운동/생체 데이터" },
                                lastDataTime = info.latestTs,
                                lastSharedTime = System.currentTimeMillis(),
                                isSynced = true,
                                isOnDevice = false
                            )
                            cacheDao.upsertHealthRecords(listOf(mergedHealth))
                            ackedDomains["health"] = info.latestTs
                        }
                    }
                }

                if (ackedRooms.isNotEmpty() || ackedDomains.isNotEmpty()) {
                    try {
                        api.sendSyncAck(
                            token,
                            SyncAckRequest(
                                ackedKakaoRooms = ackedRooms,
                                ackedDomains = ackedDomains
                            )
                        )
                    } catch (e: Exception) {
                        Log.w("SyncService", "sendSyncAck failed: ${e.message}")
                    }
                }
                return
            }
        } catch (e: Exception) {
            Log.d("SyncService", "completeSyncSession fallback to legacy reconcile: ${e.message}")
        }

        reconcileWithServerFallback(api, token, prefs, db)
    }

    private suspend fun reconcileWithServerFallback(
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
                val ackedRooms = mutableListOf<AckedKakaoRoomItem>()
                val ackedDomains = mutableMapOf<String, Long>()

                if (manifest.kakaoRooms.isNotEmpty()) {
                    val existingMap = cacheDao.getAllKakaoRooms().associateBy { it.roomName }
                    val mergedRooms = manifest.kakaoRooms.mapNotNull { sRoom ->
                        if (sRoom.roomName.isBlank()) return@mapNotNull null
                        prefs.updateRoomSyncedMessageTs(sRoom.roomName, sRoom.lastMessageTs)
                        ackedRooms.add(AckedKakaoRoomItem(sRoom.roomName, sRoom.lastMessageTs))
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

                if (manifest.syncedRecordingHashes.isNotEmpty()) {
                    cacheDao.markRecordingsSyncedByHashes(manifest.syncedRecordingHashes)
                }
                if (manifest.lastSmsTs > 0L) {
                    prefs.updateLastSmsTs(manifest.lastSmsTs)
                    cacheDao.markSmsSyncedUpTo(manifest.lastSmsTs)
                    ackedDomains["sms"] = manifest.lastSmsTs
                }
                if (manifest.syncedSmsIds.isNotEmpty()) {
                    cacheDao.markSmsSyncedByIds(manifest.syncedSmsIds)
                }
                if (manifest.lastCallTs > 0L) {
                    prefs.updateLastCallTs(manifest.lastCallTs)
                    cacheDao.markCallLogsSyncedUpTo(manifest.lastCallTs)
                    ackedDomains["call"] = manifest.lastCallTs
                }
                if (manifest.syncedCallIds.isNotEmpty()) {
                    cacheDao.markCallLogsSyncedByIds(manifest.syncedCallIds)
                }
                if (manifest.lastNotiTs > 0L) {
                    prefs.updateLastNotiTs(manifest.lastNotiTs)
                    ackedDomains["notification"] = manifest.lastNotiTs
                }
                if (manifest.syncedNotiIds.isNotEmpty()) {
                    db.notificationDao().markAsSynced(manifest.syncedNotiIds)
                }
                if (manifest.lastRecordingTs > 0L) {
                    prefs.updateLastRecordingTs(manifest.lastRecordingTs)
                    ackedDomains["recording"] = manifest.lastRecordingTs
                }
                if (manifest.lastHealthTs > 0L) {
                    prefs.updateLastHealthTs(manifest.lastHealthTs)
                    ackedDomains["health"] = manifest.lastHealthTs
                }

                if (ackedRooms.isNotEmpty() || ackedDomains.isNotEmpty()) {
                    try {
                        api.sendSyncAck(token, SyncAckRequest(ackedRooms, ackedDomains))
                    } catch (_: Exception) {}
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
                description = "myLore 서버와 데이터 실시간 동기화 상태"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("myLore Sync")
            .setContentText("myLore 서버와 실시간 동기화 중...")
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
