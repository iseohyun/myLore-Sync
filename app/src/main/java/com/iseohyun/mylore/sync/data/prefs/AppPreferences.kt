package com.iseohyun.mylore.sync.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
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
        private val KEY_LAST_CONTACT_TS = longPreferencesKey("last_contact_ts")
        private val KEY_LAST_USAGE_TS = longPreferencesKey("last_usage_ts")

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

    suspend fun clearPairing() {
        context.dataStore.edit { prefs ->
            prefs[KEY_IS_PAIRED] = false
            prefs[KEY_PAIRING_TOKEN] = ""
        }
    }
}
