package com.iseohyun.mylore.sync.engine

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import android.widget.Toast

object WifiConnector {

    @SuppressLint("MissingPermission")
    fun connectOrSuggestWifi(context: Context, ssid: String, password: String = "") {
        if (ssid.isBlank()) {
            Toast.makeText(context, "등록된 서버 Wi-Fi 정보가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Android 10+ (Q+) WifiNetworkSuggestion 등록
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val suggestionBuilder = WifiNetworkSuggestion.Builder()
                    .setSsid(ssid)
                if (password.isNotBlank()) {
                    suggestionBuilder.setWpa2Passphrase(password)
                }
                val suggestion = suggestionBuilder.build()
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiManager.addNetworkSuggestions(listOf(suggestion))
            }

            // 비밀번호가 있을 경우 사용자가 Wi-Fi 접속 창에서 붙여넣을 수 있도록 클립보드에 복사
            if (password.isNotBlank()) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Wi-Fi Password", password))
                Toast.makeText(context, "[$ssid] 비밀번호가 클립보드에 복사되었습니다.", Toast.LENGTH_SHORT).show()
            }

            // Wi-Fi 패널 또는 설정 화면 호출
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Intent("android.settings.panel.action.INTERNET").apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } else {
                Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallbackIntent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallbackIntent)
            } catch (_: Exception) {
                Toast.makeText(context, "Wi-Fi 설정 화면을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
