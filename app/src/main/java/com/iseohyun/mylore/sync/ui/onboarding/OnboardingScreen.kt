package com.iseohyun.mylore.sync.ui.onboarding

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
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
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

    val qrLauncher = rememberLauncherForActivityResult(contract = ScanContract()) { result ->
        val rawContents = result.contents
        if (!rawContents.isNullOrBlank()) {
            val qrData = PairingQrData.parse(rawContents)
            if (qrData != null && qrData.token.isNotBlank() && qrData.ips.isNotEmpty()) {
                isLoading = true
                scope.launch {
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
                    isLoading = false
                    if (success) {
                        Toast.makeText(context, "PC [${qrData.pcName}]와 연결되었습니다!", Toast.LENGTH_SHORT).show()
                        onPairingSuccess()
                    } else {
                        Toast.makeText(context, "PC 서버 연결에 실패했습니다. PC가 실행 중인지 확인하세요.", Toast.LENGTH_LONG).show()
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
            Text("PC와 통신 확인 중...")
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
                scope.launch {
                    val success = performHandshakeAndSave(
                        appPreferences = appPreferences,
                        ip = ip,
                        port = port,
                        token = token
                    )
                    isLoading = false
                    if (success) {
                        Toast.makeText(context, "PC와 수동 연결되었습니다!", Toast.LENGTH_SHORT).show()
                        onPairingSuccess()
                    } else {
                        Toast.makeText(context, "입력하신 IP/포트로 PC 접속에 실패했습니다.", Toast.LENGTH_LONG).show()
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

private suspend fun performHandshakeAndSave(
    appPreferences: AppPreferences,
    ip: String,
    port: Int,
    token: String,
    pcName: String = "MYLORE-PC",
    ssids: Set<String> = emptySet()
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
                    ssids = ssids
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
