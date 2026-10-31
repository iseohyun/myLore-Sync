package com.iseohyun.mylore.sync.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.util.Log
import com.iseohyun.mylore.sync.data.remote.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BatteryStatusInfo(
    val isCharging: Boolean = false,
    val batteryPct: Int = 0
) {
    /**
     * 시작 조건: 충전 중 OR 배터리 잔량 >= 80%
     * 유지 조건(히스테리시스): 비충전 상태더라도 이미 시작된 세션은 배터리 >= 75%까지 유지
     */
    fun isBatteryOk(isActiveSession: Boolean = false): Boolean {
        val threshold = if (isActiveSession) SyncConditionMonitor.BATTERY_RETENTION_THRESHOLD_PCT else SyncConditionMonitor.BATTERY_START_THRESHOLD_PCT
        return isCharging || batteryPct >= threshold
    }
}

data class SyncConditionState(
    val isWifiConnected: Boolean = false,
    val currentSsid: String = "",
    val isServerWifiMatched: Boolean = false,
    val isCharging: Boolean = false,
    val batteryPct: Int = 0,
    val isPcReachable: Boolean = false,
    val pcName: String = "",
    val lastSmsTs: Long = 0L,
    val lastCallTs: Long = 0L,
    val lastNotiTs: Long = 0L
) {
    val isBatteryConditionMet: Boolean
        get() = isCharging || batteryPct >= SyncConditionMonitor.BATTERY_START_THRESHOLD_PCT

    val isAllConditionsMet: Boolean
        get() = isBatteryConditionMet && isPcReachable
}

class SyncConditionMonitor(private val context: Context) {

    companion object {
        const val BATTERY_START_THRESHOLD_PCT = 80
        const val BATTERY_RETENTION_THRESHOLD_PCT = 75
    }

    fun checkBatteryInfo(): BatteryStatusInfo {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
        val pct = if (level >= 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else 0

        return BatteryStatusInfo(isCharging = isCharging, batteryPct = pct)
    }

    fun checkChargingStatus(): Boolean {
        return checkBatteryInfo().isCharging
    }

    fun isBatteryAllowedForSync(isActiveSession: Boolean = false): Boolean {
        return checkBatteryInfo().isBatteryOk(isActiveSession)
    }

    fun checkWifiInfo(): Pair<Boolean, String> {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return Pair(false, "")
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return Pair(false, "")

        val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        if (!isWifi) return Pair(false, "")

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val info = wifiManager.connectionInfo
        var ssid = info?.ssid?.replace("\"", "") ?: ""
        if (ssid == "<unknown ssid>") {
            ssid = ""
        }

        return Pair(true, ssid)
    }

    suspend fun pingPcServer(ip: String, port: Int, token: String): Triple<Boolean, String, Triple<Long, Long, Long>> {
        if (ip.isBlank() || token.isBlank()) return Triple(false, "", Triple(0L, 0L, 0L))

        return withContext(Dispatchers.IO) {
            try {
                val service = ApiClient.createHealthCheckService(ip, port)
                val response = service.getStatus(token)
                if (response.isSuccessful && response.body()?.ok == true) {
                    val body = response.body()!!
                    Triple(true, body.pcName, Triple(body.lastSmsTs, body.lastCallTs, body.lastNotiTs))
                } else {
                    Triple(false, "", Triple(0L, 0L, 0L))
                }
            } catch (e: Exception) {
                Log.d("SyncConditionMonitor", "PC ping failed: ${e.message}")
                Triple(false, "", Triple(0L, 0L, 0L))
            }
        }
    }
}

