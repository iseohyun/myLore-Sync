package com.iseohyun.mylore.sync.ui.settings

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * 설정 맨 아래 [📡 무선 디버깅 켜기] 버튼.
 *
 * - 일반 앱은 무선 디버깅을 직접 켤 수 없습니다. PC에서 한 번 `adb shell pm grant com.iseohyun.mylore.sync
 *   android.permission.WRITE_SECURE_SETTINGS`로 권한을 주면 `adb_wifi_enabled`를 바로 켭니다 (Android 11+).
 * - 권한이 없거나 켜지 못하면 무선 디버깅 화면(개발자 옵션 안)을 엽니다. 그 화면을 바로 열지 못하는 휴대폰은 개발자 옵션을 엽니다.
 */
object WirelessDebugging {
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val WIRELESS_DEBUGGING_FRAGMENT = "com.android.settings.development.WirelessDebuggingFragment"

    fun canTurnOnDirectly(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** 켜거나 설정 화면을 열고, 사용자에게 보일 한 줄을 돌려줍니다 */
    fun turnOnOrOpen(context: Context): String {
        if (canTurnOnDirectly(context)) {
            val turnedOn = runCatching {
                Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, 1)
            }.getOrDefault(false)
            if (turnedOn) {
                openWirelessDebuggingScreen(context)
                return "무선 디버깅을 켰습니다."
            }
        }
        return if (openWirelessDebuggingScreen(context)) {
            "무선 디버깅 화면입니다. 여기서 켜 주세요."
        } else {
            "설정 화면을 열 수 없습니다. 개발자 옵션이 켜져 있는지 확인해 주세요."
        }
    }

    private fun openWirelessDebuggingScreen(context: Context): Boolean {
        val direct = Intent().apply {
            component = ComponentName("com.android.settings", "com.android.settings.SubSettings")
            putExtra(":settings:show_fragment", WIRELESS_DEBUGGING_FRAGMENT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val developer = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (intent in listOf(direct, developer)) {
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }
}
