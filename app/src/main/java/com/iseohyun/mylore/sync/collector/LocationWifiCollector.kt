package com.iseohyun.mylore.sync.collector

import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.remote.dto.SyncLocationLogItem
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 하이브리드 스마트 GPS · Wi-Fi 동선 수집기
 *
 * 1) [지도 앱 동반 패시브 모드 - 배터리 소모 0%]
 *    - 네이버 지도(com.nhn.android.nmap), 구글 지도(com.google.android.apps.maps), 카카오맵(net.daum.android.map), 티맵 등이
 *      켜져 있는 동안 해당 앱이 사용하는 GPS 좌표를 PASSIVE_PROVIDER(5초 / 10m 간격)로 함께 수신합니다.
 *
 * 2) [외출 시작(Outing) 알고리즘 모드 - 초절전]
 *    - 지도 앱이 활성화되어 있지 않고 + Wi-Fi 연결이 끊어진 순간(외출 시작)에만 작동합니다.
 *    - Wi-Fi 해제 즉시 출발점 위치를 1회 기록하고, 이후 2분 간격 + 25m 이상 이동 조건으로 동선을 수집합니다.
 *    - 다시 Wi-Fi에 연결되거나 지도 앱이 켜지면 능동 외출 수집기를 즉시 휴면/해제하여 배터리를 보호합니다.
 */
class LocationWifiCollector(private val context: Context) {

    companion object {
        private const val TAG = "LocationWifiCollector"

        // 주요 지도 앱 패키지 목록 (네이버 지도, 구글 지도, 카카오맵, 티맵)
        val MAP_APP_PACKAGES = setOf(
            "com.nhn.android.nmap",           // 네이버 지도
            "com.google.android.apps.maps",   // 구글 지도
            "net.daum.android.map",           // 카카오맵
            "com.skt.tmap.ku"                 // 티맵
        )

        // 패시브 수신 주기 (지도 앱 활성화 시 촘촘한 궤적 확보: 5초 / 10m)
        private const val PASSIVE_MIN_TIME_MS = 5_000L
        private const val PASSIVE_MIN_DISTANCE_M = 10f

        // 외출 모드 능동 수집 주기 (지도 앱 비활성 & Wi-Fi 미연결 시: 2분 / 25m)
        private const val OUTING_MIN_TIME_MS = 2 * 60_000L
        private const val OUTING_MIN_DISTANCE_M = 25f

        private val isPassiveListenerRegistered = AtomicBoolean(false)
        private val isWifiCallbackRegistered = AtomicBoolean(false)
        private val isOutingActiveListenerRegistered = AtomicBoolean(false)

        @Volatile
        private var lastPassiveCollectMs: Long = 0L

        @Volatile
        private var lastPassiveFixReceivedAtMs: Long = 0L

        private var outingLocationListener: LocationListener? = null

        // GPS 위성 위치가 들어오는 동안에는 네트워크(기지국·Wi-Fi) 위치를 받지 않기 위한 기준
        private const val GPS_PRIORITY_WINDOW_MS = 15_000L

        @Volatile
        private var lastGpsFixElapsedMs: Long = 0L

        /**
         * GPS 우선 규칙: 최근 [GPS_PRIORITY_WINDOW_MS] 안에 GPS 위성 위치를 받았다면
         * 그 사이에 끼어드는 다른 제공자(network/fused)의 위치는 버립니다.
         * 내비게이션 중 매끄러운 GPS 경로 사이에 수백 m 떨어진 네트워크 위치가 섞여 지그재그가 되는 것을 막습니다.
         */
        private fun shouldDropNonGpsFix(location: Location): Boolean {
            val nowElapsed = SystemClock.elapsedRealtime()
            if (location.provider == LocationManager.GPS_PROVIDER) {
                lastGpsFixElapsedMs = nowElapsed
                return false
            }
            val last = lastGpsFixElapsedMs
            return last > 0L && nowElapsed - last < GPS_PRIORITY_WINDOW_MS
        }

        /**
         * 패시브 리스너(상시 5초/10m) 및 Wi-Fi 외출 감지 콜백을 등록합니다.
         */
        @SuppressLint("MissingPermission")
        fun ensurePassiveListenerRegistered(context: Context) {
            val appCtx = context.applicationContext
            if (!hasLocationPermission(appCtx)) return

            // 1) 패시브 리스너 등록 (네이버/구글/카카오맵 GPS 공유 수신용)
            if (isPassiveListenerRegistered.compareAndSet(false, true)) {
                try {
                    val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                    if (lm != null) {
                        val db = AppDatabase.getInstance(appCtx)
                        val passiveListener = object : LocationListener {
                            override fun onLocationChanged(location: Location) {
                                val lat = location.latitude
                                val lng = location.longitude
                                if (kotlin.math.abs(lat) < 0.0001 && kotlin.math.abs(lng) < 0.0001) return
                                if (shouldDropNonGpsFix(location)) return

                                val now = System.currentTimeMillis()
                                lastPassiveFixReceivedAtMs = now

                                val mapAppActive = isMapAppCurrentlyActive(appCtx)
                                // 지도 앱이 켜져 있고 능동 외출 리스너도 돌아가고 있다면, 능동 리스너를 잠시 꺼서 중복 배터리 소모 방지
                                if (mapAppActive && isOutingActiveListenerRegistered.get()) {
                                    stopOutingActiveTracking(appCtx, "지도 앱 활성화 감지 ➔ 패시브 동반 수신 모드로 전환")
                                }

                                val item = SyncLocationLogItem(
                                    eventType = if (mapAppActive) "gps_map_piggyback" else "gps_passive",
                                    placeLabel = if (mapAppActive) "map_app" else (location.provider ?: "passive"),
                                    wifiSsid = getConnectedWifiSsid(appCtx),
                                    wifiBssid = "",
                                    latitude = lat,
                                    longitude = lng,
                                    accuracyM = location.accuracy.toDouble(),
                                    batteryPct = getBatteryPct(appCtx),
                                    ts = location.time.takeIf { it > 0L } ?: now
                                )
                                GpsBackgroundProcessor.enqueueLocationSnapshot(db, listOf(item))
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                            override fun onProviderEnabled(provider: String) {}
                            override fun onProviderDisabled(provider: String) {}
                        }

                        if (lm.allProviders.contains(LocationManager.PASSIVE_PROVIDER)) {
                            lm.requestLocationUpdates(
                                LocationManager.PASSIVE_PROVIDER,
                                PASSIVE_MIN_TIME_MS,
                                PASSIVE_MIN_DISTANCE_M,
                                passiveListener,
                                Looper.getMainLooper()
                            )
                        }
                        Log.i(TAG, "지도 앱 연동 패시브 위치 리스너(5초/10m) 등록 완료")
                    }
                } catch (e: Exception) {
                    isPassiveListenerRegistered.set(false)
                    Log.w(TAG, "패시브 위치 리스너 등록 실패", e)
                }
            }

            // 2) Wi-Fi 연결/해제(외출 시작) 실시간 감지 콜백 등록
            registerWifiOutingMonitorIfNeeded(appCtx)

            // 3) 현재 Wi-Fi 및 지도 앱 상태에 맞춰 외출 모드 동기화
            evaluateOutingState(appCtx)

            // 4) 캐시된 최근 스냅샷 주기적 보강 (최소 2분 간격)
            val now = System.currentTimeMillis()
            if (now - lastPassiveCollectMs >= 120_000L) {
                lastPassiveCollectMs = now
                try {
                    val db = AppDatabase.getInstance(appCtx)
                    val snapshot = LocationWifiCollector(appCtx).collectCurrentSnapshot()
                    if (snapshot.isNotEmpty()) {
                        GpsBackgroundProcessor.enqueueLocationSnapshot(db, snapshot)
                    }
                } catch (_: Exception) {
                }
            }
        }

        /**
         * Wi-Fi 연결/해제를 실시간 감지하여 외출 모드를 자동 제어합니다.
         */
        private fun registerWifiOutingMonitorIfNeeded(appCtx: Context) {
            if (!isWifiCallbackRegistered.compareAndSet(false, true)) return

            try {
                val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()

                cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        Log.i(TAG, "Wi-Fi 연결 감지 (귀가/실내 진입) ➔ 도착 위치 기록 및 능동 외출 GPS 해제")
                        stopOutingActiveTracking(appCtx, "Wi-Fi 연결됨")
                        recordImmediateSnapshot(appCtx, "wifi_connect")
                    }

                    override fun onLost(network: Network) {
                        Log.i(TAG, "Wi-Fi 연결 해제 감지 (외출 시작!) ➔ 출발 위치 기록 및 외출 GPS 알고리즘 가동")
                        recordImmediateSnapshot(appCtx, "outing_start")
                        evaluateOutingState(appCtx)
                    }
                })
            } catch (e: Exception) {
                isWifiCallbackRegistered.set(false)
                Log.w(TAG, "Wi-Fi 외출 감지 콜백 등록 실패", e)
            }
        }

        /**
         * 현재 상태(Wi-Fi 연결 여부 & 네이버/구글/카카오맵 활성화 여부)를 평가하여
         * 지도 앱이 꺼져 있고 Wi-Fi가 연결되지 않은 외출 상태일 때만 저전력 능동 GPS를 켭니다.
         */
        @SuppressLint("MissingPermission")
        fun evaluateOutingState(context: Context) {
            val appCtx = context.applicationContext
            if (!hasLocationPermission(appCtx)) return

            val wifiConnected = isWifiCurrentlyConnected(appCtx)
            if (wifiConnected) {
                // 실내 Wi-Fi 연결 중이면 능동 외출 GPS 완전 종료
                stopOutingActiveTracking(appCtx, "Wi-Fi 연결 상태")
                return
            }

            val mapAppActive = isMapAppCurrentlyActive(appCtx)
            if (mapAppActive) {
                // 네이버 지도, 구글 지도, 카카오맵 등이 켜져 있으면 패시브 리스너(5초/10m)가 이미 수신 중이므로 능동 요청 생략
                stopOutingActiveTracking(appCtx, "지도 앱 실행 중 (패시브 공유 수신 중)")
                return
            }

            // Wi-Fi 미연결 + 지도 앱 비활성 ➔ 외출 시작 알고리즘 가동
            startOutingActiveTrackingIfNeeded(appCtx)
        }

        @SuppressLint("MissingPermission")
        private fun startOutingActiveTrackingIfNeeded(appCtx: Context) {
            if (!isOutingActiveListenerRegistered.compareAndSet(false, true)) return

            try {
                val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
                val db = AppDatabase.getInstance(appCtx)

                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        // 콜백 시점에 Wi-Fi에 다시 붙었거나 지도 앱이 켜졌다면 능동 추적 중단
                        if (isWifiCurrentlyConnected(appCtx) || isMapAppCurrentlyActive(appCtx)) {
                            stopOutingActiveTracking(appCtx, "상태 변경(Wi-Fi 연결 또는 지도 앱 실행)")
                            return
                        }

                        val lat = location.latitude
                        val lng = location.longitude
                        if (kotlin.math.abs(lat) < 0.0001 && kotlin.math.abs(lng) < 0.0001) return
                        if (shouldDropNonGpsFix(location)) return

                        val item = SyncLocationLogItem(
                            eventType = "gps_outing",
                            placeLabel = "outing_${location.provider ?: "Point"}",
                            wifiSsid = "",
                            wifiBssid = "",
                            latitude = lat,
                            longitude = lng,
                            accuracyM = location.accuracy.toDouble(),
                            batteryPct = getBatteryPct(appCtx),
                            ts = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
                        )
                        GpsBackgroundProcessor.enqueueLocationSnapshot(db, listOf(item))
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }

                outingLocationListener = listener

                // 1순위: Android 12+ FUSED_PROVIDER 또는 NETWORK_PROVIDER (2분 / 25m)
                val targetProviders = mutableListOf<String>()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.allProviders.contains(LocationManager.FUSED_PROVIDER)) {
                    targetProviders.add(LocationManager.FUSED_PROVIDER)
                } else if (lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                    targetProviders.add(LocationManager.NETWORK_PROVIDER)
                }

                // 2순위: 야외 이동 경로 정확도를 위한 저빈도 GPS_PROVIDER (2분 / 25m)
                if (lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                    targetProviders.add(LocationManager.GPS_PROVIDER)
                }

                for (provider in targetProviders.distinct()) {
                    try {
                        lm.requestLocationUpdates(
                            provider,
                            OUTING_MIN_TIME_MS,
                            OUTING_MIN_DISTANCE_M,
                            listener,
                            Looper.getMainLooper()
                        )
                    } catch (_: Exception) {
                    }
                }

                Log.i(TAG, "외출 동선 수집 모드 ON (Wi-Fi 미연결 & 지도 앱 비활성, 주기: 2분/25m, providers=$targetProviders)")
            } catch (e: Exception) {
                isOutingActiveListenerRegistered.set(false)
                Log.w(TAG, "외출 동선 수집 모드 시작 실패", e)
            }
        }

        private fun stopOutingActiveTracking(appCtx: Context, reason: String) {
            if (!isOutingActiveListenerRegistered.compareAndSet(true, false)) return
            try {
                val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                val listener = outingLocationListener
                if (lm != null && listener != null) {
                    lm.removeUpdates(listener)
                }
                outingLocationListener = null
                Log.i(TAG, "외출 동선 수집 모드 OFF ($reason)")
            } catch (_: Exception) {
            }
        }

        private fun recordImmediateSnapshot(appCtx: Context, reasonTag: String) {
            try {
                val db = AppDatabase.getInstance(appCtx)
                val snapshots = LocationWifiCollector(appCtx).collectCurrentSnapshot()
                if (snapshots.isNotEmpty()) {
                    val tagged = snapshots.map { it.copy(eventType = reasonTag) }
                    GpsBackgroundProcessor.enqueueLocationSnapshot(db, tagged)
                }
            } catch (_: Exception) {
            }
        }

        /**
         * 네이버 지도, 구글 지도, 카카오맵, 티맵이 현재 포그라운드에서 실행 중인지 확인합니다.
         */
        fun isMapAppCurrentlyActive(context: Context): Boolean {
            try {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                if (usm != null) {
                    val now = System.currentTimeMillis()
                    // 최근 3분 내의 포그라운드 이벤트 조회
                    val events = usm.queryEvents(now - 3 * 60_000L, now)
                    val event = UsageEvents.Event()
                    var lastForegroundPkg: String? = null
                    while (events.hasNextEvent()) {
                        events.getNextEvent(event)
                        if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                            event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                        ) {
                            lastForegroundPkg = event.packageName
                        }
                    }
                    if (lastForegroundPkg != null && lastForegroundPkg in MAP_APP_PACKAGES) {
                        return true
                    }
                }
            } catch (_: Exception) {
            }
            return false
        }

        fun isWifiCurrentlyConnected(context: Context): Boolean {
            return try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
                val activeNet = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(activeNet) ?: return false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            } catch (_: Exception) {
                false
            }
        }

        fun hasLocationPermission(context: Context): Boolean {
            val hasFine = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            return hasFine || hasCoarse
        }

        private fun getConnectedWifiSsid(context: Context): String {
            return try {
                val wifiManager =
                    context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val rawSsid = wifiManager?.connectionInfo?.ssid ?: ""
                val cleaned = rawSsid.trim().removeSurrounding("\"")
                if (cleaned.isNotBlank() && !cleaned.equals("<unknown ssid>", ignoreCase = true)) cleaned else ""
            } catch (_: Exception) {
                ""
            }
        }

        private fun getBatteryPct(context: Context): Int {
            return try {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            } catch (_: Exception) {
                -1
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun collectCurrentSnapshot(): List<SyncLocationLogItem> {
        val nowMs = System.currentTimeMillis()
        var wifiSsid = ""
        var wifiBssid = ""

        try {
            val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val info = wifiManager?.connectionInfo
            if (info != null) {
                val rawSsid = info.ssid ?: ""
                val cleaned = rawSsid.trim().removeSurrounding("\"")
                if (cleaned.isNotBlank() && !cleaned.equals("<unknown ssid>", ignoreCase = true)) {
                    wifiSsid = cleaned
                }
                val rawBssid = info.bssid ?: ""
                if (rawBssid.isNotBlank() && rawBssid != "02:00:00:00:00:00") {
                    wifiBssid = rawBssid
                }
            }
        } catch (_: Exception) {
        }

        val batteryPct = getBatteryPct(context)
        val results = mutableListOf<SyncLocationLogItem>()

        if (hasLocationPermission(context)) {
            try {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                if (lm != null) {
                    val providers = (lm.allProviders + listOf(
                        LocationManager.PASSIVE_PROVIDER,
                        LocationManager.NETWORK_PROVIDER,
                        LocationManager.GPS_PROVIDER,
                        "fused"
                    )).distinct()

                    for (p in providers) {
                        val loc = try {
                            lm.getLastKnownLocation(p)
                        } catch (_: Exception) {
                            null
                        }
                        if (loc != null) {
                            val lat = loc.latitude
                            val lng = loc.longitude
                            if (kotlin.math.abs(lat) >= 0.0001 || kotlin.math.abs(lng) >= 0.0001) {
                                val locTs = loc.time.takeIf { it > 0L } ?: nowMs
                                results.add(
                                    SyncLocationLogItem(
                                        eventType = if (wifiSsid.isNotBlank()) "wifi_connect" else "gps_passive",
                                        placeLabel = p,
                                        wifiSsid = wifiSsid,
                                        wifiBssid = wifiBssid,
                                        latitude = lat,
                                        longitude = lng,
                                        accuracyM = loc.accuracy.toDouble(),
                                        batteryPct = batteryPct,
                                        ts = locTs
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        return results.distinctBy {
            "${it.ts / 10000}_${String.format(Locale.US, "%.4f", it.latitude)}_${String.format(Locale.US, "%.4f", it.longitude)}"
        }
    }
}
