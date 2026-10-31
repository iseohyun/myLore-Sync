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
import com.iseohyun.mylore.sync.collector.SmsAndMmsCollector
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SmsListScreen(
    appPreferences: AppPreferences,
    onOpenDrawer: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }

    val smsList by db.syncCacheDao().getAllSmsFlow().collectAsState(initial = emptyList())
    val lastSmsTs by appPreferences.lastSmsTs.collectAsState(initial = 0L)
    var contactMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isRefreshing by remember { mutableStateOf(true) }
    val verifiedTimeMs = remember { System.currentTimeMillis() }

    LaunchedEffect(lastSmsTs) {
        isRefreshing = true
        withContext(Dispatchers.IO) {
            contactMap = loadPhoneToNameMap(context)
            SmsAndMmsCollector(context).refreshSmsCache(db, lastSmsTs)
        }
        isRefreshing = false
    }

    // 전송성공은 확실하나 시각정보(syncedAt)가 없다면 검증시각으로 DB 덮어쓰기
    LaunchedEffect(smsList, lastSmsTs) {
        val needsBackfill = smsList.any {
            (it.isSynced || (lastSmsTs > 0L && it.ts <= lastSmsTs)) && it.syncedAt <= 0L
        }
        if (needsBackfill) {
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                if (lastSmsTs > 0L) {
                    db.syncCacheDao().markSmsSyncedUpTo(lastSmsTs, now)
                }
                db.syncCacheDao().backfillMissingSmsSyncedAt(now)
            }
        }
    }

    val pendingCount = remember(smsList, lastSmsTs) {
        smsList.count { it.syncedAt <= 0L && !it.isSynced && (lastSmsTs <= 0L || it.ts > lastSmsTs) }
    }

    val columns = remember {
        listOf(
            TableColumnSpec("송수신", 0.14f, TextAlign.Center),
            TableColumnSpec("대상", 0.30f, TextAlign.Start),
            TableColumnSpec("이벤트시기", 0.28f, TextAlign.Center),
            TableColumnSpec("전송시기", 0.28f, TextAlign.Center)
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
                Text("문자(SMS / MMS) 동기화", fontSize = 19.sp, fontWeight = FontWeight.Bold)
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
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("✉️ 총 보유 문자: ${smsList.size}건", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    "전송완료: ${smsList.size - pendingCount}건 · 미전송: ${pendingCount}건",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (pendingCount > 0) MaterialTheme.colorScheme.error else Color(0xFF2E7D32)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (smsList.isEmpty() && isRefreshing) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (smsList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("기기에 저장된 문자가 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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

                    itemsIndexed(smsList, key = { _, item -> item.id }) { index, item ->
                        val isDefinitelySynced =
                            item.syncedAt > 0L || item.isSynced || (lastSmsTs > 0L && item.ts <= lastSmsTs)
                        val effectiveSyncedAt = when {
                            item.syncedAt > 0L -> item.syncedAt
                            isDefinitelySynced -> verifiedTimeMs
                            else -> 0L
                        }

                        val dirLabel = when (item.type) {
                            "sent" -> "송신"
                            "inbox" -> "수신"
                            else -> "-"
                        }
                        val targetLabel = resolveContactOrPhone(
                            rawPhoneOrAddress = item.address,
                            contactMap = contactMap
                        )

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
                                text = dirLabel,
                                modifier = Modifier
                                    .weight(columns[0].weight)
                                    .padding(horizontal = 2.dp),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                color = if (dirLabel == "송신") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                            )

                            Text(
                                text = targetLabel,
                                modifier = Modifier
                                    .weight(columns[1].weight)
                                    .padding(horizontal = 2.dp),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Text(
                                text = formatTableDateTime(item.ts),
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
