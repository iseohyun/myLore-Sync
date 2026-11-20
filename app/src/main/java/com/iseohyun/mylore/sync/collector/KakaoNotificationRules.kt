package com.iseohyun.mylore.sync.collector

/**
 * 알림 제목과 카톡 읽음 판단에 쓰는 순수 규칙. 안드로이드 API를 쓰지 않아 JVM 단위 시험으로 확인합니다.
 *
 * 카톡 읽음 기록은 PC(myLore)의 카톡 자동 꺼내기가 "휴대폰에서 이미 읽은 방"만 열 때 씁니다.
 * 방 열쇠와 합친 제목은 PC와 똑같이 만들어야 하므로, 바꿀 때는 두 쪽 약속 문서
 * ("휴대폰 → PC 카톡 읽음 알림 규칙")를 먼저 고칩니다.
 */
object KakaoNotificationRules {

    const val KAKAO_PACKAGE = "com.kakao.talk"

    /** 알림을 눌러 열었음 (`NotificationListenerService.REASON_CLICK`) */
    const val REASON_CLICK = 1

    /** 카톡이 스스로 지웠음 (`REASON_APP_CANCEL`). 휴대폰이나 PC에서 그 방을 읽으면 이렇게 지웁니다. */
    const val REASON_APP_CANCEL = 8

    /** 카톡이 알림을 한꺼번에 지웠음 (`REASON_APP_CANCEL_ALL`) */
    const val REASON_APP_CANCEL_ALL = 9

    /** 알림이 사라진 뒤, 새 메시지로 같은 방 알림이 다시 떴는지 보기까지 기다리는 시간 */
    const val REPLACED_CHECK_DELAY_MS = 3_000L

    /**
     * 알림이 사라진 이유가 "읽음"인지 봅니다.
     * 손으로 밀어 지운 것(`REASON_CANCEL` 2, `REASON_CANCEL_ALL` 3)과 그 밖의 이유는 읽음이 아닙니다.
     */
    fun isReadReason(reason: Int): Boolean =
        reason == REASON_CLICK || reason == REASON_APP_CANCEL || reason == REASON_APP_CANCEL_ALL

    /**
     * 알림 동기화와 읽음 기록에 쓰는 합친 제목.
     * 대화방 제목이 비지 않았고 [rawTitle](EXTRA_TITLE)과 다르면 `"대화방 제목 (EXTRA_TITLE)"`, 아니면 [rawTitle]입니다.
     */
    fun combinedTitle(rawTitle: String, convTitle: String): String =
        if (convTitle.isNotBlank() && convTitle != rawTitle) "$convTitle ($rawTitle)" else rawTitle

    /**
     * 카톡 방 열쇠: [convTitle] → [subText] → [rawTitle] 가운데 비지 않은 첫 값입니다. 셋 다 비면 [title]입니다.
     * 넷 다 비면 빈 문자열을 돌려줍니다(어느 방인지 알 수 없음).
     */
    fun roomKey(convTitle: String, subText: String, rawTitle: String, title: String = ""): String =
        sequenceOf(convTitle, subText, rawTitle, title)
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: ""

    /**
     * 지금 떠 있는 카톡 알림들의 방 열쇠([activeRoomKeys]) 가운데 [roomKey]와 같은 것이 있는지 봅니다.
     * 있으면 읽힌 것이 아니라 새 메시지 때문에 알림이 바뀌면서 사라진 것입니다.
     */
    fun hasSameRoom(roomKey: String, activeRoomKeys: Iterable<String>): Boolean =
        roomKey.isNotEmpty() && activeRoomKeys.any { it == roomKey }
}

/**
 * 알림 extras에서 읽은 제목 칸들. 모두 앞뒤 공백을 지운 값이고, 없으면 빈 문자열입니다.
 *
 * @property rawTitle `Notification.EXTRA_TITLE` (1:1 방이면 보통 상대 이름)
 * @property convTitle `Notification.EXTRA_CONVERSATION_TITLE`
 * @property subText `Notification.EXTRA_SUB_TEXT`
 */
data class NotificationTitleFields(
    val rawTitle: String,
    val convTitle: String,
    val subText: String
) {
    /** [KakaoNotificationRules.combinedTitle]로 만든 합친 제목 */
    val title: String
        get() = KakaoNotificationRules.combinedTitle(rawTitle, convTitle)

    /** [KakaoNotificationRules.roomKey]로 정한 방 열쇠 */
    val roomKey: String
        get() = KakaoNotificationRules.roomKey(convTitle, subText, rawTitle, title)
}
