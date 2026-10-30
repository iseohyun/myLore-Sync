package com.iseohyun.mylore.sync.ui.dashboard

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.iseohyun.mylore.sync.collector.AppUsageCollector
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.local.entity.SyncLogEntity
import com.iseohyun.mylore.sync.data.prefs.AppPreferences
import com.iseohyun.mylore.sync.data.remote.dto.PairingQrData
import com.iseohyun.mylore.sync.engine.RealtimeSyncForegroundService
import com.iseohyun.mylore.sync.engine.SyncConditionMonitor
import com.iseohyun.mylore.sync.ui.businesscard.LiveCardScannerActivity
import com.iseohyun.mylore.sync.ui.onboarding.QrScannerActivity
import com.iseohyun.mylore.sync.ui.onboarding.performHandshakeAndSave
import com.iseohyun.mylore.sync.ui.screens.*
import com.iseohyun.mylore.sync.ui.settings.SettingsScreen
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DashboardScreen(
    appPreferences: AppPreferences,
    onNavigateToSettings: () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentItem by remember { mutableStateOf(DrawerItem.STATUS) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        "myLore Sync",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "스마트폰 라이프로그 동기화",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    // Upper Group
                    listOf(
                        DrawerItem.KAKAO,
                        DrawerItem.SMS,
                        DrawerItem.CALL,
                        DrawerItem.NOTIFICATION,
                        DrawerItem.RECORDINGS,
                        DrawerItem.HEALTH
                    ).forEach { item ->
                        NavigationDrawerItem(
                            label = { Text("${item.icon}  ${item.title}", fontSize = 15.sp, fontWeight = FontWeight.Medium) },
                            selected = currentItem == item,
                            onClick = {
                                currentItem = item
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    // Lower Group
                    listOf(
                        DrawerItem.BUSINESS_CARD,
                        DrawerItem.STATUS,
                        DrawerItem.SETTINGS
                    ).forEach { item ->
                        NavigationDrawerItem(
                            label = { Text("${item.icon}  ${item.title}", fontSize = 15.sp, fontWeight = FontWeight.Medium) },
                            selected = currentItem == item,
                            onClick = {
                                currentItem = item
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    ) {
        when (currentItem) {
            DrawerItem.STATUS -> {
                StatusDashboardContent(
                    appPreferences = appPreferences,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onNavigateToSettings = { currentItem = DrawerItem.SETTINGS }
                )
            }
            DrawerItem.KAKAO -> {
                KakaoListScreen(
                    appPreferences = appPreferences,
                    onOpenDrawer = { scope.launch { drawerState.open() } }
                )
            }
            DrawerItem.SMS -> {
                SmsListScreen(
                    appPreferences = appPreferences,
                    onOpenDrawer = { scope.launch { drawerState.open() } }
                )
            }
            DrawerItem.CALL -> {
                CallListScreen(
                    appPreferences = appPreferences,
                    onOpenDrawer = { scope.launch { drawerState.open() } }
                )
            }
            DrawerItem.NOTIFICATION -> {
                NotificationListScreen(
                    onOpenDrawer = { scope.launch { drawerState.open() } }
                )
            }
            DrawerItem.RECORDINGS -> {
                RecordingsListScreen(
                    onOpenDrawer = { scope.launch { drawerState.open() } }
                )
            }
            DrawerItem.HEALTH -> {
                HealthSyncScreen(
                    onOpenDrawer = { scope.launch { drawerState.open() } }
                )
            }
            DrawerItem.BUSINESS_CARD -> {
                BusinessCardWalletScreen(
                    onOpenDrawer = { scope.launch { drawerState.open() } }
                )
            }
            DrawerItem.SETTINGS -> {
                SettingsScreen(
                    appPreferences = appPreferences,
                    onBack = { currentItem = DrawerItem.STATUS },
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onUnpaired = { onNavigateToSettings() }
                )
            }
        }
    }
}

@Composable
private fun StatusDashboardContent(
    appPreferences: AppPreferences,
    onOpenDrawer: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }

    val pcIp by appPreferences.pcIp.collectAsState(initial = "")
    val pcPort by appPreferences.pcPort.collectAsState(initial = AppPreferences.DEFAULT_PORT)
    val pairingToken by appPreferences.pairingToken.collectAsState(initial = "")
    val pcName by appPreferences.pcName.collectAsState(initial = "MYLORE-PC")
    val allowedSsids by appPreferences.allowedSsids.collectAsState(initial = AppPreferences.DEFAULT_SSIDS)

    var isCharging by remember { mutableStateOf(false) }
    var currentSsid by remember { mutableStateOf("") }
    var isWifiConnected by remember { mutableStateOf(false) }
    var isPcReachable by remember { mutableStateOf(false) }

    val usageCollector = remember { AppUsageCollector(context) }
    var hasUsagePermission by remember { mutableStateOf(usageCollector.hasUsagePermission()) }

    val logs by db.syncLogDao().getRecentLogsFlow().collectAsState(initial = emptyList())
    val monitor = remember { SyncConditionMonitor(context) }
    val scope = rememberCoroutineScope()

    val qrLauncher = rememberLauncherForActivityResult(contract = ScanContract()) { result ->
        val rawContents = result.contents
        if (!rawContents.isNullOrBlank()) {
            val qrData = PairingQrData.parse(rawContents)
            if (qrData != null && qrData.token.isNotBlank() && qrData.ips.isNotEmpty()) {
                scope.launch {
                    Toast.makeText(context, "새 서버와 연결 확인 중...", Toast.LENGTH_SHORT).show()
                    val ip = qrData.ips.first()
                    val port = qrData.port
                    val token = qrData.token

                    val success = performHandshakeAndSave(
                        appPreferences = appPreferences,
                        ip = ip,
                        port = port,
                        token = token,
                        pcName = qrData.pcName,
                        ssids = qrData.ssids.toSet()
                    )
                    if (success) {
                        Toast.makeText(context, "myLore 서버 [${qrData.pcName}]와 재연결되었습니다!", Toast.LENGTH_SHORT).show()
                        val (reachable, _, _) = monitor.pingPcServer(ip, port, token)
                        isPcReachable = reachable
                    } else {
                        Toast.makeText(context, "myLore 서버 연결에 실패했습니다. PC가 실행 중인지 확인하세요.", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                Toast.makeText(context, "유효하지 않은 QR 코드입니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(pcIp, pcPort, pairingToken) {
        while (true) {
            isCharging = monitor.checkChargingStatus()
            val (wifiConn, ssid) = monitor.checkWifiInfo(allowedSsids)
            isWifiConnected = wifiConn
            currentSsid = ssid

            val (reachable, _, _) = monitor.pingPcServer(pcIp, pcPort, pairingToken)
            isPcReachable = reachable
            hasUsagePermission = usageCollector.hasUsagePermission()

            val isAllMet = (allowedSsids.contains(ssid) || (wifiConn && reachable)) && isCharging && reachable
            if (isAllMet) {
                RealtimeSyncForegroundService.startService(context)
            }

            kotlinx.coroutines.delay(3000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenDrawer) {
                    Text("☰", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text("myLore Sync", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("연결된 PC: $pcName ($pcIp:$pcPort)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            IconButton(onClick = onNavigateToSettings) {
                Text("⚙️", fontSize = 20.sp)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Usage Permission Notice Banner
        if (!hasUsagePermission) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("📱 앱 사용량 분석 공유 권한 필요", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text("사용정보 접근 허용을 켜야 앱 사용시간이 PC와 공유됩니다.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val packageIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            try {
                                context.startActivity(packageIntent)
                            } catch (_: Exception) {
                                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("권한 허용", fontSize = 12.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // 3 Conditions Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("3대 동기화 조건 실시간 상태", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(12.dp))

                StatusRow(
                    label = "1. 지정 Wi-Fi 연결",
                    value = if (isWifiConnected) "연결됨 (${currentSsid.ifBlank { "SSID 감지 중" }})" else "미연결",
                    isOk = allowedSsids.contains(currentSsid) || (isWifiConnected && isPcReachable)
                )

                Spacer(modifier = Modifier.height(8.dp))

                StatusRow(
                    label = "2. 충전 중 상태",
                    value = if (isCharging) "충전 중 (Charging)" else "충전기 미연결",
                    isOk = isCharging
                )

                Spacer(modifier = Modifier.height(8.dp))

                StatusRow(
                    label = "3. myLore 서버",
                    value = if (isPcReachable) "온라인 (Online)" else "오프라인 (클릭하여 QR 재연결)",
                    isOk = isPcReachable,
                    onClick = if (!isPcReachable) {
                        {
                            val options = ScanOptions().apply {
                                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                setPrompt("PC 화면의 myLore QR 코드를 스캔하세요")
                                setCameraId(0)
                                setBeepEnabled(true)
                                setBarcodeImageEnabled(false)
                                setCaptureActivity(QrScannerActivity::class.java)
                            }
                            qrLauncher.launch(options)
                        }
                    } else null
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    RealtimeSyncForegroundService.startService(context)
                    Toast.makeText(context, "수동 동기화를 시작합니다.", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("🔄 수동 동기화")
            }

            Button(
                onClick = {
                    val intent = Intent(context, LiveCardScannerActivity::class.java)
                    context.startActivity(intent)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("📷 실시간 누적 스캔")
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Live Log Feed
        Text("실시간 전송 로그", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("수집된 전송 로그가 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                ) {
                    items(logs) { log ->
                        LogItemRow(log)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
    isOk: Boolean,
    onClick: (() -> Unit)? = null
) {
    val clickableModifier = if (onClick != null) {
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    }

    Row(
        modifier = clickableModifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(if (isOk) Color(0xFF4CAF50) else Color(0xFFF44336), CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                value,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (!isOk && onClick != null) MaterialTheme.colorScheme.primary else Color.Unspecified
            )
        }
    }
}

@Composable
private fun LogItemRow(log: SyncLogEntity) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("[${log.type}] ${log.message}", fontSize = 13.sp)
        }
        Text(
            dateFormat.format(Date(log.timestamp)),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
