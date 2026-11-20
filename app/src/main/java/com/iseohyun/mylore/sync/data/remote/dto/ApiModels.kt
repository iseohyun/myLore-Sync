package com.iseohyun.mylore.sync.data.remote.dto

import com.google.gson.annotations.SerializedName

data class StatusResponse(
    @SerializedName("ok") val ok: Boolean = false,
    @SerializedName("pc_name") val pcName: String = "",
    @SerializedName("ssid") val ssid: String = "",
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
    @SerializedName("total_count") val totalCount: Int,
    @SerializedName("total_bytes") val totalBytes: Long = 0L,
    @SerializedName("synced_bytes") val syncedBytes: Long = 0L,
    @SerializedName("is_lazy_update") val isLazyUpdate: Boolean = false
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
    /** 합친 제목 (`"대화방 제목 (EXTRA_TITLE)"` 또는 EXTRA_TITLE) */
    @SerializedName("title") val title: String,
    @SerializedName("text") val text: String,
    @SerializedName("sub_text") val subText: String = "",
    @SerializedName("ts") val ts: Long,
    /** 합치기 전 EXTRA_TITLE. PC가 카톡 방 열쇠를 정할 때 씁니다(예전 PC는 무시). */
    @SerializedName("raw_title") val rawTitle: String = "",
    /** EXTRA_CONVERSATION_TITLE */
    @SerializedName("conv_title") val convTitle: String = ""
)

data class SyncNotificationsRequest(
    @SerializedName("items") val items: List<SyncNotificationItem>,
    @SerializedName("current_index") val currentIndex: Int = 1,
    @SerializedName("total_count") val totalCount: Int = 1
)

/**
 * 휴대폰에서 읽혀 사라진 카톡 알림 한 건 (`POST /api/v1/sync/kakao-read-events`).
 * PC 카톡 자동 꺼내기가 "휴대폰에서 이미 읽은 방"만 열 때 씁니다. 응답은 [SyncResponse]의 `ack_ids`입니다.
 */
data class SyncKakaoReadEventItem(
    @SerializedName("id") val id: Long,
    /** 알림 동기화의 `title`과 같은 합친 제목 */
    @SerializedName("title") val title: String,
    @SerializedName("raw_title") val rawTitle: String,
    @SerializedName("conv_title") val convTitle: String,
    @SerializedName("sub_text") val subText: String,
    /** 사라진 알림이 떴던 시각(ms) */
    @SerializedName("posted_ts") val postedTs: Long,
    /** 알림이 사라진 시각(ms) */
    @SerializedName("removed_ts") val removedTs: Long,
    /** `NotificationListenerService.REASON_*` (1 누름, 8 · 9 카톡이 지움) */
    @SerializedName("reason") val reason: Int
)

data class SyncKakaoReadEventsRequest(
    @SerializedName("items") val items: List<SyncKakaoReadEventItem>
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

// ==================== v2.0 통합 동기화 프로토콜 DTO ====================

data class VolumePlanItem(
    @SerializedName("total_count") val totalCount: Int = 0,
    @SerializedName("synced_count") val syncedCount: Int = 0,
    @SerializedName("total_bytes") val totalBytes: Long = 0L,
    @SerializedName("synced_bytes") val syncedBytes: Long = 0L
)

data class SessionVolumesDto(
    @SerializedName("kakao") val kakao: VolumePlanItem = VolumePlanItem(),
    @SerializedName("notification") val notification: VolumePlanItem = VolumePlanItem(),
    @SerializedName("sms") val sms: VolumePlanItem = VolumePlanItem(),
    @SerializedName("call") val call: VolumePlanItem = VolumePlanItem(),
    @SerializedName("recording") val recording: VolumePlanItem = VolumePlanItem(),
    @SerializedName("health") val health: VolumePlanItem = VolumePlanItem(),
    @SerializedName("photo") val photo: VolumePlanItem = VolumePlanItem()
)

data class SessionPlanRequest(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("is_ready") val isReady: Boolean,
    @SerializedName("total_bytes") val totalBytes: Long,
    @SerializedName("synced_bytes") val syncedBytes: Long,
    @SerializedName("total_count") val totalCount: Int,
    @SerializedName("synced_count") val syncedCount: Int,
    @SerializedName("volumes") val volumes: SessionVolumesDto
)

data class ClientLatestTimestampsDto(
    @SerializedName("last_kakao_ts") val lastKakaoTs: Long = 0L,
    @SerializedName("last_noti_ts") val lastNotiTs: Long = 0L,
    @SerializedName("last_sms_ts") val lastSmsTs: Long = 0L,
    @SerializedName("last_call_ts") val lastCallTs: Long = 0L,
    @SerializedName("last_recording_ts") val lastRecordingTs: Long = 0L,
    @SerializedName("last_health_ts") val lastHealthTs: Long = 0L,
    @SerializedName("last_photo_ts") val lastPhotoTs: Long = 0L
)

data class SyncCompleteRequest(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("client_latest") val clientLatest: ClientLatestTimestampsDto
)

data class UnackedDomainInfo(
    @SerializedName("latest_ts") val latestTs: Long = 0L,
    @SerializedName("synced_hashes") val syncedHashes: List<String> = emptyList(),
    @SerializedName("latest_record_type") val latestRecordType: String = "",
    @SerializedName("folder_name") val folderName: String = ""
)

data class UnackedDomainsDto(
    @SerializedName("notification") val notification: UnackedDomainInfo? = null,
    @SerializedName("sms") val sms: UnackedDomainInfo? = null,
    @SerializedName("call") val call: UnackedDomainInfo? = null,
    @SerializedName("recording") val recording: UnackedDomainInfo? = null,
    @SerializedName("health") val health: UnackedDomainInfo? = null,
    @SerializedName("photo") val photo: UnackedDomainInfo? = null
)

data class SyncCompleteResponse(
    @SerializedName("ok") val ok: Boolean = false,
    @SerializedName("has_unacked_updates") val hasUnackedUpdates: Boolean = false,
    @SerializedName("unacked_kakao_rooms") val unackedKakaoRooms: List<ServerKakaoRoomInfo> = emptyList(),
    @SerializedName("unacked_domains") val unackedDomains: UnackedDomainsDto? = null,
    // 하위 호환 필드 (manifest/reconcile 공유)
    @SerializedName("kakao_rooms") val kakaoRooms: List<ServerKakaoRoomInfo> = emptyList(),
    @SerializedName("health_records") val healthRecords: List<ServerHealthRecordInfo> = emptyList(),
    @SerializedName("synced_recording_hashes") val syncedRecordingHashes: List<String> = emptyList()
)

data class AckedKakaoRoomItem(
    @SerializedName("room_name") val roomName: String,
    @SerializedName("last_message_ts") val lastMessageTs: Long
)

data class SyncAckRequest(
    @SerializedName("acked_kakao_rooms") val ackedKakaoRooms: List<AckedKakaoRoomItem> = emptyList(),
    @SerializedName("acked_domains") val ackedDomains: Map<String, Long> = emptyMap()
)

data class SyncAckResponse(
    @SerializedName("ok") val ok: Boolean = false,
    @SerializedName("acked_room_count") val ackedRoomCount: Int = 0,
    @SerializedName("acked_domain_count") val ackedDomainCount: Int = 0
)


data class SyncLocationLogItem(
    @SerializedName("recorded_at") val recordedAt: String = "",
    @SerializedName("event_type") val eventType: String = "movement",
    @SerializedName("wifi_ssid") val wifiSsid: String = "",
    @SerializedName("wifi_bssid") val wifiBssid: String = "",
    @SerializedName("place_label") val placeLabel: String = "",
    @SerializedName("latitude") val latitude: Double? = null,
    @SerializedName("longitude") val longitude: Double? = null,
    @SerializedName("accuracy_m") val accuracyM: Double? = null,
    @SerializedName("battery_pct") val batteryPct: Int? = null,
    @SerializedName("ts") val ts: Long = 0L,
    @SerializedName("note") val note: String = ""
)

/**
 * 위치 로그 전송 요청.
 * PC 서버(`handle_sync_location_logs`)는 배열 필드명을 `items`로 읽으므로 반드시 `items`로 직렬화합니다.
 * (`logs`로 보내면 서버가 빈 요청으로 처리해 한 건도 저장하지 않습니다.)
 */
data class SyncLocationLogsRequest(
    @SerializedName("items") val logs: List<SyncLocationLogItem>
)


data class VoiceprintSessionResponse(
    @SerializedName("ok") val ok: Boolean = false,
    @SerializedName("phraseIdx") val phraseIdx: Int = 0,
    @SerializedName("totalPhrases") val totalPhrases: Int = 5,
    @SerializedName("phraseTitle") val phraseTitle: String = "",
    @SerializedName("phraseText") val phraseText: String = "",
    @SerializedName("sampleCount") val sampleCount: Int = 0,
    @SerializedName("crossAgreementScore") val crossAgreementScore: Double = 0.0,
    @SerializedName("consistencyA") val consistencyA: Double = 0.0,
    @SerializedName("consistencyB") val consistencyB: Double = 0.0,
    @SerializedName("error") val error: String? = null
)

data class VoiceprintEnrollRequest(
    @SerializedName("audioBase64") val audioBase64: String,
    @SerializedName("phraseText") val phraseText: String = "",
    @SerializedName("fileExt") val fileExt: String = "m4a"
)

data class VoiceprintEnrollResponse(
    @SerializedName("ok") val ok: Boolean = false,
    @SerializedName("sampleCount") val sampleCount: Int = 0,
    @SerializedName("crossAgreementScore") val crossAgreementScore: Double = 0.0,
    @SerializedName("consistencyA") val consistencyA: Double = 0.0,
    @SerializedName("consistencyB") val consistencyB: Double = 0.0,
    @SerializedName("nextPhraseIdx") val nextPhraseIdx: Int = 0,
    @SerializedName("totalPhrases") val totalPhrases: Int = 5,
    @SerializedName("nextPhraseTitle") val nextPhraseTitle: String = "",
    @SerializedName("nextPhraseText") val nextPhraseText: String = "",
    @SerializedName("error") val error: String? = null
)


/** 에이닷 전화 "텍스트로 저장" 파일 한 건 (통화 요약 + 화자별 스크립트) */
data class SyncCallTranscriptItem(
    /** 원래 파일 이름. 통화 녹음 파일과 이름 규칙이 같아 PC가 통화를 찾는 데 씁니다. */
    @SerializedName("file_name") val fileName: String,
    /** UTF-8로 변환한 본문 */
    @SerializedName("text") val text: String,
    @SerializedName("modified_at") val modifiedAt: Long = 0L
)

data class SyncCallTranscriptsRequest(
    @SerializedName("items") val items: List<SyncCallTranscriptItem>
)

data class SyncCallTranscriptsResponse(
    @SerializedName("ok") val ok: Boolean = false,
    @SerializedName("saved_count") val savedCount: Int = 0,
    /** PC가 받았다고 확인한 파일 이름 (이미 같은 내용으로 저장돼 있던 것 포함) */
    @SerializedName("ack_names") val ackNames: List<String> = emptyList()
)
