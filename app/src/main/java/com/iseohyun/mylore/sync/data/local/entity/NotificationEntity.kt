package com.iseohyun.mylore.sync.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_notifications")
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    /** 합친 제목 (`"대화방 제목 (EXTRA_TITLE)"` 또는 EXTRA_TITLE) */
    val title: String,
    val text: String,
    val subText: String = "",
    val postedAt: Long,
    val isSynced: Boolean = false,
    val syncedAt: Long = 0L,
    /** 합치기 전 EXTRA_TITLE. PC가 카톡 방 열쇠를 정할 때 씁니다. DB 버전 10 전에 받은 알림은 빈 값입니다. */
    val rawTitle: String = "",
    /** EXTRA_CONVERSATION_TITLE. DB 버전 10 전에 받은 알림은 빈 값입니다. */
    val convTitle: String = ""
)
