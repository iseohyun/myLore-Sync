package com.iseohyun.mylore.sync.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 휴대폰에서 읽혀서 사라진 카톡 알림 한 건 (PC로 보내기 전까지 보관).
 *
 * PC(myLore)의 카톡 자동 꺼내기는 방을 열면 읽음 처리되므로 "휴대폰에서 이미 읽은 방"만 엽니다.
 * 이 기록으로 PC가 방마다 휴대폰에서 읽은 시각을 압니다. PC가 받았다고 확인(`ack_ids`)한 것만 지웁니다.
 */
@Entity(tableName = "pending_kakao_read_events")
data class KakaoReadEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 알림 동기화와 같은 합친 제목 (`"대화방 제목 (EXTRA_TITLE)"` 또는 EXTRA_TITLE) */
    val title: String,
    /** EXTRA_TITLE */
    val rawTitle: String,
    /** EXTRA_CONVERSATION_TITLE */
    val convTitle: String,
    /** EXTRA_SUB_TEXT */
    val subText: String,
    /** 사라진 알림이 떴던 시각(ms) */
    val postedAt: Long,
    /** 알림이 사라진 시각(ms) */
    val removedAt: Long,
    /** 사라진 이유 (`NotificationListenerService.REASON_*`: 1 누름, 8 · 9 카톡이 지움) */
    val reason: Int
)
