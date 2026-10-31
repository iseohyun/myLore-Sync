package com.iseohyun.mylore.sync.data.remote.dto

import com.google.gson.annotations.SerializedName

data class StatusResponse(
    @SerializedName("ok") val ok: Boolean = false,
    @SerializedName("pc_name") val pcName: String = "",
    @SerializedName("version") val version: String = "",
    @SerializedName("force_full_sync") val forceFullSync: Boolean = false,
    @SerializedName("last_kakao_ts") val lastKakaoTs: Long = 0L,
    @SerializedName("last_sms_ts") val lastSmsTs: Long = 0L,
    @SerializedName("last_call_ts") val lastCallTs: Long = 0L,
    @SerializedName("last_noti_ts") val lastNotiTs: Long = 0L,
    @SerializedName("last_recording_ts") val lastRecordingTs: Long = 0L,
    @SerializedName("last_health_ts") val lastHealthTs: Long = 0L,
    @SerializedName("last_contact_ts") val lastContactTs: Long = 0L,
    @SerializedName("last_usage_ts") val lastUsageTs: Long = 0L
)

data class SyncWatermarksRequest(
    @SerializedName("last_kakao_ts") val lastKakaoTs: Long,
    @SerializedName("last_sms_ts") val lastSmsTs: Long,
    @SerializedName("last_call_ts") val lastCallTs: Long,
    @SerializedName("last_noti_ts") val lastNotiTs: Long,
    @SerializedName("last_recording_ts") val lastRecordingTs: Long,
    @SerializedName("last_health_ts") val lastHealthTs: Long,
    @SerializedName("last_contact_ts") val lastContactTs: Long,
    @SerializedName("last_usage_ts") val lastUsageTs: Long
)

/**
 * 카톡 대화방별 전송 요약 정보 (전송 후 삭제된 대화방의 최신 대화 시각 및 공유 시각 유지용)
 */
data class ServerKakaoRoomInfo(
    @SerializedName("room_name") val roomName: String = "",
    @SerializedName("folder_name") val folderName: String = "",
    @SerializedName("last_message") val lastMessage: String = "",
    @SerializedName("last_message_ts") val lastMessageTs: Long = 0L,
    @SerializedName("last_shared_ts") val lastSharedTs: Long = 0L,
    @SerializedName("total_bytes") val totalBytes: Long = 0L,
    @SerializedName("synced_bytes") val syncedBytes: Long = 0L
)

/**
 * 헬스 데이터 전송 요약 정보 (전송 후 삭제된 헬스 백업의 최신 운동 종류 및 마지막 시각 유지용)
 */
data class ServerHealthRecordInfo(
    @SerializedName("folder_name") val folderName: String = "",
    @SerializedName("latest_record_type") val latestRecordType: String = "삼성 헬스 운동/생체 데이터",
    @SerializedName("last_data_ts") val lastDataTs: Long = 0L,
    @SerializedName("last_shared_ts") val lastSharedTs: Long = 0L,
    @SerializedName("csv_count") val csvCount: Int = 0,
    @SerializedName("json_count") val jsonCount: Int = 0,
    @SerializedName("total_size_kb") val totalSizeKb: Long = 0L
)

/**
 * 유휴 시점(모든 전송 완료 후) 모바일 ➔ 서버 전송 목록 상호 동기화 요청
 */
data class SyncReconcileRequest(
    @SerializedName("last_kakao_ts") val lastKakaoTs: Long = 0L,
    @SerializedName("last_sms_ts") val lastSmsTs: Long = 0L,
    @SerializedName("last_call_ts") val lastCallTs: Long = 0L,
    @SerializedName("last_noti_ts") val lastNotiTs: Long = 0L,
    @SerializedName("last_recording_ts") val lastRecordingTs: Long = 0L,
    @SerializedName("last_health_ts") val lastHealthTs: Long = 0L,
    @SerializedName("local_kakao_rooms") val localKakaoRooms: List<ServerKakaoRoomInfo> = emptyList(),
    @SerializedName("local_health_records") val localHealthRecords: List<ServerHealthRecordInfo> = emptyList(),
    @SerializedName("local_recording_hashes") val localRecordingHashes: List<String> = emptyList()
)

/**
 * 서버가 보유한 전체 동기화 목록 요약(Manifest) 응답
 * - 모바일 앱이 빠른 초기 로딩 및 유휴 시점 서버-단말 간 싱크 정합성을 맞추는 데 사용
 */
data class SyncManifestResponse(
    @SerializedName("ok") val ok: Boolean = false,
    @SerializedName("last_kakao_ts") val lastKakaoTs: Long = 0L,
    @SerializedName("last_sms_ts") val lastSmsTs: Long = 0L,
    @SerializedName("last_call_ts") val lastCallTs: Long = 0L,
    @SerializedName("last_noti_ts") val lastNotiTs: Long = 0L,
    @SerializedName("last_recording_ts") val lastRecordingTs: Long = 0L,
    @SerializedName("last_health_ts") val lastHealthTs: Long = 0L,
    @SerializedName("kakao_rooms") val kakaoRooms: List<ServerKakaoRoomInfo> = emptyList(),
    @SerializedName("health_records") val healthRecords: List<ServerHealthRecordInfo> = emptyList(),
    @SerializedName("synced_recording_hashes") val syncedRecordingHashes: List<String> = emptyList(),
    @SerializedName("synced_sms_ids") val syncedSmsIds: List<Long> = emptyList(),
    @SerializedName("synced_call_ids") val syncedCallIds: List<Long> = emptyList(),
    @SerializedName("synced_noti_ids") val syncedNotiIds: List<Long> = emptyList()
)

data class BatchInitRequest(
    @SerializedName("service") val service: String,
    @SerializedName("total_count") val totalCount: Int
)

data class PairConfirmRequest(
    @SerializedName("device_name") val deviceName: String,
    @SerializedName("model") val model: String,
    @SerializedName("android_version") val androidVersion: String,
    @SerializedName("app_version") val appVersion: String
)

data class PairConfirmResponse(
    @SerializedName("ok") val ok: Boolean = false,
    @SerializedName("registered_device_id") val registeredDeviceId: String = ""
)

data class CheckHashesRequest(
    @SerializedName("hashes") val hashes: List<String>
)

data class CheckHashesResponse(
    @SerializedName("missing_hashes") val missingHashes: List<String> = emptyList()
)

data class SyncNotificationItem(
    @SerializedName("id") val id: Long,
    @SerializedName("pkg") val pkg: String,
    @SerializedName("title") val title: String,
    @SerializedName("text") val text: String,
    @SerializedName("sub_text") val subText: String = "",
    @SerializedName("ts") val ts: Long
)

data class SyncNotificationsRequest(
    @SerializedName("items") val items: List<SyncNotificationItem>,
    @SerializedName("current_index") val currentIndex: Int = 1,
    @SerializedName("total_count") val totalCount: Int = 1
)

data class SyncSmsItem(
    @SerializedName("id") val id: Long,
    @SerializedName("address") val address: String,
    @SerializedName("body") val body: String,
    @SerializedName("ts") val ts: Long,
    @SerializedName("type") val type: String = "inbox"
)

data class SyncSmsRequest(
    @SerializedName("items") val items: List<SyncSmsItem>
)

data class SyncCallLogItem(
    @SerializedName("id") val id: Long,
    @SerializedName("number") val number: String,
    @SerializedName("name") val name: String,
    @SerializedName("call_type") val callType: String,
    @SerializedName("duration_sec") val durationSec: Long,
    @SerializedName("ts") val ts: Long
)

data class SyncCallLogsRequest(
    @SerializedName("items") val items: List<SyncCallLogItem>
)

data class SyncResponse(
    @SerializedName("ok") val ok: Boolean = false,
    @SerializedName("ack_ids") val ackIds: List<Long> = emptyList(),
    @SerializedName("inserted_count") val insertedCount: Int = 0,
    @SerializedName("upserted_count") val upsertedCount: Int = 0,
    @SerializedName("recorded_count") val recordedCount: Int = 0,
    @SerializedName("finance_parsed") val financeParsed: Int = 0,
    @SerializedName("messages_added") val messagesAdded: Int = 0,
    @SerializedName("overlapping_count") val overlappingCount: Int = 0,
    @SerializedName("percent") val percent: Int = 0,
    @SerializedName("folder_index") val folderIndex: Int = 0,
    @SerializedName("total_folders") val totalFolders: Int = 0,
    @SerializedName("current_index") val currentIndex: Int = 0,
    @SerializedName("total_count") val totalCount: Int = 0,
    @SerializedName("verified_for_cleanup") val verifiedForCleanup: Boolean? = false
)

data class SyncContactItem(
    @SerializedName("contact_id") val contactId: String,
    @SerializedName("name") val name: String,
    @SerializedName("phones") val phones: List<String> = emptyList(),
    @SerializedName("emails") val emails: List<String> = emptyList(),
    @SerializedName("organization") val organization: String = "",
    @SerializedName("job_title") val jobTitle: String = "",
    @SerializedName("memo") val memo: String = "",
    @SerializedName("updated_ts") val updatedTs: Long
)

data class SyncContactsRequest(
    @SerializedName("items") val items: List<SyncContactItem>
)

data class SyncAppUsageItem(
    @SerializedName("date") val date: String,
    @SerializedName("pkg") val pkg: String,
    @SerializedName("app_name") val appName: String,
    @SerializedName("total_foreground_ms") val totalForegroundMs: Long,
    @SerializedName("launch_count") val launchCount: Int = 0,
    @SerializedName("last_time_used") val lastTimeUsed: Long = 0L
)

data class SyncAppUsageRequest(
    @SerializedName("items") val items: List<SyncAppUsageItem>
)
