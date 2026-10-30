package com.iseohyun.mylore.sync.engine

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.delay

object WifiConnector {

    private var activeNetworkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * 현재 기기가 목표 SSID에 이미 연결되어 있는지 확인합니다.
     */
    fun isConnectedToTargetSsid(context: Context, targetSsid: String): Boolean {
        if (targetSsid.isBlank()) return false
        val monitor = SyncConditionMonitor(context)
        val (isWifi, currentSsid) = monitor.checkWifiInfo()
        return isWifi && currentSsid.equals(targetSsid, ignoreCase = true)
    }

    /**
     * 백그라운드 유지용 WifiNetworkSuggestion 등록 +
     * 즉시 접속용 WifiNetworkSpecifier 요청 또는 Wi-Fi 설정 창 호출을 수행합니다.
     */
    @SuppressLint("MissingPermission")
    fun connectOrSuggestWifi(
        context: Context,
        ssid: String,
        password: String = "",
        authType: String = "WPA2",
        openSettingsPanel: Boolean = true
    ) {
        if (ssid.isBlank()) {
            Toast.makeText(context, "등록된 서버 Wi-Fi 정보가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val isWpa3 = authType.uppercase().contains("WPA3") || authType.uppercase().contains("SAE")

        try {
            // 1. Android 10+ (Q+) 백그라운드 영구 Wi-Fi 제안 (WifiNetworkSuggestion) 등록
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val suggestions = mutableListOf<WifiNetworkSuggestion>()

                // 기본 제안 (WPA2 또는 WPA3)
                val primaryBuilder = WifiNetworkSuggestion.Builder().setSsid(ssid)
                if (password.isNotBlank()) {
                    if (isWpa3) {
                        primaryBuilder.setWpa3Passphrase(password)
                    } else {
                        primaryBuilder.setWpa2Passphrase(password)
                    }
                }
                suggestions.add(primaryBuilder.build())

                // WPA2/WPA3 혼합(Transition) 공유기 호환을 위해 반대 인증 방식도 함께 제안 등록
                if (password.isNotBlank()) {
                    val fallbackBuilder = WifiNetworkSuggestion.Builder().setSsid(ssid)
                    if (isWpa3) {
                        fallbackBuilder.setWpa2Passphrase(password)
                    } else {
                        fallbackBuilder.setWpa3Passphrase(password)
                    }
                    suggestions.add(fallbackBuilder.build())
                }

                val status = wifiManager.addNetworkSuggestions(suggestions)
                Log.d("WifiConnector", "addNetworkSuggestions($ssid) result=$status")

                // 2. 현재 해당 SSID에 미연결 상태인 경우 WifiNetworkSpecifier로 OS 즉시 연결 다이얼로그 요청
                if (!isConnectedToTargetSsid(context, ssid)) {
                    requestImmediateWifiConnection(context, ssid, password, isWpa3)
                }
            }

            // 3. 비밀번호 클립보드 복사 (수동 연결 창 진입 시 원클릭 붙여넣기 편의 제공)
            if (password.isNotBlank()) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Wi-Fi Password", password))
            }

            // 4. 사용자가 명시적으로 설정 패널 오픈을 요청했거나 구버전 안드로이드인 경우
            if (openSettingsPanel) {
                Toast.makeText(
                    context,
                    if (password.isNotBlank()) "[$ssid] Wi-Fi 연결 요청 (비밀번호 클립보드 복사됨)" else "[$ssid] Wi-Fi 연결 요청",
                    Toast.LENGTH_SHORT
                ).show()

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
            }
        } catch (e: Exception) {
            Log.w("WifiConnector", "Error connecting/suggesting Wi-Fi", e)
            if (openSettingsPanel) {
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

    /**
     * Android 10+ (API 29+) WifiNetworkSpecifier를 통해 시스템에 즉시当該 SSID 접속을 요청하고,
     * 네트워크 연결 시 프로세스 소켓을 해당 Wi-Fi 네트워크에 바인딩합니다.
     */
    private fun requestImmediateWifiConnection(
        context: Context,
        ssid: String,
        password: String,
        isWpa3: Boolean
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        try {
            val connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            // 기존 콜백 정리
            activeNetworkCallback?.let {
                try {
                    connectivityManager.unregisterNetworkCallback(it)
                } catch (_: Exception) {}
            }

            val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            if (password.isNotBlank()) {
                if (isWpa3) {
                    specifierBuilder.setWpa3Passphrase(password)
                } else {
                    specifierBuilder.setWpa2Passphrase(password)
                }
            }

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifierBuilder.build())
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    Log.i("WifiConnector", "Target Wi-Fi [$ssid] connected via Specifier! Binding process to network.")
                    connectivityManager.bindProcessToNetwork(network)
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    Log.i("WifiConnector", "Specifier Wi-Fi [$ssid] lost, unbinding process network.")
                    connectivityManager.bindProcessToNetwork(null)
                }
            }

            activeNetworkCallback = callback
            connectivityManager.requestNetwork(request, callback)
        } catch (e: Exception) {
            Log.w("WifiConnector", "requestImmediateWifiConnection failed", e)
        }
    }

    /**
     * QR 스캔 시 Wi-Fi 선(先) 접속을 트리거하고, 목표 SSID 또는 사설망 전환이 완료될 때까지 대기합니다.
     */
    suspend fun ensureWifiConnectedBeforeHandshake(
        context: Context,
        targetSsid: String,
        password: String,
        authType: String = "WPA2",
        maxWaitMs: Long = 15000L
    ): Boolean {
        if (targetSsid.isBlank()) return true
        if (isConnectedToTargetSsid(context, targetSsid)) return true

        // 1. Suggestion 등록 + Specifier 즉시 연결 다이얼로그 호출
        connectOrSuggestWifi(
            context = context,
            ssid = targetSsid,
            password = password,
            authType = authType,
            openSettingsPanel = false
        )

        // 2. 최대 maxWaitMs 동안 Wi-Fi 연결 전환 폴링 대기
        val startTime = System.currentTimeMillis()
        val monitor = SyncConditionMonitor(context)
        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            val (isWifi, currentSsid) = monitor.checkWifiInfo()
            if (isWifi && (currentSsid.equals(targetSsid, ignoreCase = true) || currentSsid.isBlank())) {
                // 라우팅 테이블 안정화를 위해 짧은 대기
                delay(800)
                return true
            }
            delay(1000)
        }

        // Specifier 다이얼로그가 기기 정책으로 뜨지 않았거나 아직 미연결인 경우 인터넷 설정 패널 오픈 유도
        connectOrSuggestWifi(
            context = context,
            ssid = targetSsid,
            password = password,
            authType = authType,
            openSettingsPanel = true
        )
        return false
    }
}
