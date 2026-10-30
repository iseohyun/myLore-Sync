package com.iseohyun.mylore.sync.ui.screens

enum class DrawerItem(val title: String, val icon: String, val isSecondary: Boolean = false) {
    KAKAO("카톡", "💬"),
    SMS("문자", "✉️"),
    CALL("통화", "📞"),
    NOTIFICATION("알림", "🔔"),
    RECORDINGS("녹음", "🎙️"),
    HEALTH("헬스", "🏃"),
    BUSINESS_CARD("명함", "📇", isSecondary = true),
    STATUS("상태", "📊", isSecondary = true),
    SETTINGS("설정", "⚙️", isSecondary = true)
}
