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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iseohyun.mylore.sync.collector.KakaoChatFolderWatcher
import com.iseohyun.mylore.sync.collector.KakaoRoomSummary
import com.iseohyun.mylore.sync.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun KakaoListScreen(
    appPreferences: AppPreferences? = null,
    onOpenDrawer: () -> Unit
) {
    var roomList by remember { mutableStateOf<List<KakaoRoomSummary>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val watcher = KakaoChatFolderWatcher()
            roomList = watcher.scanRoomSummaries(appPreferences)
            isLoading = false
        }
    }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA) }
    val totalCapacity = remember(roomList) { roomList.sumOf { it.totalBytes } }
    val totalSynced = remember(roomList) { roomList.sumOf { it.syncedBytes } }
    val overallPercent = remember(totalCapacity, totalSynced) {
        if (totalCapacity > 0) ((totalSynced.toDouble() / totalCapacity) * 100).toInt().coerceIn(0, 100) else 100
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
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onOpenDrawer) {
                Text("☰", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("카카오톡 대화방 동기화", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Summary Card with Overall Graphic Progress
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("💬 감지된 대화방 요약", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        "${roomList.size}개 대화방",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "총 공유 대상 용량: ${KakaoChatFolderWatcher.formatFileSize(totalCapacity)}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "$overallPercent% 완료",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (overallPercent == 100) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Graphic Progress Bar for Overall Sync
                LinearProgressIndicator(
                    progress = { overallPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp)),
                    color = if (overallPercent == 100) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    "현재 공유 완료: ${KakaoChatFolderWatcher.formatFileSize(totalSynced)} / ${KakaoChatFolderWatcher.formatFileSize(totalCapacity)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (roomList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📁", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "감지된 카카오톡 대화방이 없습니다.",
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "카카오톡 대화방 > 설정 > 대화 내용 내보내기를 실행해 주세요.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(roomList) { room ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            // Room Title & Status Badge
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    room.roomName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    modifier = Modifier.weight(1f)
                                )

                                val (badgeText, badgeBg, badgeTextColor) = when {
                                    room.percent >= 100 -> Triple("✓ 100% 완료", Color(0xFFE8F5E9), Color(0xFF2E7D32))
                                    room.percent > 0 -> Triple("🔄 ${room.percent}% 진행 중", Color(0xFFE3F2FD), Color(0xFF1565C0))
                                    else -> Triple("⏳ 0% 대기", Color(0xFFFFF3E0), Color(0xFFE65100))
                                }

                                Box(
                                    modifier = Modifier
                                        .background(badgeBg, RoundedCornerShape(12.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        badgeText,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = badgeTextColor
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Capacity Measurement Details
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "📊 공유 대상 용량: ${KakaoChatFolderWatcher.formatFileSize(room.totalBytes)}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "${room.percent}%",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (room.percent >= 100) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            // Breakdown: Text vs Media
                            Text(
                                "• 텍스트: ${KakaoChatFolderWatcher.formatFileSize(room.textBytes)}  |  • 첨부 미디어: ${KakaoChatFolderWatcher.formatFileSize(room.mediaBytes)} (${room.mediaCount}개 파일)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Graphical Progress Bar
                            LinearProgressIndicator(
                                progress = { room.percent / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = if (room.percent >= 100) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "공유된 용량: ${KakaoChatFolderWatcher.formatFileSize(room.syncedBytes)} / ${KakaoChatFolderWatcher.formatFileSize(room.totalBytes)}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (room.totalBytes > room.syncedBytes && room.percent < 100) {
                                    Text(
                                        "남은 용량: ${KakaoChatFolderWatcher.formatFileSize(room.totalBytes - room.syncedBytes)}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))

                            // Last Conversation Message
                            Text(
                                "가장 마지막 대화:",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                room.lastMessage,
                                fontSize = 13.sp,
                                maxLines = 2,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Timestamps
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("마지막 대화 일시", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        dateFormat.format(Date(room.lastMessageTime)),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("PC에 공유된 마지막 순간", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        if (room.lastSharedTime > 0L) dateFormat.format(Date(room.lastSharedTime)) else "미공유",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (room.lastSharedTime > 0L) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
