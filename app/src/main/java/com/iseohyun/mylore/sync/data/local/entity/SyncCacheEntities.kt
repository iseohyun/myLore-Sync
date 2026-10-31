package com.iseohyun.mylore.sync.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 카카오톡 대화방 동기화 캐시 (현재 기기 내 공유 가능 폴더 + 전송 후 삭제된 과거 대화방 이력 유지)
 */
@Entity(
    tableName = "kakao_room_cache",
    indices = [Index(value = ["lastMessageTime"]), Index(value = ["lastSharedTime"])]
)
data class KakaoRoomCacheEntity(
    @PrimaryKey
    val roomName: String,
    val folderName: String = "",
    val filePath: String = "",
    val fileLastModified: Long = 0L,
    val lastMessage: String = "",
    val lastMessageTime: Long = 0L,
    val lastSharedTime: Long = 0L,
    val totalBytes: Long = 0L,
    val textBytes: Long = 0L,
    val mediaBytes: Long = 0L,
    val mediaCount: Int = 0,
    val syncedBytes: Long = 0L,
    val percent: Int = 0,
    val isOnDevice: Boolean = true
)

/**
 * 문자(SMS / MMS / RCS) 로컬 캐시 (기존 내용 즉시 표시 + 신규 문자만 증분 조회)
 */
@Entity(
    tableName = "sms_cache",
    indices = [Index(value = ["ts"]), Index(value = ["isSynced"])]
)
data class SmsCacheEntity(
    @PrimaryKey
    val id: Long,
    val address: String,
    val body: String,
    val ts: Long,
    val type: String,
    val isSynced: Boolean = false
)

/**
 * 통화 이력(CallLog) 로컬 캐시 (기존 내용 즉시 표시 + 신규 통화만 증분 조회)
 */
@Entity(
    tableName = "call_log_cache",
    indices = [Index(value = ["ts"]), Index(value = ["isSynced"])]
)
data class CallLogCacheEntity(
    @PrimaryKey
    val id: Long,
    val number: String,
    val name: String,
    val callType: String,
    val durationSec: Long,
    val ts: Long,
    val isSynced: Boolean = false
)

/**
 * 통화 녹음 및 음성 녹음 로컬 캐시 (파일 크기/수정시각 기반 SHA-256 해시 캐싱으로 재조회 지연 제거)
 */
@Entity(
    tableName = "recording_cache",
    indices = [Index(value = ["recordedAt"]), Index(value = ["fileHash"]), Index(value = ["isSynced"])]
)
data class RecordingCacheEntity(
    @PrimaryKey
    val filePath: String,
    val fileName: String,
    val parentFolderName: String,
    val fileSize: Long,
    val lastModified: Long,
    val fileHash: String,
    val phoneNumber: String,
    val contactName: String,
    val recordedAt: Long,
    val isSynced: Boolean = false
)

/**
 * 삼성 헬스 & 워치 운동 데이터 캐시 (전송 후 삭제되더라도 최신 운동/파일 종류 및 마지막 데이터 시각 유지)
 */
@Entity(
    tableName = "health_sync_cache",
    indices = [Index(value = ["lastDataTime"]), Index(value = ["lastSharedTime"])]
)
data class HealthSyncCacheEntity(
    @PrimaryKey
    val folderName: String,
    val folderPath: String = "",
    val latestRecordType: String = "삼성 헬스 운동/생체 데이터",
    val lastDataTime: Long = 0L,
    val lastSharedTime: Long = 0L,
    val csvCount: Int = 0,
    val jsonCount: Int = 0,
    val totalSizeKb: Long = 0L,
    val lastModified: Long = 0L,
    val isSynced: Boolean = false,
    val isOnDevice: Boolean = true
)
