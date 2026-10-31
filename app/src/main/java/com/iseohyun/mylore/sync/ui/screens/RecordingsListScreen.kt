package com.iseohyun.mylore.sync.ui.screens

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
import com.iseohyun.mylore.sync.collector.CallRecordingCollector
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun RecordingsListScreen(
    appPreferences: AppPreferences? = null,
    onOpenDrawer: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val prefs = remember { appPreferences ?: AppPreferences(context) }

    // 1. 기존 캐시된 녹음 목록 즉시 표시 (파일 SHA-256 재계산 없음)
    val recordings by db.syncCacheDao().getAllRecordingsFlow().collectAsState(initial = emptyList())
    val lastRecordingTs by prefs.lastRecordingTs.collectAsState(initial = 0L)
    var isRefreshing by remember { mutableStateOf(true) }

    // 2. 백그라운드 지연 업데이트: 새로 추가되거나 수정된 녹음 파일만 해시 계산 후 반영
    LaunchedEffect(lastRecordingTs) {
        isRefreshing = true
        withContext(Dispatchers.IO) {
            CallRecordingCollector().refreshRecordingsCache(db, lastRecordingTs)
        }
        isRefreshing = false
    }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA) }
    val pendingRecordings = remember(recordings, lastRecordingTs) {
        recordings.filter { !it.isSynced && it.recordedAt > lastRecordingTs }
    }
    val oldestUnsyncedTs = remember(pendingRecordings) {
        pendingRecordings.minOfOrNull { it.recordedAt }
    }
    val oldestRecordTs = remember(recordings) {
        recordings.minOfOrNull { it.recordedAt }
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
                Spacer(modifier = Modifier.width(8.dp))
                Text("음성 및 통화 녹음 동기화", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }

            if (isRefreshing) {
                Text(
                    "🔄 추가 녹음 확인 중...",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Summary Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("🎙️ 녹음 파일 보관 현황", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("총 보유 녹음: ${recordings.size}개", fontSize = 13.sp)
                    Text(
                        "미전송: ${pendingRecordings.size}개",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (pendingRecordings.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "미전송 가장 오래된 녹음 시점: " +
                            when {
                                oldestUnsyncedTs != null -> dateFormat.format(Date(oldestUnsyncedTs))
                                oldestRecordTs != null -> "모두 전송됨 (최고령: ${dateFormat.format(Date(oldestRecordTs))})"
                                else -> "파일 없음"
                            },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (recordings.isEmpty() && isRefreshing) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (recordings.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("기기 내 발견된 녹음 파일이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(recordings, key = { it.filePath }) { rec ->
                    val isSynced = rec.isSynced || (lastRecordingTs > 0L && rec.recordedAt <= lastRecordingTs)
                    val typeLabel = when {
                        rec.parentFolderName == "TPhoneCallRecords" -> "T전화 녹음"
                        rec.parentFolderName == "Watch" -> "워치 녹음"
                        rec.contactName == "Voice Recorder" -> "음성 메모"
                        else -> "통화 녹음"
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                MaterialTheme.colorScheme.secondaryContainer,
                                                RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            typeLabel,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        if (rec.contactName.isNotBlank() && rec.contactName != "Voice Recorder") rec.contactName else rec.fileName,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        maxLines = 1
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .background(
                                            if (isSynced) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                                            RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        if (isSynced) "전송 완료" else "전송 대기",
                                        fontSize = 11.sp,
                                        color = if (isSynced) Color(0xFF2E7D32) else Color(0xFFC62828),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            if (rec.phoneNumber.isNotBlank()) {
                                Text("연결 번호: ${rec.phoneNumber}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "경로: ${rec.parentFolderName}/${rec.fileName} (${rec.fileSize / 1024} KB)",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    dateFormat.format(Date(rec.recordedAt)),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
