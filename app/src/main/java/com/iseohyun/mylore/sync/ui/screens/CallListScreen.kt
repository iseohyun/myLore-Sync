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
import com.iseohyun.mylore.sync.collector.CallRecordingInfo
import com.iseohyun.mylore.sync.data.prefs.AppPreferences
import com.iseohyun.mylore.sync.data.remote.dto.SyncCallLogItem
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
    var callLogs by remember { mutableStateOf<List<SyncCallLogItem>>(emptyList()) }
    var recordings by remember { mutableStateOf<List<CallRecordingInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val lastCallTs by appPreferences.lastCallTs.collectAsState(initial = 0L)

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val logCollector = CallLogCollector(context)
            callLogs = logCollector.collectCallLogsSince(0L).reversed()

            val recCollector = CallRecordingCollector()
            recordings = recCollector.scanCallRecordings()

            isLoading = false
        }
    }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA) }

    // 음성파일 중 아직 서버 기준(lastCallTs) 이후인 가장 오래된 시점
    val pendingRecordings = recordings.filter { it.recordedAt > lastCallTs }
    val oldestPendingVoiceTs = pendingRecordings.minOfOrNull { it.recordedAt }

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
            Text("통화 기록 및 녹음 동기화", fontSize = 20.sp, fontWeight = FontWeight.Bold)
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

        if (isLoading) {
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
                items(callLogs) { item ->
                    val isLogSynced = item.ts <= lastCallTs
                    // 통화 번호 또는 시간 기반 매칭 녹음 파일 탐색
                    val hasVoiceFile = recordings.any { rec ->
                        (rec.phoneNumber.isNotBlank() && item.number.contains(rec.phoneNumber)) ||
                                (Math.abs(rec.recordedAt - item.ts) < 120_000L)
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
                                    // 통화기록 전송 배지
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

                                    // 통화음성파일 배지
                                    if (hasVoiceFile) {
                                        Box(
                                            modifier = Modifier
                                                .background(Color(0xFFE1F5FE), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 5.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                "🎙️음성보유",
                                                fontSize = 10.sp,
                                                color = Color(0xFF0277BD),
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
