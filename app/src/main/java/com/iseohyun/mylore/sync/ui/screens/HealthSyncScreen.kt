package com.iseohyun.mylore.sync.ui.screens

import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class HealthFolderInfo(
    val folder: File,
    val folderName: String,
    val csvCount: Int,
    val jsonCount: Int,
    val totalSizeKb: Long,
    val lastModified: Long
)

@Composable
fun HealthSyncScreen(
    onOpenDrawer: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var healthFolders by remember { mutableStateOf<List<HealthFolderInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }

    fun refreshHealthData() {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            val root = File(Environment.getExternalStorageDirectory(), "Download/Samsung Health")
            val list = mutableListOf<HealthFolderInfo>()
            if (root.exists() && root.isDirectory) {
                root.listFiles { f -> f.isDirectory }?.forEach { folder ->
                    val csvs = folder.listFiles { _, name -> name.endsWith(".csv") }?.size ?: 0
                    val jsons = File(folder, "jsons").walkTopDown().filter { it.extension == "json" }.count()
                    val totalSize = folder.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024

                    list.add(
                        HealthFolderInfo(
                            folder = folder,
                            folderName = folder.name,
                            csvCount = csvs,
                            jsonCount = jsons,
                            totalSizeKb = totalSize,
                            lastModified = folder.lastModified()
                        )
                    )
                }
            }
            healthFolders = list.sortedByDescending { it.lastModified }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshHealthData()
    }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onOpenDrawer) {
                Text("☰", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("삼성 헬스 & 워치 GPS 동기화", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("🏃 삼성 헬스 데이터 보관 상태", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(6.dp))
                if (healthFolders.isNotEmpty()) {
                    val totalCsv = healthFolders.sumOf { it.csvCount }
                    val totalJson = healthFolders.sumOf { it.jsonCount }
                    val totalMb = healthFolders.sumOf { it.totalSizeKb } / 1024
                    Text("전송 대기 폴더: ${healthFolders.size}개 ($totalMb MB)", fontSize = 13.sp)
                    Text("운동 및 건강 통계: CSV ${totalCsv}개, 워치 GPS/심박: JSON ${totalJson}개", fontSize = 12.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
                } else {
                    Text("현재 기기 내에 보관된 삼성 헬스 내보내기 데이터가 없습니다.", fontSize = 13.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Action Buttons: 전송 및 완료 후 삭제
        if (healthFolders.isNotEmpty()) {
            Button(
                onClick = { showDeleteConfirmDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isDeleting
            ) {
                Text("🗑️ 모든 헬스 데이터 전송 완료 후 기기에서 삭제")
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (healthFolders.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📂", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("삼성 헬스 내보내기 데이터가 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("삼성 헬스 앱 > 설정 > 개인 데이터 다운로드를 실행해 주세요.", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(healthFolders) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(item.folderName, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text("${item.totalSizeKb / 1024} MB", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("• 운동 세션 및 걸음/수면 CSV: ${item.csvCount}개", fontSize = 12.sp)
                            Text("• 워치 초단위 실시간 GPS/심박 JSON: ${item.jsonCount}개", fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "추출 시각: ${dateFormat.format(Date(item.lastModified))}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.End)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("헬스 데이터 삭제 확인") },
            text = {
                Text("기기 내 Download/Samsung Health 폴더에 보관된 모든 헬스 내보내기 파일(${healthFolders.size}개 폴더)을 삭제하여 저장공간을 확보하시겠습니까?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        isDeleting = true
                        scope.launch(Dispatchers.IO) {
                            val root = File(Environment.getExternalStorageDirectory(), "Download/Samsung Health")
                            if (root.exists()) {
                                root.deleteRecursively()
                            }
                            withContext(Dispatchers.Main) {
                                isDeleting = false
                                Toast.makeText(context, "헬스 데이터가 안전하게 삭제되었습니다.", Toast.LENGTH_SHORT).show()
                                refreshHealthData()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("삭제")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("취소")
                }
            }
        )
    }
}
