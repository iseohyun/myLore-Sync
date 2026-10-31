package com.iseohyun.mylore.sync.ui.screens

import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.local.entity.HealthSyncCacheEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HealthSyncScreen(
    onOpenDrawer: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val scope = rememberCoroutineScope()

    // 1. 기존 캐시된 헬스 폴더 및 과거 전송 후 삭제된 헬스 이력 즉시 표시 (0ms)
    val healthRecords by db.syncCacheDao().getAllHealthRecordsFlow().collectAsState(initial = emptyList())
    var isRefreshing by remember { mutableStateOf(true) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }

    fun refreshHealthCache() {
        isRefreshing = true
        scope.launch(Dispatchers.IO) {
            val dao = db.syncCacheDao()
            val existingMap = dao.getAllHealthRecords().associateBy { it.folderName }
            val root = File(Environment.getExternalStorageDirectory(), "Download/Samsung Health")
            val activeNames = mutableListOf<String>()
            val toUpsert = mutableListOf<HealthSyncCacheEntity>()

            if (root.exists() && root.isDirectory) {
                root.listFiles { f -> f.isDirectory }?.forEach { folder ->
                    activeNames.add(folder.name)
                    val lastMod = folder.lastModified()
                    val cached = existingMap[folder.name]

                    // 폴더 수정 시각이 동일하면 수천 개 JSON 파일 walkTopDown 재탐색 생략
                    if (cached != null && cached.lastModified == lastMod && cached.totalSizeKb > 0L) {
                        if (!cached.isOnDevice) {
                            toUpsert.add(cached.copy(isOnDevice = true))
                        }
                    } else {
                        val csvFiles = folder.listFiles { _, name -> name.endsWith(".csv") } ?: emptyArray()
                        val csvs = csvFiles.size
                        val jsons = File(folder, "jsons").walkTopDown().filter { it.extension == "json" }.count()
                        val totalSize = folder.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024

                        val latestCsvName = csvFiles.maxByOrNull { it.lastModified() }?.name ?: ""
                        val recordType = when {
                            latestCsvName.contains("exercise") -> "운동 세션 & 워치 GPS 경로"
                            latestCsvName.contains("pedometer") -> "걸음수 & 활동량 통계"
                            latestCsvName.contains("heart_rate") -> "심박수 & 생체 기록"
                            latestCsvName.contains("sleep") -> "수면 패턴 기록"
                            else -> "삼성 헬스 통합 백업 (운동/걸음/심박/GPS)"
                        }

                        toUpsert.add(
                            HealthSyncCacheEntity(
                                folderName = folder.name,
                                folderPath = folder.absolutePath,
                                latestRecordType = recordType,
                                lastDataTime = lastMod,
                                lastSharedTime = cached?.lastSharedTime ?: 0L,
                                csvCount = csvs,
                                jsonCount = jsons,
                                totalSizeKb = totalSize,
                                lastModified = lastMod,
                                isSynced = cached?.isSynced ?: false,
                                isOnDevice = true
                            )
                        )
                    }
                }
            }

            if (toUpsert.isNotEmpty()) {
                dao.upsertHealthRecords(toUpsert)
            }
            if (activeNames.isNotEmpty()) {
                dao.markInactiveHealthFoldersDeleted(activeNames)
            } else {
                dao.markAllHealthFoldersDeletedOnDevice()
            }

            withContext(Dispatchers.Main) {
                isRefreshing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshHealthCache()
    }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA) }
    val activeFolders = remember(healthRecords) { healthRecords.filter { it.isOnDevice } }
    val latestSyncedRecord = remember(healthRecords) { healthRecords.maxByOrNull { it.lastDataTime } }

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
                Spacer(modifier = Modifier.width(8.dp))
                Text("삼성 헬스 & 워치 GPS 동기화", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }

            if (isRefreshing) {
                Text(
                    "🔄 최신 확인 중...",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("🏃 삼성 헬스 데이터 보관 및 전송 상태", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(6.dp))
                if (activeFolders.isNotEmpty()) {
                    val totalCsv = activeFolders.sumOf { it.csvCount }
                    val totalJson = activeFolders.sumOf { it.jsonCount }
                    val totalMb = activeFolders.sumOf { it.totalSizeKb } / 1024
                    Text("현재 기기 내 폴더: ${activeFolders.size}개 ($totalMb MB)", fontSize = 13.sp)
                    Text("운동 및 건강 통계: CSV ${totalCsv}개, 워치 GPS/심박: JSON ${totalJson}개", fontSize = 12.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
                } else {
                    Text("현재 기기 내 대기 중인 헬스 폴더 없음 (과거 이력 ${healthRecords.size}건)", fontSize = 13.sp)
                }

                if (latestSyncedRecord != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "최신 데이터 종류: ${latestSyncedRecord.latestRecordType} (${dateFormat.format(Date(latestSyncedRecord.lastDataTime))})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (activeFolders.isNotEmpty()) {
            Button(
                onClick = { showDeleteConfirmDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isDeleting
            ) {
                Text("🗑️ 모든 헬스 데이터 전송 완료 후 기기에서 삭제 (이력 유지)")
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (healthRecords.isEmpty() && isRefreshing) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (healthRecords.isEmpty()) {
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
                items(healthRecords, key = { it.folderName }) { item ->
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
                                Text(
                                    item.folderName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (!item.isOnDevice || item.isSynced) Color(0xFFE8F5E9) else Color(0xFFE3F2FD),
                                            RoundedCornerShape(8.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        when {
                                            !item.isOnDevice -> "✓ 전송 후 정리됨"
                                            item.isSynced -> "✓ 전송 완료"
                                            else -> "${item.totalSizeKb / 1024} MB 대기"
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (!item.isOnDevice || item.isSynced) Color(0xFF2E7D32) else Color(0xFF1565C0)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "최신 파일 종류: ${item.latestRecordType}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text("• 운동 세션 및 걸음/수면 CSV: ${item.csvCount}개  |  • 워치 GPS/심박 JSON: ${item.jsonCount}개", fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "마지막 데이터 시각: ${dateFormat.format(Date(item.lastDataTime))}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (item.lastSharedTime > 0L) {
                                    Text(
                                        "공유 시각: ${dateFormat.format(Date(item.lastSharedTime))}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                            }
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
                Text("기기 내 Download/Samsung Health 폴더의 원본 파일(${activeFolders.size}개 폴더)을 삭제하여 저장공간을 확보합니다. 삭제 후에도 마지막 전송 파일 종류와 시각은 앱에 유지됩니다.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        isDeleting = true
                        scope.launch(Dispatchers.IO) {
                            val now = System.currentTimeMillis()
                            val dao = db.syncCacheDao()
                            val updated = dao.getAllHealthRecords().map {
                                it.copy(
                                    isSynced = true,
                                    isOnDevice = false,
                                    lastSharedTime = if (it.lastSharedTime > 0L) it.lastSharedTime else now
                                )
                            }
                            dao.upsertHealthRecords(updated)

                            val root = File(Environment.getExternalStorageDirectory(), "Download/Samsung Health")
                            if (root.exists()) {
                                root.deleteRecursively()
                            }
                            withContext(Dispatchers.Main) {
                                isDeleting = false
                                Toast.makeText(context, "원본 헬스 폴더가 삭제되었으며 전송 이력이 보존되었습니다.", Toast.LENGTH_SHORT).show()
                                refreshHealthCache()
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
