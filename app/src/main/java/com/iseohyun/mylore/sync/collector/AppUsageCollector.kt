package com.iseohyun.mylore.sync.collector

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import com.iseohyun.mylore.sync.data.remote.dto.SyncAppUsageItem
import java.text.SimpleDateFormat
import java.util.*

class AppUsageCollector(private val context: Context) {

    companion object {
        private const val TAG = "AppUsageCollector"
    }

    /**
     * 사용정보 접근 허용 권한(PACKAGE_USAGE_STATS)이 승인되었는지 확인합니다.
     */
    fun hasUsagePermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * lastUsageTs 이후부터 현재까지의 일자별 앱 사용시간 및 실행횟수를 수집합니다.
     */
    @Suppress("MissingPermission")
    fun collectUsageStatsSince(lastUsageTs: Long): List<SyncAppUsageItem> {
        if (!hasUsagePermission()) {
            Log.w(TAG, "PACKAGE_USAGE_STATS permission not granted")
            return emptyList()
        }

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()

        val endTime = System.currentTimeMillis()
        // 기본적으로 최소 지난 24시간 또는 lastUsageTs부터 조회
        val startTime = if (lastUsageTs > 0) {
            lastUsageTs.coerceAtLeast(endTime - (7 * 24 * 60 * 60 * 1000L))
        } else {
            endTime - (24 * 60 * 60 * 1000L)
        }

        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        ) ?: return emptyList()

        val pm = context.packageManager
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
        val todayStr = dateFormat.format(Date(endTime))

        val resultList = mutableListOf<SyncAppUsageItem>()

        for (u in stats) {
            // 사용 시간이 1초(1000ms) 이상인 앱만 선별
            if (u.totalTimeInForeground >= 1000L) {
                val appName = try {
                    val appInfo = pm.getApplicationInfo(u.packageName, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    u.packageName
                }

                val dateStr = if (u.lastTimeUsed > 0) dateFormat.format(Date(u.lastTimeUsed)) else todayStr

                resultList.add(
                    SyncAppUsageItem(
                        date = dateStr,
                        pkg = u.packageName,
                        appName = appName,
                        totalForegroundMs = u.totalTimeInForeground,
                        launchCount = 0,
                        lastTimeUsed = u.lastTimeUsed
                    )
                )
            }
        }

        return resultList
    }
}
