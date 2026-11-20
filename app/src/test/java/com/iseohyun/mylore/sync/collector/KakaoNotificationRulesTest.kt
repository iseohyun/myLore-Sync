package com.iseohyun.mylore.sync.collector

import android.service.notification.NotificationListenerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 카톡 읽음 기록 규칙 시험. 이름은 모두 가짜 예시입니다.
 */
class KakaoNotificationRulesTest {

    // ---- 읽음 이유 거르기 ----

    @Test
    fun readReasons_matchPlatformConstants() {
        assertEquals(NotificationListenerService.REASON_CLICK, KakaoNotificationRules.REASON_CLICK)
        assertEquals(NotificationListenerService.REASON_APP_CANCEL, KakaoNotificationRules.REASON_APP_CANCEL)
        assertEquals(NotificationListenerService.REASON_APP_CANCEL_ALL, KakaoNotificationRules.REASON_APP_CANCEL_ALL)
    }

    @Test
    fun isReadReason_acceptsClickAndAppCancel() {
        assertTrue(KakaoNotificationRules.isReadReason(NotificationListenerService.REASON_CLICK))
        assertTrue(KakaoNotificationRules.isReadReason(NotificationListenerService.REASON_APP_CANCEL))
        assertTrue(KakaoNotificationRules.isReadReason(NotificationListenerService.REASON_APP_CANCEL_ALL))
    }

    @Test
    fun isReadReason_rejectsSwipeAndOtherReasons() {
        val notRead = listOf(
            0,
            NotificationListenerService.REASON_CANCEL, // 손으로 밀어 지움
            NotificationListenerService.REASON_CANCEL_ALL, // 모두 지우기
            NotificationListenerService.REASON_ERROR,
            NotificationListenerService.REASON_PACKAGE_CHANGED,
            NotificationListenerService.REASON_USER_STOPPED,
            NotificationListenerService.REASON_PACKAGE_BANNED,
            NotificationListenerService.REASON_LISTENER_CANCEL,
            NotificationListenerService.REASON_LISTENER_CANCEL_ALL,
            NotificationListenerService.REASON_GROUP_SUMMARY_CANCELED,
            NotificationListenerService.REASON_GROUP_OPTIMIZATION,
            NotificationListenerService.REASON_PACKAGE_SUSPENDED,
            NotificationListenerService.REASON_PROFILE_TURNED_OFF,
            NotificationListenerService.REASON_UNAUTOBUNDLED,
            NotificationListenerService.REASON_CHANNEL_BANNED,
            NotificationListenerService.REASON_SNOOZED,
            NotificationListenerService.REASON_TIMEOUT
        )
        for (reason in notRead) {
            assertFalse("reason=$reason", KakaoNotificationRules.isReadReason(reason))
        }
    }

    // ---- 합친 제목 만들기 ----

    @Test
    fun combinedTitle_withoutConversationTitle_isRawTitle() {
        assertEquals("친구A", KakaoNotificationRules.combinedTitle(rawTitle = "친구A", convTitle = ""))
        assertEquals("친구A", KakaoNotificationRules.combinedTitle(rawTitle = "친구A", convTitle = "   "))
    }

    @Test
    fun combinedTitle_withDifferentConversationTitle_putsSenderInParentheses() {
        assertEquals(
            "주말 등산 모임 (보낸사람B)",
            KakaoNotificationRules.combinedTitle(rawTitle = "보낸사람B", convTitle = "주말 등산 모임")
        )
    }

    @Test
    fun combinedTitle_withSameConversationTitle_isRawTitle() {
        assertEquals("친구A", KakaoNotificationRules.combinedTitle(rawTitle = "친구A", convTitle = "친구A"))
    }

    // ---- 방 열쇠 ----

    @Test
    fun roomKey_oneToOneRoom_isRawTitle() {
        assertEquals("친구A", KakaoNotificationRules.roomKey(convTitle = "", subText = "", rawTitle = "친구A", title = "친구A"))
    }

    @Test
    fun roomKey_prefersConversationTitle() {
        assertEquals(
            "주말 등산 모임",
            KakaoNotificationRules.roomKey(convTitle = "주말 등산 모임", subText = "다른 방", rawTitle = "보낸사람B")
        )
    }

    @Test
    fun roomKey_usesSubTextWhenNoConversationTitle() {
        assertEquals(
            "주말 등산 모임",
            KakaoNotificationRules.roomKey(convTitle = "", subText = "주말 등산 모임", rawTitle = "보낸사람B")
        )
    }

    @Test
    fun roomKey_skipsBlankValuesAndTrims() {
        assertEquals("친구A", KakaoNotificationRules.roomKey(convTitle = "  ", subText = "\t", rawTitle = " 친구A "))
    }

    @Test
    fun roomKey_fallsBackToTitle_thenEmpty() {
        assertEquals("예전 제목", KakaoNotificationRules.roomKey(convTitle = "", subText = "", rawTitle = "", title = "예전 제목"))
        assertEquals("", KakaoNotificationRules.roomKey(convTitle = "", subText = "", rawTitle = "", title = ""))
    }

    @Test
    fun titleFields_buildTitleAndRoomKeyTheSameWay() {
        val group = NotificationTitleFields(rawTitle = "보낸사람B", convTitle = "주말 등산 모임", subText = "")
        assertEquals("주말 등산 모임 (보낸사람B)", group.title)
        assertEquals("주말 등산 모임", group.roomKey)

        val oneToOne = NotificationTitleFields(rawTitle = "친구A", convTitle = "", subText = "")
        assertEquals("친구A", oneToOne.title)
        assertEquals("친구A", oneToOne.roomKey)

        assertEquals("", NotificationTitleFields(rawTitle = "", convTitle = "", subText = "").roomKey)
    }

    // ---- 바뀐 알림 거르기 ----

    @Test
    fun hasSameRoom_newMessageFromAnotherSenderInSameGroup_isReplacement() {
        val removed = NotificationTitleFields(rawTitle = "보낸사람B", convTitle = "주말 등산 모임", subText = "")
        val reposted = NotificationTitleFields(rawTitle = "보낸사람C", convTitle = "주말 등산 모임", subText = "")
        assertTrue(KakaoNotificationRules.hasSameRoom(removed.roomKey, listOf("친구A", reposted.roomKey)))
    }

    @Test
    fun hasSameRoom_noNotificationOfThatRoom_isRead() {
        assertFalse(KakaoNotificationRules.hasSameRoom("주말 등산 모임", listOf("친구A")))
        assertFalse(KakaoNotificationRules.hasSameRoom("주말 등산 모임", emptyList()))
    }

    @Test
    fun hasSameRoom_emptyKeyNeverMatches() {
        assertFalse(KakaoNotificationRules.hasSameRoom("", listOf("")))
    }
}
