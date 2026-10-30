package com.iseohyun.mylore.sync

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.iseohyun.mylore.sync.data.prefs.AppPreferences
import com.iseohyun.mylore.sync.ui.dashboard.DashboardScreen
import com.iseohyun.mylore.sync.ui.onboarding.OnboardingScreen
import com.iseohyun.mylore.sync.ui.settings.SettingsScreen
import com.iseohyun.mylore.sync.ui.theme.MyLoreSyncTheme

class MainActivity : ComponentActivity() {

    private lateinit var appPreferences: AppPreferences

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        appPreferences = AppPreferences(applicationContext)

        requestRequiredPermissions()

        setContent {
            MyLoreSyncTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val isPaired by appPreferences.isPaired.collectAsState(initial = false)
                    var currentScreen by remember { mutableStateOf("main") }

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

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CAMERA
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
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
