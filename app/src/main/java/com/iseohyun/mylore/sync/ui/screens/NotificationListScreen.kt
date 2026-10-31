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
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.local.entity.NotificationEntity
import com.iseohyun.mylore.sync.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotificationListScreen(
    appPreferences: AppPreferences? = null,
    onOpenDrawer: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val prefs = remember { appPreferences ?: AppPreferences(context) }

    val notifications by db.notificationDao().getAllNotificationsFlow().collectAsState(initial = emptyList())
    val lastNotiTs by prefs.lastNotiTs.collectAsState(initial = 0L)
    val verifiedTimeMs = remember { System.currentTimeMillis() }

    var selectedNotification by remember { mutableStateOf<Pair<NotificationEntity, Long>?>(null) }

    // 전송성공은 확실하나 시각정보(syncedAt)가 없다면 검증시각으로 DB 덮어쓰기
    LaunchedEffect(notifications, lastNotiTs) {
        val needsBackfill = notifications.any {
            (it.isSynced || (lastNotiTs > 0L && it.postedAt <= lastNotiTs)) && it.syncedAt <= 0L
        }
        if (needsBackfill) {
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                if (lastNotiTs > 0L) {
                    db.notificationDao().markNotificationsSyncedUpTo(lastNotiTs, now)
                }
                db.notificationDao().backfillMissingNotificationSyncedAt(now)
            }
        }
    }

    val pendingCount = remember(notifications, lastNotiTs) {
        notifications.count { it.syncedAt <= 0L && !it.isSynced && (lastNotiTs <= 0L || it.postedAt > lastNotiTs) }
    }

    val columns = remember {
        listOf(
            TableColumnSpec("이벤트명", 0.30f, TextAlign.Start),
            TableColumnSpec("이벤트 시기", 0.27f, TextAlign.Center),
            TableColumnSpec("전송시점", 0.27f, TextAlign.Center),
            TableColumnSpec("내용보기", 0.16f, TextAlign.Center)
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
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onOpenDrawer) {
                Text("☰", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text("스마트폰 알림 기록 동기화", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Summary Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🔔 보유 알림: ${notifications.size}건", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    "전송완료: ${notifications.size - pendingCount}건 · 미전송: ${pendingCount}건",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (pendingCount > 0) MaterialTheme.colorScheme.error else Color(0xFF2E7D32)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (notifications.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("수집된 알림이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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

                    itemsIndexed(notifications, key = { _, item -> item.id }) { index, item ->
                        val isDefinitelySynced =
                            item.syncedAt > 0L || item.isSynced || (lastNotiTs > 0L && item.postedAt <= lastNotiTs)
                        val effectiveSyncedAt = when {
                            item.syncedAt > 0L -> item.syncedAt
                            isDefinitelySynced -> verifiedTimeMs
                            else -> 0L
                        }

                        val shortApp = item.packageName.substringAfterLast(".").ifBlank { item.packageName }
                        val eventName = when {
                            item.title.isNotBlank() -> "[$shortApp] ${item.title}"
                            shortApp.isNotBlank() -> shortApp
                            else -> "-"
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
                                .padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = eventName,
                                modifier = Modifier
                                    .weight(columns[0].weight)
                                    .padding(horizontal = 2.dp),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Text(
                                text = formatTableDateTime(item.postedAt),
                                modifier = Modifier
                                    .weight(columns[1].weight)
                                    .padding(horizontal = 2.dp),
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Text(
                                text = formatTableDateTime(effectiveSyncedAt),
                                modifier = Modifier
                                    .weight(columns[2].weight)
                                    .padding(horizontal = 2.dp),
                                fontSize = 11.sp,
                                fontWeight = if (effectiveSyncedAt > 0L) FontWeight.SemiBold else FontWeight.Normal,
                                textAlign = TextAlign.Center,
                                color = if (effectiveSyncedAt > 0L) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline
                            )

                            Box(
                                modifier = Modifier
                                    .weight(columns[3].weight)
                                    .padding(horizontal = 2.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                OutlinedButton(
                                    onClick = { selectedNotification = Pair(item, effectiveSyncedAt) },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("보기", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
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

    selectedNotification?.let { (noti, syncedTs) ->
        AlertDialog(
            onDismissRequest = { selectedNotification = null },
            title = {
                Text(
                    text = noti.title.ifBlank { noti.packageName.substringAfterLast(".") }.ifBlank { "알림 상세" },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "패키지: ${noti.packageName.ifBlank { "-" }}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "이벤트 시기: ${formatFullDateTime(noti.postedAt)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "전송 시점: ${formatFullDateTime(syncedTs)}",
                        fontSize = 12.sp,
                        color = if (syncedTs > 0L) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        text = noti.text.ifBlank { "-" },
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (noti.subText.isNotBlank()) {
                        Text(
                            text = "보조 내용: ${noti.subText}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedNotification = null }) {
                    Text("닫기")
                }
            }
        )
    }
}
