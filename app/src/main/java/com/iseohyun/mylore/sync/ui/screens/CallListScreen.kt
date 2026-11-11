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
import com.iseohyun.mylore.sync.collector.CallLogCollector
import com.iseohyun.mylore.sync.collector.CallRecordingCollector
import com.iseohyun.mylore.sync.data.local.AppDatabase
import com.iseohyun.mylore.sync.data.local.entity.CallLogCacheEntity
import com.iseohyun.mylore.sync.data.local.entity.RecordingCacheEntity
import com.iseohyun.mylore.sync.data.prefs.AppPreferences
import com.iseohyun.mylore.sync.data.remote.dto.SyncCallLogItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class CallTableRowModel(
    val key: String,
    val directionLabel: String,
    val targetLabel: String,
    val eventTs: Long,
    val durationText: String,
    val voiceText: String,
    val completedSyncTs: Long
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CallListScreen(
    appPreferences: AppPreferences,
    onOpenDrawer: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }

    val callLogs by db.syncCacheDao().getAllCallLogsFlow().collectAsState(initial = emptyList())
    val allRecordings by db.syncCacheDao().getAllRecordingsFlow().collectAsState(initial = emptyList())
    val lastCallTs by appPreferences.lastCallTs.collectAsState(initial = 0L)
    val lastRecordingTs by appPreferences.lastRecordingTs.collectAsState(initial = 0L)
    var contactMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isRefreshing by remember { mutableStateOf(true) }
    val verifiedTimeMs = remember { System.currentTimeMillis() }

    // T전화녹음(TPhoneCallRecords) 및 통화녹음만 필터링하여 통화 항목에서 통합 관리
    val callRecordings = remember(allRecordings) {
        allRecordings.filter { CallRecordingCollector.isCallRecording(it) }
    }

    LaunchedEffect(lastCallTs, lastRecordingTs) {
        isRefreshing = true
        withContext(Dispatchers.IO) {
            contactMap = loadPhoneToNameMap(context)
            CallLogCollector(context).refreshCallLogCache(db, lastCallTs)
            CallRecordingCollector().refreshRecordingsCache(db, lastRecordingTs, appPreferences)
        }
        isRefreshing = false
    }

    // 전송성공은 확실하나 시각정보(syncedAt)가 없다면 검증시각으로 DB 덮어쓰기
    LaunchedEffect(callLogs, callRecordings, lastCallTs) {
        val callNeedsBackfill = callLogs.any {
            (it.isSynced || (lastCallTs > 0L && it.ts <= lastCallTs)) && it.syncedAt <= 0L
        }
        val recNeedsBackfill = callRecordings.any { it.isSynced && it.syncedAt <= 0L }
        if (callNeedsBackfill || recNeedsBackfill) {
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                if (lastCallTs > 0L) {
                    db.syncCacheDao().markCallLogsSyncedUpTo(lastCallTs, now)
                }
                db.syncCacheDao().backfillMissingCallLogSyncedAt(now)
                db.syncCacheDao().backfillMissingRecordingSyncedAt(now)
            }
        }
    }

    val tableRows = remember(callLogs, callRecordings, contactMap, lastCallTs, verifiedTimeMs) {
        buildUnifiedCallTableRows(
            callLogs = callLogs,
            callRecordings = callRecordings,
            contactMap = contactMap,
            lastCallTs = lastCallTs,
            verifiedTimeMs = verifiedTimeMs
        )
    }

    val syncedRowCount = remember(tableRows) { tableRows.count { it.completedSyncTs > 0L } }

    val timeGroups = remember(tableRows) {
        groupItemsByTimeSplit(tableRows) { it.eventTs }
    }
    var collapsedKeys by remember { mutableStateOf(setOf<String>()) }

    val columns = remember {
        listOf(
            TableColumnSpec("송수신", 0.11f, TextAlign.Center),
            TableColumnSpec("대상", 0.22f, TextAlign.Start),
            TableColumnSpec("이벤트시기", 0.22f, TextAlign.Center),
            TableColumnSpec("통화시간", 0.14f, TextAlign.Center),
            TableColumnSpec("음성여부", 0.09f, TextAlign.Center),
            TableColumnSpec("전송완료시기", 0.22f, TextAlign.Center)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp)
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
                Text("통화 및 통화녹음(T전화) 동기화", fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "📞 통화이벤트 ${tableRows.size}건 (통화녹음 ${callRecordings.size}개 포함)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                Text(
                    "완료: ${syncedRowCount}건 · 미완료: ${tableRows.size - syncedRowCount}건",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (syncedRowCount < tableRows.size) MaterialTheme.colorScheme.error else Color(0xFF2E7D32)
                )
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

        if (tableRows.isEmpty() && isRefreshing) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (tableRows.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("기기에 통화 이력이나 통화 녹음이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                key = { _, row -> "${group.key}_${row.key}" }
                            ) { index, row ->
                                val rowBg = if (index % 2 == 0) {
                                    MaterialTheme.colorScheme.surface
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                }

                                val dirColor = when (row.directionLabel) {
                                    "송신" -> MaterialTheme.colorScheme.primary
                                    "수신" -> MaterialTheme.colorScheme.secondary
                                    "거부" -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.outline
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(rowBg)
                                        .padding(horizontal = 6.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = row.directionLabel,
                                        modifier = Modifier
                                            .weight(columns[0].weight)
                                            .padding(horizontal = 1.dp),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        color = dirColor
                                    )

                                    Text(
                                        text = row.targetLabel,
                                        modifier = Modifier
                                            .weight(columns[1].weight)
                                            .padding(horizontal = 2.dp),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Text(
                                        text = formatTableDateTime(row.eventTs),
                                        modifier = Modifier
                                            .weight(columns[2].weight)
                                            .padding(horizontal = 1.dp),
                                        fontSize = 10.sp,
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Text(
                                        text = row.durationText,
                                        modifier = Modifier
                                            .weight(columns[3].weight)
                                            .padding(horizontal = 1.dp),
                                        fontSize = 10.sp,
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

                                    Text(
                                        text = row.voiceText,
                                        modifier = Modifier
                                            .weight(columns[4].weight)
                                            .padding(horizontal = 1.dp),
                                        fontSize = 11.sp,
                                        fontWeight = if (row.voiceText != "-") FontWeight.Bold else FontWeight.Normal,
                                        textAlign = TextAlign.Center,
                                        color = if (row.voiceText != "-") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                    )

                                    Text(
                                        text = formatTableDateTime(row.completedSyncTs),
                                        modifier = Modifier
                                            .weight(columns[5].weight)
                                            .padding(horizontal = 1.dp),
                                        fontSize = 10.sp,
                                        fontWeight = if (row.completedSyncTs > 0L) FontWeight.SemiBold else FontWeight.Normal,
                                        textAlign = TextAlign.Center,
                                        color = if (row.completedSyncTs > 0L) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline
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

private fun buildUnifiedCallTableRows(
    callLogs: List<CallLogCacheEntity>,
    callRecordings: List<RecordingCacheEntity>,
    contactMap: Map<String, String>,
    lastCallTs: Long,
    verifiedTimeMs: Long
): List<CallTableRowModel> {
    val rows = ArrayList<CallTableRowModel>(callLogs.size + callRecordings.size)
    val matchedRecPaths = HashSet<String>()

    for (call in callLogs) {
        val syncDto = SyncCallLogItem(
            id = call.id,
            number = call.number,
            name = call.name,
            callType = call.callType,
            durationSec = call.durationSec,
            ts = call.ts
        )
        val matchedRec = CallRecordingCollector.findMatchingCallRecording(syncDto, callRecordings)
        if (matchedRec != null) {
            matchedRecPaths.add(matchedRec.filePath)
        }

        val dirLabel = when (call.callType) {
            "outgoing" -> "송신"
            "incoming" -> "수신"
            "rejected", "missed" -> "거부"
            else -> "-"
        }

        val target = resolveContactOrPhone(
            rawPhoneOrAddress = call.number,
            cachedName = call.name.ifBlank { matchedRec?.contactName ?: "" },
            contactMap = contactMap
        )

        val durationStr = if (call.durationSec > 0L) {
            formatDurationHhMmSs(call.durationSec)
        } else {
            "00:00:00"
        }

        val isLogSynced = call.syncedAt > 0L || call.isSynced || (lastCallTs > 0L && call.ts <= lastCallTs)
        val effectiveLogSyncTs = when {
            call.syncedAt > 0L -> call.syncedAt
            isLogSynced -> verifiedTimeMs
            else -> 0L
        }

        val completedSyncTs = if (matchedRec != null) {
            val isRecSynced = matchedRec.syncedAt > 0L || matchedRec.isSynced
            val effectiveRecSyncTs = when {
                matchedRec.syncedAt > 0L -> matchedRec.syncedAt
                isRecSynced -> verifiedTimeMs
                else -> 0L
            }
            if (isLogSynced && isRecSynced) {
                maxOf(effectiveLogSyncTs, effectiveRecSyncTs)
            } else {
                0L
            }
        } else {
            effectiveLogSyncTs
        }

        rows.add(
            CallTableRowModel(
                key = "call_${call.id}",
                directionLabel = dirLabel,
                targetLabel = target,
                eventTs = call.ts,
                durationText = durationStr,
                voiceText = if (matchedRec != null) "O" else "-",
                completedSyncTs = completedSyncTs
            )
        )
    }

    for (rec in callRecordings) {
        if (matchedRecPaths.contains(rec.filePath)) continue

        val target = resolveContactOrPhone(
            rawPhoneOrAddress = rec.phoneNumber,
            cachedName = rec.contactName,
            contactMap = contactMap
        ).let { resolved ->
            if (resolved == "-") rec.fileName.ifBlank { "-" } else resolved
        }

        val isRecSynced = rec.syncedAt > 0L || rec.isSynced
        val completedSyncTs = when {
            rec.syncedAt > 0L -> rec.syncedAt
            isRecSynced -> verifiedTimeMs
            else -> 0L
        }

        rows.add(
            CallTableRowModel(
                key = "rec_${rec.filePath}",
                directionLabel = "-",
                targetLabel = target,
                eventTs = rec.recordedAt,
                durationText = "-",
                voiceText = "O",
                completedSyncTs = completedSyncTs
            )
        )
    }

    return rows.sortedByDescending { it.eventTs }
}
