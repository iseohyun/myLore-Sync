package com.iseohyun.mylore.sync.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iseohyun.mylore.sync.collector.CallRecordingCollector
import com.iseohyun.mylore.sync.collector.KakaoChatFolderWatcher
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordingsListScreen(
    appPreferences: AppPreferences? = null,
    onOpenDrawer: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val prefs = remember { appPreferences ?: AppPreferences(context) }

    val allRecordings by db.syncCacheDao().getAllRecordingsFlow().collectAsState(initial = emptyList())
    val lastRecordingTs by prefs.lastRecordingTs.collectAsState(initial = 0L)
    var isRefreshing by remember { mutableStateOf(true) }
    val verifiedTimeMs = remember { System.currentTimeMillis() }

    // T전화녹음 및 일반 통화녹음은 '통화' 화면으로 이전되었으므로 순수 음성녹음(Voice Recorder, 워치 등)만 표시
    val voiceRecordings = remember(allRecordings) {
        allRecordings.filter { !CallRecordingCollector.isCallRecording(it) }
    }

    LaunchedEffect(lastRecordingTs) {
        isRefreshing = true
        withContext(Dispatchers.IO) {
            CallRecordingCollector().refreshRecordingsCache(db, lastRecordingTs, prefs)
        }
        isRefreshing = false
    }

    // 전송성공은 확실하나 시각정보(syncedAt)가 없다면 검증시각으로 DB 덮어쓰기
    LaunchedEffect(voiceRecordings) {
        val needsBackfill = voiceRecordings.any { it.isSynced && it.syncedAt <= 0L }
        if (needsBackfill) {
            withContext(Dispatchers.IO) {
                db.syncCacheDao().backfillMissingRecordingSyncedAt(System.currentTimeMillis())
            }
        }
    }

    val pendingCount = remember(voiceRecordings) {
        voiceRecordings.count { !it.isSynced && it.syncedAt <= 0L }
    }

    val columns = remember {
        listOf(
            TableColumnSpec("파일명(구분)", 0.36f, TextAlign.Start),
            TableColumnSpec("녹음일시", 0.25f, TextAlign.Center),
            TableColumnSpec("용량", 0.14f, TextAlign.Center),
            TableColumnSpec("전송시기", 0.25f, TextAlign.Center)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
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
                Text("음성 녹음 동기화", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }

            if (isRefreshing) {
                Text(
                    "🔄 확인 중...",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Summary Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🎙️ 보유 음성녹음: ${voiceRecordings.size}개", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(
                        "전송완료: ${voiceRecordings.size - pendingCount}개 · 미전송: ${pendingCount}개",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (pendingCount > 0) MaterialTheme.colorScheme.error else Color(0xFF2E7D32)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "※ T전화 녹음 및 통화 녹음은 [통화] 메뉴에서 통합 관리됩니다.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (voiceRecordings.isEmpty() && isRefreshing) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (voiceRecordings.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "기기 내 일반 음성 녹음 파일이 없습니다. (통화/T전화 녹음은 통화 탭 참조)",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(8.dp),
                tonalElevation = 1.dp
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    stickyHeader {
                        StickyTableHeader(columns = columns)
                    }

                    itemsIndexed(voiceRecordings, key = { _, rec -> rec.filePath }) { index, rec ->
                        val isDefinitelySynced = rec.syncedAt > 0L || rec.isSynced
                        val effectiveSyncedAt = when {
                            rec.syncedAt > 0L -> rec.syncedAt
                            isDefinitelySynced -> verifiedTimeMs
                            else -> 0L
                        }

                        val tagPrefix = when {
                            rec.contactName == "Watch Voice Memo" || rec.parentFolderName.contains("Watch", ignoreCase = true) -> "[워치] "
                            else -> "[음성] "
                        }

                        val rowBg = if (index % 2 == 0) {
                            MaterialTheme.colorScheme.surface
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(rowBg)
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (rec.fileName.isNotBlank()) "$tagPrefix${rec.fileName}" else "-",
                                modifier = Modifier
                                    .weight(columns[0].weight)
                                    .padding(horizontal = 2.dp),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Text(
                                text = formatTableDateTime(rec.recordedAt),
                                modifier = Modifier
                                    .weight(columns[1].weight)
                                    .padding(horizontal = 2.dp),
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Text(
                                text = if (rec.fileSize > 0L) KakaoChatFolderWatcher.formatFileSize(rec.fileSize) else "-",
                                modifier = Modifier
                                    .weight(columns[2].weight)
                                    .padding(horizontal = 2.dp),
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Text(
                                text = formatTableDateTime(effectiveSyncedAt),
                                modifier = Modifier
                                    .weight(columns[3].weight)
                                    .padding(horizontal = 2.dp),
                                fontSize = 11.sp,
                                fontWeight = if (effectiveSyncedAt > 0L) FontWeight.SemiBold else FontWeight.Normal,
                                textAlign = TextAlign.Center,
                                color = if (effectiveSyncedAt > 0L) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline
                            )
                        }
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}
