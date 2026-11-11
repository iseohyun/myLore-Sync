package com.iseohyun.mylore.sync.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.iseohyun.mylore.sync.collector.AppUsageCollector
import com.iseohyun.mylore.sync.data.prefs.AppPreferences
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    appPreferences: AppPreferences,
    onBack: () -> Unit,
    onOpenDrawer: (() -> Unit)? = null,
    onUnpaired: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pcIp by appPreferences.pcIp.collectAsState(initial = "")
    val pcPort by appPreferences.pcPort.collectAsState(initial = AppPreferences.DEFAULT_PORT)
    val pairingToken by appPreferences.pairingToken.collectAsState(initial = "")
    val serverSsid by appPreferences.serverSsid.collectAsState(initial = "")
    val serverWifiPw by appPreferences.serverWifiPw.collectAsState(initial = "")
    val googleMapsApiKey by appPreferences.googleMapsApiKey.collectAsState(initial = "")
    var apiKeyInput by remember(googleMapsApiKey) { mutableStateOf(googleMapsApiKey) }

    // 권한 실시간 체크 상태
    fun checkPermissionsState(): Map<String, Boolean> {
        val notiAllowed = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        val usageAllowed = AppUsageCollector(context).hasUsagePermission()
        val storageAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        val contactsAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val smsAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        val callAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        val locationAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bgLocationAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            locationAllowed && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            locationAllowed
        }
        val cameraAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val recordAudioAllowed = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryAllowed = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true

        return mapOf(
            "알림 접근 권한" to notiAllowed,
            "앱 사용정보 접근 권한" to usageAllowed,
            "모든 파일 접근 권한" to storageAllowed,
            "연락처(주소록) 권한" to contactsAllowed,
            "문자(SMS/MMS) 권한" to smsAllowed,
            "통화 기록 권한" to callAllowed,
            "위치(Wi-Fi 감지) 권한" to locationAllowed,
            "위치 [항상 허용] (지도앱/외출 GPS)" to bgLocationAllowed,
            "카메라(명함/QR) 권한" to cameraAllowed,
            "마이크(모바일 음성 채취) 권한" to recordAudioAllowed,
            "배터리 최적화 예외" to batteryAllowed
        )
    }

    var permStates by remember { mutableStateOf(checkPermissionsState()) }
    var showVoiceCaptureDialog by rememberSaveable { mutableStateOf(false) }

    val audioPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        permStates = checkPermissionsState()
        if (granted) {
            showVoiceCaptureDialog = true
        } else {
            Toast.makeText(context, "음성 채취를 위해 마이크 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        permStates = checkPermissionsState()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Top Header (Fixed)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onOpenDrawer != null) {
                IconButton(onClick = onOpenDrawer) {
                    Text("☰", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(4.dp))
            } else {
                IconButton(onClick = onBack) {
                    Text("⬅️", fontSize = 20.sp)
                }
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text("시스템 설정 및 권한 현황", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // Section 1: Android System Permissions Status
        Text("시스템 권한 획득 상태 및 설정", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("각 권한의 현재 획득 여부를 확인하고 원클릭으로 설정 화면을 엽니다.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                permStates.forEach { (name, isGranted) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium)

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (isGranted) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                                        RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    if (isGranted) "허용됨" else "미허용",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isGranted) Color(0xFF2E7D32) else Color(0xFFC62828)
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            OutlinedButton(
                                onClick = {
                                    openPermissionSettings(context, name)
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("열기", fontSize = 11.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("⚙️ myLore Sync 전체 앱 상세 설정 열기")
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Section 2: Google Maps SDK 설정 안내
        Text("🔑 Google Maps SDK 설정 안내", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            "공식 Google Maps SDK for Android 엔진이 활성화되어 있으며, 패키지 이름 및 SHA-1 인증서 지문 제한이 걸린 API 키와 호환됩니다.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text("등록된 Google Maps API Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "• 패키지명: com.iseohyun.mylore.sync\n" +
                    "• SHA-1: CD:E1:54:A3:B1:A3:C5:4F:AC:7E:8F:AE:F1:CD:E2:E7:53:7B:34:D7\n" +
                    "• 필수 활성화 API: Maps SDK for Android",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = {
                        scope.launch {
                            appPreferences.updateGoogleMapsApiKey(apiKeyInput)
                            Toast.makeText(context, "Google Maps API 키가 저장되었습니다.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("💾 API Key 저장")
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Section 3: Server Wi-Fi (NAT) Connection
        Text("서버 소속 Wi-Fi (NAT)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("QR 코드 페어링 시 전달받은 서버 소속 공유기(Wi-Fi) 정보입니다.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("네트워크 (SSID):", fontSize = 14.sp)
                    Text(
                        if (serverSsid.isNotBlank()) serverSsid else "미등록 (QR 스캔 필요)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (serverSsid.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (serverWifiPw.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("비밀번호:", fontSize = 14.sp)
                        Text("•••••••• (보안 저장됨)", fontSize = 14.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }

                if (serverSsid.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            com.iseohyun.mylore.sync.engine.WifiConnector.connectOrSuggestWifi(context, serverSsid, serverWifiPw)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📶 서버 Wi-Fi [$serverSsid] 연결")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Section 4: Connection & Unpair
        Text("연결 정보", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(modifier = Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("PC IP: $pcIp", fontSize = 14.sp)
                Text("포트: $pcPort", fontSize = 14.sp)
                Text("토큰: ${pairingToken.take(8)}...", fontSize = 14.sp)

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        scope.launch {
                            appPreferences.clearPairing()
                            onUnpaired()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🔓 PC 연동 해제 (Unpair)")
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Section 5: Mobile Voiceprint Capture (서버 연동 모바일 음성 채취)
        Text("모바일 음성지문 채취 (서버 연동)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            "PC 모니터에 표시된 문구를 실제 통화 자세로 읽고 서버(2종 오픈소스 CAM++ & ResNet34)로 전송합니다. 스피커 모드가 아닐 때는 화면에 볼이 닿아도 동작하지 않습니다.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Button(
                    onClick = {
                        if (pcIp.isBlank()) {
                            Toast.makeText(context, "먼저 PC 서버와 페어링이 필요합니다.", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val hasMic = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasMic) {
                            showVoiceCaptureDialog = true
                        } else {
                            audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🎙️ 모바일 음성 채취하기", fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showVoiceCaptureDialog) {
        MobileVoiceCaptureDialog(
            pcIp = pcIp,
            pcPort = pcPort,
            pairingToken = pairingToken,
            onDismiss = { showVoiceCaptureDialog = false }
        )
    }
}

private fun openPermissionSettings(context: Context, permName: String) {
    try {
        when {
            permName.contains("알림 접근") -> {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            permName.contains("앱 사용정보") -> {
                val packageIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                try {
                    context.startActivity(packageIntent)
                } catch (_: Exception) {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            }
            permName.contains("모든 파일 접근") -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } else {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                }
            }
            permName.contains("배터리") -> {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                context.startActivity(intent)
            }
            else -> {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            }
        }
    } catch (_: Exception) {
        Toast.makeText(context, "설정 화면을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
    }
}
