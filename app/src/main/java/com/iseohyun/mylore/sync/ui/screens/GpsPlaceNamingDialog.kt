package com.iseohyun.mylore.sync.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iseohyun.mylore.sync.data.prefs.AppPreferences
import com.iseohyun.mylore.sync.data.remote.KakaoPlaceLookup
import com.iseohyun.mylore.sync.data.remote.PlaceCandidate
import kotlinx.coroutines.launch

/**
 * GPS 체류 지점에 장소 이름을 붙이는 대화상자.
 *
 * - 이름은 직접 입력하거나, 카카오 주소·장소 조회 후보를 눌러 채운 뒤 [onSave]로 승인해야 저장됩니다.
 * - 카카오 조회는 체류 좌표를 외부(카카오)로 보내므로, 처음 한 번 안내 후 동의를 받은 뒤에만 실행합니다.
 */
@Composable
fun GpsPlaceNamingDialog(
    latitude: Double,
    longitude: Double,
    stayTimeText: String,
    initialName: String,
    prefs: AppPreferences,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    /** 이미 이름이 저장된 장소일 때만 전달 (이름 삭제) */
    onDelete: (() -> Unit)?
) {
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(initialName) }
    var hasConsent by remember { mutableStateOf(false) }
    var isAskingConsent by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<PlaceCandidate>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        hasConsent = prefs.hasKakaoPlaceLookupConsent()
    }

    fun runLookup() {
        isAskingConsent = false
        isLoading = true
        errorMessage = null
        scope.launch {
            // 이름 칸에 적어 둔 말이 있으면 검색어로 함께 사용 (예: "수영장")
            val result = KakaoPlaceLookup.lookup(latitude, longitude, keyword = name)
            candidates = result.candidates
            errorMessage = result.errorMessage
            isLoading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("장소 이름", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stayTimeText, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("이름 (예: 집, 수영장)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "'수영장', '병원'처럼 종류를 적고 찾기를 누르면 근처에서 그 말로도 찾습니다.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                if (isAskingConsent) {
                    Text(
                        "이 체류 지점의 좌표를 카카오에 보내 주소와 주변 장소 이름을 조회합니다. " +
                            "조회는 이 버튼을 누를 때만 실행되며, 한 번 동의하면 다시 묻지 않습니다.",
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                prefs.setKakaoPlaceLookupConsent(true)
                                hasConsent = true
                                runLookup()
                            }
                        }) {
                            Text("동의하고 조회")
                        }
                        OutlinedButton(onClick = { isAskingConsent = false }) {
                            Text("취소")
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = { if (hasConsent) runLookup() else isAskingConsent = true },
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🔎 카카오에서 주소·장소 찾기")
                    }
                }

                if (isLoading) {
                    Spacer(modifier = Modifier.height(10.dp))
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                }

                errorMessage?.let { message ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }

                if (candidates.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("후보를 누르면 이름에 채워집니다.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(modifier = Modifier.heightIn(max = 260.dp)) {
                        candidates.forEach { candidate ->
                            HorizontalDivider()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { name = candidate.name }
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(candidate.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(candidate.detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(name.trim()) },
                enabled = name.isNotBlank()
            ) {
                Text("저장")
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("이름 삭제", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("닫기")
                }
            }
        }
    )
}
