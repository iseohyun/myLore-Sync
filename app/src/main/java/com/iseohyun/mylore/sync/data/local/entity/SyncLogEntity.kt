package com.iseohyun.mylore.sync.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_logs")
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val type: String, // "NOTIFICATION", "SMS", "CALL_LOG", "KAKAO_CHAT", "STATUS"
    val message: String,
    val isSuccess: Boolean
)
