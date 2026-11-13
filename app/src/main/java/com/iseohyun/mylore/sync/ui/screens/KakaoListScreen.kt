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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iseohyun.mylore.sync.collector.KakaoChatFolderWatcher
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KakaoListScreen(
    appPreferences: AppPreferences? = null,
    onOpenDrawer: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }

    val roomList by db.syncCacheDao().getAllKakaoRoomsFlow().collectAsState(initial = emptyList())
    var isRefreshing by remember { mutableStateOf(true) }
    val verifiedTimeMs = remember { System.currentTimeMillis() }

    // 백그라운드 증분 갱신 + 전송성공이 확실하나 시각정보(lastSharedTime)가 없는 항목은 검증시각으로 덮어쓰기
    LaunchedEffect(Unit) {
        isRefreshing = true
        withContext(Dispatchers.IO) {
            KakaoChatFolderWatcher().refreshKakaoRoomCache(db, appPreferences)
            db.syncCacheDao().backfillMissingKakaoSharedTime(System.currentTimeMillis())
        }
        isRefreshing = false
    }

    LaunchedEffect(roomList) {
        val needsBackfill = roomList.any { (!it.isOnDevice || it.percent >= 100) && it.lastSharedTime <= 0L }
        if (needsBackfill) {
            withContext(Dispatchers.IO) {
                db.syncCacheDao().backfillMissingKakaoSharedTime(System.currentTimeMillis())
            }
        }
    }

    val activeRooms = remember(roomList) { roomList.filter { it.isOnDevice } }
    val archivedRooms = remember(roomList) { roomList.filter { !it.isOnDevice } }
    val totalCapacity = remember(roomList) { roomList.sumOf { it.totalBytes } }
    val totalSynced = remember(roomList) { roomList.sumOf { it.syncedBytes } }
    val overallPercent = remember(totalCapacity, totalSynced) {
        if (totalCapacity > 0) ((totalSynced.toDouble() / totalCapacity) * 100).toInt().coerceIn(0, 100) else 100
    }

    // 마지막 대화 일시(없으면 공유 시각) 기준으로 오늘 / 이번주 / 이번달 / 연도(2026, 2025...) 그룹화
    val timeGroups = remember(roomList) {
        groupItemsByTimeSplit(roomList) { room ->
            if (room.lastMessageTime > 0L) room.lastMessageTime else room.lastSharedTime
        }
    }
    var collapsedKeys by remember { mutableStateOf(setOf<String>()) }

    val columns = remember {
        listOf(
            TableColumnSpec("이름", 0.25f, TextAlign.Start),
            TableColumnSpec("마지막대화일시", 0.26f, TextAlign.Center),
            TableColumnSpec("PC공유", 0.23f, TextAlign.Center),
            TableColumnSpec("전송시기", 0.26f, TextAlign.Center)
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
                Text("카카오톡 대화방 동기화", fontSize = 19.sp, fontWeight = FontWeight.Bold)
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

        Spacer(modifier = Modifier.height(8.dp))

        // Summary Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("💬 카톡 대화방 요약", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        "기기보유 ${activeRooms.size}개 · 정리완료 ${archivedRooms.size}개",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { overallPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = if (overallPercent == 100) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "용량: ${KakaoChatFolderWatcher.formatFileSize(totalSynced)} / ${KakaoChatFolderWatcher.formatFileSize(totalCapacity)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "$overallPercent% 완료",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (overallPercent == 100) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        TimeSplitTabControlBar(
            groups = timeGroups,
            collapsedKeys = collapsedKeys,
            onToggleGroup = { key ->
                collapsedKeys = if (collapsedKeys.contains(key)) collapsedKeys - key else collapsedKeys + key
            },
            onExpandAll = { collapsedKeys = emptySet() },
            onCollapseAll = { collapsedKeys = timeGroups.map { it.key }.toSet() }
        )

        Spacer(modifier = Modifier.height(6.dp))

        if (roomList.isEmpty() && isRefreshing) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (roomList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "공유 가능하거나 과거 공유된 카카오톡 대화방이 없습니다.",
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

                    timeGroups.forEach { group ->
                        val isExpanded = !collapsedKeys.contains(group.key)

                        item(key = "split_${group.key}") {
                            TimeSplitSectionHeader(
                                title = group.title,
                                count = group.items.size,
                                isExpanded = isExpanded,
                                onToggle = {
                                    collapsedKeys = if (isExpanded) {
                                        collapsedKeys + group.key
                                    } else {
                                        collapsedKeys - group.key
                                    }
                                }
                            )
                        }

                        if (isExpanded) {
                            itemsIndexed(
                                items = group.items,
                                key = { _, item -> "${group.key}_${item.roomName}" }
                            ) { index, room ->
                                val isDefinitelySynced = !room.isOnDevice || room.percent >= 100 || room.lastSharedTime > 0L
                                val effectiveSharedTime = when {
                                    room.lastSharedTime > 0L -> room.lastSharedTime
                                    isDefinitelySynced -> verifiedTimeMs
                                    else -> 0L
                                }

                                val pcShareText = when {
                                    !room.isOnDevice -> "완료(정리됨)"
                                    room.percent >= 100 -> "100% (${KakaoChatFolderWatcher.formatFileSize(room.totalBytes)})"
                                    room.percent > 0 -> "${room.percent}% (${KakaoChatFolderWatcher.formatFileSize(room.syncedBytes)})"
                                    room.totalBytes > 0L -> if (effectiveSharedTime > 0L) "공유됨" else "-"
                                    else -> if (effectiveSharedTime > 0L) "완료" else "-"
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
                                        text = room.roomName.ifBlank { "-" },
                                        modifier = Modifier
                                            .weight(columns[0].weight)
                                            .padding(horizontal = 2.dp),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Text(
                                        text = formatTableDateTime(room.lastMessageTime),
                                        modifier = Modifier
                                            .weight(columns[1].weight)
                                            .padding(horizontal = 2.dp),
                                        fontSize = 11.sp,
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Text(
                                        text = pcShareText,
                                        modifier = Modifier
                                            .weight(columns[2].weight)
                                            .padding(horizontal = 2.dp),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        textAlign = TextAlign.Center,
                                        color = if (effectiveSharedTime > 0L) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Text(
                                        text = formatTableDateTime(effectiveSharedTime),
                                        modifier = Modifier
                                            .weight(columns[3].weight)
                                            .padding(horizontal = 2.dp),
                                        fontSize = 11.sp,
                                        fontWeight = if (effectiveSharedTime > 0L) FontWeight.SemiBold else FontWeight.Normal,
                                        textAlign = TextAlign.Center,
                                        color = if (effectiveSharedTime > 0L) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline
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
    }
}
