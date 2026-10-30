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
