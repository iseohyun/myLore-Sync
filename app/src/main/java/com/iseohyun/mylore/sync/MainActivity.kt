package com.iseohyun.mylore.sync

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.iseohyun.mylore.sync.collector.CallRecordingCollector
import com.iseohyun.mylore.sync.data.prefs.AppPreferences
import com.iseohyun.mylore.sync.data.remote.dto.PairingQrData
import com.iseohyun.mylore.sync.ui.dashboard.DashboardScreen
import com.iseohyun.mylore.sync.ui.onboarding.OnboardingScreen
import com.iseohyun.mylore.sync.ui.onboarding.executeQrPairingPipeline
import com.iseohyun.mylore.sync.ui.settings.SettingsScreen
import com.iseohyun.mylore.sync.ui.theme.MyLoreSyncTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var appPreferences: AppPreferences

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        com.iseohyun.mylore.sync.collector.LocationWifiCollector.ensurePassiveListenerRegistered(applicationContext)
        lifecycleScope.launch(Dispatchers.IO) {
            CallRecordingCollector.initializeActiveDirectories(appPreferences)
            com.iseohyun.mylore.sync.collector.PhotoMediaCollector.initializeActiveDirectories(appPreferences)
            val db = com.iseohyun.mylore.sync.data.local.AppDatabase.getInstance(applicationContext)
            com.iseohyun.mylore.sync.collector.GpsBackgroundProcessor.startHealthGpsSync(
                db = db,
                prefs = appPreferences,
                force = false,
                context = applicationContext
            )
            if (appPreferences.isPaired.first()) {
                com.iseohyun.mylore.sync.engine.RealtimeSyncForegroundService.startService(applicationContext)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        appPreferences = AppPreferences(applicationContext)
        com.iseohyun.mylore.sync.collector.LocationWifiCollector.ensurePassiveListenerRegistered(applicationContext)

        // 앱 초기 실행 시 후보 녹음/사진 폴더 리스트 중 실제 존재하는 폴더를 확인하여 기본 조회 폴더로 지정 및 GPS 데이터 수집
        lifecycleScope.launch(Dispatchers.IO) {
            CallRecordingCollector.initializeActiveDirectories(appPreferences)
            com.iseohyun.mylore.sync.collector.PhotoMediaCollector.initializeActiveDirectories(appPreferences)
            val db = com.iseohyun.mylore.sync.data.local.AppDatabase.getInstance(applicationContext)
            com.iseohyun.mylore.sync.collector.GpsBackgroundProcessor.startHealthGpsSync(
                db = db,
                prefs = appPreferences,
                force = false,
                context = applicationContext
            )
            if (appPreferences.isPaired.first()) {
                com.iseohyun.mylore.sync.engine.RealtimeSyncForegroundService.startService(applicationContext)
            }
        }

        requestRequiredPermissions()
        handleDeepLinkIntent(intent)

        setContent {
            MyLoreSyncTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val isPaired by appPreferences.isPaired.collectAsState(initial = false)
                    var currentScreen by rememberSaveable { mutableStateOf("main") }

                    if (!isPaired) {
                        OnboardingScreen(
                            appPreferences = appPreferences,
                            onPairingSuccess = {
                                currentScreen = "main"
                            }
                        )
                    } else {
                        when (currentScreen) {
                            "settings" -> {
                                SettingsScreen(
                                    appPreferences = appPreferences,
                                    onBack = { currentScreen = "main" },
                                    onUnpaired = { currentScreen = "main" }
                                )
                            }
                            else -> {
                                DashboardScreen(
                                    appPreferences = appPreferences,
                                    onNavigateToSettings = { currentScreen = "settings" }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLinkIntent(intent)
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        val dataUri = intent?.data ?: return
        if (dataUri.scheme == "myloresync" && dataUri.host == "pair") {
            val rawData = dataUri.getQueryParameter("data") ?: dataUri.toString()
            val qrData = PairingQrData.parse(rawData)
            if (qrData != null && qrData.token.isNotBlank() && qrData.ips.isNotEmpty()) {
                lifecycleScope.launch {
                    Toast.makeText(
                        this@MainActivity,
                        "서버 Wi-Fi [${qrData.targetSsid.ifBlank { qrData.pcName }}] 선(先) 접속 및 연동 시도 중...",
                        Toast.LENGTH_SHORT
                    ).show()

                    val success = executeQrPairingPipeline(
                        context = this@MainActivity,
                        appPreferences = appPreferences,
                        qrData = qrData
                    )
                    if (success) {
                        Toast.makeText(
                            this@MainActivity,
                            "myLore 서버 [${qrData.pcName}]와 원스톱 연결되었습니다!",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "QR 정보 저장 완료. [${qrData.targetSsid.ifBlank { "서버 Wi-Fi" }}] 연결 시 자동 연동됩니다.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.CAMERA
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        permissionLauncher.launch(permissions.toTypedArray())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }
}
