package com.iseohyun.mylore.sync.ui.dashboard

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.iseohyun.mylore.sync.collector.AppUsageCollector
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.prefs.AppPreferences
import com.iseohyun.mylore.sync.data.remote.dto.PairingQrData
import com.iseohyun.mylore.sync.engine.RealtimeSyncForegroundService
import com.iseohyun.mylore.sync.engine.SyncConditionMonitor
import com.iseohyun.mylore.sync.ui.onboarding.QrScannerActivity
import com.iseohyun.mylore.sync.ui.onboarding.executeQrPairingPipeline
import com.iseohyun.mylore.sync.ui.screens.*
import com.iseohyun.mylore.sync.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(
    appPreferences: AppPreferences,
    onNavigateToSettings: () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentItem by rememberSaveable { mutableStateOf(DrawerItem.STATUS) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // GPS(지도) 화면에서는 지도 드래그와 겹치지 않도록 "닫힌 상태"의 열기 제스처만 끕니다.
        // 메뉴가 열려 있을 때는 항상 켜서, 항목 대신 바깥 배경을 눌러도 메뉴가 닫히게 합니다.
        gesturesEnabled = currentItem != DrawerItem.GPS || drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .statusBarsPadding()
                        .verticalScroll(rememberScrollState())
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
                        DrawerItem.PHOTO,
                        DrawerItem.HEALTH,
                        DrawerItem.GPS
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

                    Spacer(modifier = Modifier.height(24.dp))
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
                    appPreferences = appPreferences,
                    onOpenDrawer = { scope.launch { drawerState.open() } }
                )
            }
            DrawerItem.RECORDINGS -> {
                RecordingsListScreen(
                    appPreferences = appPreferences,
                    onOpenDrawer = { scope.launch { drawerState.open() } }
                )
            }
            DrawerItem.PHOTO -> {
                PhotoListScreen(
                    appPreferences = appPreferences,
                    onOpenDrawer = { scope.launch { drawerState.open() } }
                )
            }
            DrawerItem.HEALTH -> {
                HealthSyncScreen(
                    onOpenDrawer = { scope.launch { drawerState.open() } }
                )
            }
            DrawerItem.GPS -> {
                HealthGpsScreen(
                    appPreferences = appPreferences,
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
    @Suppress("UNUSED_PARAMETER") onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }

    val pcIp by appPreferences.pcIp.collectAsState(initial = "")
    val pcPort by appPreferences.pcPort.collectAsState(initial = AppPreferences.DEFAULT_PORT)
    val pairingToken by appPreferences.pairingToken.collectAsState(initial = "")

    var isCharging by remember { mutableStateOf(false) }
    var batteryPct by remember { mutableStateOf(100) }
    var isPcReachable by remember { mutableStateOf(false) }
    var isSessionActive by remember { mutableStateOf(false) }
    // 첫 서버 확인이 끝나기 전에는 "서버 없음"으로 단정하지 않습니다.
    var hasCheckedOnce by remember { mutableStateOf(false) }

    val usageCollector = remember { AppUsageCollector(context) }
    var hasUsagePermission by remember { mutableStateOf(usageCollector.hasUsagePermission()) }

    val monitor = remember { SyncConditionMonitor(context) }
    val scope = rememberCoroutineScope()

    var statPeriod by rememberSaveable { mutableStateOf(StatPeriod.TODAY) }
    var categoryStats by remember { mutableStateOf<List<SyncCategoryStat>>(emptyList()) }

    val qrLauncher = rememberLauncherForActivityResult(contract = ScanContract()) { result ->
        val rawContents = result.contents
        if (!rawContents.isNullOrBlank()) {
            val qrData = PairingQrData.parse(rawContents)
            if (qrData != null && qrData.token.isNotBlank() && qrData.ips.isNotEmpty()) {
                scope.launch {
                    Toast.makeText(
                        context,
                        "서버 Wi-Fi [${qrData.targetSsid.ifBlank { qrData.pcName }}] 선(先) 접속 및 연동 시도 중...",
                        Toast.LENGTH_SHORT
                    ).show()

                    val success = executeQrPairingPipeline(
                        context = context,
                        appPreferences = appPreferences,
                        qrData = qrData
                    )
                    if (success) {
                        Toast.makeText(context, "myLore 서버 [${qrData.pcName}]와 재연결되었습니다!", Toast.LENGTH_SHORT).show()
                        isPcReachable = true
                    } else {
                        Toast.makeText(
                            context,
                            "QR 정보 저장 완료. [${qrData.targetSsid.ifBlank { "서버 Wi-Fi" }}] 연결 시 자동 연동됩니다.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } else {
                Toast.makeText(context, "유효하지 않은 QR 코드입니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 접속 상태(배터리·서버) 주기 점검 및 조건 충족 시 동기화 서비스 기동
    LaunchedEffect(pcIp, pcPort, pairingToken) {
        while (true) {
            val batteryInfo = monitor.checkBatteryInfo()
            isCharging = batteryInfo.isCharging
            batteryPct = batteryInfo.batteryPct

            val (reachable, _, _) = monitor.pingPcServer(pcIp, pcPort, pairingToken)
            isPcReachable = reachable
            hasCheckedOnce = true
            hasUsagePermission = usageCollector.hasUsagePermission()

            val batteryOk = batteryInfo.isBatteryOk(isActiveSession = isSessionActive)
            if (batteryOk && reachable) {
                if (!isSessionActive) {
                    isSessionActive = true
                    RealtimeSyncForegroundService.startService(context)
                }
            } else {
                isSessionActive = false
            }

            kotlinx.coroutines.delay(3000)
        }
    }

    // 분류별 전송 통계: 기간을 바꾸면 즉시, 이후 5초마다 갱신
    LaunchedEffect(statPeriod) {
        while (true) {
            categoryStats = loadSyncCategoryStats(context, db, appPreferences, statPeriod)
            kotlinx.coroutines.delay(5000)
        }
    }

    val batteryThreshold = if (isSessionActive) {
        SyncConditionMonitor.BATTERY_RETENTION_THRESHOLD_PCT
    } else {
        SyncConditionMonitor.BATTERY_START_THRESHOLD_PCT
    }
    val isBatteryOk = isCharging || batteryPct >= batteryThreshold
    val isServerMissing = hasCheckedOnce && !isPcReachable
    val isConnected = isPcReachable && isBatteryOk

    // 불만족 사유: 서버를 못 찾은 경우를 먼저, 그다음 배터리 조건
    val statusText = when {
        !hasCheckedOnce -> "확인 중"
        isServerMissing -> "서버 없음 · QR 스캔"
        !isBatteryOk -> "배터리 $batteryPct% (충전 필요)"
        else -> "연결됨"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // 제목줄: 삼선 메뉴 · 앱 이름 · 접속 상태(오른쪽 정렬)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onOpenDrawer) {
                Text("☰", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text("myLore Sync", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.weight(1f))
            ConnectionStatusBadge(
                isOk = isConnected,
                isPending = !hasCheckedOnce,
                text = statusText,
                onClick = if (isServerMissing) {
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

        Spacer(modifier = Modifier.height(14.dp))

        // 앱 사용량 권한 안내 (권한이 없을 때만 표시)
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

        // 분류별 전송 현황 (가로 막대 그래프)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp)
            ) {
                // 최근 전송 수 집계 기간 선택
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatPeriod.entries.forEach { period ->
                        FilterChip(
                            selected = statPeriod == period,
                            onClick = { statPeriod = period },
                            label = { Text(period.label, fontSize = 12.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // 인덱스(열 제목)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "분류",
                        modifier = Modifier.width(CATEGORY_LABEL_WIDTH),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "전송 완성률",
                        modifier = Modifier.weight(1f),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "${statPeriod.label} 전송",
                        modifier = Modifier.width(RECENT_COUNT_WIDTH),
                        fontSize = 11.sp,
                        textAlign = TextAlign.End,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))

                if (categoryStats.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        categoryStats.forEach { stat ->
                            CategoryProgressRow(stat)
                        }
                    }
                }
            }
        }
    }
}

private val CATEGORY_LABEL_WIDTH = 76.dp
private val RECENT_COUNT_WIDTH = 70.dp
private val STATUS_OK_COLOR = Color(0xFF4CAF50)
private val STATUS_BAD_COLOR = Color(0xFFF44336)
private val STATUS_PENDING_COLOR = Color(0xFF9E9E9E)

/**
 * 제목줄 오른쪽의 접속 상태 표시: 녹색 원(정상) / 빨간 원 + 불만족 사유.
 * [onClick]이 있으면(서버를 못 찾은 경우) 눌러서 QR 스캔으로 이동합니다.
 */
@Composable
private fun ConnectionStatusBadge(
    isOk: Boolean,
    isPending: Boolean,
    text: String,
    onClick: (() -> Unit)?
) {
    val dotColor = when {
        isPending -> STATUS_PENDING_COLOR
        isOk -> STATUS_OK_COLOR
        else -> STATUS_BAD_COLOR
    }
    val rowModifier = if (onClick != null) {
        Modifier
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 8.dp)
    } else {
        Modifier.padding(horizontal = 6.dp, vertical = 8.dp)
    }

    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(dotColor, CircleShape)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 분류 한 줄: 이름 · 전체 완성률 막대(보낸 수/전체 수) · 선택 기간의 전송 수 */
@Composable
private fun CategoryProgressRow(stat: SyncCategoryStat) {
    val percent = (stat.completionRatio * 100).toInt()
    val isComplete = stat.synced >= stat.total

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${stat.icon} ${stat.label}",
            modifier = Modifier.width(CATEGORY_LABEL_WIDTH),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        Column(modifier = Modifier.weight(1f)) {
            LinearProgressIndicator(
                progress = { stat.completionRatio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp)),
                color = if (isComplete) STATUS_OK_COLOR else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface,
                drawStopIndicator = {}
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "$percent%  (${stat.synced} / ${stat.total})",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "${stat.recentSynced}건",
            modifier = Modifier.width(RECENT_COUNT_WIDTH),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End
        )
    }
}
