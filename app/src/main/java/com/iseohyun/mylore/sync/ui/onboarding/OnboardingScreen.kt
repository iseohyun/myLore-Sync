package com.iseohyun.mylore.sync.ui.onboarding

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iseohyun.mylore.sync.data.prefs.AppPreferences
import com.iseohyun.mylore.sync.data.remote.ApiClient
import com.iseohyun.mylore.sync.data.remote.dto.PairConfirmRequest
import com.iseohyun.mylore.sync.data.remote.dto.PairingQrData
import com.iseohyun.mylore.sync.engine.WifiConnector
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OnboardingScreen(
    appPreferences: AppPreferences,
    onPairingSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showManualDialog by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var loadingMessage by remember { mutableStateOf("서버와 통신 확인 중...") }

    val qrLauncher = rememberLauncherForActivityResult(contract = ScanContract()) { result ->
        val rawContents = result.contents
        if (!rawContents.isNullOrBlank()) {
            val qrData = PairingQrData.parse(rawContents)
            if (qrData != null && qrData.token.isNotBlank() && qrData.ips.isNotEmpty()) {
                isLoading = true
                scope.launch {
                    val success = executeQrPairingPipeline(
                        context = context,
                        appPreferences = appPreferences,
                        qrData = qrData,
                        onStatusUpdate = { msg -> loadingMessage = msg }
                    )
                    isLoading = false
                    if (success) {
                        Toast.makeText(context, "myLore 서버 [${qrData.pcName}]와 연결되었습니다!", Toast.LENGTH_SHORT).show()
                        onPairingSuccess()
                    } else {
                        Toast.makeText(
                            context,
                            "QR 정보는 저장되었습니다. [${qrData.targetSsid.ifBlank { "서버 Wi-Fi" }}] 연결 시 자동 동기화됩니다.",
                            Toast.LENGTH_LONG
                        ).show()
                        onPairingSuccess()
                    }
                }
            } else {
                Toast.makeText(context, "유효하지 않은 QR 코드입니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "myLore Sync",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "PC myLore와 휴대폰을 1초 만에 연결합니다.",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (isLoading) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(loadingMessage, fontWeight = FontWeight.Medium)
        } else {
            Button(
                onClick = {
                    val options = ScanOptions().apply {
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        setPrompt("PC 화면의 myLore QR 코드를 스캔하세요")
                        setCameraId(0)
                        setBeepEnabled(true)
                        setBarcodeImageEnabled(false)
                        setCaptureActivity(QrScannerActivity::class.java)
                    }
                    qrLauncher.launch(options)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("📷 PC 화면 QR 코드로 연결", fontSize = 18.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = { showManualDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text("⌨️ 수동으로 IP / 토큰 입력하기", fontSize = 16.sp)
            }
        }
    }

    if (showManualDialog) {
        ManualPairingDialog(
            onDismiss = { showManualDialog = false },
            onConfirm = { ip, port, token ->
                showManualDialog = false
                isLoading = true
                loadingMessage = "수동 IP [$ip:$port] 연결 확인 중..."
                scope.launch {
                    val success = performHandshakeAndSave(
                        appPreferences = appPreferences,
                        ip = ip,
                        port = port,
                        token = token
                    )
                    isLoading = false
                    if (success) {
                        Toast.makeText(context, "myLore 서버와 수동 연결되었습니다!", Toast.LENGTH_SHORT).show()
                        onPairingSuccess()
                    } else {
                        Toast.makeText(context, "입력하신 IP/포트로 서버 접속에 실패했습니다.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }
}

@Composable
private fun ManualPairingDialog(
    onDismiss: () -> Unit,
    onConfirm: (ip: String, port: Int, token: String) -> Unit
) {
    var ip by remember { mutableStateOf("192.168.0.") }
    var portText by remember { mutableStateOf("18443") }
    var token by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PC 수동 연결 설정") },
        text = {
            Column {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text("PC IP 주소") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it },
                    label = { Text("포트 (기본: 18443)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("보안 토큰 (X-MyLore-Token)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val port = portText.toIntOrNull() ?: 18443
                    if (ip.isNotBlank() && token.isNotBlank()) {
                        onConfirm(ip.trim(), port, token.trim())
                    }
                }
            ) {
                Text("연결 확인")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}

/**
 * [Wi-Fi 선(先) 접속 → 핸드셰이크 자동 재시도] 원스톱 QR 페어링 파이프라인
 * 1. QR 코드 정보를 로컬 DataStore에 즉시 선저장 (네트워크가 달라도 설정 유실 방지)
 * 2. 현재 Wi-Fi가 서버 소속 SSID가 아닌 경우, WifiConnector를 먼저 호출하여 Wi-Fi 연결 수행 및 대기
 * 3. QR의 모든 후보 IP(ips)를 순회하며 최대 15초간 핸드셰이크 자동 재시도
 */
internal suspend fun executeQrPairingPipeline(
    context: Context,
    appPreferences: AppPreferences,
    qrData: PairingQrData,
    onStatusUpdate: (String) -> Unit = {}
): Boolean {
    val serverSsid = qrData.targetSsid
    val serverWifiPw = qrData.targetWifiPw
    val serverWifiAuth = qrData.targetWifiAuth
    val candidateIps = qrData.ips.filter { it.isNotBlank() }
    val port = qrData.port
    val token = qrData.token

    if (candidateIps.isEmpty() || token.isBlank()) return false

    // Step 1: QR에서 획득한 페어링 정보 및 Wi-Fi 자격 증명을 먼저 저장
    appPreferences.savePairingInfo(
        ip = candidateIps.first(),
        port = port,
        token = token,
        pcName = qrData.pcName,
        serverSsid = serverSsid,
        serverWifiPw = serverWifiPw
    )

    // Step 2: Wi-Fi 선(先) 접속 (다른 Wi-Fi 또는 모바일 데이터 상태일 때 먼저 서버 NAT Wi-Fi로 전환)
    if (serverSsid.isNotBlank() && !WifiConnector.isConnectedToTargetSsid(context, serverSsid)) {
        onStatusUpdate("서버 소속 Wi-Fi [$serverSsid] 선(先) 접속 중...")
        WifiConnector.ensureWifiConnectedBeforeHandshake(
            context = context,
            targetSsid = serverSsid,
            password = serverWifiPw,
            authType = serverWifiAuth,
            maxWaitMs = 12000L
        )
    }

    // Step 3: 핸드셰이크 자동 재시도 (Wi-Fi 전환 직후 DHCP/라우팅 지연 및 다중 IP 대응)
    val maxAttempts = 8
    for (attempt in 1..maxAttempts) {
        onStatusUpdate("myLore 서버 [${qrData.pcName}] 핸드셰이크 연결 시도 중 ($attempt/$maxAttempts)...")
        for (ip in candidateIps) {
            val ok = performHandshakeAndSave(
                appPreferences = appPreferences,
                ip = ip,
                port = port,
                token = token,
                pcName = qrData.pcName,
                serverSsid = serverSsid,
                serverWifiPw = serverWifiPw
            )
            if (ok) {
                return true
            }
        }
        if (attempt < maxAttempts) {
            delay(1500)
        }
    }

    return false
}

internal suspend fun performHandshakeAndSave(
    appPreferences: AppPreferences,
    ip: String,
    port: Int,
    token: String,
    pcName: String = "MYLORE-PC",
    serverSsid: String = "",
    serverWifiPw: String = ""
): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val api = ApiClient.createHealthCheckService(ip, port)
            val statusRes = api.getStatus(token)
            if (statusRes.isSuccessful && statusRes.body()?.ok == true) {
                val realPcName = statusRes.body()?.pcName?.ifBlank { pcName } ?: pcName

                // Send pair confirm
                val confirmReq = PairConfirmRequest(
                    deviceName = Build.MODEL,
                    model = Build.PRODUCT,
                    androidVersion = Build.VERSION.RELEASE,
                    appVersion = "1.0.0"
                )
                api.pairConfirm(token, confirmReq)

                appPreferences.savePairingInfo(
                    ip = ip,
                    port = port,
                    token = token,
                    pcName = realPcName,
                    serverSsid = serverSsid,
                    serverWifiPw = serverWifiPw
                )
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
