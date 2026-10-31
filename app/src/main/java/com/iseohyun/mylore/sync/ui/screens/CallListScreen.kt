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
import com.iseohyun.mylore.sync.collector.CallLogCollector
import com.iseohyun.mylore.sync.collector.CallRecordingCollector
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun CallListScreen(
    appPreferences: AppPreferences,
    onOpenDrawer: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }

    // 1. 기존 캐시된 통화 이력 및 녹음 파일 목록 즉시 렌더링 (0ms)
    val callLogs by db.syncCacheDao().getAllCallLogsFlow().collectAsState(initial = emptyList())
    val recordings by db.syncCacheDao().getAllRecordingsFlow().collectAsState(initial = emptyList())
    val lastCallTs by appPreferences.lastCallTs.collectAsState(initial = 0L)
    val lastRecordingTs by appPreferences.lastRecordingTs.collectAsState(initial = 0L)
    var isRefreshing by remember { mutableStateOf(true) }

    // 2. 백그라운드 지연 업데이트: 새로 추가된 통화 및 변경된 녹음 파일만 증분 스캔
    LaunchedEffect(lastCallTs, lastRecordingTs) {
        isRefreshing = true
        withContext(Dispatchers.IO) {
            CallLogCollector(context).refreshCallLogCache(db, lastCallTs)
            CallRecordingCollector().refreshRecordingsCache(db, lastRecordingTs)
        }
        isRefreshing = false
    }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA) }

    val pendingRecordings = remember(recordings, lastRecordingTs) {
        recordings.filter { !it.isSynced && it.recordedAt > lastRecordingTs }
    }
    val oldestPendingVoiceTs = remember(pendingRecordings) {
        pendingRecordings.minOfOrNull { it.recordedAt }
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
                Text("통화 기록 및 녹음 동기화", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }

            if (isRefreshing) {
                Text(
                    "🔄 추가 통화 확인 중...",
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
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("📞 통화 및 음성 파일 현황", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("보유 통화: ${callLogs.size}건", fontSize = 13.sp)
                    Text("녹음 파일: ${recordings.size}개", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "아직 서버로 전송하지 않은 가장 오래된 통화음성 시점: " +
                            if (oldestPendingVoiceTs != null) dateFormat.format(Date(oldestPendingVoiceTs)) else "모두 동기화됨",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (callLogs.isEmpty() && isRefreshing) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (callLogs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("기기에 통화 이력이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(callLogs, key = { it.id }) { item ->
                    val isLogSynced = item.isSynced || (lastCallTs > 0L && item.ts <= lastCallTs)
                    val matchedVoice = recordings.firstOrNull { rec ->
                        (rec.phoneNumber.isNotBlank() && item.number.contains(rec.phoneNumber)) &&
                                (Math.abs(rec.recordedAt - item.ts) < 300_000L)
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
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        when (item.callType) {
                                            "incoming" -> "📥 수신"
                                            "outgoing" -> "📤 발신"
                                            "missed" -> "⚠️ 부재중"
                                            "rejected" -> "🚫 거절"
                                            else -> "📞 통화"
                                        },
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        if (item.name.isNotBlank()) "${item.name} (${item.number})" else item.number,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                if (isLogSynced) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                                                RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 5.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            if (isLogSynced) "기록완료" else "기록대기",
                                            fontSize = 10.sp,
                                            color = if (isLogSynced) Color(0xFF2E7D32) else Color(0xFFC62828),
                                            fontWeight = FontWeight.Medium
                                        )
                                    }

                                    if (matchedVoice != null) {
                                        val isVoiceSynced = matchedVoice.isSynced || (lastRecordingTs > 0L && matchedVoice.recordedAt <= lastRecordingTs)
                                        Box(
                                            modifier = Modifier
                                                .background(
                                                    if (isVoiceSynced) Color(0xFFE1F5FE) else Color(0xFFFFF3E0),
                                                    RoundedCornerShape(4.dp)
                                                )
                                                .padding(horizontal = 5.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                if (isVoiceSynced) "🎙️음성전송됨" else "🎙️음성대기",
                                                fontSize = 10.sp,
                                                color = if (isVoiceSynced) Color(0xFF0277BD) else Color(0xFFE65100),
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("통화시간: ${item.durationSec}초", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    dateFormat.format(Date(item.ts)),
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
