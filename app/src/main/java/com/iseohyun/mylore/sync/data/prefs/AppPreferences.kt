package com.iseohyun.mylore.sync.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.iseohyun.mylore.sync.BuildConfig
import com.iseohyun.mylore.sync.data.remote.dto.StatusResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class RoomSyncProgress(
    val syncedBytes: Long,
    val totalBytes: Long,
    val lastSharedTs: Long,
    val lastSyncedMessageTs: Long = 0L
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mylore_preferences")

class AppPreferences(private val context: Context) {

    companion object {
        private val KEY_PC_IP = stringPreferencesKey("pc_ip")
        private val KEY_PC_PORT = intPreferencesKey("pc_port")
        private val KEY_PAIRING_TOKEN = stringPreferencesKey("pairing_token")
        private val KEY_SERVER_SSID = stringPreferencesKey("server_ssid")
        private val KEY_SERVER_WIFI_PW = stringPreferencesKey("server_wifi_pw")
        private val KEY_IS_PAIRED = booleanPreferencesKey("is_paired")
        private val KEY_PC_NAME = stringPreferencesKey("pc_name")

        private val KEY_LAST_KAKAO_TS = longPreferencesKey("last_kakao_ts")
        private val KEY_LAST_SMS_TS = longPreferencesKey("last_sms_ts")
        private val KEY_LAST_CALL_TS = longPreferencesKey("last_call_ts")
        private val KEY_LAST_NOTI_TS = longPreferencesKey("last_noti_ts")
        private val KEY_LAST_RECORDING_TS = longPreferencesKey("last_recording_ts")
        private val KEY_LAST_HEALTH_TS = longPreferencesKey("last_health_ts")
        private val KEY_LAST_PHOTO_TS = longPreferencesKey("last_photo_ts")
        private val KEY_LAST_GPS_COLLECT_TS = longPreferencesKey("last_gps_collect_ts")
        private val KEY_GOOGLE_MAPS_API_KEY = stringPreferencesKey("google_maps_api_key")
        private val KEY_LAST_CONTACT_TS = longPreferencesKey("last_contact_ts")
        private val KEY_LAST_USAGE_TS = longPreferencesKey("last_usage_ts")

        private val KEY_CANDIDATE_RECORDING_DIRS = stringSetPreferencesKey("candidate_recording_dirs")
        private val KEY_ACTIVE_RECORDING_DIRS = stringSetPreferencesKey("active_recording_dirs")
        private val KEY_RECORDING_HASH_ONLY_RESET_V2 = booleanPreferencesKey("recording_hash_only_reset_v2")

        private val KEY_CANDIDATE_PHOTO_DIRS = stringSetPreferencesKey("candidate_photo_dirs")
        private val KEY_ACTIVE_PHOTO_DIRS = stringSetPreferencesKey("active_photo_dirs")

        const val DEFAULT_PORT = 18443
    }

    val pcIp: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_PC_IP] ?: ""
    }

    val pcPort: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_PC_PORT] ?: DEFAULT_PORT
    }

    val pairingToken: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_PAIRING_TOKEN] ?: ""
    }

    val serverSsid: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SERVER_SSID] ?: ""
    }

    val serverWifiPw: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SERVER_WIFI_PW] ?: ""
    }

    val isPaired: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_IS_PAIRED] ?: false
    }

    val pcName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_PC_NAME] ?: "MYLORE-PC"
    }

    val lastKakaoTs: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_KAKAO_TS] ?: 0L
    }

    val lastSmsTs: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_SMS_TS] ?: 0L
    }

    val lastCallTs: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_CALL_TS] ?: 0L
    }

    val lastNotiTs: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_NOTI_TS] ?: 0L
    }

    val lastRecordingTs: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_RECORDING_TS] ?: 0L
    }

    val lastHealthTs: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_HEALTH_TS] ?: 0L
    }

    val lastPhotoTs: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_PHOTO_TS] ?: 0L
    }

    val lastGpsCollectTs: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_GPS_COLLECT_TS] ?: 0L
    }

    suspend fun updateLastGpsCollectTs(ts: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_GPS_COLLECT_TS] = ts
        }
    }

    val googleMapsApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_GOOGLE_MAPS_API_KEY] ?: BuildConfig.MAPS_API_KEY
    }

    suspend fun updateGoogleMapsApiKey(key: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_GOOGLE_MAPS_API_KEY] = key.trim()
        }
    }

    val lastContactTs: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_CONTACT_TS] ?: 0L
    }

    val lastUsageTs: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_USAGE_TS] ?: 0L
    }

    suspend fun savePairingInfo(
        ip: String,
        port: Int,
        token: String,
        pcName: String = "MYLORE-PC",
        serverSsid: String = "",
        serverWifiPw: String = ""
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PC_IP] = ip
            prefs[KEY_PC_PORT] = port
            prefs[KEY_PAIRING_TOKEN] = token
            prefs[KEY_PC_NAME] = pcName
            prefs[KEY_IS_PAIRED] = true
            if (serverSsid.isNotBlank()) {
                prefs[KEY_SERVER_SSID] = serverSsid
            }
            if (serverWifiPw.isNotBlank()) {
                prefs[KEY_SERVER_WIFI_PW] = serverWifiPw
            }
        }
    }

    private fun getRoomKeyHash(roomName: String): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("MD5")
            digest.digest(roomName.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            roomName.filter { it.isLetterOrDigit() }.take(32)
        }
    }

    suspend fun getRoomSyncProgress(roomName: String): RoomSyncProgress {
        val hash = getRoomKeyHash(roomName)
        val keySynced = longPreferencesKey("kakao_room_${hash}_synced")
        val keyTotal = longPreferencesKey("kakao_room_${hash}_total")
        val keyTs = longPreferencesKey("kakao_room_${hash}_ts")
        val keyMsgTs = longPreferencesKey("kakao_room_${hash}_msg_ts")
        val prefs = context.dataStore.data.first()
        return RoomSyncProgress(
            syncedBytes = prefs[keySynced] ?: 0L,
            totalBytes = prefs[keyTotal] ?: 0L,
            lastSharedTs = prefs[keyTs] ?: 0L,
            lastSyncedMessageTs = prefs[keyMsgTs] ?: 0L
        )
    }

    suspend fun updateRoomSyncProgress(
        roomName: String,
        syncedBytes: Long,
        totalBytes: Long,
        lastSharedTs: Long,
        lastSyncedMessageTs: Long = 0L
    ) {
        val hash = getRoomKeyHash(roomName)
        val keySynced = longPreferencesKey("kakao_room_${hash}_synced")
        val keyTotal = longPreferencesKey("kakao_room_${hash}_total")
        val keyTs = longPreferencesKey("kakao_room_${hash}_ts")
        val keyMsgTs = longPreferencesKey("kakao_room_${hash}_msg_ts")
        context.dataStore.edit { prefs ->
            prefs[keySynced] = syncedBytes
            prefs[keyTotal] = totalBytes
            prefs[keyTs] = lastSharedTs
            if (lastSyncedMessageTs > 0L) {
                prefs[keyMsgTs] = maxOf(prefs[keyMsgTs] ?: 0L, lastSyncedMessageTs)
            }
        }
    }

    suspend fun updateRoomSyncedMessageTs(roomName: String, lastMessageTs: Long) {
        if (lastMessageTs <= 0L) return
        val hash = getRoomKeyHash(roomName)
        val keyMsgTs = longPreferencesKey("kakao_room_${hash}_msg_ts")
        context.dataStore.edit { prefs ->
            prefs[keyMsgTs] = maxOf(prefs[keyMsgTs] ?: 0L, lastMessageTs)
        }
    }

    suspend fun updateLastKakaoTs(ts: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_KAKAO_TS] = maxOf(prefs[KEY_LAST_KAKAO_TS] ?: 0L, ts)
        }
    }

    suspend fun updateLastSmsTs(ts: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_SMS_TS] = maxOf(prefs[KEY_LAST_SMS_TS] ?: 0L, ts)
        }
    }

    suspend fun updateLastCallTs(ts: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_CALL_TS] = maxOf(prefs[KEY_LAST_CALL_TS] ?: 0L, ts)
        }
    }

    suspend fun updateLastNotiTs(ts: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_NOTI_TS] = maxOf(prefs[KEY_LAST_NOTI_TS] ?: 0L, ts)
        }
    }

    suspend fun updateLastRecordingTs(ts: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_RECORDING_TS] = maxOf(prefs[KEY_LAST_RECORDING_TS] ?: 0L, ts)
        }
    }

    suspend fun updateLastHealthTs(ts: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_HEALTH_TS] = maxOf(prefs[KEY_LAST_HEALTH_TS] ?: 0L, ts)
        }
    }

    suspend fun updateLastPhotoTs(ts: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_PHOTO_TS] = maxOf(prefs[KEY_LAST_PHOTO_TS] ?: 0L, ts)
        }
    }

    suspend fun updateLastContactTs(ts: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_CONTACT_TS] = maxOf(prefs[KEY_LAST_CONTACT_TS] ?: 0L, ts)
        }
    }

    suspend fun updateLastUsageTs(ts: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_USAGE_TS] = maxOf(prefs[KEY_LAST_USAGE_TS] ?: 0L, ts)
        }
    }

    /**
     * 서버와 서비스별 전송 완료 시점(워터마크)을 상호 공유·동기화합니다.
     * 서버가 전체 재동기화(forceFullSync)를 요청한 경우 모든 워터마크를 0으로 초기화합니다.
     */
    suspend fun reconcileWatermarksWithServer(status: StatusResponse) {
        context.dataStore.edit { prefs ->
            if (status.ssid.isNotBlank()) {
                prefs[KEY_SERVER_SSID] = status.ssid
            }
            if (status.forceFullSync) {
                prefs[KEY_LAST_KAKAO_TS] = 0L
                prefs[KEY_LAST_SMS_TS] = 0L
                prefs[KEY_LAST_CALL_TS] = 0L
                prefs[KEY_LAST_NOTI_TS] = 0L
                prefs[KEY_LAST_RECORDING_TS] = 0L
                prefs[KEY_LAST_HEALTH_TS] = 0L
                prefs[KEY_LAST_PHOTO_TS] = 0L
                prefs[KEY_LAST_CONTACT_TS] = 0L
                prefs[KEY_LAST_USAGE_TS] = 0L
            } else {
                prefs[KEY_LAST_KAKAO_TS] = maxOf(prefs[KEY_LAST_KAKAO_TS] ?: 0L, status.lastKakaoTs)
                prefs[KEY_LAST_SMS_TS] = maxOf(prefs[KEY_LAST_SMS_TS] ?: 0L, status.lastSmsTs)
                prefs[KEY_LAST_CALL_TS] = maxOf(prefs[KEY_LAST_CALL_TS] ?: 0L, status.lastCallTs)
                prefs[KEY_LAST_NOTI_TS] = maxOf(prefs[KEY_LAST_NOTI_TS] ?: 0L, status.lastNotiTs)
                prefs[KEY_LAST_RECORDING_TS] = maxOf(prefs[KEY_LAST_RECORDING_TS] ?: 0L, status.lastRecordingTs)
                prefs[KEY_LAST_HEALTH_TS] = maxOf(prefs[KEY_LAST_HEALTH_TS] ?: 0L, status.lastHealthTs)
                prefs[KEY_LAST_CONTACT_TS] = maxOf(prefs[KEY_LAST_CONTACT_TS] ?: 0L, status.lastContactTs)
                prefs[KEY_LAST_USAGE_TS] = maxOf(prefs[KEY_LAST_USAGE_TS] ?: 0L, status.lastUsageTs)
            }
        }
    }

    /**
     * 통화/음성 녹음 탐색을 위한 후보(참고) 폴더 경로 목록을 반환합니다.
     * 저장된 후보 목록이 없으면 기본 후보 목록으로 초기화하여 저장합니다.
     */
    suspend fun getOrInitCandidateRecordingDirs(defaultCandidates: List<String>): Set<String> {
        val prefs = context.dataStore.data.first()
        val stored = prefs[KEY_CANDIDATE_RECORDING_DIRS]
        if (!stored.isNullOrEmpty()) {
            // 새로 추가된 기본 후보가 있으면 병합
            val merged = stored + defaultCandidates
            if (merged.size != stored.size) {
                context.dataStore.edit { it[KEY_CANDIDATE_RECORDING_DIRS] = merged }
            }
            return merged
        }
        val initialSet = defaultCandidates.toSet()
        context.dataStore.edit { it[KEY_CANDIDATE_RECORDING_DIRS] = initialSet }
        return initialSet
    }

    /**
     * 후보 폴더 목록에 새로운 상대/절대 경로를 추가합니다.
     */
    suspend fun addCandidateRecordingDir(path: String) {
        if (path.isBlank()) return
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_CANDIDATE_RECORDING_DIRS] ?: emptySet()
            prefs[KEY_CANDIDATE_RECORDING_DIRS] = current + path.trim()
        }
    }

    /**
     * 앱 초기 실행 시 실제 존재가 확인되어 기본 조회 폴더로 지정된 활성 폴더 목록(절대 경로)을 저장/조회합니다.
     */
    val activeRecordingDirsFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_RECORDING_DIRS] ?: emptySet()
    }

    suspend fun getActiveRecordingDirs(): Set<String> {
        return context.dataStore.data.first()[KEY_ACTIVE_RECORDING_DIRS] ?: emptySet()
    }

    suspend fun saveActiveRecordingDirs(activePaths: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ACTIVE_RECORDING_DIRS] = activePaths
        }
    }

    /**
     * 사진/동영상 탐색을 위한 후보(참고) 폴더 경로 목록을 반환합니다.
     */
    suspend fun getOrInitCandidatePhotoDirs(defaultCandidates: List<String>): Set<String> {
        val prefs = context.dataStore.data.first()
        val stored = prefs[KEY_CANDIDATE_PHOTO_DIRS]
        if (!stored.isNullOrEmpty()) {
            val merged = stored + defaultCandidates
            if (merged.size != stored.size) {
                context.dataStore.edit { it[KEY_CANDIDATE_PHOTO_DIRS] = merged }
            }
            return merged
        }
        val initialSet = defaultCandidates.toSet()
        context.dataStore.edit { it[KEY_CANDIDATE_PHOTO_DIRS] = initialSet }
        return initialSet
    }

    val activePhotoDirsFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACTIVE_PHOTO_DIRS] ?: emptySet()
    }

    suspend fun saveActivePhotoDirs(activePaths: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ACTIVE_PHOTO_DIRS] = activePaths
        }
    }

    /**
     * 과거 워터마크(`lastRecordingTs`) 비교 버그로 인해 미전송 녹음(예: TPhoneCallRecords)이
     * 로컬 DB에서 `isSynced = true`로 잘못 마킹된 상태를 1회 초기화하여 서버 해시 대조를 거치도록 합니다.
     */
    suspend fun consumeRecordingHashOnlyResetNeeded(): Boolean {
        val prefs = context.dataStore.data.first()
        if (prefs[KEY_RECORDING_HASH_ONLY_RESET_V2] == true) return false
        context.dataStore.edit {
            it[KEY_RECORDING_HASH_ONLY_RESET_V2] = true
            it[KEY_LAST_RECORDING_TS] = 0L
        }
        return true
    }

    suspend fun clearPairing() {
        context.dataStore.edit { prefs ->
            prefs[KEY_IS_PAIRED] = false
            prefs[KEY_PAIRING_TOKEN] = ""
        }
    }
}
